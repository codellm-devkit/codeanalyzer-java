package com.ibm.cldk.syntactic_analysis.controlflow;

import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.LabeledStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.ibm.cldk.schema.JBodyNode;
import com.ibm.cldk.syntactic_analysis.L1BuildContext;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a {@link ControlFlowGraph} from a callable's JavaParser {@code BlockStmt}, projecting each
 * source statement to a body node keyed by its {@code line:col} anchor. Control-flow structure is
 * derived syntactically from the AST (the "ast" L3 engine): the edges are exact, no build required.
 *
 * <p>The core is a recursive {@code link} that wires a statement into the graph and connects its
 * normal exit to a {@code next} target with a caller-chosen edge kind ({@code fallthrough} in a plain
 * sequence, {@code loop_back} for a loop body's tail). An instance holds the graph and context so the
 * recursion stays readable; later tasks add switch, break/continue, and exception handling.
 */
public final class CfgBuilder {

    private final ControlFlowGraph g = new ControlFlowGraph();
    private final L1BuildContext ctx;

    /**
     * Enclosing scopes, innermost first (Deque head): loops and switches (break/continue targets) and
     * finally regions (abrupt exits are rerouted through them). One stack so an abrupt exit knows which
     * finally regions lie between it and its target.
     */
    private final Deque<Frame> frames = new ArrayDeque<>();

    /** A label seen on a {@code LabeledStmt}, consumed by the loop/switch it immediately precedes. */
    private String pendingLabel;

    /**
     * Enclosing exception-handler targets; innermost first (Deque head). Each {@code try} pushes the set
     * of nodes a thrown exception in its body may reach (all its catch entries, or the finally, or the
     * enclosing handler). A throwing statement edges to every target in the innermost set.
     */
    private final Deque<List<String>> handlers = new ArrayDeque<>();

    /** Distinguishes the temporary sentinel targets used to fan a finally's completion out. */
    private int sentinelSeq;

    /**
     * An enclosing scope: a loop/switch (a break/continue target) or a finally region (which abrupt
     * exits pass through). A finally frame accumulates the {@code continuations} its completion must
     * reach — the union of the normal-next and every abrupt exit's next hop.
     */
    private static final class Frame {
        private enum Kind {
            LOOP,
            SWITCH,
            FINALLY
        }

        private final Kind kind;
        private final String breakTarget; // LOOP/SWITCH
        private final String continueTarget; // LOOP only (null otherwise)
        private final String label; // LOOP/SWITCH; null when unlabeled
        private final String finallyEntry; // FINALLY
        private final Set<String> continuations; // FINALLY (mutable, insertion-ordered)

        private Frame(Kind kind, String breakTarget, String continueTarget, String label,
                String finallyEntry, Set<String> continuations) {
            this.kind = kind;
            this.breakTarget = breakTarget;
            this.continueTarget = continueTarget;
            this.label = label;
            this.finallyEntry = finallyEntry;
            this.continuations = continuations;
        }

        static Frame loop(String breakTarget, String continueTarget, String label) {
            return new Frame(Kind.LOOP, breakTarget, continueTarget, label, null, null);
        }

        static Frame switchScope(String breakTarget, String label) {
            return new Frame(Kind.SWITCH, breakTarget, null, label, null, null);
        }

        static Frame finallyScope(String finallyEntry) {
            return new Frame(Kind.FINALLY, null, null, null, finallyEntry, new LinkedHashSet<>());
        }
    }

    private CfgBuilder(L1BuildContext ctx) {
        this.ctx = ctx;
    }

    public static ControlFlowGraph build(BlockStmt body, Map<String, JBodyNode> existingBody, L1BuildContext ctx) {
        CfgBuilder b = new CfgBuilder(ctx);
        // Populate the shared body-node set first (seeds L1 call nodes, then creates one node per
        // statement). The edge-linking pass below calls ensureNode too, but those calls are no-ops for
        // already-created nodes — the additive invariant holds.
        BodyNodeBuilder.populate(b.g, body, existingBody, ctx);
        String first = b.linkSequence(body.getStatements(), ControlFlowGraph.EXIT, "fallthrough");
        b.g.addEdge(ControlFlowGraph.ENTRY, first, "fallthrough");
        return b.g;
    }

    /**
     * Link a sequence so each statement falls through to the next; only the last connects to
     * {@code next}, with {@code kindToNext}. Returns the sequence's entry id.
     */
    private String linkSequence(List<Statement> stmts, String next, String kindToNext) {
        String cur = next;
        String kind = kindToNext;
        for (int i = stmts.size() - 1; i >= 0; i--) {
            cur = link(stmts.get(i), cur, kind);
            kind = "fallthrough";
        }
        return cur;
    }

    /**
     * Wire statement {@code s} into the graph and connect its normal exit to {@code next}. Returns the
     * entry node id of {@code s}. {@code kindToNext} is the edge kind used for the terminal connection
     * of straight-line control (a loop body's tail passes {@code loop_back}); constructs with their own
     * exit semantics (loops, return/throw) ignore it.
     */
    private String link(Statement s, String next, String kindToNext) {
        if (s.isLabeledStmt()) {
            return linkLabeled(s.asLabeledStmt(), next, kindToNext);
        }
        if (s.isBlockStmt()) {
            return linkSequence(s.asBlockStmt().getStatements(), next, kindToNext);
        }
        if (s.isIfStmt()) {
            return linkIf(s.asIfStmt(), next, kindToNext);
        }
        if (s.isWhileStmt()) {
            return linkWhile(s.asWhileStmt(), next);
        }
        if (s.isForStmt()) {
            return linkFor(s.asForStmt(), next);
        }
        if (s.isForEachStmt()) {
            return linkForEach(s.asForEachStmt(), next);
        }
        if (s.isDoStmt()) {
            return linkDo(s.asDoStmt(), next);
        }
        if (s.isSwitchStmt()) {
            return linkSwitch(s.asSwitchStmt(), next);
        }
        if (s.isTryStmt()) {
            return linkTry(s.asTryStmt(), next);
        }
        if (s.isSynchronizedStmt()) {
            // Monitor enter/exit are not distinct L3 nodes; the body carries the flow (and its throws
            // route to the enclosing handler like any other statement).
            return link(s.asSynchronizedStmt().getBody(), next, kindToNext);
        }
        if (s.isBreakStmt()) {
            return linkBreak(s.asBreakStmt());
        }
        if (s.isContinueStmt()) {
            return linkContinue(s.asContinueStmt());
        }
        if (s.isReturnStmt()) {
            String id = ensure(s, "return");
            routeAbrupt(id, "return", ControlFlowGraph.EXIT, null); // through enclosing finallies, then exit
            if (canThrow(s)) {
                for (String h : currentHandlers()) {
                    g.addEdge(id, h, "exception"); // the returned expression may throw first
                }
            }
            return id;
        }
        if (s.isThrowStmt()) {
            String id = ensure(s, "statement");
            for (String h : throwTargets()) {
                g.addEdge(id, h, "exception");
            }
            return id;
        }
        // ensureNode never overwrites: a seeded call node keeps its "call" kind and identity.
        String id = ensure(s, "statement");
        g.addEdge(id, next, kindToNext);
        // A statement that can throw (contains a call/allocation) routes to the enclosing handler(s), if
        // any. Outside a try there is no explicit edge: the exceptional path is the method exit already
        // reached by normal flow, and edging every call to @exit would swamp the graph.
        if (canThrow(s)) {
            for (String h : currentHandlers()) {
                g.addEdge(id, h, "exception");
            }
        }
        return id;
    }

    /** An {@code if}: a branch node whose true/false edges enter the arms, both rejoining at {@code next}. */
    private String linkIf(IfStmt s, String next, String kindToNext) {
        String id = ensure(s, "branch");
        g.addEdge(id, link(s.getThenStmt(), next, kindToNext), "true");
        if (s.getElseStmt().isPresent()) {
            g.addEdge(id, link(s.getElseStmt().get(), next, kindToNext), "false");
        } else {
            g.addEdge(id, next, "false");
        }
        return id;
    }

    /** A top-tested loop ({@code while}/{@code for}/for-each): true into the body, body tail loops back. */
    private String linkTopTestedLoop(Statement s, Statement body, String next) {
        String loop = ensure(s, "loop");
        frames.push(Frame.loop(next, loop, takeLabel()));
        try {
            g.addEdge(loop, link(body, loop, "loop_back"), "true");
        } finally {
            frames.pop();
        }
        g.addEdge(loop, next, "false");
        return loop;
    }

    private String linkWhile(WhileStmt s, String next) {
        return linkTopTestedLoop(s, s.getBody(), next);
    }

    // The for's init/update are expressions folded onto the loop node (they run before entry and on the
    // back-edge); L3's statement-granularity CFG does not give them their own nodes.
    private String linkFor(ForStmt s, String next) {
        return linkTopTestedLoop(s, s.getBody(), next);
    }

    private String linkForEach(ForEachStmt s, String next) {
        return linkTopTestedLoop(s, s.getBody(), next);
    }

    /** A {@code do}/{@code while}: the body runs first, then the bottom test loops back up to it. */
    private String linkDo(DoStmt s, String next) {
        String loop = ensure(s, "loop"); // the bottom while-condition test
        frames.push(Frame.loop(next, loop, takeLabel()));
        String bodyEntry;
        try {
            bodyEntry = link(s.getBody(), loop, "fallthrough");
        } finally {
            frames.pop();
        }
        g.addEdge(loop, bodyEntry, "loop_back");
        g.addEdge(loop, next, "false");
        return bodyEntry; // a do-while is entered through its body
    }

    /**
     * A {@code switch}: a switch node with a {@code switch_case} edge to each case's entry. Classic
     * ({@code case 1:}) cases fall through to the next case's entry; arrow ({@code case 1 ->}) cases do
     * not. {@code break} inside a case exits to the join (the switch's frame breakTarget). With no
     * {@code default}, an extra {@code switch_case} edge models the no-match path out of the switch.
     */
    private String linkSwitch(SwitchStmt s, String next) {
        String sw = ensure(s, "switch");
        frames.push(Frame.switchScope(next, takeLabel())); // break -> join; a switch has no continue
        try {
            List<SwitchEntry> entries = s.getEntries();
            String[] heads = new String[entries.size()];
            boolean hasDefault = false;
            // Right-to-left so a classic case's tail falls through to the next case's entry.
            String fallInto = next;
            for (int i = entries.size() - 1; i >= 0; i--) {
                SwitchEntry e = entries.get(i);
                heads[i] = linkSequence(e.getStatements(), fallInto, "fallthrough");
                fallInto = e.getType() == SwitchEntry.Type.STATEMENT_GROUP ? heads[i] : next;
                hasDefault |= e.getLabels().isEmpty();
            }
            for (String head : heads) {
                g.addEdge(sw, head, "switch_case");
            }
            if (!hasDefault) {
                g.addEdge(sw, next, "switch_case");
            }
        } finally {
            frames.pop();
        }
        return sw;
    }

    /**
     * A {@code try}/{@code catch}/{@code finally} (and try-with-resources). Every exit from the try —
     * normal completion, each catch, and abrupt exits (return/break/continue) and uncaught throws — runs
     * the finally first. The finally is a single node copy (line:col identity precludes per-path copies);
     * its completion fans out to the <em>union</em> of every continuation that flows through it (the
     * normal next, each abrupt exit's next hop, the enclosing handler for a propagated exception). This
     * is a sound over-approximation: it may add infeasible edges but never omits a real one, and it makes
     * the finally post-dominate the try body (so CDG is correct). A thrown exception in the try body
     * routes to every catch entry (type dispatch is over-approximated), else to the finally. A
     * try-with-resources analyses like a plain try; its implicit {@code close()} has no source position,
     * so it is not a distinct node.
     */
    private String linkTry(TryStmt s, String next) {
        if (!s.getFinallyBlock().isPresent()) {
            return linkTryWithoutFinally(s, next);
        }
        // Link the finally body to a temporary sentinel so its real entry is known (abrupt exits route
        // into it) and its completion can be fanned out to the collected continuations afterwards.
        String sentinel = "#finally-sentinel-" + sentinelSeq++;
        String finallyEntry = link(s.getFinallyBlock().get(), sentinel, "fallthrough");
        Frame fin = Frame.finallyScope(finallyEntry);
        fin.continuations.add(next); // normal completion continues after the finally

        frames.push(fin); // the finally applies to abrupt exits in the catches and the try body
        try {
            List<String> catchEntries = new ArrayList<>();
            for (CatchClause cc : s.getCatchClauses()) {
                catchEntries.add(link(cc.getBody(), finallyEntry, "fallthrough"));
            }
            List<String> tryHandlers;
            if (!catchEntries.isEmpty()) {
                tryHandlers = catchEntries;
            } else {
                // No catch: an uncaught exception runs the finally, then propagates to the enclosing
                // handler (or the method exit).
                tryHandlers = List.of(finallyEntry);
                fin.continuations.addAll(
                        currentHandlers().isEmpty() ? List.of(ControlFlowGraph.EXIT) : currentHandlers());
            }
            handlers.push(tryHandlers);
            String tryEntry;
            try {
                tryEntry = link(s.getTryBlock(), finallyEntry, "fallthrough");
            } finally {
                handlers.pop();
            }
            g.redirect(sentinel, fin.continuations, "fallthrough");
            return tryEntry;
        } finally {
            frames.pop();
        }
    }

    /** A {@code try} with catches but no finally: catches and normal completion flow to {@code next}. */
    private String linkTryWithoutFinally(TryStmt s, String next) {
        List<String> catchEntries = new ArrayList<>();
        for (CatchClause cc : s.getCatchClauses()) {
            catchEntries.add(link(cc.getBody(), next, "fallthrough"));
        }
        List<String> tryHandlers = !catchEntries.isEmpty() ? catchEntries
                : currentHandlers().isEmpty() ? List.of(ControlFlowGraph.EXIT) : currentHandlers();
        handlers.push(tryHandlers);
        try {
            return link(s.getTryBlock(), next, "fallthrough");
        } finally {
            handlers.pop();
        }
    }

    /**
     * Route an abrupt exit ({@code from}, kind {@code return}/{@code break}/{@code continue}) to its
     * {@code target}, threading it through every enclosing finally between the exit and {@code stopAt}
     * (the loop/switch frame it targets, or {@code null} for a return, which passes through them all).
     * Each finally on the way records its next hop as a continuation.
     */
    private void routeAbrupt(String from, String kind, String target, Frame stopAt) {
        List<Frame> fins = enclosingFinallies(stopAt);
        if (fins.isEmpty()) {
            g.addEdge(from, target, kind);
            return;
        }
        g.addEdge(from, fins.get(0).finallyEntry, kind);
        for (int i = 0; i < fins.size(); i++) {
            fins.get(i).continuations.add(i + 1 < fins.size() ? fins.get(i + 1).finallyEntry : target);
        }
    }

    /** The finally frames between the current point and {@code stopAt} (all of them when {@code null}). */
    private List<Frame> enclosingFinallies(Frame stopAt) {
        List<Frame> fins = new ArrayList<>();
        for (Frame f : frames) {
            if (f == stopAt) {
                break;
            }
            if (f.kind == Frame.Kind.FINALLY) {
                fins.add(f);
            }
        }
        return fins;
    }

    /** Where a thrown exception goes: the enclosing handler set, or the method exit when unhandled. */
    private List<String> throwTargets() {
        return currentHandlers().isEmpty() ? List.of(ControlFlowGraph.EXIT) : currentHandlers();
    }

    private List<String> currentHandlers() {
        return handlers.isEmpty() ? List.of() : handlers.peek();
    }

    /** A statement can throw (for exception edges) when it contains a call or an allocation. */
    private static boolean canThrow(Statement s) {
        return s.findFirst(MethodCallExpr.class).isPresent() || s.findFirst(ObjectCreationExpr.class).isPresent();
    }

    /**
     * A labeled statement. A labeled loop's label rides on the loop's own frame (so {@code continue
     * <label>} can find it); any other labeled statement gets a break-only frame so {@code break
     * <label>} exits to {@code next}.
     */
    private String linkLabeled(LabeledStmt s, String next, String kindToNext) {
        String label = s.getLabel().asString();
        Statement inner = s.getStatement();
        if (isLoop(inner) || inner.isSwitchStmt()) {
            pendingLabel = label;
            return link(inner, next, kindToNext);
        }
        frames.push(Frame.switchScope(next, label)); // a labeled block is a break-only target
        try {
            return link(inner, next, kindToNext);
        } finally {
            frames.pop();
        }
    }

    private String linkBreak(BreakStmt s) {
        String id = ensure(s, "statement");
        Frame f = targetFrame(s.getLabel().map(l -> l.asString()).orElse(null), false);
        routeAbrupt(id, "break", f.breakTarget, f); // through any finallies between here and the target
        return id;
    }

    private String linkContinue(ContinueStmt s) {
        String id = ensure(s, "statement");
        Frame f = targetFrame(s.getLabel().map(l -> l.asString()).orElse(null), true);
        routeAbrupt(id, "continue", f.continueTarget, f);
        return id;
    }

    /** Resolve the break/continue target: the named loop/switch, or the innermost one (finallies skipped). */
    private Frame targetFrame(String label, boolean needsContinue) {
        for (Frame f : frames) {
            if (f.kind == Frame.Kind.FINALLY) {
                continue; // a finally is passed through, never a break/continue target
            }
            boolean labelOk = label == null || label.equals(f.label);
            boolean kindOk = !needsContinue || f.continueTarget != null;
            if (labelOk && kindOk) {
                return f;
            }
        }
        // Compilable source always has an enclosing target; degrade to @exit rather than throwing.
        return Frame.loop(ControlFlowGraph.EXIT, ControlFlowGraph.EXIT, null);
    }

    private String takeLabel() {
        String l = pendingLabel;
        pendingLabel = null;
        return l;
    }

    private static boolean isLoop(Statement s) {
        return s.isWhileStmt() || s.isForStmt() || s.isForEachStmt() || s.isDoStmt();
    }

    /** Materialise the node for {@code s} at its anchor with the given kind (never overwriting). */
    private String ensure(Statement s, String kind) {
        String id = BodyNodeBuilder.nodeIdFor(s);
        g.ensureNode(id, kind, ctx.spanOf(s));
        g.recordAst(id, s); // the data-dependence pass reads defs/uses off this statement
        return id;
    }
}
