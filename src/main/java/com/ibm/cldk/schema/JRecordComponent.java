package com.ibm.cldk.schema;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * A component of a {@code record} type — its name, resolved {@code type}, modifiers and structured
 * decorators. A Java-specific addition to the keystone's type node (see cldk-devtools#40).
 *
 * <p>v1 also carried a {@code defaultValue} derived from compact-constructor assignments; that is
 * dropped deliberately — Java record components have no default values, so the field was misleading.
 */
@Data
public class JRecordComponent {
    private String name;
    private String type;
    private Span span;
    private List<String> modifiers = new ArrayList<>();
    private List<JDecorator> decorators = new ArrayList<>();
    private List<JComment> comments = new ArrayList<>();
    private boolean isVariadic;
}
