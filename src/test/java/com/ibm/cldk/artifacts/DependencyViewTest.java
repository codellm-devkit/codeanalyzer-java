package com.ibm.cldk.artifacts;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.cldk.schema.CanId;
import com.ibm.cldk.schema.JArtifact;
import com.ibm.cldk.schema.JDependency;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Behavioural tests for {@link DependencyView}: the declared/lock-backfill two-step reconciliation,
 * the lock-extraction status rule, and -- the regression this class exists to prevent -- that every
 * read comes from disk rather than {@link JArtifact#getSource()}, which capture flags can empty or
 * truncate.
 */
class DependencyViewTest {

    @Test
    void build_declaredDependencyCarriesProvDeclaredAndDirectTrue(@TempDir Path tmp) throws IOException {
        Files.writeString(
                tmp.resolve("pom.xml"),
                "<project><dependencies>"
                        + "<dependency><groupId>org.example</groupId><artifactId>widget</artifactId>"
                        + "<version>1.2.3</version></dependency>"
                        + "</dependencies></project>",
                StandardCharsets.UTF_8);
        Map<String, JArtifact> artifacts = ArtifactDiscovery.discover(tmp, "app", true, 262144);

        List<JDependency> deps = DependencyView.build(tmp, artifacts);

        assertEquals(1, deps.size());
        JDependency dep = deps.get(0);
        assertEquals("org.example", dep.getGroup());
        assertEquals("widget", dep.getName());
        assertEquals("1.2.3", dep.getSpec());
        assertEquals("runtime", dep.getKind());
        assertTrue(dep.getExtras().isEmpty());
        assertTrue(dep.isDirect());
        assertEquals(List.of("declared"), dep.getProv());
        assertEquals(CanId.artifactId("app", "pom.xml"), dep.getDeclaredIn());
        assertNull(dep.getLockedVersion());
        assertEquals("full", artifacts.get("pom.xml").getExtraction());
    }

    @Test
    void build_lockPinMatchingADeclaredDependencySetsLockedVersionWithoutDuplicating(@TempDir Path tmp)
            throws IOException {
        Files.writeString(
                tmp.resolve("build.gradle"),
                "dependencies { implementation 'com.google.guava:guava:31.1-jre' }\n",
                StandardCharsets.UTF_8);
        Files.writeString(
                tmp.resolve("gradle.lockfile"),
                "com.google.guava:guava:31.1-jre=compileClasspath,runtimeClasspath\n",
                StandardCharsets.UTF_8);
        Map<String, JArtifact> artifacts = ArtifactDiscovery.discover(tmp, "app", true, 262144);

        List<JDependency> deps = DependencyView.build(tmp, artifacts);

        assertEquals(1, deps.size(), "a lock pin matching a declared dependency must not duplicate it");
        JDependency dep = deps.get(0);
        assertEquals("com.google.guava", dep.getGroup());
        assertEquals("guava", dep.getName());
        assertEquals("31.1-jre", dep.getLockedVersion());
        assertTrue(dep.isDirect(), "still directly declared -- the lock only adds a pinned version");
        assertEquals(List.of("declared", "lockfile"), dep.getProv());
        assertEquals(
                CanId.artifactId("app", "build.gradle"), dep.getDeclaredIn(),
                "declaredIn stays the manifest that declared it, not the lock");
    }

    @Test
    void build_lockPinWithNoDeclarationBecomesTransitiveDependency(@TempDir Path tmp) throws IOException {
        Files.writeString(
                tmp.resolve("build.gradle"),
                "dependencies { implementation 'com.google.guava:guava:31.1-jre' }\n",
                StandardCharsets.UTF_8);
        Files.writeString(
                tmp.resolve("gradle.lockfile"),
                "com.google.guava:guava:31.1-jre=compileClasspath\n"
                        + "org.springframework:spring-core:5.3.21=compileClasspath\n",
                StandardCharsets.UTF_8);
        Map<String, JArtifact> artifacts = ArtifactDiscovery.discover(tmp, "app", true, 262144);

        List<JDependency> deps = DependencyView.build(tmp, artifacts);

        assertEquals(2, deps.size());
        JDependency transitive = deps.get(0).getName().equals("spring-core") ? deps.get(0) : deps.get(1);
        assertEquals("org.springframework", transitive.getGroup());
        assertEquals("spring-core", transitive.getName());
        assertEquals("runtime", transitive.getKind());
        assertFalse(transitive.isDirect(), "no manifest declares it -- it is lockfile-only");
        assertEquals(List.of("lockfile"), transitive.getProv());
        assertEquals("5.3.21", transitive.getLockedVersion());
        assertEquals(
                CanId.artifactId("app", "gradle.lockfile"), transitive.getDeclaredIn(),
                "an undeclared pin is attributed to the lock artifact itself");
    }

    @Test
    void build_malformedManifestSetsArtifactPartialButOtherArtifactsStillSucceed(@TempDir Path tmp)
            throws IOException {
        // Truncated mid-element -- identical shape to ManifestParsersTest's malformed-pom fixture.
        Files.writeString(
                tmp.resolve("pom.xml"), "<project><dependencies><dependency><groupId>g</groupId>",
                StandardCharsets.UTF_8);
        Files.writeString(
                tmp.resolve("build.gradle"),
                "dependencies { implementation 'org.springframework:spring-core:5.3.21' }\n",
                StandardCharsets.UTF_8);
        Map<String, JArtifact> artifacts = ArtifactDiscovery.discover(tmp, "app", true, 262144);

        List<JDependency> deps = assertDoesNotThrow(() -> DependencyView.build(tmp, artifacts));

        assertEquals("partial", artifacts.get("pom.xml").getExtraction());
        assertEquals(1, deps.size(), "the malformed manifest contributes nothing, but the run still succeeds");
        assertEquals("spring-core", deps.get(0).getName());
        assertEquals("full", artifacts.get("build.gradle").getExtraction());
    }

    @Test
    void build_orderingIsStableAcrossTwoIndependentRuns(@TempDir Path tmp) throws IOException {
        // Two dependencies named "apple", declared in two different manifests, force the secondary
        // sort key (declaredIn) to matter -- a name-only sort could not distinguish them.
        Files.writeString(
                tmp.resolve("pom.xml"),
                "<project><dependencies>"
                        + "<dependency><groupId>g1</groupId><artifactId>zebra</artifactId><version>1.0</version></dependency>"
                        + "<dependency><groupId>g1</groupId><artifactId>apple</artifactId><version>2.0</version></dependency>"
                        + "</dependencies></project>",
                StandardCharsets.UTF_8);
        Files.writeString(
                tmp.resolve("build.gradle"), "dependencies { implementation 'g2:apple:3.0' }\n",
                StandardCharsets.UTF_8);

        List<JDependency> first = DependencyView.build(tmp, ArtifactDiscovery.discover(tmp, "app", true, 262144));
        List<JDependency> second = DependencyView.build(tmp, ArtifactDiscovery.discover(tmp, "app", true, 262144));

        List<String> expectedNames = List.of("apple", "apple", "zebra");
        assertEquals(expectedNames, names(first));
        assertEquals(expectedNames, names(second), "two independent runs over the same input must agree");
        // (name, declaredIn) tie-break: "build.gradle" sorts before "pom.xml" lexicographically.
        assertEquals(CanId.artifactId("app", "build.gradle"), first.get(0).getDeclaredIn());
        assertEquals(CanId.artifactId("app", "pom.xml"), first.get(1).getDeclaredIn());
    }

    @Test
    void build_manifestParsesFromDiskEvenWhenSourceWasSuppressedByNoArtifactText(@TempDir Path tmp)
            throws IOException {
        Files.writeString(
                tmp.resolve("pom.xml"),
                "<project><dependencies>"
                        + "<dependency><groupId>org.example</groupId><artifactId>widget</artifactId>"
                        + "<version>1.2.3</version></dependency>"
                        + "</dependencies></project>",
                StandardCharsets.UTF_8);
        // captureText=false, exactly what --no-artifact-text does: every artifact's source is "".
        Map<String, JArtifact> artifacts = ArtifactDiscovery.discover(tmp, "app", false, 262144);
        assertEquals("", artifacts.get("pom.xml").getSource(), "sanity check: capture really is suppressed");

        List<JDependency> deps = DependencyView.build(tmp, artifacts);

        assertEquals(1, deps.size(), "an empty `source` must not be mistaken for an empty manifest");
        assertEquals("widget", deps.get(0).getName());
    }

    @Test
    void build_manifestParsesFromDiskEvenWhenSourceIsStaleOrWrong(@TempDir Path tmp) throws IOException {
        // The adversarial version of the above: `source` is not merely empty, it is a *different*,
        // syntactically valid manifest. An implementation that ever prefers `source` when it happens
        // to be non-empty would silently emit the wrong dependency instead of failing loudly.
        Files.writeString(
                tmp.resolve("pom.xml"),
                "<project><dependencies>"
                        + "<dependency><groupId>real.group</groupId><artifactId>real-artifact</artifactId>"
                        + "<version>9.9.9</version></dependency>"
                        + "</dependencies></project>",
                StandardCharsets.UTF_8);
        Map<String, JArtifact> artifacts = ArtifactDiscovery.discover(tmp, "app", true, 262144);
        artifacts.get("pom.xml").setSource(
                "<project><dependencies>"
                        + "<dependency><groupId>WRONG</groupId><artifactId>wrong-artifact</artifactId>"
                        + "<version>0.0.1</version></dependency>"
                        + "</dependencies></project>");

        List<JDependency> deps = DependencyView.build(tmp, artifacts);

        assertEquals(1, deps.size());
        assertEquals("real.group", deps.get(0).getGroup());
        assertEquals("real-artifact", deps.get(0).getName());
        assertEquals("9.9.9", deps.get(0).getSpec());
    }

    @Test
    void build_blankLockfileExtractionIsFullNotPartial(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("gradle.lockfile"), "", StandardCharsets.UTF_8);
        Map<String, JArtifact> artifacts = ArtifactDiscovery.discover(tmp, "app", true, 262144);

        List<JDependency> deps = DependencyView.build(tmp, artifacts);

        assertTrue(deps.isEmpty());
        assertEquals("full", artifacts.get("gradle.lockfile").getExtraction(), "nothing to extract is not a failure");
    }

    @Test
    void build_garbageLockfileWithContentExtractionIsPartial(@TempDir Path tmp) throws IOException {
        Files.writeString(
                tmp.resolve("gradle.lockfile"), "this is not a lockfile\n{{{ garbage }}}\n", StandardCharsets.UTF_8);
        Map<String, JArtifact> artifacts = ArtifactDiscovery.discover(tmp, "app", true, 262144);

        List<JDependency> deps = DependencyView.build(tmp, artifacts);

        assertTrue(deps.isEmpty());
        assertEquals(
                "partial", artifacts.get("gradle.lockfile").getExtraction(),
                "real content that yields zero pins must not be reported as a clean extraction");
    }

    @Test
    void build_nonManifestArtifactIsIgnoredEntirely(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("README.md"), "# hello", StandardCharsets.UTF_8);
        Map<String, JArtifact> artifacts = ArtifactDiscovery.discover(tmp, "app", true, 262144);

        List<JDependency> deps = DependencyView.build(tmp, artifacts);

        assertTrue(deps.isEmpty());
        assertEquals(
                "none", artifacts.get("README.md").getExtraction(),
                "DependencyView must not touch an artifact outside its role/basename gates");
    }

    @Test
    void build_unreadableManifestFileBecomesPartialInsteadOfCrashing(@TempDir Path tmp) {
        // No file is ever written to `tmp` -- simulates a manifest that vanished from disk between
        // discovery and this pass (or any other disk-read failure). parseManifest's pom.xml branch
        // now propagates NPE on null text (the checked-exception catch was narrowed in task 3), so
        // DependencyView must guard the precondition itself rather than ever calling it with
        // unreadable text.
        JArtifact art = new JArtifact();
        art.setId(CanId.artifactId("app", "pom.xml"));
        art.setPath("pom.xml");
        art.setRoles(List.of("dependency-manifest"));
        Map<String, JArtifact> artifacts = new HashMap<>();
        artifacts.put("pom.xml", art);

        List<JDependency> deps = assertDoesNotThrow(() -> DependencyView.build(tmp, artifacts));

        assertNotNull(deps);
        assertTrue(deps.isEmpty());
        assertEquals("partial", art.getExtraction());
    }

    @Test
    void build_unreadableLockfileBecomesPartialInsteadOfCrashing(@TempDir Path tmp) {
        // Same guard as above, exercised through the lock-backfill loop instead of the declared
        // loop -- a separate code path (parseLockPins is already null-safe internally, but
        // DependencyView must not rely on that; it applies the same disk-read guard uniformly).
        JArtifact art = new JArtifact();
        art.setId(CanId.artifactId("app", "gradle.lockfile"));
        art.setPath("gradle.lockfile");
        art.setRoles(List.of("dependency-manifest"));
        Map<String, JArtifact> artifacts = new HashMap<>();
        artifacts.put("gradle.lockfile", art);

        List<JDependency> deps = assertDoesNotThrow(() -> DependencyView.build(tmp, artifacts));

        assertTrue(deps.isEmpty());
        assertEquals("partial", art.getExtraction());
    }

    private static List<String> names(List<JDependency> deps) {
        return deps.stream().map(JDependency::getName).collect(Collectors.toList());
    }
}
