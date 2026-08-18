package com.ibm.cldk.syntactic_analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.ibm.cldk.schema.CanId;
import com.ibm.cldk.schema.JModule;
import java.util.Set;
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
    void build_defaultsPackageToEmptyWhenAbsent() {
        String source = "public class Foo {}\n";
        CompilationUnit cu = parse(source);
        L1BuildContext ctx = new L1BuildContext(CanId.applicationId("myapp"), "Foo.java", source);

        JModule module = new ModuleBuilder(ctx).build(cu);

        assertEquals("", module.getPackageName());
    }
}
