package com.ibm.cldk.syntactic_analysis;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;

/**
 * Scope questions the L1 builders share: deciding which AST nodes belong to the construct being built
 * rather than to a type or callable nested inside it.
 */
final class AstScopes {

    private AstScopes() {}

    /**
     * True when {@code node} belongs to {@code boundary} itself and not to a nested type or anonymous
     * class declared within it: no {@link BodyDeclaration} (which includes type declarations and
     * member methods/initializers) lies between the node and the boundary. Lambda bodies have no
     * {@code BodyDeclaration} of their own, so their contents stay with the enclosing callable.
     *
     * <p>The boundary is usually a callable's {@link BlockStmt} body, but it may be any node — a
     * {@code FieldDeclaration}, for instance, when attributing the anonymous classes declared in a
     * field initializer. The boundary is checked before the {@code BodyDeclaration} test, so passing a
     * {@code BodyDeclaration} as the boundary works as expected.
     */
    static boolean belongsDirectlyTo(Node node, Node boundary) {
        for (Node cur = node.getParentNode().orElse(null);
                cur != null && cur != boundary;
                cur = cur.getParentNode().orElse(null)) {
            if (cur instanceof BodyDeclaration) {
                return false;
            }
        }
        return true;
    }
}
