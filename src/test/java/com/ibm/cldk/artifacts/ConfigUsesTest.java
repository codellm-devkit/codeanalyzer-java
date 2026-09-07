package com.ibm.cldk.artifacts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.cldk.neo4j.GraphRows;
import com.ibm.cldk.neo4j.GraphRows.EdgeRow;
import com.ibm.cldk.neo4j.GraphRows.NodeRow;
import com.ibm.cldk.neo4j.V2GraphProjector;
import com.ibm.cldk.schema.Analysis;
import com.ibm.cldk.schema.JApplication;
import com.ibm.cldk.schema.JArtifact;
import com.ibm.cldk.schema.JConfigRead;
import com.ibm.cldk.schema.JConfigUseEdge;
import com.ibm.cldk.schema.JModule;
import com.ibm.cldk.schema.V2Emitter;
import com.ibm.cldk.syntactic_analysis.L1Extractor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The config-use literal tier end to end: run the real L1 extraction plus the artifact layer over a
 * fixture, resolve reads with {@link ConfigUses}, then project with {@link V2GraphProjector}.
 *
 * <p>The fixture deliberately carries all four outcomes at once — a resolved annotation read, a
 * resolved call-site read, an undeclared key, and a key that never closes on a literal — because the
 * failure mode this tier guards against is a thin-but-plausible answer, which only shows up when the
 * unresolved buckets are checked alongside the resolved one.
 */
class ConfigUsesTest {

    private static final String APP = "config-use-test";

    @TempDir
    static Path root;

    private static ConfigUses.Result result;
    private static GraphRows rows;
    private static Map<String, JArtifact> artifacts;

    @BeforeAll
    static void analyze() throws Exception {
        Path src = root.resolve("src/main/java/demo");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Config.java"),
                "package demo;\n"
                        + "public class Config {\n"
                        + "    @Value(\"${server.port}\")\n"
                        + "    private int port;\n"
                        + "    @Value(\"${absent.key}\")\n"
                        + "    private String absent;\n"
                        + "    @Value(\"a plain default\")\n"
                        + "    private String notAConfigRead;\n"
                        + "    String home() { return System.getenv(\"HOME\"); }\n"
                        + "    String undeclared() { return System.getenv(\"NOT_DECLARED\"); }\n"
                        + "    String alsoUndeclared() { return System.getenv(\"STILL_NOT_DECLARED\"); }\n"
                        + "    String dynamic(String name) { return System.getenv(name); }\n"
                        + "}\n",
                StandardCharsets.UTF_8);
        Files.writeString(src.resolve("Datasource.java"),
                "package demo;\n"
                        + "@ConfigurationProperties(prefix = \"datasource\")\n"
                        + "public class Datasource {\n"
                        + "    private String url;\n"
                        + "}\n",
                StandardCharsets.UTF_8);

        Files.writeString(root.resolve("application.properties"),
                "server.port=8080\ndatasource.url=jdbc:h2:mem\ndatasource.pool.size=4\nunread.key=x\n",
                StandardCharsets.UTF_8);
        Files.writeString(root.resolve(".env"), "HOME=/home/app\n", StandardCharsets.UTF_8);

        Map<String, JModule> modules =
                L1Extractor.extractAll(root, APP, null, new LinkedHashMap<>(), 1, 3, "ast");

        artifacts = ArtifactDiscovery.discover(root, APP, true, 262144);
        for (JArtifact a : artifacts.values()) {
            if (ConfigKeys.isEligible(a)) {
                a.setConfigKeys(ConfigKeys.extract(
                        a, DependencyView.readFromDisk(root, a.getPath()), true).keys);
            }
        }

        result = ConfigUses.detect(APP, modules, artifacts);

        Analysis analysis = V2Emitter.emit(APP, 1, modules, "test", null, null, null, null,
                artifacts, null);
        JApplication app = analysis.getApplication();
        app.setConfigUses(result.uses);
        app.setConfigReadsUnresolved(result.unresolved);
        rows = V2GraphProjector.project(analysis, APP);
    }

    private static String configKeyId(String dottedKey) {
        return artifacts.values().stream()
                .flatMap(a -> a.getConfigKeys().stream())
                .filter(k -> dottedKey.equals(k.getKey()))
                .map(k -> k.getId())
                .findFirst()
                .orElseThrow(() -> new AssertionError("fixture declares no key " + dottedKey));
    }

    private static List<JConfigUseEdge> usesOf(String dottedKey) {
        String id = configKeyId(dottedKey);
        return result.uses.stream().filter(e -> id.equals(e.getDst())).collect(Collectors.toList());
    }

    private static List<JConfigRead> unresolvedWith(String reason) {
        return result.unresolved.stream()
                .filter(r -> reason.equals(r.getReason()))
                .collect(Collectors.toList());
    }

    // ---- the four outcomes ------------------------------------------------------------------

    @Test
    void anAnnotationReadResolvesToTheDeclaredKey() {
        List<JConfigUseEdge> uses = usesOf("server.port");
        assertEquals(1, uses.size(), "@Value(\"${server.port}\") must resolve to the declared key");
        assertTrue(uses.get(0).getSrc().endsWith("/Config/port"),
                "the read is attributed to the annotated field, not a call site: " + uses.get(0).getSrc());
        assertEquals(List.of("literal"), uses.get(0).getProv());
    }

    @Test
    void aCallSiteReadResolvesToTheDeclaredKey() {
        List<JConfigUseEdge> uses = usesOf("HOME");
        assertEquals(1, uses.size(), "System.getenv(\"HOME\") must resolve against the .env key");
        assertTrue(uses.get(0).getSrc().contains("@"),
                "a call-site read is attributed to its body node's ordinal id: " + uses.get(0).getSrc());
    }

    @Test
    void aLiteralKeyNobodyDeclaredIsAnUndefinedKeyRead() {
        Set<String> keys = unresolvedWith("undefined-key").stream()
                .map(JConfigRead::getKey).collect(Collectors.toSet());
        assertTrue(keys.contains("NOT_DECLARED"), "expected NOT_DECLARED, got " + keys);
        assertTrue(keys.contains("absent.key"),
                "an unresolved ANNOTATION read must be recorded too, not silently dropped: " + keys);
    }

    @Test
    void aKeyThatNeverClosesOnALiteralIsANonLiteralRead() {
        List<JConfigRead> reads = unresolvedWith("non-literal");
        assertEquals(1, reads.size(), "System.getenv(name) is the only non-literal read in the fixture");
        assertNotNull(reads.get(0).getCallee());
        assertEquals(null, reads.get(0).getKey(), "a non-literal read has no key to report");
    }

    @Test
    void aValueAnnotationWithNoPlaceholderIsNotAConfigReadAtAll() {
        // `@Value("a plain default")` reads no configuration, so it must not appear in EITHER list --
        // recording it as unresolved would inflate the very signal the unresolved list exists to give.
        assertTrue(result.unresolved.stream().noneMatch(r -> "a plain default".equals(r.getKey())));
        assertTrue(result.uses.stream().noneMatch(e -> e.getSrc().endsWith("/notAConfigRead")));
    }

    // ---- prefix binding and the _k discriminant ----------------------------------------------

    @Test
    void aConfigurationPropertiesPrefixClaimsEveryKeyBeneathIt() {
        assertEquals(1, usesOf("datasource.url").size());
        assertEquals(1, usesOf("datasource.pool.size").size(),
                "a prefix binds every declared key under it, not just the shallow one");
        assertTrue(usesOf("unread.key").isEmpty(), "a key outside the prefix must not be claimed");
        assertTrue(usesOf("datasource.url").get(0).getSrc().endsWith("/Datasource"),
                "a prefix read is attributed to the annotated type");
    }

    @Test
    void twoUndeclaredKeysThroughOneCalleeSurviveAsTwoRelationships() {
        // Without the `_k`=(key, reason) discriminant a plain endpoint-pair MERGE collapses these
        // onto one relationship and keeps only the last key SET -- the hazard codeanalyzer-python
        // hit first. Both reads go through the same System.getenv ghost.
        List<EdgeRow> unresolved = rows.edges.stream()
                .filter(e -> e.type.equals("J_READS_CONFIG_UNRESOLVED"))
                .filter(e -> "undefined-key".equals(e.props.get("reason")))
                .collect(Collectors.toList());
        Set<Object> keys = unresolved.stream().map(e -> e.props.get("key")).collect(Collectors.toSet());
        assertTrue(keys.contains("NOT_DECLARED") && keys.contains("STILL_NOT_DECLARED"),
                "both undeclared keys must survive as distinct rows, got " + keys);
    }

    // ---- projection invariants ---------------------------------------------------------------

    @Test
    void everyProjectedConfigEdgeHasBothEndpointsInTheGraph() {
        Set<String> nodeIds = new HashSet<>();
        for (NodeRow n : rows.nodes) {
            nodeIds.add(n.value);
        }
        List<EdgeRow> configEdges = rows.edges.stream()
                .filter(e -> e.type.startsWith("J_USES_CONFIG")
                        || e.type.equals("J_READS_CONFIG_UNRESOLVED"))
                .collect(Collectors.toList());
        assertFalse(configEdges.isEmpty(), "the fixture must produce config edges to check");
        for (EdgeRow e : configEdges) {
            assertTrue(nodeIds.contains(e.from.value), "dangling src: " + e.from.value);
            assertTrue(nodeIds.contains(e.to.value), "dangling dst: " + e.to.value);
        }
    }

    @Test
    void theProjectionMatchesTheJsonEdgeCount() {
        long projected = rows.edges.stream().filter(e -> e.type.equals("J_USES_CONFIG")).count();
        assertEquals(result.uses.size(), projected,
                "every analysis.json config_use must reach the graph, and vice versa");
    }

    @Test
    void outputIsDeterministicallyOrdered() {
        List<String> srcs = result.uses.stream().map(JConfigUseEdge::getSrc).collect(Collectors.toList());
        List<String> sorted = srcs.stream().sorted().collect(Collectors.toList());
        assertEquals(sorted, srcs, "config_uses must be sorted by (src, dst) for the -j gate");
    }
}
