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

import java.util.List;
import java.util.Map;

/**
 * The output-agnostic intermediate between {@link GraphProjector} and the two writers
 * ({@link CypherWriter} snapshot / {@link BoltWriter} incremental). Pure data — no I/O, no driver.
 * A {@code GraphRows} is a deterministic, deduped bag of nodes and edges that both writers consume
 * identically.
 *
 * <p>Property values are restricted to Neo4j-legal shapes: primitives ({@link String},
 * {@link Long}/{@link Integer}, {@link Boolean}) and homogeneous {@link List}s of primitives.
 * {@code null} values are pruned (in Neo4j a null property simply means absence).
 */
public final class GraphRows {

    public final List<NodeRow> nodes;
    public final List<EdgeRow> edges;

    public GraphRows(List<NodeRow> nodes, List<EdgeRow> edges) {
        this.nodes = nodes;
        this.edges = edges;
    }

    /** How an edge addresses one of its endpoints: the label + key property to MATCH on, and value. */
    public static final class NodeRef {
        /** The label carrying the uniqueness constraint (e.g. "JSymbol", "JCompilationUnit"). */
        public final String label;
        /** "id" | "file_key" | "name". */
        public final String keyProp;
        public final String value;

        public NodeRef(String label, String keyProp, String value) {
            this.label = label;
            this.keyProp = keyProp;
            this.value = value;
        }
    }

    public static final class NodeRow {
        /** labels[0] is the constrained MERGE label; the rest are SET as extra labels. */
        public final List<String> labels;
        public final String keyProp;
        public final String value;
        public final Map<String, Object> props;

        public NodeRow(List<String> labels, String keyProp, String value, Map<String, Object> props) {
            this.labels = labels;
            this.keyProp = keyProp;
            this.value = value;
            this.props = props;
        }
    }

    public static final class EdgeRow {
        public final String type;
        public final NodeRef from;
        public final NodeRef to;
        public final Map<String, Object> props;

        public EdgeRow(String type, NodeRef from, NodeRef to, Map<String, Object> props) {
            this.type = type;
            this.from = from;
            this.to = to;
            this.props = props;
        }
    }
}
