package com.ibm.cldk.schema;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * One directed edge of a callable's L3 {@code ddg} (data-dependence) overlay: a definition at
 * {@code src} may reach a use at {@code dst} of the access path {@code var}. Both endpoints are
 * body-node <em>local</em> ids ({@code line:col} or an {@code @tag}) within the enclosing callable.
 * {@code var} is the k-limited access path ({@code base(.field|[*])*}); {@code prov} is the set of
 * analyses attesting the edge — at L3 the closed enum {@code [ssa]} (syntactic), which L4 extends
 * with {@code points-to}. Multiple ddg edges may touch one node; they are distinguished by
 * {@code var}, not by endpoint identity.
 */
@Data
public class JDdgEdge {
    private String src;
    private String dst;
    private String var;
    private List<String> prov = new ArrayList<>();
}
