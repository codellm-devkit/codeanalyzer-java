package com.ibm.cldk.schema;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * One directed edge of the L2 {@code call_graph}: a caller callable ({@code src}) reaching a callee
 * ({@code dst}), both durable {@code can://} ids. There is exactly one edge per {@code (src, dst)}
 * pair (D17): {@code prov} carries the set-union of the analyses that attest it (sorted
 * alphabetically), and {@code weight} is the number of call sites in {@code src} that reach
 * {@code dst}. Self-edges (direct recursion) are kept.
 */
@Data
public class JCallEdge {
    private String src;
    private String dst;
    private List<String> prov = new ArrayList<>();
    private int weight;
}
