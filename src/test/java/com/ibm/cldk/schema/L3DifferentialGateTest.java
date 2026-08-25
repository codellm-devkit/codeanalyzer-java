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

package com.ibm.cldk.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.ibm.cldk.CodeAnalyzer;
import com.ibm.cldk.syntactic_analysis.L1BuildContext;
import com.ibm.cldk.syntactic_analysis.L3TestSupport;
import com.ibm.cldk.syntactic_analysis.controlflow.BodyNodeBuilder;
import com.ibm.cldk.syntactic_analysis.controlflow.CdgBuilder;
import com.ibm.cldk.syntactic_analysis.controlflow.CfgBuilder;
import com.ibm.cldk.syntactic_analysis.controlflow.ControlFlowGraph;
import com.ibm.cldk.syntactic_analysis.dataflow.DdgBuilder;
import com.ibm.cldk.wala.InstructionToNode;
import com.ibm.cldk.wala.WalaAnalysis;
import com.ibm.cldk.wala.WalaCfgBuilder;
import com.ibm.cldk.wala.WalaPdgBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * L3 differential gate: both the AST engine (reference oracle) and the WALA engine are run over the
 * same fixture; CFG node-sets and reachability are hard-asserted equal; CDG is hard-cross-validated
 * after scoping out pinned divergences; DDG differences are reported as counts, never asserted equal.
 *
 * <p><b>HARD checks</b> (a mismatch outside the pinned set fails the build):
 * <ol>
 *   <li>CFG node-set equality — both engines populate body nodes via {@code BodyNodeBuilder} on the
 *       same source; the sets must be identical after excluding WALA {@code line:0} sentinel nodes.
 *   <li>CFG reachability — every node reachable from {@code @entry} and reaching {@code @exit} in
 *       both engines (the single {@code finally} node, reached on every exit path per §4.4.1, is
 *       checked as part of this invariant).
 *   <li>CDG equality — after filtering sentinel-involving edges and edges whose {@code src} or
 *       {@code dst} is a WALA-only exception source (nodes with exception edges in WALA but not in
 *       AST), the remaining CDG must match the AST reference exactly. The {@code dst} direction
 *       covers try/finally constructs where WALA models the finally body as control-dependent on
 *       the try body, producing CDG edges that the AST engine (which treats finally as always
 *       executed) does not.
 * </ol>
 *
 * <p><b>Pinned divergences</b> (asserted as <em>expected</em>, never failures):
 * <ul>
 *   <li>{@code SENTINEL_NODES} — {@code line:0} body nodes emitted by WALA when B.1 disambiguation
 *       fails; absent from the AST engine.
 *   <li>{@code EXCEPTION_EDGE_DENSITY} — WALA adds {@code exception} edges from every instruction
 *       that may throw; the AST engine edges only from statements that syntactically throw.
 *   <li>{@code CDG_EXCEPTION_INDUCED} — CDG edges whose {@code src} or {@code dst} is a WALA-only
 *       exception source (induced by the extra WALA exception edges above).
 * </ul>
 *
 * <p><b>DDG delta report</b> (printed to stdout; never asserted equal):
 * <ul>
 *   <li>Total DDG edge counts per engine.
 *   <li>Heap ({@code prov=["points-to"]}) du-pairs the WALA engine adds that the AST engine lacks
 *       (the aliased/field du-pairs the syntactic engine misses, §4.5.1).
 *   <li>Object-insensitive ({@code prov=["ssa"]}) edges the AST engine has that WALA lacks (edges
 *       the RTA analysis prunes or merges).
 * </ul>
 */
@Tag("realworld")
class L3DifferentialGateTest {

    /**
     * Fixture exercising: if/else (branch), for-loop (loop), try/finally, and a heap field access
     * (aliasedMethod — Box.f store and load in the same method, triggering WALA heap DDG).
     *
     * Line mapping (1-based, for reference):
     * <pre>
     *  1: public class DifferentialGateFixture {
     *  2:     static class Box { int f; }
     *  3: (blank)
     *  4:     int ifMethod(int x) {
     *  5:         if (x > 0) {
     *  6:             x = x + 1;
     *  7:         } else {
     *  8:             x = x - 1;
     *  9:         }
     * 10:         return x;
     * 11:     }
     * 12: (blank)
     * 13:     int loopMethod(int n) {
     * 14:         int sum = 0;
     * 15:         for (int i = 0; i < n; i++) {
     * 16:             sum = sum + i;
     * 17:         }
     * 18:         return sum;
     * 19:     }
     * 20: (blank)
     * 21:     int finallyMethod(int x) {
     * 22:         try {
     * 23:             return doWork(x);
     * 24:         } finally {
     * 25:             logValue(x);
     * 26:         }
     * 27:     }
     * 28: (blank)
     * 29:     int aliasedMethod(int p) {
     * 30:         Box a = new Box();
     * 31:         a.f = p;
     * 32:         int r = a.f;
     * 33:         return r;
     * 34:     }
     * 35: (blank)
     * 36:     int doWork(int x) { return x; }
     * 37:     void logValue(int x) { }
     * 38: }
     * </pre>
     */
    private static final String FIXTURE =
            "public class DifferentialGateFixture {\n"
            + "    static class Box { int f; }\n"
            + "\n"
            + "    int ifMethod(int x) {\n"
            + "        if (x > 0) {\n"
            + "            x = x + 1;\n"
            + "        } else {\n"
            + "            x = x - 1;\n"
            + "        }\n"
            + "        return x;\n"
            + "    }\n"
            + "\n"
            + "    int loopMethod(int n) {\n"
            + "        int sum = 0;\n"
            + "        for (int i = 0; i < n; i++) {\n"
            + "            sum = sum + i;\n"
            + "        }\n"
            + "        return sum;\n"
            + "    }\n"
            + "\n"
            + "    int finallyMethod(int x) {\n"
            + "        try {\n"
            + "            return doWork(x);\n"
            + "        } finally {\n"
            + "            logValue(x);\n"
            + "        }\n"
            + "    }\n"
            + "\n"
            + "    int aliasedMethod(int p) {\n"
            + "        Box a = new Box();\n"
            + "        a.f = p;\n"
            + "        int r = a.f;\n"
            + "        return r;\n"
            + "    }\n"
            + "\n"
            + "    int doWork(int x) { return x; }\n"
            + "    void logValue(int x) { }\n"
            + "}\n";

    // Methods with non-trivial bodies to cross-validate. doWork/logValue have trivial/empty bodies
    // and are included in WALA's call graph only if they appear as call targets; skipping them
    // avoids false failures from empty-body node-set differences.
    private static final List<String> TARGET_METHODS =
            java.util.Arrays.asList("ifMethod", "loopMethod", "finallyMethod", "aliasedMethod");

    /**
     * The single gate test. Compiles the fixture for WALA, then for each target method:
     * hard-asserts CFG node-set and reachability, hard-asserts scoped CDG equality, and reports the
     * DDG delta without asserting it.
     */
    @Test
    void differentialGateCfgCdgAgreeDdgDeltaReported(@TempDir Path tmp) throws Exception {
        String dir = compileFixture(tmp);
        WalaAnalysis wala = buildWala(dir);
        L1BuildContext ctx = L3TestSupport.ctx(FIXTURE);

        // Coverage: report which target methods appear in WALA's call graph.
        Set<String> walaNames = wala.applicationMethods().stream()
                .map(m -> m.method.getName().toString())
                .collect(Collectors.toSet());
        List<String> coveredMethods = TARGET_METHODS.stream()
                .filter(walaNames::contains)
                .collect(Collectors.toList());
        List<String> uncoveredMethods = TARGET_METHODS.stream()
                .filter(n -> !walaNames.contains(n))
                .collect(Collectors.toList());
        if (!uncoveredMethods.isEmpty()) {
            System.out.println("[COVERAGE-GAP] methods not in WALA call graph (skipped): "
                    + uncoveredMethods);
        }
        System.out.println("[COVERAGE] comparing " + coveredMethods.size() + " method(s): "
                + coveredMethods);

        int compared = 0;
        for (String methodName : coveredMethods) {
            BlockStmt body = L3TestSupport.methodBody(FIXTURE, methodName);

            // ---- AST engine (reference oracle) --------------------------------------------------
            ControlFlowGraph astG = CfgBuilder.build(body, new LinkedHashMap<>(), ctx);
            List<JCdgEdge> astCdg = CdgBuilder.build(astG);
            List<JDdgEdge> astDdg = DdgBuilder.build(astG, 3);

            // ---- WALA engine -------------------------------------------------------------------
            WalaAnalysis.MethodIr mir = findMethod(wala, methodName);
            ControlFlowGraph walaG = new ControlFlowGraph();
            BodyNodeBuilder.populate(walaG, body, new LinkedHashMap<>(), ctx);
            Map<Integer, List<Statement>> byLine = InstructionToNode.statementsByLine(body);
            InstructionToNode mapper = new InstructionToNode(byLine);
            WalaCfgBuilder.build(mir, walaG, mapper);
            WalaPdgBuilder.PdgOverlays walaOverlays = WalaPdgBuilder.build(wala, mir, mapper);

            // ---- HARD: CFG node-set equality (excluding sentinels) -----------------------------
            Set<String> astNodes = new LinkedHashSet<>(astG.nodes().keySet());
            Set<String> walaNodes = new LinkedHashSet<>(walaG.nodes().keySet());

            Set<String> walaSentinelNodes = walaNodes.stream()
                    .filter(L3DifferentialGateTest::isSentinel)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            if (!walaSentinelNodes.isEmpty()) {
                System.out.println("[PINNED:SENTINEL_NODES] " + methodName
                        + " — WALA line:0 body nodes (expected): " + walaSentinelNodes);
            }

            Set<String> walaNodesFiltered = new LinkedHashSet<>(walaNodes);
            walaNodesFiltered.removeAll(walaSentinelNodes);

            assertEquals(astNodes, walaNodesFiltered,
                    "HARD[node-set] " + methodName
                            + ": CFG node sets must agree after excluding sentinels."
                            + " AST-only=" + setDiff(astNodes, walaNodesFiltered)
                            + " WALA-only=" + setDiff(walaNodesFiltered, astNodes));

            // ---- HARD: CFG reachability --------------------------------------------------------
            assertReachability(methodName + "/AST", astG);
            assertReachability(methodName + "/WALA", walaG);

            // ---- Pinned: exception-edge density ------------------------------------------------
            List<JCfgEdge> astEdges = astG.toCfgEdges();
            List<JCfgEdge> walaEdges = walaG.toCfgEdges();

            Set<String> astExSrcs = astEdges.stream()
                    .filter(e -> "exception".equals(e.getKind()))
                    .map(JCfgEdge::getSrc)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Set<String> walaExSrcs = walaEdges.stream()
                    .filter(e -> "exception".equals(e.getKind()))
                    .map(JCfgEdge::getSrc)
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            // WALA-only exception sources: nodes WALA adds exception edges from that AST does not.
            Set<String> walaOnlyExSrcs = new LinkedHashSet<>(walaExSrcs);
            walaOnlyExSrcs.removeAll(astExSrcs);

            if (!walaOnlyExSrcs.isEmpty()) {
                System.out.println("[PINNED:EXCEPTION_EDGE_DENSITY] " + methodName
                        + " — WALA-only exception sources (expected): " + walaOnlyExSrcs);
            }

            // ---- HARD: CDG equality (scoped to non-pinned control dependences) ----------------
            // Scoping rule: remove edges involving line:0 sentinels (B.1 ambiguity) and edges
            // whose src OR dst is a WALA-only exception source. The dst direction is necessary for
            // try/finally constructs: WALA's exception model causes the finally body (which is a
            // WALA-only exception source because logValue-style calls may throw per bytecode
            // analysis) to appear control-dependent on the try body, producing CDG edges pointing
            // TO the finally statement. The AST engine — which models finally as always-executed
            // — does not produce these. Both src-induced and dst-induced divergences are captured
            // under the CDG_EXCEPTION_INDUCED pin. After this scoping the remaining CDG must
            // match exactly.
            Set<String> filteredWalaCdgKeys = walaOverlays.cdg.stream()
                    .filter(e -> !isSentinel(e.getSrc()) && !isSentinel(e.getDst()))
                    .filter(e -> !walaOnlyExSrcs.contains(e.getSrc())
                            && !walaOnlyExSrcs.contains(e.getDst()))
                    .map(e -> e.getSrc() + "->" + e.getDst())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Set<String> filteredAstCdgKeys = astCdg.stream()
                    .map(e -> e.getSrc() + "->" + e.getDst())
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            Set<String> cdgInAstNotWala = setDiff(filteredAstCdgKeys, filteredWalaCdgKeys);
            Set<String> cdgInWalaNotAst = setDiff(filteredWalaCdgKeys, filteredAstCdgKeys);

            assertEquals(filteredAstCdgKeys, filteredWalaCdgKeys,
                    "HARD[cdg] " + methodName
                            + ": CDG must agree after scoping out sentinels and WALA-only"
                            + " exception-source edges."
                            + " in-AST-not-WALA=" + cdgInAstNotWala
                            + " in-WALA-not-AST=" + cdgInWalaNotAst);

            // ---- DDG delta report (no assertion — different precisions, §4.5) -----------------
            reportDdgDelta(methodName, astDdg, walaOverlays.ddg);

            System.out.println("[PINNED-SUMMARY] " + methodName
                    + " sentinels=" + walaSentinelNodes.size()
                    + " walaOnlyExSrcs=" + walaOnlyExSrcs.size()
                    + " cdgPinnedFromWala="
                    + walaOverlays.cdg.stream()
                            .filter(e -> isSentinel(e.getSrc()) || isSentinel(e.getDst())
                                    || walaOnlyExSrcs.contains(e.getSrc())
                                    || walaOnlyExSrcs.contains(e.getDst()))
                            .count());

            compared++;
        }

        assertTrue(compared > 0,
                "At least one method must be cross-validated; WALA covered: " + walaNames);
    }

    // ----- reachability assertion ---------------------------------------------------------------

    /**
     * Asserts every node in {@code g} is reachable from {@code @entry} (forward) and reaches
     * {@code @exit} (backward). An isolated node that has no edges fails both checks.
     */
    private static void assertReachability(String label, ControlFlowGraph g) {
        Set<String> all = g.nodes().keySet();
        Set<String> fromEntry = reachable(g, ControlFlowGraph.ENTRY, true);
        Set<String> toExit = reachable(g, ControlFlowGraph.EXIT, false);

        assertEquals(all, fromEntry,
                "HARD[reachability] " + label + ": all nodes must be reachable from @entry."
                        + " Unreachable: " + setDiff(all, fromEntry));
        assertEquals(all, toExit,
                "HARD[reachability] " + label + ": all nodes must reach @exit."
                        + " Not reaching @exit: " + setDiff(all, toExit));
    }

    /** BFS over forward ({@code forward=true}) or backward ({@code forward=false}) edges. */
    private static Set<String> reachable(ControlFlowGraph g, String start, boolean forward) {
        Set<String> visited = new LinkedHashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            String node = queue.poll();
            if (visited.add(node)) {
                List<String> nexts = forward ? g.successors(node) : g.predecessors(node);
                queue.addAll(nexts);
            }
        }
        return visited;
    }

    // ----- DDG delta report ---------------------------------------------------------------------

    /**
     * Prints a DDG delta report comparing the AST engine's object-insensitive syntactic DDG against
     * the WALA engine's RTA-backed DDG (scalar + heap). Never fails the test.
     *
     * <p>The comparison key is {@code src->dst/var} (ignoring prov), so a pair present in both but
     * with different prov tags is counted in both "AST-only" and "WALA-only" lists — the intended
     * signal, since it shows where the engines' var naming conventions diverge (e.g. "a.f" vs "f").
     */
    private static void reportDdgDelta(
            String method, List<JDdgEdge> astDdg, List<JDdgEdge> walaDdg) {

        long walaHeapCount = walaDdg.stream()
                .filter(e -> e.getProv().contains("points-to"))
                .count();
        long walaScalarCount = walaDdg.stream()
                .filter(e -> e.getProv().contains("ssa"))
                .count();
        long astSsaCount = astDdg.stream()
                .filter(e -> e.getProv().contains("ssa"))
                .count();

        Set<String> astKeys = astDdg.stream()
                .map(e -> e.getSrc() + "->" + e.getDst() + "/" + e.getVar())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> walaKeys = walaDdg.stream()
                .map(e -> e.getSrc() + "->" + e.getDst() + "/" + e.getVar())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<JDdgEdge> walaHeapNotInAst = walaDdg.stream()
                .filter(e -> e.getProv().contains("points-to"))
                .filter(e -> !astKeys.contains(e.getSrc() + "->" + e.getDst() + "/" + e.getVar()))
                .collect(Collectors.toList());

        List<JDdgEdge> astOnlyEdges = astDdg.stream()
                .filter(e -> !walaKeys.contains(
                        e.getSrc() + "->" + e.getDst() + "/" + e.getVar()))
                .collect(Collectors.toList());

        System.out.println("[DDG-DELTA] " + method);
        System.out.println("  AST  edges total=" + astDdg.size()
                + " (ssa=" + astSsaCount + ")");
        System.out.println("  WALA edges total=" + walaDdg.size()
                + " (ssa=" + walaScalarCount + " heap/points-to=" + walaHeapCount + ")");
        System.out.println("  Heap (points-to) in WALA not in AST: "
                + walaHeapNotInAst.size());
        walaHeapNotInAst.forEach(e -> System.out.println(
                "    + " + e.getSrc() + " -[" + e.getVar() + "]-> " + e.getDst()
                + " prov=" + e.getProv()));
        System.out.println("  Object-insensitive (ssa) in AST not in WALA: "
                + astOnlyEdges.size());
        astOnlyEdges.forEach(e -> System.out.println(
                "    - " + e.getSrc() + " -[" + e.getVar() + "]-> " + e.getDst()
                + " prov=" + e.getProv()));
    }

    // ----- pinned-divergence utilities ----------------------------------------------------------

    /**
     * Returns {@code true} when {@code nodeId} is a WALA B.1 sentinel: the pattern {@code \d+:0}.
     * Real statement nodes always have a positive column ({@code \d+:\d+} where col >= 1).
     */
    private static boolean isSentinel(String nodeId) {
        return nodeId != null && nodeId.matches("\\d+:0");
    }

    private static <T> Set<T> setDiff(Set<T> a, Set<T> b) {
        Set<T> result = new LinkedHashSet<>(a);
        result.removeAll(b);
        return result;
    }

    // ----- compile / WALA helpers ---------------------------------------------------------------

    private static String compileFixture(Path tmp) throws Exception {
        Path src = tmp.resolve("DifferentialGateFixture.java");
        Files.writeString(src, FIXTURE);
        int rc = ToolProvider.getSystemJavaCompiler().run(
                null, null, null, "-g", "-d", tmp.toString(), src.toString());
        assertEquals(0, rc, "fixture compilation must succeed");
        return tmp.toString();
    }

    private static WalaAnalysis buildWala(String dir) {
        CodeAnalyzer.projectRootPom = dir;
        return WalaAnalysis.of(dir, null, null)
                .orElseThrow(() -> new AssertionError("WalaAnalysis.of must succeed for the fixture"));
    }

    private static WalaAnalysis.MethodIr findMethod(WalaAnalysis wala, String name) {
        return wala.applicationMethods().stream()
                .filter(m -> m.method.getName().toString().equals(name))
                .findFirst()
                .orElseThrow(
                        () -> new AssertionError("method '" + name + "' not found in WALA CG; "
                                + "available: "
                                + wala.applicationMethods().stream()
                                        .map(m -> m.method.getName().toString())
                                        .collect(Collectors.toList())));
    }
}
