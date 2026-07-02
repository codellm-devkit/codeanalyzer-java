package com.ibm.cldk;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Properties;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;


@Testcontainers
@SuppressWarnings("resource")
public class CodeAnalyzerIntegrationTest {

    /**
     * Creates a Java 11 test container that mounts the build/libs folder.
     */
    static String codeanalyzerVersion;
    static final String javaVersion = "17";
    static String javaHomePath;

    static {
        // Build project first
        try {
            Process process = new ProcessBuilder("./gradlew", "fatJar")
                    .directory(new File(System.getProperty("user.dir")))
                    .start();
            if (process.waitFor() != 0) {
                throw new RuntimeException("Build failed");
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to build codeanalyzer", e);
        }
    }

    @Container
    static final GenericContainer<?> container = new GenericContainer<>("ubuntu:latest")
            .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sh"))
            .withCommand("-c", "while true; do sleep 1; done")
            .withCopyFileToContainer(MountableFile.forHostPath(Paths.get(System.getProperty("user.dir")).resolve("build/libs")), "/opt/jars")
            .withCopyFileToContainer(MountableFile.forHostPath(Paths.get(System.getProperty("user.dir")).resolve("build/libs")), "/opt/jars")
            .withCopyFileToContainer(MountableFile.forHostPath(Paths.get(System.getProperty("user.dir")).resolve("src/test/resources/test-applications/mvnw-corrupt-test")), "/test-applications/mvnw-corrupt-test")
            .withCopyFileToContainer(MountableFile.forHostPath(Paths.get(System.getProperty("user.dir")).resolve("src/test/resources/test-applications/plantsbywebsphere")), "/test-applications/plantsbywebsphere")
            .withCopyFileToContainer(MountableFile.forHostPath(Paths.get(System.getProperty("user.dir")).resolve("src/test/resources/test-applications/call-graph-test")), "/test-applications/call-graph-test")
            .withCopyFileToContainer(MountableFile.forHostPath(Paths.get(System.getProperty("user.dir")).resolve("src/test/resources/test-applications/record-class-test")), "/test-applications/record-class-test")
            .withCopyFileToContainer(MountableFile.forHostPath(Paths.get(System.getProperty("user.dir")).resolve("src/test/resources/test-applications/init-blocks-test")), "/test-applications/init-blocks-test")
            .withCopyFileToContainer(MountableFile.forHostPath(Paths.get(System.getProperty("user.dir")).resolve("src/test/resources/test-applications/mvnw-working-test")), "/test-applications/mvnw-working-test");

    @Container
    static final GenericContainer<?> mavenContainer = new GenericContainer<>("maven:3.8.3-openjdk-17")
            .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sh"))
            .withCommand("-c", "while true; do sleep 1; done")
            .withCopyFileToContainer(MountableFile.forHostPath(Paths.get(System.getProperty("user.dir")).resolve("build/libs")), "/opt/jars")
            .withCopyFileToContainer(MountableFile.forHostPath(Paths.get(System.getProperty("user.dir")).resolve("src/test/resources/test-applications/mvnw-corrupt-test")), "/test-applications/mvnw-corrupt-test")
            .withCopyFileToContainer(MountableFile.forHostPath(Paths.get(System.getProperty("user.dir")).resolve("src/test/resources/test-applications/mvnw-working-test")), "/test-applications/mvnw-working-test")
            .withCopyFileToContainer(MountableFile.forHostPath(Paths.get(System.getProperty("user.dir")).resolve("src/test/resources/test-applications/daytrader8")), "/test-applications/daytrader8");

    public CodeAnalyzerIntegrationTest() throws IOException, InterruptedException {
    }

    @BeforeAll
    static void setUp() {
        // Install Java 17 in the base container
        try {
            container.execInContainer("apt-get", "update");
            container.execInContainer("apt-get", "install", "-y", "openjdk-17-jdk");

            // Get JAVA_HOME dynamically
            var javaHomeResult = container.execInContainer("bash", "-c",
                    "dirname $(dirname $(readlink -f $(which java)))"
            );
            javaHomePath = javaHomeResult.getStdout().trim();
            Assertions.assertFalse(javaHomePath.isEmpty(), "Failed to determine JAVA_HOME");

        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }


        // Get the version of the codeanalyzer jar
        Properties properties = new Properties();
        try (FileInputStream fis = new FileInputStream(
                Paths.get(System.getProperty("user.dir"), "gradle.properties").toFile())) {
            properties.load(fis);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        codeanalyzerVersion = properties.getProperty("version");
    }

    @Test
    void shouldHaveCorrectJavaVersionInstalled() throws Exception {
        var baseContainerresult = container.execInContainer("java", "-version");
        var mvnContainerresult = mavenContainer.execInContainer("java", "-version");
        Assertions.assertTrue(baseContainerresult.getStderr().contains("openjdk version \"" + javaVersion), "Base container Java version should be " + javaVersion);
        Assertions.assertTrue(mvnContainerresult.getStderr().contains("openjdk version \"" + javaVersion), "Maven container Java version should be " + javaVersion);
    }

    @Test
    void shouldHaveCodeAnalyzerJar() throws Exception {
        var dirContents = container.execInContainer("ls", "/opt/jars/");
        Assertions.assertTrue(dirContents.getStdout().length() > 0, "Directory listing should not be empty");
        Assertions.assertTrue(dirContents.getStdout().contains("codeanalyzer"), "Codeanalyzer.jar not found in the container.");
    }

    @Test
    void shouldBeAbleToRunCodeAnalyzer() throws Exception {
        var runCodeAnalyzerJar = container.execInContainer(
                "bash", "-c",
                String.format("export JAVA_HOME=%s && java -jar /opt/jars/codeanalyzer-%s.jar --help",
                        javaHomePath, codeanalyzerVersion
                ));

        Assertions.assertEquals(0, runCodeAnalyzerJar.getExitCode(),
                "Command should execute successfully");
        Assertions.assertTrue(runCodeAnalyzerJar.getStdout().length() > 0,
                "Should have some output");
    }

    @Test
    void callGraphShouldHaveKnownEdges() throws Exception {
        var runCodeAnalyzerOnCallGraphTest = container.execInContainer(
                "bash", "-c",
                String.format(
                        "export JAVA_HOME=%s && java -jar /opt/jars/codeanalyzer-%s.jar --input=/test-applications/call-graph-test --analysis-level=2",
                        javaHomePath, codeanalyzerVersion
                )
        );


        // Read the output JSON
        Gson gson = new Gson();
        JsonObject jsonObject = gson.fromJson(runCodeAnalyzerOnCallGraphTest.getStdout(), JsonObject.class);
        JsonArray callGraph = jsonObject.getAsJsonArray("call_graph");
        Assertions.assertTrue(StreamSupport.stream(callGraph.spliterator(), false)
                .map(JsonElement::getAsJsonObject)
                .anyMatch(entry ->
                        "CALL_DEP".equals(entry.get("type").getAsString()) &&
                                "1".equals(entry.get("weight").getAsString()) &&
                                entry.getAsJsonObject("source").get("signature").getAsString().equals("helloString()") &&
                                entry.getAsJsonObject("target").get("signature").getAsString().equals("log()")
                ), "Expected edge not found in the system dependency graph");
    }

    @Test
    void analysisLevelTwoShouldNotEmitSdgSections() throws Exception {
        var runCodeAnalyzer = container.execInContainer(
                "bash", "-c",
                String.format(
                        "export JAVA_HOME=%s && java -jar /opt/jars/codeanalyzer-%s.jar --input=/test-applications/call-graph-test --analysis-level=2",
                        javaHomePath, codeanalyzerVersion
                )
        );
        Assertions.assertEquals(0, runCodeAnalyzer.getExitCode(), "Command should execute successfully");
        JsonObject jsonObject = new Gson().fromJson(runCodeAnalyzer.getStdout(), JsonObject.class);
        Assertions.assertTrue(jsonObject.has("call_graph"), "Level 2 must emit the call graph");
        Assertions.assertFalse(jsonObject.has("system_dependency_graph"),
                "Level 2 must not emit the system dependency graph");
        Assertions.assertFalse(jsonObject.has("program_graphs"), "Level 2 must not emit program graphs");
    }

    @Test
    void fullSystemDependencyGraphShouldBeEmittedAtAnalysisLevelThree() throws Exception {
        var runCodeAnalyzer = container.execInContainer(
                "bash", "-c",
                String.format(
                        "export JAVA_HOME=%s && java -jar /opt/jars/codeanalyzer-%s.jar --input=/test-applications/call-graph-test --analysis-level=3",
                        javaHomePath, codeanalyzerVersion
                )
        );
        Assertions.assertEquals(0, runCodeAnalyzer.getExitCode(), "Command should execute successfully");
        JsonObject jsonObject = new Gson().fromJson(runCodeAnalyzer.getStdout(), JsonObject.class);

        // --- Method-level SDG edges (the JGraphEdges shape the SDK models) ---
        JsonArray systemDependencyGraph = jsonObject.getAsJsonArray("system_dependency_graph");
        Assertions.assertNotNull(systemDependencyGraph, "Level 3 must emit the system dependency graph");
        Assertions.assertTrue(StreamSupport.stream(systemDependencyGraph.spliterator(), false)
                .map(JsonElement::getAsJsonObject)
                .anyMatch(entry ->
                        "CONTROL_DEP".equals(entry.get("type").getAsString()) &&
                                entry.getAsJsonObject("source").get("signature").getAsString().equals("log()") &&
                                entry.getAsJsonObject("target").get("signature").getAsString().equals("loglog()")
                ), "Expected control dependence log() -> loglog() not found");
        Assertions.assertTrue(StreamSupport.stream(systemDependencyGraph.spliterator(), false)
                .map(JsonElement::getAsJsonObject)
                .anyMatch(entry ->
                        "DATA_DEP".equals(entry.get("type").getAsString()) &&
                                "PARAM_CALLER".equals(entry.get("source_kind").getAsString()) &&
                                "PARAM_CALLEE".equals(entry.get("destination_kind").getAsString()) &&
                                entry.getAsJsonObject("source").get("signature").getAsString().equals("helloString()") &&
                                entry.getAsJsonObject("target").get("signature").getAsString().equals("getName()")
                ), "Expected data dependence helloString() -> getName() not found");

        // --- Statement-level program graphs ---
        JsonObject programGraphs = jsonObject.getAsJsonObject("program_graphs");
        Assertions.assertNotNull(programGraphs, "Level 3 must emit program graphs");
        JsonObject functions = programGraphs.getAsJsonObject("functions");
        JsonObject helloString = functions.getAsJsonObject("org.example.User.helloString()");
        Assertions.assertNotNull(helloString, "helloString() must have program graphs");

        // CFG gate: exactly one synthetic ENTRY (id 0) and one synthetic EXIT (the max id).
        JsonArray cfgNodes = helloString.getAsJsonObject("cfg").getAsJsonArray("nodes");
        long entryCount = countNodesOfKind(cfgNodes, "entry");
        long exitCount = countNodesOfKind(cfgNodes, "exit");
        Assertions.assertEquals(1, entryCount, "CFG must have exactly one ENTRY node");
        Assertions.assertEquals(1, exitCount, "CFG must have exactly one EXIT node");

        // PDG gate: ENTRY-anchored control dependence and at least one def-use edge.
        JsonArray pdgEdges = helloString.getAsJsonObject("pdg").getAsJsonArray("edges");
        Assertions.assertTrue(StreamSupport.stream(pdgEdges.spliterator(), false)
                .map(JsonElement::getAsJsonObject)
                .anyMatch(edge -> "CDG".equals(edge.get("type").getAsString())
                        && edge.get("source").getAsInt() == 0),
                "Expected a CDG edge from the ENTRY node");
        Assertions.assertTrue(StreamSupport.stream(pdgEdges.spliterator(), false)
                .map(JsonElement::getAsJsonObject)
                .anyMatch(edge -> "DDG".equals(edge.get("type").getAsString())),
                "Expected at least one DDG edge");

        // SDG gate: known cross-function edges, anchored at the callee's ENTRY node.
        JsonArray sdgEdges = programGraphs.getAsJsonArray("sdg_edges");
        Assertions.assertTrue(StreamSupport.stream(sdgEdges.spliterator(), false)
                .map(JsonElement::getAsJsonObject)
                .anyMatch(edge -> "CALL".equals(edge.get("type").getAsString()) &&
                        edge.getAsJsonObject("source").get("signature").getAsString()
                                .equals("org.example.User.helloString()") &&
                        edge.getAsJsonObject("target").get("signature").getAsString()
                                .equals("org.example.User.log()") &&
                        edge.getAsJsonObject("target").get("node").getAsInt() == 0),
                "Expected CALL edge helloString() -> log()#0");
        Assertions.assertTrue(StreamSupport.stream(sdgEdges.spliterator(), false)
                .map(JsonElement::getAsJsonObject)
                .anyMatch(edge -> "PARAM_OUT".equals(edge.get("type").getAsString()) &&
                        edge.getAsJsonObject("source").get("signature").getAsString()
                                .equals("org.example.User.getName()") &&
                        edge.getAsJsonObject("target").get("signature").getAsString()
                                .equals("org.example.User.helloString()")),
                "Expected PARAM_OUT edge getName() -> helloString()");

        // No-dangling gate: every cross-function endpoint resolves to an emitted function graph
        // and a node id within that function's CFG node range.
        for (JsonElement element : sdgEdges) {
            for (String end : new String[] { "source", "target" }) {
                JsonObject endpoint = element.getAsJsonObject().getAsJsonObject(end);
                String signature = endpoint.get("signature").getAsString();
                int node = endpoint.get("node").getAsInt();
                JsonObject function = functions.getAsJsonObject(signature);
                Assertions.assertNotNull(function, "Dangling endpoint signature: " + signature);
                int maxNodeId = StreamSupport
                        .stream(function.getAsJsonObject("cfg").getAsJsonArray("nodes").spliterator(), false)
                        .mapToInt(n -> n.getAsJsonObject().get("id").getAsInt()).max().orElse(-1);
                Assertions.assertTrue(node >= 0 && node <= maxNodeId,
                        "Dangling endpoint node " + signature + "#" + node);
            }
        }
    }

    @Test
    void invalidGraphSelectorShouldFailFast() throws Exception {
        var runCodeAnalyzer = container.execInContainer(
                "bash", "-c",
                String.format(
                        "export JAVA_HOME=%s && java -jar /opt/jars/codeanalyzer-%s.jar --input=/test-applications/call-graph-test --analysis-level=3 --graphs=bogus",
                        javaHomePath, codeanalyzerVersion
                )
        );
        Assertions.assertNotEquals(0, runCodeAnalyzer.getExitCode(), "Unknown --graphs value must exit non-zero");
        Assertions.assertTrue(runCodeAnalyzer.getStderr().contains("Invalid --graphs"),
                "Unknown --graphs value must print a clear error");
    }

    private static long countNodesOfKind(JsonArray nodes, String kind) {
        return StreamSupport.stream(nodes.spliterator(), false)
                .map(JsonElement::getAsJsonObject)
                .filter(node -> kind.equals(node.get("kind").getAsString()))
                .count();
    }

    @Test
    void corruptMavenShouldNotBuildWithWrapper() throws IOException, InterruptedException {
        // Make executable
        mavenContainer.execInContainer("chmod", "+x", "/test-applications/mvnw-corrupt-test/mvnw");
        // Let's start by building the project by itself
        var mavenProjectBuildWithWrapper = mavenContainer.withWorkingDirectory("/test-applications/mvnw-corrupt-test").execInContainer("/test-applications/mvnw-corrupt-test/mvnw", "clean", "compile");
        Assertions.assertNotEquals(0, mavenProjectBuildWithWrapper.getExitCode());
    }

    @Test
    void corruptMavenShouldProduceAnalysisArtifactsWhenMVNCommandIsInPath() throws IOException, InterruptedException {
        // Let's start by building the project by itself
        var corruptMavenProjectBuild = mavenContainer.withWorkingDirectory("/test-applications/mvnw-corrupt-test").execInContainer("mvn", "-f", "/test-applications/mvnw-corrupt-test/pom.xml", "clean", "compile");
        Assertions.assertEquals(0, corruptMavenProjectBuild.getExitCode(), "Failed to build the project with system's default Maven.");
        // NOw run codeanalyzer and assert if analysis.json is generated.
        var runCodeAnalyzer = mavenContainer.execInContainer("java", "-jar", String.format("/opt/jars/codeanalyzer-%s.jar", codeanalyzerVersion), "--input=/test-applications/mvnw-corrupt-test", "--output=/tmp/", "--analysis-level=2", "--verbose", "--no-build");
        var codeAnalyzerOutputDirContents = mavenContainer.execInContainer("ls", "/tmp/analysis.json");
        String codeAnalyzerOutputDirContentsStdOut = codeAnalyzerOutputDirContents.getStdout();
        Assertions.assertTrue(codeAnalyzerOutputDirContentsStdOut.length() > 0, "Could not find 'analysis.json'.");
        // mvnw is corrupt, so we should see an error message in the output.
        Assertions.assertTrue(runCodeAnalyzer.getStdout().contains("[ERROR]\tCannot run program \"/test-applications/mvnw-corrupt-test/mvnw\"") && runCodeAnalyzer.getStdout().contains("/mvn."));
        // We should correctly identify the build tool used in the mvn command from the system path.
        Assertions.assertTrue(runCodeAnalyzer.getStdout().contains("[INFO]\tBuilding the project using /usr/bin/mvn."));
    }

    @Test
    void corruptMavenShouldNotTerminateWithErrorWhenMavenIsNotPresentUnlessAnalysisLevel2() throws IOException, InterruptedException {
        // When analysis level 2, we should get a Runtime Exception
        var runCodeAnalyzer = container.execInContainer(
                "bash", "-c",
                String.format(
                        "export JAVA_HOME=%s && java -jar /opt/jars/codeanalyzer-%s.jar --input=/test-applications/mvnw-corrupt-test --output=/tmp/ --analysis-level=2",
                        javaHomePath, codeanalyzerVersion
                )
        );

        Assertions.assertEquals(1, runCodeAnalyzer.getExitCode());
        Assertions.assertTrue(runCodeAnalyzer.getStderr().contains("java.lang.RuntimeException"));
    }

    @Test
    void shouldBeAbleToGenerateAnalysisArtifactForDaytrader8() throws Exception {
        var runCodeAnalyzerOnDaytrader8 = mavenContainer.execInContainer(
                "bash", "-c",
                String.format(
                        "export JAVA_HOME=%s && java -jar /opt/jars/codeanalyzer-%s.jar --input=/test-applications/daytrader8 --analysis-level=1",
                        javaHomePath, codeanalyzerVersion
                )
        );

        Assertions.assertTrue(runCodeAnalyzerOnDaytrader8.getStdout().contains("\"is_entrypoint_class\": true"), "No entry point classes found");
        Assertions.assertTrue(runCodeAnalyzerOnDaytrader8.getStdout().contains("\"is_entrypoint\": true"), "No entry point methods found");
    }


    @Test
    void shouldBeAbleToDetectCRUDOperationsAndQueriesForPlantByWebsphere() throws Exception {
        var runCodeAnalyzerOnPlantsByWebsphere = container.execInContainer(
                "bash", "-c",
                String.format(
                        "export JAVA_HOME=%s && java -jar /opt/jars/codeanalyzer-%s.jar --input=/test-applications/plantsbywebsphere --analysis-level=1",
                        javaHomePath, codeanalyzerVersion
                )
        );


        Assertions.assertEquals(0, runCodeAnalyzerOnPlantsByWebsphere.getExitCode(), "CodeAnalyzer command should succeed");
        String output = runCodeAnalyzerOnPlantsByWebsphere.getStdout();
        Gson gson = new Gson();
        JsonObject jsonObject = gson.fromJson(output, JsonObject.class);
        JsonObject symbolTable = jsonObject.getAsJsonObject("symbol_table");
        Assertions.assertNotNull(symbolTable);
        Assertions.assertTrue(symbolTable.size() > 0, "Symbol table should not be empty");

        boolean hasReadOperation = false;
        boolean hasCreateOperation = false;
        boolean hasUpdateOperation = false;
        boolean hasNamedQuery = false;
        int crudOperationCount = 0;
        int crudQueryCount = 0;

        for (Map.Entry<String, JsonElement> compilationUnitEntry : symbolTable.entrySet()) {
            JsonObject compilationUnit = compilationUnitEntry.getValue().getAsJsonObject();
            if (!compilationUnit.has("type_declarations")) {
                continue;
            }
            JsonObject typeDeclarations = compilationUnit.getAsJsonObject("type_declarations");
            for (Map.Entry<String, JsonElement> typeEntry : typeDeclarations.entrySet()) {
                JsonObject typeDeclaration = typeEntry.getValue().getAsJsonObject();
                if (!typeDeclaration.has("callable_declarations")) {
                    continue;
                }
                JsonObject callableDeclarations = typeDeclaration.getAsJsonObject("callable_declarations");
                for (Map.Entry<String, JsonElement> callableEntry : callableDeclarations.entrySet()) {
                    JsonObject callable = callableEntry.getValue().getAsJsonObject();
                    JsonArray crudOperations = callable.getAsJsonArray("crud_operations");
                    if (crudOperations != null) {
                        for (JsonElement crudOperationElement : crudOperations) {
                            JsonObject crudOperation = crudOperationElement.getAsJsonObject();
                            crudOperationCount++;
                            Assertions.assertTrue(crudOperation.has("line_number"), "CRUD operation should have line_number");
                            Assertions.assertTrue(crudOperation.has("operation_type"), "CRUD operation should have operation_type");
                            Assertions.assertTrue(crudOperation.has("target_table"), "CRUD operation should have target_table");
                            Assertions.assertTrue(crudOperation.has("involved_columns"), "CRUD operation should have involved_columns");
                            Assertions.assertTrue(crudOperation.has("condition"), "CRUD operation should have condition");
                            Assertions.assertTrue(crudOperation.has("joined_tables"), "CRUD operation should have joined_tables");
                            String operationType = crudOperation.get("operation_type").getAsString();
                            int lineNumber = crudOperation.get("line_number").getAsInt();
                            Assertions.assertTrue(lineNumber > 0, "CRUD operation should have positive line_number");
                            if ("READ".equals(operationType)) {
                                hasReadOperation = true;
                            }
                            if ("CREATE".equals(operationType)) {
                                hasCreateOperation = true;
                            }
                            if ("UPDATE".equals(operationType)) {
                                hasUpdateOperation = true;
                            }
                        }
                    }
                    JsonArray crudQueries = callable.getAsJsonArray("crud_queries");
                    if (crudQueries != null) {
                        for (JsonElement crudQueryElement : crudQueries) {
                            JsonObject crudQuery = crudQueryElement.getAsJsonObject();
                            crudQueryCount++;
                            Assertions.assertTrue(crudQuery.has("line_number"), "CRUD query should have line_number");
                            Assertions.assertTrue(crudQuery.has("query_type"), "CRUD query should have query_type");
                            Assertions.assertTrue(crudQuery.has("query_arguments"), "CRUD query should have query_arguments");
                            String queryType = crudQuery.get("query_type").getAsString();
                            int lineNumber = crudQuery.get("line_number").getAsInt();
                            Assertions.assertTrue(lineNumber > 0, "CRUD query should have positive line_number");
                            if ("NAMED".equals(queryType)) {
                                hasNamedQuery = true;
                            }
                        }
                    }
                }
            }
        }

        Assertions.assertTrue(crudOperationCount > 0, "No CRUD operations found");
        Assertions.assertTrue(crudQueryCount > 0, "No CRUD queries found");
        Assertions.assertTrue(hasNamedQuery, "No NAMED CRUD query found");
        Assertions.assertTrue(hasReadOperation, "No READ CRUD operation found");
        Assertions.assertTrue(hasCreateOperation, "No CREATE CRUD operation found");
        Assertions.assertTrue(hasUpdateOperation, "No UPDATE CRUD operation found");
    }

    @Test
    void symbolTableShouldHaveRecords() throws IOException, InterruptedException {
        var runCodeAnalyzerOnCallGraphTest = container.execInContainer(
                "bash", "-c",
                String.format(
                        "export JAVA_HOME=%s && java -jar /opt/jars/codeanalyzer-%s.jar --input=/test-applications/record-class-test --analysis-level=1",
                        javaHomePath, codeanalyzerVersion
                )
        );

        // Read the output JSON
        Gson gson = new Gson();
        JsonObject jsonObject = gson.fromJson(runCodeAnalyzerOnCallGraphTest.getStdout(), JsonObject.class);
        JsonObject symbolTable = jsonObject.getAsJsonObject("symbol_table");
        Assertions.assertEquals(4, symbolTable.size(), "Symbol table should have 4 records");
    }

    @Test
    void symbolTableShouldHaveDefaultRecordComponents() throws IOException, InterruptedException {
        var runCodeAnalyzerOnCallGraphTest = container.execInContainer(
                "bash", "-c",
                String.format(
                        "export JAVA_HOME=%s && java -jar /opt/jars/codeanalyzer-%s.jar --input=/test-applications/record-class-test --analysis-level=1",
                        javaHomePath, codeanalyzerVersion
                )
        );

        // Read the output JSON
        Gson gson = new Gson();
        JsonObject jsonObject = gson.fromJson(runCodeAnalyzerOnCallGraphTest.getStdout(), JsonObject.class);
        JsonObject symbolTable = jsonObject.getAsJsonObject("symbol_table");
        for (Map.Entry<String, JsonElement> element : symbolTable.entrySet()) {
            String key = element.getKey();
            if (!key.endsWith("PersonRecord.java")) {
                continue;
            }
            JsonObject type = element.getValue().getAsJsonObject();
            if (type.has("type_declarations")) {
                JsonObject typeDeclarations = type.getAsJsonObject("type_declarations");
                JsonArray recordComponent = typeDeclarations.getAsJsonObject("org.example.PersonRecord").getAsJsonArray("record_components");
                Assertions.assertEquals(2, recordComponent.size(), "Record component should have 2 components");
                JsonObject record = recordComponent.get(1).getAsJsonObject();
                Assertions.assertTrue(record.get("name").getAsString().equals("age") && record.get("default_value").getAsInt() == 18, "Record component should have a name");
            }
        }
    }

    @Test
    void parametersInCallableMustHaveStartAndEndLineAndColumns() throws IOException, InterruptedException {
        var runCodeAnalyzerOnCallGraphTest = container.execInContainer(
                "bash", "-c",
                String.format(
                        "export JAVA_HOME=%s && java -jar /opt/jars/codeanalyzer-%s.jar --input=/test-applications/record-class-test --analysis-level=1",
                        javaHomePath, codeanalyzerVersion
                )
        );

        // Read the output JSON
        Gson gson = new Gson();
        JsonObject jsonObject = gson.fromJson(runCodeAnalyzerOnCallGraphTest.getStdout(), JsonObject.class);
        JsonObject symbolTable = jsonObject.getAsJsonObject("symbol_table");
        for (Map.Entry<String, JsonElement> element : symbolTable.entrySet()) {
            String key = element.getKey();
            if (!key.endsWith("App.java")) {
                continue;
            }
            JsonObject type = element.getValue().getAsJsonObject();
            if (type.has("type_declarations")) {
                JsonObject typeDeclarations = type.getAsJsonObject("type_declarations");
                JsonObject mainMethod = typeDeclarations.getAsJsonObject("org.example.App")
                        .getAsJsonObject("callable_declarations")
                        .getAsJsonObject("main(java.lang.String[])");
                JsonArray parameters = mainMethod.getAsJsonArray("parameters");
                // There should be 1 parameter
                Assertions.assertEquals(1, parameters.size(), "Callable should have 1 parameter");
                JsonObject parameter = parameters.get(0).getAsJsonObject();
                // Start and end line and column should not be -1
                Assertions.assertTrue(parameter.get("start_line").getAsInt() == 7 && parameter.get("end_line").getAsInt() == 7 && parameter.get("start_column").getAsInt() == 29 && parameter.get("end_column").getAsInt() == 41, "Parameter should have start and end line and columns");
            }
        }
    }

    @Test
    void mustBeAbleToResolveInitializationBlocks() throws IOException, InterruptedException {
        var runCodeAnalyzerOnCallGraphTest = container.execInContainer(
                "bash", "-c",
                String.format(
                        "export JAVA_HOME=%s && java -jar /opt/jars/codeanalyzer-%s.jar --input=/test-applications/init-blocks-test --analysis-level=1",
                        javaHomePath, codeanalyzerVersion
                )
        );

        // Read the output JSON
        Gson gson = new Gson();
        JsonObject jsonObject = gson.fromJson(runCodeAnalyzerOnCallGraphTest.getStdout(), JsonObject.class);
        JsonObject symbolTable = jsonObject.getAsJsonObject("symbol_table");
        for (Map.Entry<String, JsonElement> element : symbolTable.entrySet()) {
            String key = element.getKey();
            if (!key.endsWith("App.java")) {
                continue;
            }
            JsonObject type = element.getValue().getAsJsonObject();
            if (type.has("type_declarations")) {
                JsonObject typeDeclarations = type.getAsJsonObject("type_declarations");
                JsonArray initializationBlocks = typeDeclarations.getAsJsonObject("org.example.App").getAsJsonArray("initialization_blocks");
                // There should be 2 blocks
                Assertions.assertEquals(2, initializationBlocks.size(), "Callable should have 1 parameter");
                Assertions.assertTrue(initializationBlocks.get(0).getAsJsonObject().get("is_static").getAsBoolean(), "Static block should be marked as static");
                Assertions.assertFalse(initializationBlocks.get(1).getAsJsonObject().get("is_static").getAsBoolean(), "Instance block should be marked as not static");
            }
        }
    }

    @Test
    void mustBeAbleToExtractCommentBlocks() throws IOException, InterruptedException {
        var runCodeAnalyzerOnCallGraphTest = container.execInContainer(
                "bash", "-c",
                String.format(
                        "export JAVA_HOME=%s && java -jar /opt/jars/codeanalyzer-%s.jar --input=/test-applications/init-blocks-test --analysis-level=1",
                        javaHomePath, codeanalyzerVersion
                )
        );

        // Read the output JSON
        Gson gson = new Gson();
        JsonObject jsonObject = gson.fromJson(runCodeAnalyzerOnCallGraphTest.getStdout(), JsonObject.class);
        JsonObject symbolTable = jsonObject.getAsJsonObject("symbol_table");
        for (Map.Entry<String, JsonElement> element : symbolTable.entrySet()) {
            String key = element.getKey();
            if (!key.endsWith("App.java")) {
                continue;
            }
            JsonObject type = element.getValue().getAsJsonObject();
            JsonArray comments = type.getAsJsonArray("comments");
            Assertions.assertEquals(16, comments.size(), "Should have 15 comments");
            Assertions.assertTrue(StreamSupport.stream(comments.spliterator(), false)
                    .map(JsonElement::getAsJsonObject)
                    .anyMatch(comment -> comment.get("is_javadoc").getAsBoolean()), "Single line comment not found");
        }
    }
}
