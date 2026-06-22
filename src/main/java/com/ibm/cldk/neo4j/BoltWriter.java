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
                Object m = n.props.get("_module");
                if (m instanceof String) {
                    byUnit.computeIfAbsent((String) m, x -> new ArrayList<>()).add(n);
                    unitOf.put(n.value, (String) m);
                } else {
                    shared.add(n);
                }
            }

            // 2. diff content_hash.
            Map<String, String> dbHash = new HashMap<>();
            try (Session s = session()) {
                s.run("MATCH (c:JCompilationUnit) RETURN c.file_key AS k, c.content_hash AS h").list()
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
                        tx.run("MATCH (x {_module: $m})-[r]->() DELETE r", Values.parameters("m", unit));
                        tx.run("MATCH (x {_module: $m}) WHERE NOT coalesce(x.id, x.file_key) IN $keys DETACH DELETE x",
                                Values.parameters("m", unit, "keys", keys));
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

            // 6. orphan prune — only safe on a full run.
            if (fullRun) {
                List<String> present = new ArrayList<>(byUnit.keySet());
                try (Session s = session()) {
                    long pruned = s.run("MATCH (c:JCompilationUnit) WHERE NOT c.file_key IN $present "
                                    + "OPTIONAL MATCH (c)-" + CypherWriter.DESCENDANTS + "->(x) "
                                    + "DETACH DELETE x, c RETURN count(c) AS pruned",
                            Values.parameters("present", present)).single().get("pruned").asLong(0);
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
                String k = e.type + "|" + e.from.label + "." + e.from.keyProp + "|" + e.to.label + "." + e.to.keyProp;
                groups.computeIfAbsent(k, x -> new ArrayList<>()).add(e);
            }
            for (List<EdgeRow> group : groups.values()) {
                EdgeRow head = group.get(0);
                String cypher = "UNWIND $rows AS row "
                        + "MATCH (a:" + head.from.label + " {" + head.from.keyProp + ": row.f}) "
                        + "MATCH (b:" + head.to.label + " {" + head.to.keyProp + ": row.t}) "
                        + "MERGE (a)-[r:" + head.type + "]->(b) SET r += row.p";
                for (List<EdgeRow> batch : CypherWriter.chunk(group, BATCH)) {
                    List<Map<String, Object>> payload = new ArrayList<>();
                    for (EdgeRow e : batch) {
                        Map<String, Object> r = new HashMap<>();
                        r.put("f", e.from.value);
                        r.put("t", e.to.value);
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
                if (n.labels.get(0).equals("JCompilationUnit") && n.value.equals(fileKey)) {
                    Object h = n.props.get("content_hash");
                    return h instanceof String ? (String) h : null;
                }
            }
            return null;
        }
    }
}
