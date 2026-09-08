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
import com.ibm.cldk.schema.CanId;
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
     * versa (spec: one app name = one graph, latest push wins).
     *
     * <p>This traversal is the <b>only</b> thing that reaches a v1 graph, and that is why
     * {@link #wipe}'s {@code can://} prefix sweep cannot replace it: v1 ids are fully-qualified
     * names carrying no application segment, and {@code GraphProjector.java} keys
     * {@code :JApplication} on {@code name}, so no prefix predicate sees a single v1 node. Folding
     * the two into one prefix sweep was tried on this branch and reverted; it orphans every v1
     * graph.
     *
     * <p>{@code HAS_ARTIFACT} (and the {@code :Package} edges {@code DECLARES_DEPENDENCY} /
     * {@code LOCKS}) stay out of this list — but no longer because the artifact subtree is
     * untouchable. {@link #wipe}'s prefix sweep now <i>does</i> reach this application's
     * {@code :Artifact}/{@code :ConfigKey} nodes, deliberately; see its javadoc. They stay out of
     * {@code DESCENDANTS} because this constant is shared with
     * {@link BoltWriter#PRUNE_VANISHED_UNITS_V2}, whose unit of deletion is one vanished
     * compilation unit, not the application: an app-level {@code :Artifact} is not a descendant of
     * any one unit and must not die with one. {@code :Package} ({@code pkg:} purls) is outside both
     * — it is not under the app prefix either — as are the shared {@code :JPackage}/
     * {@code :JAnnotation} merge targets.
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
        // Migrations first: a constraint an older release created on a property this generation no
        // longer keys on must be dropped BEFORE the load, or it is still live and enforcing.
        s.add("// ── migrations ──");
        for (String stmt : Schema.MIGRATIONS) {
            s.add(stmt + ";");
        }

        s.add("");
        s.add("// ── constraints & indexes ──");
        for (String stmt : Schema.CONSTRAINTS) {
            s.add(stmt + ";");
        }
        for (String stmt : Schema.INDEXES) {
            s.add(stmt + ";");
        }

        s.add("");
        s.add("// ── wipe this project's prior subgraph (packages/annotations are shared and survive; "
                + "artifacts/config keys are inside the app prefix and get rebuilt) ──");
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

    /**
     * {@code can://<app>/} — the scope of the prefix sweep. Refuses an empty application name:
     * {@code STARTS WITH ''} matches every node in the database, so the statement would stop being
     * scoped at all. Mirrors {@code codeanalyzer-python}'s {@code application_prefix}.
     */
    static String applicationPrefix(String appName) {
        if (appName == null || appName.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "neo4j: refusing a destructive statement without an application id");
        }
        // The trailing separator is not cosmetic: a bare `STARTS WITH 'can://app'` would also match
        // `can://appX`, a different application.
        return CanId.applicationId(appName) + "/";
    }

    /**
     * The application root by equality plus everything under {@code can://<app>/}, then the same
     * application's prior <b>v1</b> graph by containment, then the orphan sweep.
     *
     * <p><b>Statement 1, the prefix sweep.</b> Scoped by id, so it is one application by
     * construction: a second java app sharing a source path, and a sibling analyzer's own
     * {@code can://} graph under a different app, are outside it. {@code :Package} nodes
     * ({@code pkg:} purls) stay outside too — they are not under the app prefix. Two things moved
     * with the app-outermost grammar: the root is matched by its {@code can://<app>} id rather than
     * by the free-text {@code --app-name}, so the id the graph is actually keyed on is the id the
     * wipe uses; and this application's {@code :Artifact}/{@code :ConfigKey} nodes are now
     * <i>inside</i> the prefix, so the snapshot rebuilds them instead of leaving them to accumulate
     * forever with nothing ever cleaning them up. That is deliberate, and it is the one behavioural
     * widening here: a cross-language edge into a shared {@code :Artifact} is dropped by a java
     * snapshot and restored on that analyzer's next push.
     *
     * <p><b>Statement 2, the containment traversal.</b> Not redundant with the sweep above and not
     * foldable into it — see {@link #DESCENDANTS}. v1 ids are fully-qualified names, so the prefix
     * predicate cannot reach a v1 node; this hop is what makes "one app name = one graph, latest
     * push wins" hold across generations. The unit hop is unlabeled and lists both generations' rel
     * types (v1 {@code J_HAS_UNIT} → {@code :JCompilationUnit}, v2 {@code J_HAS_MODULE} →
     * {@code :JModule}) so either generation's push replaces whichever the DB currently holds. The
     * root is matched by id when it is v2 (exact, unique-constrained) OR by name when it is a legacy
     * v1 root — identifiable because v1 never writes {@code id}. An id-only match would orphan a
     * prior v1 graph instead of replacing it, breaking the very cross-generation guarantee this
     * exists for. The {@code a.id IS NULL} guard confines the name branch to those legacy roots; an
     * ungated {@code a.name =} disjunct would additionally reach every v2 root carrying this display
     * name, including roots this analyzer never minted, so do not widen it.
     *
     * <p><b>Statement 3, the orphan sweep.</b> Fully-isolated {@code :JSymbol} nodes the containment
     * traversal cannot reach — v1's import-materialized bodyless {@code :JType} stubs hang off units
     * via {@code J_IMPORTS} only, so the deletes above orphan them. Degree-0 symbols are
     * unreferencable junk in any generation, and a symbol another application still uses keeps its
     * edges and survives.
     *
     * <p>What none of this does — and must not be documented as doing — is separate two distinct
     * applications sharing an {@code --app-name}. {@code CanId.applicationId} is
     * {@code "can://" + appName}, so they share an id byte-for-byte and are ONE node; there is
     * nothing here to tell apart. Distinct services need distinct {@code --app-name} values, not a
     * wider predicate.
     */
    private static String wipe(String appName) {
        String appId = cypherValue(CanId.applicationId(appName));
        return "MATCH (x:" + RowBuilder.CAN_NODE + ") WHERE x.id = " + appId
                + " OR x.id STARTS WITH " + cypherValue(applicationPrefix(appName)) + "\n"
                + "CALL { WITH x DETACH DELETE x } IN TRANSACTIONS OF 1000 ROWS;\n"
                + "MATCH (a:JApplication) WHERE a.id = " + appId
                + " OR (a.id IS NULL AND a.name = " + cypherValue(appName) + ")\n"
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
