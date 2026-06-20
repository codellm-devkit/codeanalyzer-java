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

import com.ibm.cldk.neo4j.GraphRows.EdgeRow;
import com.ibm.cldk.neo4j.GraphRows.NodeRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The snapshot writer: render {@link GraphRows} to a self-contained {@code .cypher} script. Running
 * it (e.g. {@code cypher-shell < graph.cypher}) rebuilds this project's subgraph from scratch —
 * constraints, a scoped wipe of the prior version, then batched {@code UNWIND … MERGE} for nodes
 * and edges.
 *
 * <p>This artifact is intentionally NOT incremental: a static script has no view of the live DB, so
 * it expresses the full truth. Incremental updates are the {@link BoltWriter}'s job.
 */
public final class CypherWriter {

    private static final int BATCH = 500;
    static final String DESCENDANTS = "[:DECLARES_TYPE|HAS_NESTED_TYPE|HAS_CALLABLE|HAS_FIELD|HAS_PARAMETER"
            + "|HAS_CALLSITE|DECLARES_VAR|HAS_ENUM_CONSTANT|HAS_RECORD_COMPONENT*1..]";

    private CypherWriter() {}

    public static String renderCypher(GraphRows rows, String appName) {
        List<String> out = new ArrayList<>();

        out.add("// ── constraints & indexes ──");
        for (String stmt : Schema.CONSTRAINTS) {
            out.add(stmt + ";");
        }
        for (String stmt : Schema.INDEXES) {
            out.add(stmt + ";");
        }

        out.add("");
        out.add("// ── wipe this project's prior subgraph (packages/annotations are shared) ──");
        out.add(wipe(appName));

        out.add("");
        out.add("// ── nodes ──");
        out.addAll(nodeStatements(rows.nodes));

        out.add("");
        out.add("// ── relationships ──");
        out.addAll(edgeStatements(rows.edges));

        out.add("");
        return String.join("\n", out);
    }

    private static String wipe(String appName) {
        return "MATCH (a:Application {name: " + cypherValue(appName) + "})\n"
                + "OPTIONAL MATCH (a)-[:HAS_UNIT]->(c:CompilationUnit)\n"
                + "OPTIONAL MATCH (c)-" + DESCENDANTS + "->(x)\n"
                + "DETACH DELETE x, c, a;";
    }

    // ----------------------------------------------------------------------------------------------
    // Nodes — grouped by their full label set + key property, batched into UNWIND lists.
    // ----------------------------------------------------------------------------------------------

    private static List<String> nodeStatements(List<NodeRow> nodes) {
        Map<String, List<NodeRow>> groups = new LinkedHashMap<>();
        for (NodeRow n : nodes) {
            String k = String.join(":", n.labels) + "|" + n.keyProp;
            groups.computeIfAbsent(k, x -> new ArrayList<>()).add(n);
        }

        List<String> blocks = new ArrayList<>();
        for (List<NodeRow> group : groups.values()) {
            NodeRow head = group.get(0);
            String mergeLabel = head.labels.get(0);
            List<String> extra = head.labels.subList(1, head.labels.size());
            String setLabels = extra.isEmpty() ? "" : ", n:" + String.join(":", extra);
            for (List<NodeRow> batch : chunk(group, BATCH)) {
                List<String> list = new ArrayList<>();
                for (NodeRow n : batch) {
                    list.add("  {k: " + cypherValue(n.value) + ", p: " + cypherMap(n.props) + "}");
                }
                blocks.add("UNWIND [\n" + String.join(",\n", list) + "\n] AS row\n"
                        + "MERGE (n:" + mergeLabel + " {" + head.keyProp + ": row.k})\n"
                        + "SET n += row.p" + setLabels + ";");
            }
        }
        return blocks;
    }

    // ----------------------------------------------------------------------------------------------
    // Edges — grouped by (type, endpoint labels + key props), batched.
    // ----------------------------------------------------------------------------------------------

    private static List<String> edgeStatements(List<EdgeRow> edges) {
        Map<String, List<EdgeRow>> groups = new LinkedHashMap<>();
        for (EdgeRow e : edges) {
            String k = e.type + "|" + e.from.label + "." + e.from.keyProp + "|" + e.to.label + "." + e.to.keyProp;
            groups.computeIfAbsent(k, x -> new ArrayList<>()).add(e);
        }

        List<String> blocks = new ArrayList<>();
        for (List<EdgeRow> group : groups.values()) {
            EdgeRow head = group.get(0);
            for (List<EdgeRow> batch : chunk(group, BATCH)) {
                List<String> list = new ArrayList<>();
                for (EdgeRow e : batch) {
                    list.add("  {f: " + cypherValue(e.from.value) + ", t: " + cypherValue(e.to.value)
                            + ", p: " + cypherMap(e.props) + "}");
                }
                blocks.add("UNWIND [\n" + String.join(",\n", list) + "\n] AS row\n"
                        + "MATCH (a:" + head.from.label + " {" + head.from.keyProp + ": row.f})\n"
                        + "MATCH (b:" + head.to.label + " {" + head.to.keyProp + ": row.t})\n"
                        + "MERGE (a)-[r:" + head.type + "]->(b)\n"
                        + "SET r += row.p;");
            }
        }
        return blocks;
    }

    // ----------------------------------------------------------------------------------------------
    // Cypher literal rendering
    // ----------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    static String cypherValue(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof String) {
            return cypherString((String) v);
        }
        if (v instanceof Boolean) {
            return ((Boolean) v) ? "true" : "false";
        }
        if (v instanceof Number) {
            return v.toString();
        }
        if (v instanceof List) {
            List<Object> list = (List<Object>) v;
            List<String> parts = new ArrayList<>();
            for (Object x : list) {
                parts.add(cypherValue(x));
            }
            return "[" + String.join(", ", parts) + "]";
        }
        return cypherString(v.toString());
    }

    static String cypherMap(Map<String, Object> props) {
        List<String> entries = new ArrayList<>();
        for (Map.Entry<String, Object> e : props.entrySet()) {
            entries.add(e.getKey() + ": " + cypherValue(e.getValue()));
        }
        return "{" + String.join(", ", entries) + "}";
    }

    private static String cypherString(String s) {
        String escaped = s.replace("\\", "\\\\").replace("'", "\\'")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        return "'" + escaped + "'";
    }

    static <T> List<List<T>> chunk(List<T> items, int size) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i += size) {
            out.add(new ArrayList<>(items.subList(i, Math.min(i + size, items.size()))));
        }
        return out;
    }
}
