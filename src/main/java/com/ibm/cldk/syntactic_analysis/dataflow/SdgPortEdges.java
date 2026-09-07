package com.ibm.cldk.syntactic_analysis.dataflow;

import com.ibm.cldk.schema.JBodyNode;
import com.ibm.cldk.schema.JCallable;
import com.ibm.cldk.schema.JDdgEdge;
import com.ibm.cldk.schema.JModule;
import com.ibm.cldk.schema.JParameter;
import com.ibm.cldk.schema.JType;
import com.ibm.cldk.schema.Span;
import com.ibm.cldk.utils.Log;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * L4 stage 8, derived: the {@code ddg} edges that join the SDG port lattice to the statement graph.
 *
 * <p>{@link SdgVertices} mints the ports and the cross-function {@code param_in}/{@code param_out}
 * edges, and {@link SummaryPass} shortcuts each call site — but nothing ever flowed <em>into</em> or
 * <em>out of</em> a port, so the two halves were disjoint graphs: a statement-level {@code ddg} and
 * a port lattice reachable from it in neither direction. A {@code formal_in} — the natural seed for
 * "what does this argument reach" — had out-degree zero, which makes every interprocedural value
 * question degenerate to an empty answer indistinguishable from "no flow exists".
 *
 * <p>Four edge classes close the gap, each a re-rooting of something the statement graph already
 * says, so this pass is pure and engine-free:
 * <ul>
 *   <li>{@code @formal_in:k → use} — mirrors the {@code @entry → use} edges of parameter {@code k}.
 *       {@link DdgBuilder} defines the formals at {@code @entry} precisely because a parameter has
 *       no defining statement; the port is the same definition under its interprocedural name.
 *   <li>{@code return → @formal_out} — the statements that hand a value back.
 *   <li>{@code statement → <call>/actual_in:i} and {@code <call>/actual_out → statement} — the
 *       statement that evaluates a call's arguments and consumes its result. The call's own body
 *       node carries no dependence edges, so the ports are attached to the enclosing statement,
 *       which does.
 * </ul>
 *
 * <p>The argument attachment is deliberately coarse: every actual of a call site is fed by the one
 * statement containing it, rather than by the reaching definition of that particular argument
 * expression. L4 is over-approximate — it may add reach, never drop it — and the precise binding
 * is recoverable from the statement's own incoming edges.
 *
 * <p>Runs after {@link SummaryPass} so summaries are computed from the statement-level {@code ddg}
 * alone, exactly as before.
 */
public final class SdgPortEdges {

    private SdgPortEdges() {}

    /** Body-node kinds that carry dependence edges, i.e. can host a port attachment. */
    private static final Set<String> STATEMENT_KINDS = new HashSet<>(
            Arrays.asList("statement", "return", "branch", "loop", "switch", "expression", "block"));

    private static final String ENTRY = "@entry";
    private static final String FORMAL_OUT = "@formal_out";
    private static final String RETURN_VALUE = "$ret";

    /** Mutates each callable's {@code ddg} in place. */
    public static void apply(Map<String, JModule> modules) {
        int added = 0;
        int touched = 0;
        for (JCallable c : callables(modules)) {
            int n = connect(c);
            if (n > 0) {
                added += n;
                touched++;
            }
        }
        Log.info("L4 SDG port edges: " + added + " ddg edge(s) added across " + touched + " callable(s)");
    }

    /** @return the number of edges added to {@code c}. */
    private static int connect(JCallable c) {
        Map<String, JBodyNode> body = c.getBody();
        if (body == null || body.isEmpty() || c.getDdg() == null) {
            return 0;
        }

        List<JDdgEdge> produced = new ArrayList<>();
        formalInEdges(c, body, produced);
        formalOutEdges(c, body, produced);
        actualEdges(c, body, produced);
        return merge(c, produced);
    }

    /**
     * {@code @formal_in:k → use}, mirroring the {@code @entry} definition of parameter {@code k}.
     *
     * <p>Only the AST engine roots parameter flow at {@code @entry}; the WALA L3 engine keeps
     * {@code NormalStatement} endpoints only and so has no such edge to mirror, leaving its
     * {@code formal_in} ports unattached.
     */
    private static void formalInEdges(JCallable c, Map<String, JBodyNode> body, List<JDdgEdge> out) {
        List<JParameter> params = c.getParameters();
        for (JDdgEdge e : c.getDdg()) {
            if (!ENTRY.equals(e.getSrc()) || e.getVar() == null) {
                continue;
            }
            String base = baseOf(e.getVar());
            for (int k = 0; k < params.size(); k++) {
                if (!base.equals(params.get(k).getName())) {
                    continue;
                }
                String port = "@formal_in:" + k;
                if (body.containsKey(port)) {
                    out.add(edge(port, e.getDst(), e.getVar(), e.getProv()));
                }
                break;
            }
        }
    }

    /** {@code return → @formal_out} for every returning statement of a value-returning callable. */
    private static void formalOutEdges(JCallable c, Map<String, JBodyNode> body, List<JDdgEdge> out) {
        if (!body.containsKey(FORMAL_OUT)) {
            return; // void, or a constructor: no value leaves through a port
        }
        for (Map.Entry<String, JBodyNode> entry : body.entrySet()) {
            if ("return".equals(entry.getValue().getKind())) {
                out.add(edge(entry.getKey(), FORMAL_OUT, RETURN_VALUE, ssa()));
            }
        }
    }

    /** {@code statement → actual_in:i} and {@code actual_out → statement}, per call site. */
    private static void actualEdges(JCallable c, Map<String, JBodyNode> body, List<JDdgEdge> out) {
        for (Map.Entry<String, JBodyNode> entry : new ArrayList<>(body.entrySet())) {
            if (!"call".equals(entry.getValue().getKind())) {
                continue;
            }
            String call = entry.getKey();
            String host = enclosingStatement(call, entry.getValue(), body);
            for (int i = 0; body.containsKey(call + "/actual_in:" + i); i++) {
                String port = call + "/actual_in:" + i;
                out.add(edge(host, port, argumentName(body.get(port), i), ssa()));
            }
            String actualOut = call + "/actual_out";
            if (body.containsKey(actualOut)) {
                out.add(edge(actualOut, host, RETURN_VALUE, ssa()));
            }
        }
    }

    /**
     * The innermost statement-like body node whose source range contains {@code call}, or the call
     * itself when none does — which is the bare-call statement {@code foo(x);}, whose statement node
     * and call node are one and the same (they share an addressing anchor).
     */
    private static String enclosingStatement(String call, JBodyNode node, Map<String, JBodyNode> body) {
        Span span = node.getSpan();
        if (span == null || span.getBytes() == null) {
            return call;
        }
        String best = call;
        long bestWidth = Long.MAX_VALUE;
        for (Map.Entry<String, JBodyNode> candidate : body.entrySet()) {
            if (candidate.getKey().equals(call) || !STATEMENT_KINDS.contains(candidate.getValue().getKind())) {
                continue;
            }
            Span s = candidate.getValue().getSpan();
            if (s == null || s.getBytes() == null) {
                continue;
            }
            if (s.getBytes()[0] <= span.getBytes()[0] && span.getBytes()[1] <= s.getBytes()[1]) {
                long width = (long) s.getBytes()[1] - s.getBytes()[0];
                if (width < bestWidth) {
                    bestWidth = width;
                    best = candidate.getKey();
                }
            }
        }
        return best;
    }

    /** The value an {@code actual_in} port stands for — its own {@code of}, or {@code argN}. */
    private static String argumentName(JBodyNode port, int index) {
        return port != null && port.getOf() != null ? port.getOf() : "arg" + index;
    }

    /** The base segment of a k-limited access path ({@code a.b[*]} → {@code a}). */
    private static String baseOf(String accessPath) {
        int cut = accessPath.length();
        for (int i = 0; i < accessPath.length(); i++) {
            char ch = accessPath.charAt(i);
            if (ch == '.' || ch == '[') {
                cut = i;
                break;
            }
        }
        return accessPath.substring(0, cut);
    }

    /**
     * Appends the edges of {@code produced} that {@code c} does not already carry, then re-sorts on
     * {@code (src,dst,var,prov)} — the key every ddg producer sorts on.
     *
     * @return the number of edges actually added
     */
    private static int merge(JCallable c, List<JDdgEdge> produced) {
        if (produced.isEmpty()) {
            return 0;
        }
        List<JDdgEdge> merged = new ArrayList<>(c.getDdg());
        Set<String> seen = new HashSet<>();
        for (JDdgEdge e : merged) {
            seen.add(key(e));
        }
        int added = 0;
        for (JDdgEdge e : produced) {
            if (seen.add(key(e))) {
                merged.add(e);
                added++;
            }
        }
        if (added == 0) {
            return 0;
        }
        merged.sort(Comparator.comparing(JDdgEdge::getSrc)
                .thenComparing(JDdgEdge::getDst)
                .thenComparing(JDdgEdge::getVar)
                .thenComparing(e -> e.getProv().toString()));
        c.setDdg(merged);
        return added;
    }

    private static String key(JDdgEdge e) {
        return e.getSrc() + "\0" + e.getDst() + "\0" + e.getVar() + "\0" + e.getProv();
    }

    private static List<String> ssa() {
        List<String> prov = new ArrayList<>(1);
        prov.add("ssa");
        return prov;
    }

    private static JDdgEdge edge(String src, String dst, String var, List<String> prov) {
        JDdgEdge e = new JDdgEdge();
        e.setSrc(src);
        e.setDst(dst);
        e.setVar(var);
        e.getProv().addAll(prov);
        return e;
    }

    /** Every callable reachable from {@code modules}, mirroring {@link SdgVertices}'s traversal. */
    private static List<JCallable> callables(Map<String, JModule> modules) {
        List<JCallable> out = new ArrayList<>();
        for (JModule m : modules.values()) {
            walkTypes(m.getTypes(), out::add);
        }
        return out;
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
