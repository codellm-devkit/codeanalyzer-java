package com.ibm.cldk.schema;

import lombok.Data;

/**
 * One directed edge of a callable's L3 {@code cfg} (control-flow) overlay. {@code src} and
 * {@code dst} are body-node <em>local</em> ids ({@code line:col} or an {@code @tag} such as
 * {@code @entry}/{@code @exit}) within the enclosing callable — not {@code can://} ids, because the
 * overlay is intra-callable. {@code kind} is one of the closed set
 * {@code fallthrough|true|false|switch_case|loop_back|exception|return|break|continue}.
 */
@Data
public class JCfgEdge {
    private String src;
    private String dst;
    private String kind;
}
