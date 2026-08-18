package com.ibm.cldk.syntactic_analysis;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.ibm.cldk.schema.CanId;
import com.ibm.cldk.schema.JImport;
import com.ibm.cldk.schema.JModule;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Tests the v2 {@link ModuleBuilder} building a {@code module} node directly from the AST. */
class ModuleBuilderTest {

    private static CompilationUnit parse(String source) {
        return new JavaParser(
                        new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21))
                .parse(source)
                .getResult()
                .orElseThrow();
    }

    /** Build a module from source using a fixed file key. */
    private static JModule build(String source) {
        L1BuildContext ctx = new L1BuildContext(CanId.applicationId("myapp"), "src/Foo.java", source);
        return new ModuleBuilder(ctx).build(parse(source));
    }

    @Test
    void build_setsModuleIdKindPackageAndSource() {
        String source = "package com.example;\n\npublic class Foo {}\n";
        CompilationUnit cu = parse(source);
        String fileKey = "src/main/java/com/example/Foo.java";
        L1BuildContext ctx = new L1BuildContext(CanId.applicationId("myapp"), fileKey, source);

        JModule module = new ModuleBuilder(ctx).build(cu);

        assertEquals("can://java/myapp/" + fileKey, module.getId());
        assertEquals("module", module.getKind());
        assertEquals("com.example", module.getPackageName());
        assertEquals(source, module.getSource());
    }

    @Test
    void build_populatesTopLevelTypesKeyedBySimpleName() {
        String source = "package com.example;\n\npublic class Foo {}\ninterface Bar {}\n";
        CompilationUnit cu = parse(source);
        L1BuildContext ctx = new L1BuildContext(CanId.applicationId("myapp"), "src/Foo.java", source);

        JModule module = new ModuleBuilder(ctx).build(cu);

        assertEquals(Set.of("Foo", "Bar"), module.getTypes().keySet());
        assertEquals("class", module.getTypes().get("Foo").getKind());
        assertEquals("interface", module.getTypes().get("Bar").getKind());
        assertEquals("can://java/myapp/src/Foo.java/Foo", module.getTypes().get("Foo").getId());
    }

    @Test
    void build_setsContentHashThatIsStableAndSourceSensitive() {
        String a = "package p;\nclass Foo {}\n";
        String b = "package p;\nclass Bar {}\n";
        JModule m1 = build(a);
        JModule m2 = build(a);
        JModule m3 = build(b);

        assertTrue(m1.getContentHash().matches("[0-9a-f]{64}"), "expected lowercase sha-256 hex");
        assertEquals(m1.getContentHash(), m2.getContentHash(), "same source -> same hash (caching + Neo4j diffing)");
        assertNotEquals(m1.getContentHash(), m3.getContentHash(), "different source -> different hash");
    }

    @Test
    void build_capturesImports() {
        String source = "package p;\nimport java.util.List;\nimport static java.util.Arrays.asList;\n"
                + "import java.io.*;\nclass Foo {}\n";
        List<JImport> imports = build(source).getImports();

        assertEquals(List.of("java.util.List", "java.util.Arrays.asList", "java.io"),
                imports.stream().map(JImport::getPath).collect(Collectors.toList()));
        assertEquals("List", imports.get(0).getName());
        assertTrue(imports.get(1).isStatic());
        assertTrue(imports.get(2).isWildcard());
        assertNotNull(imports.get(0).getSpan());
    }

    @Test
    void build_moduleSpanCoversTheWholeFile() {
        // The invariant the SDK relies on: module.source[span.bytes] IS the whole file.
        String source = "package com.example;\n\npublic class Foo {}\n";
        JModule module = build(source);
        assertNotNull(module.getSpan());
        assertArrayEquals(new int[] {1, 1}, module.getSpan().getStart());
        int[] bytes = module.getSpan().getBytes();
        assertEquals(0, bytes[0]);
        assertEquals(source.getBytes(StandardCharsets.UTF_8).length, bytes[1]);
    }

    @Test
    void build_defaultsPackageToEmptyWhenAbsent() {
        String source = "public class Foo {}\n";
        CompilationUnit cu = parse(source);
        L1BuildContext ctx = new L1BuildContext(CanId.applicationId("myapp"), "Foo.java", source);

        JModule module = new ModuleBuilder(ctx).build(cu);

        assertEquals("", module.getPackageName());
    }
}
