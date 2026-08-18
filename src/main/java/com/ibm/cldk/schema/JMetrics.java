package com.ibm.cldk.schema;

import lombok.Data;

/**
 * Per-callable metrics, nested rather than flattened onto the callable (design decision D3) so the
 * family can grow without churning the callable's top-level shape. At L1: {@code cyclomatic}.
 */
@Data
public class JMetrics {
    private int cyclomatic;
}
