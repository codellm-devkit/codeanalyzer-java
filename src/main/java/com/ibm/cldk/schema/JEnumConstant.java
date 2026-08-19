package com.ibm.cldk.schema;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * An enum constant declared on an {@code enum} type, with the argument expressions passed to the
 * enum's constructor (empty for a plain constant). A Java-specific addition to the keystone's type
 * node, which has no enum-member vocabulary (see cldk-devtools#40).
 */
@Data
public class JEnumConstant {
    private String name;
    private List<String> arguments = new ArrayList<>();
    private Span span;
    private List<JComment> comments = new ArrayList<>();
}
