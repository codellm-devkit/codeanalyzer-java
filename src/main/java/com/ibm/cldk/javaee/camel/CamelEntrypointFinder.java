package com.ibm.cldk.javaee.camel;

import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.ibm.cldk.javaee.utils.interfaces.AbstractEntrypointFinder;
import com.ibm.cldk.utils.Log;
import com.ibm.cldk.utils.annotations.NotImplemented;

@NotImplemented(comment = "This class is not implemented yet. Leaving this here to refactor entrypoint detection.")
public class CamelEntrypointFinder extends AbstractEntrypointFinder {
    @Override
    public String framework() {
        return "camel";
    }

    /**
     * Detect if the method is an entrypoint.
     *
     * @param typeDecl@return True if the method is an entrypoint, false otherwise.
     */
    @Override
    public boolean isEntrypointClass(TypeDeclaration typeDecl) {
        if (!(typeDecl instanceof ClassOrInterfaceDeclaration)) {
            return false;
        }

        ClassOrInterfaceDeclaration classDecl = (ClassOrInterfaceDeclaration) typeDecl;

        // Check Camel class annotations
        if (classDecl.getAnnotations().stream().anyMatch(a -> a.getNameAsString().contains("Component"))) {
            return true;
        }

        // Check Camel parent classes and interfaces
        try {
            ResolvedReferenceTypeDeclaration resolved = classDecl.resolve();
            return resolved.getAllAncestors().stream().anyMatch(ancestor -> {
                String name = ancestor.getQualifiedName();
                return name.contains("RouteBuilder") || name.contains("Processor") || name.contains("Producer")
                        || name.contains("Consumer");
            });
        } catch (RuntimeException e) {
            Log.warn("Could not resolve class: " + e.getMessage());
        }

        return false;
    }

    /**
     * @param callableDecl
     * @return
     */
    @Override
    public boolean isEntrypointMethod(CallableDeclaration callableDecl) {
        return false;
    }
}
