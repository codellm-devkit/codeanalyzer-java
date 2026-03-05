package com.ibm.cldk;

import com.ibm.cldk.entities.Import;
import com.ibm.cldk.entities.JavaCompilationUnit;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class CodeAnalyzerTest {

    @TempDir
    Path tempDir;

    @SuppressWarnings("unchecked")
    private Map<String, JavaCompilationUnit> invokeReadSymbolTableFromFile(Path analysisFilePath) throws Exception {
        Method readSymbolTableMethod = CodeAnalyzer.class.getDeclaredMethod("readSymbolTableFromFile", File.class);
        readSymbolTableMethod.setAccessible(true);
        try {
            return (Map<String, JavaCompilationUnit>) readSymbolTableMethod.invoke(null, analysisFilePath.toFile());
        } catch (InvocationTargetException invocationTargetException) {
            Throwable targetException = invocationTargetException.getTargetException();
            if (targetException instanceof Exception) {
                throw (Exception) targetException;
            }
            throw new RuntimeException(targetException);
        }
    }

    private Path writeAnalysisFile(String jsonContent) throws Exception {
        Path analysisFilePath = tempDir.resolve("analysis.json");
        Files.writeString(analysisFilePath, jsonContent, StandardCharsets.UTF_8);
        return analysisFilePath;
    }

    @Test
    public void testReadSymbolTableFromFileRejectsLegacyImportSchema() throws Exception {
        String jsonContent = "{\n"
                + "  \"symbol_table\": {\n"
                + "    \"/tmp/T.java\": {\n"
                + "      \"file_path\": \"/tmp/T.java\",\n"
                + "      \"package_name\": \"\",\n"
                + "      \"comments\": [],\n"
                + "      \"imports\": [\"java.util.List\"],\n"
                + "      \"type_declarations\": {},\n"
                + "      \"is_modified\": false\n"
                + "    }\n"
                + "  }\n"
                + "}\n";
        Path analysisFilePath = writeAnalysisFile(jsonContent);

        IllegalStateException exception = Assertions.assertThrows(IllegalStateException.class,
                () -> invokeReadSymbolTableFromFile(analysisFilePath));
        Assertions.assertTrue(exception.getMessage().contains("legacy import schema"));
    }

    @Test
    public void testReadSymbolTableFromFileParsesExplicitImportSchema() throws Exception {
        String jsonContent = "{\n"
                + "  \"symbol_table\": {\n"
                + "    \"/tmp/T.java\": {\n"
                + "      \"file_path\": \"/tmp/T.java\",\n"
                + "      \"package_name\": \"\",\n"
                + "      \"comments\": [],\n"
                + "      \"imports\": [\n"
                + "        {\n"
                + "          \"path\": \"java.util.List\",\n"
                + "          \"is_static\": false,\n"
                + "          \"is_wildcard\": false\n"
                + "        },\n"
                + "        {\n"
                + "          \"path\": \"java.util.Collections\",\n"
                + "          \"is_static\": true,\n"
                + "          \"is_wildcard\": true\n"
                + "        }\n"
                + "      ],\n"
                + "      \"type_declarations\": {},\n"
                + "      \"is_modified\": false\n"
                + "    }\n"
                + "  }\n"
                + "}\n";
        Path analysisFilePath = writeAnalysisFile(jsonContent);

        Map<String, JavaCompilationUnit> symbolTable = invokeReadSymbolTableFromFile(analysisFilePath);
        Assertions.assertNotNull(symbolTable);
        Assertions.assertEquals(1, symbolTable.size());

        JavaCompilationUnit compilationUnit = symbolTable.get("/tmp/T.java");
        Assertions.assertNotNull(compilationUnit);
        List<Import> imports = compilationUnit.getImports();
        Assertions.assertNotNull(imports);
        Assertions.assertEquals(2, imports.size());

        Import defaultImport = imports.stream()
                .filter(imp -> "java.util.List".equals(imp.getPath()))
                .findFirst()
                .orElse(null);
        Assertions.assertNotNull(defaultImport);
        Assertions.assertFalse(defaultImport.isStatic());
        Assertions.assertFalse(defaultImport.isWildcard());

        Import staticWildcardImport = imports.stream()
                .filter(imp -> "java.util.Collections".equals(imp.getPath()))
                .findFirst()
                .orElse(null);
        Assertions.assertNotNull(staticWildcardImport);
        Assertions.assertTrue(staticWildcardImport.isStatic());
        Assertions.assertTrue(staticWildcardImport.isWildcard());
    }
}
