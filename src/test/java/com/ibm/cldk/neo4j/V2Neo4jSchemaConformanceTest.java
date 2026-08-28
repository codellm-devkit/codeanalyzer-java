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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.cldk.neo4j.GraphRows.EdgeRow;
import com.ibm.cldk.neo4j.GraphRows.NodeRow;
import com.ibm.cldk.neo4j.SchemaCatalog.NodeLabel;
import com.ibm.cldk.neo4j.SchemaCatalog.RelType;
import com.ibm.cldk.schema.Analysis;
import com.ibm.cldk.schema.JModule;
import com.ibm.cldk.schema.V2Emitter;
import com.ibm.cldk.syntactic_analysis.L1Extractor;
import com.ibm.cldk.syntactic_analysis.L2CallGraph;
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

/**
 * Schema v2 graph conformance (no container needed): run the real L1–L3 pipeline over a fixture,
 * project with {@link V2GraphProjector}, and assert the projector only ever produces what
 * {@link V2SchemaCatalog} declares — the anti-drift guard for the 2.0.0 graph contract. Also pins
 * the convergence decisions: body nodes instead of call-site nodes, and the {@code _k}-keyed
 * CFG/DDG relationships.
 */
public class V2Neo4jSchemaConformanceTest {

    private static final Path FIXTURE = Paths.get("src/test/resources/test-applications/call-graph-test");

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
                FIXTURE, "call-graph-test", null, new LinkedHashMap<>(), 3, 3, "ast");
        L2CallGraph.Result l2 = L2CallGraph.build("call-graph-test", modules, null, true);
        Analysis analysis = V2Emitter.emit(
                "call-graph-test", 3, modules, "test", l2.callGraph(), l2.externalSymbols());
        rows = V2GraphProjector.project(analysis, "call-graph-test");
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
        String cypher = CypherWriter.renderCypher(rows, "call-graph-test");
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
}
