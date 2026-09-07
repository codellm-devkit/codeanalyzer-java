package com.ibm.cldk.javaee;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.cldk.neo4j.GraphRows;
import com.ibm.cldk.neo4j.GraphRows.NodeRow;
import com.ibm.cldk.neo4j.V2GraphProjector;
import com.ibm.cldk.schema.Analysis;
import com.ibm.cldk.schema.JCallable;
import com.ibm.cldk.schema.JEntrypointReport;
import com.ibm.cldk.schema.JModule;
import com.ibm.cldk.schema.JType;
import com.ibm.cldk.schema.V2Emitter;
import com.ibm.cldk.syntactic_analysis.L1Extractor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The entrypoint report and its per-node attribution.
 *
 * <p>The case that matters most is the <b>empty</b> one: the pass under-approximates by design, so
 * "this application has no entrypoints" and "the pass found nothing" must not read the same. Every
 * assertion about a populated report is easy; the ones that keep the contract honest are the
 * zero-entrypoint fixture and the graph property that survives pruning.
 */
class EntrypointReportTest {

    @TempDir
    Path root;

    private static final class Built {
        final Map<String, JModule> modules;
        final JEntrypointReport report;
        final GraphRows rows;

        Built(Map<String, JModule> modules, JEntrypointReport report, GraphRows rows) {
            this.modules = modules;
            this.report = report;
            this.rows = rows;
        }
    }

    private Built build(String appName, String fileName, String source) throws Exception {
        Path src = root.resolve("src/main/java/demo");
        Files.createDirectories(src);
        Files.writeString(src.resolve(fileName), source, StandardCharsets.UTF_8);

        JEntrypointReport report = new JEntrypointReport();
        Map<String, JModule> modules = L1Extractor.extractAll(
                root, appName, null, new LinkedHashMap<>(), 1, 3, "ast", report);
        EntrypointScan.completeReport(report, modules);

        Analysis analysis = V2Emitter.emit(appName, 1, modules, "test");
        analysis.getApplication().setEntrypointReport(report);
        return new Built(modules, report, V2GraphProjector.project(analysis, appName));
    }

    private static List<JType> typesOf(Map<String, JModule> modules) {
        List<JType> out = new ArrayList<>();
        for (JModule m : modules.values()) {
            out.addAll(m.getTypes().values());
        }
        return out;
    }

    private static NodeRow applicationRow(GraphRows rows) {
        for (NodeRow n : rows.nodes) {
            if (n.labels.contains("JApplication")) {
                return n;
            }
        }
        throw new AssertionError("no :JApplication row projected");
    }

    // ---- the populated case -------------------------------------------------------------------

    @Test
    void aSpringControllerIsAttributedToItsFramework() throws Exception {
        Built b = build("spring-app", "Controller.java",
                "package demo;\n"
                        + "@RestController\n"
                        + "public class Controller {\n"
                        + "    @GetMapping(\"/x\")\n"
                        + "    public String x() { return \"x\"; }\n"
                        + "}\n");

        JType type = typesOf(b.modules).get(0);
        assertTrue(type.isEntrypointClass());
        assertTrue(type.getEntrypointFrameworks().contains("spring"),
                "expected spring attribution, got " + type.getEntrypointFrameworks());

        // By signature, not position: the callables map leads with the implicit default constructor,
        // which is correctly not an entrypoint.
        JCallable callable = type.getCallables().get("x()");
        assertNotNull(callable, "no x() among " + type.getCallables().keySet());
        assertTrue(callable.isEntrypoint());
        assertTrue(callable.getEntrypointFrameworks().contains("spring"));

        assertTrue(b.report.getFrameworksDetected().contains("spring"));
    }

    @Test
    void theBooleanAndTheAttributionNeverDisagree() throws Exception {
        Built b = build("mixed-app", "Mixed.java",
                "package demo;\n"
                        + "@RestController\n"
                        + "public class Mixed {\n"
                        + "    @GetMapping(\"/a\") public String a() { return \"a\"; }\n"
                        + "    public String plain() { return \"b\"; }\n"
                        + "}\n");
        for (JType t : typesOf(b.modules)) {
            assertEquals(t.isEntrypointClass(), !t.getEntrypointFrameworks().isEmpty(),
                    "is_entrypoint_class and entrypoint_frameworks disagree on " + t.getId());
            for (JCallable c : t.getCallables().values()) {
                assertEquals(c.isEntrypoint(), !c.getEntrypointFrameworks().isEmpty(),
                        "is_entrypoint and entrypoint_frameworks disagree on " + c.getId());
            }
        }
    }

    @Test
    void everyDetectedFrameworkIsOneOfTheDeclaredRulesets() throws Exception {
        Built b = build("spring-app", "Controller.java",
                "package demo;\n@RestController\npublic class Controller {}\n");
        assertTrue(b.report.getRulesets().containsAll(b.report.getFrameworksDetected()),
                "detected " + b.report.getFrameworksDetected()
                        + " is not a subset of rulesets " + b.report.getRulesets());
    }

    // ---- the empty case, which is the whole point ---------------------------------------------

    @Test
    void anApplicationWithNoEntrypointsStillGetsAReport() throws Exception {
        Built b = build("plain-app", "Plain.java",
                "package demo;\npublic class Plain { int add(int a, int b) { return a + b; } }\n");

        assertNotNull(b.report);
        assertTrue(b.report.getFrameworksDetected().isEmpty(), "nothing should have matched here");
        // The load-bearing assertion: an empty `frameworks_detected` NEXT TO a populated `rulesets`
        // is what says the pass looked and came back empty-handed, rather than never having run.
        assertEquals(5, b.report.getRulesets().size(),
                "all five rulesets must be listed even when none matched: " + b.report.getRulesets());
    }

    @Test
    void theApplicationNodeCarriesTheReportEvenWithZeroEntrypoints() throws Exception {
        Built b = build("plain-app", "Plain.java",
                "package demo;\npublic class Plain { int add(int a, int b) { return a + b; } }\n");
        NodeRow app = applicationRow(b.rows);

        // `entrypoint_frameworks` is an EMPTY list here, and RowBuilder.prune drops empty collections.
        // If it is ever set before the prune, this property silently vanishes on exactly the
        // applications the report was written for.
        assertTrue(app.props.containsKey("entrypoint_frameworks"),
                "the empty case must still carry the key, got " + app.props.keySet());
        assertTrue(app.props.containsKey("entrypoint_report_json"));

        String json = (String) app.props.get("entrypoint_report_json");
        assertTrue(json.contains("\"rulesets\""), json);
        assertTrue(json.contains("\"frameworks_detected\""), json);
        assertTrue(json.contains("\"unresolved\""), json);
        assertTrue(json.contains("\"errors\""), json);
    }

    @Test
    void theProjectedNodeCarriesItsAttribution() throws Exception {
        Built b = build("spring-app", "Controller.java",
                "package demo;\n@RestController\npublic class Controller {}\n");
        boolean found = false;
        for (NodeRow n : b.rows.nodes) {
            if (n.labels.contains("JType") && n.props.containsKey("entrypoint_frameworks")) {
                assertEquals(List.of("spring"), n.props.get("entrypoint_frameworks"));
                found = true;
            }
        }
        assertTrue(found, "no :JType row carried entrypoint_frameworks");
    }

    @Test
    void aFreshReportIsEmptyRatherThanNull() {
        JEntrypointReport report = new JEntrypointReport();
        assertNotNull(report.getFrameworksDetected());
        assertNotNull(report.getRulesets());
        assertNotNull(report.getUnresolved());
        assertNotNull(report.getErrors());
        assertFalse(EntrypointScan.rulesets().isEmpty());
    }
}
