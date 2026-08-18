package com.ibm.cldk.schema;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * Assembles the canonical schema v2 envelope from per-file {@link JModule}s produced by
 * {@link V2SymbolTableBuilder}. Pure wiring — the tree is built by the L1 builder straight from the
 * JavaParser AST (so spans, structured decorators, and source come from where that data actually
 * lives); this class only wraps the modules into the {@code application} + envelope.
 */
public final class V2Emitter {

    private V2Emitter() {}

    /** Wrap already-built modules (keyed by relative file key) into the v2 envelope. */
    public static Analysis emit(String appName, int maxLevel, Map<String, JModule> modules) {
        JApplication application = new JApplication();
        application.setId(CanId.applicationId(appName));

        // Sort file keys so output is deterministic (the -j gate).
        Map<String, JModule> sorted = new LinkedHashMap<>();
        for (String fileKey : new TreeSet<>(modules.keySet())) {
            sorted.put(fileKey, modules.get(fileKey));
        }
        application.setSymbolTable(sorted);

        Analysis analysis = new Analysis();
        analysis.setSchemaVersion("2.0.0");
        analysis.setLanguage("java");
        analysis.setMaxLevel(maxLevel);
        analysis.setApplication(application);
        return analysis;
    }
}
