package com.ibm.cldk.syntactic_analysis.controlflow;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.ibm.cldk.schema.JBodyNode;
import com.ibm.cldk.syntactic_analysis.L1BuildContext;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link ControlFlowGraph} from a callable's JavaParser {@code BlockStmt}, projecting each
 * source statement to a body node keyed by its {@code line:col} anchor. Control-flow structure is
 * derived syntactically from the AST (the "ast" L3 engine): the edges are exact, no build required.
 *
 * <p>This is the structured-CFG core — a recursive {@code link} that wires a statement into the graph
 * and connects its normal exit to a {@code next} target. Later tasks add cases (conditionals, loops,
 * switch, break/continue, exceptions); this revision handles straight-line sequences and treats every
 * other statement as a single opaque node.
 */
public final class CfgBuilder {

    private CfgBuilder() {}

    public static ControlFlowGraph build(BlockStmt body, Map<String, JBodyNode> existingBody, L1BuildContext ctx) {
        ControlFlowGraph g = new ControlFlowGraph();
        // Seed the L1 call nodes so a bare-call statement reuses its call node rather than duplicating it.
        existingBody.forEach(g::seed);
        String first = linkSequence(g, body.getStatements(), ControlFlowGraph.EXIT, ctx);
        g.addEdge(ControlFlowGraph.ENTRY, first, "fallthrough");
        return g;
    }

    /** Link a sequence of statements so each falls through to the next; return the sequence's entry id. */
    private static String linkSequence(ControlFlowGraph g, List<Statement> stmts, String next, L1BuildContext ctx) {
        String cur = next;
        for (int i = stmts.size() - 1; i >= 0; i--) {
            cur = link(g, stmts.get(i), cur, ctx);
        }
        return cur;
    }

    /**
     * Wire statement {@code s} into the graph and connect its normal exit to {@code next}. Returns the
     * entry node id of {@code s}. The generic case (here) is a single node that falls through to
     * {@code next}; later tasks branch on the statement kind before this fallthrough.
     */
    private static String link(ControlFlowGraph g, Statement s, String next, L1BuildContext ctx) {
        if (s.isBlockStmt()) {
            return linkSequence(g, s.asBlockStmt().getStatements(), next, ctx);
        }
        if (s.isIfStmt()) {
            return linkIf(g, s.asIfStmt(), next, ctx);
        }
        if (s.isReturnStmt()) {
            String id = ensure(g, s, "return", ctx);
            g.addEdge(id, ControlFlowGraph.EXIT, "return");
            return id;
        }
        if (s.isThrowStmt()) {
            // A throw with no enclosing handler abruptly exits the method; handler routing (to the
            // nearest catch/finally) is added with the exception / try-catch work.
            String id = ensure(g, s, "statement", ctx);
            g.addEdge(id, ControlFlowGraph.EXIT, "exception");
            return id;
        }
        // ensureNode never overwrites: a seeded call node keeps its "call" kind and identity.
        String id = ensure(g, s, "statement", ctx);
        g.addEdge(id, next, "fallthrough");
        return id;
    }

    /** An {@code if}: a branch node whose true/false edges enter the arms, both rejoining at {@code next}. */
    private static String linkIf(ControlFlowGraph g, IfStmt s, String next, L1BuildContext ctx) {
        String id = ensure(g, s, "branch", ctx);
        g.addEdge(id, link(g, s.getThenStmt(), next, ctx), "true");
        if (s.getElseStmt().isPresent()) {
            g.addEdge(id, link(g, s.getElseStmt().get(), next, ctx), "false");
        } else {
            g.addEdge(id, next, "false");
        }
        return id;
    }

    /** Materialise the node for {@code s} at its anchor with the given kind (never overwriting). */
    private static String ensure(ControlFlowGraph g, Statement s, String kind, L1BuildContext ctx) {
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
