package com.ibm.cldk.schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

/** The root {@code application} node of the v2 CPG. */
@Data
public class JApplication {
    private String id;
    private String kind = "application";
    private Map<String, JModule> symbolTable = new LinkedHashMap<>();

    /**
     * The L2 {@code call_graph}: one edge per {@code (src, dst)} callable pair. Left {@code null} at L1
     * so the key is omitted (absence means "no fact", never an empty array).
     */
    private List<JCallEdge> callGraph;

    /**
     * Out-of-project callables that call sites target, keyed by their {@code @external} can-id, so no
     * edge dangles. Left {@code null} at L1 so the key is omitted.
     */
    private Map<String, JExternalSymbol> externalSymbols;

    /** L4 {@code actual_in → formal_in} edges (global ordinals); null (absent) below level 4. */
    private List<JIdEdge> paramIn;

    /** L4 {@code formal_out → actual_out} edges (global ordinals); null (absent) below level 4. */
    private List<JIdEdge> paramOut;

    /**
     * The repository-artifact layer: build manifests, configuration files, and other non-source
     * artifacts indexed by repo-relative path. {@code null} (absent) when the layer produces no
     * artifacts.
     */
    private Map<String, JArtifact> artifacts;

    /**
     * Dependencies declared in the repository's artifacts — one entry per declaration, so a
     * coordinate declared in two manifests appears twice, each entry naming its own {@code
     * declaredIn}. Sorted by {@code (name, declaredIn)}. {@code null} (absent) when the layer
     * produces no dependencies.
     */
    private List<JDependency> dependencies;

    /**
     * Resolved config reads — code that reads a declared {@link JConfigKey}. L1 data like the
     * artifact layer it joins to: the literal tier needs no call graph, because a {@code call} body
     * node already carries {@code argument_expr} at L1 and an annotation is pure L1 data. Sorted by
     * {@code (src, dst)}. {@code null} (absent) when nothing was detected.
     */
    private List<JConfigUseEdge> configUses;

    /**
     * Detected config reads that closed on no declared key. Kept first-class so a read nobody can
     * trace stays as visible as one that resolves. Sorted by {@code (site, reason, key)}.
     * {@code null} (absent) when there are none.
     */
    private List<JConfigRead> configReadsUnresolved;

    /**
     * Resolved view dispatches (#259) — a {@code forward} / {@code include} / {@code sendRedirect}
     * call or a controller's view name, and the {@link JArtifact} it reaches. Sorted by
     * {@code (src, dst)}. {@code null} (absent) when nothing was detected.
     */
    private List<JViewDispatchEdge> viewDispatches;

    /**
     * Detected dispatches that closed on no artifact — a variable target, a servlet URL, or a view
     * name matching two templates. Sorted by {@code (site, reason, target)}. {@code null} (absent)
     * when there are none.
     */
    private List<JViewDispatchUnresolved> viewDispatchesUnresolved;

    /**
     * Coverage and failure record for the entrypoint pass. Unlike every other overlay on this node,
     * it is emitted <b>always, even when empty</b>: the pass under-approximates by design, so an
     * absent report and an empty one must not read the same.
     */
    private JEntrypointReport entrypointReport = new JEntrypointReport();
}
