package com.ibm.cldk.artifacts;

import com.ibm.cldk.artifacts.ArtifactClassifier.Classification;
import com.ibm.cldk.schema.CanId;
import com.ibm.cldk.schema.JArtifact;
import com.ibm.cldk.schema.JConfigKey;
import com.ibm.cldk.schema.JDependency;
import com.ibm.cldk.utils.Log;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds the repository-artifact inventory: a repo-wide walk that classifies every non-source file,
 * captures its bytes (hash + size, optionally decoded text), and — for manifests and config files —
 * overlays declared dependencies and config keys.
 *
 * <p>This walk is deliberately separate from the source-root-scoped v2 extraction: artifacts live all
 * over a repository (root manifests, {@code .github/} CI, {@code k8s/} manifests), not only under the
 * Java source roots, so it re-walks from the project root with its own directory pruning.
 *
 * <p>The inventory is application-anchored: each artifact is keyed by its repo-relative path and its id
 * is {@code <app-id>/@artifact/<rel-path>}. The result is a sorted map so output is byte-identical
 * across runs (the {@code -j} determinism gate).
 */
public final class ArtifactInventory {

    private ArtifactInventory() {}

    /** Default text-capture cap: 256 KiB. Files larger than this are inventoried with a truncated
     * prefix so a huge generated file cannot bloat the payload. */
    public static final int DEFAULT_ARTIFACT_TEXT_MAX_BYTES = 256 * 1024;

    // Directories that never hold first-class repository artifacts; pruned to keep the walk cheap and
    // the inventory free of build output and vendored trees.
    private static final String[] PRUNED_DIRS = {
        ".git", "build", "target", "node_modules", ".gradle", ".idea", "out", "bin", "dist",
        "__pycache__", ".mvn", ".venv", "venv"
    };

    /**
     * Walk {@code projectRoot} and inventory every non-source artifact.
     *
     * @param projectRoot   the repository root to walk
     * @param appName       the logical application name (for artifact ids)
     * @param captureText   whether to decode and capture file text (the {@code --artifact-text} toggle)
     * @param textMaxBytes  the text-capture cap in bytes
     * @return artifacts keyed by repo-relative path, sorted; empty when nothing qualifies
     */
    public static Map<String, JArtifact> inventory(
            Path projectRoot, String appName, boolean captureText, int textMaxBytes) {
        Map<String, JArtifact> artifacts = new TreeMap<>();
        Path root = projectRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return artifacts;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !isPruned(root, p))
                    .filter(p -> !isSource(p))
                    .forEach(p -> {
                        String relPath = root.relativize(p).toString().replace('\\', '/');
                        JArtifact artifact = buildArtifact(root, p, relPath, appName, captureText, textMaxBytes);
                        if (artifact != null) {
                            artifacts.put(relPath, artifact);
                        }
                    });
        } catch (IOException e) {
            Log.warn("Artifact inventory walk of " + root + " failed (" + e.getMessage()
                    + "); emitting whatever was collected");
        }
        return artifacts;
    }

    private static JArtifact buildArtifact(
            Path root,
            Path file,
            String relPath,
            String appName,
            boolean captureText,
            int textMaxBytes) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (IOException e) {
            Log.warn("Could not read artifact " + relPath + " (" + e.getMessage() + "); skipping");
            return null;
        }

        Classification cls = ArtifactClassifier.classify(relPath);
        JArtifact artifact = new JArtifact();
        artifact.setId(CanId.artifactId(appName, relPath));
        artifact.setArtifactKind(cls.kind);
        artifact.setPath(relPath);
        if (cls.format != null) {
            artifact.setFormat(cls.format);
        }
        artifact.setContentHash(sha256(bytes));
        artifact.setSizeBytes(bytes.length);

        // Text capture: decode as UTF-8. A binary file (undecodable bytes) is inventoried without text;
        // a file over the cap keeps a leading prefix with textTruncated set. path/hash/size are always
        // present so a truncated or binary artifact still dereferences to its source.
        byte[] forParsing = bytes;
        if (captureText) {
            boolean truncated = bytes.length > textMaxBytes;
            byte[] slice = truncated ? java.util.Arrays.copyOf(bytes, textMaxBytes) : bytes;
            // A hard byte-cap can split the final multi-byte UTF-8 character. Back the slice off to the
            // last complete character before a strict decode, so a genuine binary file still fails to
            // decode (stays binary) while a clean text prefix survives the cut.
            byte[] toDecode = truncated ? trimToUtf8Boundary(slice) : slice;
            String decoded = decodeUtf8(toDecode);
            if (decoded != null) {
                artifact.setText(decoded);
                artifact.setTextEncoding("utf-8");
                artifact.setTextTruncated(truncated);
            }
            // Parse from the (possibly truncated) captured slice so text and overlays stay consistent.
            forParsing = slice;
        }

        overlayDependencies(artifact, cls, forParsing, relPath);
        overlayConfigKeys(artifact, cls, forParsing, relPath);
        return artifact;
    }

    private static void overlayDependencies(
            JArtifact artifact, Classification cls, byte[] bytes, String relPath) {
        if (!"build_manifest".equals(cls.kind)) {
            return;
        }
        Map<String, JDependency> deps;
        if ("xml".equals(cls.format)) {
            deps = MavenDependencyParser.parse(artifact.getId(), bytes, relPath);
        } else if (cls.format != null && cls.format.startsWith("gradle")) {
            deps = GradleDependencyParser.parse(artifact.getId(), bytes, relPath);
        } else {
            return;
        }
        if (!deps.isEmpty()) {
            artifact.setDependencies(deps);
        }
    }

    private static void overlayConfigKeys(
            JArtifact artifact, Classification cls, byte[] bytes, String relPath) {
        if (!"configuration".equals(cls.kind)) {
            return;
        }
        Map<String, JConfigKey> keys =
                ConfigKeyParser.parse(artifact.getId(), cls.format, bytes, relPath);
        if (!keys.isEmpty()) {
            artifact.setConfigKeys(keys);
        }
    }

    /** True for a directory segment that should be pruned from the walk. */
    private static boolean isPruned(Path root, Path file) {
        Path rel = root.relativize(file);
        for (Path segment : rel) {
            String name = segment.toString();
            for (String pruned : PRUNED_DIRS) {
                if (pruned.equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Source files belong to the symbol table, not the artifact inventory. */
    private static boolean isSource(Path file) {
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".java") || name.endsWith(".class") || name.endsWith(".jar");
    }

    /** Drop a trailing partial UTF-8 character from a hard-truncated slice: rewind past continuation
     * bytes ({@code 10xxxxxx}) to a lead byte, then drop that lead byte too if its declared length runs
     * past the slice end. A complete final character is returned unchanged. */
    private static byte[] trimToUtf8Boundary(byte[] slice) {
        int end = slice.length;
        int i = end - 1;
        // Rewind over continuation bytes (0b10xxxxxx).
        while (i >= 0 && (slice[i] & 0xC0) == 0x80) {
            i--;
        }
        if (i < 0) {
            return slice;
        }
        int lead = slice[i] & 0xFF;
        int expected;
        if (lead < 0x80) {
            expected = 1; // ASCII
        } else if ((lead & 0xE0) == 0xC0) {
            expected = 2;
        } else if ((lead & 0xF0) == 0xE0) {
            expected = 3;
        } else if ((lead & 0xF8) == 0xF0) {
            expected = 4;
        } else {
            expected = 1; // invalid lead; let the strict decoder reject it as binary
        }
        int have = end - i;
        if (have < expected) {
            // Final character is incomplete — cut it off.
            return java.util.Arrays.copyOf(slice, i);
        }
        return slice;
    }

    /** Strict UTF-8 decode; null when the bytes are not valid UTF-8 (treated as binary). */
    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 is guaranteed present on every JVM; this is unreachable.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
