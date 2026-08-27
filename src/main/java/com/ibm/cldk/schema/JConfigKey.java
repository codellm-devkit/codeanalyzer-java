package com.ibm.cldk.schema;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * A v2 {@code config_key} node — one normalized key/value fact from a structured config file,
 * contained under the {@link JArtifact} that defines it. {@code key} is the canonical <em>dotted</em>
 * key ({@code services.payments.url}); a flat {@code .properties} key and a nested {@code .yml} path
 * share one key space, so both flatten to the same dotted form. {@code id} is the containment path
 * {@code <artifact-id>/<key>}.
 *
 * <p>Config parsing is a pure overlay: a document whose structure cannot be understood must never
 * prevent its underlying {@link JArtifact} (with its raw {@code text}) from being emitted.
 */
@Data
public class JConfigKey {
    private String id;
    private String kind = "config_key";

    /** Canonical dotted key ({@code spring.datasource.url}). */
    private String key;

    /**
     * The shared key-space namespace ({@code env}, {@code spring}, …) a config use joins on; absent for
     * a plain in-file key with no namespace.
     */
    private String namespace;

    /** The defined value when present; the boxed literal (String/Number/Boolean) as parsed. */
    private Object value;

    /**
     * Placeholder references preserved where recognizable — e.g. {@code ["env:PAYMENT_HOST"]} for a
     * value of {@code ${PAYMENT_HOST}}. Empty when the value holds no recognizable reference.
     */
    private List<String> references = new ArrayList<>();

    /** Where the key is defined in source; absent when the format carries no position. */
    private Span span;
}
