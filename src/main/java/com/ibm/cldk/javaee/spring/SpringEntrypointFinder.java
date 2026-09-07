package com.ibm.cldk.javaee.spring;

import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.ibm.cldk.javaee.utils.interfaces.AbstractEntrypointFinder;
import java.util.List;

public class SpringEntrypointFinder extends AbstractEntrypointFinder {
    @Override
    public String framework() {
        return "spring";
    }

    @Override
    public boolean isEntrypointClass(TypeDeclaration typeDeclaration) {
        List<AnnotationExpr> annotations = typeDeclaration.getAnnotations();
        for (AnnotationExpr annotation : annotations) {
            // Existing checks
            if (annotation.getNameAsString().contains("RestController")
                    || annotation.getNameAsString().contains("Controller")
                    || annotation.getNameAsString().contains("HandleInterceptor")
                    || annotation.getNameAsString().contains("HandlerInterceptor")) {
                return true;
            }

            // Spring Boot specific checks
            if (annotation.getNameAsString().contains("SpringBootApplication")
                    || annotation.getNameAsString().contains("Configuration")
                    || annotation.getNameAsString().contains("Component")
                    || annotation.getNameAsString().contains("Service")
                    || annotation.getNameAsString().contains("Repository")) {
                return true;
            }
        }

        // Check if class implements CommandLineRunner or ApplicationRunner
        if (typeDeclaration instanceof ClassOrInterfaceDeclaration) {
            ClassOrInterfaceDeclaration classDecl = (ClassOrInterfaceDeclaration) typeDeclaration;
            for (ClassOrInterfaceType implementedType : classDecl.getImplementedTypes()) {
                String typeName = implementedType.getNameAsString();
                if (typeName.equals("CommandLineRunner") || typeName.equals("ApplicationRunner")) {
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    public boolean isEntrypointMethod(CallableDeclaration callableDecl) { return callableDecl.getAnnotations().stream().anyMatch(a -> a.toString().contains("GetMapping") ||
            a.toString().contains("PostMapping") ||
            a.toString().contains("PutMapping") ||
            a.toString().contains("DeleteMapping") ||
            a.toString().contains("PatchMapping") ||
            a.toString().contains("RequestMapping") ||
            a.toString().contains("EventListener") ||
            a.toString().contains("Scheduled") ||
            a.toString().contains("KafkaListener") ||
            a.toString().contains("RabbitListener") ||
            a.toString().contains("JmsListener") ||
            a.toString().contains("PreAuthorize") ||
            a.toString().contains("PostAuthorize") ||
            a.toString().contains("PostConstruct") ||
            a.toString().contains("PreDestroy") ||
            a.toString().contains("Around") ||
            a.toString().contains("Before") ||
            a.toString().contains("After") ||
            a.toString().contains("JobScope") ||
            a.toString().contains("StepScope")); }
}
