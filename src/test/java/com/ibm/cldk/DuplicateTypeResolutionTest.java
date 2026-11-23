package com.ibm.cldk;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /** Writes {@code text} at {@code root/svc/src/main/java/com/shared/name.java}. */
    private static void shared(Path root, String svc, String name, String text) throws IOException {
        Path pkg = root.resolve(svc).resolve("src/main/java/com/shared");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve(name + ".java"), "package com.shared;\n" + text,
                StandardCharsets.UTF_8);
    }

    private static java.util.List<String> targets(java.util.List<JsonObject> edges, String caller,
            String callee) {
        return edges.stream()
                .filter(e -> e.get("src").getAsString().contains(caller))
                .map(e -> e.get("dst").getAsString())
                .filter(d -> d.contains(callee))
                .collect(Collectors.toList());
    }

    private static java.util.List<JsonObject> level2Edges(Path in, Path out) throws IOException {
        assertEquals(0, run("-i", in.toString(), "-o", out.toString(), "-a", "2", "--no-rta",
                "--app-name", "dt"));
        return callEdges(JsonParser.parseString(
                Files.readString(out.resolve("analysis.json"), StandardCharsets.UTF_8)));
    }

    /**
     * Copies whose signatures diverge (#269): each caller's call resolves only against its own
     * copy, so the edge exists only if L1 resolved the call through the caller's own source root.
     * Level 2's nearest-copy choice cannot recover a call L1 left unresolved.
     */
    @Test
    void divergedCopiesResolveThroughTheCallersOwnRoot(@TempDir Path tmp) throws Exception {
        Path in = tmp.resolve("app");
        shared(in, "svc-a", "RunStatsDataBean",
                "public class RunStatsDataBean {\n  public String origin(int n) { return \"a\"; }\n}\n");
        shared(in, "svc-b", "RunStatsDataBean",
                "public class RunStatsDataBean {\n  public String origin(String s) { return s; }\n}\n");
        shared(in, "svc-c", "RunStatsDataBean",
                "public class RunStatsDataBean {\n  public String origin() { return \"c\"; }\n}\n");
        shared(in, "svc-a", "CallerA",
                "public class CallerA {\n  public String go() { return new RunStatsDataBean().origin(1); }\n}\n");
        shared(in, "svc-b", "CallerB",
                "public class CallerB {\n  public String go() { return new RunStatsDataBean().origin(\"x\"); }\n}\n");
        java.util.List<JsonObject> edges = level2Edges(in, tmp.resolve("out"));
        java.util.List<String> a = targets(edges, "CallerA", "origin");
        java.util.List<String> b = targets(edges, "CallerB", "origin");
        assertEquals(1, a.size(), "CallerA -> origin(int): " + a);
        assertTrue(a.get(0).contains("svc-a/") && a.get(0).contains("origin(int)"), a.get(0));
        assertEquals(1, b.size(), "CallerB -> origin(String): " + b);
        assertTrue(b.get(0).contains("svc-b/") && b.get(0).contains("origin(java.lang.String)"), b.get(0));
    }

    /**
     * A caller whose own root has no copy falls back to the first other root in path order, wherever
     * the application is checked out (#269). JavaParser lists source roots in hash order of their
     * absolute paths, so without sorting, the fallback copy changed with the checkout location. The
     * copies' signatures diverge, so only svc-b's copy can resolve the call.
     */
    @Test
    void fallbackCopyIsTheFirstRootInPathOrderWhereverCheckedOut(@TempDir Path tmp) throws Exception {
        for (int i = 0; i < 8; i++) {
            Path in = tmp.resolve("checkout-" + i).resolve("app");
            shared(in, "svc-b", "RunStatsDataBean",
                    "public class RunStatsDataBean {\n  public String origin(int n) { return \"b\"; }\n}\n");
            shared(in, "svc-c", "RunStatsDataBean",
                    "public class RunStatsDataBean {\n  public String origin(String s) { return s; }\n}\n");
            shared(in, "svc-d", "CallerD",
                    "public class CallerD {\n  public String go() { return new RunStatsDataBean().origin(1); }\n}\n");
            java.util.List<String> d = targets(level2Edges(in, tmp.resolve("out-" + i)), "CallerD", "origin");
            assertEquals(1, d.size(), "checkout-" + i + ": CallerD -> origin(int): " + d);
            assertTrue(d.get(0).contains("svc-b/"), d.get(0));
        }
    }

    private static void write(Path root, String rel, String text) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    /**
     * With no copy in the caller's own root, both levels fall back to the copy nearest it (#269):
     * svc-a/vendor shares {@code svc-a} with the caller's root, lib shares nothing. L1 once fell
     * back in path order (lib first) while L2 chose by nearness, so the edges contradicted each
     * other: CallerA called svc-a/vendor's {@code get()} and, through lib's copy, {@code A.tag()}.
     */
    @Test
    void bothLevelsFallBackToTheNearestCopy(@TempDir Path tmp) throws Exception {
        Path in = tmp.resolve("app");
        write(in, "lib/src/main/java/com/shared/Bean.java",
                "package com.shared;\npublic class Bean {\n  public A get() { return new A(); }\n}\n");
        write(in, "lib/src/main/java/com/shared/A.java",
                "package com.shared;\npublic class A {\n  public String tag() { return \"a\"; }\n}\n");
        write(in, "svc-a/vendor/com/shared/Bean.java",
                "package com.shared;\npublic class Bean {\n  public B get() { return new B(); }\n}\n");
        write(in, "svc-a/vendor/com/shared/B.java",
                "package com.shared;\npublic class B {\n  public String tag() { return \"b\"; }\n}\n");
        write(in, "svc-a/src/main/java/com/app/CallerA.java",
                "package com.app;\nimport com.shared.Bean;\npublic class CallerA {\n"
                        + "  public String go() { return new Bean().get().tag(); }\n}\n");
        java.util.List<JsonObject> edges = level2Edges(in, tmp.resolve("out"));
        java.util.List<String> get = targets(edges, "CallerA", "get()");
        assertEquals(1, get.size(), "CallerA -> get(): " + get);
        assertTrue(get.get(0).contains("svc-a/vendor/"), get.get(0));
        assertEquals(1, targets(edges, "CallerA", "/B/tag()").size(), "CallerA -> B.tag(): " + edges);
        assertEquals(java.util.List.of(), targets(edges, "CallerA", "/A/tag()"));
    }

    /**
     * Known limitation, pinned (#269): only the caller's reference picks its own copy. What a found
     * copy's declarations refer to (here, the return type of {@code Bean.helper()}) resolves in the
     * application-wide order, so CallerB reaches svc-a's {@code Helper}, which lacks
     * {@code tag(String)}, and that call stays unresolved. Fixing it needs a parse cache per source
     * root; if that lands, this test should flip to expect the svc-b edge.
     */
    @Test
    void knownLimitationMembersOfAFoundCopyResolveApplicationWide(@TempDir Path tmp) throws Exception {
        Path in = tmp.resolve("app");
        for (String svc : new String[] {"svc-a", "svc-b"}) {
            shared(in, svc, "Bean",
                    "public class Bean {\n  public Helper helper() { return new Helper(); }\n}\n");
        }
        shared(in, "svc-a", "Helper",
                "public class Helper {\n  public String tag(int n) { return \"a\"; }\n}\n");
        shared(in, "svc-b", "Helper",
                "public class Helper {\n  public String tag(String s) { return s; }\n}\n");
        shared(in, "svc-b", "CallerB",
                "public class CallerB {\n  public String go() { return new Bean().helper().tag(\"x\"); }\n}\n");
        java.util.List<JsonObject> edges = level2Edges(in, tmp.resolve("out"));
        java.util.List<String> helper = targets(edges, "CallerB", "helper()");
        assertEquals(1, helper.size(), "CallerB -> helper(): " + helper);
        assertTrue(helper.get(0).contains("svc-b/"), helper.get(0));
        assertEquals(java.util.List.of(), targets(edges, "CallerB", "tag("));
    }

    private static String stderrOf(String... args) throws Exception {
        java.io.PrintStream original = System.err;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(captured, true, "UTF-8"));
        try {
            assertEquals(0, run(args), String.join(" ", args));
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    /**
     * A duplicated name is warned about on stderr on every run, verbose or not, and is not a
     * degradation (#269): the output keeps every copy, so --strict passes.
     */
    @Test
    void duplicateQualifiedNamesWarnOnStderrAndPassStrict(@TempDir Path tmp) throws Exception {
        Path in = vendoredServices(tmp.resolve("app"));
        String err = stderrOf("-i", in.toString(), "-o", tmp.resolve("strict").toString(),
                "--app-name", "dt", "--strict");
        assertTrue(err.contains("warning: 1 qualified type name(s) are declared in more than one file"),
                err);
        assertTrue(err.contains("com.shared.RunStatsDataBean in svc-a/src/main/java/com/shared/"
                + "RunStatsDataBean.java, svc-b/"), err);
        Path single = project(tmp.resolve("single"));
        String clean = stderrOf("-i", single.toString(), "-o", tmp.resolve("single-out").toString(),
                "--app-name", "widgets", "--strict");
        assertTrue(!clean.contains("warning:"), clean);
    }

}
