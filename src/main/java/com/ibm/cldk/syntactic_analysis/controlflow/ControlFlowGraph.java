package com.ibm.cldk.syntactic_analysis.controlflow;

import com.github.javaparser.ast.Node;
import com.ibm.cldk.schema.JBodyNode;
import com.ibm.cldk.schema.JCfgEdge;
import com.ibm.cldk.schema.Span;
import java.util.ArrayList;
import java.util.Collection;
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

    /** One directed CFG edge with its kind; value semantics so {@code toCfgEdges} can deduplicate. */
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
    // Forward and backward adjacency, kept in sync with `edges` via index()/redirect() for O(1) lookup.
    private final Map<String, List<String>> succ = new LinkedHashMap<>();
    private final Map<String, List<String>> pred = new LinkedHashMap<>();
    /** The JavaParser statement each node was built from — the data-dependence pass reads defs/uses off it. */
    private final Map<String, Node> ast = new LinkedHashMap<>();

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
        Edge e = new Edge(src, dst, kind);
        edges.add(e);
        index(e);
    }

    /**
     * Remove every directed edge with the given {@code (src, dst, kind)} triple. Rebuilds the
     * adjacency maps after removal. Only call on method-scope graphs where the rebuild cost is
     * negligible. Used by {@code WalaCfgBuilder} to splice orphaned body nodes into the CFG.
     */
    public void removeEdge(String src, String dst, String kind) {
        boolean changed = edges.removeIf(
                e -> e.src().equals(src) && e.dst().equals(dst) && e.kind().equals(kind));
        if (changed) {
            succ.clear();
            pred.clear();
            edges.forEach(this::index);
        }
    }

    private void index(Edge e) {
        succ.computeIfAbsent(e.src(), k -> new ArrayList<>()).add(e.dst());
        pred.computeIfAbsent(e.dst(), k -> new ArrayList<>()).add(e.src());
    }

    /**
     * Replace every edge into {@code from} with an edge (of {@code kind}) to each of {@code tos}. Used to
     * fan a finally block's completion out to the union of its continuations without a synthetic node:
     * the finally is linked to a temporary sentinel, then that sentinel is redirected here.
     */
    public void redirect(String from, Collection<String> tos, String kind) {
        List<Edge> kept = new ArrayList<>();
        List<Edge> removed = new ArrayList<>();
        for (Edge e : edges) {
            (e.dst().equals(from) ? removed : kept).add(e);
        }
        edges.clear();
        succ.clear();
        pred.clear();
        for (Edge e : kept) {
            edges.add(e);
            index(e);
        }
        for (Edge e : removed) {
            for (String to : tos) {
                addEdge(e.src(), to, kind);
            }
        }
    }

    /** Associate a node id with the JavaParser statement it was built from (first wins). */
    public void recordAst(String id, Node node) {
        ast.putIfAbsent(id, node);
    }

    /** The JavaParser statement for a node, or {@code null} for synthetics and seeded call nodes. */
    public Node astNode(String id) {
        return ast.get(id);
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
