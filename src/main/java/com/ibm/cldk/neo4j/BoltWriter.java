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
import com.ibm.cldk.utils.Log;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Session;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Values;

/**
 * The incremental writer: push {@link GraphRows} into a live Neo4j over Bolt. Unlike the snapshot
 * writer, this one reads the DB's current state and updates only what changed.
 *
 * <p>Algorithm (the compilation-unit subgraph is the unit of idempotent replacement):
 * <ol>
 *   <li>ensure constraints + indexes.</li>
 *   <li>diff each unit's {@code content_hash} against the DB → the set of changed units.</li>
 *   <li>per changed unit, in a transaction: delete the edges it owned (edges out of its nodes),
 *       detach-delete the declarations it no longer emits, then upsert its current nodes.</li>
 *   <li>upsert edges owned by changed units (+ the shared edges).</li>
 *   <li>on a FULL run only, prune units whose source file vanished.</li>
 * </ol>
 *
 * <p>Nodes are MERGE-upserted, never blindly deleted, so a declaration another (unchanged) unit
 * still references survives and its incoming edges stay valid. {@code :JPackage}/{@code :JAnnotation}
 * are shared (no {@code _module}) and are MERGE-only.
 */
public final class BoltWriter implements BoltSink {
    /**
     * The v2 purge: scoped by the module's own {@code can://} id rather than by a bare file key.
     *
     * <p>The id is a path — {@code can://java/<app>/<file>} — so matching the module itself by
     * equality and its declarations by {@code id STARTS WITH <id> + '/'} is containment, and it is
     * simultaneously scoped to one language, one application and one module. That last one is what
     * neither a label anchor nor {@code _module} could give: two java applications sharing
     * {@code src/main/java/Foo.java} have identical labels and an identical file key, and only the
     * id distinguishes them (.github#50).
     *
     * <p>The trailing slash is not cosmetic. A bare {@code STARTS WITH <id>} would also match
     * {@code <id>Xtra}, so descendants are matched on the separator and the module on equality.
     *
     * <p>{@code :JCanNode} is present only so the prefix predicate can seek: Neo4j property indexes
     * are label-scoped, and without a label this scans the whole node store.
     */
    static final String PURGE_MODULE_EDGES_V2 =
            "MATCH (x:" + RowBuilder.CAN_NODE + ") WHERE x.id = $mid OR x.id STARTS WITH $pre "
                    + "MATCH (x)-[r]->() DELETE r";

    /** @see #PURGE_MODULE_EDGES_V2 */
    static final String PURGE_VANISHED_NODES_V2 =
            "MATCH (x:" + RowBuilder.CAN_NODE + ") WHERE (x.id = $mid OR x.id STARTS WITH $pre) "
                    + "AND NOT x.id IN $keys DETACH DELETE x";

    /**
     * The prefix that matches a module's declarations but not a sibling module whose path merely
     * starts the same way. The separator is the whole point: {@code can://java/app/src/Foo.java}
     * is also a prefix of {@code can://java/app/src/Foo.javaX}, so a bare {@code STARTS WITH} on
     * the module id would purge a different module's nodes. The module itself is matched by
     * equality instead, since its own id does not end in a separator.
     */
    static String descendantPrefix(String moduleId) {
        return moduleId + "/";
    }

    /**
     * The {@code can://} id of the module these rows belong to, or {@code null} on the v1 path.
     * Taken from the module's own row rather than parsed out of a declaration's id, because a file
     * key may itself contain {@code /} and so cannot be recovered by splitting.
     */
    private static String canModuleIdOf(List<NodeRow> nodes) {
        for (NodeRow n : nodes) {
            if (n.labels.contains("JModule") && RowBuilder.isCanId(n.value)) {
                return n.value;
            }
        }
        return null;
    }


    private static final int BATCH = 1000;

    /** Public no-arg constructor: {@link Neo4jEmitter} instantiates this reflectively via {@link BoltSink}. */
    public BoltWriter() {}

    @Override
    public void write(GraphRows rows, BoltConfig cfg, boolean fullRun) {
        try (Driver driver = GraphDatabase.driver(cfg.uri, AuthTokens.basic(cfg.user, cfg.password))) {
            new Runner(driver, cfg.database).run(rows, fullRun);
        }
    }

    private static final class Runner {
        private final Driver driver;
        private final String database;

        Runner(Driver driver, String database) {
            this.driver = driver;
            this.database = database;
        }

        private Session session() {
            return database != null
                    ? driver.session(SessionConfig.forDatabase(database))
                    : driver.session();
        }

        void run(GraphRows rows, boolean fullRun) {
            // 1. schema (DDL runs in its own autocommit transactions).
            try (Session s = session()) {
                for (String stmt : Schema.CONSTRAINTS) {
                    s.run(stmt);
                }
                for (String stmt : Schema.INDEXES) {
                    s.run(stmt);
                }
            }

            // Partition nodes by owning unit; shared nodes have no _module.
            Map<String, List<NodeRow>> byUnit = new LinkedHashMap<>();
            List<NodeRow> shared = new ArrayList<>();
            Map<String, String> unitOf = new HashMap<>(); // node value → owning unit
            for (NodeRow n : rows.nodes) {
                if (n.moduleKey != null) {
                    byUnit.computeIfAbsent(n.moduleKey, x -> new ArrayList<>()).add(n);
                    unitOf.put(n.value, n.moduleKey);
                } else {
                    shared.add(n);
                }
            }

            // 2. diff content_hash. Both generations key their per-file node on file_key: v1
            // :JCompilationUnit and v2 :JModule.
            Map<String, String> dbHash = new HashMap<>();
            try (Session s = session()) {
                s.run("MATCH (c) WHERE c:JCompilationUnit OR c:JModule "
                                + "RETURN c.file_key AS k, c.content_hash AS h").list()
                        .forEach(rec -> dbHash.put(rec.get("k").asString(null), rec.get("h").asString(null)));
            }
            Set<String> changed = new HashSet<>();
            for (Map.Entry<String, List<NodeRow>> e : byUnit.entrySet()) {
                String unit = e.getKey();
                String rowHash = hashOf(e.getValue(), unit);
                if (!dbHash.containsKey(unit) || rowHash == null || !rowHash.equals(dbHash.get(unit))) {
                    changed.add(unit);
                }
            }
            Log.info("neo4j(bolt): " + byUnit.size() + " units (" + changed.size() + " changed), "
                    + shared.size() + " shared nodes, " + rows.edges.size() + " edges");

            // 3. shared nodes are always upserted (MERGE-only).
            upsertNodes(shared);

            // 4. per changed unit: purge owned edges + vanished decls, then upsert its nodes.
            for (String unit : changed) {
                List<NodeRow> nodes = byUnit.get(unit);
                List<String> keys = new ArrayList<>();
                for (NodeRow n : nodes) {
                    keys.add(n.value);
                }
                try (Session s = session()) {
                    s.writeTransaction(tx -> {
                        String moduleId = canModuleIdOf(nodes);
                        if (moduleId == null) {
                            // Legacy v1 rows: FQN-shaped ids carry no application segment, so there
                            // is no prefix to scope by. v2 is the schema as of 3.0.0, so rather than
                            // carry a second scoping mechanism for a legacy path, the purge is
                            // skipped and said so. Nodes are still upserted -- a v1 push updates, it
                            // just never deletes.
                            Log.info("neo4j(bolt): legacy v1 rows for " + unit
                                    + " - purge skipped (v1 ids carry no application scope)");
                            return null;
                        }
                        // The module's own can:// id scopes this to one module of one application of
                        // one language at once, because the id is a path and a prefix match on it is
                        // containment. `_module` could not: it is a bare project-relative file key,
                        // so two applications sharing a source path purged each other (.github#50).
                        Map<String, Object> args = new LinkedHashMap<>();
                        args.put("mid", moduleId);
                        args.put("pre", descendantPrefix(moduleId));
                        args.put("keys", keys);
                        tx.run(PURGE_MODULE_EDGES_V2, args);
                        tx.run(PURGE_VANISHED_NODES_V2, args);
                        return null;
                    });
                }
                upsertNodes(nodes);
            }

            // 5. upsert edges owned by a changed unit (owner = source node's unit) or shared.
            List<EdgeRow> edges = new ArrayList<>();
            for (EdgeRow e : rows.edges) {
                String owner = unitOf.get(e.from.value);
                if (owner == null || changed.contains(owner)) {
                    edges.add(e);
                }
            }
            upsertEdges(edges);

            // 6. orphan prune — only safe on a full run. Reaches both generations' per-file nodes
            // through the shared application anchor, so a v2 push also prunes a prior v1 graph's
            // units (and other applications in the database are never touched).
            if (fullRun) {
                List<String> present = new ArrayList<>(byUnit.keySet());
                String app = appNameOf(rows);
                // Checked against the same hazard as the _module purges above (#213) and found
                // sound, so deliberately left alone: this traversal never leaves java's own graph.
                // It enters at :JApplication (java-owned, and scoped to this app by name), hops a
                // java-owned relationship type, and expands only through DESCENDANTS -- every
                // member of which is J_-prefixed containment. `c` and `x` are unlabelled but
                // unreachable except along those edges, and DESCENDANTS deliberately excludes
                // HAS_ARTIFACT, so no cross-language :Artifact/:ConfigKey and no shared
                // :JPackage/:JAnnotation can be reached. Do not add a non-containment type to
                // DESCENDANTS without re-checking that.
                try (Session s = session()) {
                    long pruned = s.run("MATCH (:JApplication {name: $app})-[:J_HAS_UNIT|J_HAS_MODULE]->(c) "
                                    + "WHERE NOT c.file_key IN $present "
                                    + "OPTIONAL MATCH (c)-" + CypherWriter.DESCENDANTS + "->(x) "
                                    + "DETACH DELETE x, c RETURN count(c) AS pruned",
                            Values.parameters("present", present, "app", app)).single().get("pruned").asLong(0);
                    Log.info("neo4j(bolt): pruned " + pruned + " vanished unit(s)");
                }
            } else {
                Log.info("neo4j(bolt): targeted run — orphan pruning skipped (deleted files not removed)");
            }
        }

        private void upsertNodes(List<NodeRow> nodes) {
            Map<String, List<NodeRow>> groups = new LinkedHashMap<>();
            for (NodeRow n : nodes) {
                groups.computeIfAbsent(String.join(":", n.labels) + "|" + n.keyProp, x -> new ArrayList<>()).add(n);
            }
            for (List<NodeRow> group : groups.values()) {
                NodeRow head = group.get(0);
                List<String> extra = head.labels.subList(1, head.labels.size());
                String setLabels = extra.isEmpty() ? "" : ", n:" + String.join(":", extra);
                String cypher = "UNWIND $rows AS row MERGE (n:" + head.labels.get(0) + " {"
                        + head.keyProp + ": row.k}) SET n += row.p" + setLabels;
                for (List<NodeRow> batch : CypherWriter.chunk(group, BATCH)) {
                    List<Map<String, Object>> payload = new ArrayList<>();
                    for (NodeRow n : batch) {
                        Map<String, Object> r = new HashMap<>();
                        r.put("k", n.value);
                        r.put("p", n.props);
                        payload.add(r);
                    }
                    try (Session s = session()) {
                        s.run(cypher, Values.parameters("rows", payload));
                    }
                }
            }
        }

        private void upsertEdges(List<EdgeRow> edges) {
            Map<String, List<EdgeRow>> groups = new LinkedHashMap<>();
            for (EdgeRow e : edges) {
                String k = e.type + "|" + e.from.label + "." + e.from.keyProp + "|" + e.to.label + "." + e.to.keyProp
                        + "|" + (e.key != null);
                groups.computeIfAbsent(k, x -> new ArrayList<>()).add(e);
            }
            for (List<EdgeRow> group : groups.values()) {
                EdgeRow head = group.get(0);
                boolean keyed = head.key != null;
                String cypher = "UNWIND $rows AS row "
                        + "MATCH (a:" + head.from.label + " {" + head.from.keyProp + ": row.f}) "
                        + "MATCH (b:" + head.to.label + " {" + head.to.keyProp + ": row.t}) "
                        + "MERGE (a)-[r:" + head.type + (keyed ? " {_k: row.k}" : "") + "]->(b) SET r += row.p";
                for (List<EdgeRow> batch : CypherWriter.chunk(group, BATCH)) {
                    List<Map<String, Object>> payload = new ArrayList<>();
                    for (EdgeRow e : batch) {
                        Map<String, Object> r = new HashMap<>();
                        r.put("f", e.from.value);
                        r.put("t", e.to.value);
                        if (keyed) {
                            r.put("k", e.key);
                        }
                        r.put("p", e.props);
                        payload.add(r);
                    }
                    try (Session s = session()) {
                        s.run(cypher, Values.parameters("rows", payload));
                    }
                }
            }
        }

        private static String hashOf(List<NodeRow> nodes, String fileKey) {
            for (NodeRow n : nodes) {
                String label = n.labels.get(0);
                boolean fileNode = label.equals("JCompilationUnit")
                        || (label.equals("JModule") && fileKey.equals(n.props.get("file_key")));
                if (fileNode && (n.value.equals(fileKey) || fileKey.equals(n.props.get("file_key")))) {
                    Object h = n.props.get("content_hash");
                    return h instanceof String ? (String) h : null;
                }
            }
            return null;
        }

        /** The application anchor's name — the one {@code :JApplication} row every projection emits. */
        private static String appNameOf(GraphRows rows) {
            for (NodeRow n : rows.nodes) {
                if (n.labels.get(0).equals("JApplication")) {
                    return n.value;
                }
            }
            return "application";
        }
    }
}
