package com.ibm.cldk.syntactic_analysis.dataflow;

import com.ibm.cldk.schema.JBodyNode;
import com.ibm.cldk.schema.JCallEdge;
import com.ibm.cldk.schema.JCallable;
import com.ibm.cldk.schema.JDdgEdge;
import com.ibm.cldk.schema.JIdEdge;
import com.ibm.cldk.schema.JModule;
import com.ibm.cldk.schema.JParameter;
import com.ibm.cldk.schema.JType;
import com.ibm.cldk.schema.Span;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * L4 stage 8: the interprocedural {@code summary} edges. A summary edge is a shortcut <em>inside a
 * caller</em> — {@code <call>/actual_in:j → <call>/actual_out} at one call site — recording that data
 * entering the callee through argument {@code j} may come back out through its return value. It
 * exists so a consumer (slicing, taint) can cross a call in one hop instead of descending into the
 * callee.
 *
 * <p>Emitting it needs the callee's transfer relation {@code flows(callee) ⊆ params × {$ret}}, which
 * needs <em>its</em> callees' first — hence the bottom-up walk over {@link Scc#condense} (callees
 * first), with mutual recursion resolved by iterating one component to a fixpoint.
 *
 * <p>The relation is derived <em>syntactically</em>, in the weak-update (may-flow, never-drop)
 * posture the L4 design takes: a parameter reaches the return if the callable's {@code ddg} carries
 * it there, if a call site passes it on and that callee's own summary returns it, or if the
 * parameter is simply named in a {@code return} expression. That last rule is what makes the pass
 * useful at all on ordinary code — {@code int c(int z) { return z - 3; }} has no local variable, so
 * no def-use, so no {@code ddg} edge to follow — at the cost of counting a parameter merely
 * <em>mentioned</em> in a return as flowing. Over-approximating there is the accepted trade.
 */
public final class SummaryPass {

    private SummaryPass() {}

    /**
     * Sets {@code summary} on every callable holding at least one pass-through call site; leaves it
     * null (absent — "no fact") elsewhere. Runs after {@link SdgVertices#apply}, whose synthetic
     * vertices are both this pass's seeds and its edge endpoints.
     *
     * @param fieldDepth the k of the access-path k-limit the {@code ddg} was built with. Flow labels
     *     here are parameter <em>indices</em>, and {@link AccessPath#of} truncates only the tail of a
     *     path, never its base segment — so base matching already agrees with any k, and the label
     *     set is bounded by arity rather than by k. Carried because it is where the bound enters once
     *     the relation grows past {@code param → $ret} to field-sensitive labels.
     */
    public static void apply(Map<String, JModule> modules, List<JCallEdge> callGraph, int fieldDepth) {
        // ponytail: single-threaded. Wavefront parallelism over the SCC DAG (a component may run as
        // soon as its successors have) drops straight in if this ever dominates a real run — the
        // condensation already supplies the dependency order.
        // ponytail: recomputed every run. Caching summaries in `cache_dir` waits on a cache that
        // survives analysisLevel >= 3, which CodeAnalyzer currently bypasses outright.
        Map<String, Fn> fns = index(modules);
        Map<String, Set<Integer>> flows = solve(fns, callGraph);
        emit(fns, flows);
    }

    // ----- stage 1: the per-callable transfer relation -------------------------------------------

    /** One resolved, in-project call site — where bridge edges and summary edges both come from. */
    private static final class Site {
        private final String local;
        private final String callee;
        private final List<String> args;
        private final boolean hasActualOut;

        Site(String local, String callee, List<String> args, boolean hasActualOut) {
            this.local = local;
            this.callee = callee;
            this.args = args;
            this.hasActualOut = hasActualOut;
        }

        String actualIn(int j) {
            return local + "/actual_in:" + j;
        }

        String actualOut() {
            return local + "/actual_out";
        }
    }

    /**
     * The round-invariant facts one callable contributes. Everything here is pure syntax, so it is
     * computed once; only the callee bridges change between fixpoint rounds.
     */
    private static final class Fn {
        private final JCallable callable;
        /** {@code ddg} as adjacency (src → dsts) — the fixed half of the reachability graph. */
        private final Map<String, List<String>> ddg = new LinkedHashMap<>();
        /** Parameter index → the body nodes at which its value is first visible. */
        private final Map<Integer, Set<String>> seeds = new LinkedHashMap<>();
        /** Reaching one of these means "the value left through the return". */
        private final Set<String> sinks = new LinkedHashSet<>();

        private final List<Site> sites = new ArrayList<>();

        Fn(JCallable callable) {
            this.callable = callable;
        }
    }

    /** Indexes every callable's static facts, keyed by callable id. */
    private static Map<String, Fn> index(Map<String, JModule> modules) {
        Map<String, Fn> fns = new LinkedHashMap<>();
        for (JModule module : modules.values()) {
            // Decoded once per module, not per node: `span.bytes` are offsets into this array.
            byte[] source =
                    module.getSource() == null ? null : module.getSource().getBytes(StandardCharsets.UTF_8);
            walkTypes(module.getTypes(), source, fns);
        }
        return fns;
    }

    /** Mirrors {@link SdgVertices}'s traversal (nested types, then each callable's local types). */
    private static void walkTypes(Map<String, JType> types, byte[] source, Map<String, Fn> fns) {
        for (JType t : types.values()) {
            walkTypes(t.getTypes(), source, fns);
            for (JCallable c : t.getCallables().values()) {
                if (c.getId() != null) {
                    fns.put(c.getId(), facts(c, source));
                }
                walkTypes(c.getTypes(), source, fns);
            }
        }
    }

    /** Stage 1's syntactic half: the reachability graph, the sinks, the call sites and the seeds. */
    private static Fn facts(JCallable c, byte[] source) {
        Fn fn = new Fn(c);
        if (c.getDdg() != null) {
            for (JDdgEdge e : c.getDdg()) {
                fn.ddg.computeIfAbsent(e.getSrc(), k -> new ArrayList<>()).add(e.getDst());
            }
        }

        Map<String, JBodyNode> body = c.getBody();
        Map<String, String> returnText = new LinkedHashMap<>();
        for (Map.Entry<String, JBodyNode> entry : body.entrySet()) {
            JBodyNode node = entry.getValue();
            if ("return".equals(node.getKind())) {
                fn.sinks.add(entry.getKey());
                String text = slice(source, node.getSpan());
                if (text != null) {
                    returnText.put(entry.getKey(), text);
                }
            } else if ("call".equals(node.getKind())
                    && node.getCallee() != null
                    && !node.getCallee().contains("/@external/")) {
                fn.sites.add(new Site(
                        entry.getKey(),
                        node.getCallee(),
                        node.getArgumentExpr(),
                        body.containsKey(entry.getKey() + "/actual_out")));
            }
        }
        if (body.containsKey("@formal_out")) {
            fn.sinks.add("@formal_out");
        }

        List<JParameter> params = c.getParameters();
        for (int i = 0; i < params.size(); i++) {
            Set<String> seeds = seedsFor(fn, params.get(i).getName(), returnText);
            if (!seeds.isEmpty()) {
                fn.seeds.put(i, seeds);
            }
        }
        return fn;
    }

    /**
     * Where a parameter's value is visible, syntactically: the def end of any {@code ddg} edge whose
     * access path is rooted at it, any call-site {@code actual_in} vertex whose argument text names
     * it, and any {@code return} statement whose text names it (see the class javadoc — a parameter
     * flowing straight to the return is the commonest summary shape and leaves no def-use trail).
     */
    private static Set<String> seedsFor(Fn fn, String name, Map<String, String> returnText) {
        Set<String> seeds = new LinkedHashSet<>();
        if (name == null || name.isEmpty()) {
            return seeds;
        }
        Pattern word = Pattern.compile("\\b" + Pattern.quote(name) + "\\b");

        List<JDdgEdge> ddg = fn.callable.getDdg();
        if (ddg != null) {
            for (JDdgEdge e : ddg) {
                if (name.equals(base(e.getVar()))) {
                    seeds.add(e.getSrc());
                }
            }
        }
        for (Site site : fn.sites) {
            for (int j = 0; j < site.args.size(); j++) {
                String vertex = site.actualIn(j);
                if (site.args.get(j) != null
                        && word.matcher(site.args.get(j)).find()
                        && fn.callable.getBody().containsKey(vertex)) {
                    seeds.add(vertex);
                }
            }
        }
        for (Map.Entry<String, String> ret : returnText.entrySet()) {
            if (word.matcher(ret.getValue()).find()) {
                seeds.add(ret.getKey());
            }
        }
        return seeds;
    }

    /** An access path's base segment — the text before the first {@code .} or {@code [}. */
    private static String base(String var) {
        if (var == null) {
            return null;
        }
        int dot = var.indexOf('.');
        int bracket = var.indexOf('[');
        int cut = dot < 0 ? bracket : (bracket < 0 ? dot : Math.min(dot, bracket));
        return cut < 0 ? var : var.substring(0, cut);
    }

    /** The UTF-8 byte slice a node's {@code span} names, or null when the span is unusable. */
    private static String slice(byte[] source, Span span) {
        if (source == null || span == null || span.getBytes() == null || span.getBytes().length < 2) {
            return null;
        }
        int from = span.getBytes()[0];
        int to = span.getBytes()[1];
        if (from < 0 || to > source.length || from >= to) {
            return null;
        }
        return new String(source, from, to - from, StandardCharsets.UTF_8);
    }

    // ----- stage 2: the bottom-up SCC fixpoint ---------------------------------------------------

    /**
     * Solves every callable's {@code flows} relation, callees first. Within one component each member
     * is recomputed until none grows. Termination is structural: a member's seeds, sinks and
     * {@code ddg} are fixed, the only other edges — the callee bridges — appear as callee relations
     * grow, and the result is unioned in rather than replaced, so every relation grows monotonically;
     * {@code flows(c) ⊆ {0 … arity(c)-1}} bounds it, so a component settles in at most (its members'
     * total arity + 1) rounds.
     */
    private static Map<String, Set<Integer>> solve(Map<String, Fn> fns, List<JCallEdge> callGraph) {
        Map<String, Set<Integer>> flows = new LinkedHashMap<>();
        for (List<String> component : Scc.condense(fns.keySet(), callGraph)) {
            boolean changed = true;
            while (changed) {
                changed = false;
                for (String id : component) {
                    Fn fn = fns.get(id);
                    if (fn == null || fn.seeds.isEmpty()) {
                        continue; // no seed, no flow — and seeds never appear later
                    }
                    Set<Integer> reaching = computeFlows(fn, fns, flows);
                    changed |= flows.computeIfAbsent(id, k -> new TreeSet<>()).addAll(reaching);
                }
            }
        }
        return flows;
    }

    /** One iteration of a callable's transfer relation: which parameters reach a return sink. */
    private static Set<Integer> computeFlows(Fn fn, Map<String, Fn> fns, Map<String, Set<Integer>> flows) {
        // Built once per callable per round, then walked once per parameter.
        Map<String, List<String>> graph = new LinkedHashMap<>(fn.ddg);
        for (Site site : fn.sites) {
            for (int j : passThrough(site, fns, flows)) {
                graph.computeIfAbsent(site.actualIn(j), k -> new ArrayList<>()).add(site.actualOut());
            }
        }

        Set<Integer> reaching = new TreeSet<>();
        for (Map.Entry<Integer, Set<String>> seed : fn.seeds.entrySet()) {
            if (reaches(graph, seed.getValue(), fn.sinks)) {
                reaching.add(seed.getKey());
            }
        }
        return reaching;
    }

    /** BFS from {@code seeds}; true as soon as a sink is touched. */
    private static boolean reaches(Map<String, List<String>> graph, Set<String> seeds, Set<String> sinks) {
        Set<String> seen = new LinkedHashSet<>(seeds);
        Deque<String> queue = new ArrayDeque<>(seeds);
        while (!queue.isEmpty()) {
            String node = queue.poll();
            if (sinks.contains(node)) {
                return true;
            }
            for (String next : graph.getOrDefault(node, List.of())) {
                if (seen.add(next)) {
                    queue.add(next);
                }
            }
        }
        return false;
    }

    /**
     * The argument positions of {@code site} the callee hands back through its return, per its
     * relation <em>as currently known</em> — empty for a callee still being solved in this same SCC,
     * which the next round then picks up. An argument past the callee's last formal collapses onto
     * it, matching how {@link SdgVertices} wires varargs {@code param_in} edges.
     */
    private static List<Integer> passThrough(Site site, Map<String, Fn> fns, Map<String, Set<Integer>> flows) {
        Set<Integer> calleeFlows = flows.get(site.callee);
        Fn callee = fns.get(site.callee);
        if (!site.hasActualOut || calleeFlows == null || callee == null) {
            return List.of();
        }
        int arity = callee.callable.getParameters().size();
        List<Integer> out = new ArrayList<>();
        for (int j = 0; arity > 0 && j < site.args.size(); j++) {
            if (calleeFlows.contains(Math.min(j, arity - 1))) {
                out.add(j);
            }
        }
        return out;
    }

    // ----- stage 3: emission ---------------------------------------------------------------------

    /** Writes each caller's shortcut edges; a caller with none keeps {@code summary} absent. */
    private static void emit(Map<String, Fn> fns, Map<String, Set<Integer>> flows) {
        for (Fn fn : fns.values()) {
            List<JIdEdge> summary = new ArrayList<>();
            for (Site site : fn.sites) {
                for (int j : passThrough(site, fns, flows)) {
                    if (fn.callable.getBody().containsKey(site.actualIn(j))) {
                        summary.add(edge(site.actualIn(j), site.actualOut()));
                    }
                }
            }
            if (!summary.isEmpty()) {
                summary.sort(Comparator.comparing(JIdEdge::getSrc).thenComparing(JIdEdge::getDst));
                fn.callable.setSummary(summary);
            }
        }
    }

    private static JIdEdge edge(String src, String dst) {
        JIdEdge e = new JIdEdge();
        e.setSrc(src);
        e.setDst(dst);
        return e;
    }
}
