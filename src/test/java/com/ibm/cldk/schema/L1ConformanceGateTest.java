package com.ibm.cldk.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.cldk.syntactic_analysis.L1Extractor;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The L1 conformance gate: emitted v2 output must validate against the canonical schema, and the
 * structural invariants the design spec names must hold.
 *
 * <p>The real-world cases are tagged {@code realworld} and excluded from the default {@code test}
 * task: analysing whole applications with full symbol resolution takes minutes, which is too slow for
 * an inner loop. Run them with {@code ./gradlew realWorldConformanceTest}. The in-repo fixture cases
 * stay in the default suite so the gate still guards every change.
 *
 * <p>The oracle is the in-repo JSON Schema (the SDK's v2 models do not exist yet); it is strict, so a
 * renamed or stray key fails here rather than reaching consumers. The gate runs over the small
 * in-repo fixtures and — when the git submodules are checked out — over real-world applications,
 * which is where scale-dependent problems (unresolvable dependencies, odd constructs) show up.
 */
class L1ConformanceGateTest {

    private static final Path TEST_APPS = Paths.get("src/test/resources/test-applications");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonSchema schema() throws IOException {
        try (InputStream in = L1ConformanceGateTest.class.getResourceAsStream("/schema/analysis.v2.schema.json")) {
            assertNotNull(in, "the canonical v2 schema must be on the test classpath");
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in);
        }
    }

    /** Analyse a project and return its emitted payload as JSON. */
    private static JsonNode analyse(Path project) throws IOException {
        Map<String, JModule> modules = L1Extractor.extractAll(project, project.getFileName().toString());
        Analysis analysis = V2Emitter.emit(project.getFileName().toString(), 1, modules, "test");
        return MAPPER.readTree(V2Json.compact().toJson(analysis));
    }

    private static void assertConformant(JsonNode payload) throws IOException {
        Set<ValidationMessage> problems = schema().validate(payload);
        assertTrue(problems.isEmpty(),
                "output must validate against the canonical v2 schema, but got:\n  "
                        + problems.stream().map(ValidationMessage::getMessage).collect(Collectors.joining("\n  ")));
    }

    /** Every module's source must be reproducible by slicing itself — the get_method_body contract. */
    private static void assertNodeTextIsSliceable(JsonNode payload) {
        JsonNode symbolTable = payload.get("application").get("symbol_table");
        assertTrue(symbolTable.size() > 0, "symbol_table must not be empty");
        symbolTable.fields().forEachRemaining(entry -> {
            JsonNode module = entry.getValue();
            byte[] source = module.get("source").asText().getBytes(StandardCharsets.UTF_8);
            JsonNode span = module.get("span");
            assertEquals(0, span.get("bytes").get(0).asInt(), entry.getKey());
            assertEquals(source.length, span.get("bytes").get(1).asInt(),
                    "module span must cover the whole file: " + entry.getKey());
        });
    }

    /** No body-node key may be a bare line — the two-tier identity gate requires the column. */
    private static void assertLocalIdsCarryColumns(JsonNode payload) {
        payload.get("application").get("symbol_table").forEach(module ->
                module.path("types").forEach(type -> assertTypeLocalIds(type)));
    }

    private static void assertTypeLocalIds(JsonNode type) {
        type.path("callables").forEach(callable ->
                callable.path("body").fieldNames().forEachRemaining(key ->
                        assertTrue(key.matches("\\d+:\\d+") || key.startsWith("@"),
                                "body key must be line:col or @tag, got: " + key)));
        type.path("types").forEach(L1ConformanceGateTest::assertTypeLocalIds);
    }

    @ParameterizedTest(name = "L1 gate on in-repo fixture: {0}")
    @ValueSource(strings = {
        "record-class-test",
        "init-blocks-test",
        "call-graph-test",
        "enum-record-bodies-test",
        "generics-varargs-duplicate-signature-test"
    })
    void inRepoFixturesConformToTheCanonicalSchema(String fixture) throws IOException {
        Path project = TEST_APPS.resolve(fixture);
        JsonNode payload = analyse(project);
        assertConformant(payload);
        assertNodeTextIsSliceable(payload);
        assertLocalIdsCarryColumns(payload);
    }

    @Test
    void idsAreStableAndOutputDeterministicAcrossRuns() throws IOException {
        Path project = TEST_APPS.resolve("record-class-test");
        assertEquals(analyse(project).toString(), analyse(project).toString(),
                "two runs over unchanged source must be byte-identical");
    }

    @Test
    void recordFixtureExercisesRecordComponents() throws IOException {
        // A field with no test is a silent regression point: assert a concrete value, not just a shape.
        JsonNode payload = analyse(TEST_APPS.resolve("record-class-test"));
        boolean sawRecordWithComponents = false;
        for (JsonNode module : payload.get("application").get("symbol_table")) {
            for (JsonNode type : module.path("types")) {
                if ("record".equals(type.path("kind").asText()) && type.path("record_components").size() > 0) {
                    sawRecordWithComponents = true;
                }
            }
        }
        assertTrue(sawRecordWithComponents, "the record fixture should yield a record with components");
    }

    @Test
    void initBlocksFixtureExercisesInitializerCallables() throws IOException {
        JsonNode payload = analyse(TEST_APPS.resolve("init-blocks-test"));
        boolean sawInitializer = false;
        for (JsonNode module : payload.get("application").get("symbol_table")) {
            for (JsonNode type : module.path("types")) {
                for (JsonNode callable : type.path("callables")) {
                    if ("initializer".equals(callable.path("kind").asText())) {
                        sawInitializer = true;
                    }
                }
            }
        }
        assertTrue(sawInitializer, "initializer blocks must surface as callables");
    }

    @Test
    void compactConstructorIsEmittedWithTheRecordComponentsAsItsSignature() throws IOException {
        // A compact constructor is a CompactConstructorDeclaration, not a CallableDeclaration, so it was
        // dropped wholesale. Its signature has to come from the record's components or no `new Money(...)`
        // site can ever join it.
        JsonNode money = typeIn(analyse(TEST_APPS.resolve("enum-record-bodies-test")), "Money");
        JsonNode ctor = money.path("callables").path("<init>(java.util.List, int)");
        assertFalse(ctor.isMissingNode(),
                "expected the compact constructor, got: " + fieldNames(money.path("callables")));
        assertEquals("constructor", ctor.path("kind").asText());
        assertTrue(ctor.path("body").size() > 0, "its body's call sites belong to it");
    }

    @Test
    void enumConstantClassBodiesAreEmittedAsTypes() throws IOException {
        JsonNode op = typeIn(analyse(TEST_APPS.resolve("enum-record-bodies-test")), "Op");
        JsonNode plus = op.path("types").path("$enum$PLUS");
        assertFalse(plus.isMissingNode(),
                "expected a type for PLUS's class body, got: " + fieldNames(op.path("types")));
        assertTrue(plus.path("callables").has("apply(int, int)"),
                "the overriding method must be a callable of the constant's own type");
        assertFalse(op.path("types").has("$enum$NOOP"), "a constant with no body gets no type");
    }

    @Test
    void initializerErrorChannelRecordsNestedThrows() throws IOException {
        // v1 only scanned an initializer's top-level statements, so a throw inside a catch was invisible.
        JsonNode app = typeIn(analyse(TEST_APPS.resolve("init-blocks-test")), "App");
        JsonNode init = app.path("callables").path("<clinit>$0()");
        assertFalse(init.isMissingNode());
        assertEquals("java.lang.RuntimeException", init.path("error_channel").path(0).asText());
    }

    @Test
    void callSitesCarryTheResolvedCalleeFactsTheSdkReconstructs() throws IOException {
        // D1 has the SDK rebuild v1's `.call_sites` surface from body nodes, so every fact v1's CallSite
        // exposed must still be present on one.
        JsonNode money = typeIn(analyse(TEST_APPS.resolve("enum-record-bodies-test")), "Money");
        JsonNode body = money.path("callables").path("plus(org.example.Money)").path("body");
        // `return new Money(tags, cents + other.cents());` — a constructor call and a method call.
        JsonNode ctorCall = nodeWithMethodName(body, "<init>");
        assertEquals("org.example.Money", ctorCall.path("return_type").asText(),
                "a constructor call evaluates to the instantiated type");
        JsonNode centsCall = nodeWithMethodName(body, "cents");
        assertEquals("int", centsCall.path("return_type").asText());
        assertEquals("public", centsCall.path("accessibility").asText(),
                "a record's accessor is public — the fact v1 spread over four booleans");
    }

    @Test
    void typeParametersCarryTheBoundsThatDistinguishOverloads() throws IOException {
        // These three `copy` overloads differ ONLY in their type parameter's bound. The erased signature
        // keeps them apart, but without `type_parameters` nothing in the output says why they differ:
        // the key is fully erased, so the bound's own type arguments survive only here.
        JsonNode utils = typeIn(analyse(TEST_APPS.resolve("generics-varargs-duplicate-signature-test")),
                "FunctorUtils");
        JsonNode callables = utils.path("callables");
        assertEquals("java.util.function.Consumer<?>",
                soleBoundOf(callables.path("copy(java.util.function.Consumer[])")));
        assertEquals("java.util.function.Predicate<?>",
                soleBoundOf(callables.path("copy(java.util.function.Predicate[])")));
        assertEquals("java.util.function.Function<?, ?>",
                soleBoundOf(callables.path("copy(java.util.function.Function[])")));
    }

    @Test
    void multipleTypeParametersKeepDeclarationOrder() throws IOException {
        // `<R extends Function<I, O>, P extends Function<...>, I, O>` — a type argument binds by position,
        // so reordering these would silently mis-describe the method.
        JsonNode utils = typeIn(analyse(TEST_APPS.resolve("generics-varargs-duplicate-signature-test")),
                "FunctorUtils");
        JsonNode params = utils.path("callables")
                .path("coerce(java.util.function.Function)")
                .path("type_parameters");
        assertEquals(4, params.size());
        assertEquals("R", params.path(0).path("name").asText());
        assertEquals("P", params.path(1).path("name").asText());
        assertEquals("I", params.path(2).path("name").asText());
        assertEquals("O", params.path(3).path("name").asText());
        assertTrue(params.path(2).path("bounds").isEmpty(), "`I` is unbounded");
    }

    /** The single declared bound of a callable's single type parameter. */
    private static String soleBoundOf(JsonNode callable) {
        assertFalse(callable.isMissingNode(), "expected the callable to be emitted");
        JsonNode params = callable.path("type_parameters");
        assertEquals(1, params.size(), "expected exactly one type parameter");
        JsonNode bounds = params.path(0).path("bounds");
        assertEquals(1, bounds.size(), "expected exactly one bound");
        return bounds.path(0).asText();
    }

    private static JsonNode nodeWithMethodName(JsonNode body, String methodName) {
        for (JsonNode node : body) {
            if (methodName.equals(node.path("method_name").asText())) {
                return node;
            }
        }
        throw new AssertionError("no call site named " + methodName + " among " + body.size() + " body nodes");
    }

    /** The first type of the module declaring {@code name}, searched by simple name. */
    private static JsonNode typeIn(JsonNode payload, String name) {
        for (JsonNode module : payload.get("application").get("symbol_table")) {
            JsonNode type = module.path("types").path(name);
            if (!type.isMissingNode()) {
                return type;
            }
        }
        throw new AssertionError("no type named " + name + " in the emitted symbol table");
    }

    private static String fieldNames(JsonNode node) {
        StringBuilder names = new StringBuilder();
        node.fieldNames().forEachRemaining(n -> names.append(names.length() == 0 ? "" : ", ").append(n));
        return names.toString();
    }

    static boolean submodulesCheckedOut() {
        return Files.isDirectory(TEST_APPS.resolve("spring-petclinic/src"));
    }

    @Tag("realworld")
    @ParameterizedTest(name = "L1 gate on real-world app: {0}")
    @EnabledIf("submodulesCheckedOut")
    @ValueSource(strings = {
        "spring-petclinic",
        "quarkuscoffeeshop-counter",
        "quarkuscoffeeshop-domain",
        "commons-lang"
    })
    void realWorldApplicationsConformToTheCanonicalSchema(String app) throws IOException {
        Path project = TEST_APPS.resolve(app);
        JsonNode payload = analyse(project);
        assertConformant(payload);
        assertNodeTextIsSliceable(payload);
        assertLocalIdsCarryColumns(payload);
        assertFalse(payload.get("application").get("symbol_table").isEmpty(),
                "a real application must yield modules");
    }

    @Test
    @Tag("realworld")
    @EnabledIf("submodulesCheckedOut")
    void springPetclinicResolvesFrameworkAnnotationsAndEntrypoints() throws IOException {
        // Spring controllers are the canonical entrypoint case, and structured decorators are what make
        // annotation arguments (routes) machine-readable.
        JsonNode payload = analyse(TEST_APPS.resolve("spring-petclinic"));
        boolean sawEntrypointClass = false;
        boolean sawDecoratorWithArgs = false;
        for (JsonNode module : payload.get("application").get("symbol_table")) {
            for (JsonNode type : module.path("types")) {
                sawEntrypointClass |= type.path("is_entrypoint_class").asBoolean(false);
                for (JsonNode decorator : type.path("decorators")) {
                    sawDecoratorWithArgs |= decorator.path("args").size() > 0;
                }
                for (JsonNode callable : type.path("callables")) {
                    for (JsonNode decorator : callable.path("decorators")) {
                        sawDecoratorWithArgs |= decorator.path("args").size() > 0;
                    }
                }
            }
        }
        assertTrue(sawEntrypointClass, "petclinic has Spring controllers, so some type is an entrypoint class");
        assertTrue(sawDecoratorWithArgs, "structured decorators must retain annotation arguments");
    }
}
