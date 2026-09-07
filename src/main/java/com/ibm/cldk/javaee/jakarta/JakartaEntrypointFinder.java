package com.ibm.cldk.javaee.jakarta;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.ibm.cldk.javaee.utils.interfaces.AbstractEntrypointFinder;

@SuppressWarnings({"unchecked", "rawtypes"})
public class JakartaEntrypointFinder extends AbstractEntrypointFinder {
    @Override
    public String framework() {
        return "jakarta";
    }

    @Override
    public boolean isEntrypointClass(TypeDeclaration typeDecl) {
        if (!(typeDecl instanceof ClassOrInterfaceDeclaration)) {
            return false;
        }

        ClassOrInterfaceDeclaration classDecl = (ClassOrInterfaceDeclaration) typeDecl;

        // Check annotations
        if (classDecl.getAnnotations().stream()
                .anyMatch(a -> a.getNameAsString().contains("WebServlet") || a.getNameAsString().contains("WebFilter")
                        || a.getNameAsString().contains("WebListener") || a.getNameAsString().contains("ServerEndpoint")
                        || a.getNameAsString().contains("MessageDriven")
                        || a.getNameAsString().contains("WebService"))) {
            return true;
        }

        // Check types
        return classDecl.getExtendedTypes().stream()
                .map(ClassOrInterfaceType::getNameAsString)
                .anyMatch(n -> n.contains("HttpServlet") || n.contains("GenericServlet"))
                || classDecl.getImplementedTypes().stream().map(
                ClassOrInterfaceType::asString).anyMatch(
                n -> n.contains("ServletContextListener")
                        || n.contains("HttpSessionListener")
                        || n.contains("ServletRequestListener")
                        || n.contains("MessageListener"));
    }

    @Override
    public boolean isEntrypointMethod(CallableDeclaration callableDecl) {
        return ((NodeList<Parameter>) callableDecl.getParameters()).stream()
                .anyMatch(parameter -> parameter.getType().asString().contains("HttpServletRequest") ||
                        parameter.getType().asString().contains("HttpServletResponse"));
    }
}
