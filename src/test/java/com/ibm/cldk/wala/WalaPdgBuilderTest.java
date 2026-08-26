package com.ibm.cldk.wala;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.ibm.cldk.CodeAnalyzer;
import com.ibm.cldk.schema.JCdgEdge;
import com.ibm.cldk.schema.JDdgEdge;
import com.ibm.cldk.syntactic_analysis.L3TestSupport;
import com.ibm.cldk.wala.WalaPdgBuilder.PdgOverlays;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Realworld tests for {@link WalaPdgBuilder}: compiles the aliased-Box fixture, builds the
 * per-method PDG overlays, and asserts CDG/DDG correctness including the heap DDG (the whole
 * reason the WALA engine exists) and RTA over-approximation behaviour.
 */
@Tag("realworld")
class WalaPdgBuilderTest {

    /**
     * The aliased-Box fixture.
     *
     * <p>Line numbers (1-based):
     * <pre>
     *  1: public class BoxFixture {
     *  2:     static class Box {
     *  3:         int f;
     *  4:     }
     *  5: (blank)
     *  6:     int scalar(int p) {
     *  7:         int x = p + 1;
     *  8:         return x;
     *  9:     }
     * 10: (blank)
     * 11:     int aliased(int p) {
     * 12:         Box a = new Box();
     * 13:         Box b = a;
     * 14:         a.f = p;
     * 15:         int r1 = b.f;
     * 16:         Box c = new Box();
     * 17:         c.f = 42;
     * 18:         int r2 = c.f;
     * 19:         return r1 + r2;
     * 20:     }
     * 21: (blank)
     * 22:     int conditional(int p) {
     * 23:         int result = 0;
     * 24:         if (p > 0) {
     * 25:             result = p;
     * 26:         } else {
     * 27:             result = -1;
     * 28:         }
     * 29:         return result;
     * 30:     }
     * 31: }
     * </pre>
     */
    private static final String FIXTURE_SOURCE =
            "public class BoxFixture {\n"
            + "    static class Box {\n"
            + "        int f;\n"
            + "    }\n"
            + "\n"
            + "    int scalar(int p) {\n"
            + "        int x = p + 1;\n"
            + "        return x;\n"
            + "    }\n"
            + "\n"
            + "    int aliased(int p) {\n"
            + "        Box a = new Box();\n"
            + "        Box b = a;\n"
            + "        a.f = p;\n"
            + "        int r1 = b.f;\n"
            + "        Box c = new Box();\n"
            + "        c.f = 42;\n"
            + "        int r2 = c.f;\n"
            + "        return r1 + r2;\n"
            + "    }\n"
            + "\n"
            + "    int conditional(int p) {\n"
            + "        int result = 0;\n"
            + "        if (p > 0) {\n"
            + "            result = p;\n"
            + "        } else {\n"
            + "            result = -1;\n"
            + "        }\n"
            + "        return result;\n"
            + "    }\n"
            + "}\n";

    // Source line constants matching the fixture above.
    private static final int STORE_A_LINE = 14; // a.f = p
    private static final int LOAD_B_LINE  = 15; // int r1 = b.f
    private static final int STORE_C_LINE = 17; // c.f = 42
    private static final int LOAD_C_LINE  = 18; // int r2 = c.f
    private static final int IF_LINE      = 24; // if (p > 0)

    // ----- case (a): scalar DATA_DEP -------------------------------------------------------

    /**
     * The {@code scalar} method has {@code int x = p + 1; return x;}: the scalar def-use
     * chain from the addition to the return must appear as a DDG edge with {@code prov=["ssa"]}.
     */
    @Test
    void scalarDataDepEdgeHasSsaProvenance(@TempDir Path tmp) throws Exception {
        String dir = compileFixture(tmp);
        WalaAnalysis wala = buildWala(dir);
        WalaAnalysis.MethodIr mir = findMethod(wala, "scalar");
        PdgOverlays overlays = buildOverlays(mir, wala, "scalar");

        assertFalse(overlays.ddg.isEmpty(), "scalar method must produce at least one DDG edge");
        boolean hasSsaEdge = overlays.ddg.stream()
                .anyMatch(e -> e.getProv().size() == 1 && "ssa".equals(e.getProv().get(0)));
        assertTrue(hasSsaEdge,
                "scalar method must have a DDG edge with prov=[\"ssa\"]; ddg: " + overlays.ddg);
    }

    // ----- case (b): aliased HEAP_DATA_DEP -------------------------------------------------

    /**
     * The {@code aliased} method stores into {@code a.f} and reads via the alias {@code b.f}.
     * WALA's RTA must produce a {@code HEAP_DATA_DEP} edge from the store line to the load line
     * with {@code prov=["points-to"]}.
     */
    @Test
    void heapDataDepEdgeHasPointsToProvenanceAndAliasedEndpoints(@TempDir Path tmp) throws Exception {
        String dir = compileFixture(tmp);
        WalaAnalysis wala = buildWala(dir);
        WalaAnalysis.MethodIr mir = findMethod(wala, "aliased");
        PdgOverlays overlays = buildOverlays(mir, wala, "aliased");

        List<JDdgEdge> heapEdges = overlays.ddg.stream()
                .filter(e -> e.getProv().size() == 1 && "points-to".equals(e.getProv().get(0)))
                .collect(Collectors.toList());

        assertFalse(heapEdges.isEmpty(),
                "aliased method must produce at least one DDG edge with prov=[\"points-to\"]");

        boolean hasAliasedEdge = heapEdges.stream()
                .anyMatch(e -> e.getSrc().startsWith(STORE_A_LINE + ":")
                        && e.getDst().startsWith(LOAD_B_LINE + ":"));
        assertTrue(hasAliasedEdge,
                "must have a heap DDG edge from a.f store (line " + STORE_A_LINE
                        + ") to b.f load (line " + LOAD_B_LINE
                        + "); heap edges: " + heapEdges);
    }

    // ----- case (c): CONTROL_DEP -----------------------------------------------------------

    /**
     * The {@code conditional} method has an {@code if (p > 0)} on line 24 and a guarded body
     * {@code result = p} on line 25. A CDG edge must run from the branch to the guarded statement.
     */
    @Test
    void controlDepEdgeExistsFromIfToGuardedStatement(@TempDir Path tmp) throws Exception {
        String dir = compileFixture(tmp);
        WalaAnalysis wala = buildWala(dir);
        WalaAnalysis.MethodIr mir = findMethod(wala, "conditional");
        PdgOverlays overlays = buildOverlays(mir, wala, "conditional");

        assertFalse(overlays.cdg.isEmpty(),
                "conditional method must produce at least one CDG edge");
        boolean hasBranchEdge = overlays.cdg.stream()
                .anyMatch(e -> e.getSrc().startsWith(IF_LINE + ":"));
        assertTrue(hasBranchEdge,
                "must have a CDG edge from the if-test at line " + IF_LINE
                        + "; cdg: " + overlays.cdg);
    }

    // ----- case (d): RTA over-approximation ------------------------------------------------

    /**
     * With two {@code Box} allocations in {@code aliased} (objects {@code a} and {@code c}),
     * RTA connects every {@code Box.f} store to every {@code Box.f} load regardless of object
     * identity. The heap edges MUST include cross-object pairs, documenting the expected
     * type-based imprecision: {@code a.f} store connecting to {@code c.f} load, and/or
     * {@code c.f} store connecting to {@code b.f} load.
     */
    @Test
    void rtaOverApproximationProducesCrossObjectHeapEdges(@TempDir Path tmp) throws Exception {
        String dir = compileFixture(tmp);
        WalaAnalysis wala = buildWala(dir);
        WalaAnalysis.MethodIr mir = findMethod(wala, "aliased");
        PdgOverlays overlays = buildOverlays(mir, wala, "aliased");

        List<JDdgEdge> heapEdges = overlays.ddg.stream()
                .filter(e -> e.getProv().size() == 1 && "points-to".equals(e.getProv().get(0)))
                .collect(Collectors.toList());

        // At least one cross-object pair must be present.
        boolean hasCrossAToC = heapEdges.stream()
                .anyMatch(e -> e.getSrc().startsWith(STORE_A_LINE + ":")
                        && e.getDst().startsWith(LOAD_C_LINE + ":"));
        boolean hasCrossCToB = heapEdges.stream()
                .anyMatch(e -> e.getSrc().startsWith(STORE_C_LINE + ":")
                        && e.getDst().startsWith(LOAD_B_LINE + ":"));
        assertTrue(hasCrossAToC || hasCrossCToB,
                "RTA must produce cross-object heap edges documenting type-based imprecision; "
                        + "heap edges: " + heapEdges);
    }

    // ----- helpers -------------------------------------------------------------------------

    private static String compileFixture(Path tmp) throws Exception {
        Path src = tmp.resolve("BoxFixture.java");
        Files.writeString(src, FIXTURE_SOURCE);
        int rc = ToolProvider.getSystemJavaCompiler().run(
                null, null, null, "-g", "-d", tmp.toString(), src.toString());
        assertEquals(0, rc, "fixture compilation must succeed");
        return tmp.toString();
    }

    private static WalaAnalysis buildWala(String dir) {
        CodeAnalyzer.projectRootPom = dir;
        return WalaAnalysis.of(dir, null, null)
                .orElseThrow(() -> new AssertionError("WalaAnalysis.of must succeed"));
    }

    private static WalaAnalysis.MethodIr findMethod(WalaAnalysis wala, String name) {
        return wala.applicationMethods().stream()
                .filter(m -> m.method.getName().toString().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("method '" + name + "' not found in "
                        + wala.applicationMethods().stream()
                                .map(m -> m.method.getName().toString())
                                .collect(Collectors.toList())));
    }

    private static PdgOverlays buildOverlays(
            WalaAnalysis.MethodIr mir, WalaAnalysis wala, String methodName) {
        BlockStmt body = L3TestSupport.methodBody(FIXTURE_SOURCE, methodName);
        Map<Integer, List<Statement>> byLine = InstructionToNode.statementsByLine(body);
        InstructionToNode mapper = new InstructionToNode(byLine);
        return WalaPdgBuilder.build(wala, mir, mapper);
    }
}
