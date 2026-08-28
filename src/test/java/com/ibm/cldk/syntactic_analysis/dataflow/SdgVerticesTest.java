package com.ibm.cldk.syntactic_analysis.dataflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.cldk.schema.JBodyNode;
import com.ibm.cldk.schema.JCallable;
import com.ibm.cldk.schema.JIdEdge;
import com.ibm.cldk.schema.JModule;
import com.ibm.cldk.schema.JParameter;
import com.ibm.cldk.schema.JType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SdgVerticesTest {

    /** a(int x) at 3:16 calls b(int) — the minimal HRB shape. */
    private static Map<String, JModule> twoCallableModule() {
        JModule m = new JModule();
        m.setId("can://java/app/A.java");

        JCallable b = new JCallable();
        b.setId("can://java/app/A.java/A/b(int)");
        b.setReturnType("int");
        JParameter p = new JParameter();
        p.setName("y");
        p.setType("int");
        b.getParameters().add(p);

        JCallable a = new JCallable();
        a.setId("can://java/app/A.java/A/a(int)");
        a.setReturnType("int");
        JParameter px = new JParameter();
        px.setName("x");
        px.setType("int");
        a.getParameters().add(px);
        JBodyNode call = new JBodyNode();
        call.setKind("call");
        call.setCallee("can://java/app/A.java/A/b(int)");
        call.getArgumentExpr().add("x + 1");
        call.setReturnType("int");
        a.getBody().put("3:16", call);

        JType t = new JType();
        t.setId("can://java/app/A.java/A");
        t.getCallables().put("a(int)", a);
        t.getCallables().put("b(int)", b);
        m.getTypes().put("A", t);

        Map<String, JModule> modules = new LinkedHashMap<>();
        modules.put("A.java", m);
        return modules;
    }

    @Test
    void buildsFormalActualVerticesAndParamEdges() {
        Map<String, JModule> modules = twoCallableModule();
        SdgVertices.Result r = SdgVertices.apply(modules);

        JCallable a = modules.get("A.java").getTypes().get("A").getCallables().get("a(int)");
        JCallable b = modules.get("A.java").getTypes().get("A").getCallables().get("b(int)");

        assertEquals("formal_in", b.getBody().get("@formal_in:0").getKind());
        assertEquals("y", b.getBody().get("@formal_in:0").getOf());
        assertEquals("$ret", b.getBody().get("@formal_out").getOf());
        JBodyNode actualIn = a.getBody().get("3:16/actual_in:0");
        assertNotNull(actualIn, "actual_in child of the call node");
        assertEquals("3:16", actualIn.getParent());
        assertEquals("arg0", actualIn.getOf());
        assertNotNull(a.getBody().get("3:16/actual_out"));

        assertEquals(1, r.paramIn.size());
        JIdEdge in = r.paramIn.get(0);
        assertEquals("can://java/app/A.java/A/a(int)@3:16/actual_in:0", in.getSrc());
        assertEquals("can://java/app/A.java/A/b(int)@formal_in:0", in.getDst());
        assertEquals(1, r.paramOut.size());
        assertEquals("can://java/app/A.java/A/b(int)@formal_out", r.paramOut.get(0).getSrc());
        assertEquals("can://java/app/A.java/A/a(int)@3:16/actual_out", r.paramOut.get(0).getDst());
    }

    @Test
    void externalCalleeGetsNoVerticesAndNoEdges() {
        Map<String, JModule> modules = twoCallableModule();
        JCallable a = modules.get("A.java").getTypes().get("A").getCallables().get("a(int)");
        JBodyNode ext = new JBodyNode();
        ext.setKind("call");
        ext.setCallee("can://java/app/@external/java.lang.Math/max(int, int)");
        ext.getArgumentExpr().add("x");
        a.getBody().put("4:9", ext);

        SdgVertices.Result r = SdgVertices.apply(modules);
        assertTrue(a.getBody().keySet().stream().noneMatch(k -> k.startsWith("4:9/")),
                "external callee: no actual vertices");
        assertEquals(1, r.paramIn.size(), "only the in-project edge");
    }

    @Test
    void extraVarargsArgumentsAllMapToTheLastFormal() {
        // b(int y) has a single parameter; make the call site to it carry 3 arguments (as if the
        // call were being resolved to a varargs-shaped callee with only one declared formal).
        Map<String, JModule> modules = twoCallableModule();
        JCallable a = modules.get("A.java").getTypes().get("A").getCallables().get("a(int)");
        JBodyNode call = a.getBody().get("3:16");
        call.getArgumentExpr().add("2");
        call.getArgumentExpr().add("3");

        SdgVertices.Result r = SdgVertices.apply(modules);

        assertNotNull(a.getBody().get("3:16/actual_in:0"));
        assertNotNull(a.getBody().get("3:16/actual_in:1"));
        assertNotNull(a.getBody().get("3:16/actual_in:2"));
        assertEquals(3, r.paramIn.size());
        assertTrue(r.paramIn.stream().allMatch(e -> e.getDst().endsWith("@formal_in:0")),
                "every extra argument beyond the last formal collapses onto it");
    }

    @Test
    void deterministicAcrossRuns() {
        SdgVertices.Result r1 = SdgVertices.apply(twoCallableModule());
        SdgVertices.Result r2 = SdgVertices.apply(twoCallableModule());
        assertEquals(
                com.ibm.cldk.schema.V2Json.compact().toJson(r1.paramIn),
                com.ibm.cldk.schema.V2Json.compact().toJson(r2.paramIn));
    }
}
