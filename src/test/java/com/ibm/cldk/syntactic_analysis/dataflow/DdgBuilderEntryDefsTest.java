package com.ibm.cldk.syntactic_analysis.dataflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.ibm.cldk.schema.JDdgEdge;
import com.ibm.cldk.syntactic_analysis.L1BuildContext;
import com.ibm.cldk.syntactic_analysis.L3Overlays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * A callable's formals are defined at {@code @entry}, so its own dataflow can see them. Before this,
 * {@code @entry} had no AST node and therefore no defs, and no ddg edge could root at a parameter.
 */
class DdgBuilderEntryDefsTest {

    /** Build the AST-engine L3 overlays for one method's body, with its formals declared. */
    private static List<JDdgEdge> ddgOf(String source, String methodName) {
        MethodDeclaration md = StaticJavaParser.parse(source)
                .findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals(methodName))
                .findFirst().orElseThrow();
        BlockStmt body = md.getBody().orElseThrow();
        List<String> formals = md.getParameters().stream()
                .map(p -> p.getNameAsString()).collect(Collectors.toList());
        L1BuildContext ctx = new L1BuildContext("can://java/t", "T.java", source, 3, 3, "ast");
        return L3Overlays.build(body, new LinkedHashMap<>(), ctx, 3, formals).ddg();
    }

    private static boolean hasEntryEdge(List<JDdgEdge> ddg, String var) {
        return ddg.stream().anyMatch(e -> "@entry".equals(e.getSrc()) && var.equals(e.getVar()));
    }

    @Test
    void aParameterUsedInTheReturnGetsAnEntryRootedEdge() {
        List<JDdgEdge> ddg = ddgOf("class T { int m(int q) { return q; } }", "m");
        assertTrue(hasEntryEdge(ddg, "q"),
                "the formal is defined at @entry and used by the return: " + ddg);
        assertEquals(1, ddg.stream().filter(e -> "@entry".equals(e.getSrc())).count(),
                "exactly one entry-rooted edge for one used formal: " + ddg);
    }

    @Test
    void anUnusedParameterProducesNoEdge() {
        List<JDdgEdge> ddg = ddgOf("class T { int m(int q) { return 1; } }", "m");
        assertTrue(ddg.stream().noneMatch(e -> "@entry".equals(e.getSrc())),
                "a formal nothing reads yields no edge — a def with no use is not a dependence: " + ddg);
    }

    @Test
    void aLocalShadowingTheFormalKillsTheEntryDefinition() {
        // `q` is reassigned before the read, so the read depends on the assignment, not on @entry.
        List<JDdgEdge> ddg = ddgOf("class T { int m(int q) { q = 5; return q; } }", "m");
        assertTrue(ddg.stream().noneMatch(e -> "@entry".equals(e.getSrc())),
                "the reassignment kills the entry def before any use reaches it: " + ddg);
    }

    @Test
    void twoFormalsBothUsedEachGetTheirOwnEdge() {
        List<JDdgEdge> ddg = ddgOf("class T { int m(int a, int b) { return a + b; } }", "m");
        assertTrue(hasEntryEdge(ddg, "a") && hasEntryEdge(ddg, "b"), ddg.toString());
    }

    @Test
    void everyEntryEdgeCarriesSsaProvenance() {
        List<JDdgEdge> ddg = ddgOf("class T { int m(int q) { return q; } }", "m");
        ddg.stream().filter(e -> "@entry".equals(e.getSrc()))
                .forEach(e -> assertEquals(List.of("ssa"), e.getProv(),
                        "an entry def is syntactic, not points-to derived"));
    }
}
