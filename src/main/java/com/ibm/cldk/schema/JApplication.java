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

    /**
     * The repository-artifact inventory: non-source files keyed by repo-relative path, each carrying its
     * declared dependencies and config keys. Ungated (present at every analysis level, like the symbol
     * table). Left {@code null} when the inventory is empty so the key is omitted.
     */
    private Map<String, JArtifact> artifacts;
}
