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
import java.util.TreeSet;

/**
 * The L2 pass: a walk over the emitted L1 tree that backfills each resolved {@code call} node's
 * {@code callee}, accumulates the {@code declared} {@code call_graph} edges, and homes out-of-project
 * targets as {@code external_symbols} (§4). When a WALA RTA call graph is supplied, its edges are
 * joined in as an {@code rta} overlay (§5): {@code prov} carries the set-union of the analyses that
 * attest each edge.
 *
 * <p>The declared pass consumes only the tree. Each call site carries a declaring-type <em>binary
 * name</em> hint (an L1 refinement); this pass builds a type index from the tree keyed by that same
 * binary name, then for each site maps the hint through the index — a hit composes an in-project
 * callable id, a miss homes an external symbol. Deriving the index from the payload rather than
 * registering it during L1 keeps L1 free of cross-module state and makes a warm-cache run compute
 * exactly what a cold run does.
 *
 * <p>Resolution order, first match wins. The hint is a discriminated union — a {@code can://} id when
 * L1 resolved the site locally (an anonymous creation), a binary type name otherwise:
 *
 * <ol>
 *   <li><b>Node identity.</b> The hint is a {@code can://} id (an anonymous creation, resolved by L1 to
 *       its own constructor) → that id, once confirmed present in the tree.
 *   <li><b>In-project hit.</b> The hint's binary name is an in-project type <em>and</em> the composed
 *       callable id is present in the tree → that callable's id. A {@code declared} edge.
 *   <li><b>Signature-miss.</b> The type is in-project but no such callable exists → nothing (homing it
 *       external would misclassify an in-project type; naming an absent callable would dangle).
 *   <li><b>External.</b> The type is not in the project → an {@code @external} id; register the symbol.
 *   <li><b>Unresolved.</b> No hint (the site did not resolve) → nothing; {@code callee} stays absent.
 * </ol>
 *
 * <p>The RTA overlay may add external symbols but never in-project nodes: an in-project WALA endpoint
 * that maps to no callable in the tree (a bridge method, {@code access$000}, {@code lambda$foo$0},
 * {@code $values()}, or an {@code $anon$N}-vs-{@code Outer$1} identity-join failure) is <em>dropped</em>
 * rather than fabricated, since the {@code declared} edge already attests those calls. That drop falls
 * out of the same tree-membership check the declared pass uses, so no-dangling is structural.
 */
public final class L2CallGraph {

    private L2CallGraph() {}

    /**
     * One WALA RTA call-graph edge, reduced to what the join needs and nothing WALA-specific — so the
     * join logic is unit-testable against synthetic endpoint pairs. The type names are <em>binary</em>
     * ({@code org.example.Map$Entry}) and the signatures are erased ({@code m(java.util.List)}), already
     * converted from WALA's descriptors by the adapter that produces these.
     */
    public static final class RtaEndpoint {
        private final boolean srcAppClass;
        private final String srcType;
        private final String srcSignature;
        private final boolean dstAppClass;
        private final String dstType;
        private final String dstSignature;

        public RtaEndpoint(
                boolean srcAppClass,
                String srcType,
                String srcSignature,
                boolean dstAppClass,
                String dstType,
                String dstSignature) {
            this.srcAppClass = srcAppClass;
            this.srcType = srcType;
            this.srcSignature = srcSignature;
            this.dstAppClass = dstAppClass;
            this.dstType = dstType;
            this.dstSignature = dstSignature;
        }

        @Override
        public String toString() {
            return (srcAppClass ? "app " : "lib ") + srcType + "#" + srcSignature
                    + " -> " + (dstAppClass ? "app " : "lib ") + dstType + "#" + dstSignature;
        }
    }

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

    /** Declared-only, with out-of-project targets homed as external symbols. */
    public static Result build(String appName, Map<String, JModule> modules) {
        return build(appName, modules, null, true);
    }

    /** Declared + rta overlay, with out-of-project targets homed as external symbols. */
    public static Result build(String appName, Map<String, JModule> modules, List<RtaEndpoint> rtaEdges) {
        return build(appName, modules, rtaEdges, true);
    }

    /**
     * The full pass: backfill {@code callee}, produce {@code declared} edges, optionally join a WALA RTA
     * call graph (as {@link RtaEndpoint} pairs; {@code null} for declared-only) as an {@code rta}
     * overlay, and home out-of-project targets.
     *
     * <p>{@code includeExternal} gates the external targets. When {@code false}, a call resolving outside
     * the project is dropped — no {@code callee}, no edge, no {@code external_symbols} entry — matching
     * v1's application-only call graph; the CLI defaults this off and {@code --external-calls} opts in.
     * When {@code true} (the design's §2 posture) they are homed so no edge dangles. The overlay never
     * mutates {@code callee} — that is a declared-analysis refinement.
     */
    public static Result build(
            String appName, Map<String, JModule> modules, List<RtaEndpoint> rtaEdges, boolean includeExternal) {
        // The type index (named types only) and the set of every callable id in the tree. The latter
        // turns no-dangling, the signature-miss case, and the RTA drop rules into O(1) membership checks.
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

        // Sorted maps so the output is deterministic regardless of walk order.
        Map<String, JExternalSymbol> externalSymbols = new TreeMap<>();
        Map<String, Map<String, Integer>> declaredWeights = new TreeMap<>();
        for (JModule module : modules.values()) {
            for (JType type : module.getTypes().values()) {
                backfillTree(type, appName, typeIdByBinaryName, callableIds, declaredWeights, externalSymbols,
                        includeExternal);
            }
        }

        Map<String, Map<String, Integer>> rtaWeights = new TreeMap<>();
        if (rtaEdges != null) {
            for (RtaEndpoint edge : rtaEdges) {
                joinRtaEdge(edge, appName, typeIdByBinaryName, callableIds, rtaWeights, externalSymbols,
                        includeExternal);
            }
        }

        List<JCallEdge> callGraph = mergeEdges(declaredWeights, rtaWeights);
        Log.debug("L2 call graph: " + callGraph.size() + " edge(s), "
                + externalSymbols.size() + " external symbol(s)"
                + (rtaEdges == null ? " (declared only)" : " (declared + rta)"));
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
            Map<String, JExternalSymbol> externalSymbols,
            boolean includeExternal) {
        for (JCallable callable : type.getCallables().values()) {
            backfillCallable(callable, appName, index, callableIds, weightBySrcDst, externalSymbols, includeExternal);
            callable.getTypes().values().forEach(local ->
                    backfillTree(local, appName, index, callableIds, weightBySrcDst, externalSymbols, includeExternal));
        }
        type.getTypes().values().forEach(nested ->
                backfillTree(nested, appName, index, callableIds, weightBySrcDst, externalSymbols, includeExternal));
    }

    private static void backfillCallable(
            JCallable callable,
            String appName,
            Map<String, String> index,
            Set<String> callableIds,
            Map<String, Map<String, Integer>> weightBySrcDst,
            Map<String, JExternalSymbol> externalSymbols,
            boolean includeExternal) {
        String src = callable.getId();
        for (JBodyNode node : callable.getBody().values()) {
            if (!"call".equals(node.getKind())) {
                continue;
            }
            String dst = resolveCallee(node, appName, index, callableIds, externalSymbols, includeExternal);
            if (dst == null) {
                continue; // signature-miss or unresolved: callee stays absent, no edge
            }
            node.setCallee(dst);
            weightBySrcDst.computeIfAbsent(src, k -> new TreeMap<>()).merge(dst, 1, Integer::sum);
        }
    }

    /**
     * The callee id for one {@code call} node, or {@code null} when it resolves to nothing (a
     * signature-miss or an unresolved site). Registers an external symbol as a side effect of case 4.
     */
    private static String resolveCallee(
            JBodyNode node,
            String appName,
            Map<String, String> index,
            Set<String> callableIds,
            Map<String, JExternalSymbol> externalSymbols,
            boolean includeExternal) {
        String hint = node.getDeclaringTypeHint();
        if (hint == null) {
            return null; // the site did not resolve
        }
        if (hint.startsWith(CanId.SCHEME)) {
            // Case 1: L1 already resolved an anonymous creation to its own constructor by node identity
            // and stored that can-id. Use it directly; the membership check keeps no-dangling structural.
            return callableIds.contains(hint) ? hint : null;
        }
        String signature = node.getCalleeSignature();
        if (signature == null) {
            return null; // resolved the type but not a signature to compose
        }
        if (index.containsKey(hint)) {
            return inProjectId(hint, signature, index, callableIds); // in-project hit (case 2) or signature-miss
        }
        if (!includeExternal) {
            return null; // case 4 gated off: match v1's application-only graph
        }
        String externalId = CanId.externalId(appName, hint, signature); // case 4
        externalSymbols.computeIfAbsent(externalId, k -> externalSymbol(signature, hint, node.isConstructorCall()));
        return externalId;
    }

    /**
     * Join one WALA RTA edge. The src must be an in-project callable that exists in the tree, or the
     * edge is unattributable and dropped. An in-project dst that maps to no tree callable is dropped
     * (the overlay never fabricates an in-project node); a library dst is homed external.
     */
    private static void joinRtaEdge(
            RtaEndpoint edge,
            String appName,
            Map<String, String> index,
            Set<String> callableIds,
            Map<String, Map<String, Integer>> rtaWeights,
            Map<String, JExternalSymbol> externalSymbols,
            boolean includeExternal) {
        if (!edge.srcAppClass) {
            return; // a library caller has no in-project node to attribute the edge to
        }
        String src = inProjectId(edge.srcType, edge.srcSignature, index, callableIds);
        if (src == null) {
            return; // an in-project synthetic (bridge, access$, lambda$) or an identity-join failure
        }
        String dst;
        if (edge.dstAppClass) {
            dst = inProjectId(edge.dstType, edge.dstSignature, index, callableIds);
            if (dst == null) {
                return; // in-project but absent from the tree — drop rather than fabricate
            }
        } else {
            if (!includeExternal) {
                return; // external target gated off: match v1's application-only graph
            }
            dst = CanId.externalId(appName, edge.dstType, edge.dstSignature);
            externalSymbols.computeIfAbsent(
                    dst, k -> externalSymbol(edge.dstSignature, edge.dstType, edge.dstSignature.startsWith("<init>")));
        }
        rtaWeights.computeIfAbsent(src, k -> new TreeMap<>()).merge(dst, 1, Integer::sum);
    }

    /** The in-project callable id for a binary type + signature, or {@code null} if absent from the tree. */
    private static String inProjectId(
            String binaryType, String signature, Map<String, String> index, Set<String> callableIds) {
        String typeId = index.get(binaryType);
        if (typeId == null) {
            return null;
        }
        String composed = CanId.childId(typeId, signature);
        return callableIds.contains(composed) ? composed : null;
    }

    private static JExternalSymbol externalSymbol(String signature, String binaryType, boolean constructor) {
        JExternalSymbol symbol = new JExternalSymbol();
        symbol.setKind(constructor ? "constructor" : "method");
        symbol.setSignature(signature);
        // The id carries the binary name; declaring_type is the legible dotted spelling.
        symbol.setDeclaringType(binaryType.replace('$', '.'));
        return symbol;
    }

    /**
     * Merge the declared and RTA weight tables into edges. One edge per {@code (src, dst)}: {@code prov}
     * is the set-union of the analyses attesting it, alphabetical; {@code weight} is the declared
     * call-site count when declared attests it (those are the sites a consumer can navigate to),
     * otherwise the RTA count. Edges sort by {@code (src, dst)}.
     */
    private static List<JCallEdge> mergeEdges(
            Map<String, Map<String, Integer>> declaredWeights, Map<String, Map<String, Integer>> rtaWeights) {
        Set<String> srcs = new TreeSet<>();
        srcs.addAll(declaredWeights.keySet());
        srcs.addAll(rtaWeights.keySet());

        List<JCallEdge> edges = new ArrayList<>();
        for (String src : srcs) {
            Map<String, Integer> declared = declaredWeights.getOrDefault(src, Map.of());
            Map<String, Integer> rta = rtaWeights.getOrDefault(src, Map.of());
            Set<String> dsts = new TreeSet<>();
            dsts.addAll(declared.keySet());
            dsts.addAll(rta.keySet());
            for (String dst : dsts) {
                boolean byDeclared = declared.containsKey(dst);
                boolean byRta = rta.containsKey(dst);
                List<String> prov = new ArrayList<>();
                if (byDeclared) {
                    prov.add("declared"); // alphabetical before "rta"
                }
                if (byRta) {
                    prov.add("rta");
                }
                JCallEdge edge = new JCallEdge();
                edge.setSrc(src);
                edge.setDst(dst);
                edge.setProv(prov);
                edge.setWeight(byDeclared ? declared.get(dst) : rta.get(dst));
                edges.add(edge);
            }
        }
        return edges;
    }
}
