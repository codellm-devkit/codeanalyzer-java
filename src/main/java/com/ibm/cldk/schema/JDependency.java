package com.ibm.cldk.schema;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * A declared dependency from a build manifest or lock file, representing one pinned package in an
 * ecosystem. A Maven coordinate is two-segment, so it is carried as a {@code group}
 * ({@code groupId}) plus a {@code name} ({@code artifactId}) rather than one flat name.
 *
 * <p>{@code ecosystem} exists for SDK symmetry with purl and is always {@code "maven"} — the only
 * ecosystem this analyzer emits. {@code kind} is free-vocabulary; the values actually produced are
 * {@code runtime}, {@code dev}, {@code optional} and {@code build}.
 *
 * <p>{@code lockedVersion} is {@code null} (omitted from JSON) when the dependency is unpinned —
 * recorded only when found in a lock file or package manager.
 */
@Data
public class JDependency {
    /** Maven {@code groupId}; with {@link #name} it forms the two-segment coordinate. */
    private String group;

    /** Maven {@code artifactId} (the package name). */
    private String name;

    /** Package ecosystem; always {@code maven} — the only one this analyzer emits. */
    private String ecosystem = "maven";

    /** Declared version range or spec, verbatim as written (may be empty). */
    private String spec = "";

    /** Dependency kind: runtime|dev|optional|build. Defaults to runtime. */
    private String kind = "runtime";

    /** Maven classifiers (free-vocabulary extras, e.g., {@code sources}, {@code javadoc}). */
    private List<String> extras = new ArrayList<>();

    /** The {@code JArtifact} id where this dependency was declared. */
    private String declaredIn = "";

    /** {@code true} if directly declared; {@code false} for lockfile-only transitive pins. */
    private boolean direct = true;

    /**
     * Pinned or resolved version from a lock file. {@code null} (omitted from JSON) when the
     * dependency is unpinned.
     */
    private String lockedVersion;

    /** Provenance hints: sources where this dependency was found — declared|lockfile|heuristic. */
    private List<String> prov = new ArrayList<>();
}
