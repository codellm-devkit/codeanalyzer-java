package com.ibm.cldk.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * A v2 {@code type} node. The specific flavor is the {@code kind} value
 * ({@code class}|{@code interface}|{@code enum}|{@code record}|{@code annotation}) rather than a
 * pile of {@code is_*} booleans (design decision D4).
 */
@Data
public class JType {
    private String id;
    private String kind;
    private Span span;
    private List<JComment> comments = new ArrayList<>();
    private List<String> modifiers = new ArrayList<>();
    private List<String> baseTypes = new ArrayList<>();
    private List<String> interfaces = new ArrayList<>();
    private List<JDecorator> decorators = new ArrayList<>();

    /** True when a framework finder recognises this type as an entrypoint (e.g. a Spring controller). */
    private boolean isEntrypointClass;

    /** Enum constants, in declaration order — present only on {@code enum} types. */
    private List<JEnumConstant> enumConstants = new ArrayList<>();

    /** Record components, in declaration order — present only on {@code record} types. */
    private List<JRecordComponent> recordComponents = new ArrayList<>();

    /** Fields declared in this type, keyed by simple name (one entry per declared variable). */
    private Map<String, JField> fields = new LinkedHashMap<>();

    /** Methods and constructors, keyed by type-erasure signature (keystone containment name). */
    private Map<String, JCallable> callables = new LinkedHashMap<>();

    /**
     * Member/inner types declared directly inside this one, keyed by simple name. Nesting and
     * parent are encoded by this containment position (and the {@code can://…/Outer/Inner} id path);
     * local classes declared in method bodies live under the enclosing callable, not here.
     */
    private Map<String, JType> types = new LinkedHashMap<>();
}
