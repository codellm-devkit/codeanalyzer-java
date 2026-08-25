package com.ibm.cldk.syntactic_analysis.controlflow;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.ibm.cldk.schema.JBodyNode;
import com.ibm.cldk.syntactic_analysis.L1BuildContext;
import java.util.Map;

/**
 * Populates the body-node set in a {@link ControlFlowGraph}: seeds the L1 call nodes, then creates
 * one node per statement in the callable body (recursing into nested bodies). Both the AST engine
 * ({@link CfgBuilder}) and the forthcoming WALA engine call this method so the two engines share an
 * identical node set — the differential gate can then compare them.
 *
 * <p>Node identity rules (line:col anchor, bare-call reuse) are exactly those {@link CfgBuilder}
 * uses. Moving them here is a pure extract — the AST engine's output is unchanged. Kind mapping:
 * {@code branch} for {@code if}, {@code loop} for while/for/for-each/do, {@code switch} for switch,
 * {@code return} for return statements, {@code statement} for everything else. A seeded L1 call node
 * keeps its {@code call} kind (additive invariant: {@code ensureNode} never overwrites an existing
 * entry).
 */
public final class BodyNodeBuilder {

    private BodyNodeBuilder() {}

    /**
     * Seed {@code g} with the L1 call nodes from {@code existingBody}, then walk {@code body} and
     * create one node per statement with the appropriate kind. Calling this before
     * {@link CfgBuilder} links edges means every {@code ensureNode} call in the edge-linking pass
     * is a no-op for already-created nodes — the additive invariant holds.
     */
    public static void populate(ControlFlowGraph g, BlockStmt body,
            Map<String, JBodyNode> existingBody, L1BuildContext ctx) {
        existingBody.forEach(g::seed);
        visitBlock(g, body, ctx);
    }

    private static void visitBlock(ControlFlowGraph g, BlockStmt block, L1BuildContext ctx) {
        for (Statement s : block.getStatements()) {
            visit(g, s, ctx);
        }
    }

    private static void visit(ControlFlowGraph g, Statement s, L1BuildContext ctx) {
        if (s.isLabeledStmt()) {
            visit(g, s.asLabeledStmt().getStatement(), ctx);
            return;
        }
        if (s.isBlockStmt()) {
            visitBlock(g, s.asBlockStmt(), ctx);
            return;
        }
        if (s.isIfStmt()) {
            IfStmt ifStmt = s.asIfStmt();
            ensure(g, s, "branch", ctx);
            visit(g, ifStmt.getThenStmt(), ctx);
            ifStmt.getElseStmt().ifPresent(el -> visit(g, el, ctx));
            return;
        }
        if (s.isWhileStmt()) {
            ensure(g, s, "loop", ctx);
            visit(g, s.asWhileStmt().getBody(), ctx);
            return;
        }
        if (s.isForStmt()) {
            ensure(g, s, "loop", ctx);
            visit(g, s.asForStmt().getBody(), ctx);
            return;
        }
        if (s.isForEachStmt()) {
            ensure(g, s, "loop", ctx);
            visit(g, s.asForEachStmt().getBody(), ctx);
            return;
        }
        if (s.isDoStmt()) {
            ensure(g, s, "loop", ctx);
            visit(g, s.asDoStmt().getBody(), ctx);
            return;
        }
        if (s.isSwitchStmt()) {
            SwitchStmt sw = s.asSwitchStmt();
            ensure(g, s, "switch", ctx);
            for (SwitchEntry entry : sw.getEntries()) {
                for (Statement es : entry.getStatements()) {
                    visit(g, es, ctx);
                }
            }
            return;
        }
        if (s.isTryStmt()) {
            TryStmt tryStmt = s.asTryStmt();
            visitBlock(g, tryStmt.getTryBlock(), ctx);
            for (CatchClause cc : tryStmt.getCatchClauses()) {
                visitBlock(g, cc.getBody(), ctx);
            }
            tryStmt.getFinallyBlock().ifPresent(fb -> visitBlock(g, fb, ctx));
            return;
        }
        if (s.isSynchronizedStmt()) {
            visit(g, s.asSynchronizedStmt().getBody(), ctx);
            return;
        }
        if (s.isReturnStmt()) {
            ensure(g, s, "return", ctx);
            return;
        }
        // break, continue, throw, expression statements, empty statements, etc.
        ensure(g, s, "statement", ctx);
    }

    private static void ensure(ControlFlowGraph g, Statement s, String kind, L1BuildContext ctx) {
        String id = nodeIdFor(s);
        g.ensureNode(id, kind, ctx.spanOf(s));
        g.recordAst(id, s);
    }

    /**
     * The body-node local id ({@code line:col}) at a statement's addressing anchor. Package-private
     * so {@link CfgBuilder} can delegate to it without duplicating the rule.
     */
    static String nodeIdFor(Statement s) {
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
