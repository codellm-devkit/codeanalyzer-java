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
 * The oracle for L3's three per-callable overlays (cfg/cdg/ddg), tested directly against
 * hand-authored payloads rather than through the producer.
 *
 * <p>Written before the producer exists, deliberately: the loosely-typed {@code {"type":"array"}}
 * these arrays started as accepts every malformation, so each rejection here is a way a plausible
 * L3 payload could be wrong — a cfg edge kind that is not in the closed set, an endpoint that is a
 * {@code can://} id rather than a body-node local id, a ddg edge with no {@code var} or an empty
 * {@code prov} — and the tightened schema has to say so.
 */
class L3SchemaOracleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A level-3 payload with one callable carrying the given cfg/cdg/ddg member JSON. */
    private static String payload(String overlays) {
        String callable = "{"
                + "\"id\":\"can://java/myapp/Foo.java/Foo/m()\","
                + "\"kind\":\"method\","
                + "\"signature\":\"m()\","
                + overlays
                + "}";
        String type = "{"
                + "\"id\":\"can://java/myapp/Foo.java/Foo\","
                + "\"kind\":\"class\","
                + "\"callables\":{\"m()\":" + callable + "}}";
        String module = "{"
                + "\"id\":\"can://java/myapp/Foo.java\","
                + "\"kind\":\"module\","
                + "\"source\":\"\","
                + "\"types\":{\"Foo\":" + type + "}}";
        return "{"
                + "\"schema_version\":\"2.0.0\","
                + "\"language\":\"java\","
                + "\"max_level\":3,"
                + "\"application\":{"
                + "\"id\":\"can://java/myapp\","
                + "\"kind\":\"application\","
                + "\"symbol_table\":{\"Foo.java\":" + module + "}}}";
    }

    private static Set<ValidationMessage> validate(String json) throws IOException {
        try (InputStream in = L3SchemaOracleTest.class.getResourceAsStream("/schema/analysis.v2.schema.json")) {
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

    private static final String CFG = "\"cfg\":[{\"src\":\"@entry\",\"dst\":\"2:5\",\"kind\":\"fallthrough\"}]";
    private static final String CDG = "\"cdg\":[{\"src\":\"2:5\",\"dst\":\"3:7\"}]";
    private static final String DDG =
            "\"ddg\":[{\"src\":\"2:5\",\"dst\":\"3:7\",\"var\":\"x\",\"prov\":[\"ssa\"]}]";

    @Test
    void aLevel3PayloadCarryingAllThreeOverlaysValidates() throws IOException {
        assertAccepted(payload(CFG + "," + CDG + "," + DDG));
    }

    @Test
    void aCallableCarryingNoOverlaysStillValidates() throws IOException {
        // Backward compatibility: a callable below L3 (no cfg/cdg/ddg) must still validate.
        assertAccepted(payload("\"cfg\":[]"));
    }

    @Test
    void aCfgEdgeWithAnUnknownKindIsRejected() throws IOException {
        assertRejected(payload("\"cfg\":[{\"src\":\"@entry\",\"dst\":\"2:5\",\"kind\":\"goto\"}]"),
                "cfg kind is a closed enum");
    }

    @Test
    void aCfgEdgeWithACanIdEndpointIsRejected() throws IOException {
        assertRejected(payload("\"cfg\":[{\"src\":\"can://java/x\",\"dst\":\"2:5\",\"kind\":\"true\"}]"),
                "cfg endpoints are body-node local ids, not can:// ids");
    }

    @Test
    void aCfgEdgeMissingItsKindIsRejected() throws IOException {
        assertRejected(payload("\"cfg\":[{\"src\":\"@entry\",\"dst\":\"2:5\"}]"), "kind is required on a cfg edge");
    }

    @Test
    void aDdgEdgeMissingItsVarIsRejected() throws IOException {
        assertRejected(payload("\"ddg\":[{\"src\":\"2:5\",\"dst\":\"3:7\",\"prov\":[\"ssa\"]}]"),
                "var is required on a ddg edge");
    }

    @Test
    void aDdgEdgeWithEmptyProvenanceIsRejected() throws IOException {
        assertRejected(payload("\"ddg\":[{\"src\":\"2:5\",\"dst\":\"3:7\",\"var\":\"x\",\"prov\":[]}]"),
                "ddg prov has minItems 1");
    }

    @Test
    void aDdgEdgeWithANonSsaProvenanceIsRejected() throws IOException {
        assertRejected(payload("\"ddg\":[{\"src\":\"2:5\",\"dst\":\"3:7\",\"var\":\"x\",\"prov\":[\"points-to\"]}]"),
                "L3 prov is the closed enum [ssa]; points-to is an L4 tier");
    }

    @Test
    void aCfgEdgeCarryingAStrayKeyIsRejected() throws IOException {
        assertRejected(payload("\"cfg\":[{\"src\":\"@entry\",\"dst\":\"2:5\",\"kind\":\"true\",\"extra\":1}]"),
                "cfgEdge has additionalProperties:false");
    }
}
