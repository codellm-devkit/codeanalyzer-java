package com.ibm.cldk.schema;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * A v2 {@code parameter} of a callable: {@code name}, declared {@code type}, byte-offset {@code span},
 * and structured {@code decorators} (e.g. {@code @RequestParam("q")}). The type is the
 * <em>resolved</em> qualified name, degrading to the AST spelling when the symbol solver cannot resolve
 * it (D8); dataflow {@code formal_in} vertices arrive later.
 */
@Data
public class JParameter {
    private String name;
    private String type;
    private Span span;
    private List<String> modifiers = new ArrayList<>();
    private List<JDecorator> decorators = new ArrayList<>();

    /** True for a varargs parameter ({@code String... names}); {@code type} stays the element type. */
    private boolean isVariadic;
}
