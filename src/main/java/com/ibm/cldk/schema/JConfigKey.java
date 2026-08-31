package com.ibm.cldk.schema;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * A configuration key defined or referenced in an artifact — a property in a {@code .properties}
 * file, a YAML key path, an XML element, an environment variable, or a Dockerfile argument.
 * {@code id} is the canonical {@code can://artifact/<app>/<path>@key/<dotted.key>} reference, nesting
 * under the artifact's id so the key is discoverable and addressable.
 *
 * <p>{@code namespace} is free-vocabulary (properties, yaml, xml, env, dockerfile) and identifies
 * the config language. {@code references} are the raw tokens (e.g., environment variable names,
 * property placeholders) that this key references in order, deduplicated.
 */
@Data
public class JConfigKey {
    /** Canonical id: {@code <artifact-id>@key/<dotted.key>}. */
    private String id;

    /** Dotted key path (e.g., {@code server.port}, {@code logging.level.root}). */
    private String key;

    /** Config language/namespace: properties|yaml|xml|env|dockerfile. */
    private String namespace;

    /** Configuration value when captured via {@code --artifact-text}, {@code null} otherwise. */
    private String value;

    /** Location in source, best-effort; {@code null} is acceptable. */
    private Span span;

    /** Raw tokens (e.g., env var names, property placeholders) this key references, deduplicated. */
    private List<String> references = new ArrayList<>();
}
