package com.ibm.cldk.syntactic_analysis;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.ibm.cldk.schema.JBodyNode;
import com.ibm.cldk.utils.Log;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Builds the L1 slice of a callable's {@code body{}}: one {@code call} node per call site that
 * belongs directly to this callable — method invocations, {@code new} constructor invocations, and
 * explicit {@code this(...)}/{@code super(...)} chaining (all three are sites L2 resolves into
 * {@code call_graph} edges, so omitting any of them would lose edges).
 *
 * <p>Nodes are keyed by their <em>local</em> id — a {@code line:col} source position, per the
 * keystone ({@code body} is "keyed by the node's local id"). The full
 * {@code <callable-id>@<local-id>} form is derived only where cross-callable ids are needed (L4's
 * application-scope {@code param_in}/{@code param_out}).
 *
 * <p>The addressing anchor is the <em>invoked name</em> (method name, or instantiated type name),
 * not the enclosing expression's start, so chained calls {@code a.b().c()} get distinct ids instead
 * of colliding. Invocations inside nested local/anonymous classes belong to their own callables and
 * are excluded; lambda bodies (which have no separate callable) are kept. Nodes are ordered by
 * source position so output is deterministic under parallel fan-out.
 *
 * <p>L3 completes {@code body} with the remaining statements. A bare call statement resolves to the
 * same local id as the {@code call} node emitted here — L3 must therefore <em>not</em> overwrite an
 * existing {@code call} node (the call node <em>is</em> that statement, as in the keystone's worked
 * example); rewriting its kind would break the additive invariant.
 */
public final class CallSiteBuilder {

    private final L1BuildContext ctx;

    public CallSiteBuilder(L1BuildContext ctx) {
        this.ctx = ctx;
    }

    public Map<String, JBodyNode> build(BlockStmt body) {
        List<Node> sites = new ArrayList<>();
        body.findAll(MethodCallExpr.class).stream().filter(n -> belongsDirectlyTo(n, body)).forEach(sites::add);
        body.findAll(ObjectCreationExpr.class).stream().filter(n -> belongsDirectlyTo(n, body)).forEach(sites::add);
        body.findAll(ExplicitConstructorInvocationStmt.class).stream()
                .filter(n -> belongsDirectlyTo(n, body))
                .forEach(sites::add);

        sites.sort(Comparator.<Node>comparingInt(n -> anchorPosition(n)[0])
                .thenComparingInt(n -> anchorPosition(n)[1]));

        Map<String, JBodyNode> nodes = new LinkedHashMap<>();
        for (Node site : sites) {
            JBodyNode node = new JBodyNode();
            node.setKind("call");
            node.setSpan(ctx.spanOf(site));
            // `callee` stays unset at L1 and is filled in when L2 resolves this site.
            List<Expression> args = argumentsOf(site);
            node.setArguments(args.stream().map(CallSiteBuilder::localId).collect(Collectors.toList()));
            node.setArgumentExpr(args.stream().map(Object::toString).collect(Collectors.toList()));
            node.setArgumentTypes(
                    args.stream().map(ctx::resolveExpressionType).collect(Collectors.toList()));
            enrich(node, site);
            nodes.put(localId(site), node);
        }
        return nodes;
    }

    /**
     * Fill in the resolved call-site facts, degrading silently when resolution fails (a missing
     * dependency must thin the node's data, never drop the node or fail the build).
     */
    private void enrich(JBodyNode node, Node site) {
        if (site instanceof MethodCallExpr) {
            MethodCallExpr call = (MethodCallExpr) site;
            call.getScope().ifPresent(scope -> {
                node.setReceiverExpr(scope.toString());
                String type = ctx.resolveExpressionType(scope);
                if (!type.isEmpty()) {
                    node.setReceiverType(type);
                }
            });
            try {
                ResolvedMethodDeclaration resolved = call.resolve();
                node.setCalleeSignature(Signatures.typeErasure(resolved));
                node.setStaticCall(resolved.isStatic());
            } catch (Throwable e) {
                Log.debug("Could not resolve call: " + call + ": " + e.getMessage());
            }
        } else if (site instanceof ObjectCreationExpr) {
            ObjectCreationExpr creation = (ObjectCreationExpr) site;
            node.setConstructorCall(true);
            node.setReceiverType(ctx.resolveType(creation.getType()));
            try {
                node.setCalleeSignature(Signatures.typeErasure(creation.resolve()));
            } catch (Throwable e) {
                Log.debug("Could not resolve constructor call: " + creation + ": " + e.getMessage());
            }
        } else if (site instanceof ExplicitConstructorInvocationStmt) {
            node.setConstructorCall(true);
            try {
                node.setCalleeSignature(
                        Signatures.typeErasure(((ExplicitConstructorInvocationStmt) site).resolve()));
            } catch (Throwable e) {
                Log.debug("Could not resolve constructor invocation: " + site + ": " + e.getMessage());
            }
        }
    }

    /**
     * True when {@code node} is part of {@code body} itself and not of a nested type or anonymous
     * class declared within it: no {@link BodyDeclaration} (which includes type declarations and
     * member methods/initializers) lies between the node and the body block.
     */
    static boolean belongsDirectlyTo(Node node, BlockStmt body) {
        for (Node cur = node.getParentNode().orElse(null);
                cur != null && cur != body;
                cur = cur.getParentNode().orElse(null)) {
            if (cur instanceof BodyDeclaration) {
                return false;
            }
        }
        return true;
    }

    private static List<Expression> argumentsOf(Node site) {
        NodeList<Expression> args;
        if (site instanceof MethodCallExpr) {
            args = ((MethodCallExpr) site).getArguments();
        } else if (site instanceof ObjectCreationExpr) {
            args = ((ObjectCreationExpr) site).getArguments();
        } else {
            args = ((ExplicitConstructorInvocationStmt) site).getArguments();
        }
        return new ArrayList<>(args);
    }

    /** The local id {@code line:col} of a node's addressing anchor. */
    private static String localId(Node node) {
        int[] pos = anchorPosition(node);
        return pos[0] + ":" + pos[1];
    }

    /**
     * Addressing position: the invoked name for a method call, the instantiated type for a
     * {@code new} expression, and the statement itself for {@code this(...)}/{@code super(...)} —
     * so sites nested in one expression stay distinct. Falls back to the node's own begin.
     */
    private static int[] anchorPosition(Node node) {
        Node anchor = node;
        if (node instanceof MethodCallExpr) {
            anchor = ((MethodCallExpr) node).getName();
        } else if (node instanceof ObjectCreationExpr) {
            anchor = ((ObjectCreationExpr) node).getType();
        }
        return anchor.getRange()
                .map(r -> new int[] {r.begin.line, r.begin.column})
                .orElseGet(() -> node.getRange()
                        .map(r -> new int[] {r.begin.line, r.begin.column})
                        .orElse(new int[] {0, 0}));
    }
}
