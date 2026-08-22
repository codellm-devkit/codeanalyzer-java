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
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
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
        assertEquals(Boolean.FALSE, node.getIsStaticCall(), "String.substring is an instance method");
        assertFalse(node.isConstructorCall());
    }

    @Test
    void build_flagsStaticCall() {
        JBodyNode node = build("package p;\nclass Foo {\n  void m() {\n    Math.max(1, 2);\n  }\n}\n").get("4:10");
        assertEquals(Boolean.TRUE, node.getIsStaticCall(), "Math.max is static");
        assertEquals("java.lang.Math", node.getReceiverType());
    }

    @Test
    void build_flagsConstructorCall() {
        JBodyNode node = build("package p;\nclass Foo {\n  void m() {\n    new String(\"x\");\n  }\n}\n").get("4:9");
        assertTrue(node.isConstructorCall());
        assertEquals("java.lang.String", node.getReceiverType(), "the instantiated type");
    }

    @Test
    void build_constructorCalleeSignatureMatchesTheDeclarationSideSignature() {
        // The callee signature must be joinable against the target callable's `signature`, which uses
        // `<init>` for constructors. A class-named signature would never match, so L2 would silently
        // drop every constructor edge.
        JBodyNode node = build("package p;\nclass Foo {\n  void m() {\n    new String(\"x\");\n  }\n}\n")
                .get("4:9");
        assertEquals("<init>(java.lang.String)", node.getCalleeSignature());
    }

    @Test
    void build_resolutionFailureForOneExpressionDoesNotPoisonAnother() {
        // Two receivers spelled `x` in different methods: one unresolvable, one not. Memoizing the
        // failure by expression text would wrongly blank the second.
        String source = "package p;\nclass Foo {\n"
                + "  void a(Mystery x) { x.f(); }\n"
                + "  void b(String x) { x.length(); }\n}\n";
        CompilationUnit cu = TestParsers.parseResolved(source);
        L1BuildContext ctx = new L1BuildContext(CanId.applicationId("myapp"), FILE_KEY, source);
        CallSiteBuilder builder = new CallSiteBuilder(ctx);
        MethodDeclaration a = cu.getType(0).getMethodsByName("a").get(0);
        MethodDeclaration b = cu.getType(0).getMethodsByName("b").get(0);

        builder.build(a.getBody().orElseThrow());   // fails to resolve `x`
        Map<String, JBodyNode> second = builder.build(b.getBody().orElseThrow());

        assertEquals("java.lang.String", second.values().iterator().next().getReceiverType(),
                "the resolvable `x` must still resolve after the unresolvable one");
    }

    @Test
    void build_skipsCallSitesWithoutASourceRange() {
        // Programmatically constructed nodes carry no range. They cannot be addressed by a line:col id,
        // and inventing one would both fabricate a location and collide with any other rangeless node.
        BlockStmt body = new BlockStmt();
        body.addStatement(new ExpressionStmt(new MethodCallExpr("foo")));
        body.addStatement(new ExpressionStmt(new MethodCallExpr("bar")));
        L1BuildContext ctx = new L1BuildContext(CanId.applicationId("myapp"), FILE_KEY, "class X {}\n");

        assertTrue(new CallSiteBuilder(ctx).build(body).isEmpty(),
                "rangeless call sites are skipped rather than silently overwriting each other");
    }

    @Test
    void build_unresolvableCallStillEmitsNodeWithoutResolvedFacts() {
        // Honest degradation: an unresolvable callee must not drop the call node or crash.
        JBodyNode node = build("package p;\nclass Foo {\n  void m() {\n    mystery(x);\n  }\n}\n").get("4:5");
        assertEquals("call", node.getKind());
        assertNull(node.getCalleeSignature());
        assertNull(node.getIsStaticCall(),
                "staticness is unknown for an unresolved callee — absent, not a false claim");
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

    @Test
    void build_capturesMethodNameReturnTypeAndAccessibility() {
        // The rich call-site facts v1 carried on CallSite; the SDK reconstructs `.call_sites` from these.
        String source = "package p;\nclass Foo {\n  void m() {\n    \"abc\".substring(1);\n  }\n}\n";
        JBodyNode node = build(source).get("4:11");
        assertNotNull(node, "expected the substring call site");
        assertEquals("substring", node.getMethodName());
        assertEquals("java.lang.String", node.getReturnType());
        assertEquals("public", node.getAccessibility());
    }

    @Test
    void build_reportsPackagePrivateAccessibilityDistinctlyFromUnknown() {
        // v1 collapsed both onto is_unspecified=true. Package-private is a resolved fact; an unresolved
        // callee has no accessibility fact at all (D12).
        // `m()` must come first — build() analyses the file's first callable.
        String pkgPrivate = "package p;\nclass Foo {\n  void m() {\n    hidden();\n  }\n"
                + "  void hidden() {}\n}\n";
        assertEquals("package_private", build(pkgPrivate).get("4:5").getAccessibility());

        String unresolved = "package p;\nclass Foo {\n  void m() {\n    mystery();\n  }\n}\n";
        assertNull(build(unresolved).get("4:5").getAccessibility(),
                "accessibility is unknown for an unresolved callee — absent, not 'unspecified'");
    }

    @Test
    void build_constructorCallCarriesInitNameAndInstantiatedType() {
        String source = "package p;\nclass Foo {\n  void m() {\n    new StringBuilder();\n  }\n}\n";
        JBodyNode node = build(source).get("4:9");
        assertEquals("<init>", node.getMethodName());
        assertEquals("java.lang.StringBuilder", node.getReturnType(),
                "a constructor call evaluates to the instantiated type");
        assertTrue(node.isConstructorCall());
    }

    @Test
    void build_returnTypeOfACastCallIsTheCastType() {
        // Mirrors v1: a cast is a stronger statement of the call's type than JavaParser's inference
        // through it, so the cast wins.
        String source = "package p;\nclass Foo {\n  void m() {\n    String s = (String) o();\n  }\n"
                + "  Object o() { return null; }\n}\n";
        assertEquals("java.lang.String", build(source).get("4:25").getReturnType());
    }

    @Test
    void build_capturesTheCommentOnTheCallsStatement() {
        String source = "package p;\nclass Foo {\n  void m() {\n"
                + "    // charge the customer\n    bill();\n  }\n}\n";
        JBodyNode node = build(source).get("5:5");
        assertNotNull(node.getComment(), "the statement's comment documents the call site");
        assertTrue(node.getComment().getContent().contains("charge the customer"));
    }

    @Test
    void build_explicitConstructorInvocationTakesItsOwnComment() {
        // this(...)/super(...) IS the statement, so its comment is on the site itself. Reading the
        // parent's would pick up the enclosing block's comment, which documents the block.
        String source = "package p;\nclass Foo {\n  Foo() {\n    // delegate to the full constructor\n"
                + "    this(1);\n  }\n}\n";
        JBodyNode node = build(source).get("5:5");
        assertNotNull(node.getComment());
        assertTrue(node.getComment().getContent().contains("delegate to the full constructor"));
    }

    @Test
    void build_recordsDeclaringTypeHintDistinctFromReceiverTypeOnInheritedCalls() {
        // getClass() is declared on java.lang.Object though the receiver is a String. `dst` needs the
        // DECLARING type, and this is exactly where it diverges from receiver_type (§Background) — so
        // the hint captures Object, not String.
        JBodyNode node = build("package p;\nclass Foo {\n  void m() {\n    \"abc\".getClass();\n  }\n}\n")
                .get("4:11");
        assertEquals("java.lang.String", node.getReceiverType());
        assertEquals("java.lang.Object", node.getDeclaringTypeHint());
    }

    @Test
    void build_declaringTypeHintUsesTheBinaryDollarNameForNestedTypes() {
        // Map.Entry is nested; its binary name is java.util.Map$Entry — the spelling WALA emits and the
        // L2 index keys on, so a dotted java.util.Map.Entry would never join.
        String source = "package p;\nimport java.util.Map;\n"
                + "class Foo {\n  void m(Map.Entry<String, String> e) {\n    e.getKey();\n  }\n}\n";
        JBodyNode node = build(source).values().stream()
                .filter(n -> "getKey".equals(n.getMethodName())).findFirst().orElseThrow();
        assertEquals("java.util.Map$Entry", node.getDeclaringTypeHint());
    }

    @Test
    void build_recordsDeclaringTypeHintForConstructorCalls() {
        JBodyNode node = build("package p;\nclass Foo {\n  void m() {\n    new StringBuilder();\n  }\n}\n")
                .get("4:9");
        assertEquals("java.lang.StringBuilder", node.getDeclaringTypeHint());
    }

    @Test
    void build_leavesDeclaringTypeHintAbsentForUnresolvedCalls() {
        JBodyNode node = build("package p;\nclass Foo {\n  void m() {\n    mystery(x);\n  }\n}\n").get("4:5");
        assertNull(node.getDeclaringTypeHint(), "no declaring type is known for an unresolved callee");
    }

    @Test
    void build_leavesDeclaringTypeHintAbsentForAnonymousClassCreations() {
        // new Runnable(){...} resolves its declaring type to java.lang.Runnable, but dst must be the
        // anonymous class's OWN constructor (§1). Composing java.lang.Runnable.<init>() here would
        // manufacture a false endpoint; L1 leaves the hint for L2 to fill by AST-node identity.
        String source = "package p;\nclass Foo {\n  void m() {\n"
                + "    Runnable r = new Runnable() { public void run() {} };\n  }\n}\n";
        JBodyNode node = build(source).values().stream()
                .filter(JBodyNode::isConstructorCall).findFirst().orElseThrow();
        assertNull(node.getDeclaringTypeHint(),
                "an anonymous creation's declaring-type hint is deferred to L2's node-identity match");
    }
}
