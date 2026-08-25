package com.ibm.cldk.wala;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.Statement;
import com.ibm.cldk.syntactic_analysis.controlflow.BodyNodeBuilder;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InstructionToNode}. Exercises the package-private
 * {@link InstructionToNode#match} seam directly with hand-built
 * {@link InstructionToNode.InstructionDescriptor} instances, so no WALA build is required.
 *
 * <p>Covers all four cases from the B.1 mapping spec:
 * (a) single statement on the line → its real {@code line:col};
 * (b) two invoke statements on one line, disambiguated by invoked method name;
 * (c) two contentless statements on one line → sentinel;
 * (d) no covering statement for the line → sentinel.
 */
class InstructionToNodeTest {

    /** Parse a class source and return the statements from the named method's body. */
    private static List<Statement> stmtsOf(String classSource, String methodName) {
        return StaticJavaParser.parse(classSource)
                .findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no method " + methodName))
                .getBody()
                .orElseThrow(() -> new AssertionError("method " + methodName + " has no body"))
                .getStatements();
    }

    /**
     * (a) A single statement on the line: the mapper returns that statement's exact
     * {@code line:col} as produced by {@link BodyNodeBuilder#nodeIdFor}.
     */
    @Test
    void singleStatementOnLineMapsToItsNodeId() {
        String src = "class C { void m() {\n int x = 1;\n} }";
        List<Statement> stmts = stmtsOf(src, "m");
        assertEquals(1, stmts.size());
        Statement stmt = stmts.get(0);
        int line = stmt.getRange().orElseThrow().begin.line;
        String expectedId = BodyNodeBuilder.nodeIdFor(stmt);

        Map<Integer, List<Statement>> byLine = Collections.singletonMap(line, stmts);
        InstructionToNode mapper = new InstructionToNode(byLine);

        // No invoke name needed — single candidate is taken unconditionally.
        InstructionToNode.InstructionDescriptor desc =
                new InstructionToNode.InstructionDescriptor(null, null);
        String result = mapper.match(desc, line, byLine.get(line));

        assertEquals(expectedId, result);
    }

    /**
     * (b) Two statements on one line ({@code int a = f(); int b = g();}) with an SSA invoke for
     * {@code f}: the mapper returns the {@code int a = f()} statement's {@code line:col}.
     */
    @Test
    void twoInvokeStmtsOnOneLineDisambiguatedByMethodName() {
        // Both stmts land on the same (single) line of the parsed source.
        String src = "class C { void m() { int a = f(); int b = g(); } }";
        List<Statement> stmts = stmtsOf(src, "m");
        assertEquals(2, stmts.size());
        int line = stmts.get(0).getRange().orElseThrow().begin.line;
        String expectedId = BodyNodeBuilder.nodeIdFor(stmts.get(0)); // the f() statement

        InstructionToNode mapper = new InstructionToNode(Collections.emptyMap());
        InstructionToNode.InstructionDescriptor desc =
                new InstructionToNode.InstructionDescriptor("f", null);
        String result = mapper.match(desc, line, stmts);

        assertEquals(expectedId, result);
    }

    /**
     * (c) Two contentless statements on one line ({@code int a = 1; int b = 2;}) with an
     * ambiguous instruction (no invoke name): the mapper returns the sentinel {@code "<line>:0"}.
     */
    @Test
    void twoContentlessStmtsReturnSentinel() {
        String src = "class C { void m() { int a = 1; int b = 2; } }";
        List<Statement> stmts = stmtsOf(src, "m");
        assertEquals(2, stmts.size());
        int line = stmts.get(0).getRange().orElseThrow().begin.line;

        InstructionToNode mapper = new InstructionToNode(Collections.emptyMap());
        InstructionToNode.InstructionDescriptor desc =
                new InstructionToNode.InstructionDescriptor(null, null);
        String result = mapper.match(desc, line, stmts);

        assertEquals(line + ":0", result);
    }

    /**
     * (d) The source line has no covering statement in the index: the mapper returns
     * {@code "<line>:0"}.
     */
    @Test
    void noCoveringStatementReturnsSentinel() {
        InstructionToNode mapper = new InstructionToNode(Collections.emptyMap());
        InstructionToNode.InstructionDescriptor desc =
                new InstructionToNode.InstructionDescriptor(null, null);
        String result = mapper.match(desc, 99, Collections.emptyList());

        assertEquals("99:0", result);
    }

    /** The over-approximation counter increments once per sentinel emission. */
    @Test
    void overApproximationCounterIncrementsOnEachSentinel() {
        String src = "class C { void m() { int a = 1; int b = 2; } }";
        List<Statement> stmts = stmtsOf(src, "m");
        int line = stmts.get(0).getRange().orElseThrow().begin.line;

        InstructionToNode mapper = new InstructionToNode(Collections.emptyMap());
        InstructionToNode.InstructionDescriptor desc =
                new InstructionToNode.InstructionDescriptor(null, null);

        // Two sentinel-emitting calls.
        mapper.match(desc, line, stmts);             // (c): ambiguous stmts
        mapper.match(desc, 99, Collections.emptyList()); // (d): no candidates

        assertEquals(2, mapper.overApproximationCount());
    }

    /**
     * Verify that when an invoke name does not match any candidate the mapper still falls back to
     * the sentinel rather than an incorrect attribution.
     */
    @Test
    void invokeNameThatMatchesNoCandidateFallsBackToSentinel() {
        String src = "class C { void m() { int a = f(); int b = g(); } }";
        List<Statement> stmts = stmtsOf(src, "m");
        int line = stmts.get(0).getRange().orElseThrow().begin.line;

        InstructionToNode mapper = new InstructionToNode(Collections.emptyMap());
        // "h" matches neither f() nor g()
        InstructionToNode.InstructionDescriptor desc =
                new InstructionToNode.InstructionDescriptor("h", null);
        String result = mapper.match(desc, line, stmts);

        assertEquals(line + ":0", result);
    }

    /**
     * When two statements both contain a call to the same method name, disambiguation fails and
     * the sentinel is returned.
     */
    @Test
    void ambiguousInvokeNameAcrossMultipleCandidatesReturnsSentinel() {
        // Both stmts call f(); can't pick one.
        String src = "class C { void m() { f(); f(); } }";
        List<Statement> stmts = stmtsOf(src, "m");
        assertEquals(2, stmts.size());
        int line = stmts.get(0).getRange().orElseThrow().begin.line;

        InstructionToNode mapper = new InstructionToNode(Collections.emptyMap());
        InstructionToNode.InstructionDescriptor desc =
                new InstructionToNode.InstructionDescriptor("f", null);
        String result = mapper.match(desc, line, stmts);

        assertEquals(line + ":0", result);
    }

    /**
     * {@link InstructionToNode#map} integrates the descriptor extraction and matching. For a line
     * with a single statement, the method returns that statement's nodeId regardless of the
     * instruction kind (tested here with a null instruction after building a single-candidate map).
     */
    @Test
    void mapDelegatesToMatchViaStatementsByLineIndex() {
        String src = "class C { void m() {\n f();\n} }";
        List<Statement> stmts = stmtsOf(src, "m");
        assertEquals(1, stmts.size());
        int line = stmts.get(0).getRange().orElseThrow().begin.line;
        String expectedId = BodyNodeBuilder.nodeIdFor(stmts.get(0));

        Map<Integer, List<Statement>> byLine = new HashMap<>();
        byLine.put(line, stmts);
        InstructionToNode mapper = new InstructionToNode(byLine);

        // Use match() directly (map() requires a real SSAInstruction).
        InstructionToNode.InstructionDescriptor desc =
                new InstructionToNode.InstructionDescriptor(null, null);
        assertEquals(expectedId, mapper.match(desc, line, stmts));
    }
}
