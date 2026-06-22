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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.javaparser.Problem;
import com.google.gson.GsonBuilder;
import com.ibm.cldk.SymbolTable;
import com.ibm.cldk.entities.JavaCompilationUnit;
import com.ibm.cldk.neo4j.GraphRows.EdgeRow;
import com.ibm.cldk.neo4j.GraphRows.NodeRow;
import com.ibm.cldk.neo4j.SchemaCatalog.NodeLabel;
import com.ibm.cldk.neo4j.SchemaCatalog.RelType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Schema conformance test (no container needed). Projects a sample fixture and asserts that the
 * real projector only ever produces node labels, relationship types and properties that the catalog
 * ({@link SchemaCatalog}) declares. This is the anti-drift guard: if {@link GraphProjector} grows a
 * label or property that the catalog doesn't declare, this fails — keeping the published
 * {@code schema.json} honest. It also checks the checked-in {@code schema.neo4j.json} is regenerated.
 */
public class Neo4jSchemaConformanceTest {

    private static final Path FIXTURE = Paths.get("src/test/resources/test-applications/call-graph-test");

    private static GraphRows rows;

    private static final Map<String, NodeLabel> BY_LABEL = new HashMap<>();
    private static final Map<String, String> MERGE_OF = new HashMap<>();
    private static final Map<String, RelType> REL_BY_TYPE = new HashMap<>();
    private static final Set<String> MARKERS = new HashSet<>(SchemaCatalog.MARKER_LABELS);

    @BeforeAll
    static void project() throws Exception {
        for (NodeLabel nl : SchemaCatalog.NODE_LABELS) {
            BY_LABEL.put(nl.label, nl);
            MERGE_OF.put(nl.label, nl.mergeLabel);
        }
        for (RelType rt : SchemaCatalog.REL_TYPES) {
            REL_BY_TYPE.put(rt.type, rt);
        }
        Pair<Map<String, JavaCompilationUnit>, Map<String, List<Problem>>> extracted =
                SymbolTable.extractAll(FIXTURE);
        rows = GraphProjector.project(extracted.getLeft(), null, "call-graph-test");
    }

    /** The specific (catalog) label for a node row: the non-merge, non-marker label. */
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
    public void checkedInSchemaMatchesCatalog() throws Exception {
        Path onDiskPath = Paths.get("schema.neo4j.json");
        assertTrue(Files.exists(onDiskPath), "schema.neo4j.json missing — run `--emit schema`");
        String onDisk = new String(Files.readAllBytes(onDiskPath), StandardCharsets.UTF_8).trim();
        String fresh = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
                .toJson(SchemaCatalog.buildSchemaDocument()).trim();
        assertEquals(fresh, onDisk, "schema.neo4j.json is stale — regenerate with `--emit schema`");
    }
}
