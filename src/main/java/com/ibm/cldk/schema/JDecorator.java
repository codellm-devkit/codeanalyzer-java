package com.ibm.cldk.schema;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * A structured annotation/decorator: {@code name} + argument expressions + {@code span}. Java
 * annotations carry meaningful arguments (e.g. {@code @RequestMapping("/x")}), so v2 keeps them
 * structured rather than as flat strings (design decision D2).
 */
@Data
public class JDecorator {
    private String name;
    private List<String> args = new ArrayList<>();
    private Span span;
}
