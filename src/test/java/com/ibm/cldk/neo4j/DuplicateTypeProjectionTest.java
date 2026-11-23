package com.ibm.cldk.neo4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.cldk.neo4j.GraphRows.EdgeRow;
import com.ibm.cldk.schema.JModule;
import com.ibm.cldk.schema.V2Emitter;
import com.ibm.cldk.syntactic_analysis.L1Extractor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The projector resolves a duplicated type name to the copy nearest the referring module (#269):
 * extends and import edges from each service reach that service's own {@code com.shared.Base}.
 */
class DuplicateTypeProjectionTest {

    private static void write(Path root, String rel, String text) throws IOException {
        Path f = root.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, text, StandardCharsets.UTF_8);
    }

    private static GraphRows project(Path root) throws IOException {
        for (String svc : new String[] {"svc-a", "svc-b"}) {
            String src = svc + "/src/main/java/";
            String child = "Child" + Character.toUpperCase(svc.charAt(4));
            write(root, src + "com/shared/Base.java", "package com.shared;\npublic class Base {}\n");
            write(root, src + "com/shared/" + child + ".java",
                    "package com.shared;\npublic class " + child + " extends Base {}\n");
            write(root, src + "com/other/Uses.java",
                    "package com.other;\nimport com.shared.Base;\npublic class Uses { Base base; }\n");
        }
        Map<String, JModule> modules = L1Extractor.extractAll(root, "dt", null, new LinkedHashMap<>(), 1, 3, "ast");
        return V2GraphProjector.project(V2Emitter.emit("dt", 1, modules, "test"), "dt");
    }

    private static List<EdgeRow> edges(GraphRows rows, String type) {
        return rows.edges.stream().filter(e -> e.type.equals(type)).collect(Collectors.toList());
    }

    @Test
    void extendsReachesTheSubclassesOwnServicesCopy(@TempDir Path tmp) throws IOException {
        List<EdgeRow> ext = edges(project(tmp), "J_EXTENDS");
        assertEquals(2, ext.size(), ext.toString());
        for (EdgeRow e : ext) {
            String svc = e.from.value.contains("/svc-a/") ? "/svc-a/" : "/svc-b/";
            assertTrue(e.to.value.contains(svc + "src/main/java/com/shared/Base.java"),
                    e.from.value + " extends " + e.to.value);
        }
    }

    @Test
    void importsReachTheImportingServicesOwnCopy(@TempDir Path tmp) throws IOException {
        List<EdgeRow> imports = edges(project(tmp), "J_IMPORTS").stream()
                .filter(e -> e.to.value.contains("com/shared/Base.java"))
                .collect(Collectors.toList());
        assertEquals(2, imports.size(), imports.toString());
        for (EdgeRow e : imports) {
            String svc = e.from.value.contains("svc-a/") ? "svc-a/" : "svc-b/";
            assertTrue(e.to.value.contains(svc + "src/main/java/com/shared/Base.java"),
                    e.from.value + " imports " + e.to.value);
        }
    }
}
