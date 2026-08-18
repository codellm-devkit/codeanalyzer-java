package com.ibm.cldk.schema;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * A v2 {@code parameter} of a callable: {@code name}, syntactic declared {@code type}, byte-offset
 * {@code span}, and structured {@code decorators} (e.g. {@code @RequestParam("q")}). At L1 the type
 * is the AST spelling (no cross-module resolution); dataflow {@code formal_in} vertices arrive later.
 */
@Data
public class JParameter {
    private String name;
    private String type;
    private Span span;
    private List<JDecorator> decorators = new ArrayList<>();

    /** True for a varargs parameter ({@code String... names}); {@code type} stays the element type. */
    private boolean isVariadic;
}
