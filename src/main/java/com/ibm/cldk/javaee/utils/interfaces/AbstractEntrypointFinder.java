package com.ibm.cldk.javaee.utils.interfaces;

import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.ibm.cldk.utils.annotations.NotImplemented;

@SuppressWarnings({"unchecked", "rawtypes"})
public abstract class AbstractEntrypointFinder {
    /**
     * Enum for rules.
     */
    enum Rulset{
    }

    /**
     * Detect if the method is an entrypoint.
     *
     * @param receiverType The type of the receiver object.
     * @param name The name of the method.
     * @return True if the method is an entrypoint, false otherwise.
     */
    public abstract boolean isEntrypointClass(TypeDeclaration typeDecl);

    /**
     * This finder's framework name, as it appears in a node's {@code entrypoint_frameworks} and in
     * the application-level report's {@code rulesets}. Java's finders are hardcoded classes rather
     * than data-driven rule files, so the finder set IS the ruleset vocabulary.
     */
    public abstract String framework();

    public abstract boolean isEntrypointMethod(CallableDeclaration callableDecl);
}
