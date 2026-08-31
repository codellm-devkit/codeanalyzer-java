package com.ibm.cldk.artifacts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Behavioural tests for {@link ManifestParsers}: one happy-path case per format, the Maven
 * scope-to-kind mapping (the one judgement call this class makes), the whole-file-vs-per-line
 * failure distinction, and the XXE probe that is the point of hardening the XML factory at all.
 */
class ManifestParsersTest {

    // ---- pom.xml --------------------------------------------------------------------------

    @Test
    void parseManifest_pomXmlExtractsGroupArtifactVersionAndClassifier() {
        String pom = "<project><dependencies>"
                + "<dependency><groupId>org.springframework</groupId><artifactId>spring-core</artifactId>"
                + "<version>5.3.21</version></dependency>"
                + "<dependency><groupId>org.apache.commons</groupId><artifactId>commons-lang3</artifactId>"
                + "<version>3.14.0</version><classifier>sources</classifier></dependency>"
                + "</dependencies></project>";

        ManifestParsers.ParseResult result = ManifestParsers.parseManifest("pom.xml", pom);

        assertFalse(result.partial);
        assertEquals(2, result.deps.size());
        ManifestParsers.RawDep spring = result.deps.get(0);
        assertEquals("org.springframework", spring.group);
        assertEquals("spring-core", spring.name);
        assertEquals("5.3.21", spring.spec);
        assertEquals("runtime", spring.kind, "default (unscoped) Maven dependency is compile scope -> runtime");
        assertTrue(spring.extras.isEmpty());
        ManifestParsers.RawDep commons = result.deps.get(1);
        assertEquals(List.of("sources"), commons.extras, "classifier lands in extras");
    }

    @Test
    void parseManifest_pomXmlMissingVersionYieldsEmptySpec() {
        // No <version>: inherited from a parent or a BOM's <dependencyManagement>. JDependency.spec's
        // own contract says "may be empty" for exactly this case.
        String pom = "<project><dependencies>"
                + "<dependency><groupId>g</groupId><artifactId>a</artifactId></dependency>"
                + "</dependencies></project>";

        ManifestParsers.ParseResult result = ManifestParsers.parseManifest("pom.xml", pom);

        assertEquals(1, result.deps.size());
        assertEquals("", result.deps.get(0).spec);
    }

    @Test
    void parseManifest_pomXmlScopeTestYieldsKindDev() {
        String pom = "<project><dependencies>"
                + "<dependency><groupId>junit</groupId><artifactId>junit</artifactId>"
                + "<version>4.13.2</version><scope>test</scope></dependency>"
                + "</dependencies></project>";

        ManifestParsers.ParseResult result = ManifestParsers.parseManifest("pom.xml", pom);

        assertEquals("dev", result.deps.get(0).kind);
    }

    @Test
    void parseManifest_pomXmlProvidedAndSystemScopesBothYieldKindBuild() {
        String pom = "<project><dependencies>"
                + "<dependency><groupId>g</groupId><artifactId>provided-dep</artifactId>"
                + "<version>1.0</version><scope>provided</scope></dependency>"
                + "<dependency><groupId>g</groupId><artifactId>system-dep</artifactId>"
                + "<version>1.0</version><scope>system</scope></dependency>"
                + "</dependencies></project>";

        ManifestParsers.ParseResult result = ManifestParsers.parseManifest("pom.xml", pom);

        assertEquals(2, result.deps.size());
        assertEquals("build", result.deps.get(0).kind, "provided is compile-time-only, like a build requirement");
        assertEquals("build", result.deps.get(1).kind, "system is compile-time-only, like a build requirement");
    }

    @Test
    void parseManifest_pomXmlImportScopedEntryIsAbsent() {
        String pom = "<project><dependencies>"
                + "<dependency><groupId>com.example</groupId><artifactId>bom</artifactId>"
                + "<version>1.0</version><scope>import</scope></dependency>"
                + "<dependency><groupId>g</groupId><artifactId>real-dep</artifactId><version>1.0</version></dependency>"
                + "</dependencies></project>";

        ManifestParsers.ParseResult result = ManifestParsers.parseManifest("pom.xml", pom);

        assertEquals(1, result.deps.size(), "import scope is BOM inclusion, not a dependency");
        assertEquals("real-dep", result.deps.get(0).name);
    }

    @Test
    void parseManifest_pomXmlOptionalTrueAddsOptionalToExtrasWithoutChangingScopeDerivedKind() {
        // Kind is derived solely from <scope> (there is no fifth RawDep field to carry this flag);
        // <optional>true</optional> instead lands in `extras`, the same free-vocabulary bucket a
        // classifier uses.
        String pom = "<project><dependencies>"
                + "<dependency><groupId>g</groupId><artifactId>a</artifactId><version>1.0</version>"
                + "<scope>runtime</scope><optional>true</optional></dependency>"
                + "</dependencies></project>";

        ManifestParsers.ParseResult result = ManifestParsers.parseManifest("pom.xml", pom);

        ManifestParsers.RawDep dep = result.deps.get(0);
        assertEquals("runtime", dep.kind);
        assertEquals(List.of("optional"), dep.extras);
    }

    @Test
    void parseManifest_pomXmlDependencyManagementBlockIsNotTreatedAsADeclaredDependency() {
        // Only /project/dependencies/dependency is a declared dependency; a <dependencyManagement>
        // entry (even a non-import one) is a version constraint, not something the project itself
        // depends on unless it also appears in the plain <dependencies> block.
        String pom = "<project>"
                + "<dependencyManagement><dependencies>"
                + "<dependency><groupId>g</groupId><artifactId>managed-only</artifactId><version>1.0</version></dependency>"
                + "</dependencies></dependencyManagement>"
                + "<dependencies>"
                + "<dependency><groupId>g</groupId><artifactId>declared</artifactId><version>1.0</version></dependency>"
                + "</dependencies>"
                + "</project>";

        ManifestParsers.ParseResult result = ManifestParsers.parseManifest("pom.xml", pom);

        assertEquals(1, result.deps.size());
        assertEquals("declared", result.deps.get(0).name);
    }

    @Test
    void parseManifest_malformedPomXmlReturnsPartialWithNoRecords() {
        String truncated = "<project><dependencies><dependency><groupId>g</groupId>";

        ManifestParsers.ParseResult result = ManifestParsers.parseManifest("pom.xml", truncated);

        assertTrue(result.partial);
        assertTrue(result.deps.isEmpty());
    }

    @Test
    void parseManifest_pomXmlXxeProbeDoesNotResolveExternalEntity() {
        // Classic XXE PoC: if the entity resolved, group would contain /etc/passwd's contents. The
        // hardened factory must refuse the whole document at the DOCTYPE, not silently substitute it.
        String xxePom = "<?xml version=\"1.0\"?>\n"
                + "<!DOCTYPE project [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>\n"
                + "<project><dependencies>"
                + "<dependency><groupId>&xxe;</groupId><artifactId>probe</artifactId><version>1.0</version></dependency>"
                + "</dependencies></project>";

        ManifestParsers.ParseResult result = ManifestParsers.parseManifest("pom.xml", xxePom);

        assertTrue(result.partial, "a DOCTYPE-bearing pom.xml must fail closed, not resolve the entity");
        assertTrue(result.deps.isEmpty(), "no record may carry resolved external-entity content");
    }

    // ---- build.gradle / build.gradle.kts ---------------------------------------------------

    @Test
    void parseManifest_buildGradleExtractsEachConfigurationWithItsMappedKind() {
        String gradle = "dependencies {\n"
                + "    implementation 'org.springframework:spring-core:5.3.21'\n"
                + "    api \"com.google.guava:guava:31.1-jre\"\n"
                + "    testImplementation 'junit:junit:4.13.2'\n"
                + "    compileOnly 'org.projectlombok:lombok:1.18.30'\n"
                + "    annotationProcessor 'org.projectlombok:lombok:1.18.30'\n"
                + "    runtimeOnly 'mysql:mysql-connector-java:8.0.28'\n"
                + "}\n";

        ManifestParsers.ParseResult result = ManifestParsers.parseManifest("build.gradle", gradle);

        assertFalse(result.partial);
        assertEquals(6, result.deps.size());
        assertEquals("runtime", result.deps.get(0).kind, "implementation");
        assertEquals("com.google.guava", result.deps.get(1).group);
        assertEquals("guava", result.deps.get(1).name);
        assertEquals("31.1-jre", result.deps.get(1).spec);
        assertEquals("runtime", result.deps.get(1).kind, "api");
        assertEquals("dev", result.deps.get(2).kind, "testImplementation");
        assertEquals("build", result.deps.get(3).kind, "compileOnly");
        assertEquals("build", result.deps.get(4).kind, "annotationProcessor");
        assertEquals("runtime", result.deps.get(5).kind, "runtimeOnly");
    }

    @Test
    void parseManifest_buildGradleKtsBasenameIsAlsoDispatched() {
        String gradle = "implementation(\"org.springframework:spring-core:5.3.21\")\n";

        ManifestParsers.ParseResult result = ManifestParsers.parseManifest("build.gradle.kts", gradle);

        assertEquals(1, result.deps.size());
        assertEquals("spring-core", result.deps.get(0).name);
    }

    @Test
    void parseManifest_gradleLineWithInterpolatedVersionKeepsTheLiteralSpec() {
        // Deliberately shallow: evaluating $springVersion would mean evaluating a program. The
        // literal text is captured verbatim and left unresolved -- a known gap, not a bug.
        String gradle = "implementation \"org.springframework:spring-core:$springVersion\"\n";

        ManifestParsers.ParseResult result = ManifestParsers.parseManifest("build.gradle", gradle);

        assertEquals(1, result.deps.size());
        assertEquals("$springVersion", result.deps.get(0).spec);
    }

    @Test
    void parseManifest_gradleProjectReferenceLineProducesNoRecord() {
        // A project reference carries no 'g:a:v' coordinate literal at all, so the shallow regex
        // simply does not match -- the line is skipped like any other non-matching line, and the
        // file does not fail.
        String gradle = "testImplementation project(':core')\n";

        ManifestParsers.ParseResult result = ManifestParsers.parseManifest("build.gradle", gradle);

        assertFalse(result.partial);
        assertTrue(result.deps.isEmpty());
    }

    @Test
    void parseManifest_gradleUnmatchedLinesAreSkippedAndTheFileStillSucceeds() {
        String gradle = "plugins { id 'java' }\n"
                + "// a comment mentioning implementation but no coordinate\n"
                + "implementation 'org.springframework:spring-core:5.3.21'\n"
                + "this line is complete garbage {{{\n";

        ManifestParsers.ParseResult result = ManifestParsers.parseManifest("build.gradle", gradle);

        assertFalse(result.partial);
        assertEquals(1, result.deps.size());
    }

    // ---- gradle.lockfile --------------------------------------------------------------------

    @Test
    void parseLockPins_gradleLockfileExtractsGroupArtifactToVersionDroppingConfigurations() {
        String lock = "# This is a Gradle generated file for dependency locking.\n"
                + "com.google.guava:guava:31.1-jre=compileClasspath,runtimeClasspath\n"
                + "org.springframework:spring-core:5.3.21=compileClasspath\n"
                + "empty=annotationProcessor,testAnnotationProcessor\n";

        Map<String, String> pins = ManifestParsers.parseLockPins("gradle.lockfile", lock);

        assertEquals(2, pins.size());
        assertEquals("31.1-jre", pins.get("com.google.guava:guava"));
        assertEquals("5.3.21", pins.get("org.springframework:spring-core"));
    }

    @Test
    void parseLockPins_malformedLockfileReturnsEmptyMapRatherThanThrowing() {
        String garbage = "this is not a lockfile\n{{{ not even close }}}\n";

        Map<String, String> pins = ManifestParsers.parseLockPins("gradle.lockfile", garbage);

        assertTrue(pins.isEmpty());
    }

    @Test
    void parseLockPins_blankLockfileReturnsEmptyMap() {
        Map<String, String> pins = ManifestParsers.parseLockPins("gradle.lockfile", "");

        assertTrue(pins.isEmpty());
    }

    // ---- dispatch / unknown basenames ---------------------------------------------------------

    @Test
    void parseManifest_unknownBasenameReturnsEmptyNonPartialResult() {
        ManifestParsers.ParseResult result = ManifestParsers.parseManifest("README.md", "# hello");

        assertFalse(result.partial);
        assertTrue(result.deps.isEmpty());
    }

    @Test
    void parseLockPins_unknownBasenameReturnsEmptyMap() {
        Map<String, String> pins = ManifestParsers.parseLockPins("README.md", "# hello");

        assertTrue(pins.isEmpty());
    }
}
