package com.ibm.cldk.schema;

import lombok.Data;

/**
 * An id-to-id edge — the shape of {@code param_in}/{@code param_out} (application scope,
 * global-ordinal endpoints) and {@code summary} (callable scope, local endpoints).
 *
 * <p>{@code var} is the callee-side formal's variable on a {@code param_in}/{@code param_out}
 * edge — the parameter name, or {@code $ret} for the return port — set on every such edge. The
 * Neo4j catalog declared it on {@code J_PARAM_IN}/{@code J_PARAM_OUT} while nothing wrote it, so
 * a Cypher predicate on {@code r.var} went three-valued across every call boundary
 * (codeanalyzer-python#195). Absent (never null-serialized) on {@code summary} edges.
 */
@Data
public class JIdEdge {
    private String src;
    private String dst;
    private String var;
}
