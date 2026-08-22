package com.ibm.cldk.syntactic_analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.javaparser.ast.CompilationUnit;
import com.ibm.cldk.schema.CanId;
import com.ibm.cldk.schema.JCallEdge;
import com.ibm.cldk.schema.JModule;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Focused tests for {@link L2CallGraph}'s resolution order, built straight from source through the real
 * L1 builders. The end-to-end conformance gate lives in {@code L2CallGraphGateTest}; this class pins
 * the cases a single-class fixture cannot express — chiefly the anonymous-creation node-identity edge
 * (§4 case 1) and the fabricated-endpoint it must avoid.
 */
class L2CallGraphTest {

    private static final String APP = "app";
    private static final String FILE = "Foo.java";
    private static final String FOO = "can://java/app/Foo.java/Foo";

    private static Map<String, JModule> modulesFrom(String source) {
        CompilationUnit cu = TestParsers.parseResolved(source);
        L1BuildContext ctx = new L1BuildContext(CanId.applicationId(APP), FILE, source);
        Map<String, JModule> modules = new LinkedHashMap<>();
        modules.put(FILE, new ModuleBuilder(ctx).build(cu));
        return modules;
    }

    private static boolean hasEdge(L2CallGraph.Result r, String src, String dst) {
        return r.callGraph().stream().anyMatch(e -> src.equals(e.getSrc()) && dst.equals(e.getDst()));
    }

    @Test
    void anonymousCreationEdgeTargetsTheAnonymousConstructor() {
        // `new Runnable(){}` resolves its declaring type to java.lang.Runnable, but the edge must point
        // at the anonymous class's own generated constructor — the callable L1 actually emitted.
        Map<String, JModule> modules = modulesFrom(
                "package p;\nclass Foo {\n  void m() {\n"
                        + "    Runnable r = new Runnable() { public void run() {} };\n  }\n}\n");
        L2CallGraph.Result result = L2CallGraph.build(APP, modules);

        String m = FOO + "/m()";
        String anonConstructor = m + "/$anon$0/<init>()";
        assertTrue(hasEdge(result, m, anonConstructor),
                "expected an edge from m() to its anonymous class's own constructor " + anonConstructor);
    }

    @Test
    void anInterfaceAnonymousClassIsNotHomedAsAFabricatedExternalConstructor() {
        // The endpoint the design forbids: java.lang.Runnable has no constructor, so composing
        // Runnable.<init>() would manufacture a false external symbol a validator would accept.
        Map<String, JModule> modules = modulesFrom(
                "package p;\nclass Foo {\n  void m() {\n"
                        + "    Runnable r = new Runnable() { public void run() {} };\n  }\n}\n");
        L2CallGraph.Result result = L2CallGraph.build(APP, modules);

        assertTrue(result.externalSymbols().keySet().stream().noneMatch(k -> k.contains("java.lang.Runnable")),
                "an interface anonymous class must never be homed as an external Runnable.<init>()");
    }

    @Test
    void selfRecursionEdgeIsKept() {
        // v1 dropped self-edges via a !source.equals(target) guard; direct recursion is a real edge.
        Map<String, JModule> modules = modulesFrom(
                "package p;\nclass Foo {\n  void m(int n) {\n    m(n - 1);\n  }\n}\n");
        L2CallGraph.Result result = L2CallGraph.build(APP, modules);

        String m = FOO + "/m(int)";
        assertTrue(hasEdge(result, m, m), "a directly recursive call must produce a self-edge");
        JCallEdge selfEdge = result.callGraph().stream()
                .filter(e -> m.equals(e.getSrc()) && m.equals(e.getDst())).findFirst().orElseThrow();
        assertEquals(1, selfEdge.getWeight());
    }
}
