package com.ibm.cldk.syntactic_analysis.dataflow;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.ibm.cldk.schema.JDdgEdge;
import com.ibm.cldk.syntactic_analysis.controlflow.ControlFlowGraph;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the data-dependence overlay by a classic monotone reaching-definitions fixpoint over the
 * {@link ControlFlowGraph}. Defs and uses are k-limited access paths ({@link AccessPath}); a definition
 * of a path kills prior definitions of that path and its extensions (prefix-kill), and a use is joined
 * to every reaching definition whose path overlaps it. Edges are {@code prov:["ssa"]} — syntactic,
 * object-insensitive (see the L3 design's §4.5); aliasing is deferred to L4.
 */
public final class DdgBuilder {

    private static final List<String> SSA = List.of("ssa");

    private DdgBuilder() {}

    private static final class Def {
        private final String path;
        private final String node;

        Def(String path, String node) {
            this.path = path;
            this.node = node;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Def)) {
                return false;
            }
            Def d = (Def) o;
            return path.equals(d.path) && node.equals(d.node);
        }

        @Override
        public int hashCode() {
            return path.hashCode() * 31 + node.hashCode();
        }
    }

    public static List<JDdgEdge> build(ControlFlowGraph cfg, int fieldDepth) {
        List<String> nodes = reachableFromEntry(cfg);
        Map<String, Set<String>> defs = new HashMap<>();
        Map<String, Set<String>> uses = new HashMap<>();
        for (String n : nodes) {
            Set<String> d = new LinkedHashSet<>();
            Set<String> u = new LinkedHashSet<>();
            collect(cfg.astNode(n), d, u, fieldDepth);
            defs.put(n, d);
            uses.put(n, u);
        }

        Map<String, Set<Def>> in = new HashMap<>();
        Map<String, Set<Def>> out = new HashMap<>();
        for (String n : nodes) {
            in.put(n, new HashSet<>());
            out.put(n, new HashSet<>());
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String n : nodes) {
                Set<Def> newIn = new HashSet<>();
                for (String p : cfg.predecessors(n)) {
                    Set<Def> po = out.get(p);
                    if (po != null) {
                        newIn.addAll(po);
                    }
                }
                Set<Def> newOut = new HashSet<>();
                for (Def d : newIn) {
                    if (!killed(d.path, defs.get(n))) {
                        newOut.add(d);
                    }
                }
                for (String p : defs.get(n)) {
                    newOut.add(new Def(p, n));
                }
                if (!newIn.equals(in.get(n)) || !newOut.equals(out.get(n))) {
                    in.put(n, newIn);
                    out.put(n, newOut);
                    changed = true;
                }
            }
        }

        List<JDdgEdge> edges = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String n : nodes) {
            for (String u : uses.get(n)) {
                for (Def d : in.get(n)) {
                    if (overlaps(d.path, u) && seen.add(d.node + " " + n + " " + u)) {
                        JDdgEdge e = new JDdgEdge();
                        e.setSrc(d.node);
                        e.setDst(n);
                        e.setVar(u);
                        e.setProv(SSA);
                        edges.add(e);
                    }
                }
            }
        }
        edges.sort(Comparator.comparing(JDdgEdge::getSrc)
                .thenComparing(JDdgEdge::getDst)
                .thenComparing(JDdgEdge::getVar));
        return edges;
    }

    private static List<String> reachableFromEntry(ControlFlowGraph cfg) {
        List<String> order = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(ControlFlowGraph.ENTRY);
        seen.add(ControlFlowGraph.ENTRY);
        while (!queue.isEmpty()) {
            String x = queue.poll();
            order.add(x);
            for (String y : cfg.successors(x)) {
                if (seen.add(y)) {
                    queue.add(y);
                }
            }
        }
        return order;
    }

    /** A definition of {@code path} is killed by a node that redefines {@code path} or a prefix of it. */
    private static boolean killed(String path, Set<String> nodeDefs) {
        for (String d : nodeDefs) {
            if (path.equals(d) || path.startsWith(d + ".") || path.startsWith(d + "[")) {
                return true;
            }
        }
        return false;
    }

    /** A definition reaches a use when their access paths overlap (one is a prefix of the other). */
    private static boolean overlaps(String def, String use) {
        return def.equals(use)
                || use.startsWith(def + ".") || use.startsWith(def + "[")
                || def.startsWith(use + ".") || def.startsWith(use + "[");
    }

    /** Collect the defs and uses a CFG node contributes, from its own header expressions (not nested bodies). */
    private static void collect(Node ast, Set<String> defs, Set<String> uses, int k) {
        if (ast == null) {
            return;
        }
        if (ast instanceof IfStmt) {
            expr(((IfStmt) ast).getCondition(), defs, uses, k);
        } else if (ast instanceof WhileStmt) {
            expr(((WhileStmt) ast).getCondition(), defs, uses, k);
        } else if (ast instanceof DoStmt) {
            expr(((DoStmt) ast).getCondition(), defs, uses, k);
        } else if (ast instanceof ForStmt) {
            ForStmt f = (ForStmt) ast;
            f.getInitialization().forEach(e -> expr(e, defs, uses, k));
            f.getCompare().ifPresent(e -> expr(e, defs, uses, k));
            f.getUpdate().forEach(e -> expr(e, defs, uses, k));
        } else if (ast instanceof ForEachStmt) {
            ForEachStmt fe = (ForEachStmt) ast;
            fe.getVariable().getVariables().forEach(vd -> defs.add(vd.getNameAsString()));
            expr(fe.getIterable(), defs, uses, k);
        } else if (ast instanceof SwitchStmt) {
            expr(((SwitchStmt) ast).getSelector(), defs, uses, k);
        } else if (ast instanceof ReturnStmt) {
            ((ReturnStmt) ast).getExpression().ifPresent(e -> expr(e, defs, uses, k));
        } else if (ast instanceof ThrowStmt) {
            expr(((ThrowStmt) ast).getExpression(), defs, uses, k);
        } else if (ast instanceof ExpressionStmt) {
            expr(((ExpressionStmt) ast).getExpression(), defs, uses, k);
        }
        // break/continue/synchronized/block/labeled contribute no direct defs or uses.
    }

    private static void expr(Expression e, Set<String> defs, Set<String> uses, int k) {
        if (e instanceof AssignExpr) {
            AssignExpr a = (AssignExpr) e;
            String target = AccessPath.of(a.getTarget(), k);
            if (target != null) {
                defs.add(target);
                if (a.getOperator() != AssignExpr.Operator.ASSIGN) {
                    uses.add(target); // a compound assignment reads its target too
                }
            }
            targetReads(a.getTarget(), defs, uses, k);
            expr(a.getValue(), defs, uses, k);
            return;
        }
        if (e instanceof VariableDeclarationExpr) {
            ((VariableDeclarationExpr) e).getVariables().forEach(vd -> {
                defs.add(vd.getNameAsString());
                vd.getInitializer().ifPresent(init -> expr(init, defs, uses, k));
            });
            return;
        }
        if (e instanceof UnaryExpr) {
            UnaryExpr u = (UnaryExpr) e;
            if (isIncDec(u.getOperator())) {
                String p = AccessPath.of(u.getExpression(), k);
                if (p != null) {
                    defs.add(p);
                    uses.add(p);
                    return;
                }
            }
            expr(u.getExpression(), defs, uses, k);
            return;
        }
        String path = AccessPath.of(e, k);
        if (path != null) {
            uses.add(path);
            if (e instanceof ArrayAccessExpr) {
                expr(((ArrayAccessExpr) e).getIndex(), defs, uses, k); // the index variable is a real use
            }
            return;
        }
        for (Node c : e.getChildNodes()) {
            if (c instanceof Expression) {
                expr((Expression) c, defs, uses, k);
            }
        }
    }

    /** Reads implied by an assignment target: an array index/base, or a field's scope. */
    private static void targetReads(Expression target, Set<String> defs, Set<String> uses, int k) {
        if (target instanceof ArrayAccessExpr) {
            ArrayAccessExpr aa = (ArrayAccessExpr) target;
            String base = AccessPath.of(aa.getName(), k);
            if (base != null) {
                uses.add(base);
            }
            expr(aa.getIndex(), defs, uses, k);
        } else if (target instanceof FieldAccessExpr) {
            String scope = AccessPath.of(((FieldAccessExpr) target).getScope(), k);
            if (scope != null) {
                uses.add(scope);
            }
        }
    }

    private static boolean isIncDec(UnaryExpr.Operator op) {
        return op == UnaryExpr.Operator.PREFIX_INCREMENT
                || op == UnaryExpr.Operator.POSTFIX_INCREMENT
                || op == UnaryExpr.Operator.PREFIX_DECREMENT
                || op == UnaryExpr.Operator.POSTFIX_DECREMENT;
    }
}
