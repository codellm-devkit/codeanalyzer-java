package com.ibm.cldk.javaee.struts;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.ibm.cldk.javaee.utils.interfaces.AbstractEntrypointFinder;
import com.ibm.cldk.utils.Log;
import java.util.Optional;

public class StrutsEntrypointFinder extends AbstractEntrypointFinder {
    @Override
    public String framework() {
        return "struts";
    }

    @Override
    public boolean isEntrypointClass(TypeDeclaration typeDeclaration){
        if (!(typeDeclaration instanceof ClassOrInterfaceDeclaration)) {
            return false;
        }

        ClassOrInterfaceDeclaration classDecl = (ClassOrInterfaceDeclaration) typeDeclaration;

        // Check class-level Struts annotations
        if (classDecl.getAnnotations().stream().anyMatch(a -> a.getNameAsString().contains("Action")
                || a.getNameAsString().contains("Namespace") || a.getNameAsString().contains("InterceptorRef"))) {
            return true;
        }

        // Check if extends ActionSupport or implements Interceptor
        try {
            ResolvedReferenceTypeDeclaration resolved = classDecl.resolve();
            return resolved.getAllAncestors().stream().anyMatch(ancestor -> {
                String name = ancestor.getQualifiedName();
                return name.contains("ActionSupport") || name.contains("Interceptor");
            });
        } catch (RuntimeException e) {
            Log.warn("Could not resolve class: " + e.getMessage());
        }

        return false;
    }

    @Override
    public boolean isEntrypointMethod(CallableDeclaration callableDecl) {
        // First check if this method is in a Struts Action class
        Optional<Node> parentNode = callableDecl.getParentNode();
        if (parentNode.isEmpty() || !(parentNode.get() instanceof ClassOrInterfaceDeclaration)) {
            return false;
        }

        ClassOrInterfaceDeclaration parentClass = (ClassOrInterfaceDeclaration) parentNode.get();
        if (parentClass.getExtendedTypes().stream()
                .map(ClassOrInterfaceType::asString)
                .noneMatch(type -> type.contains("ActionSupport") || type.contains("Action")))
            return false;

        return callableDecl.getAnnotations().stream().anyMatch(a -> a.toString().contains("Action") ||
                a.toString().contains("Actions") ||
                a.toString().contains("ValidationMethod") ||
                a.toString().contains("InputConfig") ||
                a.toString().contains("BeforeResult") ||
                a.toString().contains("After") ||
                a.toString().contains("Before") ||
                a.toString().contains("Result") ||
                a.toString().contains("Results")) || callableDecl.getNameAsString().equals("execute");
    }
}
