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
 *
 * <p>D1's "no flat line fields" applies to {@code code_start_line} too: it is exactly
 * {@code body_span.start[0]}, and a primitive sentinel would have to assert {@code -1} for an abstract
 * method — a fact where {@code body_span} is correctly absent altogether.
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

    /**
     * Declared type parameters, in declaration order — the {@code <T>} in {@code <T> T pick(T t)}.
     * A type variable resolves to its bare name, and {@code declaration} omits the clause, so these
     * are the only place the bound is recorded. The type parameters a generic <em>enclosing type</em>
     * declares are on that type, not repeated here.
     */
    private List<JTypeParameter> typeParameters = new ArrayList<>();

    /**
     * Span of the body block ({@code { ... }}) alone, absent when there is no body. The callable's own
     * {@code span} covers the whole declaration, so this is what a consumer slices to obtain just the
     * method body — the text v1 carried in its per-callable {@code code} field, without duplicating it.
     */
    private Span bodySpan;

    /**
     * Signature-with-parameter-names text, as v1 exposed it — {@code public int add(int a, int b)}.
     * Retained because it is useful verbatim in LLM prompts: {@code span.bytes} covers the declaration
     * <em>and</em> the body, so recovering just this line means re-parsing the slice.
     */
    private String declaration;

    /**
     * True for compiler-generated members the source does not declare (e.g. a default constructor).
     * Never set at L1, which reads declarations only; implicit members are discovered at L2, from the
     * call graph's view of the compiled code.
     */
    private boolean isImplicit;

    private List<JComment> comments = new ArrayList<>();
    /** True when a framework finder recognises this callable as an entrypoint (e.g. a Spring route). */
    private boolean isEntrypoint;

    private JMetrics metrics;
    private JRefs refs;

    private List<JVariableDeclaration> localVariables = new ArrayList<>();

    /** L1 emits only {@code call} nodes here, keyed by ordinal id; the rest of the body arrives at L3. */
    private Map<String, JBodyNode> body = new LinkedHashMap<>();

    /** L3 control-flow edges over this callable's body nodes; null (absent) below level 3. */
    private List<JCfgEdge> cfg;
    /** L3 control-dependence edges over this callable's body nodes; null (absent) below level 3. */
    private List<JCdgEdge> cdg;
    /** L3 data-dependence edges (prov {@code ssa}) over this callable's body nodes; null below level 3. */
    private List<JDdgEdge> ddg;

    /** L4 {@code actual_in → actual_out} summary edges (local ids); null (absent) below level 4. */
    private List<JIdEdge> summary;

    /** Local (method-body) classes, keyed by simple name — nesting encoded by containment (D4). */
    private Map<String, JType> types = new LinkedHashMap<>();
}
