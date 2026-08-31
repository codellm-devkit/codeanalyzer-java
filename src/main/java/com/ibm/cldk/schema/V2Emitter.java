package com.ibm.cldk.schema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Assembles the canonical schema v2 envelope from per-file {@link JModule}s produced by
 * {@code L1Extractor}/{@code ModuleBuilder}. Pure wiring — the tree is built by those builders straight
 * from the JavaParser AST (so spans, structured decorators, and source come from where that data
 * actually lives); this class only wraps the modules into the {@code application} + envelope.
 */
public final class V2Emitter {

    private V2Emitter() {}

    /** Wrap already-built modules (keyed by relative file key) into the v2 envelope. */
    public static Analysis emit(String appName, int maxLevel, Map<String, JModule> modules) {
        return emit(appName, maxLevel, modules, null);
    }

    /** As above, stamping the analyzer version into the envelope manifest. */
    public static Analysis emit(
            String appName, int maxLevel, Map<String, JModule> modules, String analyzerVersion) {
        return emit(appName, maxLevel, modules, analyzerVersion, null, null);
    }

    /**
     * As above, additionally attaching L2's application-scope overlays. {@code callGraph} and
     * {@code externalSymbols} are set only when non-{@code null} (L2+), so an L1 envelope omits both
     * keys — absence means "no fact", not an empty collection.
     */
    public static Analysis emit(
            String appName,
            int maxLevel,
            Map<String, JModule> modules,
            String analyzerVersion,
            List<JCallEdge> callGraph,
            Map<String, JExternalSymbol> externalSymbols) {
        return emit(appName, maxLevel, modules, analyzerVersion, callGraph, externalSymbols, null, null);
    }

    public static Analysis emit(
            String appName,
            int maxLevel,
            Map<String, JModule> modules,
            String analyzerVersion,
            List<JCallEdge> callGraph,
            Map<String, JExternalSymbol> externalSymbols,
            List<JIdEdge> paramIn,
            List<JIdEdge> paramOut) {
        return emit(appName, maxLevel, modules, analyzerVersion, callGraph, externalSymbols,
                paramIn, paramOut, null, null);
    }

    /**
     * As above, additionally attaching the repository-artifact layer: build manifests, config files,
     * and declared dependencies. {@code artifacts} and {@code dependencies} are set only when
     * non-{@code null} and non-empty, the same "absence means no fact" rule every other
     * application-scope overlay here follows. Unlike the others, this layer is L1 data — a caller
     * passes it at every analysis level, not only when a level-gated overlay is available.
     */
    public static Analysis emit(
            String appName,
            int maxLevel,
            Map<String, JModule> modules,
            String analyzerVersion,
            List<JCallEdge> callGraph,
            Map<String, JExternalSymbol> externalSymbols,
            List<JIdEdge> paramIn,
            List<JIdEdge> paramOut,
            Map<String, JArtifact> artifacts,
            List<JDependency> dependencies) {
        JApplication application = new JApplication();
        application.setId(CanId.applicationId(appName));

        // Sort file keys so output is deterministic (the -j gate).
        Map<String, JModule> sorted = new LinkedHashMap<>();
        for (String fileKey : new TreeSet<>(modules.keySet())) {
            sorted.put(fileKey, modules.get(fileKey));
        }
        application.setSymbolTable(sorted);
        // Absence means "no fact": an empty overlay is omitted, not emitted as [] / {}. This is what
        // lets --external-calls off match v1 (no external_symbols key at all) rather than an empty map.
        if (callGraph != null && !callGraph.isEmpty()) {
            application.setCallGraph(callGraph);
        }
        if (externalSymbols != null && !externalSymbols.isEmpty()) {
            application.setExternalSymbols(externalSymbols);
        }
        if (paramIn != null && !paramIn.isEmpty()) {
            application.setParamIn(paramIn);
        }
        if (paramOut != null && !paramOut.isEmpty()) {
            application.setParamOut(paramOut);
        }
        if (artifacts != null && !artifacts.isEmpty()) {
            application.setArtifacts(artifacts);
        }
        if (dependencies != null && !dependencies.isEmpty()) {
            application.setDependencies(dependencies);
        }

        Analysis analysis = new Analysis();
        analysis.setSchemaVersion("2.0.0");
        analysis.setLanguage("java");
        analysis.setMaxLevel(maxLevel);
        if (analyzerVersion != null) {
            JAnalyzerInfo analyzer = new JAnalyzerInfo();
            analyzer.setVersion(analyzerVersion);
            analysis.setAnalyzer(analyzer);
        }
        analysis.setApplication(application);
        return analysis;
    }
}
