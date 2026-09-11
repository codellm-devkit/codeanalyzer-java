package com.ibm.cldk.javaee.jakarta;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.ibm.cldk.javaee.utils.interfaces.AbstractEntrypointFinder;
import java.util.Set;

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
                n -> n.equals("Filter") || n.endsWith(".Filter")
                        || n.contains("ServletContextListener")
                        || n.contains("HttpSessionListener")
                        || n.contains("ServletRequestListener")
                        || n.contains("MessageListener"));
    }

    /**
     * The servlet, filter, listener and WebSocket lifecycle methods the container itself invokes.
     * Anything else on an entrypoint type is ordinary code, however servlet-typed its parameters.
     */
    private static final Set<String> LIFECYCLE = Set.of(
            "service", "doGet", "doPost", "doPut", "doDelete", "doHead", "doOptions", "doTrace",
            "doFilter", "init", "destroy",
            "contextInitialized", "contextDestroyed", "sessionCreated", "sessionDestroyed",
            "requestInitialized", "requestDestroyed", "onMessage");

    private static final Set<String> CALLBACK_ANNOTATIONS =
            Set.of("OnMessage", "OnOpen", "OnClose", "OnError");

    /**
     * A container-invoked method of an entrypoint type: a lifecycle method by name, or a WebSocket
     * callback by annotation (#263). Before this, any method with an {@code HttpServletRequest} /
     * {@code HttpServletResponse} parameter was marked — which made a plain helper such as
     * DayTrader's {@code requestDispatch(ctx, req, resp, page)} an entrypoint, and every
     * interprocedural tier then refused to bind its parameters from the call sites that do bind
     * them. The container binds a lifecycle method's parameters; it never calls a helper.
     */
    @Override
    public boolean isEntrypointMethod(CallableDeclaration callableDecl) {
        boolean callback = ((NodeList<AnnotationExpr>) callableDecl.getAnnotations()).stream()
                .anyMatch(a -> CALLBACK_ANNOTATIONS.contains(a.getNameAsString()));
        if (!callback && !LIFECYCLE.contains(callableDecl.getNameAsString())) {
            return false;
        }
        java.util.Optional<TypeDeclaration> owner = callableDecl.findAncestor(TypeDeclaration.class);
        return owner.isPresent() && isEntrypointClass(owner.get());
    }
}
