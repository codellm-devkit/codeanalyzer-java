package com.ibm.cldk.artifacts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.cldk.schema.JArtifact;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Behavioural tests for {@link ArtifactDiscovery}: the never-drop inventory contract (every
 * branch in the discovery walk's classification order), the ignore set's directory-vs-file
 * distinction, and the invariants a downstream consumer relies on (whole-file {@code sha256}/
 * {@code sizeBytes} regardless of capture/truncation, deterministic key order).
 */
class ArtifactDiscoveryTest {

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void discover_classifiesPomXmlAsADependencyManifest(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("pom.xml"), "<project></project>", StandardCharsets.UTF_8);

        Map<String, JArtifact> artifacts = ArtifactDiscovery.discover(tmp, "app", true, 262144);

        JArtifact pom = artifacts.get("pom.xml");
        assertNotNull(pom);
        assertEquals("can://artifact/app/pom.xml", pom.getId());
        assertEquals("pom.xml", pom.getPath());
        // configKeys/extraction are later tasks' fields; discovery must leave their defaults alone.
        assertEquals("artifact", pom.getKind());
        assertEquals("none", pom.getExtraction());
        assertTrue(pom.getConfigKeys().isEmpty());
        assertEquals("xml", pom.getFormat());
        assertEquals(List.of("dependency-manifest"), pom.getRoles());
        assertEquals("<project></project>", pom.getSource());
    }

    @Test
    void discover_stillInventoriesAnUnmatchedTextFileAsTextUnknown(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("notes.txt"), "hello", StandardCharsets.UTF_8);

        Map<String, JArtifact> artifacts = ArtifactDiscovery.discover(tmp, "app", true, 262144);

        JArtifact notes = artifacts.get("notes.txt");
        assertNotNull(notes, "never drop a file: an unmatched extension must still be inventoried");
        assertEquals("text", notes.getFormat());
        assertEquals(List.of("unknown"), notes.getRoles());
        assertEquals("hello", notes.getSource());
    }

    @Test
    void discover_excludesFilesUnderTargetDirectory(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("target");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("classes.txt"), "compiled", StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("pom.xml"), "<project></project>", StandardCharsets.UTF_8);

        Map<String, JArtifact> artifacts = ArtifactDiscovery.discover(tmp, "app", true, 262144);

        assertFalse(artifacts.containsKey("target/classes.txt"));
        assertTrue(artifacts.containsKey("pom.xml"));
    }

    @Test
    void discover_excludesFilesUnderBuildDirectory(@TempDir Path tmp) throws IOException {
        Path dir = tmp.resolve("build/resources");
        Files.createDirectories(dir);
        // The #199 lesson: Gradle's processResources copies fixtures into build/resources; those
        // copies are build output, not project artifacts, and must not be inventoried either.
        Files.writeString(dir.resolve("app.properties"), "k=v", StandardCharsets.UTF_8);

        Map<String, JArtifact> artifacts = ArtifactDiscovery.discover(tmp, "app", true, 262144);

        assertTrue(artifacts.isEmpty());
    }

    @Test
    void discover_aFileNamedBuildIsNotExcluded(@TempDir Path tmp) throws IOException {
        // The deliberate divergence from python's reference: IGNORED matches a directory segment,
        // not python's `any(part in IGNORED for part in rel.parts)`, which also excludes a *file*
        // literally named "build" or "target". A directory check must not have that bug.
        Files.writeString(tmp.resolve("build"), "not a directory", StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("target"), "not a directory either", StandardCharsets.UTF_8);

        Map<String, JArtifact> artifacts = ArtifactDiscovery.discover(tmp, "app", true, 262144);

        assertTrue(artifacts.containsKey("build"), "a file named 'build' is not a build-output directory");
        assertTrue(artifacts.containsKey("target"), "a file named 'target' is not a build-output directory");
    }

    @Test
    void discover_skipsJavaFilesEntirely(@TempDir Path tmp) throws IOException {
        Path pkg = tmp.resolve("src/main/java/com/example");
        Files.createDirectories(pkg);
        Files.writeString(
                pkg.resolve("Greeter.java"), "package com.example;\npublic class Greeter {}\n",
                StandardCharsets.UTF_8);

        Map<String, JArtifact> artifacts = ArtifactDiscovery.discover(tmp, "app", true, 262144);

        assertTrue(artifacts.isEmpty(), "the symbol table owns .java files, not the artifact layer");
    }

    @Test
    void discover_classifiesNonUtf8FilesAsBinaryButKeepsHashAndSize(@TempDir Path tmp) throws IOException {
        byte[] raw = {(byte) 0xFF, (byte) 0xFE, 0x00, 0x01, 0x02}; // not valid UTF-8
        Files.write(tmp.resolve("blob.dat"), raw);

        Map<String, JArtifact> artifacts = ArtifactDiscovery.discover(tmp, "app", true, 262144);

        JArtifact blob = artifacts.get("blob.dat");
        assertNotNull(blob);
        assertEquals("binary", blob.getFormat());
        assertEquals(List.of("unknown"), blob.getRoles());
        assertEquals("", blob.getSource());
        assertFalse(blob.isTextTruncated());
        assertEquals(raw.length, blob.getSizeBytes());
        assertEquals(sha256(raw), blob.getSha256());
    }

    @Test
    void discover_aRuleMatchedBinaryFileKeepsTheRulesRolesInsteadOfUnknown(@TempDir Path tmp) throws IOException {
        // pom.xml is rule-matched (dependency-manifest); if its bytes are not valid UTF-8 it must
        // still downgrade to format=binary while KEEPING that role, not falling back to unknown.
        byte[] raw = {(byte) 0xFF, (byte) 0xFE, 0x00};
        Files.write(tmp.resolve("pom.xml"), raw);

        Map<String, JArtifact> artifacts = ArtifactDiscovery.discover(tmp, "app", true, 262144);

        JArtifact pom = artifacts.get("pom.xml");
        assertNotNull(pom);
        assertEquals("binary", pom.getFormat());
        assertEquals(List.of("dependency-manifest"), pom.getRoles());
        assertEquals("", pom.getSource());
        assertEquals(sha256(raw), pom.getSha256());
    }

    @Test
    void discover_extensionlessShebangFileGetsScriptRole(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("run-server"), "#!/bin/sh\necho hi\n", StandardCharsets.UTF_8);

        Map<String, JArtifact> artifacts = ArtifactDiscovery.discover(tmp, "app", true, 262144);

        JArtifact script = artifacts.get("run-server");
        assertNotNull(script);
        assertEquals("text", script.getFormat());
        assertEquals(List.of("script"), script.getRoles());
    }

    @Test
    void discover_truncatesOverCapFilesButHashesTheWholeFile(@TempDir Path tmp) throws IOException {
        String content = "a".repeat(100);
        byte[] raw = content.getBytes(StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("big.txt"), content, StandardCharsets.UTF_8);

        Map<String, JArtifact> artifacts = ArtifactDiscovery.discover(tmp, "app", true, 10);

        JArtifact big = artifacts.get("big.txt");
        assertNotNull(big);
        assertTrue(big.isTextTruncated());
        assertEquals(10, big.getSource().length());
        assertEquals(
                raw.length, big.getSizeBytes(),
                "sizeBytes must describe the whole file, not the captured prefix");
        assertEquals(
                sha256(raw), big.getSha256(), "sha256 must hash the whole file even when source is truncated");
    }

    @Test
    void discover_dropsAPartialMultiByteCharacterAtTheTruncationBoundaryRatherThanThrowing(@TempDir Path tmp)
            throws IOException {
        // "caf" followed by the 2-byte UTF-8 sequence for U+00E9 (e-acute: 0xC3 0xA9), twice. Built
        // as explicit bytes rather than a string literal so the split point is unambiguous and the
        // fixture does not depend on the source file's own encoding. A cap of 4 lands right after
        // the first 0xC3 lead byte, splitting that character.
        byte[] raw = {'c', 'a', 'f', (byte) 0xC3, (byte) 0xA9, 'c', 'a', 'f', (byte) 0xC3, (byte) 0xA9};
        Files.write(tmp.resolve("accented.txt"), raw);

        Map<String, JArtifact> artifacts = ArtifactDiscovery.discover(tmp, "app", true, 4);

        JArtifact accented = artifacts.get("accented.txt");
        assertNotNull(accented, "a cap landing mid-character must still produce a usable artifact");
        assertTrue(accented.isTextTruncated());
        assertEquals("caf", accented.getSource(), "the split lead byte of 'e' must be dropped, not replaced");
        assertEquals(raw.length, accented.getSizeBytes());
        assertEquals(sha256(raw), accented.getSha256());
    }

    @Test
    void discover_aPomXmlOverTheCapIsNotTruncated(@TempDir Path tmp) throws IOException {
        String content = "<project>" + "x".repeat(100) + "</project>";
        Files.writeString(tmp.resolve("pom.xml"), content, StandardCharsets.UTF_8);

        Map<String, JArtifact> artifacts = ArtifactDiscovery.discover(tmp, "app", true, 10);

        JArtifact pom = artifacts.get("pom.xml");
        assertNotNull(pom);
        assertFalse(pom.isTextTruncated(), "a dependency manifest is captured whole regardless of the cap");
        assertEquals(content, pom.getSource());
        assertEquals(content.getBytes(StandardCharsets.UTF_8).length, pom.getSizeBytes());
    }

    @Test
    void discover_withCaptureTextDisabled_keepsInventoryButEmptiesEverySource(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("pom.xml"), "<project></project>", StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("notes.txt"), "hello", StandardCharsets.UTF_8);
        Files.write(tmp.resolve("blob.dat"), new byte[] {(byte) 0xFF, (byte) 0xFE});

        Map<String, JArtifact> withText = ArtifactDiscovery.discover(tmp, "app", true, 262144);
        Map<String, JArtifact> withoutText = ArtifactDiscovery.discover(tmp, "app", false, 262144);

        assertEquals(withText.keySet(), withoutText.keySet(), "inventory must be identical regardless of capture");
        for (String key : withText.keySet()) {
            JArtifact on = withText.get(key);
            JArtifact off = withoutText.get(key);
            assertEquals(on.getFormat(), off.getFormat(), key);
            assertEquals(on.getRoles(), off.getRoles(), key);
            assertEquals(on.getSha256(), off.getSha256(), key);
            assertEquals(on.getSizeBytes(), off.getSizeBytes(), key);
            assertEquals("", off.getSource(), key);
            assertFalse(off.isTextTruncated(), key);
        }
    }

    @Test
    void discover_producesIdenticalKeyOrderAcrossRuns(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("pom.xml"), "<project></project>", StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("notes.txt"), "hello", StandardCharsets.UTF_8);
        Path k8s = tmp.resolve("k8s");
        Files.createDirectories(k8s);
        Files.writeString(k8s.resolve("deploy.yml"), "kind: Deployment", StandardCharsets.UTF_8);

        Map<String, JArtifact> first = ArtifactDiscovery.discover(tmp, "app", true, 262144);
        Map<String, JArtifact> second = ArtifactDiscovery.discover(tmp, "app", true, 262144);

        assertEquals(List.copyOf(first.keySet()), List.copyOf(second.keySet()));
        assertEquals(List.of("k8s/deploy.yml", "notes.txt", "pom.xml"), List.copyOf(first.keySet()));
    }
}
