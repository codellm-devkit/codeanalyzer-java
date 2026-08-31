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
 * The oracle for the body-node ids L4 introduces, tested against hand-authored payloads rather than
 * through the producer — the sibling of {@link L3SchemaOracleTest}, one level up.
 *
 * <p>{@code SdgVertices} keys a call site's actuals off the site's own local id:
 * {@code <line:col>/actual_in:<i>} and {@code <line:col>/actual_out}. The formals it mints are
 * {@code @}-tagged and were already admitted; the actuals were not, so an {@code -a 4} payload
 * failed this repository's own conformance gate (#207). The pattern enumerates those two forms
 * instead of loosening its character class, so the rejections below are the point of the fix: a
 * pattern widened to accept anything with a slash in it would gate nothing.
 */
class L4SchemaOracleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A level-4 payload whose single callable carries one body node under the given local id. */
    private static String payload(String localId, String kind) {
        String callable = "{"
                + "\"id\":\"can://java/myapp/Foo.java/Foo/m()\","
                + "\"kind\":\"method\","
                + "\"signature\":\"m()\","
                + "\"body\":{\"" + localId + "\":{\"kind\":\"" + kind + "\"}}"
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
                + "\"max_level\":4,"
                + "\"application\":{"
                + "\"id\":\"can://java/myapp\","
                + "\"kind\":\"application\","
                + "\"symbol_table\":{\"Foo.java\":" + module + "}}}";
    }

    private static Set<ValidationMessage> validate(String json) throws IOException {
        try (InputStream in = L4SchemaOracleTest.class.getResourceAsStream("/schema/analysis.v2.schema.json")) {
            assertNotNull(in, "the canonical v2 schema must be on the test classpath");
            JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in);
            return schema.validate(MAPPER.readTree(json));
        }
    }

    private static void assertAccepted(String localId, String kind) throws IOException {
        Set<ValidationMessage> problems = validate(payload(localId, kind));
        assertTrue(problems.isEmpty(), "expected the oracle to accept the local id " + localId + ", but got:\n  "
                + problems.stream().map(ValidationMessage::getMessage).collect(Collectors.joining("\n  ")));
    }

    private static void assertRejected(String localId, String because) throws IOException {
        assertFalse(validate(payload(localId, "statement")).isEmpty(),
                "the oracle must reject the local id " + localId + ": " + because);
    }

    @Test
    void anActualInVertexKeyedOffItsCallSiteValidates() throws IOException {
        assertAccepted("3:39/actual_in:0", "actual_in");
    }

    @Test
    void anActualOutVertexKeyedOffItsCallSiteValidates() throws IOException {
        assertAccepted("3:39/actual_out", "actual_out");
    }

    @Test
    void theFormalVerticesStillValidate() throws IOException {
        assertAccepted("@formal_in:0", "formal_in");
        assertAccepted("@formal_out", "formal_out");
    }

    @Test
    void aPlainPositionStillValidates() throws IOException {
        assertAccepted("3:39", "call");
    }

    @Test
    void anActualInWithoutItsIndexIsRejected() throws IOException {
        // SdgVertices always numbers an actual_in by the parameter it feeds.
        assertRejected("3:39/actual_in", "actual_in carries the argument's ordinal");
    }

    @Test
    void anActualOutCarryingAnIndexIsRejected() throws IOException {
        // A call site returns at most one value, so there is no ordinal to carry.
        assertRejected("3:39/actual_out:0", "actual_out is unindexed");
    }

    @Test
    void anUnknownSuffixIsRejected() throws IOException {
        assertRejected("3:39/actual_side", "only the two forms SdgVertices mints are admitted");
    }

    @Test
    void aCompoundIdWhoseBaseIsNotAPositionIsRejected() throws IOException {
        assertRejected("entry/actual_out", "the base of a compound id is the call site's line:col");
    }

    @Test
    void aBareSlashSuffixIsRejected() throws IOException {
        assertRejected("3:39/", "a trailing slash names no vertex");
    }
}
