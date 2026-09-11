package com.ibm.cldk.schema;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * A detected view dispatch that closed on no artifact — first-class so a page nobody can trace is as
 * visible as one that resolves (#259, spec 2026-09-11 D4). Mirrors {@link JConfigRead}.
 */
@Data
public class JViewDispatchUnresolved {
    /** The dispatching body node's global ordinal id. */
    private String site;

    /** The {@code @external} can-id of the dispatching callee. */
    private String callee;

    /** The decoded literal target when there was one; absent for {@code reason="non-literal"}. */
    private String target;

    /** Mechanism, as on {@link JViewDispatchEdge#getVia()}. */
    private String via;

    /** {@code non-literal}, {@code no-such-artifact}, or {@code ambiguous}. */
    private String reason;

    /** Every tier attempted before giving up. */
    private List<String> prov = new ArrayList<>();
}
