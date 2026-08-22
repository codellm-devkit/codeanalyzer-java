package com.ibm.cldk.syntactic_analysis;

import com.ibm.cldk.schema.CanId;
import com.ibm.cldk.schema.JBodyNode;
import com.ibm.cldk.schema.JCallEdge;
import com.ibm.cldk.schema.JCallable;
import com.ibm.cldk.schema.JExternalSymbol;
import com.ibm.cldk.schema.JModule;
import com.ibm.cldk.schema.JType;
import com.ibm.cldk.utils.Log;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The L2 pass: a pure walk over the emitted L1 tree that backfills each resolved {@code call} node's
 * {@code callee}, accumulates the {@code declared} {@code call_graph} edges, and homes out-of-project
 * targets as {@code external_symbols} (§4).
 *
 * <p>It consumes only the tree. Each call site carries a declaring-type <em>binary name</em> hint (an
 * L1 refinement); this pass builds a type index from the tree keyed by that same binary name, then for
 * each site maps the hint through the index — a hit composes an in-project callable id, a miss homes an
 * external symbol. Deriving the index from the payload rather than registering it during L1 keeps L1
 * free of cross-module state and makes a warm-cache run compute exactly what a cold run does.
 *
 * <p>Resolution order, first match wins (anonymous-creation node identity — the {@code can://}-prefixed
 * hint — is a later addition; today's hints are all binary type names or absent):
 *
 * <ol>
 *   <li><b>In-project hit.</b> The hint's binary name is an in-project type <em>and</em> the composed
 *       callable id is present in the tree → that callable's id. A {@code declared} edge.
 *   <li><b>Signature-miss.</b> The type is in-project but no such callable exists → nothing (homing it
 *       external would misclassify an in-project type; naming an absent callable would dangle).
 *   <li><b>External.</b> The type is not in the project → an {@code @external} id; register the symbol.
 *   <li><b>Unresolved.</b> No hint (the site did not resolve) → nothing; {@code callee} stays absent.
 * </ol>
 */
public final class L2CallGraph {

    private L2CallGraph() {}

    /** The application-scope overlays the pass produces, alongside the {@code callee} mutations. */
    public static final class Result {
        private final List<JCallEdge> callGraph;
        private final Map<String, JExternalSymbol> externalSymbols;

        Result(List<JCallEdge> callGraph, Map<String, JExternalSymbol> externalSymbols) {
            this.callGraph = callGraph;
            this.externalSymbols = externalSymbols;
        }

        public List<JCallEdge> callGraph() {
            return callGraph;
        }

        public Map<String, JExternalSymbol> externalSymbols() {
            return externalSymbols;
        }
    }

    /**
     * Backfill {@code callee} on every resolvable {@code call} node of {@code modules} (mutating them
     * in place) and return the {@code declared} edges and external symbols the sites imply.
     */
    public static Result build(String appName, Map<String, JModule> modules) {
        // The type index (named types only) and the set of every callable id in the tree. The latter
        // turns both the no-dangling invariant and the signature-miss case into O(1) membership checks.
        Map<String, String> typeIdByBinaryName = new HashMap<>();
        Set<String> callableIds = new HashSet<>();
        for (JModule module : modules.values()) {
            String pkg = module.getPackageName();
            for (Map.Entry<String, JType> entry : module.getTypes().entrySet()) {
                if (entry.getKey().startsWith("$")) {
                    continue; // synthetic body types have no computable binary name (never at top level)
                }
                indexNamedTypes(binaryName(pkg, entry.getKey()), entry.getValue(), typeIdByBinaryName);
            }
            module.getTypes().values().forEach(type -> collectCallableIds(type, callableIds));
        }

        // Sorted maps so the output is deterministic regardless of walk order: edges by (src, dst),
        // external symbols by key.
        Map<String, Map<String, Integer>> weightBySrcDst = new TreeMap<>();
        Map<String, JExternalSymbol> externalSymbols = new TreeMap<>();
        for (JModule module : modules.values()) {
            for (JType type : module.getTypes().values()) {
                backfillTree(type, appName, typeIdByBinaryName, callableIds, weightBySrcDst, externalSymbols);
            }
        }

        List<JCallEdge> callGraph = new ArrayList<>();
        for (Map.Entry<String, Map<String, Integer>> srcEntry : weightBySrcDst.entrySet()) {
            for (Map.Entry<String, Integer> dstEntry : srcEntry.getValue().entrySet()) {
                JCallEdge edge = new JCallEdge();
                edge.setSrc(srcEntry.getKey());
                edge.setDst(dstEntry.getKey());
                edge.setProv(List.of("declared"));
                edge.setWeight(dstEntry.getValue());
                callGraph.add(edge);
            }
        }
        Log.debug("L2 call graph: " + callGraph.size() + " declared edge(s), "
                + externalSymbols.size() + " external symbol(s)");
        return new Result(callGraph, externalSymbols);
    }

    /** {@code <package>.<Simple>}, or the bare simple name in the default package. */
    private static String binaryName(String packageName, String simpleName) {
        return packageName == null || packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
    }

    /**
     * Index a named type and its named descendants by binary name. {@code $anon$N}/{@code $enum$NAME}
     * bodies are skipped — javac numbers those per outer class while L1 numbers them per scope, so they
     * have no binary name that would join; they are reached only by node identity, not this index.
     */
    private static void indexNamedTypes(String binaryName, JType type, Map<String, String> index) {
        index.put(binaryName, type.getId());
        for (Map.Entry<String, JType> entry : type.getTypes().entrySet()) {
            if (!entry.getKey().startsWith("$")) {
                indexNamedTypes(binaryName + "$" + entry.getKey(), entry.getValue(), index);
            }
        }
    }

    /** Every callable id reachable from {@code type} — through member, local, anonymous and enum-body types. */
    private static void collectCallableIds(JType type, Set<String> ids) {
        for (JCallable callable : type.getCallables().values()) {
            ids.add(callable.getId());
            callable.getTypes().values().forEach(local -> collectCallableIds(local, ids));
        }
        type.getTypes().values().forEach(nested -> collectCallableIds(nested, ids));
    }

    private static void backfillTree(
            JType type,
            String appName,
            Map<String, String> index,
            Set<String> callableIds,
            Map<String, Map<String, Integer>> weightBySrcDst,
            Map<String, JExternalSymbol> externalSymbols) {
        for (JCallable callable : type.getCallables().values()) {
            backfillCallable(callable, appName, index, callableIds, weightBySrcDst, externalSymbols);
            callable.getTypes().values().forEach(
                    local -> backfillTree(local, appName, index, callableIds, weightBySrcDst, externalSymbols));
        }
        type.getTypes().values().forEach(
                nested -> backfillTree(nested, appName, index, callableIds, weightBySrcDst, externalSymbols));
    }

    private static void backfillCallable(
            JCallable callable,
            String appName,
            Map<String, String> index,
            Set<String> callableIds,
            Map<String, Map<String, Integer>> weightBySrcDst,
            Map<String, JExternalSymbol> externalSymbols) {
        String src = callable.getId();
        for (JBodyNode node : callable.getBody().values()) {
            if (!"call".equals(node.getKind())) {
                continue;
            }
            String dst = resolveCallee(node, appName, index, callableIds, externalSymbols);
            if (dst == null) {
                continue; // signature-miss or unresolved: callee stays absent, no edge
            }
            node.setCallee(dst);
            weightBySrcDst.computeIfAbsent(src, k -> new TreeMap<>()).merge(dst, 1, Integer::sum);
        }
    }

    /**
     * The callee id for one {@code call} node, or {@code null} when it resolves to nothing (a
     * signature-miss or an unresolved site). Registers an external symbol as a side effect of case 3.
     */
    private static String resolveCallee(
            JBodyNode node,
            String appName,
            Map<String, String> index,
            Set<String> callableIds,
            Map<String, JExternalSymbol> externalSymbols) {
        String hint = node.getDeclaringTypeHint();
        String signature = node.getCalleeSignature();
        if (hint == null || signature == null) {
            return null; // the site did not resolve, so there is nothing to compose
        }
        String typeId = index.get(hint);
        if (typeId != null) {
            String composed = CanId.childId(typeId, signature);
            return callableIds.contains(composed) ? composed : null; // in-project hit, else signature-miss
        }
        String externalId = CanId.externalId(appName, hint, signature);
        externalSymbols.computeIfAbsent(externalId, k -> {
            JExternalSymbol symbol = new JExternalSymbol();
            symbol.setKind(node.isConstructorCall() ? "constructor" : "method");
            symbol.setSignature(signature);
            // The id carries the binary name; declaring_type is the legible dotted spelling.
            symbol.setDeclaringType(hint.replace('$', '.'));
            return symbol;
        });
        return externalId;
    }
}
