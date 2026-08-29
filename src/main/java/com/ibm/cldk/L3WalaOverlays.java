/*
Copyright IBM Corporation 2023, 2024

Licensed under the Apache Public License 2.0, Version 2.0 (the "License");
you may not use this file except in compliance with the License.

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.ibm.cldk;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.ibm.cldk.schema.CanId;
import com.ibm.cldk.schema.JCallable;
import com.ibm.cldk.schema.JModule;
import com.ibm.cldk.schema.JType;
import com.ibm.cldk.syntactic_analysis.L1BuildContext;
import com.ibm.cldk.syntactic_analysis.controlflow.BodyNodeBuilder;
import com.ibm.cldk.syntactic_analysis.controlflow.ControlFlowGraph;
import com.ibm.cldk.utils.Log;
import com.ibm.cldk.wala.InstructionToNode;
import com.ibm.cldk.wala.WalaAnalysis;
import com.ibm.cldk.wala.WalaAnalysis.MethodIr;
import com.ibm.cldk.wala.WalaCfgBuilder;
import com.ibm.cldk.wala.WalaPdgBuilder;
import com.ibm.cldk.wala.WalaPdgBuilder.PdgOverlays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Post-build L3 orchestrator for the WALA engine.
 *
 * For each method in WALA's application call graph, correlates the WALA {@code IMethod} to its
 * {@link JCallable} in the L1 module map, re-parses the source (from {@link JModule#getSource()}),
 * finds the method's {@link BlockStmt}, populates a fresh {@link ControlFlowGraph}, and attaches
 * cfg/cdg/ddg overlays directly to the callable.
 *
 * Methods not matched in the call graph get no overlay (coverage caveat §5.3); this is logged as
 * a summary count rather than an error. Absent-analysis degradation (when {@link WalaAnalysis#of}
 * returns empty) lives in the caller — this class is not invoked in that case.
 */
public final class L3WalaOverlays {

    private L3WalaOverlays() {}

    /**
     * Applies WALA-engine L3 overlays (cfg/cdg/ddg) to all matching callables in {@code modules}.
     *
     * @param wala       the pre-built WALA analysis (already used for L2 rta endpoints)
     * @param input      the project root directory
     * @param modules    the L1 module map (mutated in place: cfg/cdg/ddg are set on callables)
     * @param fieldDepth the DDG access-path bound k ({@code --graph-field-depth})
     */
    public static void apply(
            WalaAnalysis wala,
            String input,
            Map<String, JModule> modules,
            int fieldDepth) {

        if (modules.isEmpty()) {
            return;
        }

        // Derive the applicationId from any module in the map.
        String applicationId = deriveApplicationId(modules);

        // Build a binary-type-name → (moduleKey, JType) index.
        Map<String, TypeEntry> typeIndex = buildTypeIndex(modules);

        // Memoized re-parse cache: moduleKey → CompilationUnit (plain JavaParser, no solver).
        Map<String, CompilationUnit> parseCache = new LinkedHashMap<>();

        int matched = 0;
        int skippedNoMatch = 0;
        int totalOverApprox = 0;

        for (MethodIr m : wala.applicationMethods()) {
            Optional<Joined> joinedOpt = join(m, typeIndex, parseCache, modules);
            if (!joinedOpt.isPresent()) {
                skippedNoMatch++;
                continue;
            }
            Joined joined = joinedOpt.get();
            JCallable callable = joined.callable;
            BlockStmt blockStmt = joined.blockStmt;

            // Source text for L1BuildContext (spans need the original text for byte offsets).
            String source = modules.get(joined.moduleKey).getSource();

            // Build a minimal L1BuildContext: only spanOf() is called in BodyNodeBuilder.populate;
            // the solver-dependent helpers are not used on this path.
            L1BuildContext ctx = new L1BuildContext(
                    applicationId, joined.moduleKey, source, 3, fieldDepth, "wala");

            // Populate the body-node graph seeded with the callable's existing L1 call nodes.
            ControlFlowGraph cfg = new ControlFlowGraph();
            BodyNodeBuilder.populate(cfg, blockStmt, callable.getBody(), ctx);

            // Build the InstructionToNode mapper from the re-parsed block.
            Map<Integer, List<com.github.javaparser.ast.stmt.Statement>> byLine =
                    InstructionToNode.statementsByLine(blockStmt);
            InstructionToNode mapper = new InstructionToNode(byLine);

            // Add CFG edges from the WALA SSACFG.
            WalaCfgBuilder.build(m, cfg, mapper);

            // Build CDG and DDG from the WALA per-method PDG.
            PdgOverlays pdg = WalaPdgBuilder.build(wala, m, mapper);

            // Attach overlays to the callable (additive: body is enriched, never overwritten).
            callable.setBody(cfg.nodes());
            callable.setCfg(cfg.toCfgEdges());
            callable.setCdg(pdg.cdg);
            callable.setDdg(pdg.ddg);

            matched++;
            totalOverApprox += mapper.overApproximationCount();
        }

        Log.info("L3 WALA overlays applied: " + matched + " callable(s) covered, "
                + skippedNoMatch + " skipped (no source match), "
                + totalOverApprox + " sentinel over-approximation(s)");
    }

    // ----- the WALA-method → JCallable join -----------------------------------------------------

    /**
     * Resolves the WALA method {@code m} to the {@link JCallable} it was compiled from and the
     * {@link BlockStmt} of its re-parsed source, or empty when any leg of the join fails (type not
     * in the L1 map, signature not among its callables, source absent or unparseable, body not
     * locatable — including a callable with no body at all, whose {@code body_span} is absent).
     *
     * <p>Shared with {@link L4WalaOverlays} so both overlay passes reach the same callable from the
     * same WALA node; {@code parseCache} carries the memoized compilation units across the loop.
     */
    static Optional<Joined> join(
            MethodIr m,
            Map<String, TypeEntry> typeIndex,
            Map<String, CompilationUnit> parseCache,
            Map<String, JModule> modules) {

        // Derive the join keys using the same converters as RtaCallGraph (same package).
        String binaryType =
                RtaCallGraph.binaryTypeName(m.method.getDeclaringClass().getName().toString());
        String sig = RtaCallGraph.signature(
                m.method.getName().toString(), m.method.getDescriptor().toString());

        // Look up the JType then the JCallable.
        TypeEntry entry = typeIndex.get(binaryType);
        if (entry == null) {
            return Optional.empty();
        }
        JCallable callable = entry.type.getCallables().get(sig);
        if (callable == null) {
            return Optional.empty();
        }

        // Re-parse the source (memoized; source text comes from the L1 JModule).
        CompilationUnit cu = parseOrCached(parseCache, entry.moduleKey, modules);
        if (cu == null) {
            return Optional.empty();
        }

        // Find the BlockStmt for this method in the re-parsed CU.
        return findBody(cu, binaryType, entry.packageName, callable)
                .map(block -> new Joined(entry.moduleKey, callable, block));
    }

    // ----- index building -----------------------------------------------------------------------

    /**
     * Builds a map from WALA binary type name (e.g. {@code "com.example.Widget$Inner"}) to the
     * module key and JType that holds that type's callables.
     */
    static Map<String, TypeEntry> buildTypeIndex(Map<String, JModule> modules) {
        Map<String, TypeEntry> index = new LinkedHashMap<>();
        for (Map.Entry<String, JModule> entry : modules.entrySet()) {
            String moduleKey = entry.getKey();
            JModule module = entry.getValue();
            String pkg = module.getPackageName() != null ? module.getPackageName() : "";
            for (Map.Entry<String, JType> typeEntry : module.getTypes().entrySet()) {
                String topBinaryName = pkg.isEmpty()
                        ? typeEntry.getKey()
                        : pkg + "." + typeEntry.getKey();
                indexType(index, topBinaryName, typeEntry.getValue(), moduleKey, pkg);
            }
        }
        return index;
    }

    private static void indexType(
            Map<String, TypeEntry> index,
            String binaryName,
            JType type,
            String moduleKey,
            String packageName) {
        index.put(binaryName, new TypeEntry(moduleKey, type, packageName));
        for (Map.Entry<String, JType> nested : type.getTypes().entrySet()) {
            indexType(index, binaryName + "$" + nested.getKey(),
                    nested.getValue(), moduleKey, packageName);
        }
    }

    // ----- source re-parsing --------------------------------------------------------------------

    private static CompilationUnit parseOrCached(
            Map<String, CompilationUnit> cache,
            String moduleKey,
            Map<String, JModule> modules) {
        if (cache.containsKey(moduleKey)) {
            return cache.get(moduleKey);
        }
        JModule module = modules.get(moduleKey);
        if (module == null || module.getSource() == null) {
            cache.put(moduleKey, null);
            return null;
        }
        ParseResult<CompilationUnit> result = new JavaParser().parse(module.getSource());
        CompilationUnit cu = result.getResult().orElse(null);
        cache.put(moduleKey, cu);
        return cu;
    }

    // ----- BlockStmt lookup ---------------------------------------------------------------------

    /**
     * Finds the {@link BlockStmt} for the given callable in the re-parsed compilation unit.
     *
     * Primary match: the callable's {@code body_span} start line (from L1). If body_span is
     * absent (abstract method, interface method without default), returns empty.
     * Fallback: match by callable name only when there is a unique declaration with that name
     * in the target type.
     */
    private static Optional<BlockStmt> findBody(
            CompilationUnit cu,
            String binaryType,
            String packageName,
            JCallable callable) {

        if (callable.getBodySpan() == null) {
            return Optional.empty();
        }

        TypeDeclaration<?> td = findTypeDecl(cu, binaryType, packageName);
        if (td == null) {
            return Optional.empty();
        }

        int targetLine = callable.getBodySpan().getStart()[0];

        // Match by body-open line — the most reliable discriminator.
        for (com.github.javaparser.ast.body.BodyDeclaration<?> member : td.getMembers()) {
            if (!(member instanceof CallableDeclaration)) {
                continue;
            }
            Optional<BlockStmt> body = bodyOfDecl((CallableDeclaration<?>) member);
            if (body.isPresent()
                    && body.get().getBegin().map(p -> p.line).orElse(-1) == targetLine) {
                return body;
            }
        }

        // Fallback: unique name match (handles cases where the line table is off by one).
        String methodName = callable.getSignature();
        int parenIdx = methodName.indexOf('(');
        if (parenIdx > 0) {
            methodName = methodName.substring(0, parenIdx);
        }
        List<BlockStmt> nameMatches = new ArrayList<>();
        for (com.github.javaparser.ast.body.BodyDeclaration<?> member : td.getMembers()) {
            if (!(member instanceof CallableDeclaration)) {
                continue;
            }
            CallableDeclaration<?> cd = (CallableDeclaration<?>) member;
            String cdName = cd instanceof MethodDeclaration
                    ? cd.getNameAsString()
                    : "<init>";
            if (methodName.equals(cdName)) {
                bodyOfDecl(cd).ifPresent(nameMatches::add);
            }
        }
        if (nameMatches.size() == 1) {
            return Optional.of(nameMatches.get(0));
        }
        return Optional.empty();
    }

    private static Optional<BlockStmt> bodyOfDecl(CallableDeclaration<?> cd) {
        if (cd instanceof MethodDeclaration) {
            return ((MethodDeclaration) cd).getBody();
        }
        if (cd instanceof ConstructorDeclaration) {
            return Optional.of(((ConstructorDeclaration) cd).getBody());
        }
        return Optional.empty();
    }

    /**
     * Navigates the compilation unit to find the type declaration for {@code binaryType}.
     * For nested types (containing {@code $}), each segment names a member type.
     */
    private static TypeDeclaration<?> findTypeDecl(
            CompilationUnit cu, String binaryType, String packageName) {

        // Strip the package prefix to get the simple-name chain.
        String typeChain = binaryType;
        if (packageName != null && !packageName.isEmpty()
                && binaryType.startsWith(packageName + ".")) {
            typeChain = binaryType.substring(packageName.length() + 1);
        }
        String[] parts = typeChain.split("\\$");

        // Find the top-level type.
        TypeDeclaration<?> current = null;
        for (TypeDeclaration<?> top : cu.getTypes()) {
            if (top.getNameAsString().equals(parts[0])) {
                current = top;
                break;
            }
        }
        if (current == null) {
            return null;
        }

        // Navigate member (nested) types.
        for (int i = 1; i < parts.length; i++) {
            TypeDeclaration<?> next = null;
            for (com.github.javaparser.ast.body.BodyDeclaration<?> member : current.getMembers()) {
                if (member instanceof TypeDeclaration
                        && ((TypeDeclaration<?>) member).getNameAsString().equals(parts[i])) {
                    next = (TypeDeclaration<?>) member;
                    break;
                }
            }
            if (next == null) {
                return null;
            }
            current = next;
        }
        return current;
    }

    // ----- applicationId derivation -------------------------------------------------------------

    /**
     * Derives the {@code can://java/<app>} applicationId from the first entry in {@code modules}.
     * The module id has the form {@code applicationId/normalizedFileKey}, so strip the suffix.
     */
    private static String deriveApplicationId(Map<String, JModule> modules) {
        Map.Entry<String, JModule> first = modules.entrySet().iterator().next();
        String moduleId = first.getValue().getId();
        if (moduleId == null) {
            return CanId.SCHEME + "/unknown";
        }
        String normalizedFileKey = first.getKey().replace("\\", "/").replaceFirst("^[./]+", "");
        int sep = moduleId.lastIndexOf("/" + normalizedFileKey);
        if (sep > 0) {
            return moduleId.substring(0, sep);
        }
        // Fallback: trim the last slash-delimited segment matching the key.
        int last = moduleId.lastIndexOf('/');
        return last > 0 ? moduleId.substring(0, last) : moduleId;
    }

    // ----- inner types --------------------------------------------------------------------------

    /** One WALA method successfully joined to its L1 callable and re-parsed body block. */
    static final class Joined {
        final String moduleKey;
        final JCallable callable;
        final BlockStmt blockStmt;

        Joined(String moduleKey, JCallable callable, BlockStmt blockStmt) {
            this.moduleKey = moduleKey;
            this.callable = callable;
            this.blockStmt = blockStmt;
        }
    }

    static final class TypeEntry {
        final String moduleKey;
        final JType type;
        final String packageName;

        TypeEntry(String moduleKey, JType type, String packageName) {
            this.moduleKey = moduleKey;
            this.type = type;
            this.packageName = packageName;
        }
    }
}
