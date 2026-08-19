package com.ibm.cldk.syntactic_analysis;

import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.ibm.cldk.schema.JModule;
import com.ibm.cldk.schema.V2Json;
import com.ibm.cldk.utils.Log;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;

/**
 * On-disk cache of built L1 modules, so an unchanged file is neither reparsed nor rebuilt on a
 * subsequent run. A module is reusable when its {@code content_hash} still matches the file on disk —
 * which is what that field exists for.
 *
 * <p>The cache is keyed by the same relative file key as {@code symbol_table}, and the whole file is
 * discarded when the analyzer version or the application name changes: both are baked into every
 * {@code can://} id, so a cached module built under different settings would contain wrong ids. A
 * missing, unreadable or stale cache is never fatal — it just means everything is rebuilt.
 */
public final class L1Cache {

    private L1Cache() {}

    private static final String FILE_NAME = "analysis_cache.json";

    /** What is persisted: the modules plus the settings they were built under. */
    @Data
    static class Envelope {
        private String schemaVersion;
        private String analyzerVersion;
        private String appName;
        private Map<String, JModule> modules = new LinkedHashMap<>();
    }

    public static Path fileIn(Path cacheDir) {
        return cacheDir.resolve(FILE_NAME);
    }

    /**
     * Load reusable modules, or an empty map when there is nothing usable. Never throws: a corrupt or
     * mismatched cache degrades to a full rebuild rather than failing the analysis.
     */
    public static Map<String, JModule> load(Path cacheDir, String appName, String analyzerVersion) {
        if (cacheDir == null) {
            return new LinkedHashMap<>();
        }
        Path path = fileIn(cacheDir);
        if (!Files.isRegularFile(path)) {
            return new LinkedHashMap<>();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Envelope envelope =
                    V2Json.compact().fromJson(reader, new TypeToken<Envelope>() {}.getType());
            if (envelope == null || envelope.getModules() == null) {
                return new LinkedHashMap<>();
            }
            boolean sameSettings = "2.0.0".equals(envelope.getSchemaVersion())
                    && java.util.Objects.equals(appName, envelope.getAppName())
                    && java.util.Objects.equals(analyzerVersion, envelope.getAnalyzerVersion());
            if (!sameSettings) {
                Log.debug("Ignoring cache built under different settings: " + path);
                return new LinkedHashMap<>();
            }
            return envelope.getModules();
        } catch (IOException | JsonSyntaxException e) {
            Log.debug("Ignoring unreadable cache " + path + ": " + e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    /** Persist the built modules. A write failure is reported but does not fail the analysis. */
    public static void save(
            Path cacheDir, String appName, String analyzerVersion, Map<String, JModule> modules) {
        if (cacheDir == null) {
            return;
        }
        Envelope envelope = new Envelope();
        envelope.setSchemaVersion("2.0.0");
        envelope.setAnalyzerVersion(analyzerVersion);
        envelope.setAppName(appName);
        envelope.setModules(modules);
        try {
            Files.createDirectories(cacheDir);
            try (Writer writer = Files.newBufferedWriter(fileIn(cacheDir), StandardCharsets.UTF_8)) {
                V2Json.compact().toJson(envelope, writer);
            }
        } catch (IOException e) {
            Log.warn("Could not write analysis cache to " + cacheDir + ": " + e.getMessage());
        }
    }
}
