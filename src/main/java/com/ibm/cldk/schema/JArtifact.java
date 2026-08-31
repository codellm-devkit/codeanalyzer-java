package com.ibm.cldk.schema;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * A build or configuration artifact in the repository — a build manifest, config file, Dockerfile,
 * CI definition, or other non-source file. The {@code id} is the canonical {@code can://artifact/}
 * path, and {@code path} is its repo-relative location (also the map key in {@code application.artifacts}),
 * allowing consumers to navigate back to the filesystem.
 *
 * <p>{@code format} is free-vocabulary and identifies the parser: {@code xml}, {@code yaml},
 * {@code json}, {@code properties}, {@code gradle}, {@code dockerfile}, {@code text}, {@code binary}.
 *
 * <p>{@code roles} are assigned by {@code ArtifactDiscovery}'s first-match-wins classification
 * rules, not by a user, and the vocabulary the rules actually emit is exactly: {@code
 * dependency-manifest}, {@code tool-config}, {@code container-image}, {@code service-topology},
 * {@code iac}, {@code ci}, {@code env}, {@code legal}, {@code docs}, {@code script}, {@code
 * unknown}. One artifact may carry several — a {@code build.gradle} is both a {@code
 * dependency-manifest} and a {@code tool-config}. The list is open in the schema (a consumer must
 * tolerate an unseen role), but this analyzer emits no role outside it: {@code
 * dependency-manifest} in particular is load-bearing, gating both the text-capture cap exemption
 * and dependency extraction.
 */
@Data
public class JArtifact {
    private String id;
    private String kind = "artifact";

    /** Repo-relative path with {@code /} separators; also the map key. */
    private String path;

    /** Format identifier: xml|yaml|json|properties|gradle|dockerfile|text|binary. */
    private String format;

    /** Rule-assigned semantic roles; see the class javadoc for the vocabulary actually emitted. */
    private List<String> roles = new ArrayList<>();

    /** File size in bytes. */
    private long sizeBytes;

    /** SHA-256 hash of the whole file. */
    private String sha256;

    /**
     * File contents when captured (controlled by {@code --artifact-text}), empty string for binary
     * or when capture is disabled.
     */
    private String source = "";

    /** {@code true} if the source was truncated to fit memory limits. */
    private boolean textTruncated;

    /** Extraction/parsing status: {@code none}, {@code partial}, or {@code full}. */
    private String extraction = "none";

    /** Configuration keys defined or referenced in this artifact. */
    private List<JConfigKey> configKeys = new ArrayList<>();
}
