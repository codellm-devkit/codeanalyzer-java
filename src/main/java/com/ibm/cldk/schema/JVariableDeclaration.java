package com.ibm.cldk.schema;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * A local variable declared in a callable's body: {@code name}, the declared {@code type} as a
 * <em>resolved</em> qualified name (degrading to the AST spelling when unresolvable, D8), its
 * {@code initializer} expression text if any, and {@code span}.
 *
 * <p>Kept as a named list on the callable even though L3 will
 * also emit the declaration <em>statements</em> into {@code body} — the two answer different
 * questions ("what locals exist here" vs "what is the control flow").
 */
@Data
public class JVariableDeclaration {
    private String name;
    private String type;
    private String initializer;
    private Span span;
    private List<JComment> comments = new ArrayList<>();
}
