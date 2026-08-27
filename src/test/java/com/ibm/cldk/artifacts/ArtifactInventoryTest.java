package com.ibm.cldk.artifacts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.cldk.schema.JArtifact;
import com.ibm.cldk.schema.JConfigKey;
import com.ibm.cldk.schema.JDependency;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the repository-artifact inventory: the repo-wide walk, classification, dependency
 * and config-key overlays, binary handling, the text-capture toggle, and the byte cap. Assertions pin
 * concrete values, not just shapes — a silently mis-classified file or a dropped dependency must fail
 * here.
 */
class ArtifactInventoryTest {

    private static final Path FIXTURE =
            Paths.get("src/test/resources/test-applications/artifact-inventory-test");
    private static final String APP = "artifact-inventory-test";

    private static Map<String, JArtifact> inventory(boolean captureText, int cap) {
        return ArtifactInventory.inventory(FIXTURE, APP, captureText, cap);
    }

    private static Map<String, JArtifact> inventory() {
        return inventory(true, ArtifactInventory.DEFAULT_ARTIFACT_TEXT_MAX_BYTES);
    }

    @Test
    void inventoryKeysAreRepoRelativePathsAndSourceFilesAreExcluded() {
        Map<String, JArtifact> artifacts = inventory();

        // The non-source files are present, keyed by repo-relative path.
        assertTrue(artifacts.containsKey("pom.xml"));
        assertTrue(artifacts.containsKey("Dockerfile"));
        assertTrue(artifacts.containsKey("README.md"));
        assertTrue(artifacts.containsKey("k8s/deployment.yaml"));
        assertTrue(artifacts.containsKey(".github/workflows/ci.yml"));
        assertTrue(artifacts.containsKey("src/main/resources/application.properties"));
        assertTrue(artifacts.containsKey("src/main/resources/application.yml"));

        // Source files belong to the symbol table, not the inventory.
        assertFalse(artifacts.containsKey("src/main/java/com/example/App.java"),
                ".java files must not be inventoried");

        // build/ is pruned wholesale, so a config-looking file under it never appears.
        assertFalse(artifacts.containsKey("build/generated/generated.properties"),
                "build/ output must be pruned from the walk");
    }

    @Test
    void classificationIsByKindAndFormat() {
        Map<String, JArtifact> artifacts = inventory();

        assertKindFormat(artifacts.get("pom.xml"), "build_manifest", "xml");
        assertKindFormat(artifacts.get("Dockerfile"), "container", "dockerfile");
        assertKindFormat(artifacts.get("README.md"), "documentation", "markdown");
        assertKindFormat(artifacts.get("k8s/deployment.yaml"), "deployment_manifest", "yaml");
        assertKindFormat(artifacts.get(".github/workflows/ci.yml"), "ci", "yaml");
        assertKindFormat(artifacts.get("src/main/resources/application.properties"),
                "configuration", "properties");
        assertKindFormat(artifacts.get("src/main/resources/application.yml"),
                "configuration", "yaml");
        assertKindFormat(artifacts.get("logo.png"), "other", "png");
    }

    @Test
    void artifactCarriesIdHashSizeAndText() {
        JArtifact pom = inventory().get("pom.xml");
        assertEquals("can://java/" + APP + "/@artifact/pom.xml", pom.getId());
        assertEquals("pom.xml", pom.getPath());
        assertTrue(pom.getSizeBytes() > 0);
        assertTrue(pom.getContentHash().matches("[0-9a-f]{64}"), "content_hash is a SHA-256 hex digest");
        assertEquals("utf-8", pom.getTextEncoding());
        assertNotNull(pom.getText());
        assertTrue(pom.getText().contains("<artifactId>spring-core</artifactId>"));
        assertFalse(pom.isTextTruncated());
    }

    @Test
    void mavenDependenciesCarryScopeAndVersion() {
        Map<String, JDependency> deps = inventory().get("pom.xml").getDependencies();
        assertNotNull(deps, "the manifest must carry its declared dependencies");

        JDependency spring = deps.get("org.springframework:spring-core");
        assertNotNull(spring, "group is never dropped from the native name");
        assertEquals("maven", spring.getEcosystem());
        assertEquals("runtime", spring.getScope(), "Maven compile-scope maps to runtime");
        assertEquals("6.1.4", spring.getVersionSpec());
        assertEquals("6.1.4", spring.getResolvedVersion(), "a literal version is also resolved");
        assertTrue(spring.isDirect());
        assertEquals("can://java/" + APP + "/@artifact/pom.xml/org.springframework:spring-core",
                spring.getId());

        assertEquals("development", deps.get("org.projectlombok:lombok").getScope(),
                "Maven provided-scope maps to development");
        assertEquals("test", deps.get("org.junit.jupiter:junit-jupiter").getScope());

        // A ${property} version is kept as the spec but left unresolved.
        JDependency propref = deps.get("com.acme:propref");
        assertEquals("${spring.version}", propref.getVersionSpec());
        assertNull(propref.getResolvedVersion(), "a property-reference version is not resolved");
    }

    @Test
    void configKeysAreFlattenedToDottedKeysWithReferences() {
        JArtifact props = inventory().get("src/main/resources/application.properties");
        Map<String, JConfigKey> keys = props.getConfigKeys();
        assertNotNull(keys);

        JConfigKey url = keys.get("spring.datasource.url");
        assertNotNull(url);
        assertEquals("spring", url.getNamespace());
        assertEquals("jdbc:postgresql://db:5432/app", url.getValue());
        assertTrue(url.getReferences().isEmpty());

        JConfigKey host = keys.get("payment.host");
        assertEquals("env:PAYMENT_HOST", host.getReferences().get(0),
                "a ${PLACEHOLDER} value is preserved as a reference");

        JConfigKey port = keys.get("payment.port");
        assertEquals("env:PAYMENT_PORT", port.getReferences().get(0),
                "a ${REF:default} reference drops the default suffix");
    }

    @Test
    void yamlConfigKeysFlattenNestingAndListIndices() {
        JArtifact yaml = inventory().get("src/main/resources/application.yml");
        Map<String, JConfigKey> keys = yaml.getConfigKeys();
        assertNotNull(keys);

        assertEquals(8080, keys.get("server.port").getValue());
        assertEquals("artifact-inventory-test", keys.get("spring.application.name").getValue());
        assertEquals("env:DB_URL", keys.get("spring.datasource.url").getReferences().get(0));
        // A YAML list flattens by ordinal index.
        assertEquals("payments", keys.get("services[0].name").getValue());
        assertEquals("http://orders:9001", keys.get("services[1].url").getValue());
    }

    @Test
    void binaryFileIsInventoriedWithoutText() {
        JArtifact binary = inventory().get("logo.png");
        assertNotNull(binary);
        assertTrue(binary.getContentHash().matches("[0-9a-f]{64}"));
        assertTrue(binary.getSizeBytes() > 0);
        assertNull(binary.getText(), "undecodable bytes are inventoried without text");
        assertNull(binary.getTextEncoding());
    }

    @Test
    void noArtifactTextSuppressesTextButKeepsInventoryAndOverlays() {
        Map<String, JArtifact> artifacts = inventory(false, ArtifactInventory.DEFAULT_ARTIFACT_TEXT_MAX_BYTES);
        JArtifact pom = artifacts.get("pom.xml");
        assertNull(pom.getText(), "--no-artifact-text drops the text payload");
        assertNull(pom.getTextEncoding());
        // Inventory facts and parsed overlays survive without text.
        assertTrue(pom.getContentHash().matches("[0-9a-f]{64}"));
        assertNotNull(pom.getDependencies(), "dependencies are parsed even with text capture off");
        assertNotNull(artifacts.get("src/main/resources/application.properties").getConfigKeys());
    }

    @Test
    void oversizedFileIsTruncatedWithLeadingPrefix() {
        // A cap below the README's size forces truncation.
        long readmeSize = inventory().get("README.md").getSizeBytes();
        int cap = (int) (readmeSize / 2);
        JArtifact readme = inventory(true, cap).get("README.md");
        assertTrue(readme.isTextTruncated(), "a file past the cap is truncated");
        assertNotNull(readme.getText());
        assertTrue(readme.getText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= cap,
                "captured text is at most the cap");
        // path/hash/size still describe the whole file, not the prefix.
        assertEquals(readmeSize, readme.getSizeBytes());
    }

    private static void assertKindFormat(JArtifact artifact, String kind, String format) {
        assertNotNull(artifact, "expected an artifact for kind " + kind);
        assertEquals(kind, artifact.getArtifactKind());
        assertEquals(format, artifact.getFormat());
    }
}
