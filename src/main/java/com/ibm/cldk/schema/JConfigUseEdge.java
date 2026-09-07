package com.ibm.cldk.schema;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * One resolved config read: code that reads a declared {@link JConfigKey}. Application-scope,
 * mirroring {@code param_in}/{@code param_out} — the endpoints span callables and artifacts, so the
 * edge has no single owning callable.
 *
 * <p>{@code src} is a <em>union</em> of endpoint kinds, which is where this diverges from
 * codeanalyzer-python's call-site-only {@code PY_USES_CONFIG} (spec 2026-09-07, D1). Python's config
 * reads are calls; Java's dominant idiom is an annotation, which has no call site to anchor on:
 *
 * <ul>
 *   <li>a call-site read ({@code System.getenv("X")}) → the {@code call} body node's global ordinal
 *       id, {@code <callable-id>@<local-id>};
 *   <li>{@code @Value("${x}")} on a field → the field's id;
 *   <li>{@code @Value("${x}")} on a parameter → the owning callable's id;
 *   <li>{@code @ConfigurationProperties("x")} on a class → the type's id.
 * </ul>
 *
 * <p>{@code dst} is always a {@link JConfigKey} id. Superset-monotonic across levels: the literal
 * tier runs at L1 and the L3/L4 dataflow tier only adds, so
 * {@code config_uses(-a 1) ⊆ config_uses(-a 4)}.
 */
@Data
public class JConfigUseEdge {
    /** Body-node ordinal id, field id, callable id, or type id — see the class javadoc. */
    private String src;

    /** The {@link JConfigKey} id this read closed on. */
    private String dst;

    /** Tiers that produced this edge: {@code literal} at L1, {@code dataflow} at L3/L4. */
    private List<String> prov = new ArrayList<>();
}
