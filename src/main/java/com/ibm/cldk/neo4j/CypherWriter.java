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
import java.io.IOException;
import java.io.StringWriter;
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
    /**
     * Every containment relationship either graph generation emits — v1 (unit-rooted) and v2
     * (module-rooted) together, so a v2 push wipes/prunes a prior v1 graph of the same app and vice
     * versa (spec: one app name = one graph, latest push wins). {@code :Package}, {@code :Artifact}
     * and {@code :ConfigKey} are all deliberately unreachable from this wipe, same as
     * {@code :JPackage}/{@code :JAnnotation} below: all three are un-prefixed cross-language merge
     * targets ({@code CanId.artifactId}'s own javadoc: the {@code artifact} id segment exists so a
     * sibling-language analyzer scanning the same repository lands on the same node rather than a
     * duplicate). That other analyzer's own edges may be attached to the very node this wipe would
     * {@code DETACH DELETE}, destroying data this tool cannot see and did not write — corruption
     * neither detectable nor repairable from here. A stale {@code :Artifact}/{@code :ConfigKey}
     * left behind after a file is removed from the analyzed repo is the accepted tradeoff instead:
     * recoverable with a full re-push or a separate sweep, unlike another tool's silently deleted
     * edges. (Tried once, reverted: see git history — widening this to reach {@code :Artifact}/
     * {@code :ConfigKey} was implemented and shipped before this exact hazard was caught in
     * review.)
     */
    static final String DESCENDANTS = "[:J_DECLARES_TYPE|J_HAS_NESTED_TYPE|J_HAS_CALLABLE|J_HAS_FIELD|J_HAS_PARAMETER"
            + "|J_HAS_CALLSITE|J_DECLARES_VAR|J_HAS_ENUM_CONSTANT|J_HAS_RECORD_COMPONENT|J_HAS_INIT_BLOCK"
            + "|J_HAS_CRUD_OPERATION|J_HAS_CRUD_QUERY|J_HAS_COMMENT"
            + "|J_DECLARES|J_HAS_METHOD|J_HAS_BODY_NODE*1..]";

    private CypherWriter() {}

    /**
     * Render the whole script into a {@code String}. Convenience for callers holding a small graph
     * (tests, diagnostics); it delegates to {@link #writeCypher} so the two can never drift.
     *
     * <p><b>Prefer {@link #writeCypher} for anything user-facing.</b> A large repository's script
     * exceeds the JVM's maximum {@code String} length and this method then throws
     * {@code OutOfMemoryError: Requested string length exceeds VM limit} — a ceiling on one array,
     * not a heap shortage, so no {@code -Xmx} avoids it (#209).
     */
    public static String renderCypher(GraphRows rows, String appName) {
        StringWriter out = new StringWriter();
        try {
            writeCypher(out, rows, appName);
        } catch (IOException impossible) {
            // StringWriter never throws; it only declares IOException to satisfy Writer.
            throw new IllegalStateException(impossible);
        }
        return out.toString();
    }

    /**
     * Stream the script to {@code out}, one statement at a time. Nothing larger than a single batch
     * is ever held as a {@code String}, so peak memory is bounded by {@link #BATCH} rather than by
     * the size of the graph.
     *
     * <p>{@code out} is written to incrementally and is not flushed or closed here — the caller owns
     * it, and should hand in a buffered writer so that per-statement writes do not become
     * per-statement syscalls.
     */
    public static void writeCypher(Appendable out, GraphRows rows, String appName) throws IOException {
        Statements s = new Statements(out);
        s.add("// ── constraints & indexes ──");
        for (String stmt : Schema.CONSTRAINTS) {
            s.add(stmt + ";");
        }
        for (String stmt : Schema.INDEXES) {
            s.add(stmt + ";");
        }

        s.add("");
        s.add("// ── wipe this project's prior subgraph (packages/annotations/artifacts/config keys are shared) ──");
        s.add(wipe(appName));

        s.add("");
        s.add("// ── nodes ──");
        writeNodeStatements(s, rows.nodes);

        s.add("");
        s.add("// ── relationships ──");
        writeEdgeStatements(s, rows.edges);

        s.add("");
    }

    /**
     * Writes statements separated by newlines, exactly as {@code String.join("\n", ...)} did:
     * the separator goes BETWEEN statements, so the script does not gain a trailing newline the
     * rendered form never had. Emitting {@code statement + "\n"} each time would append one extra
     * byte at the end — small, but this file is compared byte-for-byte across versions.
     */
    private static final class Statements {
        private final Appendable out;
        private boolean first = true;

        Statements(Appendable out) {
            this.out = out;
        }

        void add(String statement) throws IOException {
            if (!first) {
                out.append('\n');
            }
            first = false;
            out.append(statement);
        }
    }

    private static String wipe(String appName) {
        // The unit hop is unlabeled and lists both generations' rel types (v1 J_HAS_UNIT →
        // :JCompilationUnit, v2 J_HAS_MODULE → :JModule) so either generation's push replaces
        // whichever generation the DB currently holds for this app. Deliberately does NOT include
        // HAS_ARTIFACT → :Artifact: see DESCENDANTS' javadoc for why the whole artifact/config-key
        // subtree stays outside every wipe this class runs. The second statement sweeps
        // fully-isolated :JSymbol nodes the containment traversal cannot reach — v1's
        // import-materialized bodyless :JType stubs hang off units via J_IMPORTS only, so the
        // DETACH DELETE above orphans them; degree-0 symbols are unreferencable junk in any
        // generation, and a symbol another application still uses keeps its edges and survives.
        return "MATCH (a:JApplication {name: " + cypherValue(appName) + "})\n"
                + "OPTIONAL MATCH (a)-[:J_HAS_UNIT|J_HAS_MODULE]->(c)\n"
                + "OPTIONAL MATCH (c)-" + DESCENDANTS + "->(x)\n"
                + "DETACH DELETE x, c, a;\n"
                + "MATCH (s:JSymbol) WHERE NOT (s)--() DELETE s;";
    }

    // ----------------------------------------------------------------------------------------------
    // Nodes — grouped by their full label set + key property, batched into UNWIND lists.
    // ----------------------------------------------------------------------------------------------

    private static void writeNodeStatements(Statements out, List<NodeRow> nodes) throws IOException {
        Map<String, List<NodeRow>> groups = new LinkedHashMap<>();
        for (NodeRow n : nodes) {
            String k = String.join(":", n.labels) + "|" + n.keyProp;
            groups.computeIfAbsent(k, x -> new ArrayList<>()).add(n);
        }

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
                out.add("UNWIND [\n" + String.join(",\n", list) + "\n] AS row\n"
                        + "MERGE (n:" + mergeLabel + " {" + head.keyProp + ": row.k})\n"
                        + "SET n += row.p" + setLabels + ";");
            }
        }
    }

    // ----------------------------------------------------------------------------------------------
    // Edges — grouped by (type, endpoint labels + key props), batched.
    // ----------------------------------------------------------------------------------------------

    private static void writeEdgeStatements(Statements out, List<EdgeRow> edges) throws IOException {
        Map<String, List<EdgeRow>> groups = new LinkedHashMap<>();
        for (EdgeRow e : edges) {
            String k = e.type + "|" + e.from.label + "." + e.from.keyProp + "|" + e.to.label + "." + e.to.keyProp
                    + "|" + (e.key != null);
            groups.computeIfAbsent(k, x -> new ArrayList<>()).add(e);
        }

        for (List<EdgeRow> group : groups.values()) {
            EdgeRow head = group.get(0);
            boolean keyed = head.key != null;
            for (List<EdgeRow> batch : chunk(group, BATCH)) {
                List<String> list = new ArrayList<>();
                for (EdgeRow e : batch) {
                    list.add("  {f: " + cypherValue(e.from.value) + ", t: " + cypherValue(e.to.value)
                            + (keyed ? ", k: " + cypherValue(e.key) : "")
                            + ", p: " + cypherMap(e.props) + "}");
                }
                out.add("UNWIND [\n" + String.join(",\n", list) + "\n] AS row\n"
                        + "MATCH (a:" + head.from.label + " {" + head.from.keyProp + ": row.f})\n"
                        + "MATCH (b:" + head.to.label + " {" + head.to.keyProp + ": row.t})\n"
                        + "MERGE (a)-[r:" + head.type + (keyed ? " {_k: row.k}" : "") + "]->(b)\n"
                        + "SET r += row.p;");
            }
        }
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
