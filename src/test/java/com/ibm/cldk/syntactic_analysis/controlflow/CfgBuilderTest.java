package com.ibm.cldk.syntactic_analysis.controlflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.ibm.cldk.schema.JBodyNode;
import com.ibm.cldk.schema.JCfgEdge;
import com.ibm.cldk.syntactic_analysis.L3TestSupport;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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

    @Test
    void whileLoopHasTrueIntoBodyLoopBackAndFalseToExit() {
        ControlFlowGraph g = build("{ while (a()) { b(); }\n c(); }");
        String loop = nodeOfKind(g, "loop");
        assertTrue(hasEdge(g, loop, "true"), "true edge enters the body");
        assertTrue(g.toCfgEdges().stream().anyMatch(e -> e.getDst().equals(loop) && e.getKind().equals("loop_back")),
                "the body loops back to the loop test");
        assertTrue(hasEdge(g, loop, "false"), "false edge leaves the loop");
    }

    @Test
    void forLoopProducesALoopNodeWithABackEdge() {
        ControlFlowGraph g = build("{ for (int i = 0; i < 3; i++) { b(); } }");
        String loop = nodeOfKind(g, "loop");
        assertTrue(g.toCfgEdges().stream().anyMatch(e -> e.getDst().equals(loop) && e.getKind().equals("loop_back")));
    }

    @Test
    void forEachLoopProducesALoopNodeWithABackEdge() {
        ControlFlowGraph g = build("{ for (String s : items()) { b(); } }");
        String loop = nodeOfKind(g, "loop");
        assertTrue(g.toCfgEdges().stream().anyMatch(e -> e.getDst().equals(loop) && e.getKind().equals("loop_back")));
    }

    @Test
    void doWhileEntersTheBodyBeforeTheTest() {
        // The do-while's entry is the body, not the loop test; the test sits below and loops back up.
        ControlFlowGraph g = build("{ do { b(); } while (a());\n c(); }");
        String loop = nodeOfKind(g, "loop");
        String entry = g.successors("@entry").get(0);
        assertNotEquals(loop, entry, "the loop test is not the do-while's entry");
        assertTrue(g.toCfgEdges().stream().anyMatch(e -> e.getSrc().equals(loop) && e.getKind().equals("loop_back")));
    }

    @Test
    void breakEdgesToTheLoopExit() {
        ControlFlowGraph g = build("{ while (a()) { if (b()) break;\n d(); } }");
        assertTrue(g.toCfgEdges().stream().anyMatch(e -> e.getKind().equals("break")),
                "break produces a break edge out of the loop");
    }

    @Test
    void continueEdgesToTheLoopTest() {
        ControlFlowGraph g = build("{ while (a()) { if (b()) continue;\n d(); } }");
        String loop = nodeOfKind(g, "loop");
        assertTrue(g.toCfgEdges().stream().anyMatch(e -> e.getKind().equals("continue") && e.getDst().equals(loop)),
                "continue targets the loop test");
    }

    @Test
    void labeledContinueTargetsTheOuterLoop() {
        ControlFlowGraph g = build(
                "{ outer: for (int i = 0; a(); i++) { for (int j = 0; b(); j++) { continue outer; } }\n c(); }");
        String outerLoop = g.successors("@entry").get(0);
        assertEquals("loop", g.nodes().get(outerLoop).getKind());
        String contDst = g.toCfgEdges().stream()
                .filter(e -> e.getKind().equals("continue"))
                .map(JCfgEdge::getDst)
                .findFirst()
                .orElseThrow();
        assertEquals(outerLoop, contDst, "continue outer targets the outer loop test, not the inner");
    }

    @Test
    void classicSwitchFallsThroughBetweenCases() {
        // A default clause suppresses the implicit no-match edge, so caseTargets are exactly the bodies.
        ControlFlowGraph g = build(
                "{ switch (a()) { case 1: b();\n case 2: d(); break;\n default: e(); }\n c(); }");
        String sw = nodeOfKind(g, "switch");
        List<String> cases = caseTargets(g, sw);
        assertTrue(cases.size() >= 2, "each case is selected by a switch_case edge");
        assertTrue(g.toCfgEdges().stream().anyMatch(e -> e.getKind().equals("fallthrough")
                && cases.contains(e.getSrc()) && cases.contains(e.getDst())),
                "a classic case falls through to the next");
    }

    @Test
    void arrowSwitchHasNoInterCaseFallthrough() {
        ControlFlowGraph g = build(
                "{ switch (a()) { case 1 -> b();\n case 2 -> d();\n default -> e(); }\n c(); }");
        String sw = nodeOfKind(g, "switch");
        List<String> cases = caseTargets(g, sw);
        assertFalse(g.toCfgEdges().stream().anyMatch(e -> e.getKind().equals("fallthrough")
                && cases.contains(e.getSrc()) && cases.contains(e.getDst())),
                "arrow cases do not fall through to one another");
    }

    @Test
    void switchBreakEdgesOutOfTheSwitch() {
        ControlFlowGraph g = build("{ switch (a()) { case 1: break;\n default: d(); }\n c(); }");
        assertTrue(g.toCfgEdges().stream().anyMatch(e -> e.getKind().equals("break")),
                "a case break edges out of the switch to the join");
    }

    @Test
    void tryCatchRoutesAnExceptionEdgeToTheCatch() {
        ControlFlowGraph g = build("{ try { risky(); } catch (Exception e) { handle(); }\n c(); }");
        assertTrue(g.toCfgEdges().stream().anyMatch(e -> e.getKind().equals("exception")),
                "a throwing statement in the try body routes an exception edge to the catch");
    }

    @Test
    void finallyIsReachedOnNormalAndExceptionalPaths() {
        ControlFlowGraph g = build("{ try { risky(); } finally { cleanup(); }\n c(); }");
        boolean reachedBothWays = g.nodes().keySet().stream().anyMatch(n ->
                inEdges(g, n).stream().anyMatch(e -> e.getKind().equals("exception"))
                        && inEdges(g, n).stream().anyMatch(e -> !e.getKind().equals("exception")));
        assertTrue(reachedBothWays, "the finally block is reached on both normal and exceptional paths");
    }

    @Test
    void tryWithResourcesRoutesAnExceptionEdgeToTheCatch() {
        ControlFlowGraph g = build(
                "{ try (AutoCloseable r = open()) { risky(); } catch (Exception e) { handle(); }\n c(); }");
        assertTrue(g.toCfgEdges().stream().anyMatch(e -> e.getKind().equals("exception")),
                "try-with-resources routes exceptions like a plain try");
    }

    @Test
    void synchronizedLinksItsBodyIntoTheFlow() {
        ControlFlowGraph g = build("{ synchronized (lock) { b(); }\n c(); }");
        assertNotEquals("@exit", g.successors("@entry").get(0), "the synchronized body is on the path");
        assertWellFormed(g);
    }

    @Test
    void infiniteLoopStillReachesExit() {
        // while(true) has no normal exit, but the false edge keeps @exit reachable (post-dominance total).
        assertWellFormed(build("{ while (true) { b(); } }"));
    }

    /** The edges entering {@code n}. */
    private static List<JCfgEdge> inEdges(ControlFlowGraph g, String n) {
        return g.toCfgEdges().stream().filter(e -> e.getDst().equals(n)).collect(Collectors.toList());
    }

    /** Every CFG-edge endpoint is reachable from @entry and reaches @exit. */
    private static void assertWellFormed(ControlFlowGraph g) {
        Set<String> endpoints = new HashSet<>();
        g.toCfgEdges().forEach(e -> {
            endpoints.add(e.getSrc());
            endpoints.add(e.getDst());
        });
        Set<String> fromEntry = reachable(g, "@entry", true);
        Set<String> toExit = reachable(g, "@exit", false);
        for (String n : endpoints) {
            assertTrue(fromEntry.contains(n), n + " is unreachable from @entry");
            assertTrue(toExit.contains(n), n + " does not reach @exit");
        }
    }

    private static Set<String> reachable(ControlFlowGraph g, String start, boolean forward) {
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty()) {
            String x = queue.poll();
            for (String y : forward ? g.successors(x) : g.predecessors(x)) {
                if (seen.add(y)) {
                    queue.add(y);
                }
            }
        }
        return seen;
    }

    /** The switch_case targets leaving the switch node. */
    private static List<String> caseTargets(ControlFlowGraph g, String sw) {
        return g.toCfgEdges().stream()
                .filter(e -> e.getSrc().equals(sw) && e.getKind().equals("switch_case"))
                .map(JCfgEdge::getDst)
                .collect(Collectors.toList());
    }

    private static boolean hasEdge(ControlFlowGraph g, String src, String kind) {
        return g.toCfgEdges().stream().anyMatch(e -> e.getSrc().equals(src) && e.getKind().equals(kind));
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
