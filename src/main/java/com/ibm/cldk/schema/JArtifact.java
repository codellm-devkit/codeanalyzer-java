package com.ibm.cldk.schema;

import java.util.Map;
import lombok.Data;

/**
 * A v2 {@code artifact} node — one non-source repository file, inventoried so a downstream consumer
 * can cite its contents without re-reading the working tree. Contained directly under
 * {@code application} as a named map keyed by repo-relative path; {@code id} is
 * {@code <app-id>/@artifact/<rel-path>}.
 *
 * <p>{@code artifactKind} classifies the file into a closed set — {@code build_manifest},
 * {@code dependency_lockfile}, {@code configuration}, {@code deployment_manifest}, {@code container},
 * {@code infrastructure}, {@code ci}, {@code script}, {@code documentation}, {@code data},
 * {@code other} — where {@code other} is the catch-all: coverage over perfect classification, so a
 * file is never dropped for lack of a parser. The closed set is enforced by the JSON Schema
 * conformance gate rather than a Java enum.
 *
 * <p>Text capture: decodable bytes populate {@code text} with {@code textEncoding}; binary files are
 * inventoried without {@code text}; a file past the capture cap holds a leading prefix with
 * {@code textTruncated} set. {@code path}, {@code contentHash}, and {@code sizeBytes} are always
 * present, so a truncated or binary artifact still dereferences to its source. Capture is toggled by
 * {@code --artifact-text}/{@code --no-artifact-text} and only affects the {@code text} payload, never
 * the inventory.
 */
@Data
public class JArtifact {
    private String id;
    private String kind = "artifact";

    /** The closed-set classification; {@code other} when nothing else fits. */
    private String artifactKind;

    /** Repo-relative path, matching the symbol-table file-key convention. */
    private String path;

    /** The document format when recognizable ({@code xml}, {@code yaml}, {@code properties}, …). */
    private String format;

    /** The producing subsystem, when known. */
    private String source;

    /** SHA-256 hex digest of the raw bytes; always present. */
    private String contentHash;

    /** Size of the raw bytes; always present. */
    private long sizeBytes;

    /** Verbatim contents when captured and decodable; absent for binary or when capture is off. */
    private String text;

    /** Text encoding of {@code text} ({@code utf-8}); absent when {@code text} is absent. */
    private String textEncoding;

    /** {@code true} when the file exceeded the cap and {@code text} holds only a leading prefix. */
    private boolean textTruncated;

    /**
     * Dependencies declared by this artifact (manifests only), keyed by ecosystem-native name. Left
     * {@code null} when there are none so the key is omitted — absence means "no fact".
     */
    private Map<String, JDependency> dependencies;

    /**
     * Config keys defined by this artifact (config files only), keyed by dotted key. Left {@code null}
     * when there are none so the key is omitted.
     */
    private Map<String, JConfigKey> configKeys;
}
