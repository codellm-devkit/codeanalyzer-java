package com.ibm.cldk.syntactic_analysis.dataflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.cldk.schema.JBodyNode;
import com.ibm.cldk.schema.JCallEdge;
import com.ibm.cldk.schema.JCallable;
import com.ibm.cldk.schema.JDdgEdge;
import com.ibm.cldk.schema.JModule;
import com.ibm.cldk.schema.JParameter;
import com.ibm.cldk.schema.JType;
import com.ibm.cldk.schema.Span;
import com.ibm.cldk.schema.V2Json;
import com.ibm.cldk.syntactic_analysis.L1Extractor;
import com.ibm.cldk.syntactic_analysis.L2CallGraph;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SummaryPassTest {

    private static final String FIXTURE = "src/test/resources/test-applications/l4-sdg-test";

    private static Map<String, JModule> analyzed() throws Exception {
        Map<String, JModule> modules = L1Extractor.extractAll(
                Paths.get(FIXTURE), "l4-sdg-test", null, new LinkedHashMap<>(), 3, 3, "ast");
        L2CallGraph.Result l2 = L2CallGraph.build("l4-sdg-test", modules, null, true);
        SdgVertices.apply(modules);
        SummaryPass.apply(modules, l2.callGraph(), 3);
        return modules;
    }

    private static JCallable callable(Map<String, JModule> modules, String idSuffix) {
        return modules.values().stream()
                .flatMap(m -> m.getTypes().values().stream())
                .flatMap(t -> t.getCallables().values().stream())
                .filter(c -> c.getId().endsWith(idSuffix))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void transitiveFlowYieldsSummaryEdgeInTheCaller() throws Exception {
        Map<String, JModule> modules = analyzed();
        // Chain.a calls b; b's param flows to its return via c — so a's call site gets a summary edge.
        JCallable a = callable(modules, "/Chain/a(int)");
        assertNotNull(a.getSummary(), "a carries a summary edge for the a→b→c flow");
        assertEquals(1, a.getSummary().size());
        String src = a.getSummary().get(0).getSrc();
        String dst = a.getSummary().get(0).getDst();
        assertTrue(src.endsWith("/actual_in:0") && dst.endsWith("/actual_out"), src + " → " + dst);
        assertEquals(
                src.substring(0, src.indexOf("/actual_in")),
                dst.substring(0, dst.indexOf("/actual_out")),
                "same call site");
    }

    @Test
    void mutualRecursionReachesFixpointAndIsDeterministic() throws Exception {
        Map<String, JModule> m1 = analyzed(); // terminating at all is half the gate
        Map<String, JModule> m2 = analyzed();
        assertEquals(
                V2Json.compact().toJson(m1),
                V2Json.compact().toJson(m2),
                "two runs byte-identical (fixpoint order must not leak)");
    }

    @Test
    void mutualRecursionSummarisesBothDirections() throws Exception {
        Map<String, JModule> modules = analyzed();
        // even → odd → even is one SCC, so both are solved by the same fixpoint loop: reaching this
        // assertion at all means it terminated, and each member ends up shortcutting its own site.
        for (String id : new String[] {"/Mutual/even(int)", "/Mutual/odd(int)"}) {
            JCallable c = callable(modules, id);
            assertNotNull(c.getSummary(), id + " carries its call site's summary edge");
            assertEquals(1, c.getSummary().size(), id);
            assertTrue(c.getSummary().get(0).getSrc().endsWith("/actual_in:0"), id);
            assertTrue(c.getSummary().get(0).getDst().endsWith("/actual_out"), id);
        }
    }

    @Test
    void aCallableWithNoPassThroughCallSiteKeepsSummaryAbsent() throws Exception {
        Map<String, JModule> modules = analyzed();
        // Chain.c calls nothing, so it has no call site to shortcut...
        assertNull(callable(modules, "/Chain/c(int)").getSummary(), "no call sites, no summary");
        // ...and Heap.roundTrip's two sites are a void call (no actual_out) and a no-arg call (no
        // actual_in), so neither can carry a shortcut. Absent, not an empty list.
        assertNull(callable(modules, "/Heap/roundTrip(int)").getSummary(), "no viable site, no summary");
    }

    /**
     * A call whose value passes through a local before being returned — {@code int t = f(x); return
     * t;} — which is where summary <em>composition</em> has to work: nothing in the return statement
     * names the parameter, so the syntactic return rule cannot rescue it and the only route is the
     * callee's own summary. The tree is built by hand, node for node, from the shape the AST engine
     * really emits for this source (verified by running {@code -a 4} over it): the call sits at its
     * own body node, the def-use edge hangs off the enclosing <em>statement</em>, and the call node
     * itself carries no ddg edge at all.
     *
     * <p>The assertion is on {@code top}, not {@code mid}: {@code mid}'s own summary edge follows
     * from {@code flows(callee)} alone and holds either way, so only the grandparent's edge actually
     * depends on {@code flows(mid)} having composed through the call.
     */
    @Test
    void aValuePassingThroughALocalStillComposes() {
        Map<String, JModule> modules = passThroughChain();
        SdgVertices.apply(modules);
        SummaryPass.apply(modules, passThroughCallGraph(), 3);

        JCallable top = callable(modules, "/Pass/top(int)");
        assertNotNull(top.getSummary(), "top composes through mid, which composes through callee");
        assertEquals(1, top.getSummary().size());
        assertEquals("topCall/actual_in:0", top.getSummary().get(0).getSrc());
        assertEquals("topCall/actual_out", top.getSummary().get(0).getDst());

        JCallable mid = callable(modules, "/Pass/mid(int)");
        assertNotNull(mid.getSummary());
        assertEquals(1, mid.getSummary().size());
    }

    /**
     * The same composition, but with the in-project call wrapped in an unresolved one —
     * {@code int t = Math.abs(id(a)); return t;}. The wrapper is a body node too, and its span is
     * narrower than the statement's, so it is what encloses the inner call; it is external, has no
     * summary, and is an orphan carrying no ddg edge. If the route out of a call node were built only
     * for resolved in-project sites, the chain would stop dead on the wrapper and {@code top} would
     * lose a summary edge it should have — an under-approximation. Node ids, spans and the
     * {@code callee}-less wrapper mirror what {@code -a 4} really emits for this source.
     */
    @Test
    void anUnresolvedWrapperDoesNotBreakComposition() {
        Map<String, JModule> modules = wrappedChain();
        SdgVertices.apply(modules);
        SummaryPass.apply(modules, List.of(callEdge("top(int)", "caller(int)"), callEdge("caller(int)", "id(int)")), 3);

        JCallable top = callable(modules, "/Pass/top(int)");
        assertNotNull(top.getSummary(), "top still composes through caller, wrapper notwithstanding");
        assertEquals(1, top.getSummary().size());
        assertEquals("topCall/actual_in:0", top.getSummary().get(0).getSrc());
        assertEquals("topCall/actual_out", top.getSummary().get(0).getDst());
    }

    private static final String WRAP_SOURCE = String.join(
            "\n",
            "class Pass {",
            "    int id(int p) { return p; }",
            "    int caller(int a) { int t = Math.abs(id(a)); return t; }",
            "    int top(int b) { int u = caller(b); return u; }",
            "}");

    private static Map<String, JModule> wrappedChain() {
        JType type = new JType();
        type.setId("can://java/pass/Pass.java/Pass");

        JCallable id = method("id(int)", "p");
        id.getBody().put("@entry", node("entry", null, WRAP_SOURCE));
        id.getBody().put("idRet", node("return", "return p;", WRAP_SOURCE));
        id.setDdg(new ArrayList<>());
        type.getCallables().put("id(int)", id);

        JCallable caller = method("caller(int)", "a");
        caller.getBody().put("@entry", node("entry", null, WRAP_SOURCE));
        caller.getBody().put("wrapCall", node("call", "Math.abs(id(a))", WRAP_SOURCE)); // no callee: unresolved
        caller.getBody().put("idCall", callNode("id(a)", "id(int)", WRAP_SOURCE));
        caller.getBody().put("callerDecl", node("statement", "int t = Math.abs(id(a));", WRAP_SOURCE));
        caller.getBody().put("callerRet", node("return", "return t;", WRAP_SOURCE));
        caller.setDdg(new ArrayList<>(List.of(ddg("callerDecl", "callerRet", "t"))));
        type.getCallables().put("caller(int)", caller);

        JCallable top = method("top(int)", "b");
        top.getBody().put("@entry", node("entry", null, WRAP_SOURCE));
        top.getBody().put("topCall", callNode("caller(b)", "caller(int)", WRAP_SOURCE));
        top.getBody().put("topDecl", node("statement", "int u = caller(b);", WRAP_SOURCE));
        top.getBody().put("topRet", node("return", "return u;", WRAP_SOURCE));
        top.setDdg(new ArrayList<>(List.of(ddg("topDecl", "topRet", "u"))));
        type.getCallables().put("top(int)", top);

        JModule module = new JModule();
        module.setId("can://java/pass/Pass.java");
        module.setSource(WRAP_SOURCE);
        module.getTypes().put("Pass", type);
        return new LinkedHashMap<>(Map.of("Pass.java", module));
    }

    // Line 2 holds callee, 3 mid, 4 top; every snippet below is unique, and the text is ASCII so a
    // char offset is a byte offset.
    private static final String PASS_SOURCE = String.join(
            "\n",
            "class Pass {",
            "    int callee(int q) { return q; }",
            "    int mid(int x) { int t = callee(x); return t; }",
            "    int top(int w) { int s = mid(w); return s; }",
            "}");

    /** {@code callee → mid → top}, each level passing its argument out through the return. */
    private static Map<String, JModule> passThroughChain() {
        JType type = new JType();
        type.setId("can://java/pass/Pass.java/Pass");

        JCallable callee = method("callee(int)", "q");
        callee.getBody().put("@entry", node("entry", null, PASS_SOURCE));
        callee.getBody().put("calleeRet", node("return", "return q;", PASS_SOURCE));
        callee.setDdg(new ArrayList<>());
        type.getCallables().put("callee(int)", callee);

        JCallable mid = method("mid(int)", "x");
        mid.getBody().put("@entry", node("entry", null, PASS_SOURCE));
        mid.getBody().put("midCall", callNode("callee(x)", "callee(int)", PASS_SOURCE));
        mid.getBody().put("midDecl", node("statement", "int t = callee(x);", PASS_SOURCE));
        mid.getBody().put("midRet", node("return", "return t;", PASS_SOURCE));
        // The def-use edge leaves the *statement*, never the nested call node — that asymmetry is
        // the whole point of the case.
        mid.setDdg(new ArrayList<>(List.of(ddg("midDecl", "midRet", "t"))));
        type.getCallables().put("mid(int)", mid);

        JCallable top = method("top(int)", "w");
        top.getBody().put("@entry", node("entry", null, PASS_SOURCE));
        top.getBody().put("topCall", callNode("mid(w)", "mid(int)", PASS_SOURCE));
        top.getBody().put("topDecl", node("statement", "int s = mid(w);", PASS_SOURCE));
        top.getBody().put("topRet", node("return", "return s;", PASS_SOURCE));
        top.setDdg(new ArrayList<>(List.of(ddg("topDecl", "topRet", "s"))));
        type.getCallables().put("top(int)", top);

        JModule module = new JModule();
        module.setId("can://java/pass/Pass.java");
        module.setSource(PASS_SOURCE);
        module.getTypes().put("Pass", type);
        return new LinkedHashMap<>(Map.of("Pass.java", module));
    }

    private static JDdgEdge ddg(String src, String dst, String var) {
        JDdgEdge e = new JDdgEdge();
        e.setSrc(src);
        e.setDst(dst);
        e.setVar(var);
        e.setProv(List.of("ssa"));
        return e;
    }

    private static JCallable method(String signature, String param) {
        JCallable c = new JCallable();
        c.setId("can://java/pass/Pass.java/Pass/" + signature);
        c.setKind("method");
        c.setSignature(signature);
        c.setReturnType("int");
        JParameter p = new JParameter();
        p.setName(param);
        p.setType("int");
        c.setParameters(new ArrayList<>(List.of(p)));
        return c;
    }

    private static JBodyNode node(String kind, String snippet, String source) {
        JBodyNode n = new JBodyNode();
        n.setKind(kind);
        if (snippet != null) {
            Span span = new Span();
            int at = source.indexOf(snippet);
            span.setBytes(new int[] {at, at + snippet.length()});
            n.setSpan(span);
        }
        return n;
    }

    private static JBodyNode callNode(String snippet, String calleeSignature, String source) {
        JBodyNode n = node("call", snippet, source);
        n.setCallee("can://java/pass/Pass.java/Pass/" + calleeSignature);
        n.setReturnType("int");
        n.setArgumentExpr(new ArrayList<>(List.of(snippet.substring(snippet.indexOf('(') + 1, snippet.length() - 1))));
        return n;
    }

    private static List<JCallEdge> passThroughCallGraph() {
        return List.of(
                callEdge("top(int)", "mid(int)"),
                callEdge("mid(int)", "callee(int)"));
    }

    private static JCallEdge callEdge(String from, String to) {
        JCallEdge e = new JCallEdge();
        e.setSrc("can://java/pass/Pass.java/Pass/" + from);
        e.setDst("can://java/pass/Pass.java/Pass/" + to);
        return e;
    }

    @Test
    void summaryEndpointsAreExistingLocalBodyNodes() throws Exception {
        Map<String, JModule> modules = analyzed();
        modules.values().stream()
                .flatMap(m -> m.getTypes().values().stream())
                .flatMap(t -> t.getCallables().values().stream())
                .filter(c -> c.getSummary() != null)
                .forEach(c -> c.getSummary().forEach(e -> {
                    assertTrue(c.getBody().containsKey(e.getSrc()), c.getId() + " src " + e.getSrc());
                    assertTrue(c.getBody().containsKey(e.getDst()), c.getId() + " dst " + e.getDst());
                }));
    }
}
