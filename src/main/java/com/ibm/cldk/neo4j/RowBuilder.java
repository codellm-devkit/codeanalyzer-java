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
import com.ibm.cldk.neo4j.GraphRows.NodeRef;
import com.ibm.cldk.neo4j.GraphRows.NodeRow;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Accumulates nodes/edges with {@code MERGE} semantics in memory, so the same node touched many
 * times (a hot annotation, a canonical package) collapses to one row, and cross-reference edges to
 * a target that never materialized are dropped (the "edge-only-when-resolved" rule).
 *
 * <p>This is the in-memory analog of {@code MERGE (n:Label {key}) SET n += props}: re-seeing the
 * same (mergeLabel, value) merges props (last write wins) and unions labels.
 */
public final class RowBuilder {

    /** key: {@code labels[0] + " " + value}. */
    private final Map<String, NodeRow> nodes = new LinkedHashMap<>();
    private final List<EdgeRow> edges = new ArrayList<>();
    /** Edges gated against node existence at {@link #finish()}. */
    private final List<EdgeRow> deferred = new ArrayList<>();
    /** Every node value seen, for resolved-gating. */
    private final Set<String> keys = new HashSet<>();

    /** Convenience: a new mutable props map. */
    public static Map<String, Object> props() {
        return new LinkedHashMap<>();
    }

    /**
     * Drop {@code null} entries — in Neo4j a null property means "absent", so we never store one.
     * Empty collections are also dropped (Neo4j cannot store an empty typed list cleanly).
     */
    public static Map<String, Object> prune(Map<String, Object> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : in.entrySet()) {
            Object v = e.getValue();
            if (v == null) {
                continue;
            }
            if (v instanceof List && ((List<?>) v).isEmpty()) {
                continue;
            }
            out.put(e.getKey(), v);
        }
        return out;
    }

    /**
     * Upsert a node. Re-seeing the same {@code (labels[0], value)} merges props (last write wins)
     * and unions labels.
     */
    public NodeRef node(List<String> labels, String keyProp, String value, Map<String, Object> props) {
        String id = labels.get(0) + " " + value;
        NodeRow existing = nodes.get(id);
        if (existing != null) {
            existing.props.putAll(props);
            for (String l : labels) {
                if (!existing.labels.contains(l)) {
                    existing.labels.add(l);
                }
            }
            // putAll may have re-introduced the lifted property; strip it again on the v2 path only,
            // so a re-merged v1 node keeps the property its contract still includes.
            if (isCanId(value)) {
                existing.props.remove(MODULE_PROP);
            }
        } else {
            Map<String, Object> p = new LinkedHashMap<>(props);
            // Lift the owning module off the emitted properties and onto the row. The writer needs
            // it to group nodes; the graph does not, because a v2 node's owning module is already
            // the prefix of its can:// id. See NodeRow.moduleKey.
            Object m = p.remove(MODULE_PROP);
            String moduleKey = m instanceof String ? (String) m : null;
            List<String> allLabels = new ArrayList<>(labels);
            if (isCanId(value)) {
                // The index anchor for prefix-scoped statements: Neo4j property indexes are
                // label-scoped, so `WHERE n.id STARTS WITH $p` needs a label to seek on. Carries no
                // safety claim of its own -- the prefix does that -- and is java-namespaced so an
                // anchoring mistake still cannot reach another language's nodes.
                allLabels.add(CAN_NODE);
            } else if (moduleKey != null) {
                // v1 ids are FQN-shaped and carry no application segment, so they cannot be
                // prefix-scoped. That path keeps the property it has always had; only the v2
                // contract drops it.
                p.put(MODULE_PROP, moduleKey);
            }
            nodes.put(id, new NodeRow(allLabels, keyProp, value, p, moduleKey));
        }
        keys.add(value);
        return new NodeRef(labels.get(0), keyProp, value);
    }

    /** The marker label carried by every node keyed on a {@code can://} id. */
    public static final String CAN_NODE = "JCanNode";

    private static final String MODULE_PROP = "_module";

    /** Whether a node key is a v2 {@code can://} id, as opposed to a v1 FQN-shaped one. */
    static boolean isCanId(String value) {
        return value != null && value.startsWith("can://");
    }

    /** An edge whose endpoints are known to exist (both ends emitted as nodes this run). */
    public void edge(String type, NodeRef from, NodeRef to, Map<String, Object> props) {
        edges.add(new EdgeRow(type, from, to, props));
    }

    /** As above, with a {@code _k} MERGE discriminant (see {@link EdgeRow#key}). */
    public void keyedEdge(String type, NodeRef from, NodeRef to, Map<String, Object> props, String key) {
        edges.add(new EdgeRow(type, from, to, props, key));
    }

    public void edge(String type, NodeRef from, NodeRef to) {
        edges.add(new EdgeRow(type, from, to, RowBuilder.props()));
    }

    /**
     * An edge to a {@code :JSymbol} target that may be external/library code not present in the
     * graph. Deferred and kept only if the target id was actually emitted as a node — so J_EXTENDS /
     * J_IMPLEMENTS / J_RESOLVES_TO / J_CALLS never dangle.
     */
    public void edgeToSymbol(String type, NodeRef from, String targetId, Map<String, Object> props) {
        deferred.add(new EdgeRow(type, from, new NodeRef("JSymbol", "id", targetId), props));
    }

    public void edgeToSymbol(String type, NodeRef from, String targetId) {
        edgeToSymbol(type, from, targetId, RowBuilder.props());
    }

    /** An edge kept only if BOTH endpoints were emitted as nodes (used for CALLS). */
    public void edgeIfBothResolved(String type, NodeRef from, NodeRef to, Map<String, Object> props) {
        deferred.add(new EdgeRow(type, from, to, props));
    }

    public GraphRows finish() {
        for (EdgeRow e : deferred) {
            if (keys.contains(e.from.value) && keys.contains(e.to.value)) {
                edges.add(e);
            }
        }
        // Dedupe edges the way Neo4j's MERGE would: one relationship per
        // (type, source, target[, _k]), last-write-wins on props (mirrors `MERGE (a)-[r]->(b) SET r += p`).
        Map<String, EdgeRow> uniqueEdges = new LinkedHashMap<>();
        for (EdgeRow e : edges) {
            uniqueEdges.put(e.type + "|" + e.from.label + ":" + e.from.value
                    + "|" + e.to.label + ":" + e.to.value
                    + (e.key == null ? "" : "|" + e.key), e);
        }

        List<NodeRow> nodeList = new ArrayList<>(nodes.values());
        nodeList.sort((a, b) ->
                (a.labels.get(0) + " " + a.value).compareTo(b.labels.get(0) + " " + b.value));
        List<EdgeRow> edgeList = new ArrayList<>(uniqueEdges.values());
        edgeList.sort((a, b) ->
                (a.type + " " + a.from.value + " " + a.to.value + " " + (a.key == null ? "" : a.key))
                        .compareTo(b.type + " " + b.from.value + " " + b.to.value + " "
                                + (b.key == null ? "" : b.key)));
        return new GraphRows(nodeList, edgeList);
    }
}
