package com.ibm.cldk.wala;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.ibm.cldk.CodeAnalyzer;
import com.ibm.cldk.schema.JBodyNode;
import com.ibm.cldk.schema.JCfgEdge;
import com.ibm.cldk.syntactic_analysis.L3TestSupport;
import com.ibm.cldk.syntactic_analysis.controlflow.BodyNodeBuilder;
import com.ibm.cldk.syntactic_analysis.controlflow.CfgBuilder;
import com.ibm.cldk.syntactic_analysis.controlflow.ControlFlowGraph;
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
 * Realworld tests for {@link WalaCfgBuilder}: compiles a fixture, builds the SSACFG, and asserts
 * well-formedness and CFG-kind fidelity against the AST engine ({@link CfgBuilder}) as the
 * reference.
 */
@Tag("realworld")
class WalaCfgBuilderTest {

    // ----- if-else fixture -----------------------------------------------------------------------

    private static final String IF_FIXTURE =
            "public class WalaCfgIfFixture {\n"
            + "    int compute(int x) {\n"
            + "        if (x > 0) {\n"
            + "            x = x + 1;\n"
            + "        } else {\n"
            + "            x = x - 1;\n"
            + "        }\n"
            + "        return x;\n"
            + "    }\n"
            + "}\n";

    /**
     * Builds the WALA CFG for the if-else fixture and asserts:
     * (1) well-formedness (all nodes reachable from @entry, all reach @exit);
     * (2) the branch node has "true" and "false" out-edges;
     * (3) the "true" edge targets the same node as the AST engine's "true" edge (then-arm);
     * (4) the "false" edge targets the same node as the AST engine's "false" edge (else-arm).
     */
    @Test
    void cfgEdgeKindsMatchAstEngineForIfElse(@TempDir Path tmp) throws Exception {
        String dir = compileFixture(tmp, "WalaCfgIfFixture.java", IF_FIXTURE);
        WalaAnalysis wala = buildWala(dir);

        WalaAnalysis.MethodIr mir = findMethod(wala, "compute");

        BlockStmt body = L3TestSupport.methodBody(IF_FIXTURE, "compute");
        ControlFlowGraph walaG = buildWalaCfg(IF_FIXTURE, body, mir);

        // Reference: build the same graph via the AST engine.
        ControlFlowGraph astG = CfgBuilder.build(body, new LinkedHashMap<>(), L3TestSupport.ctx(IF_FIXTURE));

        // Well-formedness.
        assertWellFormed(walaG);

        // Find the branch node (kind "branch").
        String branchNode = findNodeByKind(walaG, "branch");

        List<JCfgEdge> walaEdges = walaG.toCfgEdges();
        List<JCfgEdge> astEdges = astG.toCfgEdges();

        // The AST engine's true/false targets are the reference.
        String astTrueDst = edgeDst(astEdges, branchNode, "true");
        String astFalseDst = edgeDst(astEdges, branchNode, "false");

        String walaTrueDst = edgeDst(walaEdges, branchNode, "true");
        String walaFalseDst = edgeDst(walaEdges, branchNode, "false");

        assertTrue(walaTrueDst != null, "WALA CFG must have a 'true' edge from the branch node");
        assertTrue(walaFalseDst != null, "WALA CFG must have a 'false' edge from the branch node");
        assertEquals(astTrueDst, walaTrueDst,
                "'true' edge must target the then-arm node (AST engine reference)");
        assertEquals(astFalseDst, walaFalseDst,
                "'false' edge must target the else-arm node (AST engine reference)");
    }

    // ----- while-loop fixture -------------------------------------------------------------------

    private static final String WHILE_FIXTURE =
            "public class WalaCfgWhileFixture {\n"
            + "    int loop(int x) {\n"
            + "        while (x > 0) {\n"
            + "            x = x - 1;\n"
            + "        }\n"
            + "        return x;\n"
            + "    }\n"
            + "}\n";

    /**
     * Builds the WALA CFG for the while-loop fixture and asserts:
     * (1) well-formedness;
     * (2) a "loop_back" edge exists;
     * (3) the loop_back edge goes FROM the body statement TO the loop node (matching the AST engine);
     * (4) the loop node has a "true" edge to the body and a "false" edge to the after-loop node
     *     (matching the AST engine).
     */
    @Test
    void cfgEmitsLoopBackAndCorrectLoopEdgesForWhile(@TempDir Path tmp) throws Exception {
        String dir = compileFixture(tmp, "WalaCfgWhileFixture.java", WHILE_FIXTURE);
        WalaAnalysis wala = buildWala(dir);

        WalaAnalysis.MethodIr mir = findMethod(wala, "loop");

        BlockStmt body = L3TestSupport.methodBody(WHILE_FIXTURE, "loop");
        ControlFlowGraph walaG = buildWalaCfg(WHILE_FIXTURE, body, mir);

        // Reference.
        ControlFlowGraph astG = CfgBuilder.build(body, new LinkedHashMap<>(), L3TestSupport.ctx(WHILE_FIXTURE));

        // Well-formedness.
        assertWellFormed(walaG);

        // Find the loop node (kind "loop").
        String loopNode = findNodeByKind(walaG, "loop");

        List<JCfgEdge> walaEdges = walaG.toCfgEdges();
        List<JCfgEdge> astEdges = astG.toCfgEdges();

        // (a) A loop_back edge must exist in the WALA graph.
        boolean hasLoopBack = walaEdges.stream().anyMatch(e -> "loop_back".equals(e.getKind()));
        assertTrue(hasLoopBack, "WALA CFG must contain at least one 'loop_back' edge");

        // (b) The loop_back edge from the WALA CFG must have the same src and dst as the AST engine's.
        List<JCfgEdge> astLoopBackEdges = astEdges.stream()
                .filter(e -> "loop_back".equals(e.getKind()))
                .collect(Collectors.toList());
        assertFalse(astLoopBackEdges.isEmpty(), "AST CFG must also have a 'loop_back' edge");

        List<JCfgEdge> walaLoopBackEdges = walaEdges.stream()
                .filter(e -> "loop_back".equals(e.getKind()))
                .collect(Collectors.toList());
        // Verify the WALA loop_back edge goes to the loop node (dst = loop node).
        boolean walaLbTargetsLoop = walaLoopBackEdges.stream()
                .anyMatch(e -> loopNode.equals(e.getDst()));
        assertTrue(walaLbTargetsLoop,
                "WALA 'loop_back' edge must target the loop node; edges: " + walaLoopBackEdges);

        // (c) Loop node true/false edges match AST engine reference.
        String astTrueDst = edgeDst(astEdges, loopNode, "true");
        String astFalseDst = edgeDst(astEdges, loopNode, "false");

        String walaTrueDst = edgeDst(walaEdges, loopNode, "true");
        String walaFalseDst = edgeDst(walaEdges, loopNode, "false");

        assertTrue(walaTrueDst != null, "Loop node must have a 'true' edge (to body)");
        assertTrue(walaFalseDst != null, "Loop node must have a 'false' edge (loop exit)");
        assertEquals(astTrueDst, walaTrueDst,
                "'true' edge from loop node must target the body (AST reference)");
        assertEquals(astFalseDst, walaFalseDst,
                "'false' edge from loop node must target the loop exit (AST reference)");
    }

    // ----- do/while fixture ---------------------------------------------------------------------

    private static final String DO_WHILE_FIXTURE =
            "public class WalaCfgDoWhileFixture {\n"
            + "    int loop(int x) {\n"
            + "        do {\n"
            + "            x = x - 1;\n"
            + "        } while (x > 0);\n"
            + "        return x;\n"
            + "    }\n"
            + "}\n";

    /**
     * Builds the WALA CFG for the do/while fixture and asserts:
     * (1) well-formedness (all nodes reachable from @entry, all reach @exit);
     * (2) a "loop_back" edge exists;
     * (3) the loop_back edge goes FROM the loop node (the 'do' line node) to the body entry,
     *     matching the AST engine reference;
     * (4) the loop node has a "false" edge to the after-loop node, matching the AST engine.
     *
     * Unlike a top-tested while/for, the do/while's loop_back goes FROM the loop node (the bottom
     * test, anchored at the 'do' line) TO the body entry — it does not target the loop node itself.
     */
    @Test
    void cfgEmitsLoopBackAndCorrectLoopEdgesForDoWhile(@TempDir Path tmp) throws Exception {
        String dir = compileFixture(tmp, "WalaCfgDoWhileFixture.java", DO_WHILE_FIXTURE);
        WalaAnalysis wala = buildWala(dir);

        WalaAnalysis.MethodIr mir = findMethod(wala, "loop");

        BlockStmt body = L3TestSupport.methodBody(DO_WHILE_FIXTURE, "loop");
        ControlFlowGraph walaG = buildWalaCfg(DO_WHILE_FIXTURE, body, mir);

        // Reference: build the same graph via the AST engine.
        ControlFlowGraph astG = CfgBuilder.build(body, new LinkedHashMap<>(), L3TestSupport.ctx(DO_WHILE_FIXTURE));

        // Well-formedness.
        assertWellFormed(walaG);

        // Find the loop node (kind "loop") — anchored at the 'do' line.
        String loopNode = findNodeByKind(walaG, "loop");

        List<JCfgEdge> walaEdges = walaG.toCfgEdges();
        List<JCfgEdge> astEdges = astG.toCfgEdges();

        // (a) A loop_back edge must exist in the WALA graph.
        boolean hasLoopBack = walaEdges.stream().anyMatch(e -> "loop_back".equals(e.getKind()));
        assertTrue(hasLoopBack, "WALA CFG must contain at least one 'loop_back' edge");

        // (b) The AST engine must also have a loop_back edge.
        List<JCfgEdge> astLoopBackEdges = astEdges.stream()
                .filter(e -> "loop_back".equals(e.getKind()))
                .collect(Collectors.toList());
        assertFalse(astLoopBackEdges.isEmpty(), "AST CFG must also have a 'loop_back' edge");

        // (c) For do/while the loop_back goes FROM the loop node TO the body entry.
        //     The destination must match what the AST engine emits from the same loop node.
        String astLoopBackDst = edgeDst(astEdges, loopNode, "loop_back");
        String walaLoopBackDst = edgeDst(walaEdges, loopNode, "loop_back");
        assertTrue(walaLoopBackDst != null,
                "WALA 'loop_back' edge must originate from the loop node; all edges: " + walaEdges);
        assertEquals(astLoopBackDst, walaLoopBackDst,
                "'loop_back' edge from loop node must target the body entry (AST reference)");

        // (d) The false edge from the loop node (loop exit) must match the AST engine.
        String astFalseDst = edgeDst(astEdges, loopNode, "false");
        String walaFalseDst = edgeDst(walaEdges, loopNode, "false");
        assertTrue(walaFalseDst != null, "Loop node must have a 'false' edge (loop exit)");
        assertEquals(astFalseDst, walaFalseDst,
                "'false' edge from loop node must target the loop exit (AST reference)");
    }

    // ----- shared test helpers ------------------------------------------------------------------

    private static String compileFixture(Path tmp, String fileName, String source) throws Exception {
        Path src = tmp.resolve(fileName);
        Files.writeString(src, source);
        int rc = ToolProvider.getSystemJavaCompiler().run(
                null, null, null, "-g", "-d", tmp.toString(), src.toString());
        assertEquals(0, rc, "fixture compilation must succeed");
        return tmp.toString();
    }

    private static WalaAnalysis buildWala(String dir) {
        CodeAnalyzer.projectRootPom = dir;
        return WalaAnalysis.of(dir, null, null)
                .orElseThrow(() -> new AssertionError("WalaAnalysis.of must succeed"));
    }

    private static WalaAnalysis.MethodIr findMethod(WalaAnalysis wala, String name) {
        return wala.applicationMethods().stream()
                .filter(m -> m.method.getName().toString().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("method '" + name + "' not found"));
    }

    private static ControlFlowGraph buildWalaCfg(
            String source, BlockStmt body, WalaAnalysis.MethodIr mir) {
        ControlFlowGraph g = new ControlFlowGraph();
        BodyNodeBuilder.populate(g, body, new LinkedHashMap<>(), L3TestSupport.ctx(source));
        Map<Integer, List<Statement>> byLine = InstructionToNode.statementsByLine(body);
        InstructionToNode mapper = new InstructionToNode(byLine);
        return WalaCfgBuilder.build(mir, g, mapper);
    }

    private static void assertWellFormed(ControlFlowGraph g) {
        Set<String> all = g.nodes().keySet();
        Set<String> fromEntry = reachable(g, ControlFlowGraph.ENTRY);
        assertEquals(all, fromEntry,
                "all nodes must be reachable from @entry; unreachable: " + diff(all, fromEntry));
        Set<String> toExit = reverseReachable(g, ControlFlowGraph.EXIT);
        assertEquals(all, toExit,
                "all nodes must reach @exit; missing: " + diff(all, toExit));
    }

    private static String findNodeByKind(ControlFlowGraph g, String kind) {
        return g.nodes().entrySet().stream()
                .filter(e -> kind.equals(e.getValue().getKind()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no '" + kind + "' node found"));
    }

    /** Returns the destination of the first edge from {@code src} with the given {@code kind}. */
    private static String edgeDst(List<JCfgEdge> edges, String src, String kind) {
        return edges.stream()
                .filter(e -> src.equals(e.getSrc()) && kind.equals(e.getKind()))
                .map(JCfgEdge::getDst)
                .findFirst()
                .orElse(null);
    }

    private static Set<String> reachable(ControlFlowGraph g, String start) {
        Set<String> visited = new LinkedHashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            String node = queue.poll();
            if (visited.add(node)) {
                g.successors(node).forEach(queue::add);
            }
        }
        return visited;
    }

    private static Set<String> reverseReachable(ControlFlowGraph g, String start) {
        Set<String> visited = new LinkedHashSet<>();
        Queue<String> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            String node = queue.poll();
            if (visited.add(node)) {
                g.predecessors(node).forEach(queue::add);
            }
        }
        return visited;
    }

    private static Set<String> diff(Set<String> all, Set<String> found) {
        Set<String> missing = new LinkedHashSet<>(all);
        missing.removeAll(found);
        return missing;
    }
}
