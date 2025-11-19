package com.ibm.cldk;

import com.ibm.cldk.entities.CallSite;
import com.ibm.cldk.entities.Callable;
import com.ibm.cldk.entities.JavaCompilationUnit;
import com.ibm.cldk.entities.Type;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class SymbolTableTest {

    private String getJavaCodeForTestResource(String resourcePath) {
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream(resourcePath);
        assert inputStream != null;
        return new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))
                .lines()
                .collect(Collectors.joining("\n"));
    }

    @Test
    public void testExtractSingleGenricsDuplicateSignature_Validate() throws IOException {
        String javaCode = getJavaCodeForTestResource("test-applications/generics-varargs-duplicate-signature-test/Validate.java");
        Map<String, JavaCompilationUnit> symbolTable = SymbolTable.extractSingle(javaCode).getLeft();
        Assertions.assertEquals(1, symbolTable.size());
        Map<String, Type> typeDeclaration = symbolTable.values().iterator().next().getTypeDeclarations();
        Assertions.assertEquals(1, typeDeclaration.size());
        Map<String, Callable> callables = typeDeclaration.values().iterator().next().getCallableDeclarations();
        Assertions.assertEquals(17, callables.size());
    }

    @Test
    public void testExtractSingleGenricsDuplicateSignature_FunctorUtils() throws IOException {
        String javaCode = getJavaCodeForTestResource("test-applications/generics-varargs-duplicate-signature-test/FunctorUtils.java");
        Map<String, JavaCompilationUnit> symbolTable = SymbolTable.extractSingle(javaCode).getLeft();
        Assertions.assertEquals(1, symbolTable.size());
        Map<String, Type> typeDeclaration = symbolTable.values().iterator().next().getTypeDeclarations();
        Assertions.assertEquals(1, typeDeclaration.size());
        Map<String, Callable> callables = typeDeclaration.values().iterator().next().getCallableDeclarations();
        Assertions.assertEquals(10, callables.size());
    }

    @Test
    public void testCallSiteArgumentExpression() throws IOException {
        String javaCode = getJavaCodeForTestResource("test-applications/generics-varargs-duplicate-signature-test/Validate.java");
        Map<String, Type> typeDeclaration = SymbolTable.extractSingle(javaCode).getLeft()
                .values().iterator().next().getTypeDeclarations();
        Callable callable = typeDeclaration.values().iterator().next().getCallableDeclarations()
                .get("notEmpty(java.util.Collection<?>, java.lang.String, java.lang.Object[])");
        Assertions.assertNotNull(callable);
        for (CallSite callSite : callable.getCallSites()) {
            if (callSite.getMethodName().equals("requireNonNull")) {
                String[] expectedArgumentExpr = {"collection", "toSupplier(message, values)"};
                List<String> argumentExpr = callSite.getArgumentExpr();
                Assertions.assertArrayEquals(expectedArgumentExpr, argumentExpr.toArray(new String[0]));
                break;
            }
        }
    }

}
