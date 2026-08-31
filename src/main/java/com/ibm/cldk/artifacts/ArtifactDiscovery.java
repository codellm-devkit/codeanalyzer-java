package com.ibm.cldk.artifacts;

import com.ibm.cldk.schema.CanId;
import com.ibm.cldk.schema.JArtifact;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Repo-wide inventory of every non-source file: build manifests, config, Dockerfiles, CI
 * definitions, and anything else a Java project ships beside its {@code .java} sources.
 *
 * <p>The governing rule is <b>never drop a file</b>. A path matching no entry in {@link #RULES} is
 * still captured — {@code text}/{@code unknown} if it decodes as UTF-8, {@code binary} if it does
 * not — because the layer's value is a complete answer to "what is in this repo," and a
 * classification miss is a shrug, not an omission. The one carve-out is a {@code .java} file that
 * no rule names: the symbol table (L1) already owns those, and inventorying them here too would
 * produce two nodes for one file with no way for a consumer to tell which is authoritative.
 *
 * <p>Nothing here parses artifact content. This class only decides what a file IS, hashes it, and
 * captures its text for the emitted JSON payload. Extraction (dependencies, config keys) re-reads
 * each file <b>from disk</b> via {@link DependencyView#readFromDisk} and must never read {@code
 * source} back out of the returned {@link JArtifact}s: {@code source} is a payload-size-controlled
 * field, empty under {@code --no-artifact-text} and a truncated prefix past {@code
 * --artifact-text-max-bytes}, so extraction driven off it would silently degrade under either flag.
 * See {@code readFromDisk}'s javadoc before wiring any new extraction pass.
 */
public final class ArtifactDiscovery {

    private ArtifactDiscovery() {}

    // First match wins — order matters. Specific names precede generic globs, so
    // `pom.xml` is a manifest while any other `*.xml` is a config candidate.
    private static final List<Rule> RULES = List.of(
            new Rule("pom.xml", "xml", List.of("dependency-manifest")),
            new Rule("build.gradle", "gradle", List.of("dependency-manifest", "tool-config")),
            new Rule("build.gradle.kts", "gradle", List.of("dependency-manifest", "tool-config")),
            new Rule("settings.gradle", "gradle", List.of("tool-config")),
            new Rule("settings.gradle.kts", "gradle", List.of("tool-config")),
            new Rule("gradle.lockfile", "text", List.of("dependency-manifest")),
            new Rule("gradle.properties", "properties", List.of("tool-config")),
            new Rule("gradle/libs.versions.toml", "text", List.of("dependency-manifest")),
            new Rule("ivy.xml", "xml", List.of("dependency-manifest")),
            new Rule("Dockerfile", "dockerfile", List.of("container-image")),
            new Rule("*.dockerfile", "dockerfile", List.of("container-image")),
            new Rule("docker-compose*.yml", "yaml", List.of("service-topology")),
            new Rule("docker-compose*.yaml", "yaml", List.of("service-topology")),
            new Rule("compose.yml", "yaml", List.of("service-topology")),
            new Rule("compose.yaml", "yaml", List.of("service-topology")),
            new Rule("k8s/*.yml", "yaml", List.of("service-topology")),
            new Rule("k8s/*.yaml", "yaml", List.of("service-topology")),
            new Rule("Chart.yaml", "yaml", List.of("service-topology")),
            new Rule("values.yaml", "yaml", List.of("service-topology")),
            new Rule("application*.yml", "yaml", List.of("tool-config")),
            new Rule("application*.yaml", "yaml", List.of("tool-config")),
            new Rule("application*.properties", "properties", List.of("tool-config")),
            new Rule("bootstrap*.yml", "yaml", List.of("tool-config")),
            new Rule("logback*.xml", "xml", List.of("tool-config")),
            new Rule("web.xml", "xml", List.of("tool-config")),
            new Rule("persistence.xml", "xml", List.of("tool-config")),
            new Rule("beans.xml", "xml", List.of("tool-config")),
            new Rule("*.tf", "text", List.of("iac")),
            new Rule(".github/workflows/*.yml", "yaml", List.of("ci")),
            new Rule(".github/workflows/*.yaml", "yaml", List.of("ci")),
            new Rule(".gitlab-ci.yml", "yaml", List.of("ci")),
            new Rule("Jenkinsfile", "text", List.of("ci")),
            new Rule(".env", "text", List.of("env")),
            new Rule(".env.*", "text", List.of("env")),
            new Rule("LICENSE*", "text", List.of("legal")),
            new Rule("NOTICE*", "text", List.of("legal")),
            new Rule("*.md", "text", List.of("docs")),
            new Rule("*.adoc", "text", List.of("docs")),
            new Rule("*.properties", "properties", List.of("tool-config")),
            new Rule("*.yml", "yaml", List.of("unknown")),
            new Rule("*.yaml", "yaml", List.of("unknown")),
            new Rule("*.json", "json", List.of("unknown")),
            new Rule("*.xml", "xml", List.of("unknown")));

    // Scoped to Java build output. `build` and `target` are the same class of exclusion the L1
    // extractor learned in #199: Gradle's build/resources copies of test fixtures are build output,
    // not project code, and the lesson generalises to artifacts.
    private static final Set<String> IGNORED = Set.of(
            ".git", ".hg", ".svn", "target", "build", "out", "bin",
            ".gradle", ".mvn", ".idea", ".settings", "node_modules",
            ".codeanalyzer", "_library_dependencies");

    /** A first-match-wins classification row: glob {@code pattern}, {@code format}, and {@code roles}. */
    private static final class Rule {
        private final String pattern;
        private final String format;
        private final List<String> roles;

        Rule(String pattern, String format, List<String> roles) {
            this.pattern = pattern;
            this.format = format;
            this.roles = roles;
        }
    }

    /**
     * Walk {@code projectDir} and return every regular file as an artifact, keyed by its
     * repo-relative {@code /}-separated path with iteration order sorted by that key.
     *
     * @param captureText when {@code false}, every {@code source} is empty and no file is
     *     truncated, but classification and hashing are unaffected — the inventory is identical
     *     either way, only the captured text differs
     * @param textMaxBytes byte cap on captured text for a decodable file; a {@code
     *     dependency-manifest} is exempt (always captured whole when {@code captureText} is on)
     *     so the complete manifest text appears in the emitted JSON payload for consumers, not
     *     truncated by the cap
     */
    public static Map<String, JArtifact> discover(
            Path projectDir, String appName, boolean captureText, int textMaxBytes) throws IOException {
        Path root = projectDir.toAbsolutePath().normalize();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
        }

        Map<String, JArtifact> artifacts = new TreeMap<>();
        for (Path file : files) {
            Path relative = root.relativize(file);
            if (isIgnored(relative)) {
                continue;
            }
            String relPosix = relative.toString().replace('\\', '/');
            String name = basename(relPosix);
            Rule rule = classify(relPosix);
            if (rule == null && name.endsWith(".java")) {
                continue; // the symbol table owns Java source, not this layer
            }

            byte[] raw = Files.readAllBytes(file);
            String text = decodeStrict(raw);
            boolean decodable = text != null;

            String format;
            List<String> roles;
            if (!decodable) {
                format = "binary";
                roles = rule != null ? rule.roles : List.of("unknown");
            } else if (rule != null) {
                format = rule.format;
                roles = rule.roles;
            } else {
                format = "text";
                roles = (!name.contains(".") && text.startsWith("#!")) ? List.of("script") : List.of("unknown");
            }

            JArtifact artifact = new JArtifact();
            artifact.setId(CanId.artifactId(appName, relPosix));
            artifact.setPath(relPosix);
            artifact.setFormat(format);
            artifact.setRoles(roles);
            // sha256/sizeBytes always describe the whole file, independent of capture/truncation
            // below -- this is the invariant a consumer relies on to detect a truncated source.
            artifact.setSizeBytes(raw.length);
            artifact.setSha256(sha256Hex(raw));

            if (decodable && captureText) {
                int cap = roles.contains("dependency-manifest") ? raw.length : textMaxBytes;
                if (raw.length <= cap) {
                    artifact.setSource(text);
                } else {
                    artifact.setSource(decodeLenientPrefix(raw, cap));
                    artifact.setTextTruncated(true);
                }
            }

            artifacts.put(relPosix, artifact);
        }
        return artifacts;
    }

    // Directory segments only, deliberately: testing every segment including the leaf would also
    // match a *file* literally named "build" or "target". Only the segments strictly above the file
    // name are checked -- do not "simplify" this into a check over all segments.
    private static boolean isIgnored(Path relative) {
        int dirSegments = relative.getNameCount() - 1;
        for (int i = 0; i < dirSegments; i++) {
            if (IGNORED.contains(relative.getName(i).toString())) {
                return true;
            }
        }
        return false;
    }

    private static String basename(String relPosix) {
        int slash = relPosix.lastIndexOf('/');
        return slash < 0 ? relPosix : relPosix.substring(slash + 1);
    }

    // A pattern containing '/' matches the full repo-relative path; a bare pattern matches just the
    // basename (e.g. "*.xml" matches at any depth). Both are fnmatch-shaped, so '*' crosses '/' --
    // "k8s/*.yml" matches a nested "k8s/base/svc.yml" too, not only files directly under k8s/ (see
    // globMatches below, which is where that is deliberate rather than accidental).
    private static Rule classify(String relPosix) {
        String name = basename(relPosix);
        for (Rule rule : RULES) {
            String target = rule.pattern.contains("/") ? relPosix : name;
            if (globMatches(rule.pattern, target)) {
                return rule;
            }
        }
        return null;
    }

    // Only '*' appears anywhere in RULES, so that is all this translates. '*' is a plain regex
    // wildcard here and so can cross '/', unlike java.nio's glob PathMatcher, whose '*' stops at a
    // path separator and would silently narrow "k8s/*.yml".
    private static boolean globMatches(String pattern, String target) {
        StringBuilder regex = new StringBuilder();
        for (String literal : pattern.split("\\*", -1)) {
            if (regex.length() > 0) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(literal));
        }
        return Pattern.matches(regex.toString(), target);
    }

    /** Whole-file strict UTF-8 decode; {@code null} means the file is not valid UTF-8 ("binary"). */
    private static String decodeStrict(byte[] raw) {
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(raw)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /**
     * Decode a byte prefix for a truncated capture. The cap can land mid-character; IGNORE drops
     * the dangling partial character at the cut instead of throwing. Only called on bytes already
     * proven fully UTF-8 decodable by {@link #decodeStrict}, so the sole possible error is that one
     * boundary character.
     */
    private static String decodeLenientPrefix(byte[] raw, int len) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.IGNORE)
                    .onUnmappableCharacter(CodingErrorAction.IGNORE)
                    .decode(ByteBuffer.wrap(raw, 0, len))
                    .toString();
        } catch (CharacterCodingException e) {
            // IGNORE never reports an error; this path cannot execute.
            throw new IllegalStateException(e);
        }
    }

    // Hand-rolled hex encoding: this project's toolchain is pinned to Java 11 (see the Kotlin
    // `jvmToolchain(11)` block), which predates java.util.HexFormat (17+). Mirrors
    // L1BuildContext.contentHash()'s identical nibble loop.
    private static String sha256Hex(byte[] raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM; absence is unrecoverable, not a soft failure.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
