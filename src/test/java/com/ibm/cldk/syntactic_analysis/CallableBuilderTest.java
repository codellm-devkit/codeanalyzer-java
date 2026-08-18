package com.ibm.cldk.syntactic_analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.ibm.cldk.schema.CanId;
import com.ibm.cldk.schema.JCallable;
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
        CompilationUnit cu = new JavaParser(
                        new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21))
                .parse(source)
                .getResult()
                .orElseThrow();
        CallableDeclaration<?> cd = cu.getType(0).findFirst(CallableDeclaration.class).orElseThrow();
        L1BuildContext ctx = new L1BuildContext(CanId.applicationId("myapp"), FILE_KEY, source);
        return new CallableBuilder(ctx).build(cd, TYPE_ID, classFieldNames);
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
        assertEquals("String", c.getParameters().get(0).getType());
        assertEquals("String", c.getReturnType());
        assertEquals(List.of("public"), c.getModifiers());
        assertEquals("Override", c.getDecorators().get(0).getName());
    }

    @Test
    void build_capturesErrorChannelFromThrows() {
        JCallable c = build("void read() throws IOException, RuntimeException {}");
        assertEquals(List.of("IOException", "RuntimeException"), c.getErrorChannel());
    }

    @Test
    void build_computesCyclomaticMetric() {
        JCallable c = build("void m(int x) { if (x > 0) { } }");
        assertEquals(2, c.getMetrics().getCyclomatic());
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
        assertEquals(List.of("count"), c.getRefs().getFields());
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
