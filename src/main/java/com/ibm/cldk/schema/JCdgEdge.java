package com.ibm.cldk.schema;

import lombok.Data;

/**
 * One directed edge of a callable's L3 {@code cdg} (control-dependence) overlay: {@code dst} executes
 * only under the branch outcome at {@code src}. Both are body-node <em>local</em> ids
 * ({@code line:col} or an {@code @tag}) within the enclosing callable. Control dependence carries no
 * further attributes.
 */
@Data
public class JCdgEdge {
    private String src;
    private String dst;
}
