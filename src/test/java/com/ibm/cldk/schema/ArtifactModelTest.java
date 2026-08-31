package com.ibm.cldk.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The artifact layer's wire shape and id grammar, matching codeanalyzer-python v1.3.0. */
class ArtifactModelTest {

    @Test
    void artifactIdIsLanguageNeutral() {
        assertEquals("can://artifact/myapp/deploy/docker-compose.yml",
                CanId.artifactId("myapp", "deploy/docker-compose.yml"),
                "the scheme carries `artifact`, not `java` — sibling analyzers must land on this node");
    }

    @Test
    void configKeyIdNestsUnderItsArtifact() {
        String art = CanId.artifactId("myapp", "src/main/resources/application.yml");
        assertEquals(art + "@key/server.port", CanId.configKeyId(art, "server.port"));
    }

    @Test
    void configKeyEnvDualMintIdNeverCollidesWithConfigKeyId() {
        String art = CanId.artifactId("myapp", "docker-compose.yml");
        // A real yaml document can flatten a top-level "env:" block one level deep into exactly
        // the dotted key "env.DB_HOST" -- the same text a naive "env." prefix through configKeyId
        // would build for a compose-recognized bare "DB_HOST" var. configKeyEnvDualMintId must use
        // a different delimiter so the two can never collide, for any key/var name whatsoever.
        String plainYamlId = CanId.configKeyId(art, "env.DB_HOST");
        String dualMintId = CanId.configKeyEnvDualMintId(art, "DB_HOST");

        assertEquals(art + "@key/env.DB_HOST", plainYamlId);
        assertEquals(art + "@key:env/DB_HOST", dualMintId);
        assertNotEquals(plainYamlId, dualMintId,
                "the two share \"<artifactId>@key\" and must diverge right after it (':' vs '/'), before "
                        + "either consumes any key content, so no dotted key or bare variable name can equalize them");
    }

    @Test
    void purlIsTwoSegmentForMaven() {
        assertEquals("pkg:maven/org.apache.commons/commons-lang3",
                CanId.purlMaven("org.apache.commons", "commons-lang3"));
    }

    @Test
    void unsetOptionalsAreOmittedNotNulled() {
        JDependency d = new JDependency();
        d.setGroup("org.example");
        d.setName("widget");
        String json = V2Json.compact().toJson(d);
        assertFalse(json.contains("locked_version"), "an unpinned dependency omits the key: " + json);
        assertTrue(json.contains("\"ecosystem\":\"maven\""), json);
        assertTrue(json.contains("\"direct\":true"), json);
    }

    @Test
    void applicationOmitsTheLayerWhenEmpty() {
        JApplication app = new JApplication();
        app.setId("can://java/x");
        String json = V2Json.compact().toJson(app);
        assertFalse(json.contains("artifacts"), json);
        assertFalse(json.contains("dependencies"), json);
    }

    @Test
    void configKeysNestInsideTheirArtifact() {
        JArtifact a = new JArtifact();
        a.setPath("application.properties");
        a.setFormat("properties");
        JConfigKey k = new JConfigKey();
        k.setKey("server.port");
        k.setNamespace("properties");
        a.getConfigKeys().add(k);
        String json = V2Json.compact().toJson(a);
        assertTrue(json.contains("\"config_keys\""), json);
        assertTrue(json.contains("\"server.port\""), json);
    }
}
