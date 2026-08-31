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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Schema v2 graph conformance (no container needed): run the real L1–L3 pipeline plus the L4 SDG
 * passes ({@link SdgVertices}, {@link SummaryPass}) over a fixture, project with
 * {@link V2GraphProjector}, and assert the projector only ever produces what
 * {@link V2SchemaCatalog} declares — the anti-drift guard for the 2.2.0 graph contract. Also pins
 * the convergence decisions: body nodes instead of call-site nodes, and the {@code _k}-keyed
 * CFG/DDG relationships.
 */
public class V2Neo4jSchemaConformanceTest {

    // l4-sdg-test (not call-graph-test): its calls are all 1-arg with a transitive a→b→c chain, so
    // J_PARAM_IN/J_SUMMARY are guaranteed non-empty here. The v1/v2 conformance tests still exercise
    // call-graph-test.
    private static final Path FIXTURE = Paths.get("src/test/resources/test-applications/l4-sdg-test");

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
                FIXTURE, "l4-sdg-test", null, new LinkedHashMap<>(), 3, 3, "ast");
        L2CallGraph.Result l2 = L2CallGraph.build("l4-sdg-test", modules, null, true);
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
                ArtifactDiscovery.discover(ARTIFACT_TMP, "l4-sdg-test", true, 262144);
        List<JDependency> dependencies = DependencyView.build(ARTIFACT_TMP, artifacts);
        for (JArtifact a : artifacts.values()) {
            if (ConfigKeys.isEligible(a)) {
                ConfigKeys.Result r = ConfigKeys.extract(
                        a, DependencyView.readFromDisk(ARTIFACT_TMP, a.getPath()), true);
                a.setConfigKeys(r.keys);
            }
        }

        Analysis analysis = V2Emitter.emit(
                "l4-sdg-test", 3, modules, "test", l2.callGraph(), l2.externalSymbols(),
                sdg.paramIn, sdg.paramOut, artifacts, dependencies);
        rows = V2GraphProjector.project(analysis, "l4-sdg-test");
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
        String cypher = CypherWriter.renderCypher(rows, "l4-sdg-test");
        assertTrue(cypher.contains("J_HAS_UNIT|J_HAS_MODULE"),
                "the wipe must traverse both generations' unit relationship");
        assertTrue(cypher.contains("MATCH (s:JSymbol) WHERE NOT (s)--() DELETE s"),
                "the wipe must sweep orphaned symbols (v1 import-materialized type stubs)");
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
    // Repository-artifact layer (Task 7): Artifact/Package/ConfigKey, graph contract 2.2.0.
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
        for (EdgeRow edge : rows.edges) {
            hasArtifact |= edge.type.equals("HAS_ARTIFACT");
            definesConfig |= edge.type.equals("DEFINES_CONFIG");
            if (edge.type.equals("DECLARES_DEPENDENCY") && edge.to.value.equals(pkgId)) {
                declaresDependency = true;
                declaresDependencyKey = edge.key;
                assertEquals("1.0.0", edge.props.get("spec"));
                assertEquals("runtime", edge.props.get("kind"));
                assertEquals(Boolean.TRUE, edge.props.get("direct"));
            }
            if (edge.type.equals("LOCKS") && edge.to.value.equals(pkgId)) {
                locks = true;
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
    void wipeReachesArtifactAndConfigKeySoARepushLeavesNoOrphans() {
        // Same cypher-text-assertion shape as wipeCoversBothGenerationsSoV2ReplacesAPriorV1Graph
        // above (no in-process Neo4j to actually execute the wipe against and check for orphans).
        // The scenario this pins: push, then remove a config-bearing artifact from the analyzed
        // repo and push again -- without HAS_ARTIFACT on the app anchor's first hop, the wipe's
        // OPTIONAL MATCH (a)-[...]->(c) never binds the prior push's :Artifact nodes at all, so
        // DETACH DELETE never reaches them (or their :ConfigKey rows via DEFINES_CONFIG), and both
        // survive the second push as permanent orphans.
        String cypher = CypherWriter.renderCypher(rows, "l4-sdg-test");
        assertTrue(cypher.contains("J_HAS_UNIT|J_HAS_MODULE|HAS_ARTIFACT"),
                "the wipe's app-anchor hop must also reach this app's :Artifact nodes via HAS_ARTIFACT");
        assertTrue(CypherWriter.DESCENDANTS.contains("DEFINES_CONFIG"),
                "wipe/prune descendant traversal must include DEFINES_CONFIG so a wiped "
                        + "Artifact's ConfigKeys are swept too");
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
