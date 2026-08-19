package com.ibm.cldk.syntactic_analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.github.javaparser.ast.CompilationUnit;
import com.ibm.cldk.SymbolTable;
import com.ibm.cldk.entities.Callable;
import com.ibm.cldk.entities.JavaCompilationUnit;
import com.ibm.cldk.schema.CanId;
import com.ibm.cldk.schema.JCallable;
import com.ibm.cldk.schema.JModule;
import com.ibm.cldk.schema.Span;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * v2 drops the per-callable {@code code} string that v1 carried, on the basis that body text is a slice
 * of {@code module.source}. That only holds if some span actually delimits the body: a callable's own
 * span covers its <em>whole declaration</em> (modifiers, signature and body), so slicing it does not
 * reproduce v1's {@code code}, which was the {@code { ... }} block alone.
 *
 * <p>These tests pin the equivalence directly — for the same source, slicing {@code body_span} out of
 * {@code module.source} must yield exactly what v1 put in {@code code} — so the migration cannot
 * silently change what downstream consumers get from {@code get_method_body}.
 */
class BodyTextParityTest {

    @AfterEach
    void clearV1StaticState() {
        // The v1 symbol table accumulates into a static table; keep it from leaking into other tests.
        SymbolTable.declaredMethodsAndConstructors.clear();
    }

    /** Slice a span out of the module source the way a consumer would. */
    private static String slice(JModule module, Span span) {
        byte[] source = module.getSource().getBytes(StandardCharsets.UTF_8);
        int[] bytes = span.getBytes();
        return new String(source, bytes[0], bytes[1] - bytes[0], StandardCharsets.UTF_8);
    }

    private static JModule buildV2(String source) {
        CompilationUnit cu = TestParsers.parseResolved(source);
        L1BuildContext ctx = new L1BuildContext(CanId.applicationId("app"), "src/Foo.java", source);
        return new ModuleBuilder(ctx).build(cu);
    }

    private static Callable v1Callable(String source, String namePrefix) throws IOException {
        Map<String, JavaCompilationUnit> table = SymbolTable.extractSingle(source).getLeft();
        for (JavaCompilationUnit cu : table.values()) {
            for (com.ibm.cldk.entities.Type type : cu.getTypeDeclarations().values()) {
                for (Map.Entry<String, Callable> e : type.getCallableDeclarations().entrySet()) {
                    if (e.getKey().startsWith(namePrefix)) {
                        return e.getValue();
                    }
                }
            }
        }
        throw new IllegalStateException("no v1 callable starting with " + namePrefix);
    }

    private static JCallable v2Callable(JModule module, String namePrefix) {
        for (Map.Entry<String, JCallable> e : module.getTypes().get("Foo").getCallables().entrySet()) {
            if (e.getKey().startsWith(namePrefix)) {
                return e.getValue();
            }
        }
        throw new IllegalStateException("no v2 callable starting with " + namePrefix);
    }

    @Test
    void bodySpanSliceEqualsV1CodeForAMethod() throws IOException {
        String source = "package p;\n"
                + "class Foo {\n"
                + "  int add(int a, int b) {\n"
                + "    int sum = a + b;\n"
                + "    return sum;\n"
                + "  }\n"
                + "}\n";
        JModule module = buildV2(source);
        JCallable v2 = v2Callable(module, "add(");
        assertNotNull(v2.getBodySpan(), "a method with a body must carry body_span");
        assertEquals(v1Callable(source, "add(").getCode(), slice(module, v2.getBodySpan()));
    }

    @Test
    void bodySpanSliceEqualsV1CodeForAConstructor() throws IOException {
        String source = "package p;\nclass Foo {\n  Foo(int x) {\n    this.x = x;\n  }\n  int x;\n}\n";
        JModule module = buildV2(source);
        JCallable v2 = v2Callable(module, "<init>");
        assertEquals(v1Callable(source, "<init>").getCode(), slice(module, v2.getBodySpan()));
    }

    @Test
    void callableSpanIsWiderThanBodySpan() throws IOException {
        // The distinction that makes body_span necessary: the callable's own span includes the signature.
        String source = "package p;\nclass Foo {\n  public int add(int a) { return a; }\n}\n";
        JModule module = buildV2(source);
        JCallable v2 = v2Callable(module, "add(");
        assertEquals("{ return a; }", slice(module, v2.getBodySpan()));
        assertEquals("public int add(int a) { return a; }", slice(module, v2.getSpan()));
    }

    @Test
    void abstractMethodHasNoBodySpan() {
        String source = "package p;\nabstract class Foo {\n  abstract int f();\n}\n";
        JModule module = buildV2(source);
        assertNull(v2Callable(module, "f(").getBodySpan(), "no body -> no body_span (absent = no fact)");
    }

    @Test
    void initializerBlockCarriesBodySpan() {
        String source = "package p;\nclass Foo {\n  static {\n    setUp();\n  }\n}\n";
        JModule module = buildV2(source);
        JCallable init = module.getTypes().get("Foo").getCallables().get("<clinit>$0()");
        assertNotNull(init.getBodySpan());
        assertEquals("{\n    setUp();\n  }", slice(module, init.getBodySpan()));
    }
}
