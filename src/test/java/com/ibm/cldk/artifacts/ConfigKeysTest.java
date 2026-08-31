package com.ibm.cldk.artifacts;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.cldk.schema.CanId;
import com.ibm.cldk.schema.JArtifact;
import com.ibm.cldk.schema.JConfigKey;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Behavioural tests for {@link ConfigKeys}: one flattening case per eligible format, the
 * dockerfile ARG/ENV id-collision guard, the {@code ${...}}-before-{@code $VAR} reference
 * masking, the {@code captureValue} gate, and the {@code isEligible} admit list (including the
 * two precedence rules python documents: binary wins over an env-family name, and env-family
 * basenames are admitted regardless of declared format).
 */
class ConfigKeysTest {

    private static JArtifact artifact(String path, String format) {
        JArtifact a = new JArtifact();
        a.setId(CanId.artifactId("app", path));
        a.setPath(path);
        a.setFormat(format);
        return a;
    }

    private static Optional<JConfigKey> find(List<JConfigKey> keys, String key) {
        return keys.stream().filter(k -> k.getKey().equals(key)).findFirst();
    }

    // ---- properties -------------------------------------------------------------------------

    @Test
    void extract_propertiesFileYieldsDottedKeysWithLastWins() {
        JArtifact art = artifact("application.properties", "properties");
        String text = "a.b.c=1\nfoo=bar\na.b.c=2\n";

        ConfigKeys.Result result = ConfigKeys.extract(art, text, true);

        assertTrue(result.ok);
        assertEquals(2, result.keys.size());
        JConfigKey abc = find(result.keys, "a.b.c").orElseThrow();
        assertEquals("2", abc.getValue(), "last occurrence wins");
        assertEquals("properties", abc.getNamespace());
        assertEquals(CanId.configKeyId(art.getId(), "a.b.c"), abc.getId());
        assertEquals("bar", find(result.keys, "foo").orElseThrow().getValue());
    }

    @Test
    void extract_propertiesColonSeparatorAndCommentsAreHandledNatively() {
        JArtifact art = artifact("application.properties", "properties");
        String text = "! a bang comment\n# a hash comment\nserver.port: 8080\n";

        ConfigKeys.Result result = ConfigKeys.extract(art, text, true);

        assertTrue(result.ok);
        assertEquals(1, result.keys.size());
        assertEquals("8080", result.keys.get(0).getValue());
    }

    // ---- yaml ---------------------------------------------------------------------------------

    @Test
    void extract_springApplicationYmlYieldsServerPortAndAListIndex() {
        JArtifact art = artifact("application.yml", "yaml");
        String text = "server:\n  port: 8080\nservers:\n  - host: a\n  - host: b\n";

        ConfigKeys.Result result = ConfigKeys.extract(art, text, true);

        assertTrue(result.ok);
        assertEquals("8080", find(result.keys, "server.port").orElseThrow().getValue());
        assertEquals("yaml", find(result.keys, "server.port").orElseThrow().getNamespace());
        assertEquals("a", find(result.keys, "servers.0.host").orElseThrow().getValue());
        assertEquals("b", find(result.keys, "servers.1.host").orElseThrow().getValue());
    }

    @Test
    void extract_yamlComposeEnvironmentBlockDualMintsEnvNamespaceWithDisambiguatedId() {
        JArtifact art = artifact("docker-compose.yml", "yaml");
        String text = "services:\n  web:\n    environment:\n      DB_HOST: localhost\n";

        ConfigKeys.Result result = ConfigKeys.extract(art, text, true);

        assertTrue(result.ok);
        // The plain yaml-namespace dotted path is still minted...
        JConfigKey yamlKey = find(result.keys, "services.web.environment.DB_HOST").orElseThrow();
        assertEquals("yaml", yamlKey.getNamespace());
        // ...alongside a dual-minted env-namespace key on the bare var name, id-disambiguated.
        List<JConfigKey> envKeys = result.keys.stream()
                .filter(k -> "env".equals(k.getNamespace()) && k.getKey().equals("DB_HOST"))
                .collect(java.util.stream.Collectors.toList());
        assertEquals(1, envKeys.size());
        assertEquals("localhost", envKeys.get(0).getValue());
        assertEquals(
                CanId.configKeyId(art.getId(), "env.DB_HOST"), envKeys.get(0).getId(),
                "env dual-mint id must be disambiguated so it cannot collide with a top-level yaml key of the same name");
    }

    @Test
    void extract_malformedYamlReturnsOkFalseAndDoesNotThrow() {
        JArtifact art = artifact("application.yml", "yaml");
        String text = "server: [unterminated\nother: value\n";

        ConfigKeys.Result result = assertDoesNotThrow(() -> ConfigKeys.extract(art, text, true));

        assertFalse(result.ok);
        assertTrue(result.keys.isEmpty());
    }

    // ---- xml ------------------------------------------------------------------------------

    @Test
    void extract_xmlDescriptorYieldsAnElementPath() {
        JArtifact art = artifact("web.xml", "xml");
        String text = "<config><server><port>8080</port></server></config>";

        ConfigKeys.Result result = ConfigKeys.extract(art, text, true);

        assertTrue(result.ok);
        JConfigKey key = find(result.keys, "config.server.port").orElseThrow();
        assertEquals("8080", key.getValue());
        assertEquals("xml", key.getNamespace());
    }

    @Test
    void extract_xmlAttributesFlattenAsPathAtAttrAndRepeatedSiblingsGetNumericSegments() {
        JArtifact art = artifact("beans.xml", "xml");
        String text = "<beans env=\"prod\">"
                + "<bean id=\"a\"/>"
                + "<bean id=\"b\"/>"
                + "</beans>";

        ConfigKeys.Result result = ConfigKeys.extract(art, text, true);

        assertTrue(result.ok);
        assertEquals("prod", find(result.keys, "beans@env").orElseThrow().getValue());
        assertEquals(
                "a", find(result.keys, "beans.bean.0@id").orElseThrow().getValue(),
                "a single occurrence would keep a clean path, but two <bean> siblings must be indexed");
        assertEquals("b", find(result.keys, "beans.bean.1@id").orElseThrow().getValue());
    }

    @Test
    void extract_malformedXmlReturnsOkFalseAndDoesNotThrow() {
        JArtifact art = artifact("web.xml", "xml");
        String text = "<config><server>";

        ConfigKeys.Result result = assertDoesNotThrow(() -> ConfigKeys.extract(art, text, true));

        assertFalse(result.ok);
        assertTrue(result.keys.isEmpty());
    }

    // ---- dockerfile -------------------------------------------------------------------------

    @Test
    void extract_dockerfileEnvGoesToEnvNamespaceAndArgGoesToDockerfileNamespaceWithoutIdCollision() {
        JArtifact art = artifact("Dockerfile", "dockerfile");
        String text = "ARG VERSION=1.0\nENV VERSION=$VERSION\n";

        ConfigKeys.Result result = ConfigKeys.extract(art, text, true);

        assertTrue(result.ok);
        List<JConfigKey> versionKeys = result.keys.stream()
                .filter(k -> k.getKey().equals("VERSION"))
                .collect(java.util.stream.Collectors.toList());
        assertEquals(2, versionKeys.size(), "same bare key name from both ENV and ARG");

        JConfigKey envKey = versionKeys.stream().filter(k -> "env".equals(k.getNamespace())).findFirst().orElseThrow();
        JConfigKey argKey =
                versionKeys.stream().filter(k -> "dockerfile".equals(k.getNamespace())).findFirst().orElseThrow();
        assertEquals(CanId.configKeyId(art.getId(), "VERSION"), envKey.getId());
        assertEquals(
                CanId.configKeyId(art.getId(), "arg.VERSION"), argKey.getId(),
                "ARG's id must be disambiguated with an arg. prefix so it cannot collide with ENV's plain id");
        assertEquals("$VERSION", envKey.getValue());
        assertEquals("1.0", argKey.getValue());
    }

    @Test
    void extract_dockerfileArgWithNoDefaultYieldsNullValueNotEmptyString() {
        JArtifact art = artifact("Dockerfile", "dockerfile");
        String text = "ARG BUILD_ID\n";

        ConfigKeys.Result result = ConfigKeys.extract(art, text, true);

        assertTrue(result.ok);
        JConfigKey key = find(result.keys, "BUILD_ID").orElseThrow();
        assertNull(key.getValue(), "an absent default is a distinct fact from an explicitly empty value");
    }

    @Test
    void extract_dockerfileLegacySpaceFormEnvIsParsedVerbatim() {
        JArtifact art = artifact("Dockerfile", "dockerfile");
        String text = "ENV NAME John Doe\n";

        ConfigKeys.Result result = ConfigKeys.extract(art, text, true);

        assertTrue(result.ok);
        assertEquals("John Doe", find(result.keys, "NAME").orElseThrow().getValue());
    }

    @Test
    void extract_dockerfileMultiKeyEnvLineYieldsBothKeys() {
        JArtifact art = artifact("Dockerfile", "dockerfile");
        String text = "ENV FOO=1 BAR=2\n";

        ConfigKeys.Result result = ConfigKeys.extract(art, text, true);

        assertTrue(result.ok);
        assertEquals("1", find(result.keys, "FOO").orElseThrow().getValue());
        assertEquals("2", find(result.keys, "BAR").orElseThrow().getValue());
    }

    // ---- env-family basenames (.env, .env.*) --------------------------------------------------

    @Test
    void extract_dotEnvFileYieldsEnvNamespaceRegardlessOfDeclaredFormat() {
        // ArtifactDiscovery assigns .env files format="text" -- extract must dispatch on the
        // basename, not the format, exactly like isEligible does.
        JArtifact art = artifact(".env", "text");
        String text = "# a comment\nexport DB_HOST=localhost\nDB_PORT=5432\n";

        ConfigKeys.Result result = ConfigKeys.extract(art, text, true);

        assertTrue(result.ok);
        assertEquals("localhost", find(result.keys, "DB_HOST").orElseThrow().getValue());
        assertEquals("env", find(result.keys, "DB_HOST").orElseThrow().getNamespace());
        assertEquals("5432", find(result.keys, "DB_PORT").orElseThrow().getValue());
    }

    // ---- references ------------------------------------------------------------------------

    @Test
    void extract_bracedReferenceIsMaskedSoBareFormInsideItIsNotDoubleCounted() {
        JArtifact art = artifact("application.properties", "properties");
        String text = "url=jdbc://${DB_HOST}/db?fallback=$DB_HOST\n";

        ConfigKeys.Result result = ConfigKeys.extract(art, text, true);

        JConfigKey key = find(result.keys, "url").orElseThrow();
        assertEquals(
                List.of("${DB_HOST}", "$DB_HOST"), key.getReferences(),
                "both distinct sigil forms are kept, in order, with no spurious extra entry");
    }

    @Test
    void extract_dottedBracedPlaceholderIsRecognized() {
        // Spring/Maven's dominant form is a DOTTED property path, unlike a shell env var.
        JArtifact art = artifact("application.properties", "properties");
        String text = "url=${spring.datasource.url}\n";

        ConfigKeys.Result result = ConfigKeys.extract(art, text, true);

        assertEquals(List.of("${spring.datasource.url}"), find(result.keys, "url").orElseThrow().getReferences());
    }

    // ---- captureValue ------------------------------------------------------------------------

    @Test
    void extract_captureValueFalseKeepsKeysAndReferencesButNullsValue() {
        JArtifact art = artifact("application.properties", "properties");
        String text = "url=${DB_HOST}\n";

        ConfigKeys.Result result = ConfigKeys.extract(art, text, false);

        assertTrue(result.ok);
        JConfigKey key = find(result.keys, "url").orElseThrow();
        assertNull(key.getValue());
        assertEquals("properties", key.getNamespace());
        assertEquals(
                List.of("${DB_HOST}"), key.getReferences(),
                "references are extracted from the raw value regardless of captureValue");
    }

    // ---- format with no flattener ------------------------------------------------------------

    @Test
    void extract_formatWithNoFlattenerReturnsEmptyAndOk() {
        JArtifact art = artifact("data.json", "json");

        ConfigKeys.Result result = ConfigKeys.extract(art, "{\"a\":1}", true);

        assertTrue(result.ok, "not applicable is not a failure");
        assertTrue(result.keys.isEmpty());
    }

    // ---- isEligible ------------------------------------------------------------------------

    @Test
    void isEligible_admitsExactlyPropertiesYamlXmlDockerfileAndEnvFamily() {
        assertTrue(ConfigKeys.isEligible(artifact("application.properties", "properties")));
        assertTrue(ConfigKeys.isEligible(artifact("application.yml", "yaml")));
        assertTrue(ConfigKeys.isEligible(artifact("web.xml", "xml")));
        assertTrue(ConfigKeys.isEligible(artifact("Dockerfile", "dockerfile")));
        assertTrue(ConfigKeys.isEligible(artifact(".env", "text")), "env-family basename, regardless of format");
        assertTrue(ConfigKeys.isEligible(artifact(".env.local", "text")));

        assertFalse(ConfigKeys.isEligible(artifact("build.gradle", "gradle")), "a build script is a program, not config");
        assertFalse(ConfigKeys.isEligible(artifact("data.json", "json")));
        assertFalse(ConfigKeys.isEligible(artifact("README.md", "text")));
    }

    @Test
    void isEligible_binaryIsNeverEligibleEvenWithAnEnvFamilyName() {
        // A rule-matched-but-undecodable file downgrades to format="binary" regardless of its
        // basename -- the binary check must win even over an env-family name.
        assertFalse(ConfigKeys.isEligible(artifact(".env", "binary")));
    }

    @Test
    void isEligible_doesNotThrowWhenFormatIsNull() {
        JArtifact art = new JArtifact();
        art.setPath("mystery-file");
        // format left null (never set)

        assertFalse(assertDoesNotThrow(() -> ConfigKeys.isEligible(art)));
    }
}
