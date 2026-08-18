package com.ibm.cldk.schema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * A v2 {@code callable} node (method or constructor). Its {@code id} is the containment path
 * {@code <type-id>/<signature>} (design decision D8). Per D1 there is no per-callable {@code code},
 * no flat {@code start_line}/{@code end_line}, and no {@code call_sites[]} — the source is a slice of
 * {@code module.source[span.bytes]} and call sites are {@code body} {@code call} nodes. Metrics and
 * cross-refs are nested (D3). {@code thrown_exceptions} become {@code error_channel}.
 */
@Data
public class JCallable {
    private String id;
    private String kind;
    private String signature;
    private Span span;
    private List<JParameter> parameters = new ArrayList<>();
    private String returnType;
    private List<String> errorChannel = new ArrayList<>();
    private List<String> modifiers = new ArrayList<>();
    private List<JDecorator> decorators = new ArrayList<>();
    private JMetrics metrics;
    private JRefs refs;

    /** L1 emits only {@code call} nodes here, keyed by ordinal id; the rest of the body arrives at L3. */
    private Map<String, JBodyNode> body = new LinkedHashMap<>();

    /** Local (method-body) classes, keyed by simple name — nesting encoded by containment (D4). */
    private Map<String, JType> types = new LinkedHashMap<>();
}
