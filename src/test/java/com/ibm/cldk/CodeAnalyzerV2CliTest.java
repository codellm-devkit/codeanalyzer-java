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
import org.junit.jupiter.api.AfterEach;
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

    /**
     * These tests drive the whole CLI, which runs the v1 symbol table and leaves its <em>static</em>
     * state populated ({@code javaSymbolSolver}, the resolution caches, the declared-callables table).
     * {@link SymbolTable#extractSingle} does not assign that solver field, so it behaves differently
     * depending on whether something else ran first — restore the initial state so this class cannot
     * change the outcome of tests that run after it.
     */
    @AfterEach
    void restoreSymbolTableStatics() throws Exception {
        Field solver = SymbolTable.class.getDeclaredField("javaSymbolSolver");
        solver.setAccessible(true);
        solver.set(null, null);
        clearCollection("unresolvedTypes");
        clearCollection("unresolvedExpressions");
        SymbolTable.declaredMethodsAndConstructors.clear();
    }

    private static void clearCollection(String field) throws Exception {
        Field f = SymbolTable.class.getDeclaredField(field);
        f.setAccessible(true);
        ((java.util.Collection<?>) f.get(null)).clear();
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
    void cacheFileIsWrittenAndReusedOnASecondRun(@TempDir Path tmp) throws IOException {
        Path in = project(tmp.resolve("app"));
        Path out = tmp.resolve("out");
        Path cache = tmp.resolve("cache");

        assertEquals(0, run("-i", in.toString(), "-o", out.toString(), "--schema", "v2",
                "--app-name", "widgets", "-c", cache.toString()));
        Path cacheFile = cache.resolve("analysis_cache.json");
        assertTrue(Files.exists(cacheFile), "a run with --cache-dir must write analysis_cache.json");

        // Prove reuse rather than timing it: plant a sentinel in the cached module. If the second run
        // reuses the cache the sentinel survives into the output; if it rebuilds, it cannot.
        String doctored = Files.readString(cacheFile).replace("\"package\":\"com.example\"",
                "\"package\":\"SENTINEL\"");
        assertTrue(doctored.contains("SENTINEL"), "precondition: the cache holds the package name");
        Files.writeString(cacheFile, doctored);

        assertEquals(0, run("-i", in.toString(), "-o", out.toString(), "--schema", "v2",
                "--app-name", "widgets", "-c", cache.toString()));
        assertTrue(Files.readString(out.resolve("analysis.json")).contains("SENTINEL"),
                "the second run should have reused the cached module");
    }

    @Test
    void eagerIgnoresTheCache(@TempDir Path tmp) throws IOException {
        Path in = project(tmp.resolve("app"));
        Path out = tmp.resolve("out");
        Path cache = tmp.resolve("cache");
        run("-i", in.toString(), "-o", out.toString(), "--schema", "v2", "-c", cache.toString());
        Path cacheFile = cache.resolve("analysis_cache.json");
        Files.writeString(cacheFile,
                Files.readString(cacheFile).replace("\"package\":\"com.example\"", "\"package\":\"SENTINEL\""));

        assertEquals(0, run("-i", in.toString(), "-o", out.toString(), "--schema", "v2",
                "-c", cache.toString(), "--eager"));
        assertFalse(Files.readString(out.resolve("analysis.json")).contains("SENTINEL"),
                "--eager must rebuild instead of trusting the cache");
    }

    @Test
    void changedFileIsRebuiltWhileOthersAreReused(@TempDir Path tmp) throws IOException {
        Path in = project(tmp.resolve("app"));
        Path out = tmp.resolve("out");
        Path cache = tmp.resolve("cache");
        Path second = in.resolve("src/main/java/com/example/Other.java");
        Files.writeString(second, "package com.example;\npublic class Other { int n() { return 2; } }\n",
                StandardCharsets.UTF_8);
        run("-i", in.toString(), "-o", out.toString(), "--schema", "v2", "-c", cache.toString());

        // Sentinel both cached modules, then edit only one file on disk.
        Path cacheFile = cache.resolve("analysis_cache.json");
        Files.writeString(cacheFile, Files.readString(cacheFile).replace("int n()", "int SENTINEL()"));
        Files.writeString(second, "package com.example;\npublic class Other { int n() { return 3; } }\n",
                StandardCharsets.UTF_8);

        assertEquals(0, run("-i", in.toString(), "-o", out.toString(), "--schema", "v2",
                "-c", cache.toString()));
        String analysis = Files.readString(out.resolve("analysis.json"));
        assertFalse(analysis.contains("SENTINEL"), "the edited file must be rebuilt, not reused");
        assertTrue(analysis.contains("return 3"), "and the new content must be present");
    }

    @Test
    void noCacheDirMeansNoCacheFile(@TempDir Path tmp) throws IOException {
        Path in = project(tmp.resolve("app"));
        Path out = tmp.resolve("out");
        assertEquals(0, run("-i", in.toString(), "-o", out.toString(), "--schema", "v2"));
        assertFalse(Files.exists(out.resolve("analysis_cache.json")),
                "caching is opt-in: no --cache-dir, no cache file");
    }

    @Test
    void unknownSchemaValueFailsLoudly(@TempDir Path tmp) throws IOException {
        Path in = project(tmp.resolve("app"));
        assertNotEquals(0, run("-i", in.toString(), "--schema", "v3"),
                "an unrecognised flag value must not silently fall back");
    }

    @Test
    void v2AtAnalysisLevelTwoEmitsTheCallGraphOverlay(@TempDir Path tmp) throws IOException {
        Path in = project(tmp.resolve("app"));
        Path out = tmp.resolve("out");
        assertEquals(0, run("-i", in.toString(), "-o", out.toString(), "--schema", "v2", "-a", "2"),
                "level 2 is supported: declared edges need only the dependency jars, never a build");
        JsonObject root = JsonParser.parseString(Files.readString(out.resolve("analysis.json"))).getAsJsonObject();
        assertEquals(2, root.get("max_level").getAsInt());
        assertTrue(root.getAsJsonObject("application").has("call_graph"),
                "level 2 attaches the call_graph overlay");
    }

    @Test
    void v2WithAnalysisLevelAboveTwoFailsLoudly(@TempDir Path tmp) throws IOException {
        Path in = project(tmp.resolve("app"));
        assertNotEquals(0, run("-i", in.toString(), "--schema", "v2", "-a", "3"),
                "L3/L4 are not implemented; asking for a level beyond 2 must be an error, not an L2 payload");
    }

    @Test
    void v2WithNeo4jEmitFailsLoudly(@TempDir Path tmp) throws IOException {
        Path in = project(tmp.resolve("app"));
        assertNotEquals(0, run("-i", in.toString(), "--schema", "v2", "--emit", "neo4j"),
                "the graph projection is still v1");
    }
}
