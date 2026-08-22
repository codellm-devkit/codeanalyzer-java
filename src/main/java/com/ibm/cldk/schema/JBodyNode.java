package com.ibm.cldk.schema;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * A node in a callable's {@code body}: at L1 only {@code call} nodes (an AST region for a method
 * invocation). {@code callee} is the sanctioned {@code null}-then-id slot — left {@code null} at L1
 * and backfilled with the callee's {@code can://} id when the L2 call graph resolves the site.
 * {@code arguments} are the local ids of the invocation's argument expressions.
 */
@Data
public class JBodyNode {
    private String kind;
    private Span span;
    /** Only meaningful on {@code call} nodes; {@code null} at L1 (backfilled at L2). */
    private String callee;
    private List<String> arguments = new ArrayList<>();

    // --- Rich call-site facts (only on `call` nodes) --------------------------------------------
    // The canonical `call` node carries just {callee, arguments}, which is thinner than every
    // analyzer's real call-site data (the Python reference analyzer keeps a parallel rich
    // `call_sites[]` for the same reason). These are therefore additive Java fields, retained because
    // the framework/CRUD finders key on `receiver_type` and dropping them would regress against v1.

    /** The receiver expression as written ({@code "abc"}, {@code helper}, {@code this}). */
    private String receiverExpr;

    /** Resolved type of the receiver — or, for a {@code new} expression, the instantiated type. */
    private String receiverType;

    /** Resolved types of the argument expressions, positionally. */
    private List<String> argumentTypes = new ArrayList<>();

    /** The argument expressions as written, positionally. */
    private List<String> argumentExpr = new ArrayList<>();

    /** Erased signature of the resolved callee ({@code substring(int)}); absent when unresolvable. */
    private String calleeSignature;

    /** Invoked name — the method's name, or {@code <init>} for a constructor call. */
    private String methodName;

    /**
     * The type this call evaluates to (the instantiated type for a {@code new} expression); absent when
     * it cannot be resolved.
     */
    private String returnType;

    /**
     * Declared accessibility of the callee: {@code public}, {@code protected}, {@code private} or
     * {@code package_private}. Absent when the callee cannot be resolved — accessibility is then
     * genuinely unknown, and v1's {@code is_unspecified} boolean conflated that with package-private.
     */
    private String accessibility;

    /** Comment attached to the construct containing this call site, if any. */
    private JComment comment;

    /**
     * Whether the callee is static. A {@code Boolean} rather than a primitive: when the callee cannot
     * be resolved this is genuinely <em>unknown</em>, and absence says that honestly where {@code false}
     * would assert "not static".
     */
    private Boolean isStaticCall;

    /** Syntactically evident (a {@code new} expression or {@code this(...)}/{@code super(...)}). */
    private boolean isConstructorCall;
}
