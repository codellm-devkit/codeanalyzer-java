package com.ibm.cldk.schema;

import lombok.Data;

/**
 * A v2 {@code dependency} node — one declared build-tool dependency, contained under the manifest
 * {@link JArtifact} that declares it. {@code name} is the ecosystem-native identity, verbatim: for
 * Maven/Gradle that is {@code groupId:artifactId} (the group is never dropped). {@code id} is the
 * containment path {@code <artifact-id>/<name>}.
 *
 * <p>{@code scope} is drawn from the shared closed vocabulary {@code runtime | development | test |
 * build | optional | unknown}, identical across the python/java/typescript analyzers so a downstream
 * consumer reads one dependency map. {@code unknown} is the catch-all — an unmapped native scope is
 * never dropped or guessed. The closed set is enforced by the JSON Schema conformance gate, not by a
 * Java enum (matching how {@link JType#kind} is a validated-by-convention string).
 */
@Data
public class JDependency {
    private String id;
    private String kind = "dependency";

    /** Ecosystem-native identity, verbatim ({@code org.springframework:spring-core} for Maven). */
    private String name;

    /** The version constraint as declared ({@code 6.1.4}, {@code [1.0,2.0)}); absent when unspecified. */
    private String versionSpec;

    /** The concrete version a lockfile pins, when one does; absent otherwise. */
    private String resolvedVersion;

    /** The dependency ecosystem ({@code maven} for both Maven and Gradle coordinates). */
    private String ecosystem;

    /** The shared {@code scope} vocabulary value. */
    private String scope;

    /** {@code true} for a direct declaration; {@code false} for a lockfile-only transitive dep. */
    private boolean direct;
}
