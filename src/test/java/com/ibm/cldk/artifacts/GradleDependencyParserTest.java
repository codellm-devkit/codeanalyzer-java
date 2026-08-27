package com.ibm.cldk.artifacts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.cldk.schema.JArtifact;
import com.ibm.cldk.schema.JDependency;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Coverage for the best-effort Gradle dependency parser, driven through the full inventory so the
 * classification-to-parser routing (a {@code build_manifest/gradle} file reaches {@code
 * GradleDependencyParser}) is exercised too. The fixture mixes every recognized declaration form plus
 * one the parser deliberately cannot read.
 */
class GradleDependencyParserTest {

    private static final Path FIXTURE =
            Paths.get("src/test/resources/test-applications/gradle-artifact-test");
    private static final String APP = "gradle-artifact-test";

    private static Map<String, JDependency> gradleDeps() {
        Map<String, JArtifact> artifacts =
                ArtifactInventory.inventory(FIXTURE, APP, true, ArtifactInventory.DEFAULT_ARTIFACT_TEXT_MAX_BYTES);
        JArtifact build = artifacts.get("build.gradle");
        assertNotNull(build, "build.gradle must be inventoried");
        assertEquals("build_manifest", build.getArtifactKind());
        assertEquals("gradle", build.getFormat());
        Map<String, JDependency> deps = build.getDependencies();
        assertNotNull(deps, "a build.gradle with declared dependencies carries a dependency overlay");
        return deps;
    }

    @Test
    void stringNotationCarriesScopeAndResolvedVersion() {
        JDependency spring = gradleDeps().get("org.springframework:spring-core");
        assertNotNull(spring, "single-quoted string notation is recognized");
        assertEquals("maven", spring.getEcosystem());
        assertEquals("runtime", spring.getScope(), "implementation maps to runtime");
        assertEquals("6.1.4", spring.getVersionSpec());
        assertEquals("6.1.4", spring.getResolvedVersion());
        assertTrue(spring.isDirect());
        assertEquals("can://java/" + APP + "/@artifact/build.gradle/org.springframework:spring-core",
                spring.getId());
    }

    @Test
    void doubleQuotedAndParenthesizedNotationIsRecognized() {
        JDependency guava = gradleDeps().get("com.google.guava:guava");
        assertNotNull(guava, "double-quoted, parenthesized api(...) notation is recognized");
        assertEquals("runtime", guava.getScope(), "api maps to runtime");
        assertEquals("33.0.0-jre", guava.getResolvedVersion());
    }

    @Test
    void configurationNamesMapOntoCanonicalScopes() {
        Map<String, JDependency> deps = gradleDeps();
        assertEquals("test", deps.get("org.junit.jupiter:junit-jupiter").getScope());
        assertEquals("development", deps.get("org.projectlombok:lombok").getScope(),
                "compileOnly maps to development");
        assertEquals("runtime", deps.get("com.h2database:h2").getScope(),
                "runtimeOnly maps to runtime");
    }

    @Test
    void interpolatedVersionIsKeptAsSpecButNotResolved() {
        JDependency junit = gradleDeps().get("org.junit.jupiter:junit-jupiter");
        assertEquals("${junitVersion}", junit.getVersionSpec());
        assertNull(junit.getResolvedVersion(), "an interpolated version is not resolved");
    }

    @Test
    void versionlessDeclarationIsRecognizedWithoutAVersion() {
        JDependency slf4j = gradleDeps().get("org.slf4j:slf4j-api");
        assertNotNull(slf4j, "a group:name declaration (version from a BOM) is recognized");
        assertNull(slf4j.getVersionSpec(), "no version token means no spec");
        assertNull(slf4j.getResolvedVersion());
    }

    @Test
    void mapNotationIsRecognized() {
        JDependency h2 = gradleDeps().get("com.h2database:h2");
        assertNotNull(h2, "group:/name:/version: map notation is recognized");
        assertEquals("2.2.224", h2.getResolvedVersion());
    }

    @Test
    void dynamicCoordinateIsNotEmittedAsADependency() {
        // `implementation project(':internal-lib')` is a declaration line the best-effort parser
        // deliberately does not turn into a coordinate; it is logged as a gap, never guessed at.
        Map<String, JDependency> deps = gradleDeps();
        assertFalse(deps.containsKey(":internal-lib"), "a project(...) coordinate is not fabricated");
        assertFalse(deps.keySet().stream().anyMatch(k -> k.contains("internal-lib")),
                "no dependency is synthesized from a dynamic coordinate");
        // The six statically-declared coordinates are the whole recognized set.
        assertEquals(6, deps.size(), "exactly the six recognizable declarations are captured");
    }
}
