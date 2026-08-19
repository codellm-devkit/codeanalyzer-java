package com.ibm.cldk.syntactic_analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.cldk.schema.JCallable;
import com.ibm.cldk.schema.JModule;
import com.ibm.cldk.schema.JType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Orchestration-level tests for {@link L1Extractor}: walking a real project directory, keying the
 * symbol table by stable relative paths, and retaining source so node text is a byte-slice. These are
 * the L1 gate checks stated in the design spec, exercised over a real (if small) project on disk.
 */
class L1ExtractorTest {

    private static Path writeProject(Path root) throws IOException {
        Path pkg = root.resolve("src/main/java/com/example");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("Greeter.java"),
                "package com.example;\n"
                        + "\n"
                        + "/** Greets. */\n"
                        + "public class Greeter {\n"
                        + "  private String name;\n"
                        + "  public String greet(String who) {\n"
                        + "    return \"hi \" + who;\n"
                        + "  }\n"
                        + "}\n",
                StandardCharsets.UTF_8);
        Files.writeString(pkg.resolve("Caller.java"),
                "package com.example;\n"
                        + "\n"
                        + "public class Caller {\n"
                        + "  void run() {\n"
                        + "    new Greeter().greet(\"world\");\n"
                        + "  }\n"
                        + "}\n",
                StandardCharsets.UTF_8);
        return root;
    }

    @Test
    void extractAll_keysModulesByStableRelativePaths(@TempDir Path tmp) throws IOException {
        Map<String, JModule> modules = L1Extractor.extractAll(writeProject(tmp), "myapp");

        assertEquals(2, modules.size());
        for (String key : modules.keySet()) {
            assertFalse(key.startsWith("/"), "symbol_table keys must not be absolute: " + key);
            assertFalse(key.contains(".."), "symbol_table keys must not escape the root: " + key);
            assertFalse(key.contains("\\"), "separators must be normalised: " + key);
        }
        assertTrue(modules.containsKey("src/main/java/com/example/Greeter.java"));
    }

    @Test
    void extractAll_retainsSourceSoNodeTextIsAByteSlice(@TempDir Path tmp) throws IOException {
        Map<String, JModule> modules = L1Extractor.extractAll(writeProject(tmp), "myapp");
        JModule module = modules.get("src/main/java/com/example/Greeter.java");

        JType greeter = module.getTypes().get("Greeter");
        assertNotNull(greeter);
        JCallable greet = greeter.getCallables().get("greet(java.lang.String)");
        assertNotNull(greet, "signature should use resolved, erased parameter types");

        int[] bytes = greet.getSpan().getBytes();
        String sliced = new String(
                module.getSource().getBytes(StandardCharsets.UTF_8), bytes[0], bytes[1] - bytes[0],
                StandardCharsets.UTF_8);
        assertTrue(sliced.startsWith("public String greet(String who)"), "got: " + sliced);
        assertTrue(sliced.endsWith("}"));
    }

    @Test
    void extractAll_resolvesAcrossFilesInTheProject(@TempDir Path tmp) throws IOException {
        // Caller references Greeter from another file: the project's own sources must be on the
        // solver's path, otherwise cross-file types silently degrade to bare spellings.
        Map<String, JModule> modules = L1Extractor.extractAll(writeProject(tmp), "myapp");
        JCallable run = modules.get("src/main/java/com/example/Caller.java")
                .getTypes().get("Caller").getCallables().get("run()");

        assertTrue(run.getRefs().getTypes().contains("com.example.Greeter"),
                "cross-file type should resolve to its qualified name, got: " + run.getRefs().getTypes());
        assertTrue(run.getBody().values().stream()
                        .anyMatch(n -> "com.example.Greeter".equals(n.getReceiverType())),
                "the greet(...) call's receiver type should resolve across files");
    }

    @Test
    void extractAll_producesStableIdsAndDeterministicOutputAcrossRuns(@TempDir Path tmp) throws IOException {
        Path root = writeProject(tmp);
        assertEquals(
                com.ibm.cldk.schema.V2Json.compact().toJson(L1Extractor.extractAll(root, "myapp")),
                com.ibm.cldk.schema.V2Json.compact().toJson(L1Extractor.extractAll(root, "myapp")),
                "two runs over unchanged source must produce byte-identical output");
    }
}
