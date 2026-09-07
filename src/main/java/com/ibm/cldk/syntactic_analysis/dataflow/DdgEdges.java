package com.ibm.cldk.syntactic_analysis.dataflow;

import com.ibm.cldk.schema.JCallable;
import com.ibm.cldk.schema.JDdgEdge;
import java.util.ArrayList;
import java.util.List;

/** Shared guards over a callable's {@code ddg}, applied by every producer that attaches edges to it. */
public final class DdgEdges {

    private DdgEdges() {}

    /**
     * Drops the edges of {@code edges} whose {@code src} or {@code dst} is not a body node of
     * {@code callable}.
     *
     * <p>A WALA instruction whose covering source statement cannot be identified maps to the
     * sentinel {@code "<line>:0"} (see {@link com.ibm.cldk.wala.InstructionToNode}), which is a
     * well-formed {@code localId} but names no body node. An edge on that sentinel is unusable:
     * the Neo4j emitter materialises body nodes from {@code body{}}, so it silently cannot write
     * the edge, and a JSON consumer resolving endpoints finds nothing on the other end. Rather
     * than emit a dependence a reader cannot follow, the edge is dropped here — the same
     * information the graph already carried, now stated identically in both artefacts.
     *
     * <p>Dropping is sound in the direction that matters: the sentinel marks an
     * over-approximation, so the edge is a <em>may</em>-dependence whose exact endpoint was never
     * established. The alternative — emitting a synthetic {@code <line>:0} vertex — would need a
     * body-node kind no schema vocabulary has.
     *
     * @return the edges that resolve on both ends, in input order
     */
    public static List<JDdgEdge> dropDangling(JCallable callable, List<JDdgEdge> edges) {
        if (edges == null || edges.isEmpty() || callable.getBody() == null) {
            return edges;
        }
        List<JDdgEdge> kept = new ArrayList<>(edges.size());
        for (JDdgEdge e : edges) {
            if (callable.getBody().containsKey(e.getSrc()) && callable.getBody().containsKey(e.getDst())) {
                kept.add(e);
            }
        }
        return kept;
    }
}
