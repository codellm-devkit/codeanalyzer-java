package com.ibm.cldk.syntactic_analysis;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;

/**
 * Scope questions the L1 builders share: deciding which AST nodes belong to the callable being built
 * rather than to a type or callable nested inside it.
 */
final class AstScopes {

    private AstScopes() {}

    /**
     * True when {@code node} belongs to {@code body} itself and not to a nested type or anonymous
     * class declared within it: no {@link BodyDeclaration} (which includes type declarations and
     * member methods/initializers) lies between the node and the body block. Lambda bodies have no
     * {@code BodyDeclaration} of their own, so their contents stay with the enclosing callable.
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
}
