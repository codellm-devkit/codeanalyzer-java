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
 * <p>A full run also reconciles the app's artifacts; see {@link #RELEASE_VANISHED_ARTIFACTS}.
 *
 * <p>Nodes are MERGE-upserted, never blindly deleted, so a declaration another (unchanged) unit
 * still references survives and its incoming edges stay valid. {@code :JPackage}/{@code :JAnnotation}
 * are shared (no {@code _module}) and are MERGE-only.
 */
public final class BoltWriter implements BoltSink {
    /**
     * The v2 purge: scoped by the module's own {@code can://} id rather than by a bare file key.
     *
     * <p>The id is a path — {@code can://<app>/java/<file>} — so matching the module itself by
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

    /** Hashes and orphan pruning are confined to the selected canonical application anchor. */
    static final String MODULE_HASHES_V2 = "MATCH (a:JApplication {id: $appId})-[:J_HAS_MODULE]->(c:JModule) "
            + "RETURN c.file_key AS k, c.content_hash AS h";

    static final String PRUNE_VANISHED_UNITS_V2 =
            "MATCH (a:JApplication)-[:J_HAS_UNIT|J_HAS_MODULE]->(c) "
                    + "WHERE a.id = $appId "
                    + "AND NOT c.file_key IN $present "
                    + "OPTIONAL MATCH (c)-" + CypherWriter.DESCENDANTS + "->(x) "
                    + "DETACH DELETE x, c RETURN count(c) AS pruned";

    static final String PRUNE_VANISHED_UNITS_V1 =
            "MATCH (a:JApplication)-[:J_HAS_UNIT]->(c) "
                    + "WHERE a.id IS NULL AND a.name = $appName "
                    + "AND NOT c.file_key IN $present "
                    + "OPTIONAL MATCH (c)-" + CypherWriter.DESCENDANTS + "->(x) "
                    + "DETACH DELETE x, c RETURN count(c) AS pruned";

    static String orphanPruneQuery(GraphRows rows) {
        return CypherWriter.legacyApplicationName(rows) != null
                ? PRUNE_VANISHED_UNITS_V1 : PRUNE_VANISHED_UNITS_V2;
    }

    /**
     * Drop every Java-owned edge on this app's artifacts and config keys so the run rebuilds them:
     * {@code HAS_ARTIFACT} from a {@code :JApplication}, {@code DECLARES_DEPENDENCY}/{@code LOCKS}
     * to {@code pkg:maven/}, and incoming {@code J_} edges ({@code J_USES_CONFIG},
     * {@code J_DISPATCHES_TO}). Not sha-diffed: siblings overwrite the shared {@code sha256}, and a
     * config change alters code edges while every module's hash stays put.
     */
    static final String PURGE_ARTIFACT_EDGES =
            "UNWIND $pres AS pre "
                    + "MATCH (t:" + RowBuilder.CAN_NODE + ") WHERE t.id STARTS WITH pre AND (t:Artifact OR t:ConfigKey) "
                    + "CALL { WITH t "
                    + "WITH [(t)<-[r]-(s) WHERE (type(r) = 'HAS_ARTIFACT' AND s:JApplication) "
                    + "OR type(r) STARTS WITH 'J_' | r] "
                    + "+ [(t)-[d:DECLARES_DEPENDENCY|LOCKS]->(p:Package) WHERE p.id STARTS WITH 'pkg:maven/' | d] AS rs "
                    + "FOREACH (r IN rs | DELETE r) } IN TRANSACTIONS OF 1000 ROWS";

    /** @see #PURGE_ARTIFACT_EDGES */
    static final String PURGE_UNRESOLVED_CONFIG_READS =
            "MATCH (:JApplication {id: $appId})-[r:J_READS_CONFIG_UNRESOLVED]->() "
                    + "CALL { WITH r DELETE r } IN TRANSACTIONS OF 1000 ROWS";

    /**
     * Release Java's claim ({@code :JCanNode}) on artifacts/config keys the run no longer emits,
     * after {@link #PURGE_ARTIFACT_EDGES}. An artifact is deleted once no {@code HAS_ARTIFACT} is
     * left: every analyzer inventories its artifacts with one, marked or not. A config key is
     * deleted once no other analyzer's {@code *CanNode} claims it.
     */
    static final String RELEASE_VANISHED_ARTIFACTS =
            "UNWIND $pres AS pre "
                    + "MATCH (n:" + RowBuilder.CAN_NODE + ") WHERE n.id STARTS WITH pre AND (n:Artifact OR n:ConfigKey) "
                    + "AND NOT n.id IN $keep "
                    + "CALL { WITH n REMOVE n:" + RowBuilder.CAN_NODE + " "
                    + "WITH n WHERE (n:Artifact AND NOT ()-[:HAS_ARTIFACT]->(n)) "
                    + "OR (n:ConfigKey AND none(l IN labels(n) WHERE l ENDS WITH 'CanNode')) "
                    + "DETACH DELETE n } IN TRANSACTIONS OF 1000 ROWS "
                    + "RETURN count(*) AS released";

    /** Only the selected application's artifacts; legacy output requires explicit retirement. */
    static List<String> artifactPrefixes(String appId, String appName) {
        return List.of(appId + "/artifact/");
    }

    /**
     * The prefix that matches a module's declarations but not a sibling module whose path merely
     * starts the same way. The separator is the whole point: {@code can://app/java/src/Foo.java}
     * is also a prefix of {@code can://app/java/src/Foo.javaX}, so a bare {@code STARTS WITH} on
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

    static void requireCompatibleApplicationIdentity(String id, Map<String, Object> incoming, Map<String, Object> stored) {
        Object name = incoming.get("application_name");
        Object namespace = incoming.get("identity_namespace");
        Object version = incoming.get("identity_version");
        Object storedVersion = stored.get("identity_version");
        if (!(name instanceof String) || !(namespace instanceof String)
                || (!(version instanceof Integer) && !(version instanceof Long)) || ((Number) version).longValue() != 2
                || !id.equals(CanId.applicationId((String) name, (String) namespace))
                || (!(storedVersion instanceof Integer) && !(storedVersion instanceof Long)) || ((Number) storedVersion).longValue() != 2
                || !name.equals(stored.get("application_name"))
                || !namespace.equals(stored.get("identity_namespace"))) {
            throw new IllegalArgumentException("Incompatible existing application identity; regenerate graph explicitly");
        }
    }

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
            // Validate the selected canonical anchor before any schema or data mutation.
            for (NodeRow node : rows.nodes) {
                if (!node.labels.contains("JApplication") || !RowBuilder.isCanId(node.value)) continue;
                requireCompatibleApplicationIdentity(node.value, node.props, node.props);
                try (Session s = session()) {
                    var records = s.run("MATCH (a:JApplication {id: $id}) RETURN a.identity_version AS identity_version, "
                                    + "a.application_name AS application_name, a.identity_namespace AS identity_namespace",
                            Values.parameters("id", node.value)).list();
                    for (var record : records) {
                        requireCompatibleApplicationIdentity(node.value, node.props, record.asMap());
                    }
                }
            }
            // 1. schema (DDL runs in its own autocommit transactions). Migrations run FIRST: a
            // constraint an older release created on a property this generation no longer keys on
            // is still live and enforcing, and would fail the very first push (see Schema.MIGRATIONS).
            try (Session s = session()) {
                for (String stmt : Schema.MIGRATIONS) {
                    s.run(stmt);
                }
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
                boolean v2 = rows.nodes.stream().anyMatch(n -> n.labels.contains("JApplication") && RowBuilder.isCanId(n.value));
                String query = v2 ? MODULE_HASHES_V2 : "MATCH (c:JCompilationUnit) RETURN c.file_key AS k, c.content_hash AS h";
                s.run(query, Values.parameters("appId", applicationIdOf(rows))).list()
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

            // 3. full run: reconcile this app's artifacts, then upsert shared nodes (MERGE-only).
            if (fullRun) {
                reconcileArtifacts(rows);
            }
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

            // 5. upsert edges owned by a changed unit (owner = source node's unit) or shared; on a
            // full run also every edge into an artifact/config key, which step 3 purged.
            List<EdgeRow> edges = new ArrayList<>();
            for (EdgeRow e : rows.edges) {
                String owner = unitOf.get(e.from.value);
                boolean intoArtifact = e.to.label.equals("Artifact") || e.to.label.equals("ConfigKey");
                if (owner == null || changed.contains(owner) || (fullRun && intoArtifact)) {
                    edges.add(e);
                }
            }
            upsertEdges(edges);

            // 6. Full-run orphan pruning uses the actual incoming schema family. A v1 request
            // selects only an id-less legacy anchor; v2 selects only its exact canonical anchor.
            if (fullRun) {
                List<String> present = new ArrayList<>(byUnit.keySet());
                String appName = appNameOf(rows);
                String appId = applicationIdOf(rows);
                // Only Java containment under this exact anchor is traversed. Shared artifacts
                // are reconciled separately and sibling namespaces are never fallback matches.
                try (Session s = session()) {
                    long pruned = s.run(orphanPruneQuery(rows),
                            Values.parameters("present", present, "appId", appId, "appName", appName))
                            .single().get("pruned").asLong(0);
                    Log.info("neo4j(bolt): pruned " + pruned + " vanished unit(s)");
                }
            } else {
                Log.info("neo4j(bolt): targeted run — orphan pruning skipped (deleted files not removed)");
            }
        }

        /** v2 only: v1 emits no artifacts, so it would release every one. */
        private void reconcileArtifacts(GraphRows rows) {
            String appId = null;
            List<String> keep = new ArrayList<>();
            for (NodeRow n : rows.nodes) {
                String label = n.labels.get(0);
                if (label.equals("JApplication") && RowBuilder.isCanId(n.value)) {
                    appId = n.value;
                } else if (label.equals("Artifact") || label.equals("ConfigKey")) {
                    keep.add(n.value);
                }
            }
            if (appId == null) {
                return;
            }
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("appId", appId);
            args.put("pres", artifactPrefixes(appId, appNameOf(rows)));
            args.put("keep", keep);
            // Auto-commit and batched to bound full-run artifact reconciliation.
            // A partial failure is safe; the next full push finishes it.
            try (Session s = session()) {
                s.run(PURGE_ARTIFACT_EDGES, args).consume();
                s.run(PURGE_UNRESOLVED_CONFIG_READS, args).consume();
                long released = s.run(RELEASE_VANISHED_ARTIFACTS, args).single().get("released").asLong(0);
                Log.info("neo4j(bolt): released " + released + " vanished artifact/config node(s)");
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

        /**
         * The application anchor's display name — read from the {@code name} property rather than
         * {@link NodeRow#value}, because that merge value is generation-dependent (the raw name
         * for v1, the {@code can://} id for v2 since Task 3). The {@code name} property is set
         * identically by both generations, so this always yields the true app name to re-derive
         * the id from.
         */
        private static String applicationIdOf(GraphRows rows) {
            for (NodeRow n : rows.nodes) {
                if (n.labels.contains("JApplication") && RowBuilder.isCanId(n.value)) return n.value;
            }
            return CanId.applicationId(appNameOf(rows));
        }

        private static String appNameOf(GraphRows rows) {
            for (NodeRow n : rows.nodes) {
                if (n.labels.get(0).equals("JApplication")) {
                    Object name = n.props.get("name");
                    return name != null ? name.toString() : n.value;
                }
            }
            return "application";
        }
    }
}
