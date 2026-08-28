package com.ibm.cldk.syntactic_analysis.dataflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.cldk.schema.JCallable;
import com.ibm.cldk.schema.JModule;
import com.ibm.cldk.schema.V2Json;
import com.ibm.cldk.syntactic_analysis.L1Extractor;
import com.ibm.cldk.syntactic_analysis.L2CallGraph;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SummaryPassTest {

    private static final String FIXTURE = "src/test/resources/test-applications/l4-sdg-test";

    private static Map<String, JModule> analyzed() throws Exception {
        Map<String, JModule> modules = L1Extractor.extractAll(
                Paths.get(FIXTURE), "l4-sdg-test", null, new LinkedHashMap<>(), 3, 3, "ast");
        L2CallGraph.Result l2 = L2CallGraph.build("l4-sdg-test", modules, null, true);
        SdgVertices.apply(modules);
        SummaryPass.apply(modules, l2.callGraph(), 3);
        return modules;
    }

    private static JCallable callable(Map<String, JModule> modules, String idSuffix) {
        return modules.values().stream()
                .flatMap(m -> m.getTypes().values().stream())
                .flatMap(t -> t.getCallables().values().stream())
                .filter(c -> c.getId().endsWith(idSuffix))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void transitiveFlowYieldsSummaryEdgeInTheCaller() throws Exception {
        Map<String, JModule> modules = analyzed();
        // Chain.a calls b; b's param flows to its return via c — so a's call site gets a summary edge.
        JCallable a = callable(modules, "/Chain/a(int)");
        assertNotNull(a.getSummary(), "a carries a summary edge for the a→b→c flow");
        assertEquals(1, a.getSummary().size());
        String src = a.getSummary().get(0).getSrc();
        String dst = a.getSummary().get(0).getDst();
        assertTrue(src.endsWith("/actual_in:0") && dst.endsWith("/actual_out"), src + " → " + dst);
        assertEquals(
                src.substring(0, src.indexOf("/actual_in")),
                dst.substring(0, dst.indexOf("/actual_out")),
                "same call site");
    }

    @Test
    void mutualRecursionReachesFixpointAndIsDeterministic() throws Exception {
        Map<String, JModule> m1 = analyzed(); // terminating at all is half the gate
        Map<String, JModule> m2 = analyzed();
        assertEquals(
                V2Json.compact().toJson(m1),
                V2Json.compact().toJson(m2),
                "two runs byte-identical (fixpoint order must not leak)");
    }

    @Test
    void mutualRecursionSummarisesBothDirections() throws Exception {
        Map<String, JModule> modules = analyzed();
        // even → odd → even is one SCC, so both are solved by the same fixpoint loop: reaching this
        // assertion at all means it terminated, and each member ends up shortcutting its own site.
        for (String id : new String[] {"/Mutual/even(int)", "/Mutual/odd(int)"}) {
            JCallable c = callable(modules, id);
            assertNotNull(c.getSummary(), id + " carries its call site's summary edge");
            assertEquals(1, c.getSummary().size(), id);
            assertTrue(c.getSummary().get(0).getSrc().endsWith("/actual_in:0"), id);
            assertTrue(c.getSummary().get(0).getDst().endsWith("/actual_out"), id);
        }
    }

    @Test
    void aCallableWithNoPassThroughCallSiteKeepsSummaryAbsent() throws Exception {
        Map<String, JModule> modules = analyzed();
        // Chain.c calls nothing, so it has no call site to shortcut...
        assertNull(callable(modules, "/Chain/c(int)").getSummary(), "no call sites, no summary");
        // ...and Heap.roundTrip's two sites are a void call (no actual_out) and a no-arg call (no
        // actual_in), so neither can carry a shortcut. Absent, not an empty list.
        assertNull(callable(modules, "/Heap/roundTrip(int)").getSummary(), "no viable site, no summary");
    }

    @Test
    void summaryEndpointsAreExistingLocalBodyNodes() throws Exception {
        Map<String, JModule> modules = analyzed();
        modules.values().stream()
                .flatMap(m -> m.getTypes().values().stream())
                .flatMap(t -> t.getCallables().values().stream())
                .filter(c -> c.getSummary() != null)
                .forEach(c -> c.getSummary().forEach(e -> {
                    assertTrue(c.getBody().containsKey(e.getSrc()), c.getId() + " src " + e.getSrc());
                    assertTrue(c.getBody().containsKey(e.getDst()), c.getId() + " dst " + e.getDst());
                }));
    }
}
