package com.ibm.cldk.syntactic_analysis.dataflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.cldk.schema.JBodyNode;
import com.ibm.cldk.schema.JCallable;
import com.ibm.cldk.schema.JDdgEdge;
import com.ibm.cldk.schema.JModule;
import com.ibm.cldk.schema.V2Json;
import com.ibm.cldk.syntactic_analysis.L1Extractor;
import com.ibm.cldk.syntactic_analysis.L2CallGraph;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SdgPortEdgesTest {

    private static final String FIXTURE = "src/test/resources/test-applications/l4-sdg-test";

    private static final Set<String> PORT_KINDS =
            new HashSet<>(Arrays.asList("formal_in", "formal_out", "actual_in", "actual_out"));

    private static Map<String, JModule> analyzed() throws Exception {
        Map<String, JModule> modules = L1Extractor.extractAll(
                Paths.get(FIXTURE), "l4-sdg-test", null, new LinkedHashMap<>(), 3, 3, "ast");
        L2CallGraph.Result l2 = L2CallGraph.build("l4-sdg-test", modules, null, true);
        SdgVertices.apply(modules);
        SummaryPass.apply(modules, l2.callGraph(), 3);
        SdgPortEdges.apply(modules);
        return modules;
    }

    private static List<JCallable> allCallables(Map<String, JModule> modules) {
        return modules.values().stream()
                .flatMap(m -> m.getTypes().values().stream())
                .flatMap(t -> t.getCallables().values().stream())
                .collect(Collectors.toList());
    }

    private static JCallable callable(Map<String, JModule> modules, String idSuffix) {
        return allCallables(modules).stream()
                .filter(c -> c.getId().endsWith(idSuffix))
                .findFirst()
                .orElseThrow();
    }

    private static boolean hasEdge(JCallable c, String src, String dst) {
        return c.getDdg().stream().anyMatch(e -> e.getSrc().equals(src) && e.getDst().equals(dst));
    }

    private static String kind(JCallable c, String localId) {
        JBodyNode n = c.getBody().get(localId);
        return n == null ? null : n.getKind();
    }

    @Test
    void aFormalInReachesTheStatementsThatReadTheParameter() throws Exception {
        Map<String, JModule> modules = analyzed();
        JCallable a = callable(modules, "/Chain/a(int)");

        // Chain.a is `return b(x + 1);` — the one statement reading x, which @entry already defines.
        List<JDdgEdge> fromPort = a.getDdg().stream()
                .filter(e -> "@formal_in:0".equals(e.getSrc()))
                .collect(Collectors.toList());
        assertFalse(fromPort.isEmpty(), "formal_in:0 must not have out-degree zero");
        for (JDdgEdge e : fromPort) {
            assertEquals("x", e.getVar(), "the edge carries the parameter's own access path");
            assertTrue(
                    hasEdge(a, "@entry", e.getDst()),
                    "every formal_in edge mirrors an @entry definition of the same parameter");
        }
    }

    @Test
    void returningStatementsReachFormalOut() throws Exception {
        Map<String, JModule> modules = analyzed();
        JCallable c = callable(modules, "/Chain/c(int)");

        List<String> returns = c.getBody().entrySet().stream()
                .filter(e -> "return".equals(e.getValue().getKind()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        assertFalse(returns.isEmpty(), "the fixture returns a value");
        for (String r : returns) {
            assertTrue(hasEdge(c, r, "@formal_out"), r + " → @formal_out");
        }
    }

    @Test
    void callSitePortsAreAttachedToTheStatementThatCarriesThem() throws Exception {
        Map<String, JModule> modules = analyzed();
        JCallable a = callable(modules, "/Chain/a(int)");

        String call = a.getBody().entrySet().stream()
                .filter(e -> "call".equals(e.getValue().getKind()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();
        String actualIn = call + "/actual_in:0";
        String actualOut = call + "/actual_out";

        String host = a.getDdg().stream()
                .filter(e -> e.getDst().equals(actualIn))
                .map(JDdgEdge::getSrc)
                .findFirst()
                .orElseThrow(() -> new AssertionError(actualIn + " has in-degree zero"));
        assertTrue(hasEdge(a, actualOut, host), actualOut + " → " + host);
        assertTrue(
                a.getBody().containsKey(host) && !PORT_KINDS.contains(kind(a, host)),
                "the attachment point is a real body node that carries dependence edges: " + host);
    }

    @Test
    void everyPortEdgeResolvesToABodyNodeOfItsOwnCallable() throws Exception {
        Map<String, JModule> modules = analyzed();
        for (JCallable c : allCallables(modules)) {
            if (c.getDdg() == null) {
                continue;
            }
            for (JDdgEdge e : c.getDdg()) {
                assertTrue(c.getBody().containsKey(e.getSrc()), c.getId() + " src " + e.getSrc());
                assertTrue(c.getBody().containsKey(e.getDst()), c.getId() + " dst " + e.getDst());
            }
        }
    }

    @Test
    void theLatticeIsNoLongerAnIsland() throws Exception {
        Map<String, JModule> modules = analyzed();
        long touching = allCallables(modules).stream()
                .filter(c -> c.getDdg() != null)
                .flatMap(c -> c.getDdg().stream()
                        .filter(e -> PORT_KINDS.contains(kind(c, e.getSrc()))
                                || PORT_KINDS.contains(kind(c, e.getDst()))))
                .count();
        assertTrue(touching > 0, "at least one ddg edge touches a port");
    }

    @Test
    void isDeterministic() throws Exception {
        assertEquals(V2Json.compact().toJson(analyzed()), V2Json.compact().toJson(analyzed()));
    }
}
