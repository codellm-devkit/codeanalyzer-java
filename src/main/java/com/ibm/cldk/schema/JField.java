package com.ibm.cldk.schema;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * A v2 {@code field} node — one per declared variable, so {@code int a, b;} yields two fields. The
 * {@code id} is the containment path {@code <type-id>/<name>}; {@code type} is the AST spelling
 * (syntactic — no resolution at L1). {@code span} covers the whole field declaration text.
 */
@Data
public class JField {
    private String id;
    private String name;
    private String type;
    private Span span;
    private List<String> modifiers = new ArrayList<>();
    private List<JDecorator> decorators = new ArrayList<>();
}
