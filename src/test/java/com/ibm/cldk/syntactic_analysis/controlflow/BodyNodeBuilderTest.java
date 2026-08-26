package com.ibm.cldk.syntactic_analysis.controlflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.ibm.cldk.schema.JBodyNode;
import com.ibm.cldk.syntactic_analysis.L3TestSupport;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BodyNodeBuilderTest {

    /** Build the reference node set via CfgBuilder (the unchanged AST engine). */
    private static ControlFlowGraph ref(String bodyBlock) {
        String src = "class Foo { void m() " + bodyBlock + " }";
        BlockStmt b = L3TestSupport.methodBody(src, "m");
        return CfgBuilder.build(b, new LinkedHashMap<>(), L3TestSupport.ctx(src));
    }

    /** Build a fresh graph populated only via BodyNodeBuilder.populate (no edges). */
    private static ControlFlowGraph populated(String bodyBlock) {
        String src = "class Foo { void m() " + bodyBlock + " }";
        BlockStmt b = L3TestSupport.methodBody(src, "m");
        ControlFlowGraph g = new ControlFlowGraph();
        BodyNodeBuilder.populate(g, b, new LinkedHashMap<>(), L3TestSupport.ctx(src));
        return g;
    }

    /** Assert that populate produces the same node-id set with the same kinds as CfgBuilder. */
    private static void assertNodeSetMatchesRef(String bodyBlock) {
        ControlFlowGraph reference = ref(bodyBlock);
        ControlFlowGraph result = populated(bodyBlock);

        assertEquals(reference.nodes().keySet(), result.nodes().keySet(),
                "node id sets must match for: " + bodyBlock);
        for (String id : reference.nodes().keySet()) {
            assertEquals(reference.nodes().get(id).getKind(), result.nodes().get(id).getKind(),
                    "kind mismatch at " + id + " for: " + bodyBlock);
        }
    }

    @Test
    void populateProducesTheSameNodeSetAsCfgBuildForIfWithoutElse() {
        assertNodeSetMatchesRef("{ if (a()) { b(); }\n c(); }");
    }

    @Test
    void everyNonSyntheticNodeHasARecordedAst() {
        String bodyBlock = "{ if (a()) { b(); }\n c(); }";
        ControlFlowGraph g = populated(bodyBlock);
        for (String id : g.nodes().keySet()) {
            if (!id.startsWith("@")) {
                assertNotNull(g.astNode(id), "no AST recorded for non-synthetic node: " + id);
            }
        }
    }

    @Test
    void syntheticEntryAndExitArePresent() {
        ControlFlowGraph g = populated("{ }");
        assertNotNull(g.nodes().get(ControlFlowGraph.ENTRY), "@entry must be present");
        assertNotNull(g.nodes().get(ControlFlowGraph.EXIT), "@exit must be present");
        assertEquals("entry", g.nodes().get(ControlFlowGraph.ENTRY).getKind());
        assertEquals("exit", g.nodes().get(ControlFlowGraph.EXIT).getKind());
    }

    @Test
    void seedsExistingL1CallNodeAndKeepsKindCall() {
        String src = "class Foo { void m(){ this.foo(); } void foo(){} }";
        BlockStmt b = L3TestSupport.methodBody(src, "m");
        MethodCallExpr call = b.findFirst(MethodCallExpr.class).orElseThrow();
        int line = call.getName().getRange().orElseThrow().begin.line;
        int col = call.getName().getRange().orElseThrow().begin.column;
        String callId = line + ":" + col;

        Map<String, JBodyNode> existing = new LinkedHashMap<>();
        JBodyNode callNode = new JBodyNode();
        callNode.setKind("call");
        existing.put(callId, callNode);

        ControlFlowGraph g = new ControlFlowGraph();
        BodyNodeBuilder.populate(g, b, existing, L3TestSupport.ctx(src));

        assertSame(callNode, g.nodes().get(callId), "the L1 call node object must be reused, not replaced");
        assertEquals("call", g.nodes().get(callId).getKind(), "kind stays 'call' (additive invariant)");
    }

    @Test
    void populateMatchesRefForWhileLoop() {
        assertNodeSetMatchesRef("{ while (a()) { b(); }\n c(); }");
    }

    @Test
    void populateMatchesRefForForLoop() {
        assertNodeSetMatchesRef("{ for (int i = 0; i < 3; i++) { b(); } }");
    }

    @Test
    void populateMatchesRefForDoWhile() {
        assertNodeSetMatchesRef("{ do { b(); } while (a());\n c(); }");
    }

    @Test
    void populateMatchesRefForSwitchStatement() {
        assertNodeSetMatchesRef(
                "{ switch (a()) { case 1: b();\n default: d(); }\n c(); }");
    }

    @Test
    void populateMatchesRefForTryCatch() {
        assertNodeSetMatchesRef("{ try { risky(); } catch (Exception e) { handle(); }\n c(); }");
    }

    @Test
    void populateMatchesRefForTryFinally() {
        assertNodeSetMatchesRef("{ try { risky(); } finally { cleanup(); }\n c(); }");
    }

    @Test
    void populateMatchesRefForReturnStatement() {
        assertNodeSetMatchesRef("{ int x = 1;\n return; }");
    }

    @Test
    void populateMatchesRefForNestedIf() {
        assertNodeSetMatchesRef("{ if (a()) { if (b()) { c(); } else { d(); } }\n e(); }");
    }
}
