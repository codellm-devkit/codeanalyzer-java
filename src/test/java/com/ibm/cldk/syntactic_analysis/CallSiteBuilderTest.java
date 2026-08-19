package com.ibm.cldk.syntactic_analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.ibm.cldk.schema.CanId;
import com.ibm.cldk.schema.JBodyNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests the v2 {@link CallSiteBuilder} — L1 emits only {@code call} nodes, keyed by the node's
 * <em>local</em> id ({@code line:col}), covering method calls, constructor invocations, and explicit
 * {@code this(...)}/{@code super(...)} chaining.
 */
class CallSiteBuilderTest {

    private static final String FILE_KEY = "src/main/java/com/example/Foo.java";

    private static Map<String, JBodyNode> build(String source) {
        CompilationUnit cu = TestParsers.parseResolved(source);
        CallableDeclaration<?> cd = cu.getType(0).findFirst(CallableDeclaration.class).orElseThrow();
        BlockStmt body = (cd instanceof MethodDeclaration)
                ? ((MethodDeclaration) cd).getBody().orElseThrow()
                : ((ConstructorDeclaration) cd).getBody();
        L1BuildContext ctx = new L1BuildContext(CanId.applicationId("myapp"), FILE_KEY, source);
        return new CallSiteBuilder(ctx).build(body);
    }

    @Test
    void build_keysAreBareLocalIdsNotFullIds() {
        // Keystone: `body` is keyed by the node's LOCAL id (`line:col` / `@tag`); the full
        // `<callable-id>@<local-id>` form is only used at application scope (L4 param_in/param_out).
        String source = "package p;\nclass Foo {\n  void m() {\n    bar(x);\n    baz();\n  }\n}\n";
        Map<String, JBodyNode> body = build(source);
        assertEquals(List.of("4:5", "5:5"), new ArrayList<>(body.keySet()));
        assertEquals("call", body.get("4:5").getKind());
    }

    @Test
    void build_callNodeCarriesNoCalleeAtL1() {
        JBodyNode bar = build("package p;\nclass Foo {\n  void m() {\n    bar(x);\n  }\n}\n").get("4:5");
        assertNull(bar.getCallee(), "callee is absent at L1 and set when L2 resolves the site");
    }

    @Test
    void build_callNodeArgumentsAreLocalIdsOfArgumentExpressions() {
        JBodyNode bar = build("package p;\nclass Foo {\n  void m() {\n    bar(x, y);\n  }\n}\n").get("4:5");
        // "    bar(x, y);" -> x at col 9, y at col 12
        assertEquals(List.of("4:9", "4:12"), bar.getArguments());
    }

    @Test
    void build_callNodeSpanSlicesToTheCallText() {
        String source = "package p;\nclass Foo {\n  void m() {\n    bar(x);\n  }\n}\n";
        JBodyNode bar = build(source).get("4:5");
        assertNotNull(bar.getSpan());
        int[] bytes = bar.getSpan().getBytes();
        assertEquals("bar(x)", source.substring(bytes[0], bytes[1]));
    }

    @Test
    void build_chainedCallsGetDistinctIdsFromTheInvokedNameAnchor() {
        // a.b().c(): anchoring on the invoked name (not the expression start) keeps the two calls apart.
        String source = "package p;\nclass Foo {\n  void m() {\n    a.b().c();\n  }\n}\n";
        Map<String, JBodyNode> body = build(source);
        assertEquals(2, body.size());
        assertEquals(List.of("4:7", "4:11"), new ArrayList<>(body.keySet()));
    }

    @Test
    void build_emitsCallNodeForConstructorInvocation() {
        // `new Helper()` is a call site too — L2 resolves it to the constructor callable, so without
        // it the v2 call graph would systematically miss constructor edges.
        String source = "package p;\nclass Foo {\n  void m() {\n    Helper h = new Helper();\n  }\n}\n";
        Map<String, JBodyNode> body = build(source);
        // anchored at the instantiated type name, mirroring the invoked-name anchor for method calls
        assertEquals(List.of("4:20"), new ArrayList<>(body.keySet()));
        assertEquals("call", body.get("4:20").getKind());
    }

    @Test
    void build_constructorCallCarriesArgumentLocalIds() {
        String source = "package p;\nclass Foo {\n  void m() {\n    new Helper(a);\n  }\n}\n";
        JBodyNode node = build(source).get("4:9");
        assertEquals(List.of("4:16"), node.getArguments());
    }

    @Test
    void build_emitsCallNodeForExplicitConstructorChaining() {
        // this(...) / super(...) are constructor calls that matter for call-graph completeness.
        String source = "package p;\nclass Foo {\n  Foo() {\n    this(1);\n  }\n}\n";
        Map<String, JBodyNode> body = build(source);
        assertEquals(List.of("4:5"), new ArrayList<>(body.keySet()));
        assertEquals("call", body.get("4:5").getKind());
    }

    @Test
    void build_ordersMethodAndConstructorCallsBySourcePosition() {
        String source = "package p;\nclass Foo {\n  void m() {\n    a();\n    new B();\n    c();\n  }\n}\n";
        Map<String, JBodyNode> body = build(source);
        assertEquals(List.of("4:5", "5:9", "6:5"), new ArrayList<>(body.keySet()));
    }

    @Test
    void build_callNodeCapturesReceiverAndResolvedTypes() {
        // The rich call-site facts v1 exposed on CallSite: framework/CRUD finders key on receiver_type,
        // and LLM consumers want the receiver/argument expressions verbatim.
        JBodyNode node = build("package p;\nclass Foo {\n  void m() {\n    \"abc\".substring(1);\n  }\n}\n")
                .get("4:11");
        assertEquals("\"abc\"", node.getReceiverExpr());
        assertEquals("java.lang.String", node.getReceiverType());
        assertEquals(List.of("int"), node.getArgumentTypes());
        assertEquals(List.of("1"), node.getArgumentExpr());
        assertEquals("substring(int)", node.getCalleeSignature());
        assertFalse(node.isStaticCall());
        assertFalse(node.isConstructorCall());
    }

    @Test
    void build_flagsStaticCall() {
        JBodyNode node = build("package p;\nclass Foo {\n  void m() {\n    Math.max(1, 2);\n  }\n}\n").get("4:10");
        assertTrue(node.isStaticCall(), "Math.max is static");
        assertEquals("java.lang.Math", node.getReceiverType());
    }

    @Test
    void build_flagsConstructorCall() {
        JBodyNode node = build("package p;\nclass Foo {\n  void m() {\n    new String(\"x\");\n  }\n}\n").get("4:9");
        assertTrue(node.isConstructorCall());
        assertEquals("java.lang.String", node.getReceiverType(), "the instantiated type");
    }

    @Test
    void build_unresolvableCallStillEmitsNodeWithoutResolvedFacts() {
        // Honest degradation: an unresolvable callee must not drop the call node or crash.
        JBodyNode node = build("package p;\nclass Foo {\n  void m() {\n    mystery(x);\n  }\n}\n").get("4:5");
        assertEquals("call", node.getKind());
        assertNull(node.getCalleeSignature());
    }

    @Test
    void build_excludesCallsInsideNestedLocalClasses() {
        // hidden() belongs to Local.inner()'s own body (its own callable), not to m().
        String source = "package p;\nclass Foo {\n  void m() {\n    outer();\n    class Local {\n"
                + "      void inner() { hidden(); }\n    }\n  }\n}\n";
        Map<String, JBodyNode> body = build(source);
        assertEquals(List.of("4:5"), new ArrayList<>(body.keySet()));
    }

    @Test
    void build_includesCallsInsideLambdas() {
        // A lambda has no separate callable; its calls are part of the enclosing method's body.
        String source = "package p;\nclass Foo {\n  void m() {\n    run(() -> log());\n  }\n}\n";
        Map<String, JBodyNode> body = build(source);
        assertTrue(body.keySet().stream().anyMatch(k -> k.startsWith("4:")));
        assertEquals(2, body.size(), "both run(...) and log() are calls in m()'s body");
    }
}
