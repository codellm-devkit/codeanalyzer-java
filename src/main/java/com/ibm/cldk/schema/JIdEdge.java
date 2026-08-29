package com.ibm.cldk.schema;

import lombok.Data;

/**
 * An id-to-id edge with no payload — the shape of {@code param_in}/{@code param_out} (application
 * scope, global-ordinal endpoints) and {@code summary} (callable scope, local endpoints).
 */
@Data
public class JIdEdge {
    private String src;
    private String dst;
}
