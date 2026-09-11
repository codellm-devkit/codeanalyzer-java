package com.ibm.cldk.schema;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * One resolved view dispatch: a body node that hands the request to a view template — a
 * {@code forward} / {@code include} / {@code sendRedirect} call, or a {@code return} in a controller
 * — and the {@link JArtifact} it reaches (#259, spec 2026-09-11 D2). Application-scope like
 * {@link JConfigUseEdge}: the endpoints span code and artifacts.
 */
@Data
public class JViewDispatchEdge {
    /** The dispatching body node's global ordinal id, {@code <callable-id>@<local-id>}. */
    private String src;

    /** The {@link JArtifact} id of the view reached. */
    private String dst;

    /** Mechanism: {@code forward | include | redirect | view-name | navigation}. */
    private String via;

    /** The tier that closed the target: {@code literal} at L1, {@code dataflow} at L3/L4. */
    private List<String> prov = new ArrayList<>();
}
