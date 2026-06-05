package com.ibm.cldk;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ibm.cldk.entities.CallSite;
import com.ibm.cldk.entities.Callable;
import com.ibm.cldk.entities.Field;
import com.ibm.cldk.entities.Import;
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
    public void testExtractSingleMissingNodeRange() throws IOException {
        String javaCode = getJavaCodeForTestResource("test-applications/missing-node-range-test/WeakHashtableTestCase.java");
        Map<String, JavaCompilationUnit> symbolTable = SymbolTable.extractSingle(javaCode).getLeft();
        Assertions.assertEquals(1, symbolTable.size());
        Map<String, Type> typeDeclaration = symbolTable.values().iterator().next().getTypeDeclarations();
        Assertions.assertEquals(2, typeDeclaration.size());
    }

    @Test
    public void testExtractSingleDefaultKeywordMethodDecl() throws IOException {
        String javaCode = getJavaCodeForTestResource("test-applications/default-keyword-method-decl/IndexExtractor.java");
        Map<String, JavaCompilationUnit> symbolTable = SymbolTable.extractSingle(javaCode).getLeft();
        Assertions.assertEquals(1, symbolTable.size());
        Map<String, Type> typeDeclaration = symbolTable.values().iterator().next().getTypeDeclarations();
        Assertions.assertEquals(1, typeDeclaration.size());
        Map<String, Callable> callables = typeDeclaration.values().iterator().next().getCallableDeclarations();
        Assertions.assertEquals(5, callables.size());
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

    @Test
    public void testExtractSingleImportMetadata() throws IOException {
        String javaCode = String.join("\n",
                "import java.util.List;",
                "import java.util.Map.*;",
                "import static java.util.Collections.emptyList;",
                "import static java.util.Collections.*;",
                "class T {}");
        Map<String, JavaCompilationUnit> symbolTable = SymbolTable.extractSingle(javaCode).getLeft();
        Assertions.assertEquals(1, symbolTable.size());
        List<Import> imports = symbolTable.values().iterator().next().getImports();
        Assertions.assertNotNull(imports);
        Assertions.assertEquals(4, imports.size());

        assertImport(imports, "java.util.List", false, false);
        assertImport(imports, "java.util.Map", false, true);
        assertImport(imports, "java.util.Collections.emptyList", true, false);
        assertImport(imports, "java.util.Collections", true, true);

        JsonArray serializedImports = JsonParser.parseString(CodeAnalyzer.gson.toJson(imports)).getAsJsonArray();
        Assertions.assertEquals(4, serializedImports.size());
        for (JsonElement serializedImport : serializedImports) {
            Assertions.assertTrue(serializedImport.isJsonObject());
            JsonObject serializedImportObject = serializedImport.getAsJsonObject();
            Assertions.assertTrue(serializedImportObject.has("path"));
            Assertions.assertTrue(serializedImportObject.has("is_static"));
            Assertions.assertTrue(serializedImportObject.has("is_wildcard"));
        }
    }

    @Test
    public void testExtractSingleFieldInitializers() throws IOException {
        String javaCode = String.join("\n",
                "class T {",
                "    private static final String QUOTES_PATH = \"/rest/quotes\";",
                "    private int count;",
                "    private int first = 1, second, third = first + 2;",
                "}");
        Map<String, JavaCompilationUnit> symbolTable = SymbolTable.extractSingle(javaCode).getLeft();
        Assertions.assertEquals(1, symbolTable.size());
        List<Field> fields = symbolTable.values().iterator().next().getTypeDeclarations()
                .values().iterator().next().getFieldDeclarations();
        Assertions.assertEquals(3, fields.size());

        Field quotesPath = fields.get(0);
        Assertions.assertEquals(List.of("QUOTES_PATH"), quotesPath.getVariables());
        Assertions.assertEquals(Map.of("QUOTES_PATH", "\"/rest/quotes\""), quotesPath.getVariableInitializers());

        Field count = fields.get(1);
        Assertions.assertEquals(List.of("count"), count.getVariables());
        Assertions.assertTrue(count.getVariableInitializers().isEmpty());

        Field multi = fields.get(2);
        Assertions.assertEquals(List.of("first", "second", "third"), multi.getVariables());
        Assertions.assertEquals(Map.of("first", "1", "third", "first + 2"), multi.getVariableInitializers());

        JsonObject serializedField = JsonParser.parseString(CodeAnalyzer.gson.toJson(quotesPath)).getAsJsonObject();
        Assertions.assertTrue(serializedField.has("variable_initializers"));
        Assertions.assertEquals("\"/rest/quotes\"",
                serializedField.getAsJsonObject("variable_initializers").get("QUOTES_PATH").getAsString());
    }

    private static void assertImport(List<Import> imports, String path, boolean isStatic, boolean isWildcard) {
        Import matchingImport = imports.stream()
                .filter(imp -> path.equals(imp.getPath())
                        && imp.isStatic() == isStatic
                        && imp.isWildcard() == isWildcard)
                .findFirst()
                .orElse(null);
        Assertions.assertNotNull(matchingImport,
                String.format("Expected import '%s' with isStatic=%s and isWildcard=%s", path, isStatic, isWildcard));
    }

}
