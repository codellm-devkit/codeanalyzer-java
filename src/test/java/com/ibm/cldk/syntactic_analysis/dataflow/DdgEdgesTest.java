package com.ibm.cldk.syntactic_analysis.dataflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.ibm.cldk.schema.JBodyNode;
import com.ibm.cldk.schema.JCallable;
import com.ibm.cldk.schema.JDdgEdge;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DdgEdgesTest {

    private static JCallable callableWithBody(String... keys) {
        JCallable c = new JCallable();
        Map<String, JBodyNode> body = new LinkedHashMap<>();
        for (String k : keys) {
            JBodyNode n = new JBodyNode();
            n.setKind("statement");
            body.put(k, n);
        }
        c.setBody(body);
        return c;
    }

    private static JDdgEdge edge(String src, String dst) {
        JDdgEdge e = new JDdgEdge();
        e.setSrc(src);
        e.setDst(dst);
        e.setVar("v");
        e.getProv().add("points-to");
        return e;
    }

    @Test
    void dropsEdgesWhoseEndpointIsNotABodyNode() {
        JCallable c = callableWithBody("10:9", "12:9");
        List<JDdgEdge> kept = DdgEdges.dropDangling(
                c,
                Arrays.asList(
                        edge("10:9", "12:9"), // both resolve
                        edge("11:0", "12:9"), // src is the unmapped sentinel
                        edge("10:9", "11:0"))); // dst is the unmapped sentinel

        assertEquals(1, kept.size(), "only the edge that resolves on both ends survives");
        assertEquals("10:9", kept.get(0).getSrc());
        assertEquals("12:9", kept.get(0).getDst());
    }

    @Test
    void keepsEveryEdgeWhenAllEndpointsResolve() {
        JCallable c = callableWithBody("@entry", "10:9", "10:9/actual_in:0");
        List<JDdgEdge> edges = Arrays.asList(edge("@entry", "10:9"), edge("10:9", "10:9/actual_in:0"));
        assertEquals(edges, DdgEdges.dropDangling(c, edges));
    }

    @Test
    void passesEmptyAndBodylessInputStraightThrough() {
        List<JDdgEdge> empty = new ArrayList<>();
        assertSame(empty, DdgEdges.dropDangling(callableWithBody("10:9"), empty));

        JCallable noBody = new JCallable();
        noBody.setBody(null);
        List<JDdgEdge> edges = Arrays.asList(edge("10:9", "11:0"));
        assertSame(edges, DdgEdges.dropDangling(noBody, edges), "no body map ⇒ nothing to check against");
    }
}
