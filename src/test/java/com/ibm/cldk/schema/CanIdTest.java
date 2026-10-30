package com.ibm.cldk.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for canonical schema v2 {@code can://} id construction (see the design spec,
 * decision D8: {@code can://<app>/java/<file>/<type>/<signature>} with {@code @<tag>} ordinals,
 * app outermost).
 */
class CanIdTest {

    @Test
    void namespaceFixtureAndInvalidNames() throws Exception {
        var fixture = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(
                getClass().getResourceAsStream("/identity/namespaces.json"), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        for (var row : fixture.getAsJsonArray("roots")) {
            var r = row.getAsJsonObject();
            String ns = r.get("namespace").isJsonNull() ? null : r.get("namespace").getAsString();
            assertEquals(r.get("expected_root").getAsString(), CanId.applicationId(r.get("application_name").getAsString(), ns));
        }
        for (var row : fixture.getAsJsonArray("invalid")) {
            String value = row.getAsJsonObject().get("value").getAsString();
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> CanId.applicationId(value));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> CanId.applicationId("app", value));
        }
        assertEquals("can://team/%21%27%28%29%2A~", CanId.applicationId("!'()*~", "team"));
        for (String value : List.of("\u00a0", "\u2007", "\u202f", "\u0085")) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> CanId.applicationId(value));
        }
        assertEquals("can://team/%F0%9F%98%80", CanId.applicationId("😀", "team"));
    }

    @Test
    void applicationIdPutsTheAppOutermost() {
        assertEquals("can://daytrader8/daytrader8", CanId.applicationId("daytrader8"));
    }

    @Test
    void moduleIdNestsTheLanguageUnderTheApp() {
        String app = CanId.applicationId("daytrader8");
        assertEquals("can://daytrader8/daytrader8/java/src/Foo.java", CanId.moduleId(app, "src/Foo.java"));
    }

    @Test
    void moduleId_normalizesBackslashesAndLeadingDotSlash() {
        String app = CanId.applicationId("myapp");
        assertEquals(
                "can://myapp/myapp/java/a/b/C.java", CanId.moduleId(app, "./a\\b\\C.java"));
    }

    @Test
    void moduleId_keepsARepoRelativeKeyWhole() {
        // --workspace-root keys carry the path from the repository root; the leading-[./] strip must
        // only drop a `./`, never eat into the key's own first segment.
        String app = CanId.applicationId("myapp");
        assertEquals("can://myapp/myapp/java/services/app/src/Foo.java",
                CanId.moduleId(app, "services/app/src/Foo.java"));
        assertEquals("can://myapp/myapp/java/services/app/src/Foo.java",
                CanId.moduleId(app, "./services/app/src/Foo.java"));
    }

    @Test
    void childId_appendsSegmentWithSlash() {
        assertEquals(
                "can://myapp/myapp/java/src/Foo.java/com.example.Foo",
                CanId.childId("can://myapp/myapp/java/src/Foo.java", "com.example.Foo"));
    }

    @Test
    void ordinalId_appendsTagAfterAt() {
        String callableId = "can://myapp/myapp/java/src/Foo.java/com.example.Foo/bar(int)";
        assertEquals(callableId + "@15:2", CanId.ordinalId(callableId, "15:2"));
        assertEquals(callableId + "@entry", CanId.ordinalId(callableId, "entry"));
    }

    @Test
    void externalIdIsLanguageNeutralUnderTheApp() {
        // No `/java/` segment: @external sits where the language sits for code nodes, so a sibling
        // analyzer over the same app mints the same id and merges onto the same node (#244).
        assertEquals(
                "can://daytrader8/daytrader8/@external/java.util.Map/get(java.lang.Object)",
                CanId.externalId("daytrader8", "java.util.Map", "get(java.lang.Object)"));
    }

    @Test
    void artifactIdNestsUnderTheAppInsteadOfAParallelScheme() {
        // Was can://artifact/<app>/<path> — a third outermost shape. Now one rule.
        assertEquals("can://daytrader8/daytrader8/artifact/pom.xml", CanId.artifactId("daytrader8", "pom.xml"));
    }

    @Test
    void everyIdSharesTheApplicationPrefix() {
        // This is what makes the prefix-scoped delete correct, so assert it directly.
        String app = CanId.applicationId("daytrader8");
        for (String id :
                List.of(
                        CanId.moduleId(app, "src/Foo.java"),
                        CanId.externalId("daytrader8", "java.util.Map", "get(java.lang.Object)"),
                        CanId.artifactId("daytrader8", "pom.xml"))) {
            assertTrue(id.startsWith(app + "/"), id + " must sit under " + app);
        }
    }
}
