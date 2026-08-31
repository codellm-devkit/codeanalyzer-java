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
 * {@code roles} are also free-vocabulary — {@code build}, {@code ci}, {@code deploy}, {@code config},
 * {@code dependency-lock} — and are user-assigned per artifact. A build manifest is both a build
 * artifact and a dependency declaration.
 */
@Data
public class JArtifact {
    private String id;
    private String kind = "artifact";

    /** Repo-relative path with {@code /} separators; also the map key. */
    private String path;

    /** Format identifier: xml|yaml|json|properties|gradle|dockerfile|text|binary. */
    private String format;

    /** Semantic roles, free-vocabulary: build, ci, deploy, config, dependency-lock, etc. */
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
