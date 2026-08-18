package com.ibm.cldk.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Tests for canonical schema v2 {@code can://} id construction (see the design spec,
 * decision D8: {@code can://java/<app>/<file>/<type>/<signature>} with {@code @<tag>} ordinals).
 */
class CanIdTest {

    @Test
    void applicationId_buildsCanJavaScheme() {
        assertEquals("can://java/myapp", CanId.applicationId("myapp"));
    }

    @Test
    void moduleId_appendsRelativeFileKey() {
        assertEquals(
                "can://java/myapp/src/main/java/Foo.java",
                CanId.moduleId("can://java/myapp", "src/main/java/Foo.java"));
    }

    @Test
    void moduleId_normalizesBackslashesAndLeadingDotSlash() {
        assertEquals(
                "can://java/myapp/a/b/C.java",
                CanId.moduleId("can://java/myapp", "./a\\b\\C.java"));
    }

    @Test
    void childId_appendsSegmentWithSlash() {
        assertEquals(
                "can://java/myapp/src/Foo.java/com.example.Foo",
                CanId.childId("can://java/myapp/src/Foo.java", "com.example.Foo"));
    }

    @Test
    void ordinalId_appendsTagAfterAt() {
        String callableId = "can://java/myapp/src/Foo.java/com.example.Foo/bar(int)";
        assertEquals(callableId + "@15:2", CanId.ordinalId(callableId, "15:2"));
        assertEquals(callableId + "@entry", CanId.ordinalId(callableId, "entry"));
    }
}
