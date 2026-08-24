package com.ibm.cldk.syntactic_analysis.dataflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.cldk.schema.JDdgEdge;
import com.ibm.cldk.syntactic_analysis.L3TestSupport;
import com.ibm.cldk.syntactic_analysis.controlflow.CfgBuilder;
import com.ibm.cldk.syntactic_analysis.controlflow.ControlFlowGraph;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class DdgBuilderTest {

    private List<JDdgEdge> ddg(String bodyBlock) {
        String src = "class Foo { void m() " + bodyBlock + " }";
        ControlFlowGraph g =
                CfgBuilder.build(L3TestSupport.methodBody(src, "m"), new LinkedHashMap<>(), L3TestSupport.ctx(src));
        return DdgBuilder.build(g, 3);
    }

    @Test
    void defReachesLaterUseOfSameLocal() {
        List<JDdgEdge> d = ddg("{ int x = 1;\n use(x); }");
        assertTrue(d.stream().anyMatch(e -> e.getVar().equals("x") && e.getProv().equals(List.of("ssa"))),
                "the declaration's definition of x reaches its later use");
    }

    @Test
    void reassignmentKillsTheEarlierDefinition() {
        // Only the x=2 definition reaches use(x); x=1 is killed. So exactly one def-source for var x.
        List<JDdgEdge> d = ddg("{ int x = 1;\n x = 2;\n use(x); }");
        long xEdges = d.stream().filter(e -> e.getVar().equals("x")).count();
        assertEquals(1, xEdges, "the reassignment kills the earlier definition, so only one reaches the use");
    }

    @Test
    void fieldAccessPathIsSpelledAndTracked() {
        List<JDdgEdge> d = ddg("{ this.f = 1;\n use(this.f); }");
        assertTrue(d.stream().anyMatch(e -> e.getVar().equals("this.f")),
                "a field write reaches a later field read under the spelled access path");
    }

    @Test
    void arrayElementDefUseCollapsesTheIndexToStar() {
        List<JDdgEdge> d = ddg("{ int[] arr = new int[3];\n arr[0] = 1;\n use(arr[1]); }");
        assertTrue(d.stream().anyMatch(e -> e.getVar().equals("arr[*]")),
                "array element def-use is index-insensitive: arr[0] reaches arr[1] under arr[*]");
    }

    @Test
    void loopCarriedDefUseIsCaptured() {
        // s is defined before the loop and re-defined in the body; the body's use of s is reached both
        // from the initial def and, loop-carried, from the body's own def.
        List<JDdgEdge> d = ddg("{ int s = 0;\n while (s < 3) { s = s + 1; }\n use(s); }");
        assertTrue(d.stream().filter(e -> e.getVar().equals("s")).count() >= 2,
                "s has multiple reaching definitions (initial and loop-carried)");
    }

    @Test
    void ddgIsDeterministic() {
        String one = ddg("{ int x = 1;\n int y = x + 1;\n use(y); }").toString();
        String two = ddg("{ int x = 1;\n int y = x + 1;\n use(y); }").toString();
        assertEquals(one, two);
    }
}
