package com.ibm.cldk.syntactic_analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.cldk.schema.JBodyNode;
import com.ibm.cldk.schema.JCallable;
import com.ibm.cldk.schema.JModule;
import com.ibm.cldk.schema.JType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A {@code return} body node carries the returned expression's source in {@code argument_expr}
 * (#259, spec 2026-09-11 D3), and a bare {@code return} carries nothing.
 */
class ReturnExpressionTest {

    @TempDir
    Path root;

    private Map<String, JBodyNode> bodyOf(String callableName) throws Exception {
        Path src = root.resolve("src/main/java/demo");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Home.java"),
                "package demo;\n"
                        + "public class Home {\n"
                        + "    String view() { return \"home\"; }\n"
                        + "    int sum(int a, int b) { return a + b; }\n"
                        + "    void bare() { return; }\n"
                        + "}\n",
                StandardCharsets.UTF_8);
        Map<String, JModule> modules = L1Extractor.extractAll(
                root, "app", null, new LinkedHashMap<>(), 3, 3, "ast");
        for (JModule m : modules.values()) {
            for (JType t : m.getTypes().values()) {
                for (JCallable c : t.getCallables().values()) {
                    if (c.getSignature().startsWith(callableName + "(")) {
                        return c.getBody();
                    }
                }
            }
        }
        throw new AssertionError("no callable " + callableName);
    }

    private static List<JBodyNode> returns(Map<String, JBodyNode> body) {
        List<JBodyNode> out = new ArrayList<>();
        for (JBodyNode n : body.values()) {
            if ("return".equals(n.getKind())) {
                out.add(n);
            }
        }
        return out;
    }

    @Test
    void aReturnedLiteralIsCarriedAsSource() throws Exception {
        List<JBodyNode> r = returns(bodyOf("view"));
        assertEquals(1, r.size());
        assertEquals(List.of("\"home\""), r.get(0).getArgumentExpr());
    }

    @Test
    void aReturnedExpressionIsCarriedVerbatim() throws Exception {
        List<JBodyNode> r = returns(bodyOf("sum"));
        assertEquals(1, r.size());
        assertEquals(List.of("a + b"), r.get(0).getArgumentExpr());
    }

    @Test
    void aBareReturnCarriesNothing() throws Exception {
        List<JBodyNode> r = returns(bodyOf("bare"));
        assertEquals(1, r.size());
        assertTrue(r.get(0).getArgumentExpr().isEmpty());
    }
}
