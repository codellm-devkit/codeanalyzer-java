package com.ibm.cldk.schema;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The oracle for L2's two new application-scope surfaces, tested directly against hand-authored
 * payloads rather than through the producer.
 *
 * <p>Written before the producer exists, deliberately: an oracle that only ever sees output the
 * producer already generates cannot fail, so it would never catch the producer drifting. Each
 * rejection case here is a way a plausible-looking L2 payload could be wrong — an edge with no
 * weight, a provenance label no L2 analysis attests, an in-project id filed under the external
 * map — and the gate has to say so.
 */
class L2SchemaOracleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Wrap application-level members in an otherwise-minimal conformant envelope. */
    private static String payload(String applicationMembers) {
        return "{"
                + "\"schema_version\":\"2.0.0\","
                + "\"language\":\"java\","
                + "\"max_level\":2,"
                + "\"application\":{"
                + "\"id\":\"can://java/myapp\","
                + "\"kind\":\"application\","
                + "\"symbol_table\":{}"
                + (applicationMembers.isEmpty() ? "" : "," + applicationMembers)
                + "}}";
    }

    private static Set<ValidationMessage> validate(String json) throws IOException {
        try (InputStream in = L2SchemaOracleTest.class.getResourceAsStream("/schema/analysis.v2.schema.json")) {
            assertNotNull(in, "the canonical v2 schema must be on the test classpath");
            JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in);
            return schema.validate(MAPPER.readTree(json));
        }
    }

    private static void assertAccepted(String json) throws IOException {
        Set<ValidationMessage> problems = validate(json);
        assertTrue(problems.isEmpty(), "expected the oracle to accept this payload, but got:\n  "
                + problems.stream().map(ValidationMessage::getMessage).collect(Collectors.joining("\n  ")));
    }

    private static void assertRejected(String json, String because) throws IOException {
        assertFalse(validate(json).isEmpty(), "the oracle must reject this payload: " + because);
    }

    private static final String EDGE = "{"
            + "\"src\":\"can://java/myapp/src/main/java/org/example/User.java/User/helloString()\","
            + "\"dst\":\"can://java/myapp/src/main/java/org/example/User.java/User/log()\","
            + "\"prov\":[\"declared\",\"rta\"],"
            + "\"weight\":2}";

    private static final String EXTERNAL = "\"can://java/myapp/@external/java.lang.String/valueOf(int)\":{"
            + "\"kind\":\"method\","
            + "\"signature\":\"valueOf(int)\","
            + "\"declaring_type\":\"java.lang.String\"}";

    @Test
    void anL2PayloadCarryingBothNewSurfacesValidates() throws IOException {
        assertAccepted(payload("\"call_graph\":[" + EDGE + "],\"external_symbols\":{" + EXTERNAL + "}"));
    }

    @Test
    void anL1PayloadCarryingNeitherSurfaceStillValidates() throws IOException {
        // The levels are additive: L1 output omits both keys, and absence means "no fact", not "empty".
        assertAccepted(payload(""));
    }

    @Test
    void anEdgeMissingItsWeightIsRejected() throws IOException {
        assertRejected(payload("\"call_graph\":[{"
                        + "\"src\":\"can://java/myapp/A.java/A/f()\","
                        + "\"dst\":\"can://java/myapp/A.java/A/g()\","
                        + "\"prov\":[\"declared\"]}]"),
                "weight is the call-site count, so an edge without one asserts nothing about strength");
    }

    @Test
    void aZeroWeightEdgeIsRejected() throws IOException {
        assertRejected(payload("\"call_graph\":[{"
                        + "\"src\":\"can://java/myapp/A.java/A/f()\","
                        + "\"dst\":\"can://java/myapp/A.java/A/g()\","
                        + "\"prov\":[\"declared\"],\"weight\":0}]"),
                "an edge with no call sites behind it should not have been emitted at all");
    }

    @Test
    void anEdgeWithNoProvenanceIsRejected() throws IOException {
        assertRejected(payload("\"call_graph\":[{"
                        + "\"src\":\"can://java/myapp/A.java/A/f()\","
                        + "\"dst\":\"can://java/myapp/A.java/A/g()\","
                        + "\"prov\":[],\"weight\":1}]"),
                "every edge is attested by at least one analysis");
    }

    @Test
    void anEdgeProvenanceOutsideTheL2VocabularyIsRejected() throws IOException {
        // `ast` is reserved for a future purely-syntactic fallback. Until something produces it, the
        // oracle accepting it would let a typo through as a new analysis name.
        assertRejected(payload("\"call_graph\":[{"
                        + "\"src\":\"can://java/myapp/A.java/A/f()\","
                        + "\"dst\":\"can://java/myapp/A.java/A/g()\","
                        + "\"prov\":[\"ast\"],\"weight\":1}]"),
                "L2 provenance is the closed enum [declared, rta]");
    }

    @Test
    void anEdgeEndpointThatIsNotACanIdIsRejected() throws IOException {
        assertRejected(payload("\"call_graph\":[{"
                        + "\"src\":\"org.example.A.f()\","
                        + "\"dst\":\"can://java/myapp/A.java/A/g()\","
                        + "\"prov\":[\"declared\"],\"weight\":1}]"),
                "endpoints are durable can-ids, not display names");
    }

    @Test
    void anEdgeCarryingAnUnknownKeyIsRejected() throws IOException {
        assertRejected(payload("\"call_graph\":[{"
                        + "\"src\":\"can://java/myapp/A.java/A/f()\","
                        + "\"dst\":\"can://java/myapp/A.java/A/g()\","
                        + "\"prov\":[\"declared\"],\"weight\":1,\"line\":12}]"),
                "a stray key means a producer emitting a fact no consumer was told about");
    }

    @Test
    void anInProjectIdKeyingTheExternalMapIsRejected() throws IOException {
        // The overlay rule: external_symbols may only ever describe symbols outside the project. An
        // in-project id here would be a type the tree should have held, silently reclassified.
        assertRejected(payload("\"external_symbols\":{"
                        + "\"can://java/myapp/src/main/java/org/example/User.java/User/log()\":{"
                        + "\"kind\":\"method\",\"signature\":\"log()\",\"declaring_type\":\"org.example.User\"}}"),
                "an in-project can-id is not an external symbol");
    }

    @Test
    void anExternalSymbolMissingItsDeclaringTypeIsRejected() throws IOException {
        assertRejected(payload("\"external_symbols\":{"
                        + "\"can://java/myapp/@external/java.lang.String/valueOf(int)\":{"
                        + "\"kind\":\"method\",\"signature\":\"valueOf(int)\"}}"),
                "declaring_type is the dotted source spelling the id only carries in binary form");
    }

    @Test
    void anExternalSymbolOfAnUnmodelledKindIsRejected() throws IOException {
        // Only call sites mint external symbols, and a call site resolves to a method or a constructor.
        assertRejected(payload("\"external_symbols\":{"
                        + "\"can://java/myapp/@external/java.lang.String/CASE_INSENSITIVE_ORDER\":{"
                        + "\"kind\":\"field\",\"signature\":\"CASE_INSENSITIVE_ORDER\","
                        + "\"declaring_type\":\"java.lang.String\"}}"),
                "external symbol kinds are exactly [method, constructor]");
    }

    @Test
    void anExternalSymbolCarryingAnUnknownKeyIsRejected() throws IOException {
        assertRejected(payload("\"external_symbols\":{"
                        + "\"can://java/myapp/@external/java.lang.String/valueOf(int)\":{"
                        + "\"kind\":\"method\",\"signature\":\"valueOf(int)\","
                        + "\"declaring_type\":\"java.lang.String\",\"jar\":\"rt.jar\"}}"),
                "a stray key means a producer emitting a fact no consumer was told about");
    }
}
