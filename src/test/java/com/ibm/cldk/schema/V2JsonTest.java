package com.ibm.cldk.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.google.gson.JsonObject;
import com.ibm.cldk.syntactic_analysis.L1BuildContext;
import com.ibm.cldk.syntactic_analysis.ModuleBuilder;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Serialization contract for schema v2: the emitted JSON <em>key names</em> (snake_case, so one set of
 * SDK models parses every analyzer) and the no-null convention (absence encodes "no fact"). These keys
 * are the contract — a rename here breaks every consumer, so they are asserted explicitly.
 */
class V2JsonTest {

    private static final String FILE_KEY = "src/Foo.java";
    private static final String SOURCE = "package com.example;\n"
            + "import java.util.List;\n"
            + "class Foo {\n"
            + "  private int count;\n"
            + "  Foo() {}\n"
            + "  int add(int a, String... rest) throws IllegalStateException {\n"
            + "    helper(a);\n"
            + "    return count;\n"
            + "  }\n"
            + "}\n";

    private static JsonObject payload() {
        CompilationUnit cu = new JavaParser(
                        new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21))
                .parse(SOURCE)
                .getResult()
                .orElseThrow();
        L1BuildContext ctx = new L1BuildContext(CanId.applicationId("myapp"), FILE_KEY, SOURCE);
        Map<String, JModule> modules = new LinkedHashMap<>();
        modules.put(FILE_KEY, new ModuleBuilder(ctx).build(cu));
        return V2Json.compact().toJsonTree(V2Emitter.emit("myapp", 1, modules)).getAsJsonObject();
    }

    private static JsonObject module() {
        return payload().getAsJsonObject("application").getAsJsonObject("symbol_table").getAsJsonObject(FILE_KEY);
    }

    private static JsonObject fooType() {
        return module().getAsJsonObject("types").getAsJsonObject("Foo");
    }

    /** The one callable whose kind is {@code method} (the constructor is the other entry). */
    private static JsonObject theMethod() {
        JsonObject callables = fooType().getAsJsonObject("callables");
        return callables.keySet().stream()
                .map(callables::getAsJsonObject)
                .filter(c -> "method".equals(c.get("kind").getAsString()))
                .findFirst()
                .orElseThrow();
    }

    private static JsonObject theConstructor() {
        JsonObject callables = fooType().getAsJsonObject("callables");
        return callables.keySet().stream()
                .map(callables::getAsJsonObject)
                .filter(c -> "constructor".equals(c.get("kind").getAsString()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void envelopeUsesSnakeCaseManifestKeys() {
        JsonObject root = payload();
        assertEquals("2.0.0", root.get("schema_version").getAsString());
        assertEquals("java", root.get("language").getAsString());
        assertEquals(1, root.get("max_level").getAsInt());
        assertTrue(root.has("application"));
    }

    @Test
    void applicationCarriesIdKindAndFileKeyedSymbolTable() {
        JsonObject app = payload().getAsJsonObject("application");
        assertEquals("can://java/myapp", app.get("id").getAsString());
        assertEquals("application", app.get("kind").getAsString());
        assertTrue(app.getAsJsonObject("symbol_table").has(FILE_KEY));
    }

    @Test
    void moduleUsesPackageSourceAndContentHashKeys() {
        JsonObject module = module();
        assertEquals("module", module.get("kind").getAsString());
        assertEquals("com.example", module.get("package").getAsString(), "`package` is a Java keyword, aliased");
        assertEquals(SOURCE, module.get("source").getAsString());
        assertTrue(module.has("content_hash"));
        assertTrue(module.has("imports"));
        assertTrue(module.has("span"));
    }

    @Test
    void typeUsesBaseTypesAndInterfacesKeys() {
        JsonObject type = fooType();
        assertEquals("class", type.get("kind").getAsString());
        assertTrue(type.has("base_types"));
        assertTrue(type.has("interfaces"));
        assertTrue(type.has("decorators"));
        assertTrue(type.has("fields"));
        assertTrue(type.has("callables"));
    }

    @Test
    void callableUsesErrorChannelAndNestedMetricsAndRefs() {
        JsonObject method = theMethod();
        assertEquals("IllegalStateException", method.getAsJsonArray("error_channel").get(0).getAsString());
        assertTrue(method.has("return_type"));
        assertTrue(method.getAsJsonObject("metrics").has("cyclomatic"), "metrics are nested, not flattened");
        assertTrue(method.getAsJsonObject("refs").has("types"));
        assertTrue(method.getAsJsonObject("refs").has("fields"));
        assertTrue(method.has("body"));
    }

    @Test
    void fieldCarriesKindAndParameterCarriesIsVariadic() {
        assertEquals("field", fooType().getAsJsonObject("fields").getAsJsonObject("count").get("kind").getAsString());
        JsonObject variadic = theMethod().getAsJsonArray("parameters").get(1).getAsJsonObject();
        assertTrue(variadic.get("is_variadic").getAsBoolean());
    }

    @Test
    void spanCarriesStartEndAndByteOffsets() {
        JsonObject span = fooType().getAsJsonObject("span");
        assertEquals(2, span.getAsJsonArray("start").size());
        assertEquals(2, span.getAsJsonArray("end").size());
        assertEquals(2, span.getAsJsonArray("bytes").size(), "byte offsets make node text an O(1) slice");
    }

    @Test
    void nullsAreOmittedRatherThanEmitted() {
        // Absence encodes "no fact": a constructor has no return type, and at L1 no call site has a
        // resolved callee (that key appears once L2 backfills it).
        assertFalse(theConstructor().has("return_type"), "constructor must not carry a null return_type");

        JsonObject body = theMethod().getAsJsonObject("body");
        JsonObject callNode = body.getAsJsonObject(body.keySet().iterator().next());
        assertEquals("call", callNode.get("kind").getAsString());
        assertFalse(callNode.has("callee"), "callee is absent at L1, not null");
    }

    @Test
    void bodyIsKeyedByBareLocalId() {
        // `line:col`, never the full `<callable-id>@line:col` form.
        String key = theMethod().getAsJsonObject("body").keySet().iterator().next();
        assertTrue(key.matches("\\d+:\\d+"), "expected a bare local id, got: " + key);
    }
}
