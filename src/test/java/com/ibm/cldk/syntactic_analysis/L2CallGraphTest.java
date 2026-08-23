package com.ibm.cldk.syntactic_analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.javaparser.ast.CompilationUnit;
import com.ibm.cldk.schema.CanId;
import com.ibm.cldk.schema.JBodyNode;
import com.ibm.cldk.schema.JCallEdge;
import com.ibm.cldk.schema.JModule;
import com.ibm.cldk.syntactic_analysis.L2CallGraph.RtaEndpoint;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    private static Optional<JCallEdge> edge(L2CallGraph.Result r, String src, String dst) {
        return r.callGraph().stream().filter(e -> src.equals(e.getSrc()) && dst.equals(e.getDst())).findFirst();
    }

    /** `class Foo { void a() { b(); } void b() {} }` — one declared edge, a() -> b(). */
    private static Map<String, JModule> twoMethodModule() {
        return modulesFrom("package p;\nclass Foo {\n  void a() {\n    b();\n  }\n  void b() {}\n}\n");
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
    void rtaAttestationOfADeclaredEdgeUnionsProvenanceAndKeepsTheDeclaredWeight() {
        Map<String, JModule> modules = twoMethodModule();
        String a = FOO + "/a()";
        String b = FOO + "/b()";
        // RTA attests the same a() -> b() call the declared analysis already found (with a higher count).
        List<RtaEndpoint> rta = List.of(
                new RtaEndpoint(true, "p.Foo", "a()", true, "p.Foo", "b()"),
                new RtaEndpoint(true, "p.Foo", "a()", true, "p.Foo", "b()"));
        JCallEdge merged = edge(L2CallGraph.build(APP, modules, rta), a, b).orElseThrow();
        assertEquals(List.of("declared", "rta"), merged.getProv(), "both analyses attest, sorted");
        assertEquals(1, merged.getWeight(), "declared weight wins — those are the navigable call sites");
    }

    @Test
    void rtaOnlyEdgeIsKeptWithRtaProvenanceAndRtaWeight() {
        Map<String, JModule> modules = twoMethodModule();
        String a = FOO + "/a()";
        String b = FOO + "/b()";
        // Dispatch fan-out the declared analysis did not see: b() -> a(), attested only by RTA.
        List<RtaEndpoint> rta = List.of(new RtaEndpoint(true, "p.Foo", "b()", true, "p.Foo", "a()"));
        JCallEdge rtaOnly = edge(L2CallGraph.build(APP, modules, rta), b, a).orElseThrow();
        assertEquals(List.of("rta"), rtaOnly.getProv());
        assertEquals(1, rtaOnly.getWeight());
    }

    @Test
    void rtaEdgeToALibraryTargetIsHomedAsAnExternalSymbolAndTheEdgeKept() {
        // RTA resolving a dispatch to a library type (List.add): the external target is homed so the
        // edge does not dangle, and the dispatch-precision win survives.
        Map<String, JModule> modules = twoMethodModule();
        String a = FOO + "/a()";
        String listAdd = "can://java/app/@external/java.util.List/add(java.lang.Object)";
        List<RtaEndpoint> rta = List.of(
                new RtaEndpoint(true, "p.Foo", "a()", false, "java.util.List", "add(java.lang.Object)"));
        L2CallGraph.Result result = L2CallGraph.build(APP, modules, rta);

        assertTrue(hasEdge(result, a, listAdd), "the edge to the homed external target is kept");
        assertTrue(result.externalSymbols().containsKey(listAdd));
        assertEquals("java.util.List", result.externalSymbols().get(listAdd).getDeclaringType());
    }

    @Test
    void rtaEdgeTouchingAnInProjectSyntheticIsDroppedNotFabricated() {
        // A bridge/access$/lambda$ endpoint on an application class has no source declaration, so its
        // composed id is absent from the tree. The overlay drops it rather than inventing a node.
        Map<String, JModule> modules = twoMethodModule();
        String a = FOO + "/a()";
        List<RtaEndpoint> rta = List.of(
                new RtaEndpoint(true, "p.Foo", "a()", true, "p.Foo", "access$000()"),
                new RtaEndpoint(true, "p.Foo", "lambda$a$0()", true, "p.Foo", "b()"));
        L2CallGraph.Result result = L2CallGraph.build(APP, modules, rta);

        assertFalse(hasEdge(result, a, FOO + "/access$000()"), "an in-project synthetic dst must not be fabricated");
        assertFalse(hasEdge(result, FOO + "/lambda$a$0()", FOO + "/b()"), "a synthetic src must not be fabricated");
        assertTrue(result.externalSymbols().isEmpty(), "an in-project synthetic is never homed as external");
    }

    @Test
    void rtaEdgeFromALibraryCallerIsDropped() {
        // A library -> application edge has no in-project node to attribute the source to.
        Map<String, JModule> modules = twoMethodModule();
        List<RtaEndpoint> rta = List.of(
                new RtaEndpoint(false, "java.lang.Thread", "run()", true, "p.Foo", "a()"));
        assertTrue(L2CallGraph.build(APP, modules, rta).callGraph().stream()
                .noneMatch(e -> e.getDst().equals(FOO + "/a()") && e.getProv().contains("rta")),
                "an unattributable library-sourced RTA edge is dropped");
    }

    @Test
    void externalCallsOffDropsOutOfProjectTargetsButKeepsInProjectEdges() {
        // --external-calls off (v1 parity): a call resolving outside the project is dropped entirely —
        // no edge, no external symbol — while in-project edges are untouched.
        String src = "package p;\nclass Foo {\n  void a() {\n    b();\n    \"x\".trim();\n  }\n  void b() {}\n}\n";
        String a = FOO + "/a()";
        String b = FOO + "/b()";
        String trim = "can://java/app/@external/java.lang.String/trim()";

        L2CallGraph.Result off = L2CallGraph.build(APP, modulesFrom(src), null, false);
        assertTrue(hasEdge(off, a, b), "in-project edges are unaffected by --external-calls");
        assertFalse(hasEdge(off, a, trim), "the external target is dropped when external calls are off");
        assertTrue(off.externalSymbols().isEmpty(), "no external symbols emitted when off (v1 parity)");

        L2CallGraph.Result on = L2CallGraph.build(APP, modulesFrom(src), null, true);
        assertTrue(hasEdge(on, a, trim), "the external target is homed when external calls are on");
        assertTrue(on.externalSymbols().containsKey(trim));
    }

    @Test
    void externalCallsOffDropsRtaLibraryTargets() {
        List<RtaEndpoint> rta = List.of(
                new RtaEndpoint(true, "p.Foo", "a()", false, "java.util.List", "add(java.lang.Object)"));
        L2CallGraph.Result off = L2CallGraph.build(APP, twoMethodModule(), rta, false);
        assertTrue(off.externalSymbols().isEmpty(), "rta library targets are dropped when external calls are off");
        assertFalse(off.callGraph().stream().anyMatch(e -> e.getDst().contains("/@external/")),
                "no external edges when off");
    }

    @Test
    void localClassCallIsUnresolvedAndNeverHomedExternal() {
        // JavaParser does not resolve a local class's instantiation/calls, so no declaring-type hint is
        // produced: the site is dropped (no callee, no edge) rather than mis-homed as external — even
        // with external homing on. This pins that safety property.
        Map<String, JModule> modules = modulesFrom(
                "package p;\nclass Foo {\n  void m() {\n    class Local { void run() {} }\n"
                        + "    new Local().run();\n  }\n}\n");
        L2CallGraph.Result r = L2CallGraph.build(APP, modules, null, true); // external ON: the risky mode
        assertTrue(r.externalSymbols().keySet().stream().noneMatch(k -> k.contains("Foo")),
                "a local class must never be homed as an external symbol");
        JBodyNode run = modules.get(FILE).getTypes().get("Foo").getCallables().get("m()").getBody().values()
                .stream().filter(n -> "run".equals(n.getMethodName())).findFirst().orElseThrow();
        assertNull(run.getCallee(), "an unresolved local-class call carries no callee");
    }

    @Test
    void buildClearsAStaleExternalCalleeOnReuse() {
        // build() mutates callee; re-running with external off on the same tree must clear an @external
        // callee the external-on run set, or the payload carries a callee with no edge/external symbol.
        Map<String, JModule> modules = modulesFrom(
                "package p;\nclass Foo {\n  void m() {\n    \"x\".trim();\n  }\n}\n");
        L2CallGraph.build(APP, modules, null, true);   // sets callee = @external String.trim()
        L2CallGraph.build(APP, modules, null, false);  // external off: must clear it
        JBodyNode node = modules.get(FILE).getTypes().get("Foo").getCallables().get("m()")
                .getBody().values().iterator().next();
        assertNull(node.getCallee(), "the stale @external callee must be cleared on the re-run");
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
