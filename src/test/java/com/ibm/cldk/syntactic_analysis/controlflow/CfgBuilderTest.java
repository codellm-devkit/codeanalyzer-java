package com.ibm.cldk.syntactic_analysis.controlflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.javaparser.ast.stmt.BlockStmt;
import com.ibm.cldk.syntactic_analysis.L3TestSupport;
import java.util.LinkedHashMap;
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
}
