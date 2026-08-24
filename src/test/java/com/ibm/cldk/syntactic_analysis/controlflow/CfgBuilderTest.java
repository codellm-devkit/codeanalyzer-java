package com.ibm.cldk.syntactic_analysis.controlflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.ibm.cldk.schema.JBodyNode;
import com.ibm.cldk.schema.JCfgEdge;
import com.ibm.cldk.syntactic_analysis.L3TestSupport;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CfgBuilderTest {

    private ControlFlowGraph build(String bodyBlock) {
        String src = "class Foo { void m() " + bodyBlock + " }";
        BlockStmt b = L3TestSupport.methodBody(src, "m");
        return CfgBuilder.build(b, new LinkedHashMap<>(), L3TestSupport.ctx(src));
    }

    private static boolean edge(ControlFlowGraph g, String src, String dst, String kind) {
        return g.toCfgEdges().stream()
                .anyMatch(e -> src.equals(e.getSrc()) && dst.equals(e.getDst()) && kind.equals(e.getKind()));
    }

    @Test
    void straightLineSequenceChainsFallthroughFromEntryToExit() {
        ControlFlowGraph g = build("{ int x = 1;\n int y = 2; }");

        assertEquals("entry", g.nodes().get("@entry").getKind());
        assertEquals("exit", g.nodes().get("@exit").getKind());

        String a = g.successors("@entry").get(0); // first statement
        String b = g.successors(a).get(0); // second statement
        assertNotEquals("@exit", a, "there must be a statement node between entry and exit");
        assertEquals("@exit", g.successors(b).get(0));

        assertTrue(edge(g, "@entry", a, "fallthrough"));
        assertTrue(edge(g, a, b, "fallthrough"));
        assertTrue(edge(g, b, "@exit", "fallthrough"));

        assertEquals("statement", g.nodes().get(a).getKind());
        assertNotNull(g.nodes().get(a).getSpan(), "a statement node must carry a real span");
    }

    @Test
    void anEmptyBodyConnectsEntryDirectlyToExit() {
        ControlFlowGraph g = build("{ }");
        assertEquals("@exit", g.successors("@entry").get(0));
    }

    @Test
    void edgesAreDeterministicallyOrdered() {
        String one = build("{ int x = 1;\n int y = 2; }").toCfgEdges().toString();
        String two = build("{ int x = 1;\n int y = 2; }").toCfgEdges().toString();
        assertEquals(one, two);
    }

    @Test
    void returnStatementEdgesToExitWithReturnKind() {
        ControlFlowGraph g = build("{ int x = 1;\n return; }");
        String ret = nodeOfKind(g, "return");
        assertEquals("return", g.nodes().get(ret).getKind());
        assertTrue(edge(g, ret, "@exit", "return"), "return edges to exit with kind 'return'");
    }

    @Test
    void throwStatementEdgesToExit() {
        ControlFlowGraph g = build("{ throw new RuntimeException(); }");
        assertTrue(g.predecessors("@exit").stream().anyMatch(id -> !id.equals("@entry")),
                "an uncaught throw connects to @exit");
    }

    @Test
    void bareCallStatementReusesTheL1CallNodeAndKeepsKindCall() {
        // A receiver call: the statement begins at `this`, but its L1 call node is keyed at the `foo`
        // name token — so this only reuses if the CFG keys the statement at the call anchor, not begin.
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

        ControlFlowGraph g = CfgBuilder.build(b, existing, L3TestSupport.ctx(src));

        assertSame(callNode, g.nodes().get(callId), "the L1 call node object is reused, not replaced");
        assertEquals("call", g.nodes().get(callId).getKind(), "kind stays 'call' (additive invariant)");
        assertTrue(g.successors("@entry").contains(callId), "the call node is wired into the CFG");
    }

    @Test
    void ifWithoutElseBranchesTrueToThenAndFalseToJoin() {
        ControlFlowGraph g = build("{ if (a()) { b(); }\n c(); }");
        String br = nodeOfKind(g, "branch");
        String trueDst = edgeDst(g, br, "true"); // the then-body (b)
        String falseDst = edgeDst(g, br, "false"); // the join (c), no else
        assertEquals(falseDst, g.successors(trueDst).get(0),
                "the then-arm falls through to the same following statement the false edge targets");
    }

    @Test
    void ifElseJoinsBothArmsAtTheFollowingStatement() {
        ControlFlowGraph g = build("{ if (a()) { b(); } else { d(); }\n c(); }");
        String br = nodeOfKind(g, "branch");
        String trueDst = edgeDst(g, br, "true"); // b
        String falseDst = edgeDst(g, br, "false"); // d
        assertNotEquals(trueDst, falseDst, "the two arms start at distinct statements");
        assertEquals(g.successors(trueDst).get(0), g.successors(falseDst).get(0),
                "both arms rejoin at the following statement");
    }

    /** The destination of the (single) edge leaving {@code src} with the given kind. */
    private static String edgeDst(ControlFlowGraph g, String src, String kind) {
        return g.toCfgEdges().stream()
                .filter(e -> e.getSrc().equals(src) && e.getKind().equals(kind))
                .map(JCfgEdge::getDst)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + kind + " edge from " + src));
    }

    /** The id of the (single) non-synthetic node with the given kind. */
    private static String nodeOfKind(ControlFlowGraph g, String kind) {
        return g.nodes().entrySet().stream()
                .filter(e -> kind.equals(e.getValue().getKind()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no node of kind " + kind));
    }
}
