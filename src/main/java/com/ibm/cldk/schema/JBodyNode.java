package com.ibm.cldk.schema;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * A node in a callable's {@code body}: at L1 only {@code call} nodes (an AST region for a method
 * invocation). {@code callee} is the sanctioned {@code null}-then-id slot — left {@code null} at L1
 * and backfilled with the callee's {@code can://} id when the L2 call graph resolves the site.
 * {@code arguments} are the local ids of the invocation's argument expressions.
 */
@Data
public class JBodyNode {
    private String kind;
    private Span span;
    /** Only meaningful on {@code call} nodes; {@code null} at L1 (backfilled at L2). */
    private String callee;
    private List<String> arguments = new ArrayList<>();
}
