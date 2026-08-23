package com.ibm.cldk.syntactic_analysis;

import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The binary (JVM) name of a resolved type — {@code java.util.Map$Entry}, not the source-legible
 * {@code java.util.Map.Entry}.
 *
 * <p>Built by a structural walk of the container chain rather than by string surgery, so it never has
 * to guess where the package ends: recurse {@link ResolvedTypeDeclaration#containerType()} collecting
 * each enclosing simple name, join them with {@code $}, then prefix {@link
 * ResolvedTypeDeclaration#getPackageName()}. That ambiguity — {@code a.b.C.D} genuinely cannot say
 * where the package stops, {@code a.b.C$D} can — is exactly why ids carry binary names (§2). It is
 * also the spelling WALA emits natively, so the L2 type index keyed by binary name serves both the
 * JavaParser declaring-type hint and the WALA overlay with one key.
 */
public final class BinaryNames {

    private BinaryNames() {}

    /** The binary name of {@code type}: {@code <package>.<Outer>$<Inner>}, or bare when unpackaged. */
    public static String of(ResolvedReferenceTypeDeclaration type) {
        Deque<String> simpleNames = new ArrayDeque<>();
        ResolvedTypeDeclaration current = type;
        while (current != null) {
            simpleNames.addFirst(current.getName());
            current = current.containerType().orElse(null);
        }
        String nested = String.join("$", simpleNames);
        String pkg = type.getPackageName();
        return pkg == null || pkg.isEmpty() ? nested : pkg + "." + nested;
    }
}
