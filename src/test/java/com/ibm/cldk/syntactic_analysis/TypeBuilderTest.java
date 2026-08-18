package com.ibm.cldk.syntactic_analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.ibm.cldk.schema.CanId;
import com.ibm.cldk.schema.JDecorator;
import com.ibm.cldk.schema.JType;
import java.util.List;
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
    void build_capturesStructuredDecoratorWithArgs() {
        JType t = buildFirstType("package p;\n@SuppressWarnings(\"unchecked\")\nclass Foo {}\n");
        assertEquals(1, t.getDecorators().size());
        JDecorator d = t.getDecorators().get(0);
        assertEquals("SuppressWarnings", d.getName());
        assertEquals(List.of("\"unchecked\""), d.getArgs());
        assertNotNull(d.getSpan());
    }
}
