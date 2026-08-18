package com.ibm.cldk.syntactic_analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.ibm.cldk.schema.CanId;
import com.ibm.cldk.schema.JField;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Tests the v2 {@link FieldBuilder} — one field node per declared variable, with id/type/span. */
class FieldBuilderTest {

    private static final String FILE_KEY = "src/main/java/com/example/Foo.java";
    private static final String TYPE_ID = "can://java/myapp/" + FILE_KEY + "/Foo";

    private static List<JField> build(String memberSource) {
        String source = "package com.example;\nclass Foo {\n  " + memberSource + "\n}\n";
        CompilationUnit cu = TestParsers.parseResolved(source);
        FieldDeclaration fd = cu.getType(0).findFirst(FieldDeclaration.class).orElseThrow();
        L1BuildContext ctx = new L1BuildContext(CanId.applicationId("myapp"), FILE_KEY, source);
        return new FieldBuilder(ctx).build(fd, TYPE_ID);
    }

    @Test
    void build_capturesNameTypeAndContainmentId() {
        List<JField> fields = build("private int count;");
        assertEquals(1, fields.size());
        JField f = fields.get(0);
        assertEquals("count", f.getName());
        assertEquals("int", f.getType());
        assertEquals(TYPE_ID + "/count", f.getId());
    }

    @Test
    void build_carriesFieldKind() {
        // Every v2 node carries a `kind` discriminator; the SDK models one Node keyed on it.
        assertEquals("field", build("private int count;").get(0).getKind());
    }

    @Test
    void build_capturesModifiers() {
        JField f = build("private static final String NAME = \"x\";").get(0);
        assertEquals(List.of("private", "static", "final"), f.getModifiers());
    }

    @Test
    void build_emitsOneFieldPerVariableInAMultiVariableDeclaration() {
        List<JField> fields = build("int a, b;");
        assertEquals(List.of("a", "b"), fields.stream().map(JField::getName).collect(Collectors.toList()));
        assertTrue(fields.stream().allMatch(f -> f.getType().equals("int")));
        assertEquals(TYPE_ID + "/a", fields.get(0).getId());
        assertEquals(TYPE_ID + "/b", fields.get(1).getId());
    }

    @Test
    void build_capturesFieldComment() {
        JField f = build("// how many\n  private int count;").get(0);
        assertEquals(1, f.getComments().size());
        assertTrue(f.getComments().get(0).getContent().contains("how many"));
        assertFalse(f.getComments().get(0).isJavadoc(), "a line comment is not javadoc");
    }

    @Test
    void build_capturesPerVariableInitializer() {
        // v1 kept variable_initializers keyed per declarator; v2 keeps one field per variable, each
        // carrying its own initializer expression text.
        List<JField> fields = build("int a = 1, b = 2;");
        assertEquals("1", fields.get(0).getInitializer());
        assertEquals("2", fields.get(1).getInitializer());
        assertNull(build("int c;").get(0).getInitializer(), "no initializer -> absent, not empty string");
    }

    @Test
    void build_spanBytesSliceToTheFieldDeclarationText() {
        List<JField> fields = build("private int count;");
        int[] bytes = fields.get(0).getSpan().getBytes();
        String source = "package com.example;\nclass Foo {\n  private int count;\n}\n";
        assertEquals("private int count;", source.substring(bytes[0], bytes[1]));
    }

    @Test
    void build_capturesStructuredDecorators() {
        JField f = build("@Column(name = \"id\") private Long id;").get(0);
        assertEquals(1, f.getDecorators().size());
        assertEquals("Column", f.getDecorators().get(0).getName());
        assertNotNull(f.getDecorators().get(0).getSpan());
    }
}
