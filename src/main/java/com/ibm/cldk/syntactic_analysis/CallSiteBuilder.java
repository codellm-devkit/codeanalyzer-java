package com.ibm.cldk.syntactic_analysis;

import com.github.javaparser.ast.AccessSpecifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExplicitConstructorInvocationStmt;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.ibm.cldk.schema.JBodyNode;
import com.ibm.cldk.utils.Log;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        body.findAll(MethodCallExpr.class).stream().filter(n -> AstScopes.belongsDirectlyTo(n, body)).forEach(sites::add);
        body.findAll(ObjectCreationExpr.class).stream().filter(n -> AstScopes.belongsDirectlyTo(n, body)).forEach(sites::add);
        body.findAll(ExplicitConstructorInvocationStmt.class).stream()
                .filter(n -> AstScopes.belongsDirectlyTo(n, body))
                .forEach(sites::add);

        // A node with no source range cannot be addressed by a line:col id. Inventing one would both
        // fabricate a location and collide with every other rangeless node, silently overwriting call
        // sites; skipping is the honest degradation.
        sites.removeIf(site -> !hasPosition(site));

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
        commentOn(site).ifPresent(c -> node.setComment(ctx.comment(c)));

        if (site instanceof MethodCallExpr) {
            MethodCallExpr call = (MethodCallExpr) site;
            node.setMethodName(call.getNameAsString());
            call.getScope().ifPresent(scope -> {
                node.setReceiverExpr(scope.toString());
                String type = ctx.resolveExpressionType(scope);
                if (!type.isEmpty()) {
                    node.setReceiverType(type);
                }
            });
            String returnType = returnTypeOf(call);
            if (!returnType.isEmpty()) {
                node.setReturnType(returnType);
            }
            try {
                ResolvedMethodDeclaration resolved = call.resolve();
                node.setCalleeSignature(Signatures.typeErasure(resolved));
                node.setIsStaticCall(resolved.isStatic());
                node.setAccessibility(accessibilityOf(resolved.accessSpecifier()));
            } catch (Throwable e) {
                Log.debug("Could not resolve call: " + call + ": " + e.getMessage());
            }
        } else if (site instanceof ObjectCreationExpr) {
            ObjectCreationExpr creation = (ObjectCreationExpr) site;
            node.setConstructorCall(true);
            node.setMethodName("<init>");
            String instantiated = ctx.resolveType(creation.getType());
            node.setReceiverType(instantiated);
            // A constructor call evaluates to the instantiated type.
            node.setReturnType(instantiated);
            try {
                ResolvedConstructorDeclaration resolved = creation.resolve();
                node.setCalleeSignature(Signatures.typeErasure(resolved));
                node.setAccessibility(accessibilityOf(resolved.accessSpecifier()));
            } catch (Throwable e) {
                Log.debug("Could not resolve constructor call: " + creation + ": " + e.getMessage());
            }
        } else if (site instanceof ExplicitConstructorInvocationStmt) {
            node.setConstructorCall(true);
            node.setMethodName("<init>");
            try {
                ResolvedConstructorDeclaration resolved = ((ExplicitConstructorInvocationStmt) site).resolve();
                node.setCalleeSignature(Signatures.typeErasure(resolved));
                node.setAccessibility(accessibilityOf(resolved.accessSpecifier()));
                node.setReceiverType(resolved.declaringType().getQualifiedName());
                node.setReturnType(resolved.declaringType().getQualifiedName());
            } catch (Throwable e) {
                Log.debug("Could not resolve constructor invocation: " + site + ": " + e.getMessage());
            }
        }
    }

    /**
     * The comment documenting a call site. A comment is attached to the <em>statement</em>, so an
     * expression site (a method call, a {@code new}) takes its enclosing statement's — the node v1 read.
     * A {@code this(...)}/{@code super(...)} site already <em>is</em> the statement, and its own comment
     * is the one that documents it; its parent is the enclosing block, whose comment belongs to the block.
     */
    private static Optional<Comment> commentOn(Node site) {
        return site.getComment().or(() -> site.getParentNode().flatMap(Node::getComment));
    }

    /**
     * The type a method call evaluates to. Mirrors v1: when the call is immediately cast, the cast type
     * is reported, because JavaParser's own inference through a cast is the less reliable of the two.
     */
    private String returnTypeOf(MethodCallExpr call) {
        Node parent = call.getParentNode().orElse(null);
        if (parent instanceof CastExpr) {
            return ctx.resolveType(((CastExpr) parent).getType());
        }
        return ctx.resolveExpressionType(call);
    }

    /**
     * The callee's declared accessibility. v1 carried four mutually exclusive booleans
     * ({@code is_public}/{@code is_protected}/{@code is_private}/{@code is_unspecified}) — the boolean
     * pile D4 replaces with a single {@code kind}-style field. Package-private is named explicitly rather
     * than left as "unspecified", and the key is simply absent when the callee cannot be resolved, so
     * genuinely-unknown accessibility is no longer indistinguishable from package-private (D12).
     */
    private static String accessibilityOf(AccessSpecifier access) {
        switch (access) {
            case PUBLIC:
                return "public";
            case PROTECTED:
                return "protected";
            case PRIVATE:
                return "private";
            default:
                return "package_private";
        }
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

    /** Whether a call site has a usable source position (its anchor's, or its own). */
    private static boolean hasPosition(Node site) {
        Node anchor = anchorOf(site);
        return anchor.getRange().isPresent() || site.getRange().isPresent();
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
    private static Node anchorOf(Node node) {
        if (node instanceof MethodCallExpr) {
            return ((MethodCallExpr) node).getName();
        }
        if (node instanceof ObjectCreationExpr) {
            return ((ObjectCreationExpr) node).getType();
        }
        return node;
    }

    private static int[] anchorPosition(Node node) {
        return anchorOf(node).getRange()
                .map(r -> new int[] {r.begin.line, r.begin.column})
                .orElseGet(() -> node.getRange()
                        .map(r -> new int[] {r.begin.line, r.begin.column})
                        .orElse(new int[] {0, 0}));
    }
}
