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
        } else {
            nodes.put(id, new NodeRow(new ArrayList<>(labels), keyProp, value, new LinkedHashMap<>(props)));
        }
        keys.add(value);
        return new NodeRef(labels.get(0), keyProp, value);
    }

    /** An edge whose endpoints are known to exist (both ends emitted as nodes this run). */
    public void edge(String type, NodeRef from, NodeRef to, Map<String, Object> props) {
        edges.add(new EdgeRow(type, from, to, props));
    }

    public void edge(String type, NodeRef from, NodeRef to) {
        edges.add(new EdgeRow(type, from, to, RowBuilder.props()));
    }

    /**
     * An edge to a {@code :Symbol} target that may be external/library code not present in the
     * graph. Deferred and kept only if the target id was actually emitted as a node — so EXTENDS /
     * IMPLEMENTS / RESOLVES_TO / CALLS never dangle.
     */
    public void edgeToSymbol(String type, NodeRef from, String targetId, Map<String, Object> props) {
        deferred.add(new EdgeRow(type, from, new NodeRef("Symbol", "id", targetId), props));
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
        // (type, source, target), last-write-wins on props (mirrors `MERGE (a)-[r]->(b) SET r += p`).
        Map<String, EdgeRow> uniqueEdges = new LinkedHashMap<>();
        for (EdgeRow e : edges) {
            uniqueEdges.put(e.type + "|" + e.from.label + ":" + e.from.value
                    + "|" + e.to.label + ":" + e.to.value, e);
        }

        List<NodeRow> nodeList = new ArrayList<>(nodes.values());
        nodeList.sort((a, b) ->
                (a.labels.get(0) + " " + a.value).compareTo(b.labels.get(0) + " " + b.value));
        List<EdgeRow> edgeList = new ArrayList<>(uniqueEdges.values());
        edgeList.sort((a, b) ->
                (a.type + " " + a.from.value + " " + a.to.value)
                        .compareTo(b.type + " " + b.from.value + " " + b.to.value));
        return new GraphRows(nodeList, edgeList);
    }
}
