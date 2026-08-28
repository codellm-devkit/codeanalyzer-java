package com.ibm.cldk.syntactic_analysis.dataflow;

import com.ibm.cldk.schema.JBodyNode;
import com.ibm.cldk.schema.JCallable;
import com.ibm.cldk.schema.JIdEdge;
import com.ibm.cldk.schema.JModule;
import com.ibm.cldk.schema.JParameter;
import com.ibm.cldk.schema.JType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * L4 stage 7 (HRB assembly), derived: the synthetic parameter vertices and the cross-function
 * param_in/param_out edges are structurally determined by the L2-backfilled call sites and the
 * callee's parameter list, so they are built directly from the v2 tree — no engine, pure and
 * deterministic. Summary edges are the SummaryPass's job (PR 2), and the semantic ddg is
 * L4WalaOverlays' (points-to genuinely needs WALA).
 */
public final class SdgVertices {

    private SdgVertices() {}

    public static final class Result {
        public final List<JIdEdge> paramIn;
        public final List<JIdEdge> paramOut;

        private Result(List<JIdEdge> paramIn, List<JIdEdge> paramOut) {
            this.paramIn = paramIn;
            this.paramOut = paramOut;
        }
    }

    /** Mutates modules in place (adds synthetic body nodes); returns the application-scope edges. */
    public static Result apply(Map<String, JModule> modules) {
        Map<String, JCallable> byId = new LinkedHashMap<>();
        forEachCallable(modules, c -> {
            if (c.getId() != null) {
                byId.put(c.getId(), c);
            }
        });

        List<JIdEdge> paramIn = new ArrayList<>();
        List<JIdEdge> paramOut = new ArrayList<>();
        forEachCallable(modules, c -> {
            addFormals(c);
            addActualsAndEdges(c, byId, paramIn, paramOut);
        });

        paramIn.sort(Comparator.comparing(JIdEdge::getSrc).thenComparing(JIdEdge::getDst));
        paramOut.sort(Comparator.comparing(JIdEdge::getSrc).thenComparing(JIdEdge::getDst));
        return new Result(paramIn, paramOut);
    }

    /**
     * Adds {@code c}'s own formal vertices: one {@code @formal_in} per declared parameter
     * (declaration order), plus a {@code @formal_out} when {@code c} actually hands a value back —
     * the thing a caller's {@code actual_out} eventually connects to.
     */
    private static void addFormals(JCallable c) {
        List<JParameter> params = c.getParameters();
        for (int i = 0; i < params.size(); i++) {
            c.getBody().put("@formal_in:" + i, vertex("formal_in", params.get(i).getName(), null));
        }
        if (returnsValue(c)) {
            c.getBody().put("@formal_out", vertex("formal_out", "$ret", null));
        }
    }

    /** {@code void} and a constructor's declared "return type" are not a value a caller can receive. */
    private static boolean returnsValue(JCallable c) {
        return c.getReturnType() != null
                && !"void".equals(c.getReturnType())
                && !"constructor".equals(c.getKind());
    }

    /**
     * For each in-project call site already in {@code c}'s own body, adds its actual vertices and
     * wires them to the resolved callee's formals. Iterates a snapshot of {@code c.getBody()} since
     * the loop body inserts the very actual vertices it discovers into that same map. An in-project
     * callee id that does not resolve through {@code byId} (a stale id) still gets its vertices —
     * only the edges are skipped.
     */
    private static void addActualsAndEdges(
            JCallable c, Map<String, JCallable> byId, List<JIdEdge> paramIn, List<JIdEdge> paramOut) {
        List<Map.Entry<String, JBodyNode>> callSites = new ArrayList<>(c.getBody().entrySet());
        for (Map.Entry<String, JBodyNode> entry : callSites) {
            String local = entry.getKey();
            JBodyNode node = entry.getValue();
            String calleeId = node.getCallee();
            if (!"call".equals(node.getKind()) || calleeId == null || calleeId.contains("/@external/")) {
                continue;
            }

            int nArgs = node.getArgumentExpr().size();
            for (int i = 0; i < nArgs; i++) {
                c.getBody().put(local + "/actual_in:" + i, vertex("actual_in", "arg" + i, local));
            }
            boolean hasActualOut = node.getReturnType() != null && !"void".equals(node.getReturnType());
            if (hasActualOut) {
                c.getBody().put(local + "/actual_out", vertex("actual_out", "$ret", local));
            }

            JCallable callee = byId.get(calleeId);
            if (callee == null) {
                continue; // vertices stand on their own; edge-only-when-resolved
            }

            int calleeParams = callee.getParameters().size();
            if (calleeParams > 0) {
                int bound = Math.min(nArgs, calleeParams);
                for (int i = 0; i < bound; i++) {
                    int formalIndex = Math.min(i, calleeParams - 1); // varargs: tail args collapse onto it
                    paramIn.add(edge(
                            global(c, local + "/actual_in:" + i), global(callee, "@formal_in:" + formalIndex)));
                }
            }
            // Recomputed rather than read off callee.getBody(): traversal order is not call order, so
            // the callee's own addFormals may not have run yet when the caller's site is processed.
            if (hasActualOut && returnsValue(callee)) {
                paramOut.add(edge(global(callee, "@formal_out"), global(c, local + "/actual_out")));
            }
        }
    }

    /** The global body-node id for a local key under {@code c} — real keys get {@code @}, synthetics concatenate. */
    private static String global(JCallable c, String local) {
        return local.startsWith("@") ? c.getId() + local : c.getId() + "@" + local;
    }

    /** A synthetic body node; synthetics carry no {@code span} — they are not a source position. */
    private static JBodyNode vertex(String kind, String of, String parent) {
        JBodyNode n = new JBodyNode();
        n.setKind(kind);
        n.setOf(of);
        n.setParent(parent);
        return n;
    }

    private static JIdEdge edge(String src, String dst) {
        JIdEdge e = new JIdEdge();
        e.setSrc(src);
        e.setDst(dst);
        return e;
    }

    /**
     * Visits every callable reachable from {@code modules} — mirrors the traversal shape of
     * {@code V2GraphProjector.indexTypes} (nested types, then each callable's own local types) without
     * depending on that class.
     */
    private static void forEachCallable(Map<String, JModule> modules, Consumer<JCallable> visitor) {
        for (JModule m : modules.values()) {
            walkTypes(m.getTypes(), visitor);
        }
    }

    private static void walkTypes(Map<String, JType> types, Consumer<JCallable> visitor) {
        for (JType t : types.values()) {
            walkTypes(t.getTypes(), visitor);
            for (JCallable c : t.getCallables().values()) {
                visitor.accept(c);
                walkTypes(c.getTypes(), visitor);
            }
        }
    }
}
