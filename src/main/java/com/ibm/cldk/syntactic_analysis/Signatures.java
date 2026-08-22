package com.ibm.cldk.syntactic_analysis;

import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodLikeDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import com.ibm.cldk.utils.Log;
import java.util.ArrayList;
import java.util.List;

/**
 * Type-erasure signature construction for callables — the durable {@code <name>(<erased-params>)}
 * segment of a callable's {@code can://} id (design decision D8). Shared by the v1 symbol table and
 * the v2 builders so both mint identical signatures. When type resolution is unavailable (pure
 * syntactic parse) it falls back to the AST signature, so it never throws.
 */
public final class Signatures {

    /**
     * Guards {@link #fullyErasedDescription} against a pathological bound chain. Erasing a type
     * variable walks one link of its bound chain per pass, and bound chains cannot be cyclic (that is a
     * compile error), so every real signature converges — the deepest case observed needs four passes.
     */
    private static final int ERASURE_PASS_LIMIT = 16;

    private Signatures() {}

    /**
     * The fully erased description of {@code type}, in the JLS sense.
     *
     * <p>JavaParser's {@code erasure()} is one level deep: a type variable erases to its declared bound,
     * but that bound keeps its own type arguments. So {@code <T extends Consumer<?>> copy(T...)} yielded
     * {@code copy(java.util.function.Consumer<?>[])}, and a self-referential bound was worse still —
     * {@code <T extends Comparable<T>> inter(T)} leaked the variable {@code T} into a name that is
     * supposed to be durable and joinable. Iterating to a fixpoint erases the bound too.
     *
     * <p>This matters because the other side of every join is a bytecode descriptor, which is always
     * fully erased: WALA's call graph names that parameter {@code java.util.function.Consumer[]}, so a
     * half-erased key silently misses and the real callable looks absent.
     */
    private static String fullyErasedDescription(ResolvedType type) {
        ResolvedType erased = type.erasure();
        String description = erased.describe();
        for (int pass = 1; pass < ERASURE_PASS_LIMIT; pass++) {
            erased = erased.erasure();
            String further = erased.describe();
            if (further.equals(description)) {
                break;
            }
            description = further;
        }
        return description;
    }

    /**
     * The type-erasure signature of an already-<em>resolved</em> method/constructor — used to name the
     * callee of a call site. Mirrors the declaration-side format so a call site's
     * {@code callee_signature} matches the target callable's {@code signature}.
     */
    public static String typeErasure(ResolvedMethodLikeDeclaration methodDecl) {
        // A ResolvedConstructorDeclaration's name is its *class* name; the declaration side emits
        // `<init>`. Using the class name here would make a call site's callee_signature unjoinable
        // against the constructor's own signature, so every constructor edge would be missed.
        String name = methodDecl instanceof ResolvedConstructorDeclaration ? "<init>" : methodDecl.getName();
        StringBuilder signature = new StringBuilder(name);
        List<String> erasureParameterTypes = new ArrayList<>();
        for (int i = 0; i < methodDecl.getNumberOfParams(); i++) {
            erasureParameterTypes.add(fullyErasedDescription(methodDecl.getParam(i).getType()));
        }
        signature.append("(");
        signature.append(String.join(", ", erasureParameterTypes));
        signature.append(")");
        return signature.toString();
    }

    /**
     * The type-erasure signature for {@code callableDecl}: the method name (or {@code <init>} for a
     * constructor) followed by erased parameter types.
     */
    public static String typeErasure(CallableDeclaration<?> callableDecl) {
        String name = (callableDecl instanceof MethodDeclaration) ? callableDecl.getNameAsString() : "<init>";
        return signature(name, callableDecl.getParameters());
    }

    /**
     * The type-erasure signature of a record's <em>compact</em> constructor ({@code record P(int x) {
     * public P { ... } }}). A compact constructor declares no parameters of its own — it <em>is</em> the
     * canonical constructor, whose parameters are the record's components — so the signature is built
     * from those components. Deriving it from the declaration's own (empty) parameter list would emit
     * {@code <init>()}, which no {@code new P(...)} call site could ever join against.
     */
    public static String typeErasure(CompactConstructorDeclaration compactConstructor) {
        List<Parameter> components = compactConstructor.findAncestor(RecordDeclaration.class)
                .<List<Parameter>>map(RecordDeclaration::getParameters)
                .orElseGet(List::of);
        return signature("<init>", components);
    }

    /**
     * The type-erasure signature of a record's <em>canonical</em> constructor, derived from the record
     * header alone.
     *
     * <p>Every record has this constructor whether or not the source writes one, so this is what names it
     * when it is implicit. It shares {@link #typeErasure(CompactConstructorDeclaration)}'s path
     * deliberately: the two must mint the same string, or a record that declares a compact constructor
     * and one that does not would be keyed differently for the same canonical constructor.
     */
    public static String typeErasure(RecordDeclaration record) {
        return signature("<init>", record.getParameters());
    }

    /** {@code <name>(<erased param types>)} — the durable signature format shared by every caller. */
    private static String signature(String name, List<Parameter> parameters) {
        List<String> erasureParameterTypes = new ArrayList<>();
        for (Parameter parameter : parameters) {
            erasureParameterTypes.add(erasedTypeOf(parameter));
        }
        return name + "(" + String.join(", ", erasureParameterTypes) + ")";
    }

    /**
     * One parameter's erased type, degrading to the AST spelling when it cannot be resolved.
     *
     * <p>Resolution is attempted <em>per parameter</em> rather than for the signature as a whole. An
     * all-or-nothing fallback to {@code CallableDeclaration.getSignature()} would emit unqualified
     * spellings for <em>every</em> parameter as soon as one failed — {@code m(List, Mystery)} where the
     * call-site side always emits qualified names, {@code m(java.util.List, Mystery)}. The two would
     * never join, so L2 would silently drop the edge (the same failure mode that made every constructor
     * edge unjoinable before the callee side normalised to {@code <init>}). Degrading only the
     * parameters that genuinely cannot be resolved keeps the rest joinable.
     */
    private static String erasedTypeOf(Parameter parameter) {
        // A varargs parameter is an array at the call site; `type` alone is the element type. The
        // resolved side gets that array-ness from the type itself, so appending the suffix here is what
        // keeps the two sides emitting the same string.
        String suffix = parameter.isVarArgs() ? "[]" : "";
        try {
            ResolvedType resolvedType = parameter.getType().resolve();
            return fullyErasedDescription(resolvedType) + suffix;
        } catch (Throwable e) {
            Log.debug("Could not resolve parameter type " + parameter.getType().asString()
                    + "; falling back to the AST spelling");
            return parameter.getType().asString() + suffix;
        }
    }
}
