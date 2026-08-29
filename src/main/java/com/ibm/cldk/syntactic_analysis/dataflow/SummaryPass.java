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
 * parameter is simply <em>named</em> in a body node's source text. That last rule is what makes the
 * pass useful at all on ordinary code, because {@link DdgBuilder} gives a parameter no defining node
 * — nothing in the {@code ddg} is ever rooted at one. Without it {@code int c(int z) { return z - 3;
 * }} has no def-use to follow at all, and {@code int m(int q) { int t = q; return t; }} has only
 * {@code (decl → ret, var "t")}, which no seed reaches. The cost is counting a parameter merely
 * <em>mentioned</em> in a statement as flowing out of it. Over-approximating there is the accepted
 * trade.
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
        /** The intraprocedural reachability graph (src → dsts) — the fixed half; bridges are added per round. */
        private final Map<String, List<String>> graph = new LinkedHashMap<>();
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
                fn.graph.computeIfAbsent(e.getSrc(), k -> new ArrayList<>()).add(e.getDst());
            }
        }

        Map<String, JBodyNode> body = c.getBody();
        Map<String, String> spanText = new LinkedHashMap<>();
        for (Map.Entry<String, JBodyNode> entry : body.entrySet()) {
            JBodyNode node = entry.getValue();
            String text = slice(source, node.getSpan());
            if (text != null) {
                spanText.put(entry.getKey(), text);
            }
            if ("return".equals(node.getKind())) {
                fn.sinks.add(entry.getKey());
            } else if ("call".equals(node.getKind())) {
                // Where a call's value goes next. Deliberately outside the resolved/in-project filter
                // below: an unresolved or external call is still a node the chain has to pass
                // *through*. `int t = Math.abs(id(a));` encloses the `id(a)` site in the `Math.abs`
                // node, which is an orphan (not a CFG node, so no ddg edge leaves it) and has no
                // summary of its own — filtering it out here would strand the hop and silently drop
                // the caller's summary edge, an under-approximation the L4 posture does not allow.
                String outer = enclosing(entry.getKey(), body);
                if (outer != null) {
                    fn.graph.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(outer);
                }
                if (node.getCallee() != null && !node.getCallee().contains("/@external/")) {
                    fn.sites.add(new Site(
                            entry.getKey(),
                            node.getCallee(),
                            node.getArgumentExpr(),
                            body.containsKey(entry.getKey() + "/actual_out")));
                }
            }
        }
        if (body.containsKey("@formal_out")) {
            fn.sinks.add("@formal_out");
        }

        // The other half of that route: a resolved call's returned value reaches the call node. Only
        // resolved sites have an actual_out vertex to leave from, so unlike the hop above this one
        // does belong inside the filter. Together they give actual_out → call node → enclosing node,
        // which is the CFG node the ddg edges actually leave from — the `ddg` does not hang off a
        // call that sits inside a larger expression, since `DdgBuilder` analyses CFG nodes and such a
        // call is not one (`int t = f(x);` puts the def-use edge on the *statement*).
        for (Site site : fn.sites) {
            fn.graph.computeIfAbsent(site.actualOut(), k -> new ArrayList<>()).add(site.local);
        }

        List<JParameter> params = c.getParameters();
        for (int i = 0; i < params.size(); i++) {
            Set<String> seeds = seedsFor(fn, params.get(i).getName(), spanText);
            if (!seeds.isEmpty()) {
                fn.seeds.put(i, seeds);
            }
        }
        return fn;
    }

    /**
     * Where a parameter's value is visible, syntactically: the def end of any {@code ddg} edge whose
     * access path is rooted at it, any call-site {@code actual_in} vertex whose argument text names
     * it, and <em>every</em> body node whose source text names it (see the class javadoc — a parameter
     * has no defining node in the {@code ddg}, so text is the only thing that can put it on the map at
     * all).
     *
     * <p>Every kind with a span is seedable, containers included. A {@code branch}/{@code loop}/
     * {@code switch} span swallows the statements nested inside it, so seeding one on a name mentioned
     * anywhere within hands the parameter that whole node's def-use reach — bulk over-approximation,
     * which this posture explicitly accepts. Excluding them is the direction that is <em>not</em>
     * allowed: those nodes carry real defs and uses of their own ({@code DdgBuilder.collect} attributes
     * a {@code for}-each's loop variable and iterated expression, and an {@code if}'s condition, to the
     * container node), while the header they come from gets no node of its own — so
     * {@code int first(int[] q) { for (int x : q) { return x; } return 0; }} names {@code q} nowhere a
     * non-container node can see, and excluding the loop drops a real flow. {@code entry}/{@code exit}
     * and the synthetic L4 vertices need no rule: they carry no span, so {@link #slice} skips them.
     */
    private static Set<String> seedsFor(Fn fn, String name, Map<String, String> spanText) {
        Set<String> seeds = new LinkedHashSet<>();
        if (name == null || name.isEmpty()) {
            return seeds;
        }
        // Spelling, not binding — this is the syntactic seeding the L4 design settles for, and every
        // way it can over-credit is a may-flow direction the weak-update posture accepts. It credits
        // `name` when it is merely mentioned rather than used (`return other(x) + 1;`, `log(x);`);
        // when the mention is a *shadowing* local or catch parameter of the same spelling, since this
        // reads text and not scopes (`{ int x = 0; sink(x); }` in a method taking `x`); when it is a
        // *field* of something else, since `.` is not a word character (`return a.z;` credits
        // parameter `z`); when it appears inside a string literal (`return "x marks";` credits `x`);
        // and when it sits next to `$`, which Java allows in identifiers but regex `\b` does not
        // treat as a word character (`a$b` credits `a`). None of these can lose a real flow.
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
        for (Map.Entry<String, String> node : spanText.entrySet()) {
            if (word.matcher(node.getValue()).find()) {
                seeds.add(node.getKey());
            }
        }
        return seeds;
    }

    /**
     * The smallest body node whose span strictly encloses {@code local}'s — the statement, outer
     * call, or enclosing {@code branch}/{@code loop}/{@code switch} a nested call sits inside. Null
     * only when <em>nothing</em> encloses it: a call in statement position at the top level of the
     * body, where the call node is itself the CFG node and needs no hop. A call in statement position
     * <em>inside</em> an {@code if}/{@code for}/{@code while} body does get a hop, to that branch or
     * loop node, whose span covers the whole statement — reach then continues along its ddg
     * out-edges, which over-approximates (the condition's dependences are not the call's) in the
     * may-flow direction the L4 posture accepts. Ties break on the node id so the choice cannot
     * depend on map order.
     */
    private static String enclosing(String local, Map<String, JBodyNode> body) {
        int[] inner = bytes(body.get(local));
        if (inner == null) {
            return null;
        }
        String best = null;
        long bestWidth = Long.MAX_VALUE;
        for (Map.Entry<String, JBodyNode> entry : body.entrySet()) {
            int[] outer = bytes(entry.getValue());
            if (entry.getKey().equals(local)
                    || outer == null
                    || outer[0] > inner[0]
                    || outer[1] < inner[1]
                    || (outer[0] == inner[0] && outer[1] == inner[1])) {
                continue;
            }
            long width = (long) outer[1] - outer[0];
            if (best == null || width < bestWidth || (width == bestWidth && entry.getKey().compareTo(best) < 0)) {
                best = entry.getKey();
                bestWidth = width;
            }
        }
        return best;
    }

    /** A node's {@code span.bytes}, or null when it has none (every synthetic vertex). */
    private static int[] bytes(JBodyNode node) {
        if (node == null || node.getSpan() == null || node.getSpan().getBytes() == null) {
            return null;
        }
        return node.getSpan().getBytes().length < 2 ? null : node.getSpan().getBytes();
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
     * reachability graph are fixed, the only other edges — the callee bridges — appear as callee
     * relations grow, and the result is unioned in rather than replaced, so relations only grow;
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
        // Built once per callable per round, then walked once per parameter. Successor lists are
        // copied, not shared: the bridges below would otherwise accumulate into fn.graph each round.
        Map<String, List<String>> graph = new LinkedHashMap<>();
        fn.graph.forEach((node, next) -> graph.put(node, new ArrayList<>(next)));
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
