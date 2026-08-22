package com.ibm.cldk.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ibm.cldk.syntactic_analysis.L1Extractor;
import com.ibm.cldk.syntactic_analysis.L2CallGraph;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The L2 gate: each item maps to a definition-of-done clause from the design (§8), asserted on the
 * {@code call-graph-test} fixture at level 2 with {@code declared}-only edges. The load-bearing one is
 * no-dangling-endpoints — the structural invariant the JSON Schema cannot express.
 */
class L2CallGraphGateTest {

    private static final Path FIXTURE = Paths.get("src/test/resources/test-applications/call-graph-test");
    private static final String APP = "call-graph-test";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String USER = "can://java/call-graph-test/src/main/java/org/example/User.java/User";
    private static final String HELLO = USER + "/helloString()";
    private static final String LOG = USER + "/log()";
    private static final String GETNAME = USER + "/getName()";
    private static final String LOGLOG = USER + "/loglog()";
    private static final String GREETER =
            "can://java/call-graph-test/src/main/java/org/example/greeting/Greeter.java/Greeter";
    private static final String GREET = GREETER + "/greet(java.lang.String)";
    private static final String TRIM = "can://java/call-graph-test/@external/java.lang.String/trim()";

    /** Analyse the fixture at level 2 (declared only) and return the emitted payload as a Gson tree. */
    private static JsonObject analyseL2() throws IOException {
        Map<String, JModule> modules = L1Extractor.extractAll(FIXTURE, APP);
        L2CallGraph.Result l2 = L2CallGraph.build(APP, modules);
        Analysis analysis = V2Emitter.emit(APP, 2, modules, "test", l2.callGraph(), l2.externalSymbols());
        return V2Json.compact().toJsonTree(analysis).getAsJsonObject();
    }

    private static JsonObject application(JsonObject payload) {
        return payload.getAsJsonObject("application");
    }

    private static JsonArray callGraph(JsonObject payload) {
        return application(payload).getAsJsonArray("call_graph");
    }

    private static boolean hasEdge(JsonArray edges, String src, String dst) {
        for (JsonElement e : edges) {
            JsonObject edge = e.getAsJsonObject();
            if (src.equals(edge.get("src").getAsString()) && dst.equals(edge.get("dst").getAsString())) {
                return true;
            }
        }
        return false;
    }

    // --- Structural helpers over the emitted tree -----------------------------------------------

    /** Every callable id anywhere in the containment tree (types, nested types, local/anon classes). */
    private static void collectCallableIds(JsonObject type, Set<String> ids) {
        JsonObject callables = type.getAsJsonObject("callables");
        if (callables != null) {
            for (String sig : callables.keySet()) {
                JsonObject callable = callables.getAsJsonObject(sig);
                ids.add(callable.get("id").getAsString());
                forEachNestedType(callable, t -> collectCallableIds(t, ids));
            }
        }
        forEachNestedType(type, t -> collectCallableIds(t, ids));
    }

    private static void forEachNestedType(JsonObject node, java.util.function.Consumer<JsonObject> visit) {
        JsonObject types = node.getAsJsonObject("types");
        if (types != null) {
            for (String name : types.keySet()) {
                visit.accept(types.getAsJsonObject(name));
            }
        }
    }

    private static Set<String> allCallableIds(JsonObject payload) {
        Set<String> ids = new HashSet<>();
        JsonObject symbolTable = application(payload).getAsJsonObject("symbol_table");
        for (String file : symbolTable.keySet()) {
            forEachNestedType(symbolTable.getAsJsonObject(file), t -> collectCallableIds(t, ids));
        }
        return ids;
    }

    /** Map of callable id → number of its {@code call} body nodes that carry a {@code callee}. */
    private static void collectCalleeCounts(JsonObject type, Map<String, Integer> counts) {
        JsonObject callables = type.getAsJsonObject("callables");
        if (callables != null) {
            for (String sig : callables.keySet()) {
                JsonObject callable = callables.getAsJsonObject(sig);
                JsonObject body = callable.getAsJsonObject("body");
                int withCallee = 0;
                if (body != null) {
                    for (String local : body.keySet()) {
                        if (body.getAsJsonObject(local).has("callee")) {
                            withCallee++;
                        }
                    }
                }
                counts.merge(callable.get("id").getAsString(), withCallee, Integer::sum);
                forEachNestedType(callable, t -> collectCalleeCounts(t, counts));
            }
        }
        forEachNestedType(type, t -> collectCalleeCounts(t, counts));
    }

    private static Map<String, Integer> calleeCountsByCallable(JsonObject payload) {
        Map<String, Integer> counts = new HashMap<>();
        JsonObject symbolTable = application(payload).getAsJsonObject("symbol_table");
        for (String file : symbolTable.keySet()) {
            forEachNestedType(symbolTable.getAsJsonObject(file), t -> collectCalleeCounts(t, counts));
        }
        return counts;
    }

    // --- Gate items -----------------------------------------------------------------------------

    @Test
    void item3_namedEdgeAndCrossPackageEdgeArePresent() throws IOException {
        JsonArray edges = callGraph(analyseL2());
        assertTrue(hasEdge(edges, HELLO, LOG), "expected the intra-class edge helloString() -> log()");
        assertTrue(hasEdge(edges, HELLO, GREET),
                "expected the cross-package edge User.helloString() -> Greeter.greet(String)");
    }

    @Test
    void item1_noEdgeEndpointDangles() throws IOException {
        JsonObject payload = analyseL2();
        Set<String> callableIds = allCallableIds(payload);
        Set<String> externalKeys = application(payload).getAsJsonObject("external_symbols").keySet();
        for (JsonElement e : callGraph(payload)) {
            JsonObject edge = e.getAsJsonObject();
            String src = edge.get("src").getAsString();
            String dst = edge.get("dst").getAsString();
            assertTrue(callableIds.contains(src), "src must be a callable in the tree: " + src);
            assertTrue(callableIds.contains(dst) || externalKeys.contains(dst),
                    "dst must be a callable in the tree or an external symbol: " + dst);
        }
    }

    @Test
    void item2_everyEdgeCarriesNonEmptyProvenance() throws IOException {
        for (JsonElement e : callGraph(analyseL2())) {
            JsonArray prov = e.getAsJsonObject().getAsJsonArray("prov");
            assertTrue(prov.size() > 0, "every edge is attested by at least one analysis");
            assertEquals("declared", prov.get(0).getAsString(), "L2 declared-only produces `declared`");
        }
    }

    @Test
    void item4_declaredWeightLeavingACallableEqualsItsBackfilledCallSiteCount() throws IOException {
        // Each backfilled node contributes exactly 1 (several nodes sharing a target collapse into one
        // edge whose weight absorbs them); a node without a callee contributes nothing. So the summed
        // weight of declared edges leaving a callable must equal its count of callee-carrying nodes.
        JsonObject payload = analyseL2();
        Map<String, Integer> weightBySrc = new HashMap<>();
        for (JsonElement e : callGraph(payload)) {
            JsonObject edge = e.getAsJsonObject();
            weightBySrc.merge(edge.get("src").getAsString(), edge.get("weight").getAsInt(), Integer::sum);
        }
        Map<String, Integer> calleeCounts = calleeCountsByCallable(payload);
        for (Map.Entry<String, Integer> entry : calleeCounts.entrySet()) {
            int expected = entry.getValue();
            int actual = weightBySrc.getOrDefault(entry.getKey(), 0);
            assertEquals(expected, actual,
                    "declared weight leaving " + entry.getKey() + " must equal its callee-carrying nodes");
        }
    }

    @Test
    void item6_outputIsByteIdenticalAcrossRuns() throws IOException {
        assertEquals(analyseL2().toString(), analyseL2().toString(),
                "level-2 output must be deterministic — edge and external-symbol order included");
    }

    @Test
    void item7_noExternalSymbolHomesAnInProjectType() throws IOException {
        JsonObject payload = analyseL2();
        JsonObject external = application(payload).getAsJsonObject("external_symbols");
        for (String key : external.keySet()) {
            String declaring = external.getAsJsonObject(key).get("declaring_type").getAsString();
            assertFalse(declaring.equals("org.example.User") || declaring.equals("org.example.greeting.Greeter"),
                    "an in-project type must never be homed as external: " + declaring);
        }
    }

    @Test
    void externalCallIsHomedWithBinaryIdAndDottedDeclaringType() throws IOException {
        // Greeter.greet calls String.trim(): an out-of-project target homed so its edge does not dangle.
        JsonObject payload = analyseL2();
        assertTrue(hasEdge(callGraph(payload), GREET, TRIM), "expected greet(String) -> String.trim()");
        JsonObject external = application(payload).getAsJsonObject("external_symbols");
        assertTrue(external.has(TRIM), "the external target must be homed under its @external can-id");
        JsonObject sym = external.getAsJsonObject(TRIM);
        assertEquals("method", sym.get("kind").getAsString());
        assertEquals("trim()", sym.get("signature").getAsString());
        assertEquals("java.lang.String", sym.get("declaring_type").getAsString(),
                "declaring_type is the dotted source spelling; the id carries the binary form");
    }

    @Test
    void conformsToTheCanonicalSchemaAtLevelTwo() throws IOException {
        JsonNode payload = MAPPER.readTree(analyseL2().toString());
        try (InputStream in = getClass().getResourceAsStream("/schema/analysis.v2.schema.json")) {
            assertNotNull(in, "the canonical v2 schema must be on the test classpath");
            JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in);
            Set<ValidationMessage> problems = schema.validate(payload);
            assertTrue(problems.isEmpty(), "level-2 output must validate against the canonical schema:\n  "
                    + problems.stream().map(ValidationMessage::getMessage).collect(Collectors.joining("\n  ")));
        }
    }

    @Test
    void item5_l2IsL1PlusTheCalleeBackfillAndTheNewApplicationKeys() throws IOException {
        // L1 ⊆ L2: analysing the same fixture at L1 and L2, the L2 tree must contain everything L1
        // emitted, allowing only `callee` absent->present and the new application-scope keys.
        Map<String, JModule> l1Modules = L1Extractor.extractAll(FIXTURE, APP);
        JsonObject l1 = V2Json.compact().toJsonTree(V2Emitter.emit(APP, 1, l1Modules, "test"))
                .getAsJsonObject();
        JsonObject l2 = analyseL2();

        assertFalse(application(l1).has("call_graph"), "L1 must not carry call_graph");
        assertFalse(application(l1).has("external_symbols"), "L1 must not carry external_symbols");
        assertTrue(application(l2).has("call_graph"), "L2 adds call_graph");
        assertTrue(application(l2).has("external_symbols"), "L2 adds external_symbols");

        JsonElement l2Stripped = stripCallee(application(l2).getAsJsonObject("symbol_table").deepCopy());
        assertEquals(application(l1).getAsJsonObject("symbol_table"), l2Stripped,
                "with `callee` removed, the L2 tree must be identical to the L1 tree");
    }

    /** Remove every {@code callee} key, recursively — the one refinement L2 makes to the L1 tree. */
    private static JsonElement stripCallee(JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            object.remove("callee");
            for (String key : new ArrayList<>(object.keySet())) {
                stripCallee(object.get(key));
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                stripCallee(child);
            }
        }
        return element;
    }
}
