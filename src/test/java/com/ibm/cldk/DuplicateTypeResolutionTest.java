package com.ibm.cldk;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/**
 * One qualified type name declared in several source roots (#269): vendored copies of a shared
 * library across services. Each caller binds to its own copy, and the duplication is reported. The
 * CLI helpers mirror {@link CodeAnalyzerV2CliTest}'s, including the static-state resets.
 */
class DuplicateTypeResolutionTest {

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
     * would leak into the next. Reset the ones these tests touch so each case starts from defaults —
     * and again afterwards, so whichever test happens to run last cannot leak {@code emit=neo4j} or a
     * deliberately bad {@code --precision} into another test class that drives the same CLI.
     */
    @BeforeEach
    @AfterEach
    void resetStaticOptions() throws Exception {
        set("emit", "json");
        set("analysisLevel", 1);
        set("precision", "rta");
        set("output", null);
        set("input", null);
        set("targetFiles", null);
        set("sourceAnalysis", null);
        CodeAnalyzer.projectRootPom = null;
        CodeAnalyzer.artifactText = true;
        CodeAnalyzer.artifactTextMaxBytes = 262144;
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

    /** #269's reproduction: three services, each vendoring {@code com.shared.RunStatsDataBean}. */
    private static Path vendoredServices(Path root) throws IOException {
        for (String svc : new String[] {"svc-a", "svc-b", "svc-c"}) {
            Path pkg = root.resolve(svc).resolve("src/main/java/com/shared");
            Files.createDirectories(pkg);
            Files.writeString(pkg.resolve("RunStatsDataBean.java"),
                    "package com.shared;\npublic class RunStatsDataBean {\n"
                            + "  public String origin() { return \"" + svc + "\"; }\n}\n",
                    StandardCharsets.UTF_8);
        }
        for (String svc : new String[] {"svc-a", "svc-b"}) {
            String caller = "Caller" + Character.toUpperCase(svc.charAt(4));
            Files.writeString(root.resolve(svc).resolve("src/main/java/com/shared/" + caller + ".java"),
                    "package com.shared;\npublic class " + caller + " {\n"
                            + "  public String go() { return new RunStatsDataBean().origin(); }\n}\n",
                    StandardCharsets.UTF_8);
        }
        return root;
    }

    private static java.util.List<JsonObject> callEdges(JsonElement node) {
        java.util.List<JsonObject> out = new java.util.ArrayList<>();
        if (node.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : node.getAsJsonObject().entrySet()) {
                if (e.getKey().equals("call_graph") && e.getValue().isJsonArray()) {
                    e.getValue().getAsJsonArray().forEach(x -> out.add(x.getAsJsonObject()));
                } else {
                    out.addAll(callEdges(e.getValue()));
                }
            }
        } else if (node.isJsonArray()) {
            node.getAsJsonArray().forEach(x -> out.addAll(callEdges(x)));
        }
        return out;
    }

    /** A caller resolves a vendored type against its own copy, not the last source root's (#269). */
    @Test
    void vendoredCopiesResolveToTheCallersOwnCopy(@TempDir Path tmp) throws Exception {
        Path in = vendoredServices(tmp.resolve("app"));
        Path out = tmp.resolve("out");
        assertEquals(0, run("-i", in.toString(), "-o", out.toString(), "-a", "2", "--no-rta",
                "--app-name", "dt"));
        java.util.List<JsonObject> edges = callEdges(JsonParser.parseString(
                Files.readString(out.resolve("analysis.json"), StandardCharsets.UTF_8)));
        for (String[] want : new String[][] {{"CallerA", "svc-a/"}, {"CallerB", "svc-b/"}}) {
            java.util.List<String> targets = edges.stream()
                    .filter(e -> e.get("src").getAsString().contains(want[0]))
                    .map(e -> e.get("dst").getAsString())
                    .filter(d -> d.contains("origin"))
                    .collect(Collectors.toList());
            assertEquals(1, targets.size(), want[0] + " -> origin(): " + targets);
            assertTrue(targets.get(0).contains(want[1]), want[0] + " bound to " + targets.get(0));
        }
    }

    /** One name declared in several source roots is reported, and fails the run under --strict (#269). */
    @Test
    void duplicateQualifiedNamesFailUnderStrict(@TempDir Path tmp) throws Exception {
        Path in = vendoredServices(tmp.resolve("app"));
        assertEquals(0, run("-i", in.toString(), "-o", tmp.resolve("lenient").toString(),
                "--app-name", "dt"));
        assertNotEquals(0, run("-i", in.toString(), "-o", tmp.resolve("strict").toString(),
                "--app-name", "dt", "--strict"));
        Path single = project(tmp.resolve("single"));
        assertEquals(0, run("-i", single.toString(), "-o", tmp.resolve("single-out").toString(),
                "--app-name", "widgets", "--strict"));
    }

}
