/*
Copyright IBM Corporation 2023, 2024

Licensed under the Apache Public License 2.0, Version 2.0 (the "License");
you may not use this file except in compliance with the License.

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */
package com.ibm.cldk;

import com.github.javaparser.Problem;
import com.google.common.reflect.TypeToken;
import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ibm.cldk.entities.JavaCompilationUnit;
import com.ibm.cldk.neo4j.BoltConfig;
import com.ibm.cldk.neo4j.Neo4jEmitter;
import com.ibm.cldk.schema.Analysis;
import com.ibm.cldk.schema.JModule;
import com.ibm.cldk.schema.V2Emitter;
import com.ibm.cldk.schema.V2Json;
import com.ibm.cldk.syntactic_analysis.L1Cache;
import com.ibm.cldk.syntactic_analysis.L1Extractor;
import com.ibm.cldk.syntactic_analysis.L2CallGraph;
import com.ibm.cldk.syntactic_analysis.dataflow.SdgVertices;
import com.ibm.cldk.syntactic_analysis.dataflow.SummaryPass;
import com.ibm.cldk.utils.BuildProject;
import com.ibm.cldk.utils.Log;
import com.ibm.cldk.wala.WalaAnalysis;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.commons.lang3.tuple.Pair;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Spec;

class VersionProvider implements CommandLine.IVersionProvider {

    public String[] getVersion() throws Exception {
        String version = getClass().getPackage().getImplementationVersion();
        return new String[] { version != null ? version : "unknown" };
    }
}

/**
 * The type Code analyzer.
 */
@Command(name = "codeanalyzer", mixinStandardHelpOptions = true, sortOptions = false, versionProvider = VersionProvider.class, description = "Analyze java application.")
public class CodeAnalyzer implements Runnable {

    @Option(names = { "-i", "--input" }, description = "Path to the project root directory.")
    private static String input;

    @Option(names = { "-t",
            "--target-files" }, description = "Paths to files to be analyzed from the input application.")
    private static List<String> targetFiles;

    @Option(names = { "-s",
            "--source-analysis" }, description = "Analyze a single string of java source code instead the project.")
    private static String sourceAnalysis;

    @Option(names = { "-o",
            "--output" }, description = "Destination directory to save the output graphs. By default, the SDG formatted as a JSON will be printed to the console.")
    private static String output;

    @Option(names = { "-b", "--build-cmd" }, description = "Custom build command. Defaults to auto build.")
    private static String build;

    @Option(names = {
            "--no-build" }, description = "Do not build your application. Use this option if you have already built your application.")
    private static boolean noBuild = false;

    @Option(names = { "--no-clean-dependencies" }, description = "Do not attempt to auto-clean dependencies")
    public static boolean noCleanDependencies = false;

    @Option(names = { "-f",
            "--project-root-path" }, description = "Path to the root pom.xml/build.gradle file of the project.")
    public static String projectRootPom;

    @Option(names = { "-a",
            "--analysis-level" }, description = "Level of analysis to perform. Options: 1 (for just symbol table); 2 (for call graph); 3 (for intraprocedural dataflow: cfg/cdg/ddg); 4 (adds the interprocedural SDG). Default: 1")
    public static int analysisLevel = 1;

    @Option(names = { "--include-test-classes" }, hidden = true, description = "Print logs to console.")
    public static boolean includeTestClasses = false;

    @Option(names = { "-v", "--verbose" }, description = "Print logs to console.")
    private static boolean verbose = false;

    @Option(names = {
            "--emit" }, description = "Output target: json (analysis.json, default) | neo4j (graph.cypher or live Bolt push) | schema (the Neo4j schema.neo4j.json contract).")
    private static String emit = "json";

    @Option(names = {
            "--app-name" }, description = "Logical application name for the graph :JApplication anchor (default: input dir name).")
    private static String appName;

    @Option(names = {
            "--neo4j-uri" }, description = "Push the graph to a live Neo4j over Bolt (incremental); omit to write graph.cypher. Falls back to the NEO4J_URI environment variable.")
    private static String neo4jUri;

    @Option(names = { "--neo4j-user" }, description = "Neo4j username (env: NEO4J_USERNAME, default: neo4j).")
    private static String neo4jUser;

    @Option(names = { "--neo4j-password" }, description = "Neo4j password (env: NEO4J_PASSWORD, default: neo4j).")
    private static String neo4jPassword;

    @Option(names = { "--neo4j-database" }, description = "Neo4j database name (env: NEO4J_DATABASE, default: server default).")
    private static String neo4jDatabase;

    @Option(names = {
            "--schema" }, description = "Output schema: v2 (canonical CPG, default) | v1 (legacy).")
    // Deliberately an INSTANCE field: the pre-existing options on this class are static, which leaks
    // values between CommandLine instances in the same JVM. New flags do not add to that.
    private String schema = "v2";

    @Option(names = {"-c",
            "--cache-dir" }, description = "Directory holding the incremental analysis cache. When set, "
                    + "unchanged files are reused from analysis_cache.json instead of being reparsed.")
    private String cacheDir;

    @Option(names = {
            "--eager" }, description = "Ignore any cached modules and rebuild everything (default: lazy).")
    private boolean eager = false;

    @Option(names = {
            "--no-rta" }, description = "Skip the WALA RTA overlay at --schema v2 --analysis-level 2, "
                    + "emitting declared-only call edges without building the application. Does not "
                    + "suppress the L4 WALA build at --analysis-level 4: the semantic ddg still needs it.")
    private boolean noRta = false;

    @Option(names = {
            "--external-calls" }, description = "Home out-of-project call targets as external_symbols at "
                    + "--schema v2 --analysis-level 2. Off by default, matching v1's application-only call "
                    + "graph; when on, edges to library/JDK targets are emitted so no edge dangles.")
    private boolean externalCalls = false;

    @Option(names = {
            "--l3-engine" }, description = "L3 dataflow engine at --schema v2 --analysis-level 3: "
                    + "ast (default; source-based, no build) or wala (post-build; requires compiled "
                    + "class files — uses WALA RTA + PDG for cfg/cdg/ddg).")
    private String l3Engine = "ast";

    @Option(names = {
            "--precision" }, description = "L4 points-to precision: rta (default; reuses the L2 "
                    + "call-graph build) | 0-cfa | 0-1-cfa (rebuild the call graph for L4).")
    public static String precision = "rta";

    @Option(names = {
            "--graph-field-depth" }, description = "DDG access-path bound k at --analysis-level 3 (default 3).")
    private int graphFieldDepth = 3;

    /** Handle used to report flag-validation errors as clean, non-zero picocli failures. */
    @Spec
    private CommandSpec spec;

    private static final String outputFileName = "analysis.json";

    public static Gson gson = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .serializeNulls() // Fix for issue #108
            .disableHtmlEscaping()
            .create();

    /**
     * The entry point of application.
     *
     * @param args the input arguments
     */
    public static void main(String[] args) {
        int exitCode = new CommandLine(new CodeAnalyzer()).execute(args);
        System.exit(exitCode);
    }

    /** First non-null, non-blank value among the candidates, or null if none qualify. */
    private static String firstNonEmpty(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.trim().isEmpty()) {
                return c;
            }
        }
        return null;
    }

    @Override
    public void run() {
        // Set log level based on quiet option
        Log.setVerbosity(verbose);
        try {
            analyze();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void analyze() throws Exception {

        // The Neo4j schema contract is a static artifact — no project analysis required.
        if ("schema".equalsIgnoreCase(emit)) {
            Neo4jEmitter.emitSchema(output);
            return;
        }

        if (isV2Schema()) {
            analyzeV2();
            return;
        }

        JsonObject combinedJsonObject = new JsonObject();
        Map<String, JavaCompilationUnit> symbolTable;
        projectRootPom = projectRootPom == null ? input : projectRootPom;
        // First of all if, sourceAnalysis is provided, we will analyze the source code
        // instead of the project.
        if (sourceAnalysis != null) {
            // Construct symbol table for source code
            Log.debug("Single file analysis.");
            Pair<Map<String, JavaCompilationUnit>, Map<String, List<Problem>>> symbolTableExtractionResult = SymbolTable
                    .extractSingle(sourceAnalysis);
            symbolTable = symbolTableExtractionResult.getLeft();
        } else {
            // download library dependencies of project for type resolution
            String dependencies = null;
            try {
                if (BuildProject.downloadLibraryDependencies(input, projectRootPom)) {
                    dependencies = String.valueOf(BuildProject.libDownloadPath);
                } else {
                    Log.warn("Failed to download library dependencies of project");
                }
            } catch (IllegalStateException illegalStateException) {
                Log.warn("Failed to download library dependencies of project");
            }

            boolean analysisFileExists = output != null
                    && Files.exists(Paths.get(output + File.separator + outputFileName));

            // if target files are specified, compute symbol table information for the given
            // files
            if (targetFiles != null) {
                Log.info(targetFiles.size() + "target files specified for analysis: " + targetFiles);

                // if target files specified for analysis level 2, downgrade to analysis level 1
                if (analysisLevel > 1) {
                    Log.warn("Incremental analysis is supported at analysis level 1 only; "
                            + "performing analysis level 1 for target files");
                    analysisLevel = 1;
                }

                // Previous code was pointing to toList, which has been introduced in Java 16
                // symbolTable = SymbolTable.extract(Paths.get(input),
                // targetFiles.stream().map(Paths::get).toList()).getLeft();
                // extract symbol table for the specified files
                symbolTable = SymbolTable
                        .extract(Paths.get(input), targetFiles.stream().map(Paths::get).collect(Collectors.toList()))
                        .getLeft();

                // if analysis file exists, update it with new symbol table information for the
                // specified fiels
                if (analysisFileExists) {
                    // read symbol table information from existing analysis file
                    Map<String, JavaCompilationUnit> existingSymbolTable = readSymbolTableFromFile(
                            new File(output, outputFileName));
                    if (existingSymbolTable != null) {
                        // for each file, tag its symbol table information as "updated" and update
                        // existing symbol table
                        for (String targetFile : targetFiles) {
                            String targetPathAbs = Paths.get(targetFile).toAbsolutePath().toString();
                            JavaCompilationUnit javaCompilationUnit = symbolTable.get(targetPathAbs);
                            javaCompilationUnit.setModified(true);
                            existingSymbolTable.put(targetPathAbs, javaCompilationUnit);
                        }
                    }
                    symbolTable = existingSymbolTable;
                }
            } else {
                // construct symbol table for project, write parse problems to file in output
                // directory if specified
                Pair<Map<String, JavaCompilationUnit>, Map<String, List<Problem>>> symbolTableExtractionResult = SymbolTable
                        .extractAll(Paths.get(input));

                symbolTable = symbolTableExtractionResult.getLeft();
            }

            if (analysisLevel > 1) {
                // Save SDG, and Call graph as JSON
                // If noBuild is not true, and build is also not provided, we will use "auto" as
                // the build command
                build = build == null ? "auto" : build;
                // Is noBuild is true, we will not build the project
                build = noBuild ? null : build;
                List<Dependency> sdgEdges = SystemDependencyGraph.construct(input, dependencies, build);
                combinedJsonObject.add("call_graph", gson.toJsonTree(sdgEdges));
            }
        }
        // Cleanup library dependencies directory
        BuildProject.cleanLibraryDependencies();

        // Neo4j graph output: project the IR to a graph and either push it over Bolt or write a
        // graph.cypher snapshot. The call graph (level 2) is included as CALLS edges when present.
        if ("neo4j".equalsIgnoreCase(emit)) {
            JsonArray callGraph = combinedJsonObject.has("call_graph")
                    ? combinedJsonObject.getAsJsonArray("call_graph")
                    : null;
            Neo4jEmitter.emit(symbolTable, callGraph, appName, input, output, targetFiles != null,
                    boltConfig());
            return;
        }

        // Convert the JavaCompilationUnit to JSON and add to consolidated json object
        String symbolTableJSONString = gson.toJson(symbolTable);
        JsonElement symbolTableJSON = gson.fromJson(symbolTableJSONString, JsonElement.class);
        combinedJsonObject.add("symbol_table", symbolTableJSON);

        // Add version number to the output JSON
        try {
            String[] versions = new VersionProvider().getVersion();
            if (versions.length > 0) {
                combinedJsonObject.addProperty("version", versions[0]);
            } else {
                combinedJsonObject.addProperty("version", "unknown");
            }
        } catch (Exception e) {
            combinedJsonObject.addProperty("version", "error retrieving version");
        }
        String consolidatedJSONString = gson.toJson(combinedJsonObject);
        emit(consolidatedJSONString);
    }

    private boolean isV2Schema() {
        if ("v2".equalsIgnoreCase(schema)) {
            return true;
        }
        if (!"v1".equalsIgnoreCase(schema)) {
            // Never silently fall back on an unrecognised flag value — the caller asked for something
            // specific and would otherwise process the wrong shape.
            throw new ParameterException(spec.commandLine(),
                    "error: unknown --schema value '" + schema + "'; use v1 or v2");
        }
        return false;
    }

    /**
     * Emit the canonical schema v2 payload. Levels 1 (containment tree) and 2 (the {@code call_graph}
     * overlay) are supported, whole-project, JSON. Anything else is an explicit error rather than a
     * silently different result.
     */
    private void analyzeV2() throws Exception {
        if (analysisLevel > 4) {
            throw new ParameterException(spec.commandLine(),
                    "error: --schema v2 currently supports --analysis-level 1, 2, 3, and 4 only");
        }
        if (analysisLevel >= 4
                && !"rta".equalsIgnoreCase(precision)
                && !"0-cfa".equalsIgnoreCase(precision)
                && !"0-1-cfa".equalsIgnoreCase(precision)) {
            throw new ParameterException(spec.commandLine(),
                    "error: unknown --precision '" + precision + "'; use rta, 0-cfa or 0-1-cfa");
        }
        if (analysisLevel >= 3
                && !"ast".equalsIgnoreCase(l3Engine)
                && !"wala".equalsIgnoreCase(l3Engine)) {
            throw new ParameterException(spec.commandLine(),
                    "error: unknown --l3-engine '" + l3Engine + "'; use ast or wala");
        }
        if ("neo4j".equalsIgnoreCase(emit)) {
            // The graph is always full-depth (keystone depth rule): depth/section selectors cannot
            // be combined with it — error loudly rather than silently project a partial graph.
            if (spec.commandLine().getParseResult().hasMatchedOption("--analysis-level")) {
                throw new ParameterException(spec.commandLine(),
                        "error: --analysis-level does not apply to --emit neo4j; "
                                + "the graph is always projected at full depth");
            }
            if (spec.commandLine().getParseResult().hasMatchedOption("--graph-field-depth")) {
                throw new ParameterException(spec.commandLine(),
                        "error: --graph-field-depth does not apply to --emit neo4j; "
                                + "the graph is always projected at full depth");
            }
            analysisLevel = 3;
            externalCalls = true;
        }
        if (sourceAnalysis != null || targetFiles != null) {
            throw new ParameterException(spec.commandLine(),
                    "error: --schema v2 supports whole-project analysis only "
                            + "(not --source-analysis or --target-files)");
        }
        if (input == null) {
            throw new ParameterException(spec.commandLine(), "error: --input is required");
        }

        String application = appName != null && !appName.isBlank()
                ? appName
                : Paths.get(input).toAbsolutePath().normalize().getFileName().toString();

        // Always attempt library type resolution: without the dependency jars on the solver's path,
        // third-party types degrade to bare spellings (`Model` rather than `org.springframework.ui.Model`)
        // and consumers lose the qualified names they join on. A failure here only thins resolution, so
        // it is a warning rather than a fatal error.
        projectRootPom = projectRootPom == null ? input : projectRootPom;
        Path dependencyDir = null;
        try {
            if (BuildProject.downloadLibraryDependencies(input, projectRootPom)) {
                dependencyDir = BuildProject.libDownloadPath;
            } else {
                Log.warn("Failed to download library dependencies; third-party types may not resolve");
            }
        } catch (Exception e) {
            Log.warn("Failed to download library dependencies (" + e.getMessage()
                    + "); third-party types may not resolve");
        }

        // Lazy by default: reuse modules whose files are byte-for-byte unchanged. `--eager` forces a
        // full rebuild, which is also how a caller recovers from a cache they distrust.
        Path cache = cacheDir == null ? null : Paths.get(cacheDir);
        String version = analyzerVersion();
        // L3 runs at parse time and needs the AST that a warm cache hit would skip; the cache is also an
        // L1 artifact that must not carry L3 overlays. So bypass the cache entirely at level >= 3.
        Map<String, JModule> cached = (eager || analysisLevel >= 3)
                ? new java.util.LinkedHashMap<>()
                : L1Cache.load(cache, application, version);

        Map<String, JModule> modules;
        List<L2CallGraph.RtaEndpoint> rtaEndpoints = null;
        WalaAnalysis wala = null;
        try {
            modules = L1Extractor.extractAll(
                    Paths.get(input), application, dependencyDir, cached,
                    analysisLevel, graphFieldDepth, l3Engine);
            // The WALA L3 engine needs the RTA build; --no-rta suppresses it, so a level-3 wala run with
            // --no-rta would silently carry no overlays. Warn rather than degrade without a signal.
            if (analysisLevel >= 3 && noRta && "wala".equalsIgnoreCase(l3Engine)) {
                Log.warn("--l3-engine wala requires the RTA build that --no-rta suppresses; "
                        + "no L3 overlays will be produced (L1/L2 output is unaffected)");
            }
            // The RTA overlay wants those same dependency jars in WALA's scope, so build it here, before
            // the finally cleans them. `declared` edges need no build, so level 2 never fails for want of
            // one: a build failure (or --no-rta) leaves rta absent and declared edges intact.
            if (analysisLevel >= 2 && !noRta) {
                String buildCommand = noBuild ? null : (build == null ? "auto" : build);
                String deps = dependencyDir == null ? null : dependencyDir.toString();
                if (analysisLevel >= 3 && "wala".equalsIgnoreCase(l3Engine)) {
                    // Build the call graph once; reuse it for both L2 rta endpoints and L3 overlays.
                    wala = WalaAnalysis.of(input, deps, buildCommand).orElse(null);
                    if (wala == null) {
                        Log.warn("WALA L3 engine unavailable; emitting L2 declared edges only");
                    }
                    rtaEndpoints = wala != null ? wala.rtaEndpoints() : java.util.List.of();
                } else {
                    rtaEndpoints = RtaCallGraph.endpoints(input, deps, buildCommand);
                }
            }
            // Apply WALA L3 overlays while the dependency jars are still live (PDG/CFG need class files).
            if (wala != null) {
                L3WalaOverlays.apply(wala, input, modules, graphFieldDepth);
            }
            // L4: the semantic ddg needs a WALA build regardless of --l3-engine, so build it here (or
            // reuse the instance --l3-engine wala already built above) while the jars are still live.
            // Must run strictly after the L3 overlay above: primeL4ModRef() permanently mutates the
            // WalaAnalysis instance's shared mod/ref maps, and every PDG built afterwards — including
            // L3's — would see the primed closure instead of the empty-defaulting one L3 relies on.
            if (analysisLevel >= 4) {
                if (wala == null) {
                    String buildCommand = noBuild ? null : (build == null ? "auto" : build);
                    String deps = dependencyDir == null ? null : dependencyDir.toString();
                    wala = WalaAnalysis.of(input, deps, buildCommand, precision).orElse(null);
                }
                if (wala != null) {
                    L4WalaOverlays.apply(wala, modules, graphFieldDepth);
                } else {
                    Log.warn("L4 semantic ddg unavailable (WALA build failed); emitting the derived "
                            + "SDG vertices and param edges only");
                }
            }
        } finally {
            BuildProject.cleanLibraryDependencies();
        }
        // Save the L1 tree before the L2 pass mutates it: the cache is an L1 artifact, so `callee`
        // (an L2 refinement) must not be persisted into it. At level >= 3 the tree carries L3 overlays,
        // which are not an L1 artifact, so the cache is not written.
        if (analysisLevel < 3) {
            L1Cache.save(cache, application, version, modules);
        }

        // maxLevel reports the requested level: the L1-L3 passes above always run to that level (or
        // degrade a specific overlay with a warning), and the L4 vertices/param edges below are
        // engine-free, so they run whenever analysisLevel >= 4 regardless of the WALA build's fate.
        Analysis analysis;
        if (analysisLevel >= 2) {
            L2CallGraph.Result l2 = L2CallGraph.build(application, modules, rtaEndpoints, externalCalls);
            // SdgVertices needs the callee ids L2CallGraph.build just backfilled onto call body nodes,
            // and no dependency jars, so it runs here rather than inside the try block above.
            SdgVertices.Result sdg = null;
            if (analysisLevel >= 4) {
                sdg = SdgVertices.apply(modules);
                // Summaries read the vertices SdgVertices just added, so this must follow it.
                SummaryPass.apply(modules, l2.callGraph(), graphFieldDepth);
            }
            analysis = V2Emitter.emit(application, analysisLevel, modules, version,
                    l2.callGraph(), l2.externalSymbols(),
                    sdg == null ? null : sdg.paramIn, sdg == null ? null : sdg.paramOut);
        } else {
            analysis = V2Emitter.emit(application, analysisLevel, modules, version);
        }

        if ("neo4j".equalsIgnoreCase(emit)) {
            Neo4jEmitter.emitV2(analysis, appName, input, output, boltConfig());
            return;
        }

        if (output == null) {
            // stdout is the data channel: compact JSON only, so the SDK can parse it directly.
            System.out.println(V2Json.compact().toJson(analysis));
        } else {
            Path outputPath = Paths.get(output);
            if (!Files.exists(outputPath)) {
                Files.createDirectories(outputPath);
            }
            try (FileWriter writer = new FileWriter(new File(output, outputFileName))) {
                writer.write(V2Json.pretty().toJson(analysis));
            }
        }
    }

    /** Bolt connection resolution, precedence: CLI flag > NEO4J_* env var > default. Null ⇒ snapshot. */
    private static BoltConfig boltConfig() {
        String uri = firstNonEmpty(neo4jUri, System.getenv("NEO4J_URI"));
        return uri == null
                ? null
                : new BoltConfig(uri,
                        firstNonEmpty(neo4jUser, System.getenv("NEO4J_USERNAME"), "neo4j"),
                        firstNonEmpty(neo4jPassword, System.getenv("NEO4J_PASSWORD"), "neo4j"),
                        firstNonEmpty(neo4jDatabase, System.getenv("NEO4J_DATABASE")));
    }

    private static String analyzerVersion() {
        try {
            String[] versions = new VersionProvider().getVersion();
            return versions.length > 0 ? versions[0] : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static void emit(String consolidatedJSONString) throws IOException {
        if (output == null) {
            System.out.println(consolidatedJSONString);
        } else {
            Path outputPath = Paths.get(output);
            if (!Files.exists(outputPath)) {
                Files.createDirectories(outputPath);
            }
            // If output is not null, export to a file
            File file = new File(output, "analysis.json");
            try (FileWriter fileWriter = new FileWriter(file)) {
                fileWriter.write(consolidatedJSONString);
                Log.done("Analysis output saved at " + output);
            } catch (IOException e) {
                Log.error("Error writing to file: " + e.getMessage());
            }
        }
    }

    private static boolean hasLegacyImportSchema(JsonObject symbolTableJson) {
        if (symbolTableJson == null) {
            return false;
        }
        for (Map.Entry<String, JsonElement> entry : symbolTableJson.entrySet()) {
            JsonElement compilationUnitElement = entry.getValue();
            if (!compilationUnitElement.isJsonObject()) {
                continue;
            }
            JsonObject compilationUnitJson = compilationUnitElement.getAsJsonObject();
            if (!compilationUnitJson.has("imports") || !compilationUnitJson.get("imports").isJsonArray()) {
                continue;
            }
            for (JsonElement importElement : compilationUnitJson.getAsJsonArray("imports")) {
                if (importElement.isJsonPrimitive() && importElement.getAsJsonPrimitive().isString()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Map<String, JavaCompilationUnit> readSymbolTableFromFile(File analysisJsonFile) {
        Type symbolTableType = new TypeToken<Map<String, JavaCompilationUnit>>() {
        }.getType();
        try (FileReader reader = new FileReader(analysisJsonFile)) {
            JsonObject jsonObject = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject symbolTableJson = jsonObject.getAsJsonObject("symbol_table");
            if (hasLegacyImportSchema(symbolTableJson)) {
                throw new IllegalStateException("Existing analysis.json uses legacy import schema (imports as strings). Regenerate analysis with codeanalyzer 2.3.7 or newer.");
            }
            return gson.fromJson(symbolTableJson, symbolTableType);
        } catch (IOException e) {
            Log.error("Error reading analysis file: " + e.getMessage());
        }
        return null;
    }
}
