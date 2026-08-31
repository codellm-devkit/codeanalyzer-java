package com.ibm.cldk.artifacts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

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
    void parseManifest_pomXmlOptionalTrueSetsKindOptional() {
        // "optional" is one of the four values in the kind vocabulary (runtime|dev|optional|build)
        // and is not part of any other vocabulary here -- extras carries Maven classifiers (which
        // artifact of a coordinate you get), a different axis from optionality (how the dependency
        // is consumed), so it must not land there.
        String pom = "<project><dependencies>"
                + "<dependency><groupId>g</groupId><artifactId>a</artifactId><version>1.0</version>"
                + "<scope>runtime</scope><optional>true</optional></dependency>"
                + "</dependencies></project>";

        ManifestParsers.ParseResult result = ManifestParsers.parseManifest("pom.xml", pom);

        ManifestParsers.RawDep dep = result.deps.get(0);
        assertEquals("optional", dep.kind);
        assertTrue(dep.extras.isEmpty());
    }

    @Test
    void parseManifest_pomXmlOptionalTrueTakesPrecedenceOverScopeDerivedKind() {
        // A <scope>test</scope> dependency that is also <optional>true</optional> must come out
        // "optional", not "dev" -- optionality wins over scope, matching the reference, where an
        // optional group's entries are "optional" regardless of what they would otherwise have been.
        String pom = "<project><dependencies>"
                + "<dependency><groupId>g</groupId><artifactId>a</artifactId><version>1.0</version>"
                + "<scope>test</scope><optional>true</optional></dependency>"
                + "</dependencies></project>";

        ManifestParsers.ParseResult result = ManifestParsers.parseManifest("pom.xml", pom);

        assertEquals("optional", result.deps.get(0).kind);
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

    @Test
    void parseManifest_pomXmlBareExternalDtdReferenceAlsoFailsClosed() {
        // A different attack shape from the probe above: no inline <!ENTITY ...> at all -- just the
        // DOCTYPE's own external subset, SYSTEM-referencing a local file. Also dominated by
        // disallow-doctype-decl in production (see the factory-level tests below for why that
        // dominance means this alone cannot attribute the other three switches), but it is a
        // materially different payload shape and worth its own regression coverage.
        String bareExternalDtd = "<?xml version=\"1.0\"?>\n"
                + "<!DOCTYPE project SYSTEM \"file:///etc/passwd\">\n"
                + "<project><dependencies>"
                + "<dependency><groupId>g</groupId><artifactId>probe</artifactId><version>1.0</version></dependency>"
                + "</dependencies></project>";

        ManifestParsers.ParseResult result = ManifestParsers.parseManifest("pom.xml", bareExternalDtd);

        assertTrue(result.partial, "a bare external-DTD reference must also fail closed");
        assertTrue(result.deps.isEmpty());
    }

    @Test
    void newSecureDocumentBuilderFactory_setsDisallowDoctypeAndBothExternalEntityFeatures() throws Exception {
        // Each of these three has a Xerces *default* that is the opposite of the secure value used
        // here (disallow-doctype-decl defaults to false; both external-entity features default to
        // true) -- verified directly against a fresh, untouched factory. That means removing any one
        // of the corresponding setFeature calls in newSecureDocumentBuilderFactory flips exactly one
        // assertion below, with no dependence on Xerces' feature-checking order. This is what a
        // behavioural probe cannot give us: disallow-doctype-decl rejects any DOCTYPE-bearing
        // document before the other two are ever consulted, so a payload-based test cannot tell
        // "external-general-entities is set correctly" apart from "it doesn't matter here anyway".
        //
        // FEATURE_SECURE_PROCESSING is deliberately not asserted here -- see the dedicated test
        // below for why getFeature() cannot attribute it.
        DocumentBuilderFactory factory = ManifestParsers.newSecureDocumentBuilderFactory();

        assertTrue(factory.getFeature("http://apache.org/xml/features/disallow-doctype-decl"));
        assertFalse(factory.getFeature("http://xml.org/sax/features/external-general-entities"));
        assertFalse(factory.getFeature("http://xml.org/sax/features/external-parameter-entities"));
    }

    @Test
    void newSecureDocumentBuilderFactory_featureSecureProcessingBlocksABareExternalDtdFetchOnItsOwn()
            throws Exception {
        // FEATURE_SECURE_PROCESSING cannot be attributed by getFeature(): verified directly that it
        // reads "true" on a completely untouched factory where setFeature was never called at all,
        // so asserting the getter would pass whether or not the setFeature call in
        // newSecureDocumentBuilderFactory exists. Only *calling* the setter activates the JAXP
        // accessExternalDTD restriction this switch exists for -- confirmed by comparing "never
        // called" (file gets read off disk, then fails to parse as a DTD) against "explicitly set
        // true" (rejected before the file is ever opened, via accessExternalDTD).
        //
        // disallow-doctype-decl is deliberately peeled back to false on the real, production-built
        // factory below (everything else from newSecureDocumentBuilderFactory is untouched):
        // verified directly that with it left in place, removing FEATURE_SECURE_PROCESSING alone
        // produces byte-identical behaviour (still rejected at the DOCTYPE token, before
        // FEATURE_SECURE_PROCESSING is ever consulted) -- so peeling it back here is the only way to
        // observe this switch's own, independent contribution.
        DocumentBuilderFactory factory = ManifestParsers.newSecureDocumentBuilderFactory();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);

        // No inline <!ENTITY ...> at all, so external-general-entities/external-parameter-entities
        // (both still secure here) do not apply to this payload; only accessExternalDTD stands
        // between this and reading the file off disk.
        String bareExternalDtd = "<?xml version=\"1.0\"?>\n"
                + "<!DOCTYPE project SYSTEM \"file:///etc/passwd\">\n"
                + "<project><dependencies>"
                + "<dependency><groupId>g</groupId><artifactId>probe</artifactId><version>1.0</version></dependency>"
                + "</dependencies></project>";

        SAXException thrown = assertThrows(SAXException.class,
                () -> factory.newDocumentBuilder().parse(new InputSource(new StringReader(bareExternalDtd))));
        assertTrue(
                thrown.getMessage().contains("accessExternalDTD"),
                "must be rejected via the accessExternalDTD restriction specifically -- i.e. before the file is "
                        + "ever read -- not merely rejected for some other reason; actual message: "
                        + thrown.getMessage());
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

    @Test
    void parseManifest_buildGradleNeverReturnsPartialRegardlessOfContent() {
        // Structural, not incidental: the brief requires this branch to never fail, and
        // parseManifest enforces it by giving pom.xml its own try/catch and none at all to the
        // Gradle branch, rather than one catch shared across both with different contracts. This is
        // the dedicated test for that contract, independent of any other Gradle test's specific
        // input shape -- genuinely arbitrary, non-UTF-friendly-looking, brace-heavy garbage.
        String garbage = "  not gradle at all {{{{ )))) ][ \\\\ '''\" \n\n\timplementation\n";

        ManifestParsers.ParseResult result = ManifestParsers.parseManifest("build.gradle", garbage);

        assertFalse(result.partial);
        assertTrue(result.deps.isEmpty());
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
