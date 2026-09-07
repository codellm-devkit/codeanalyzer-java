package com.ibm.cldk.schema;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * A detected config read that did not close on any declared {@link JConfigKey} — first-class so a
 * read nobody can trace is as visible as one that resolves. Without this, a thin
 * {@code config_uses} list and a codebase that genuinely reads nothing would look identical.
 *
 * <p>{@code key} carries the decoded literal only when the read <em>was</em> a literal but matched
 * no declared key ({@code reason="undefined-key"} — routinely the case for a variable supplied by
 * the environment rather than a checked-in config file); it is absent when the key never closed on
 * a literal at all ({@code reason="non-literal"}).
 */
@Data
public class JConfigRead {
    /** Where the read happens — the same union of endpoint kinds as {@link JConfigUseEdge#getSrc()}. */
    private String site;

    /**
     * The {@code @external} can-id of what performed the read: the callee for a call-site read, and
     * the annotation type for an annotation read ({@code @Value} injection is a read, and the ghost
     * names what performed it — spec 2026-09-07). Always present, so the projected
     * {@code J_READS_CONFIG_UNRESOLVED} edge never dangles.
     */
    private String callee;

    /** The decoded literal, when there was one; absent for {@code reason="non-literal"}. */
    private String key;

    /** {@code undefined-key} (literal, no declared match) or {@code non-literal} (never closed). */
    private String reason;

    /** Every tier attempted before giving up. */
    private List<String> prov = new ArrayList<>();
}
