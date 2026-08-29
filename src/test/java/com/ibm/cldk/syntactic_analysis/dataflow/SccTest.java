package com.ibm.cldk.syntactic_analysis.dataflow;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ibm.cldk.schema.JCallEdge;
import java.util.List;
import org.junit.jupiter.api.Test;

class SccTest {

    private static JCallEdge e(String s, String d) {
        JCallEdge x = new JCallEdge();
        x.setSrc(s);
        x.setDst(d);
        return x;
    }

    @Test
    void chainCondensesToSingletonsCalleesFirst() {
        List<List<String>> order = Scc.condense(List.of("a", "b", "c"), List.of(e("a", "b"), e("b", "c")));
        assertEquals(List.of(List.of("c"), List.of("b"), List.of("a")), order);
    }

    @Test
    void mutualRecursionFormsOneComponent() {
        List<List<String>> order = Scc.condense(
                List.of("even", "odd", "main"),
                List.of(e("even", "odd"), e("odd", "even"), e("main", "even")));
        assertEquals(2, order.size());
        assertEquals(List.of("even", "odd"), order.get(0), "SCC first (bottom-up), sorted within");
        assertEquals(List.of("main"), order.get(1));
    }

    @Test
    void deterministicRegardlessOfInputOrder() {
        List<List<String>> a = Scc.condense(List.of("x", "y"), List.of(e("x", "y")));
        List<List<String>> b = Scc.condense(List.of("y", "x"), List.of(e("x", "y")));
        assertEquals(a, b);
    }

    @Test
    void edgesToUnknownNodesAreIgnored() {
        List<List<String>> order = Scc.condense(List.of("a"), List.of(e("a", "can://…/@external/x")));
        assertEquals(List.of(List.of("a")), order);
    }
}
