package com.ibm.cldk.syntactic_analysis.controlflow;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.BreakStmt;
import com.github.javaparser.ast.stmt.ContinueStmt;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.LabeledStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.ibm.cldk.schema.JBodyNode;
import com.ibm.cldk.syntactic_analysis.L1BuildContext;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

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

    /** Enclosing loop/switch targets for break/continue; innermost first (Deque head). */
    private final Deque<Frame> frames = new ArrayDeque<>();

    /** A label seen on a {@code LabeledStmt}, consumed by the loop/switch it immediately precedes. */
    private String pendingLabel;

    /** A break/continue target: where {@code break} and {@code continue} go, plus the construct's label. */
    private static final class Frame {
        private final String breakTarget;
        private final String continueTarget; // null for a switch or labeled block (no continue target)
        private final String label; // null when unlabeled

        Frame(String breakTarget, String continueTarget, String label) {
            this.breakTarget = breakTarget;
            this.continueTarget = continueTarget;
            this.label = label;
        }
    }

    private CfgBuilder(L1BuildContext ctx) {
        this.ctx = ctx;
    }

    public static ControlFlowGraph build(BlockStmt body, Map<String, JBodyNode> existingBody, L1BuildContext ctx) {
        CfgBuilder b = new CfgBuilder(ctx);
        // Seed the L1 call nodes so a bare-call statement reuses its call node rather than duplicating it.
        existingBody.forEach(b.g::seed);
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
        if (s.isBreakStmt()) {
            return linkBreak(s.asBreakStmt());
        }
        if (s.isContinueStmt()) {
            return linkContinue(s.asContinueStmt());
        }
        if (s.isReturnStmt()) {
            String id = ensure(s, "return");
            g.addEdge(id, ControlFlowGraph.EXIT, "return");
            return id;
        }
        if (s.isThrowStmt()) {
            // A throw with no enclosing handler abruptly exits the method; handler routing (to the
            // nearest catch/finally) is added with the exception / try-catch work.
            String id = ensure(s, "statement");
            g.addEdge(id, ControlFlowGraph.EXIT, "exception");
            return id;
        }
        // ensureNode never overwrites: a seeded call node keeps its "call" kind and identity.
        String id = ensure(s, "statement");
        g.addEdge(id, next, kindToNext);
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
        frames.push(new Frame(next, loop, takeLabel()));
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
        frames.push(new Frame(next, loop, takeLabel()));
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
        frames.push(new Frame(next, null, takeLabel())); // break -> join; a switch has no continue
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
        frames.push(new Frame(next, null, label));
        try {
            return link(inner, next, kindToNext);
        } finally {
            frames.pop();
        }
    }

    private String linkBreak(BreakStmt s) {
        String id = ensure(s, "statement");
        Frame f = targetFrame(s.getLabel().map(l -> l.asString()).orElse(null), false);
        g.addEdge(id, f.breakTarget, "break");
        return id;
    }

    private String linkContinue(ContinueStmt s) {
        String id = ensure(s, "statement");
        Frame f = targetFrame(s.getLabel().map(l -> l.asString()).orElse(null), true);
        g.addEdge(id, f.continueTarget, "continue");
        return id;
    }

    /** Resolve the break/continue target: the named frame, or the innermost matching one. */
    private Frame targetFrame(String label, boolean needsContinue) {
        for (Frame f : frames) {
            boolean labelOk = label == null || label.equals(f.label);
            boolean kindOk = !needsContinue || f.continueTarget != null;
            if (labelOk && kindOk) {
                return f;
            }
        }
        // Compilable source always has an enclosing target; degrade to @exit rather than throwing.
        return new Frame(ControlFlowGraph.EXIT, ControlFlowGraph.EXIT, null);
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
        String id = nodeIdFor(s);
        g.ensureNode(id, kind, ctx.spanOf(s));
        return id;
    }

    /** The body-node local id ({@code line:col}) at a statement's addressing anchor. */
    private static String nodeIdFor(Statement s) {
        return anchorOfStatement(s).getRange()
                .map(r -> r.begin.line + ":" + r.begin.column)
                .orElse("0:0");
    }

    /**
     * A bare-call statement's node IS the L1 {@code call} node emitted at the invoked-name /
     * instantiated-type anchor (the additive invariant), so key it there; every other statement is
     * keyed at its own begin. Mirrors {@code CallSiteBuilder}'s anchoring.
     */
    private static Node anchorOfStatement(Statement s) {
        if (s instanceof ExpressionStmt) {
            Expression e = ((ExpressionStmt) s).getExpression();
            if (e instanceof MethodCallExpr) {
                return ((MethodCallExpr) e).getName();
            }
            if (e instanceof ObjectCreationExpr) {
                return ((ObjectCreationExpr) e).getType();
            }
        }
        return s;
    }
}
