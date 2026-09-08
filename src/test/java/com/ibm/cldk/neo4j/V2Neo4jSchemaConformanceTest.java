/*
Copyright IBM Corporation 2023, 2024

Licensed under the Apache Public License 2.0, Version 2.0 (the "License");
you may not use this file except in compliance with the License.

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.ibm.cldk.neo4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.cldk.artifacts.ArtifactDiscovery;
import com.ibm.cldk.artifacts.ConfigKeys;
import com.ibm.cldk.artifacts.DependencyView;
import com.ibm.cldk.neo4j.GraphRows.EdgeRow;
import com.ibm.cldk.neo4j.GraphRows.NodeRow;
import com.ibm.cldk.neo4j.SchemaCatalog.NodeLabel;
import com.ibm.cldk.neo4j.SchemaCatalog.RelType;
import com.ibm.cldk.schema.Analysis;
import com.ibm.cldk.schema.CanId;
import com.ibm.cldk.schema.JArtifact;
import com.ibm.cldk.schema.JDependency;
import com.ibm.cldk.schema.JModule;
import com.ibm.cldk.schema.V2Emitter;
import com.ibm.cldk.syntactic_analysis.L1Extractor;
import com.ibm.cldk.syntactic_analysis.L2CallGraph;
import com.ibm.cldk.syntactic_analysis.dataflow.SdgVertices;
import com.ibm.cldk.syntactic_analysis.dataflow.SummaryPass;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Schema v2 graph conformance (no container needed): run the real L1–L3 pipeline plus the L4 SDG
 * passes ({@link SdgVertices}, {@link SummaryPass}) over a fixture, project with
 * {@link V2GraphProjector}, and assert the projector only ever produces what
 * {@link V2SchemaCatalog} declares — the anti-drift guard for the graph contract. Also pins
 * the convergence decisions: body nodes instead of call-site nodes, and the {@code _k}-keyed
 * CFG/DDG relationships.
 */
public class V2Neo4jSchemaConformanceTest {

    // l4-sdg-test (not call-graph-test): its calls are all 1-arg with a transitive a→b→c chain, so
    // J_PARAM_IN/J_SUMMARY are guaranteed non-empty here. The v1/v2 conformance tests still exercise
    // call-graph-test.
    private static final String APP_NAME = "l4-sdg-test";
    private static final Path FIXTURE = Paths.get("src/test/resources/test-applications/" + APP_NAME);

    // A throwaway repository-artifact fixture, independent of FIXTURE above: ArtifactDiscovery /
    // DependencyView / ConfigKeys only care about non-.java files, so this is populated with a
    // pom.xml, a matching gradle.lockfile pin and an application.properties -- one manifest declared
    // AND locked, so HAS_ARTIFACT/DEFINES_CONFIG/DECLARES_DEPENDENCY/LOCKS are all non-empty below.
    @TempDir
    static Path ARTIFACT_TMP;

    private static GraphRows rows;

    private static final Map<String, NodeLabel> BY_LABEL = new HashMap<>();
    private static final Map<String, String> MERGE_OF = new HashMap<>();
    private static final Map<String, RelType> REL_BY_TYPE = new HashMap<>();
    private static final Set<String> MARKERS = new HashSet<>(V2SchemaCatalog.MARKER_LABELS);

    @BeforeAll
    static void project() throws Exception {
        for (NodeLabel nl : V2SchemaCatalog.NODE_LABELS) {
            BY_LABEL.put(nl.label, nl);
            MERGE_OF.put(nl.label, nl.mergeLabel);
        }
        for (RelType rt : V2SchemaCatalog.REL_TYPES) {
            REL_BY_TYPE.put(rt.type, rt);
        }
        Map<String, JModule> modules = L1Extractor.extractAll(
                FIXTURE, APP_NAME, null, new LinkedHashMap<>(), 3, 3, "ast");
        L2CallGraph.Result l2 = L2CallGraph.build(APP_NAME, modules, null, true);
        SdgVertices.Result sdg = SdgVertices.apply(modules);
        SummaryPass.apply(modules, l2.callGraph(), 3);

        // Repository-artifact layer (Task 7): mirrors CodeAnalyzer's own wiring (discover, then
        // build dependencies, then flatten config keys) over ARTIFACT_TMP.
        Files.writeString(ARTIFACT_TMP.resolve("pom.xml"),
                "<project><dependencies>"
                        + "<dependency><groupId>org.example</groupId><artifactId>widget</artifactId>"
                        + "<version>1.0.0</version></dependency>"
                        + "</dependencies></project>",
                StandardCharsets.UTF_8);
        Files.writeString(ARTIFACT_TMP.resolve("gradle.lockfile"),
                "org.example:widget:1.0.0=compileClasspath,runtimeClasspath\n", StandardCharsets.UTF_8);
        Files.writeString(ARTIFACT_TMP.resolve("application.properties"),
                "server.port=8080\nspring.datasource.url=${DB_URL}\n", StandardCharsets.UTF_8);
        Map<String, JArtifact> artifacts =
                ArtifactDiscovery.discover(ARTIFACT_TMP, APP_NAME, true, 262144);
        List<JDependency> dependencies = DependencyView.build(ARTIFACT_TMP, artifacts);
        for (JArtifact a : artifacts.values()) {
            if (ConfigKeys.isEligible(a)) {
                ConfigKeys.Result r = ConfigKeys.extract(
                        a, DependencyView.readFromDisk(ARTIFACT_TMP, a.getPath()), true);
                a.setConfigKeys(r.keys);
            }
        }

        Analysis analysis = V2Emitter.emit(
                APP_NAME, 3, modules, "test", l2.callGraph(), l2.externalSymbols(),
                sdg.paramIn, sdg.paramOut, artifacts, dependencies);
        rows = V2GraphProjector.project(analysis, APP_NAME);
    }

    private static String specificLabel(List<String> labels) {
        String merge = labels.get(0);
        if (!merge.equals("JSymbol")) {
            return merge;
        }
        for (String l : labels) {
            if (!l.equals("JSymbol") && !MARKERS.contains(l)) {
                return l;
            }
        }
        return "JSymbol";
    }

    private static Set<String> mergeLabelsFor(List<String> specifics) {
        Set<String> out = new HashSet<>();
        for (String s : specifics) {
            out.add(MERGE_OF.get(s));
        }
        return out;
    }

    @Test
    public void everyEmittedNodeLabelAndPropertyIsDeclared() {
        assertTrue(rows.nodes.size() > 0, "fixture produced no nodes");
        for (NodeRow node : rows.nodes) {
            String specific = specificLabel(node.labels);
            NodeLabel decl = BY_LABEL.get(specific);
            assertNotNull(decl, "undeclared node label: " + String.join(":", node.labels));
            assertEquals(decl.mergeLabel, node.labels.get(0), "wrong merge label for " + specific);
            for (String label : node.labels) {
                boolean ok = label.equals(decl.mergeLabel) || label.equals(specific) || MARKERS.contains(label);
                assertTrue(ok, "unexpected label '" + label + "' on " + specific);
            }
            for (String key : node.props.keySet()) {
                assertTrue(decl.properties.containsKey(key), "undeclared property '" + specific + "." + key + "'");
            }
        }
    }

    @Test
    public void everyEmittedRelationshipIsDeclared() {
        assertTrue(rows.edges.size() > 0, "fixture produced no edges");
        for (EdgeRow edge : rows.edges) {
            RelType decl = REL_BY_TYPE.get(edge.type);
            assertNotNull(decl, "undeclared relationship type: " + edge.type);
            assertTrue(mergeLabelsFor(decl.from).contains(edge.from.label),
                    "bad source " + edge.from.label + " for " + edge.type);
            assertTrue(mergeLabelsFor(decl.to).contains(edge.to.label),
                    "bad target " + edge.to.label + " for " + edge.type);
            for (String key : edge.props.keySet()) {
                assertTrue(decl.properties.containsKey(key), "undeclared property on " + edge.type + "." + key);
            }
        }
    }

    @Test
    public void convergedNodeModelHasBodyNodesAndNoV1OnlyNodes() {
        boolean sawModule = false;
        boolean sawBodyNode = false;
        for (NodeRow node : rows.nodes) {
            String merge = node.labels.get(0);
            sawModule |= merge.equals("JModule");
            sawBodyNode |= merge.equals("JBodyNode");
            assertFalse(merge.equals("JCompilationUnit") || merge.equals("JCallSite")
                            || merge.equals("JParameter") || merge.equals("JComment"),
                    "v1-only node label leaked into the v2 projection: " + merge);
        }
        assertTrue(sawModule, "no :JModule rows projected");
        assertTrue(sawBodyNode, "no :JBodyNode rows projected — the L3 body did not project");
    }

    @Test
    public void l3OverlayEdgesAreKeyedAndPresent() {
        boolean sawCfg = false;
        boolean sawHasBody = false;
        for (EdgeRow edge : rows.edges) {
            if (edge.type.equals("J_CFG_NEXT")) {
                sawCfg = true;
                assertNotNull(edge.key, "J_CFG_NEXT must carry the _k MERGE discriminant");
            }
            if (edge.type.equals("J_DDG")) {
                assertNotNull(edge.key, "J_DDG must carry the _k MERGE discriminant");
            }
            sawHasBody |= edge.type.equals("J_HAS_BODY_NODE");
        }
        assertTrue(sawCfg, "no J_CFG_NEXT edges — the L3 cfg overlay did not project");
        assertTrue(sawHasBody, "no J_HAS_BODY_NODE edges");
    }

    @Test
    public void wipeCoversBothGenerationsSoV2ReplacesAPriorV1Graph() {
        String cypher = CypherWriter.renderCypher(rows, APP_NAME);
        assertTrue(cypher.contains("J_HAS_UNIT|J_HAS_MODULE"),
                "the wipe must traverse both generations' unit relationship");
        assertTrue(cypher.contains("MATCH (s:JSymbol) WHERE NOT (s)--() DELETE s"),
                "the wipe must sweep orphaned symbols (v1 import-materialized type stubs)");
        // The v1 path is NOT reachable by the can:// prefix sweep -- v1 ids are FQNs and v1 roots
        // carry no `id` at all -- so replacing the containment traversal with the sweep orphans
        // every prior v1 graph. That was tried on this branch and reverted; both must be present.
        assertTrue(cypher.contains("OPTIONAL MATCH (a)-[:J_HAS_UNIT|J_HAS_MODULE]->(c)")
                        && cypher.contains("OPTIONAL MATCH (c)-" + CypherWriter.DESCENDANTS + "->(x)")
                        && cypher.contains("DETACH DELETE x, c, a;"),
                "the v1-reaching containment traversal must survive alongside the prefix sweep: " + cypher);
        assertTrue(cypher.contains("a.id IS NULL AND a.name = '" + APP_NAME + "'"),
                "a legacy v1 root has no id and is reachable only by the guarded name branch: " + cypher);
        for (String rel : new String[] {"J_HAS_CALLSITE", "J_HAS_COMMENT", "J_HAS_PARAMETER",
                "J_DECLARES", "J_HAS_METHOD", "J_HAS_BODY_NODE"}) {
            assertTrue(CypherWriter.DESCENDANTS.contains(rel),
                    "wipe/prune descendant traversal must include " + rel);
        }
    }

    @Test
    void l4OverlayProjectsParamAndSummaryEdges() {
        boolean paramIn = false;
        boolean paramOut = false;
        boolean summary = false;
        for (EdgeRow edge : rows.edges) {
            paramIn |= edge.type.equals("J_PARAM_IN");
            paramOut |= edge.type.equals("J_PARAM_OUT");
            summary |= edge.type.equals("J_SUMMARY");
        }
        assertTrue(paramIn, "J_PARAM_IN projected from application param_in");
        assertTrue(paramOut, "J_PARAM_OUT projected from application param_out");
        assertTrue(summary, "J_SUMMARY projected from callable summaries");
    }

    // ------------------------------------------------------------------------------------------
    // Repository-artifact layer (Task 7): Artifact/Package/ConfigKey.
    // ------------------------------------------------------------------------------------------

    @Test
    void artifactLayerNodesAreEmitted() {
        boolean sawArtifact = false;
        boolean sawPackage = false;
        boolean sawConfigKey = false;
        for (NodeRow node : rows.nodes) {
            String merge = node.labels.get(0);
            sawArtifact |= merge.equals("Artifact");
            sawPackage |= merge.equals("Package");
            sawConfigKey |= merge.equals("ConfigKey");
        }
        assertTrue(sawArtifact, "no :Artifact rows projected");
        assertTrue(sawPackage, "no :Package rows projected");
        assertTrue(sawConfigKey, "no :ConfigKey rows projected");
    }

    @Test
    void packageNodeIsKeyedByPurlAndCarriesMavenCoordinates() {
        String pkgId = CanId.purlMaven("org.example", "widget");
        NodeRow pkg = findNode("Package", pkgId);
        assertNotNull(pkg, "expected a :Package node keyed on " + pkgId);
        assertEquals("maven", pkg.props.get("ecosystem"));
        assertEquals("org.example", pkg.props.get("group"));
        assertEquals("widget", pkg.props.get("name"));
    }

    @Test
    void artifactLayerEdgesAreEmittedAndDeclaresDependencyIsKeyedByKind() {
        boolean hasArtifact = false;
        boolean definesConfig = false;
        boolean declaresDependency = false;
        boolean locks = false;
        String declaresDependencyKey = null;
        String pkgId = CanId.purlMaven("org.example", "widget");
        // Every one of the four types asserts BOTH endpoints. Asserting only `to` is the same
        // one-sided coverage that let a wrong decision ship on this layer once already (see
        // wipeStaysOffTheCrossLanguageArtifactPackageSubgraph below, which exists because only the
        // positive case had ever been asserted): a mis-sourced edge still lands on the expected
        // target, so nothing except the source endpoint can catch it.
        String pomId = CanId.artifactId(APP_NAME, "pom.xml");
        String lockId = CanId.artifactId(APP_NAME, "gradle.lockfile");
        for (EdgeRow edge : rows.edges) {
            if (edge.type.equals("HAS_ARTIFACT")) {
                hasArtifact = true;
                assertEquals("JApplication", edge.from.label, "HAS_ARTIFACT runs application -> artifact");
                assertEquals(CanId.applicationId(APP_NAME), edge.from.value,
                        "the application is the source endpoint, keyed on its can:// id");
                assertEquals("Artifact", edge.to.label);
            }
            if (edge.type.equals("DEFINES_CONFIG")) {
                definesConfig = true;
                assertEquals("Artifact", edge.from.label, "DEFINES_CONFIG runs artifact -> config key");
                assertEquals("ConfigKey", edge.to.label);
                assertTrue(edge.to.value.startsWith(edge.from.value + "@key"),
                        "a config key nests under the artifact that defines it: " + edge.to.value);
            }
            if (edge.type.equals("DECLARES_DEPENDENCY") && edge.to.value.equals(pkgId)) {
                declaresDependency = true;
                declaresDependencyKey = edge.key;
                assertEquals("Artifact", edge.from.label, "DECLARES_DEPENDENCY runs manifest -> package");
                assertEquals(pomId, edge.from.value, "the declaring manifest is the source endpoint");
                assertEquals("Package", edge.to.label);
                assertEquals("1.0.0", edge.props.get("spec"));
                assertEquals("runtime", edge.props.get("kind"));
                assertEquals(Boolean.TRUE, edge.props.get("direct"));
            }
            if (edge.type.equals("LOCKS") && edge.to.value.equals(pkgId)) {
                locks = true;
                assertEquals("Artifact", edge.from.label, "LOCKS runs lock artifact -> package");
                assertEquals(lockId, edge.from.value,
                        "the lock file is the source endpoint -- the package does not lock the lockfile");
                assertEquals("Package", edge.to.label);
                assertEquals("1.0.0", edge.props.get("version"));
                assertNull(edge.key, "LOCKS carries no _k discriminant");
            }
        }
        assertTrue(hasArtifact, "no HAS_ARTIFACT edges projected");
        assertTrue(definesConfig, "no DEFINES_CONFIG edges projected");
        assertTrue(declaresDependency, "no DECLARES_DEPENDENCY edge for the fixture's declared package");
        assertTrue(locks, "no LOCKS edge for the fixture's locked package");
        assertEquals("runtime", declaresDependencyKey,
                "DECLARES_DEPENDENCY must carry the _k=kind MERGE discriminant");
    }

    @Test
    void oneLocksRowSurvivesWhenTwoManifestsDeclareTheSameLockedCoordinate(@TempDir Path tmp)
            throws Exception {
        // Two build.gradle files of one multi-module build declaring the same coordinate, with a
        // single gradle.lockfile pinning it. `dependencies` then carries that coordinate twice,
        // both copies with a lockedVersion, so projectArtifacts walks the LOCKS mint twice for one
        // (lock artifact, package) pair -- LOCKS is a per-PACKAGE fact, unlike DECLARES_DEPENDENCY,
        // whose source ref differs per declaring manifest and which is correctly one row per
        // declaration. GraphRows promises "a deterministic, deduped bag"; this pins that promise at
        // the observable boundary, whichever layer keeps it.
        Files.writeString(tmp.resolve("build.gradle"),
                "dependencies { implementation 'org.example:widget:1.0.0' }\n", StandardCharsets.UTF_8);
        Files.createDirectories(tmp.resolve("mod"));
        Files.writeString(tmp.resolve("mod/build.gradle"),
                "dependencies { implementation 'org.example:widget:1.0.0' }\n", StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("gradle.lockfile"),
                "org.example:widget:1.0.0=compileClasspath,runtimeClasspath\n", StandardCharsets.UTF_8);

        Map<String, JArtifact> artifacts = ArtifactDiscovery.discover(tmp, "dup", true, 262144);
        List<JDependency> dependencies = DependencyView.build(tmp, artifacts);
        String pkgId = CanId.purlMaven("org.example", "widget");

        int lockedDeclarations = 0;
        for (JDependency d : dependencies) {
            if ("widget".equals(d.getName()) && d.getLockedVersion() != null) {
                lockedDeclarations++;
            }
        }
        assertEquals(2, lockedDeclarations,
                "precondition: both manifests must declare the coordinate and both copies must be locked");

        GraphRows dupRows = V2GraphProjector.project(
                V2Emitter.emit("dup", 1, new LinkedHashMap<>(), "test", null, null, null, null,
                        artifacts, dependencies),
                "dup");

        int locks = 0;
        int declares = 0;
        for (EdgeRow edge : dupRows.edges) {
            if (edge.to.value.equals(pkgId)) {
                if (edge.type.equals("LOCKS")) {
                    locks++;
                    assertEquals("1.0.0", edge.props.get("version"));
                } else if (edge.type.equals("DECLARES_DEPENDENCY")) {
                    declares++;
                }
            }
        }
        assertEquals(1, locks, "one lock artifact pinning one package is exactly one LOCKS row, "
                + "however many manifests declared that package");
        assertEquals(2, declares, "DECLARES_DEPENDENCY stays one row per declaring manifest -- "
                + "its source ref differs per manifest, so it must NOT be deduped per package");
    }

    @Test
    void theWipeReachesThisAppsArtifactsAndConfigKeysButNeverAPackage() {
        // The deliberate widening (#244), mirroring codeanalyzer-python: :Artifact/:ConfigKey are
        // under `can://<app>/` now that the app is the outermost id segment, so the snapshot's
        // prefix sweep rebuilds them instead of letting stale ones accumulate forever with nothing
        // ever cleaning them up. The accepted cost is one behavioural widening: a sibling-language
        // analyzer's edge into a shared :Artifact is dropped by a Java snapshot and restored on that
        // analyzer's next push. :Package (a `pkg:` purl) sits under no application, so it stays out
        // by construction -- assert that, because it is the only thing left holding the line.
        String cypher = CypherWriter.renderCypher(rows, APP_NAME);
        String prefix = "can://" + APP_NAME + "/";

        assertTrue(cypher.contains("MATCH (x:" + RowBuilder.CAN_NODE + ") WHERE x.id = 'can://" + APP_NAME
                        + "' OR x.id STARTS WITH '" + prefix + "'"),
                "the wipe must sweep the whole app prefix, rooted on the label-anchored index: " + cypher);
        assertTrue(cypher.contains("CALL { WITH x DETACH DELETE x } IN TRANSACTIONS OF 1000 ROWS;"),
                "the prefix sweep must batch, or a large app's wipe is one unbounded transaction: " + cypher);

        // Not "an id that looks like an artifact id" -- the actual rows the projector emitted.
        int artifacts = 0;
        int configKeys = 0;
        int packages = 0;
        for (NodeRow n : rows.nodes) {
            if (n.labels.contains("Artifact")) {
                artifacts++;
                assertTrue(n.value.startsWith(prefix),
                        "an :Artifact must be under the app prefix the wipe sweeps, got: " + n.value);
                assertTrue(n.labels.contains(RowBuilder.CAN_NODE),
                        ":Artifact needs the index anchor or the prefix sweep cannot seek it: " + n.value);
            }
            if (n.labels.contains("ConfigKey")) {
                configKeys++;
                assertTrue(n.value.startsWith(prefix),
                        "a :ConfigKey must be under the app prefix the wipe sweeps, got: " + n.value);
                assertTrue(n.labels.contains(RowBuilder.CAN_NODE),
                        ":ConfigKey needs the index anchor or the prefix sweep cannot seek it: " + n.value);
            }
            if (n.labels.contains("Package")) {
                packages++;
                assertFalse(n.value.startsWith(prefix),
                        ":Package is a pkg: purl under no application -- the wipe must not reach it: " + n.value);
                assertFalse(n.labels.contains(RowBuilder.CAN_NODE),
                        ":Package must not carry the can:// index anchor: " + n.value);
            }
        }
        assertTrue(artifacts > 0, "fixture emitted no :Artifact -- this test would pass vacuously");
        assertTrue(configKeys > 0, "fixture emitted no :ConfigKey -- this test would pass vacuously");
        assertTrue(packages > 0, "fixture emitted no :Package -- this test would pass vacuously");
        assertFalse(CanId.purlMaven("org.example", "widget").startsWith(prefix),
                "a maven purl must never fall under an application prefix");

        // DESCENDANTS still excludes the artifact/package edges, for a reason that did NOT change:
        // BoltWriter.PRUNE_VANISHED_UNITS_V2 shares this constant, and its unit of deletion is one
        // vanished compilation unit. An app-level :Artifact is not a descendant of any one unit and
        // must not die with one -- the widening above is snapshot-only, because only the snapshot
        // writes the full truth back.
        for (String rel : new String[] {"HAS_ARTIFACT", "DEFINES_CONFIG", "DECLARES_DEPENDENCY", "LOCKS"}) {
            assertFalse(CypherWriter.DESCENDANTS.contains(rel),
                    "the unit-scoped descendant traversal must never include " + rel + " -- it is shared "
                            + "with BoltWriter's per-unit prune, where an app-level artifact is out of scope");
        }
    }

    @Test
    void constraintsStayInSyncWithTheCatalog() {
        // Schema.CONSTRAINTS is the hand-maintained list CypherWriter/BoltWriter actually execute;
        // V2SchemaCatalog.uniquenessConstraints() is what the emitted schema.neo4j.json document
        // promises (one entry per distinct (merge_label, key)). The two must agree semantically --
        // not byte-for-byte: Schema.CONSTRAINTS predates the derived naming/alias convention and
        // keeps its own descriptive names (e.g. `j_symbol_id`, alias `s`) rather than the derived
        // form (`jsymbol_id`, alias `x`) -- but a promised constraint no load ever creates is
        // exactly the contract-overpromise defect class this conformance test class already guards
        // against, one level up (#197 review: schema.neo4j.json shipped promising three constraints
        // no load created because Schema.CONSTRAINTS was never extended alongside the catalog).
        Pattern labelAndKey = Pattern.compile("FOR \\(\\w+:(\\w+)\\) REQUIRE \\w+\\.(\\w+) IS UNIQUE");
        for (String derived : V2SchemaCatalog.uniquenessConstraints()) {
            Matcher dm = labelAndKey.matcher(derived);
            assertTrue(dm.find(), "unparseable derived constraint: " + derived);
            Pattern expected = Pattern.compile(
                    "FOR \\(\\w+:" + dm.group(1) + "\\) REQUIRE \\w+\\." + dm.group(2) + " IS UNIQUE");
            boolean present = false;
            for (String executed : Schema.CONSTRAINTS) {
                if (expected.matcher(executed).find()) {
                    present = true;
                    break;
                }
            }
            assertTrue(present, "Schema.CONSTRAINTS has no uniqueness constraint for ("
                    + dm.group(1) + ", " + dm.group(2) + ") -- schema.neo4j.json promises one but no load "
                    + "would create it");
        }
    }

    @Test
    void theApplicationRootIsAddressableByItsCanId() {
        NodeRow app = rows.nodes.stream()
                .filter(n -> n.labels.contains("JApplication"))
                .findFirst().orElseThrow();
        assertEquals("id", app.keyProp, "the root must merge on its id, not a display name");
        assertEquals(CanId.applicationId(APP_NAME), app.value);
        assertEquals(APP_NAME, app.props.get("name"), "name survives as a display property");
        assertTrue(app.labels.contains(RowBuilder.CAN_NODE),
                "the root must carry the index anchor, so the prefix-scoped delete can reach it");
    }

    @Test
    void twoApplicationsProjectAsTwoDistinctRoots() {
        // Two DIFFERENTLY-named services: the root is keyed on `can://<app-name>`, so each gets
        // its own id and its own prefix, and neither's wipe reaches the other. Two services sharing
        // an --app-name would still be one node -- the id is derived from the name, so it cannot
        // encode a distinction the operator did not make.
        GraphRows a = V2GraphProjector.project(
                V2Emitter.emit("svc-quotes", 1, Map.of(), "test"), "svc-quotes");
        GraphRows b = V2GraphProjector.project(
                V2Emitter.emit("svc-orders", 1, Map.of(), "test"), "svc-orders");

        String ida = a.nodes.stream().filter(n -> n.labels.contains("JApplication"))
                .findFirst().orElseThrow().value;
        String idb = b.nodes.stream().filter(n -> n.labels.contains("JApplication"))
                .findFirst().orElseThrow().value;
        assertNotEquals(ida, idb, "two differently-named services must not share a root node");
        assertEquals(CanId.applicationId("svc-quotes"), ida);
        assertEquals(CanId.applicationId("svc-orders"), idb);
    }

    @Test
    void everyProjectedIdSitsUnderTheApplicationPrefix() {
        String prefix = CanId.applicationId(APP_NAME) + "/";
        String root = CanId.applicationId(APP_NAME);
        for (NodeRow n : rows.nodes) {
            if (!RowBuilder.isCanId(n.value)) {
                continue; // JPackage/JAnnotation are name-keyed by design
            }
            assertTrue(n.value.equals(root) || n.value.startsWith(prefix),
                    n.value + " escapes the application prefix, so a scoped delete would miss it");
        }
    }

    @Test
    void noIdCarriesTheOldLanguageFirstShape() {
        for (NodeRow n : rows.nodes) {
            assertFalse(n.value.startsWith("can://java/"),
                    "old-shape id survived: " + n.value);
            assertFalse(n.value.startsWith("can://artifact/"),
                    "old-shape artifact id survived: " + n.value);
        }
    }

    private static NodeRow findNode(String mergeLabel, String value) {
        for (NodeRow node : rows.nodes) {
            if (node.labels.get(0).equals(mergeLabel) && node.value.equals(value)) {
                return node;
            }
        }
        return null;
    }
}
