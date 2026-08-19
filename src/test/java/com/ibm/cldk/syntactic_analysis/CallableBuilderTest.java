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
import com.ibm.cldk.schema.CanId;
import com.ibm.cldk.schema.JCallable;
import com.ibm.cldk.schema.JVariableDeclaration;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Tests the v2 {@link CallableBuilder} — signature, params, return/error channel, metrics, refs, body. */
class CallableBuilderTest {

    private static final String FILE_KEY = "src/main/java/com/example/Foo.java";
    private static final String TYPE_ID = "can://java/myapp/" + FILE_KEY + "/Foo";

    private static JCallable build(String memberSource, List<String> classFieldNames) {
        String source = "package com.example;\nimport java.io.IOException;\nimport java.util.*;\n"
                + "class Foo {\n  " + memberSource + "\n}\n";
        CompilationUnit cu = TestParsers.parseResolved(source);
        CallableDeclaration<?> cd = cu.getType(0).findFirst(CallableDeclaration.class).orElseThrow();
        L1BuildContext ctx = new L1BuildContext(CanId.applicationId("myapp"), FILE_KEY, source);
        return new CallableBuilder(ctx).build(cd, TYPE_ID, "com.example.Foo", classFieldNames);
    }

    private static JCallable build(String memberSource) {
        return build(memberSource, List.of());
    }

    @Test
    void build_methodKindSignatureIdAndSpan() {
        JCallable c = build("int add(int a, int b) { return a + b; }");
        assertEquals("method", c.getKind());
        assertEquals("add(int, int)", c.getSignature());
        assertEquals(TYPE_ID + "/add(int, int)", c.getId());
        assertNotNull(c.getSpan());
    }

    @Test
    void build_signatureUsesTypeErasure() {
        // The durable id's last segment: parameter types are RESOLVED and ERASED (type arguments
        // dropped), which is why a symbol solver is required — a syntactic signature would differ.
        JCallable c = build("void m(List<String> xs, String s) {}");
        assertEquals("m(java.util.List, java.lang.String)", c.getSignature());
        assertEquals(TYPE_ID + "/m(java.util.List, java.lang.String)", c.getId());
    }

    @Test
    void build_signatureFallsBackToAstWhenParameterTypeUnresolvable() {
        assertEquals("m(Mystery)", build("void m(Mystery x) {}").getSignature());
    }

    @Test
    void build_constructorKindHasNullReturnType() {
        JCallable c = build("Foo(int x) {}");
        assertEquals("constructor", c.getKind());
        assertNull(c.getReturnType());
        assertTrue(c.getId().startsWith(TYPE_ID + "/"));
    }

    @Test
    void build_capturesParametersReturnTypeModifiersAndDecorators() {
        JCallable c = build("@Override public String greet(String name) { return \"hi\"; }");
        assertEquals(List.of("name"), c.getParameters().stream()
                .map(p -> p.getName()).collect(Collectors.toList()));
        assertEquals("java.lang.String", c.getParameters().get(0).getType());
        assertEquals("java.lang.String", c.getReturnType());
        assertEquals(List.of("public"), c.getModifiers());
        assertEquals("Override", c.getDecorators().get(0).getName());
    }

    @Test
    void build_flagsEntrypointMethod() {
        assertTrue(build("@GetMapping(\"/x\") String get() { return \"\"; }").isEntrypoint(),
                "@GetMapping is a Spring entrypoint method");
        assertFalse(build("String plain() { return \"\"; }").isEntrypoint());
    }

    @Test
    void build_capturesLocalVariables() {
        JCallable c = build("void m() { int total = 0; String name; }");
        assertEquals(List.of("total", "name"),
                c.getLocalVariables().stream().map(JVariableDeclaration::getName).collect(Collectors.toList()));
        assertEquals("int", c.getLocalVariables().get(0).getType());
        assertEquals("0", c.getLocalVariables().get(0).getInitializer());
        assertNull(c.getLocalVariables().get(1).getInitializer(), "uninitialized -> absent");
        assertNotNull(c.getLocalVariables().get(0).getSpan());
    }

    @Test
    void build_localVariablesExcludeThoseInNestedLocalClasses() {
        JCallable c = build("void m() { int mine = 1; class Local { void inner() { int theirs = 2; } } }");
        assertEquals(List.of("mine"),
                c.getLocalVariables().stream().map(JVariableDeclaration::getName).collect(Collectors.toList()));
    }

    @Test
    void build_capturesJavadocComment() {
        JCallable c = build("/** Adds two numbers. */\n  int add(int a, int b) { return a + b; }");
        assertEquals(1, c.getComments().size());
        assertTrue(c.getComments().get(0).getContent().contains("Adds two numbers."));
        assertTrue(c.getComments().get(0).isJavadoc());
    }

    @Test
    void build_capturesDeclarationString() {
        // The signature-with-names text v1 exposed as `declaration` (useful verbatim in LLM prompts);
        // it is not recoverable from span.bytes, which covers the body too.
        JCallable c = build("public int add(int a, int b) { return a + b; }");
        assertEquals("public int add(int a, int b)", c.getDeclaration());
    }

    @Test
    void build_capturesCodeStartLineOfTheBody() {
        // "class Foo {" is line 4 of the wrapper, so the member starts on line 5.
        JCallable c = build("void m() {\n    x();\n  }");
        assertEquals(5, c.getCodeStartLine());
    }

    @Test
    void build_abstractMethodHasNoCodeStartLine() {
        assertEquals(-1, build("abstract void m();").getCodeStartLine());
    }

    @Test
    void build_capturesErrorChannelFromThrows() {
        JCallable c = build("void read() throws IOException, RuntimeException {}");
        assertEquals(List.of("java.io.IOException", "java.lang.RuntimeException"), c.getErrorChannel());
    }

    @Test
    void build_computesCyclomaticMetric() {
        JCallable c = build("void m(int x) { if (x > 0) { } }");
        assertEquals(2, c.getMetrics().getCyclomatic());
    }

    @Test
    void build_refsTypesIncludeCastsInstanceofAndCatchTypes() {
        // v1 only scanned variable declarators and object creations; a cast/instanceof/catch type is
        // just as much a referenced type.
        JCallable c = build("void m(Object o) { try { String s = (String) o; if (o instanceof Integer) {} }"
                + " catch (IllegalStateException e) {} }");
        assertTrue(c.getRefs().getTypes().contains("java.lang.String"));
        assertTrue(c.getRefs().getTypes().contains("java.lang.Integer"), "instanceof type");
        assertTrue(c.getRefs().getTypes().contains("java.lang.IllegalStateException"), "catch type");
    }

    @Test
    void build_capturesBodyCallNodes() {
        JCallable c = build("void m() { foo(); }");
        assertEquals(1, c.getBody().size());
        assertEquals("call", c.getBody().values().iterator().next().getKind());
    }

    @Test
    void build_capturesRefsTypesAndAccessedFields() {
        JCallable c = build("void m() { Helper h = new Helper(); this.count = h.value(); }", List.of("count"));
        assertTrue(c.getRefs().getTypes().contains("Helper"),
                "referenced types should include the syntactic type Helper");
        assertEquals(List.of("com.example.Foo.count"), c.getRefs().getFields());
    }

    @Test
    void build_capturesLocalClassUnderCallableTypesViaContainment() {
        JCallable c = build("void m() { class Local {} }");
        assertTrue(c.getTypes().containsKey("Local"));
        assertEquals(c.getId() + "/Local", c.getTypes().get("Local").getId());
    }

    @Test
    void build_abstractMethodHasEmptyBody() {
        JCallable c = build("abstract void m();");
        assertTrue(c.getBody().isEmpty());
    }
}
