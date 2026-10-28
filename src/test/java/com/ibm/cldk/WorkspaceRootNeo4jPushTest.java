package com.ibm.cldk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.cldk.neo4j.BoltConfig;
import com.ibm.cldk.neo4j.BoltWriter;
import com.ibm.cldk.neo4j.GraphRows;
import com.ibm.cldk.schema.CanId;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.Values;
import org.testcontainers.containers.Neo4jContainer;

/**
 * {@code --workspace-root} against a live Neo4j: the incremental push's wipe and orphan prune are
 * scoped by the app's {@code can://<app>/} prefix and each module's own id, so repo-relative module
 * ids must still be pruned when their file vanishes, and must not reach another application's graph.
 *
 * <p>Needs a container runtime, so it is opt-in like {@code Neo4jBoltWriterTest}: it runs only when
 * {@code RUN_CONTAINER_TESTS} is set.
 */
@EnabledIfEnvironmentVariable(named = "RUN_CONTAINER_TESTS", matches = ".+")
class WorkspaceRootNeo4jPushTest {

    private static final String PASSWORD = "testpassword123";

    @SuppressWarnings("resource")
    private static final Neo4jContainer<?> CONTAINER =
            new Neo4jContainer<>("neo4j:5").withAdminPassword(PASSWORD);

    private static Driver driver;

    @BeforeAll
    static void startup() {
        CONTAINER.start();
        driver = GraphDatabase.driver(CONTAINER.getBoltUrl(), AuthTokens.basic("neo4j", PASSWORD));
    }

    @AfterAll
    static void teardown() {
        if (driver != null) {
            driver.close();
        }
        CONTAINER.stop();
    }

    /** The CLI's pre-existing options are static; reset the ones this test sets, before and after. */
    @BeforeEach
    @AfterEach
    void resetStaticOptions() throws Exception {
        set("emit", "json");
        set("analysisLevel", 1);
        set("output", null);
        set("input", null);
        set("appName", null);
        set("neo4jUri", null);
        set("neo4jUser", null);
        set("neo4jPassword", null);
        CodeAnalyzer.projectRootPom = null;
    }

    private static void set(String field, Object value) throws Exception {
        Field f = CodeAnalyzer.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(null, value);
    }

    private static void writeClass(Path srcRoot, String name) throws IOException {
        Path pkg = Files.createDirectories(srcRoot.resolve("src/main/java/com/example"));
        Files.writeString(pkg.resolve(name + ".java"),
                "package com.example;\npublic class " + name + " {\n  public int m() { return 1; }\n}\n",
                StandardCharsets.UTF_8);
    }

    private static int push(Path in, Path repo, String app, Path out) {
        return new picocli.CommandLine(new CodeAnalyzer()).execute(
                "-i", in.toString(), "-o", out.toString(), "--emit", "neo4j", "--no-build",
                "--workspace-root", repo.toString(), "--app-name", app,
                "--neo4j-uri", CONTAINER.getBoltUrl(), "--neo4j-user", "neo4j", "--neo4j-password", PASSWORD);
    }

    /** As {@link #push}, without {@code --workspace-root}: keys are relative to {@code in}. */
    private static int pushUnrooted(Path in, String app, Path out) {
        return new picocli.CommandLine(new CodeAnalyzer()).execute(
                "-i", in.toString(), "-o", out.toString(), "--emit", "neo4j", "--no-build",
                "--app-name", app,
                "--neo4j-uri", CONTAINER.getBoltUrl(), "--neo4j-user", "neo4j", "--neo4j-password", PASSWORD);
    }

    private static long count(String cypher, Object... params) {
        try (Session s = driver.session()) {
            return s.run(cypher, Values.parameters(params)).single().get(0).asLong();
        }
    }

    private static void run(String cypher, Object... params) {
        try (Session s = driver.session()) {
            s.run(cypher, Values.parameters(params)).consume();
        }
    }

    private static String artifactPrefix(String app) {
        return CanId.applicationId(app) + "/artifact/";
    }

    /** A project with one dependency manifest and one config file that mints a key. */
    private static void writeArtifacts(Path project, String... deps) throws IOException {
        StringBuilder pom = new StringBuilder(
                "<project><modelVersion>4.0.0</modelVersion><groupId>com.example</groupId>"
                        + "<artifactId>demo</artifactId><version>1</version><dependencies>");
        for (String dep : deps) {
            String[] ga = dep.split(":");
            pom.append("<dependency><groupId>").append(ga[0]).append("</groupId><artifactId>")
                    .append(ga[1]).append("</artifactId><version>1.0</version></dependency>");
        }
        pom.append("</dependencies></project>\n");
        Files.writeString(project.resolve("pom.xml"), pom.toString(), StandardCharsets.UTF_8);
    }

    private static void writeConfig(Path project, String... keys) throws IOException {
        Path res = Files.createDirectories(project.resolve("src/main/resources"));
        StringBuilder props = new StringBuilder();
        for (String k : keys) {
            props.append(k).append("=v\n");
        }
        Files.writeString(res.resolve("application.properties"), props.toString(), StandardCharsets.UTF_8);
    }

    @Test
    void togglingWorkspaceRootLeavesNoOldKeyedArtifacts(@TempDir Path tmp) throws IOException {
        Path repo = tmp.resolve("repo");
        Path app = repo.resolve("services/app");
        writeClass(app, "Main");
        writeArtifacts(app, "org.example:lib");
        writeConfig(app, "server.port");

        assertEquals(0, pushUnrooted(app, "toggle", tmp.resolve("o1")));
        String oldPom = CanId.artifactId("toggle", "pom.xml");
        assertEquals(1, count("MATCH (a:Artifact {id: $id}) RETURN count(a)", "id", oldPom),
                "precondition: the unrooted push keys the artifact on its input-relative path");
        assertTrue(count("MATCH (k:ConfigKey) WHERE k.id STARTS WITH $p RETURN count(k)",
                "p", oldPom.replace("pom.xml", "src/main/resources/application.properties")) > 0,
                "precondition: the config file minted a key");

        assertEquals(0, push(app, repo, "toggle", tmp.resolve("o2")));

        String rooted = artifactPrefix("toggle") + "services/app/";
        assertEquals(0, count("MATCH (a:Artifact) WHERE a.id STARTS WITH $p AND NOT a.id STARTS WITH $r "
                + "RETURN count(a)", "p", artifactPrefix("toggle"), "r", rooted),
                "no artifact keyed on the old input-relative path survives");
        assertEquals(0, count("MATCH (k:ConfigKey) WHERE k.id STARTS WITH $p AND NOT k.id STARTS WITH $r "
                + "RETURN count(k)", "p", artifactPrefix("toggle"), "r", rooted),
                "no config key keyed on the old input-relative path survives");
        long artifacts = count("MATCH (a:Artifact) WHERE a.id STARTS WITH $r RETURN count(a)", "r", rooted);
        assertTrue(artifacts > 0, "the rooted push wrote its artifacts");
        assertEquals(artifacts, count("MATCH (:JApplication {id: $app})-[r:HAS_ARTIFACT]->() RETURN count(r)",
                "app", CanId.applicationId("toggle")), "one HAS_ARTIFACT per artifact the run emitted");
        assertEquals(1, count("MATCH (a:Artifact)-[r:DECLARES_DEPENDENCY]->(:Package {id: $pkg}) "
                + "WHERE a.id STARTS WITH $p RETURN count(r)",
                "p", artifactPrefix("toggle"), "pkg", CanId.purlMaven("org.example", "lib")),
                "the dependency is declared once, by the rooted manifest");
    }

    @Test
    void aChangedManifestDropsItsRemovedDependencyAndConfigKey(@TempDir Path tmp) throws IOException {
        Path app = tmp.resolve("app");
        writeClass(app, "Main");
        writeArtifacts(app, "org.example:kept", "org.example:dropped");
        writeConfig(app, "kept.key", "dropped.key");
        assertEquals(0, pushUnrooted(app, "edit", tmp.resolve("o1")));

        String pom = CanId.artifactId("edit", "pom.xml");
        String props = CanId.artifactId("edit", "src/main/resources/application.properties");
        assertEquals(1, count("MATCH (:Artifact {id: $a})-[r:DECLARES_DEPENDENCY]->(:Package {id: $pkg}) "
                + "RETURN count(r)", "a", pom, "pkg", CanId.purlMaven("org.example", "dropped")),
                "precondition: the dependency is declared");

        writeArtifacts(app, "org.example:kept");
        writeConfig(app, "kept.key");
        assertEquals(0, pushUnrooted(app, "edit", tmp.resolve("o2")));

        assertEquals(0, count("MATCH (:Artifact {id: $a})-[r:DECLARES_DEPENDENCY]->(:Package {id: $pkg}) "
                + "RETURN count(r)", "a", pom, "pkg", CanId.purlMaven("org.example", "dropped")),
                "the removed dependency's edge is gone");
        assertEquals(1, count("MATCH (:Artifact {id: $a})-[r:DECLARES_DEPENDENCY]->(:Package {id: $pkg}) "
                + "RETURN count(r)", "a", pom, "pkg", CanId.purlMaven("org.example", "kept")),
                "the kept dependency's edge stays");
        assertEquals(0, count("MATCH (k:ConfigKey {id: $id}) RETURN count(k)",
                "id", CanId.configKeyId(props, "dropped.key")), "the removed config key is gone");
        assertEquals(1, count("MATCH (k:ConfigKey {id: $id}) RETURN count(k)",
                "id", CanId.configKeyId(props, "kept.key")), "the kept config key stays");
    }

    @Test
    void aSiblingAnalyzersArtifactsAndEdgesSurviveAJavaPush(@TempDir Path tmp) throws IOException {
        Path app = tmp.resolve("app");
        writeClass(app, "Main");
        writeArtifacts(app, "org.example:lib");
        assertEquals(0, pushUnrooted(app, "poly", tmp.resolve("o1")));

        // Seed what codeanalyzer-python would have written: a claim on the shared pom.xml (with a
        // pypi dependency edge), plus an artifact and config key Java never emits.
        String pom = CanId.artifactId("poly", "pom.xml");
        String pyOnly = CanId.artifactId("poly", "pyproject.toml");
        String pyKey = CanId.configKeyId(pyOnly, "tool.name");
        run("MATCH (a:Artifact {id: $pom}) SET a:PyCanNode "
                + "MERGE (app:PyApplication {id: 'can://poly'}) MERGE (app)-[:HAS_ARTIFACT]->(a) "
                + "MERGE (p:Package {id: 'pkg:pypi/requests'}) MERGE (a)-[:DECLARES_DEPENDENCY {_k: 'main'}]->(p) "
                + "MERGE (o:Artifact:PyCanNode {id: $py}) MERGE (app)-[:HAS_ARTIFACT]->(o) "
                + "MERGE (k:ConfigKey:PyCanNode {id: $key}) MERGE (o)-[:DEFINES_CONFIG]->(k) "
                + "MERGE (o)-[:DECLARES_DEPENDENCY {_k: 'main'}]->(p)",
                "pom", pom, "py", pyOnly, "key", pyKey);

        // Java now stops emitting pom.xml: it releases its claim but must not delete a node Python holds.
        Files.delete(app.resolve("pom.xml"));
        assertEquals(0, pushUnrooted(app, "poly", tmp.resolve("o2")));

        assertEquals(1, count("MATCH (a:Artifact:PyCanNode {id: $id}) WHERE NOT a:JCanNode RETURN count(a)",
                "id", pom), "the shared artifact survives with Python's claim and without Java's");
        assertEquals(0, count("MATCH (:JApplication)-[r:HAS_ARTIFACT]->(:Artifact {id: $id}) RETURN count(r)",
                "id", pom), "Java's HAS_ARTIFACT to the released artifact is gone");
        assertEquals(0, count("MATCH (:Artifact {id: $id})-[r:DECLARES_DEPENDENCY]->(p:Package) "
                + "WHERE p.id STARTS WITH 'pkg:maven/' RETURN count(r)", "id", pom),
                "Java's maven dependency edge from the released artifact is gone");
        assertEquals(1, count("MATCH (:Artifact {id: $id})-[r:DECLARES_DEPENDENCY]->(:Package {id: 'pkg:pypi/requests'}) "
                + "RETURN count(r)", "id", pom), "Python's dependency edge on the shared artifact stays");
        assertEquals(1, count("MATCH (:Artifact {id: $id})-[:DEFINES_CONFIG]->(:ConfigKey {id: $key}) "
                + "RETURN count(*)", "id", pyOnly, "key", pyKey),
                "an artifact and config key only Python wrote are untouched");
    }

    @Test
    void anAppWhoseNameExtendsAnothersIsNotReconciledByIt(@TempDir Path tmp) throws IOException {
        Path a = tmp.resolve("a");
        Path b = tmp.resolve("b");
        for (Path p : new Path[] {a, b}) {
            writeClass(p, "Main");
            writeArtifacts(p, "org.example:lib");
        }
        assertEquals(0, pushUnrooted(b, "apps", tmp.resolve("o1")));
        long apps = count("MATCH (n:Artifact) WHERE n.id STARTS WITH $p RETURN count(n)", "p", artifactPrefix("apps"));
        assertTrue(apps > 0, "precondition");

        assertEquals(0, pushUnrooted(a, "app", tmp.resolve("o2")));

        assertEquals(apps, count("MATCH (n:Artifact:JCanNode) WHERE n.id STARTS WITH $p RETURN count(n)",
                "p", artifactPrefix("apps")), "can://app's reconciliation never reaches can://apps");
        assertEquals(1, count("MATCH (:Artifact {id: $id})-[r:DECLARES_DEPENDENCY]->() RETURN count(r)",
                "id", CanId.artifactId("apps", "pom.xml")), "can://apps keeps its dependency edge");
    }

    @Test
    void aConfigKeyAnotherAnalyzerClaimsSurvivesJavaDroppingIt(@TempDir Path tmp) throws IOException {
        Path app = tmp.resolve("app");
        writeClass(app, "Main");
        writeConfig(app, "shared.key", "other");
        assertEquals(0, pushUnrooted(app, "ckshare", tmp.resolve("o1")));

        String props = CanId.artifactId("ckshare", "src/main/resources/application.properties");
        String key = CanId.configKeyId(props, "shared.key");
        run("MATCH (k:ConfigKey {id: $key}) SET k:PyCanNode "
                + "MERGE (b:PyBodyNode {id: 'can://ckshare/python/x.py/f@1:1'}) MERGE (b)-[:PY_USES_CONFIG]->(k)",
                "key", key);

        writeConfig(app, "other");
        assertEquals(0, pushUnrooted(app, "ckshare", tmp.resolve("o2")));

        assertEquals(1, count("MATCH (k:ConfigKey:PyCanNode {id: $id}) WHERE NOT k:JCanNode RETURN count(k)",
                "id", key), "the key survives with Python's claim and without Java's");
        assertEquals(1, count("MATCH (:Artifact {id: $a})-[:DEFINES_CONFIG]->(:ConfigKey {id: $k}) RETURN count(*)",
                "a", props, "k", key), "its DEFINES_CONFIG stays");
        assertEquals(1, count("MATCH ()-[r:PY_USES_CONFIG]->(:ConfigKey {id: $k}) RETURN count(r)", "k", key),
                "Python's read of it stays");
    }

    @Test
    void anArtifactAnUnmarkedSiblingInventoriesSurvivesJavaDroppingIt(@TempDir Path tmp) throws IOException {
        Path app = tmp.resolve("app");
        writeClass(app, "Main");
        writeArtifacts(app, "org.example:lib");
        assertEquals(0, pushUnrooted(app, "oldsib", tmp.resolve("o1")));

        // A pre-1.5.0 codeanalyzer-python: inventories the file via HAS_ARTIFACT, puts no marker on it.
        String pom = CanId.artifactId("oldsib", "pom.xml");
        run("MATCH (a:Artifact {id: $pom}) MERGE (p:PyApplication {id: 'can://oldsib'}) "
                + "MERGE (p)-[:HAS_ARTIFACT]->(a)", "pom", pom);

        Files.delete(app.resolve("pom.xml"));
        assertEquals(0, pushUnrooted(app, "oldsib", tmp.resolve("o2")));

        assertEquals(1, count("MATCH (:PyApplication)-[:HAS_ARTIFACT]->(a:Artifact {id: $id}) "
                + "WHERE NOT a:JCanNode RETURN count(a)", "id", pom),
                "the artifact and the sibling's HAS_ARTIFACT survive; only Java's claim is released");
    }

    @Test
    void codeEdgesIntoConfigFollowAConfigOnlyChange(@TempDir Path tmp) throws IOException {
        Path app = tmp.resolve("app");
        Path pkg = Files.createDirectories(app.resolve("src/main/java/com/example"));
        Files.writeString(pkg.resolve("Main.java"), "package com.example;\npublic class Main {\n"
                + "  public String port() { return System.getenv(\"PORT\"); }\n}\n", StandardCharsets.UTF_8);
        Files.writeString(app.resolve(".env"), "OTHER=1\n", StandardCharsets.UTF_8);
        assertEquals(0, pushUnrooted(app, "cfguse", tmp.resolve("o1")));
        String env = CanId.artifactId("cfguse", ".env");
        assertEquals(0, count("MATCH ()-[r:J_USES_CONFIG]->(k) WHERE k.id STARTS WITH $p RETURN count(r)",
                "p", env), "precondition: PORT is undeclared");
        assertEquals(1, count("MATCH (:JApplication {id: 'can://cfguse'})-[r:J_READS_CONFIG_UNRESOLVED {key: 'PORT'}]->() "
                + "RETURN count(r)"), "precondition: the read is unresolved");

        // Only the config changes; Main.java's content_hash is identical.
        Files.writeString(app.resolve(".env"), "PORT=8080\n", StandardCharsets.UTF_8);
        assertEquals(0, pushUnrooted(app, "cfguse", tmp.resolve("o2")));

        assertEquals(1, count("MATCH ()-[r:J_USES_CONFIG]->(:ConfigKey {id: $k}) RETURN count(r)",
                "k", CanId.configKeyId(env, "PORT")), "the newly declared key gains its read");
        assertEquals(0, count("MATCH (:JApplication {id: 'can://cfguse'})-[r:J_READS_CONFIG_UNRESOLVED {key: 'PORT'}]->() "
                + "RETURN count(r)"), "the stale unresolved read is gone");

        Files.writeString(app.resolve(".env"), "OTHER=1\n", StandardCharsets.UTF_8);
        assertEquals(0, pushUnrooted(app, "cfguse", tmp.resolve("o3")));
        assertEquals(0, count("MATCH ()-[r:J_USES_CONFIG]->(k) WHERE k.id STARTS WITH $p RETURN count(r)",
                "p", env), "removing the key drops its read");
    }

    @Test
    void aLockEdgeFollowsAManifestChangeWithAnUnchangedLockfile(@TempDir Path tmp) throws IOException {
        Path app = tmp.resolve("app");
        writeClass(app, "Main");
        Files.writeString(app.resolve("build.gradle"),
                "dependencies {\n  implementation 'org.example:a:1.0'\n  implementation 'org.example:b:1.0'\n}\n",
                StandardCharsets.UTF_8);
        Files.writeString(app.resolve("gradle.lockfile"),
                "org.example:a:1.0=runtimeClasspath\nempty=\n", StandardCharsets.UTF_8);
        assertEquals(0, pushUnrooted(app, "locks", tmp.resolve("o1")));
        String lock = CanId.artifactId("locks", "gradle.lockfile");
        assertEquals(1, count("MATCH (:Artifact {id: $l})-[r:LOCKS]->(:Package {id: $p}) RETURN count(r)",
                "l", lock, "p", CanId.purlMaven("org.example", "a")), "precondition: a is locked");

        // Drop `a` from the manifest only. The lockfile is byte-identical but `a` is now lock-only.
        Files.writeString(app.resolve("build.gradle"),
                "dependencies {\n  implementation 'org.example:b:1.0'\n}\n", StandardCharsets.UTF_8);
        assertEquals(0, pushUnrooted(app, "locks", tmp.resolve("o2")));

        assertEquals(0, count("MATCH (:Artifact {id: $m})-[r:DECLARES_DEPENDENCY]->(:Package {id: $p}) RETURN count(r)",
                "m", CanId.artifactId("locks", "build.gradle"), "p", CanId.purlMaven("org.example", "a")),
                "the manifest no longer declares a");
        assertEquals(1, count("MATCH (:Artifact {id: $l})-[r:LOCKS]->(:Package {id: $p}) RETURN count(r)",
                "l", lock, "p", CanId.purlMaven("org.example", "a")), "the lock still pins a, once");
    }

    @Test
    void anUnchangedRepushIsIdempotent(@TempDir Path tmp) throws IOException {
        Path app = tmp.resolve("app");
        writeClass(app, "Main");
        writeArtifacts(app, "org.example:lib");
        writeConfig(app, "k");
        assertEquals(0, pushUnrooted(app, "idem", tmp.resolve("o1")));
        String snapshot = "MATCH (n) WHERE n.id STARTS WITH 'can://idem' OPTIONAL MATCH (n)-[r]-() "
                + "RETURN count(DISTINCT n) * 1000000 + count(DISTINCT r)";
        long before = count(snapshot);

        assertEquals(0, pushUnrooted(app, "idem", tmp.resolve("o2")));

        assertEquals(before, count(snapshot), "a re-push with no change leaves node and edge counts unchanged");
        assertEquals(1, count("MATCH (:Artifact {id: $a})-[r:DECLARES_DEPENDENCY]->() RETURN count(r)",
                "a", CanId.artifactId("idem", "pom.xml")), "no keyed edge is doubled");
    }

    @Test
    void preRekeyArtifactsAreReleasedToo(@TempDir Path tmp) throws IOException {
        Path app = tmp.resolve("app");
        writeClass(app, "Main");
        writeArtifacts(app, "org.example:lib");
        // What a 3.0.x-3.1.0 push left behind: the artifact keyed `can://artifact/<app>/...`.
        String legacy = "can://artifact/legacy/pom.xml";
        run("MERGE (a:Artifact:JCanNode {id: $id}) MERGE (k:ConfigKey:JCanNode {id: $key}) "
                + "MERGE (a)-[:DEFINES_CONFIG]->(k) "
                + "MERGE (p:Package {id: 'pkg:maven/org.example/lib'}) MERGE (a)-[:DECLARES_DEPENDENCY {_k: 'main'}]->(p)",
                "id", legacy, "key", legacy + "@key/x");

        assertEquals(0, pushUnrooted(app, "legacy", tmp.resolve("o1")));

        assertEquals(0, count("MATCH (n) WHERE n.id STARTS WITH 'can://artifact/legacy/' RETURN count(n)"),
                "the pre-re-key artifact and its config key are gone");
        assertEquals(1, count("MATCH (n:Artifact {id: $id}) RETURN count(n)",
                "id", CanId.artifactId("legacy", "pom.xml")), "the current artifact is written");
    }

    @Test
    void aV1PushLeavesV2ArtifactsAlone(@TempDir Path tmp) throws IOException {
        Path app = tmp.resolve("app");
        writeClass(app, "Main");
        writeArtifacts(app, "org.example:lib");
        assertEquals(0, pushUnrooted(app, "gen", tmp.resolve("o1")));
        long artifacts = count("MATCH (n:Artifact) WHERE n.id STARTS WITH $p RETURN count(n)", "p", artifactPrefix("gen"));
        assertTrue(artifacts > 0, "precondition");

        // A v1 full run's root is keyed on its bare name and it emits no artifacts. Driven through
        // the writer directly: the v1 CLI path needs compiled classes, which --no-build cannot give.
        GraphRows.NodeRow v1Root = new GraphRows.NodeRow(List.of("JApplication"), "name", "gen",
                new java.util.HashMap<>(Map.of("name", "gen")));
        new BoltWriter().write(new GraphRows(List.of(v1Root), List.of()),
                new BoltConfig(CONTAINER.getBoltUrl(), "neo4j", PASSWORD, null), true);

        assertEquals(artifacts, count("MATCH (n:Artifact:JCanNode) WHERE n.id STARTS WITH $p RETURN count(n)",
                "p", artifactPrefix("gen")), "v1 emits no artifacts, so it must not release v2's");
    }

    @Test
    void reconcilingOneAppLeavesAnotherAppsArtifactsAlone(@TempDir Path tmp) throws IOException {
        Path a = tmp.resolve("a");
        Path b = tmp.resolve("b");
        for (Path p : new Path[] {a, b}) {
            writeClass(p, "Main");
            writeArtifacts(p, "org.example:lib");
            writeConfig(p, "k");
        }
        assertEquals(0, pushUnrooted(a, "appa", tmp.resolve("o1")));
        assertEquals(0, pushUnrooted(b, "appb", tmp.resolve("o2")));
        long bArtifacts = count("MATCH (n) WHERE (n:Artifact OR n:ConfigKey) AND n.id STARTS WITH $p "
                + "RETURN count(n)", "p", artifactPrefix("appb"));
        assertTrue(bArtifacts > 0, "precondition: the other app has artifacts");

        Files.delete(a.resolve("pom.xml"));
        Files.delete(a.resolve("src/main/resources/application.properties"));
        assertEquals(0, pushUnrooted(a, "appa", tmp.resolve("o3")));

        assertEquals(0, count("MATCH (n:Artifact {id: $id}) RETURN count(n)",
                "id", CanId.artifactId("appa", "pom.xml")), "this app's vanished artifact is deleted");
        assertEquals(bArtifacts, count("MATCH (n) WHERE (n:Artifact OR n:ConfigKey) AND n.id STARTS WITH $p "
                + "RETURN count(n)", "p", artifactPrefix("appb")), "the other app's artifacts are unchanged");
    }

    @Test
    void aVanishedRepoRelativeModuleIsPrunedAndOtherAppsAreUntouched(@TempDir Path tmp) throws IOException {
        Path repo = tmp.resolve("repo");
        Path orders = repo.resolve("services/orders");
        Path billing = repo.resolve("services/billing");
        writeClass(orders, "Keep");
        writeClass(orders, "Gone");
        writeClass(billing, "Gone");

        assertEquals(0, push(orders, repo, "orders", tmp.resolve("o1")));
        assertEquals(0, push(billing, repo, "billing", tmp.resolve("o2")));

        String key = "services/orders/src/main/java/com/example/";
        String gone = CanId.moduleId(CanId.applicationId("orders"), key + "Gone.java");
        String keep = CanId.moduleId(CanId.applicationId("orders"), key + "Keep.java");
        String billingGone = CanId.moduleId(CanId.applicationId("billing"),
                "services/billing/src/main/java/com/example/Gone.java");
        assertEquals(1, count("MATCH (m:JModule {id: $id}) RETURN count(m)", "id", gone),
                "precondition: the module is pushed under its repo-relative id");
        long billingNodes = count("MATCH (n) WHERE n.id STARTS WITH $p RETURN count(n)",
                "p", CanId.applicationId("billing") + "/");
        assertTrue(billingNodes > 0, "precondition: the other app is in the same database");

        Files.delete(orders.resolve("src/main/java/com/example/Gone.java"));
        assertEquals(0, push(orders, repo, "orders", tmp.resolve("o3")));

        assertEquals(0, count("MATCH (n) WHERE n.id = $id OR n.id STARTS WITH $p RETURN count(n)",
                "id", gone, "p", gone + "/"), "the vanished module and its declarations are pruned");
        assertEquals(1, count("MATCH (m:JModule {id: $id}) RETURN count(m)", "id", keep),
                "the surviving module stays");
        assertEquals(1, count("MATCH (m:JModule {id: $id}) RETURN count(m)", "id", billingGone),
                "a same-named file in another app is a different node and is not touched");
        assertEquals(billingNodes, count("MATCH (n) WHERE n.id STARTS WITH $p RETURN count(n)",
                "p", CanId.applicationId("billing") + "/"), "the other app's graph is unchanged");
    }
}
