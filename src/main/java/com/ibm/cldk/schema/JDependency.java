package com.ibm.cldk.schema;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * A declared dependency from a build manifest or lock file, representing one pinned package in an
 * ecosystem. {@code group} (Maven {@code groupId}) is additive over the reference analyzer
 * (codeanalyzer-python), which has no analogue since PyPI names are single-segment; in Maven each
 * coordinate is split into a {@code group} and {@code name} ({@code artifactId}).
 *
 * <p>{@code ecosystem} and {@code kind} are free-vocabulary, matching the reference implementation:
 * ecosystems include {@code maven}, {@code npm}, {@code gradle}, {@code pypi}, {@code golang};
 * kinds include {@code runtime}, {@code dev}, {@code optional}, {@code build}.
 *
 * <p>{@code lockedVersion} is {@code null} (omitted from JSON) when the dependency is unpinned —
 * recorded only when found in a lock file or package manager.
 */
@Data
public class JDependency {
    /** Maven {@code groupId} (additive; PyPI names are single-segment and have no analogue). */
    private String group;

    /** Maven {@code artifactId} (the package name). */
    private String name;

    /** Package ecosystem: maven|npm|gradle|pypi|golang, etc. Defaults to maven. */
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
