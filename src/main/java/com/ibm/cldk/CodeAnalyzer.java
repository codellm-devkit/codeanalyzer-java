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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ibm.cldk.entities.JavaCompilationUnit;
import com.ibm.cldk.utils.BuildProject;
import com.ibm.cldk.utils.Log;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;
import org.apache.commons.lang3.tuple.Pair;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

class VersionProvider implements CommandLine.IVersionProvider {

    public String[] getVersion() throws Exception {
        // Read the version baked into an embedded resource at build time. The JAR
        // manifest's Implementation-Version is unavailable in a GraalVM native
        // image (no manifest at runtime), so the resource is the portable source.
        try (InputStream in = getClass().getResourceAsStream("/codeanalyzer-version.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                String v = props.getProperty("version");
                if (v != null && !v.isBlank() && !v.contains("${")) {
                    return new String[] { v };
                }
            }
        } catch (IOException ignored) {
            // fall through to manifest lookup
        }
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
            "--analysis-level" }, description = "Level of analysis to perform. Options: 1 (for just symbol table); 2 (for call graph). Default: 1")
    public static int analysisLevel = 1;

    @Option(names = { "--include-test-classes" }, hidden = true, description = "Print logs to console.")
    public static boolean includeTestClasses = false;

    @Option(names = { "-v", "--verbose" }, description = "Print logs to console.")
    private static boolean verbose = false;

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

    private static void analyze() throws Exception {

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
        // Cleanup library dependencies directory. Source-string analysis (-s) never
        // downloads dependencies, so skip it: touching BuildProject would run its
        // static initializer, which resolves a build command against the (null)
        // project root and throws.
        if (sourceAnalysis == null) {
            BuildProject.cleanLibraryDependencies();
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
