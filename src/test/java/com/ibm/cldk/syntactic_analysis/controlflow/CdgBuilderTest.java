package com.ibm.cldk.syntactic_analysis.controlflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.cldk.schema.JCdgEdge;
import com.ibm.cldk.syntactic_analysis.L3TestSupport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CdgBuilderTest {

    private ControlFlowGraph cfg(String bodyBlock) {
        String src = "class Foo { void m() " + bodyBlock + " }";
        return CfgBuilder.build(L3TestSupport.methodBody(src, "m"), new LinkedHashMap<>(), L3TestSupport.ctx(src));
    }

    @Test
    void thenBranchIsControlDependentOnTheIfTestButTheJoinIsNot() {
        ControlFlowGraph g = cfg("{ if (a()) { b(); }\n d(); }");
        List<JCdgEdge> cdg = CdgBuilder.build(g);
        String br = nodeOfKind(g, "branch");
        String thenNode = edgeDst(g, br, "true"); // b
        String join = edgeDst(g, br, "false"); // d (no else -> the join)
        assertTrue(cdg.stream().anyMatch(e -> e.getSrc().equals(br) && e.getDst().equals(thenNode)),
                "the then-body is control-dependent on the if test");
        assertTrue(cdg.stream().noneMatch(e -> e.getDst().equals(join)),
                "the post-if join is not control-dependent on the branch (it post-dominates it)");
    }

    @Test
    void loopBodyIsControlDependentOnTheLoopTest() {
        ControlFlowGraph g = cfg("{ while (a()) { b(); }\n d(); }");
        List<JCdgEdge> cdg = CdgBuilder.build(g);
        String loop = nodeOfKind(g, "loop");
        String body = edgeDst(g, loop, "true");
        assertTrue(cdg.stream().anyMatch(e -> e.getSrc().equals(loop) && e.getDst().equals(body)),
                "the loop body is control-dependent on the loop test");
        String after = edgeDst(g, loop, "false"); // d
        assertTrue(cdg.stream().noneMatch(e -> e.getDst().equals(after)),
                "code after the loop is not control-dependent on the loop test");
    }

    @Test
    void ifElseMakesBothArmsControlDependentOnTheBranch() {
        ControlFlowGraph g = cfg("{ if (a()) { b(); } else { d(); }\n e(); }");
        List<JCdgEdge> cdg = CdgBuilder.build(g);
        String br = nodeOfKind(g, "branch");
        String thenNode = edgeDst(g, br, "true");
        String elseNode = edgeDst(g, br, "false");
        assertTrue(cdg.stream().anyMatch(e -> e.getSrc().equals(br) && e.getDst().equals(thenNode)));
        assertTrue(cdg.stream().anyMatch(e -> e.getSrc().equals(br) && e.getDst().equals(elseNode)));
        assertNotEquals(thenNode, elseNode);
    }

    @Test
    void nestedBranchesChainControlDependence() {
        ControlFlowGraph g = cfg("{ if (a()) { if (b()) { d(); } }\n e(); }");
        List<JCdgEdge> cdg = CdgBuilder.build(g);
        String outer = g.successors("@entry").get(0);
        String inner = edgeDst(g, outer, "true");
        String d = edgeDst(g, inner, "true");
        assertEquals("branch", g.nodes().get(outer).getKind());
        assertEquals("branch", g.nodes().get(inner).getKind());
        assertTrue(cdg.stream().anyMatch(e -> e.getSrc().equals(outer) && e.getDst().equals(inner)),
                "the inner branch is control-dependent on the outer branch");
        assertTrue(cdg.stream().anyMatch(e -> e.getSrc().equals(inner) && e.getDst().equals(d)),
                "the innermost body is control-dependent on the inner branch, not the outer");
        assertTrue(cdg.stream().noneMatch(e -> e.getSrc().equals(outer) && e.getDst().equals(d)),
                "d is not directly control-dependent on the outer branch");
    }

    @Test
    void earlyReturnMakesFollowingCodeControlDependentOnTheBranch() {
        // The early return means d() runs only if the branch is false — so d IS control-dependent on it.
        ControlFlowGraph g = cfg("{ if (a()) { return; }\n d(); }");
        List<JCdgEdge> cdg = CdgBuilder.build(g);
        String br = nodeOfKind(g, "branch");
        String ret = edgeDst(g, br, "true");
        String d = edgeDst(g, br, "false");
        assertTrue(cdg.stream().anyMatch(e -> e.getSrc().equals(br) && e.getDst().equals(ret)),
                "the guarded return is control-dependent on the branch");
        assertTrue(cdg.stream().anyMatch(e -> e.getSrc().equals(br) && e.getDst().equals(d)),
                "code after an early return is control-dependent on the branch that may skip it");
    }

    @Test
    void switchCasesAreControlDependentOnTheSelector() {
        ControlFlowGraph g = cfg("{ switch (a()) { case 1: b(); break;\n default: d(); }\n e(); }");
        List<JCdgEdge> cdg = CdgBuilder.build(g);
        String sw = nodeOfKind(g, "switch");
        assertTrue(cdg.stream().anyMatch(e -> e.getSrc().equals(sw)),
                "a case body is control-dependent on the switch selector");
    }

    @Test
    void cdgIsDeterministic() {
        String one = CdgBuilder.build(cfg("{ if (a()) { b(); } else { d(); }\n e(); }")).toString();
        String two = CdgBuilder.build(cfg("{ if (a()) { b(); } else { d(); }\n e(); }")).toString();
        assertEquals(one, two);
    }

    private static String nodeOfKind(ControlFlowGraph g, String kind) {
        return g.nodes().entrySet().stream()
                .filter(e -> kind.equals(e.getValue().getKind()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no node of kind " + kind));
    }

    private static String edgeDst(ControlFlowGraph g, String src, String kind) {
        return g.toCfgEdges().stream()
                .filter(e -> e.getSrc().equals(src) && e.getKind().equals(kind))
                .map(com.ibm.cldk.schema.JCfgEdge::getDst)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + kind + " edge from " + src));
    }
}
