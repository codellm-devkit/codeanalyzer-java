package com.ibm.cldk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
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
        // size() has an in-project call (count()) and an out-of-project call (Math.max), so level-2
        // output carries an in-project edge always and an external edge only under --external-calls.
        Files.writeString(pkg.resolve("Widget.java"),
                "package com.example;\npublic class Widget {\n"
                        + "  public int size() { return Math.max(count(), 0); }\n"
                        + "  private int count() { return 1; }\n}\n",
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
        CodeAnalyzer.projectRootPom = null;
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
        // projectRootPom is also reset in @BeforeEach; mirror that here so the last test in this
        // class does not leave a stale (possibly deleted temp-dir) path that bleeds into tests in
        // other classes that call RtaCallGraph.endpoints directly (which only sets it when null).
        CodeAnalyzer.projectRootPom = null;
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
    void v2AtAnalysisLevelThreeEmitsDataflowOverlays(@TempDir Path tmp) throws IOException {
        Path in = project(tmp.resolve("app"));
        Path out = tmp.resolve("out");
        assertEquals(0, run("-i", in.toString(), "-o", out.toString(), "--schema", "v2", "-a", "3"),
                "level 3 is supported by the AST engine: it needs no build, only the sources");
        String json = Files.readString(out.resolve("analysis.json"));
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(3, root.get("max_level").getAsInt());
        assertTrue(json.contains("\"cfg\""), "level 3 lays the cfg overlay on callables");
    }

    @Test
    void v2WithAnalysisLevelAboveThreeFailsLoudly(@TempDir Path tmp) throws IOException {
        Path in = project(tmp.resolve("app"));
        assertNotEquals(0, run("-i", in.toString(), "--schema", "v2", "-a", "4"),
                "L4 is not implemented; asking for a level beyond 3 must be an error, not an L3 payload");
    }

    @Test
    void v2WalaL3EngineDegradesClearlyWhenBuildAbsent(@TempDir Path tmp) throws IOException {
        // No class files present — WALA cannot build the call graph; must exit 0 with declared
        // edges (L2 degraded mode) rather than crashing or rejecting the flag.
        Path in = project(tmp.resolve("app"));
        Path out = tmp.resolve("out");

        assertEquals(0, run("-i", in.toString(), "-o", out.toString(),
                "--schema", "v2", "-a", "3", "--l3-engine", "wala", "--no-build"),
                "--l3-engine wala must exit 0 even when WALA cannot build the call graph");

        JsonObject root = JsonParser.parseString(Files.readString(out.resolve("analysis.json")))
                .getAsJsonObject();
        // Level 3 is the requested level; even when WALA degrades, max_level is still 3.
        assertEquals(3, root.get("max_level").getAsInt());

        // The WALA engine with no compiled class files produces no application methods, so
        // L3WalaOverlays.apply either is bypassed or processes zero methods — either way no
        // cfg or ddg overlays appear.  This is the defining property of the degraded mode.
        assertFalse(hasNonEmptyOverlay(root, "cfg"),
                "degraded WALA run must not carry cfg overlays");
        assertFalse(hasNonEmptyOverlay(root, "ddg"),
                "degraded WALA run must not carry ddg overlays");
    }

    @Test
    void v2L3EngineUpperCaseNormalizedToLower(@TempDir Path tmp) throws IOException {
        // --l3-engine AST (uppercase) must be treated identically to --l3-engine ast.  Without
        // normalization, the case-sensitive gate in CallableBuilder fires as false and the run
        // silently emits no overlays despite the CLI validator accepting the value.
        Path in = project(tmp.resolve("app"));
        Path out = tmp.resolve("out");
        assertEquals(0, run("-i", in.toString(), "-o", out.toString(),
                "--schema", "v2", "-a", "3", "--l3-engine", "AST"),
                "--l3-engine AST must be accepted and produce overlays like --l3-engine ast");
        JsonObject root = JsonParser.parseString(Files.readString(out.resolve("analysis.json")))
                .getAsJsonObject();
        assertEquals(3, root.get("max_level").getAsInt());
        assertTrue(hasNonEmptyOverlay(root, "cfg"),
                "--l3-engine AST (uppercase) must produce cfg overlays; normalize-to-lower is required");
    }

    /**
     * Realworld test: compile a fixture, run with {@code --l3-engine wala}, and assert that
     * cfg and ddg overlays are present on the covered callable.
     */
    @Test
    @Tag("realworld")
    void v2WalaL3EngineProducesOverlays(@TempDir Path tmp) throws Exception {
        // Fixture: compute(int) has a scalar data dependency (r flows into helper(r)).
        String fixtureSource = "package com.example;\n"
                + "public class Sample {\n"
                + "    private int value = 10;\n"
                + "    public int compute(int x) {\n"
                + "        int r = helper(x);\n"
                + "        if (r > value) {\n"
                + "            return r;\n"
                + "        }\n"
                + "        return 0;\n"
                + "    }\n"
                + "    private int helper(int v) { return v * 2; }\n"
                + "}\n";

        // Create Maven-layout project
        Path projectDir = tmp.resolve("app");
        Path pkg = projectDir.resolve("src/main/java/com/example");
        Files.createDirectories(pkg);
        Path sourceFile = pkg.resolve("Sample.java");
        Files.writeString(sourceFile, fixtureSource, StandardCharsets.UTF_8);

        // Compile the fixture into the project directory so WALA can load the class files.
        int rc = ToolProvider.getSystemJavaCompiler().run(
                null, null, null,
                "-g", "-d", projectDir.toString(), sourceFile.toString());
        assertEquals(0, rc, "fixture compilation must succeed");

        Path out = tmp.resolve("out");
        assertEquals(0, run("-i", projectDir.toString(), "-o", out.toString(),
                "--schema", "v2", "-a", "3", "--l3-engine", "wala", "--no-build"),
                "--l3-engine wala must exit 0 on a compiled fixture");

        String json = Files.readString(out.resolve("analysis.json"));
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(3, root.get("max_level").getAsInt(), "max_level must be 3");

        // Verify overlays are present: find at least one callable with non-empty cfg and ddg.
        assertTrue(hasNonEmptyOverlay(root, "cfg"),
                "at least one callable must carry a non-empty cfg");
        assertTrue(hasNonEmptyOverlay(root, "ddg"),
                "compute(int) must carry a non-empty ddg (scalar dep r→helper)");

        // The AST engine path must still work unchanged.
        Path out2 = tmp.resolve("out2");
        assertEquals(0, run("-i", projectDir.toString(), "-o", out2.toString(),
                "--schema", "v2", "-a", "3", "--l3-engine", "ast"),
                "--l3-engine ast must still work");
        JsonObject root2 = JsonParser.parseString(Files.readString(out2.resolve("analysis.json")))
                .getAsJsonObject();
        assertEquals(3, root2.get("max_level").getAsInt(), "ast path must still emit max_level=3");
    }

    /** Returns true when any callable in the symbol table has a non-empty array for {@code key}. */
    private static boolean hasNonEmptyOverlay(JsonObject root, String key) {
        JsonObject symTable = root.getAsJsonObject("application").getAsJsonObject("symbol_table");
        for (Map.Entry<String, JsonElement> fileEntry : symTable.entrySet()) {
            JsonObject types = fileEntry.getValue().getAsJsonObject().getAsJsonObject("types");
            if (types == null) {
                continue;
            }
            if (hasNonEmptyOverlayInTypes(types, key)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNonEmptyOverlayInTypes(JsonObject types, String key) {
        for (Map.Entry<String, JsonElement> typeEntry : types.entrySet()) {
            JsonObject typeObj = typeEntry.getValue().getAsJsonObject();
            JsonObject callables = typeObj.getAsJsonObject("callables");
            if (callables != null) {
                for (Map.Entry<String, JsonElement> callableEntry : callables.entrySet()) {
                    JsonObject callable = callableEntry.getValue().getAsJsonObject();
                    if (callable.has(key) && callable.getAsJsonArray(key).size() > 0) {
                        return true;
                    }
                }
            }
            // Check nested types
            JsonObject nestedTypes = typeObj.getAsJsonObject("types");
            if (nestedTypes != null && hasNonEmptyOverlayInTypes(nestedTypes, key)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void v2ExternalCallsAreOffByDefaultForV1Parity(@TempDir Path tmp) throws IOException {
        Path in = project(tmp.resolve("app"));
        Path out = tmp.resolve("out");
        assertEquals(0, run("-i", in.toString(), "-o", out.toString(), "--schema", "v2", "-a", "2"));
        JsonObject app = JsonParser.parseString(Files.readString(out.resolve("analysis.json")))
                .getAsJsonObject().getAsJsonObject("application");
        assertFalse(app.has("external_symbols"),
                "external calls are off by default (v1 parity): no external_symbols key");
        assertTrue(app.has("call_graph"), "in-project edges are still emitted");
    }

    @Test
    void v2ExternalCallsFlagHomesOutOfProjectTargets(@TempDir Path tmp) throws IOException {
        Path in = project(tmp.resolve("app"));
        Path out = tmp.resolve("out");
        assertEquals(0, run("-i", in.toString(), "-o", out.toString(),
                "--schema", "v2", "-a", "2", "--external-calls"));
        JsonObject app = JsonParser.parseString(Files.readString(out.resolve("analysis.json")))
                .getAsJsonObject().getAsJsonObject("application");
        assertTrue(app.has("external_symbols"),
                "--external-calls homes out-of-project targets (e.g. java.lang.Math)");
    }

    @Test
    void v2WithNeo4jEmitFailsLoudly(@TempDir Path tmp) throws IOException {
        Path in = project(tmp.resolve("app"));
        assertNotEquals(0, run("-i", in.toString(), "--schema", "v2", "--emit", "neo4j"),
                "the graph projection is still v1");
    }
}
