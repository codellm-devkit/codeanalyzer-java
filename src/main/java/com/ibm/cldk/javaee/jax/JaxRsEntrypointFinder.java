package com.ibm.cldk.javaee.jax;

import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.ibm.cldk.javaee.utils.interfaces.AbstractEntrypointFinder;
import java.util.List;

@SuppressWarnings({"unchecked", "rawtypes"})
public class JaxRsEntrypointFinder extends AbstractEntrypointFinder {
    @Override
    public String framework() {
        return "jaxrs";
    }

    /**
     * Detect if the method is an entrypoint.
     *
     * @return True if the method is an entrypoint, false otherwise.
     */
    @Override
    public boolean isEntrypointClass(TypeDeclaration typeDeclaration) {
        List<CallableDeclaration> callableDeclarations = typeDeclaration.findAll(CallableDeclaration.class);
        for (CallableDeclaration callableDeclaration : callableDeclarations) {
            if (callableDeclaration.getAnnotations().stream().anyMatch(a -> a.toString().contains("POST"))
                    || callableDeclaration.getAnnotations().stream().anyMatch(a -> a.toString().contains("PUT"))
                    || callableDeclaration.getAnnotations().stream().anyMatch(a -> a.toString().contains("GET"))
                    || callableDeclaration.getAnnotations().stream().anyMatch(a -> a.toString().contains("HEAD"))
                    || callableDeclaration.getAnnotations().stream().anyMatch(a -> a.toString().contains("DELETE"))) {
                return true;
            }
        }

        return false;
    }

    /**
     * @param callableDecl
     * @return
     */
    @Override
    public boolean isEntrypointMethod(CallableDeclaration callableDecl)  {
        return callableDecl.getAnnotations().stream().anyMatch(a -> a.toString().contains("POST") || a.toString().contains("PUT")
                        || a.toString().contains("GET") || a.toString().contains("HEAD")
                        || a.toString().contains("DELETE"));
    }
}
