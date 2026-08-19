package com.ibm.cldk.schema;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * An enum constant declared on an {@code enum} type, with the argument expressions passed to the
 * enum's constructor (empty for a plain constant).
 *
 * <p>The canonical schema has no enum-member vocabulary, so this is an additive Java field; it exists
 * because dropping it would lose information the v1 symbol table carried.
 */
@Data
public class JEnumConstant {
    private String name;
    private List<String> arguments = new ArrayList<>();
    private Span span;
    private List<JComment> comments = new ArrayList<>();
}
