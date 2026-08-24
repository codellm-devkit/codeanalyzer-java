package com.ibm.cldk.syntactic_analysis.controlflow;

import com.ibm.cldk.schema.JBodyNode;
import com.ibm.cldk.schema.JCfgEdge;
import com.ibm.cldk.schema.Span;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The internal control-flow graph a {@link CfgBuilder} produces and that {@link CdgBuilder} and the
 * data-dependence builder consume. Nodes are body-node local ids ({@code line:col} or an {@code @tag});
 * every graph has exactly one {@link #ENTRY} and one {@link #EXIT} synthetic node. Edges carry a CFG
 * {@code kind}. The node map is the callable's completed {@code body} (the L1 {@code call} nodes are
 * seeded in, never overwritten — the additive invariant).
 */
public final class ControlFlowGraph {

    public static final String ENTRY = "@entry";
    public static final String EXIT = "@exit";

    private static final class Edge {
        private final String src;
        private final String dst;
        private final String kind;

        Edge(String src, String dst, String kind) {
            this.src = src;
            this.dst = dst;
            this.kind = kind;
        }

        String src() {
            return src;
        }

        String dst() {
            return dst;
        }

        String kind() {
            return kind;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Edge)) {
                return false;
            }
            Edge e = (Edge) o;
            return src.equals(e.src) && dst.equals(e.dst) && kind.equals(e.kind);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(src, dst, kind);
        }
    }

    private final Map<String, JBodyNode> nodes = new LinkedHashMap<>();
    private final List<Edge> edges = new ArrayList<>();
    private final Map<String, List<String>> succ = new LinkedHashMap<>();
    private final Map<String, List<String>> pred = new LinkedHashMap<>();

    public ControlFlowGraph() {
        JBodyNode entry = new JBodyNode();
        entry.setKind("entry");
        nodes.put(ENTRY, entry);
        JBodyNode exit = new JBodyNode();
        exit.setKind("exit");
        nodes.put(EXIT, exit);
    }

    /** The completed body: synthetic entry/exit, the seeded L1 call nodes, and the L3 statement nodes. */
    public Map<String, JBodyNode> nodes() {
        return nodes;
    }

    /** Seed an existing L1 body node (a {@code call}) without overwriting it — the additive invariant. */
    public void seed(String id, JBodyNode node) {
        nodes.putIfAbsent(id, node);
    }

    /**
     * Create a statement node at {@code id} with the given kind and span, unless one already exists
     * there (a seeded call node, or a node from an earlier link) — in which case that node is kept
     * unchanged. Returns whichever node now lives at {@code id}.
     */
    public JBodyNode ensureNode(String id, String kind, Span span) {
        JBodyNode existing = nodes.get(id);
        if (existing != null) {
            return existing;
        }
        JBodyNode node = new JBodyNode();
        node.setKind(kind);
        node.setSpan(span);
        nodes.put(id, node);
        return node;
    }

    public boolean hasNode(String id) {
        return nodes.containsKey(id);
    }

    public void addEdge(String src, String dst, String kind) {
        edges.add(new Edge(src, dst, kind));
        succ.computeIfAbsent(src, k -> new ArrayList<>()).add(dst);
        pred.computeIfAbsent(dst, k -> new ArrayList<>()).add(src);
    }

    public List<String> successors(String id) {
        return succ.getOrDefault(id, List.of());
    }

    public List<String> predecessors(String id) {
        return pred.getOrDefault(id, List.of());
    }

    /** The {@code cfg} overlay: edges deduplicated and sorted by (src, dst, kind) for determinism. */
    public List<JCfgEdge> toCfgEdges() {
        return edges.stream()
                .distinct()
                .sorted(Comparator.comparing(Edge::src).thenComparing(Edge::dst).thenComparing(Edge::kind))
                .map(e -> {
                    JCfgEdge j = new JCfgEdge();
                    j.setSrc(e.src());
                    j.setDst(e.dst());
                    j.setKind(e.kind());
                    return j;
                })
                .collect(Collectors.toList());
    }
}
