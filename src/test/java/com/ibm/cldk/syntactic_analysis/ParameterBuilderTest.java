package com.ibm.cldk.syntactic_analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.ibm.cldk.schema.CanId;
import com.ibm.cldk.schema.JParameter;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests the v2 {@link ParameterBuilder} — name, declared type, byte-offset span, decorators. */
class ParameterBuilderTest {

    private static final String FILE_KEY = "src/main/java/com/example/Foo.java";

    private static Parameter firstParam(String source) {
        CompilationUnit cu = TestParsers.parseResolved(source);
        return cu.getType(0).findFirst(MethodDeclaration.class).orElseThrow().getParameter(0);
    }

    private static JParameter build(String source) {
        L1BuildContext ctx = new L1BuildContext(CanId.applicationId("myapp"), FILE_KEY, source);
        return new ParameterBuilder(ctx).build(firstParam(source));
    }

    @Test
    void build_capturesNameAndDeclaredType() {
        JParameter p = build("package p;\nclass Foo {\n  void m(final String name) {}\n}\n");
        assertEquals("name", p.getName());
        assertEquals("java.lang.String", p.getType(), "types are resolved to qualified names via the symbol solver");
    }

    @Test
    void build_capturesParameterModifiers() {
        JParameter p = build("package p;\nclass Foo {\n  void m(final String name) {}\n}\n");
        assertEquals(List.of("final"), p.getModifiers());
    }

    @Test
    void build_resolvesGenericTypeArguments() {
        assertEquals("java.util.List<java.lang.String>",
                build("package p;\nimport java.util.List;\nclass Foo {\n  void m(List<String> xs) {}\n}\n").getType());
        assertEquals("int[]", build("package p;\nclass Foo {\n  void m(int[] xs) {}\n}\n").getType());
    }

    @Test
    void build_fallsBackToAstSpellingWhenTypeCannotBeResolved() {
        // A missing dependency must degrade to the source spelling, never crash the build.
        assertEquals("Mystery", build("package p;\nclass Foo {\n  void m(Mystery x) {}\n}\n").getType());
    }

    @Test
    void build_spanBytesSliceToTheParameterText() {
        String source = "package p;\nclass Foo {\n  void m(String name) {}\n}\n";
        JParameter p = build(source);
        assertNotNull(p.getSpan());
        int[] bytes = p.getSpan().getBytes();
        assertEquals("String name", source.substring(bytes[0], bytes[1]));
    }

    @Test
    void build_marksVariadicParameterAndKeepsElementType() {
        JParameter p = build("package p;\nclass Foo {\n  void m(String... names) {}\n}\n");
        assertTrue(p.isVariadic(), "String... must set is_variadic");
        assertEquals("java.lang.String", p.getType(), "type stays the element type; the flag carries the ...");
    }

    @Test
    void build_plainArrayParameterIsNotVariadic() {
        JParameter p = build("package p;\nclass Foo {\n  void m(String[] names) {}\n}\n");
        assertFalse(p.isVariadic(), "String[] is an array, not varargs");
        assertEquals("java.lang.String[]", p.getType());
    }

    @Test
    void build_capturesStructuredParameterDecorators() {
        JParameter p = build("package p;\nclass Foo {\n  void m(@RequestParam(\"q\") String query) {}\n}\n");
        assertEquals(1, p.getDecorators().size());
        assertEquals("RequestParam", p.getDecorators().get(0).getName());
        assertEquals(List.of("\"q\""), p.getDecorators().get(0).getArgs());
    }

    @Test
    void build_hasNoDecoratorsForPlainParameter() {
        JParameter p = build("package p;\nclass Foo {\n  void m(String name) {}\n}\n");
        assertTrue(p.getDecorators().isEmpty());
    }
}
