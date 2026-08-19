package com.ibm.cldk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * CLI-level tests for the {@code --schema v2} path: the emitted envelope, and the flag-validation
 * rules from the CLI contract (an unsupported combination must fail loudly rather than silently
 * produce a different shape).
 */
class CodeAnalyzerV2CliTest {

    private static Path project(Path root) throws IOException {
        Path pkg = root.resolve("src/main/java/com/example");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("Widget.java"),
                "package com.example;\npublic class Widget {\n  public int size() { return 1; }\n}\n",
                StandardCharsets.UTF_8);
        return root;
    }

    private static int run(String... args) {
        return new CommandLine(new CodeAnalyzer()).execute(args);
    }

    /**
     * The pre-existing CLI options on {@link CodeAnalyzer} are static, so a value set by one test
     * would leak into the next. Reset the ones these tests touch so each case starts from defaults.
     */
    @BeforeEach
    void resetStaticOptions() throws Exception {
        set("emit", "json");
        set("analysisLevel", 1);
        set("output", null);
        set("input", null);
        set("targetFiles", null);
        set("sourceAnalysis", null);
    }

    private static void set(String field, Object value) throws Exception {
        Field f = CodeAnalyzer.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(null, value);
    }

    @Test
    void v2SchemaWritesCanonicalEnvelopeToAnalysisJson(@TempDir Path tmp) throws IOException {
        Path in = project(tmp.resolve("app"));
        Path out = tmp.resolve("out");

        assertEquals(0, run("-i", in.toString(), "-o", out.toString(), "--schema", "v2", "--app-name", "widgets"));

        Path analysis = out.resolve("analysis.json");
        assertTrue(Files.exists(analysis), "analysis.json must be written");
        JsonObject root = JsonParser.parseString(Files.readString(analysis)).getAsJsonObject();

        assertEquals("2.0.0", root.get("schema_version").getAsString());
        assertEquals("java", root.get("language").getAsString());
        assertEquals(1, root.get("max_level").getAsInt());
        assertEquals("codeanalyzer-java", root.getAsJsonObject("analyzer").get("name").getAsString());

        JsonObject app = root.getAsJsonObject("application");
        assertEquals("can://java/widgets", app.get("id").getAsString());
        JsonObject symbolTable = app.getAsJsonObject("symbol_table");
        assertTrue(symbolTable.has("src/main/java/com/example/Widget.java"),
                "keyed by relative path, got: " + symbolTable.keySet());
    }

    @Test
    void v2SchemaIsNotTheDefault(@TempDir Path tmp) throws IOException {
        // The legacy shape stays the default until the rest of the migration lands, so existing
        // consumers are unaffected by this change.
        Path in = project(tmp.resolve("app"));
        Path out = tmp.resolve("out");
        assertEquals(0, run("-i", in.toString(), "-o", out.toString()));
        JsonObject root = JsonParser.parseString(Files.readString(out.resolve("analysis.json"))).getAsJsonObject();
        assertFalse(root.has("schema_version"), "default output is still the v1 shape");
        assertTrue(root.has("symbol_table"), "v1 keeps symbol_table at the top level");
    }

    @Test
    void unknownSchemaValueFailsLoudly(@TempDir Path tmp) throws IOException {
        Path in = project(tmp.resolve("app"));
        assertNotEquals(0, run("-i", in.toString(), "--schema", "v3"),
                "an unrecognised flag value must not silently fall back");
    }

    @Test
    void v2WithAnalysisLevelAboveOneFailsLoudly(@TempDir Path tmp) throws IOException {
        Path in = project(tmp.resolve("app"));
        assertNotEquals(0, run("-i", in.toString(), "--schema", "v2", "-a", "2"),
                "v2 has no call graph yet; asking for level 2 must be an error, not a level-1 payload");
    }

    @Test
    void v2WithNeo4jEmitFailsLoudly(@TempDir Path tmp) throws IOException {
        Path in = project(tmp.resolve("app"));
        assertNotEquals(0, run("-i", in.toString(), "--schema", "v2", "--emit", "neo4j"),
                "the graph projection is still v1");
    }
}
