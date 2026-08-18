package com.ibm.cldk.syntactic_analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.ibm.cldk.schema.CanId;
import com.ibm.cldk.schema.JCallable;
import com.ibm.cldk.schema.JDecorator;
import com.ibm.cldk.schema.JType;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Tests the v2 {@link TypeBuilder} — kind, byte-offset span, structured decorators, inheritance. */
class TypeBuilderTest {

    private static final String FILE_KEY = "src/main/java/com/example/Foo.java";

    private static CompilationUnit parse(String source) {
        return new JavaParser(
                        new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21))
                .parse(source)
                .getResult()
                .orElseThrow();
    }

    private static JType buildFirstType(String source) {
        CompilationUnit cu = parse(source);
        TypeDeclaration<?> td = cu.getType(0);
        L1BuildContext ctx = new L1BuildContext(CanId.applicationId("myapp"), FILE_KEY, source);
        return new TypeBuilder(ctx).build(td, ctx.moduleId());
    }

    @Test
    void build_setsIdAndClassKind() {
        JType t = buildFirstType("package com.example;\n\npublic class Foo {}\n");
        assertEquals("can://java/myapp/" + FILE_KEY + "/Foo", t.getId());
        assertEquals("class", t.getKind());
    }

    @Test
    void build_derivesKindForInterfaceEnumRecordAnnotation() {
        assertEquals("interface", buildFirstType("package p;\npublic interface I {}\n").getKind());
        assertEquals("enum", buildFirstType("package p;\npublic enum E { A, B }\n").getKind());
        assertEquals("record", buildFirstType("package p;\npublic record R(int x) {}\n").getKind());
        assertEquals("annotation", buildFirstType("package p;\npublic @interface A {}\n").getKind());
    }

    @Test
    void build_capturesJavadocAsOwnComment() {
        JType t = buildFirstType("package p;\n/** A widget. */\nclass Foo {}\n");
        assertEquals(1, t.getComments().size());
        assertTrue(t.getComments().get(0).getContent().contains("A widget."));
        assertTrue(t.getComments().get(0).isJavadoc());
        assertNotNull(t.getComments().get(0).getSpan());
    }

    @Test
    void build_commentsAreOwnNotAllContained() {
        // v1 used getAllContainedComments(), so a type listed every comment inside every member.
        // v2 gives each node only its OWN attached comment.
        JType t = buildFirstType("package p;\n/** Type doc. */\nclass Foo {\n  /** Method doc. */\n  void m() {}\n}\n");
        assertEquals(1, t.getComments().size(), "the method's javadoc belongs to the method, not the type");
        assertTrue(t.getComments().get(0).getContent().contains("Type doc."));
    }

    @Test
    void build_capturesModifiers() {
        // Keystone's type node lists modifiers[] — v1 had them and v2 must not drop them.
        assertEquals(List.of("public", "abstract"),
                buildFirstType("package p;\npublic abstract class Foo {}\n").getModifiers());
    }

    @Test
    void build_capturesInheritance() {
        JType t = buildFirstType("package p;\nclass Foo extends Base implements Runnable {}\n");
        assertEquals(List.of("Base"), t.getBaseTypes());
        assertEquals(List.of("Runnable"), t.getInterfaces());
    }

    @Test
    void build_spanBytesSliceToTheTypeText() {
        String source = "package com.example;\n\npublic class Foo {}\n";
        JType t = buildFirstType(source);
        assertNotNull(t.getSpan());
        int[] bytes = t.getSpan().getBytes();
        assertTrue(source.substring(bytes[0], bytes[1]).contains("class Foo"),
                "span.bytes should slice module source to the type's declaration text");
    }

    @Test
    void build_recursesIntoMemberTypesViaContainment() {
        // Nesting is encoded by containment (member types under the parent's `types`) and the id
        // path — no separate nesting/is_local field.
        String source = "package p;\nclass Outer {\n  class Inner {}\n  enum E { A }\n}\n";
        JType outer = buildFirstType(source);

        assertEquals(Set.of("Inner", "E"), outer.getTypes().keySet());
        assertEquals("enum", outer.getTypes().get("E").getKind());

        JType inner = outer.getTypes().get("Inner");
        assertEquals(outer.getId() + "/Inner", inner.getId());
        assertEquals("class", inner.getKind());
    }

    @Test
    void build_populatesFieldsKeyedBySimpleName() {
        JType t = buildFirstType("package p;\nclass Foo {\n  private int count;\n  String name;\n}\n");
        assertEquals(Set.of("count", "name"), t.getFields().keySet());
        assertEquals("int", t.getFields().get("count").getType());
        assertEquals(t.getId() + "/count", t.getFields().get("count").getId());
    }

    @Test
    void build_populatesCallablesKeyedBySignature() {
        JType t = buildFirstType("package p;\nclass Foo {\n  Foo() {}\n  void inc() {}\n}\n");
        assertTrue(t.getCallables().containsKey("inc()"));
        assertEquals(2, t.getCallables().size(), "constructor + method");
        assertEquals("method", t.getCallables().get("inc()").getKind());
    }

    @Test
    void build_callableRefsSeeEnclosingTypeFields() {
        // TypeBuilder must hand its field names to the callable builder so refs.fields resolves.
        JType t = buildFirstType("package p;\nclass Foo {\n  int count;\n  void inc() { count = count + 1; }\n}\n");
        JCallable inc = t.getCallables().get("inc()");
        assertEquals(List.of("count"), inc.getRefs().getFields());
    }

    @Test
    void build_capturesStructuredDecoratorWithArgs() {
        JType t = buildFirstType("package p;\n@SuppressWarnings(\"unchecked\")\nclass Foo {}\n");
        assertEquals(1, t.getDecorators().size());
        JDecorator d = t.getDecorators().get(0);
        assertEquals("SuppressWarnings", d.getName());
        assertEquals(List.of("\"unchecked\""), d.getArgs());
        assertNotNull(d.getSpan());
    }
}
