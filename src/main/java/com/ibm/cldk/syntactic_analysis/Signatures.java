package com.ibm.cldk.syntactic_analysis;

import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
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

    private Signatures() {}

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
            erasureParameterTypes.add(methodDecl.getParam(i).getType().erasure().describe());
        }
        signature.append("(");
        signature.append(String.join(", ", erasureParameterTypes));
        signature.append(")");
        return signature.toString();
    }

    /**
     * The type-erasure signature for {@code callableDecl}: the method name (or {@code <init>} for a
     * constructor) followed by erased parameter types. Falls back to the plain AST signature if the
     * parameter types cannot be resolved (no symbol solver configured).
     */
    public static String typeErasure(CallableDeclaration<?> callableDecl) {
        try {
            StringBuilder signature = new StringBuilder(
                    (callableDecl instanceof MethodDeclaration) ? callableDecl.getNameAsString() : "<init>");
            List<String> erasureParameterTypes = new ArrayList<>();
            for (Parameter parameter : callableDecl.getParameters()) {
                ResolvedType resolvedType = parameter.getType().resolve();
                if (parameter.isVarArgs()) {
                    erasureParameterTypes.add(resolvedType.erasure().describe() + "[]");
                } else {
                    erasureParameterTypes.add(resolvedType.erasure().describe());
                }
            }
            signature.append("(");
            signature.append(String.join(", ", erasureParameterTypes));
            signature.append(")");
            return signature.toString();
        } catch (Throwable e) {
            Log.debug("Could not compute type erasure signature for " + callableDecl.getSignature().asString()
                    + "; computing regular signature");
            return callableDecl.getSignature().asString();
        }
    }
}
