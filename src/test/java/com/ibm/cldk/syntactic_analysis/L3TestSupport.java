package com.ibm.cldk.syntactic_analysis;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.ibm.cldk.schema.CanId;

/**
 * Shared helpers for the L3 dataflow unit tests across the {@code controlflow} and {@code dataflow}
 * packages. Structural CFG/CDG tests need only a parsed {@code BlockStmt}; DDG tests that classify
 * variable bases use a symbol-resolving parse (added with the DDG task). The {@link L1BuildContext} is
 * handed the same source string so {@code spanOf} computes real byte offsets.
 */
public final class L3TestSupport {

    private L3TestSupport() {}

    /** Parse {@code classSource} and return the body block of the named method. */
    public static BlockStmt methodBody(String classSource, String methodName) {
        return StaticJavaParser.parse(classSource).findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no method " + methodName))
                .getBody()
                .orElseThrow(() -> new AssertionError("method " + methodName + " has no body"));
    }

    /** A build context whose source matches the parsed class, so spans resolve to real byte offsets. */
    public static L1BuildContext ctx(String source) {
        return new L1BuildContext(CanId.applicationId("app"), "Foo.java", source);
    }
}
