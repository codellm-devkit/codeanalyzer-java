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
}
