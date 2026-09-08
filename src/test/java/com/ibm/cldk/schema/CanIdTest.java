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
    void applicationIdPutsTheAppOutermost() {
        assertEquals("can://daytrader8", CanId.applicationId("daytrader8"));
    }

    @Test
    void moduleIdNestsTheLanguageUnderTheApp() {
        String app = CanId.applicationId("daytrader8");
        assertEquals("can://daytrader8/java/src/Foo.java", CanId.moduleId(app, "src/Foo.java"));
    }

    @Test
    void moduleId_normalizesBackslashesAndLeadingDotSlash() {
        String app = CanId.applicationId("myapp");
        assertEquals(
                "can://myapp/java/a/b/C.java", CanId.moduleId(app, "./a\\b\\C.java"));
    }

    @Test
    void childId_appendsSegmentWithSlash() {
        assertEquals(
                "can://myapp/java/src/Foo.java/com.example.Foo",
                CanId.childId("can://myapp/java/src/Foo.java", "com.example.Foo"));
    }

    @Test
    void ordinalId_appendsTagAfterAt() {
        String callableId = "can://myapp/java/src/Foo.java/com.example.Foo/bar(int)";
        assertEquals(callableId + "@15:2", CanId.ordinalId(callableId, "15:2"));
        assertEquals(callableId + "@entry", CanId.ordinalId(callableId, "entry"));
    }

    @Test
    void externalIdIsLanguageNeutralUnderTheApp() {
        // No `/java/` segment: @external sits where the language sits for code nodes, so a sibling
        // analyzer over the same app mints the same id and merges onto the same node (#244).
        assertEquals(
                "can://daytrader8/@external/java.util.Map/get(java.lang.Object)",
                CanId.externalId("daytrader8", "java.util.Map", "get(java.lang.Object)"));
    }

    @Test
    void artifactIdNestsUnderTheAppInsteadOfAParallelScheme() {
        // Was can://artifact/<app>/<path> — a third outermost shape. Now one rule.
        assertEquals("can://daytrader8/artifact/pom.xml", CanId.artifactId("daytrader8", "pom.xml"));
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
