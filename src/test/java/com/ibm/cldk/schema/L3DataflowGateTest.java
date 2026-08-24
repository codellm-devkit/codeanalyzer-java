package com.ibm.cldk.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonObject;
import com.ibm.cldk.syntactic_analysis.L1Extractor;
import com.ibm.cldk.syntactic_analysis.L2CallGraph;
import com.ibm.cldk.syntactic_analysis.L3TestSupport;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The L3 conformance gate: run the real producer over a control-flow fixture and assert the structural
 * invariants the design pins — CFG well-formedness, exception edges, the PDG backward-slice under the
 * syntactic DDG semantics, the {@code L2 ⊆ L3} monotonicity rule, determinism, and JSON-schema
 * conformance at level 3.
 */
class L3DataflowGateTest {

    private static final Path FIXTURE = Paths.get("src/test/resources/test-applications/dataflow-test");
    private static final String APP = "app";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Map<String, JModule> modules(int level) throws IOException {
        return L1Extractor.extractAll(FIXTURE, APP, null, new LinkedHashMap<>(), level, 3);
    }

    private static Analysis analyse(int level) throws IOException {
        Map<String, JModule> modules = modules(level);
        L2CallGraph.Result l2 = L2CallGraph.build(APP, modules);
        return V2Emitter.emit(APP, level, modules, "test", l2.callGraph(), l2.externalSymbols());
    }

    private static JsonObject json(int level) throws IOException {
        return V2Json.compact().toJsonTree(analyse(level)).getAsJsonObject();
    }

    // ---- CFG well-formedness -------------------------------------------------------------------

    @Test
    void everyCallableWithACfgIsWellFormed() throws IOException {
        Map<String, JModule> modules = modules(3);
        for (JModule m : modules.values()) {
            for (JType t : m.getTypes().values()) {
                for (JCallable c : t.getCallables().values()) {
                    if (c.getCfg() != null) {
                        assertWellFormed(c);
                    }
                }
            }
        }
    }

    private static void assertWellFormed(JCallable c) {
        Map<String, JBodyNode> body = c.getBody();
        assertTrue(body.containsKey("@entry"), c.getSignature() + " has an @entry");
        assertTrue(body.containsKey("@exit"), c.getSignature() + " has an @exit");

        Map<String, List<String>> succ = new LinkedHashMap<>();
        Set<String> endpoints = new HashSet<>();
        for (JCfgEdge e : c.getCfg()) {
            succ.computeIfAbsent(e.getSrc(), k -> new java.util.ArrayList<>()).add(e.getDst());
            endpoints.add(e.getSrc());
            endpoints.add(e.getDst());
        }
        for (String id : endpoints) {
            if (!id.startsWith("@")) {
                assertNotNull(body.get(id), c.getSignature() + " cfg endpoint " + id + " is a real body node");
                assertNotNull(body.get(id).getSpan(), c.getSignature() + " node " + id + " carries a span");
            }
        }
        Set<String> fromEntry = reach(succ, "@entry");
        for (String id : endpoints) {
            assertTrue(fromEntry.contains(id), c.getSignature() + ": " + id + " unreachable from @entry");
        }
    }

    private static Set<String> reach(Map<String, List<String>> adj, String start) {
        Set<String> seen = new HashSet<>();
        Deque<String> q = new ArrayDeque<>();
        q.add(start);
        seen.add(start);
        while (!q.isEmpty()) {
            for (String y : adj.getOrDefault(q.poll(), List.of())) {
                if (seen.add(y)) {
                    q.add(y);
                }
            }
        }
        return seen;
    }

    @Test
    void tryCatchProducesAnExceptionEdge() throws IOException {
        JCallable risky = L3TestSupport.findCallable(modules(3), "risky(int)").orElseThrow();
        assertTrue(risky.getCfg().stream().anyMatch(e -> e.getKind().equals("exception")),
                "the try/catch method has an exception edge");
    }

    @Test
    void returnInsideTryFinallyRunsTheFinally() throws IOException {
        JCallable via = L3TestSupport.findCallable(modules(3), "viaFinally(int)").orElseThrow();
        String ret = via.getBody().entrySet().stream()
                .filter(e -> "return".equals(e.getValue().getKind()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new AssertionError("viaFinally has a return"));
        String afterReturn = via.getCfg().stream()
                .filter(e -> e.getSrc().equals(ret) && e.getKind().equals("return"))
                .map(JCfgEdge::getDst)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the return has a return edge"));
        assertNotEquals("@exit", afterReturn, "the return runs the finally before leaving the method");

        Map<String, List<String>> succ = new LinkedHashMap<>();
        via.getCfg().forEach(e -> succ.computeIfAbsent(e.getSrc(), k -> new java.util.ArrayList<>()).add(e.getDst()));
        assertTrue(reach(succ, afterReturn).contains("@exit"), "after the finally, control reaches @exit");
    }

    // ---- PDG backward slice --------------------------------------------------------------------

    @Test
    void backwardSliceOfSumIncludesItsDefsAndLoopButNotUnrelatedVariables() throws IOException {
        JCallable compute = L3TestSupport.findCallable(modules(3), "compute(int)").orElseThrow();

        String returnNode = compute.getBody().entrySet().stream()
                .filter(e -> "return".equals(e.getValue().getKind()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new AssertionError("compute has a return node"));

        // Reverse adjacency over cdg ∪ ddg (the PDG).
        Map<String, List<String>> back = new LinkedHashMap<>();
        compute.getCdg().forEach(e -> back.computeIfAbsent(e.getDst(), k -> new java.util.ArrayList<>()).add(e.getSrc()));
        compute.getDdg().forEach(e -> back.computeIfAbsent(e.getDst(), k -> new java.util.ArrayList<>()).add(e.getSrc()));
        Set<String> slice = reach(back, returnNode);

        // The definitions of sum that reach the return (initial + loop-carried) are in the slice.
        List<String> sumDefs = compute.getDdg().stream()
                .filter(e -> e.getVar().equals("sum") && e.getDst().equals(returnNode))
                .map(JDdgEdge::getSrc)
                .collect(Collectors.toList());
        assertTrue(sumDefs.size() >= 2, "sum reaches its return from both the initial and the loop-carried def");
        assertTrue(slice.containsAll(sumDefs), "the sum definitions are in the backward slice");

        // The loop test controls the loop-carried def, so it is in the slice.
        String loop = compute.getBody().entrySet().stream()
                .filter(e -> "loop".equals(e.getValue().getKind()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> new AssertionError("compute has a loop node"));
        assertTrue(slice.contains(loop), "the loop test is in the slice (it controls the loop-carried def)");

        // `other` is unrelated to sum: its definition is NOT in the slice.
        compute.getDdg().stream()
                .filter(e -> e.getVar().equals("other"))
                .map(JDdgEdge::getSrc)
                .forEach(otherDef -> assertFalse(slice.contains(otherDef),
                        "the unrelated variable 'other' is not in sum's backward slice"));
    }

    // ---- L2 ⊆ L3, determinism, schema ----------------------------------------------------------

    @Test
    void level3ContainsEverythingLevel2Emitted() throws IOException {
        JsonObject l2 = json(2).getAsJsonObject("application");
        JsonObject l3 = json(3).getAsJsonObject("application");

        // Every L2 call_graph edge (src,dst) survives at L3.
        Set<String> l3Edges = l2.getAsJsonArray("call_graph") == null ? Set.of() : l3.getAsJsonArray("call_graph")
                .asList().stream()
                .map(e -> e.getAsJsonObject().get("src").getAsString() + "->" + e.getAsJsonObject().get("dst").getAsString())
                .collect(Collectors.toSet());
        if (l2.getAsJsonArray("call_graph") != null) {
            l2.getAsJsonArray("call_graph").forEach(e -> {
                String key = e.getAsJsonObject().get("src").getAsString() + "->"
                        + e.getAsJsonObject().get("dst").getAsString();
                assertTrue(l3Edges.contains(key), "L3 keeps the L2 call edge " + key);
            });
        }

        // For compute: L2 body-node keys ⊆ L3 body-node keys, and L3 adds the cfg overlay.
        JCallable computeL2 = L3TestSupport.findCallable(modules(2), "compute(int)").orElseThrow();
        JCallable computeL3 = L3TestSupport.findCallable(modules(3), "compute(int)").orElseThrow();
        assertTrue(computeL2.getCfg() == null, "L2 has no cfg");
        assertNotNull(computeL3.getCfg(), "L3 has a cfg");
        assertTrue(computeL3.getBody().keySet().containsAll(computeL2.getBody().keySet()),
                "every L2 body node key is still present at L3");
    }

    @Test
    void level3OutputIsDeterministic() throws IOException {
        assertEquals(json(3).toString(), json(3).toString());
    }

    @Test
    void conformsToTheCanonicalSchemaAtLevelThree() throws IOException {
        Set<ValidationMessage> problems;
        try (InputStream in = L3DataflowGateTest.class.getResourceAsStream("/schema/analysis.v2.schema.json")) {
            assertNotNull(in, "the canonical v2 schema must be on the test classpath");
            JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in);
            problems = schema.validate(MAPPER.readTree(V2Json.compact().toJson(analyse(3))));
        }
        assertTrue(problems.isEmpty(), "level-3 output must conform to the schema, but got:\n  "
                + problems.stream().map(ValidationMessage::getMessage).collect(Collectors.joining("\n  ")));
    }
}
