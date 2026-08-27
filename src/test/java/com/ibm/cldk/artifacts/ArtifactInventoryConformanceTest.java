package com.ibm.cldk.artifacts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.cldk.CodeAnalyzer;
import com.ibm.cldk.SymbolTable;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

/**
 * End-to-end gate for the repository-artifact layer: a full CLI run over the committed fixture must
 * emit an {@code application.artifacts} inventory that validates against the canonical v2 schema, and
 * the inventory (ungated) must be byte-identical across analysis levels 1, 2, and 3.
 */
class ArtifactInventoryConformanceTest {

    private static final Path FIXTURE =
            Paths.get("src/test/resources/test-applications/artifact-inventory-test");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static int run(String... args) {
        return new CommandLine(new CodeAnalyzer()).execute(args);
    }

    // The CLI options on CodeAnalyzer are static; reset the ones these tests touch (mirrors
    // CodeAnalyzerV2CliTest) so a value set here cannot leak into another test.
    @BeforeEach
    void resetStaticOptions() throws Exception {
        set("emit", "json");
        set("analysisLevel", 1);
        set("output", null);
        set("input", null);
        set("targetFiles", null);
        set("sourceAnalysis", null);
        CodeAnalyzer.projectRootPom = null;
    }

    @AfterEach
    void restoreSymbolTableStatics() throws Exception {
        Field solver = SymbolTable.class.getDeclaredField("javaSymbolSolver");
        solver.setAccessible(true);
        solver.set(null, null);
        clearCollection("unresolvedTypes");
        clearCollection("unresolvedExpressions");
        SymbolTable.declaredMethodsAndConstructors.clear();
        CodeAnalyzer.projectRootPom = null;
    }

    private static void set(String field, Object value) throws Exception {
        Field f = CodeAnalyzer.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(null, value);
    }

    private static void clearCollection(String field) throws Exception {
        Field f = SymbolTable.class.getDeclaredField(field);
        f.setAccessible(true);
        ((java.util.Collection<?>) f.get(null)).clear();
    }

    private static JsonSchema schema() throws IOException {
        try (InputStream in =
                ArtifactInventoryConformanceTest.class.getResourceAsStream("/schema/analysis.v2.schema.json")) {
            assertNotNull(in, "the canonical v2 schema must be on the test classpath");
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in);
        }
    }

    private static JsonNode runAndRead(Path out, String... extraArgs) throws IOException {
        String[] base = {"-i", FIXTURE.toString(), "-o", out.toString(), "--schema", "v2"};
        String[] args = new String[base.length + extraArgs.length];
        System.arraycopy(base, 0, args, 0, base.length);
        System.arraycopy(extraArgs, 0, args, base.length, extraArgs.length);
        assertEquals(0, run(args), "the v2 run over the fixture must succeed");
        return MAPPER.readTree(Files.readString(out.resolve("analysis.json")));
    }

    @Test
    void inventoryValidatesAgainstTheCanonicalSchema(@TempDir Path tmp) throws IOException {
        JsonNode payload = runAndRead(tmp.resolve("out"));
        Set<ValidationMessage> problems = schema().validate(payload);
        assertTrue(problems.isEmpty(),
                "output with an artifact inventory must validate against the v2 schema, but got:\n  "
                        + problems.stream().map(ValidationMessage::getMessage)
                                .collect(Collectors.joining("\n  ")));

        JsonNode artifacts = payload.get("application").get("artifacts");
        assertNotNull(artifacts, "the payload must carry an artifacts inventory");
        assertTrue(artifacts.has("pom.xml"), "the manifest is inventoried");
        assertTrue(artifacts.get("pom.xml").has("dependencies"), "the manifest carries its dependencies");
        assertTrue(artifacts.get("src/main/resources/application.properties").has("config_keys"),
                "the config file carries its keys");
    }

    @ParameterizedTest(name = "artifact inventory is present at analysis level {0}")
    @ValueSource(strings = {"1", "2", "3"})
    void inventoryIsUngatedAcrossAnalysisLevels(String level, @TempDir Path tmp) throws IOException {
        JsonNode payload = runAndRead(tmp.resolve("out"), "-a", level);
        assertNotNull(payload.get("application").get("artifacts"),
                "the inventory is ungated: it rides along at every analysis level");
    }

    @Test
    void inventoryIsByteIdenticalAcrossAnalysisLevels(@TempDir Path tmp) throws IOException {
        String l1 = artifactsJson(runAndRead(tmp.resolve("l1"), "-a", "1"));
        String l2 = artifactsJson(runAndRead(tmp.resolve("l2"), "-a", "2"));
        String l3 = artifactsJson(runAndRead(tmp.resolve("l3"), "-a", "3"));
        assertEquals(l1, l2, "the inventory must not change between level 1 and 2");
        assertEquals(l2, l3, "the inventory must not change between level 2 and 3");
    }

    /** The artifacts sub-tree serialized canonically, so byte-identity is compared over the inventory
     * alone (the symbol table legitimately grows with the analysis level). */
    private static String artifactsJson(JsonNode payload) {
        return payload.get("application").get("artifacts").toString();
    }
}
