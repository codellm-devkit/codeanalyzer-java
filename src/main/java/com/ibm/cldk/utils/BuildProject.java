package com.ibm.cldk.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.*;
import java.util.stream.Stream;


import static com.ibm.cldk.utils.ProjectDirectoryScanner.classFilesStream;
import static com.ibm.cldk.CodeAnalyzer.projectRootPom;
import static com.ibm.cldk.CodeAnalyzer.noCleanDependencies;
import static com.ibm.cldk.CodeAnalyzer.includeTestClasses;

public class BuildProject {
    public static Path libDownloadPath;
    private static final String LIB_DEPS_DOWNLOAD_DIR = "_library_dependencies";
    private static final String MAVEN_CMD = BuildProject.getMavenCommand();
    private static final String GRADLE_CMD = BuildProject.getGradleCommand();

    /**
     * Gets the maven command to be used for building the project.
     *
     * @return the maven command
     */
    public static String getMavenCommand() {
        String mvnSystemCommand = Arrays.stream(System.getenv("PATH").split(System.getProperty("path.separator"))).filter(Predicate.not(String::isBlank)).filter(Predicate.not(String::isEmpty)).map(path -> new File(path, System.getProperty("os.name").toLowerCase().contains("windows") ? "mvn.cmd" : "mvn")).filter(File::exists).findFirst().map(File::getAbsolutePath).orElse(null);
        File mvnWrapper = System.getProperty("os.name").toLowerCase().contains("windows") ? new File(projectRootPom, "mvnw.cmd") : new File(projectRootPom, "mvnw");
        return commandExists(mvnWrapper.getAbsoluteFile()).getKey() ? mvnWrapper.getAbsoluteFile().toString() : mvnSystemCommand;
    }

    /**
     * Gets the gradle command to be used for building the project.
     *
     * @return the gradle command
     */
    public static String getGradleCommand() {
        String gradleSystemCommand = Arrays.stream(System.getenv("PATH").split(System.getProperty("path.separator"))).filter(Predicate.not(String::isBlank)).filter(Predicate.not(String::isEmpty)).map(path -> new File(path, System.getProperty("os.name").toLowerCase().contains("windows") ? "gradle.bat" : "gradle")).filter(File::exists).findFirst().map(File::getAbsolutePath).orElse(null);
        File gradleWrapper = System.getProperty("os.name").toLowerCase().contains("windows") ? new File(projectRootPom, "gradlew.bat") : new File(projectRootPom, "gradlew");

        return commandExists(gradleWrapper.getAbsoluteFile()).getKey() ? gradleWrapper.getAbsoluteFile()    .toString() : gradleSystemCommand;
    }

    public static Path tempInitScript;

    static {
        try {
            tempInitScript = Files.createTempFile("gradle-init-", ".gradle");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static final String GRADLE_DEPENDENCIES_TASK = "allprojects { afterEvaluate { project -> task downloadDependencies(type: Copy) { def configs = project.configurations.findAll { it.canBeResolved }; dependsOn configs; from configs; into project.hasProperty('outputDir') ? project.property('outputDir') : \"${project.buildDir}/libs\"; eachFile { fileCopyDetails -> fileCopyDetails.file.setWritable(true) }; doFirst { println \"Downloading dependencies for project ${project.name} to: ${destinationDir}\"; configs.each { config -> println \"Configuration: ${config.name}\"; config.resolvedConfiguration.resolvedArtifacts.each { artifact -> println \"\\t${artifact.moduleVersion.id}:${artifact.extension}\" } } } } } }";
    private static AbstractMap.SimpleEntry<Boolean, String> commandExists(File command) {
        StringBuilder output = new StringBuilder();
        if (!command.exists()) {
            return new AbstractMap.SimpleEntry<>(false, MessageFormat.format("Command {0} does not exist.", command));
        }
        try {
            Process process = new ProcessBuilder().directory(new File(projectRootPom)).command(String.valueOf(command), "--version").start();
            // Read the output stream
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }

            // Read the error stream
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            while ((line = errorReader.readLine()) != null) {
                output.append(line).append("\n");
            }

            int exitCode = process.waitFor();
            return new AbstractMap.SimpleEntry<>(exitCode == 0, output.toString().trim());
        } catch (IOException | InterruptedException exceptions) {
            Log.error(exceptions.getMessage());
            return new AbstractMap.SimpleEntry<>(false, exceptions.getMessage());
        }
    }

    private static boolean buildWithTool(String[] buildCommand) {
        Log.info("Building the project using " + buildCommand[0] + ".");
        ProcessBuilder processBuilder = new ProcessBuilder().directory(new File(projectRootPom)).command(buildCommand);
        try {
            Process process = processBuilder.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                Log.info(line);
            }
            int exitCode = process.waitFor();
            process.getErrorStream().transferTo(System.err);
            Log.info(buildCommand[0].toUpperCase() + " build exited with code " + exitCode);
            return exitCode == 0;
        } catch (IOException | InterruptedException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Checks if Maven is installed in the system.
     *
     * @return true if Maven is installed, false otherwise.
     */
    private static boolean isMavenInstalled() {
        ProcessBuilder processBuilder = new ProcessBuilder().directory(new File(projectRootPom)).command(MAVEN_CMD, "--version");
        try {
            Process process = processBuilder.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine(); // Read the first line of the output
            if (line != null && line.contains("Apache Maven")) {
                return true;
            }
        } catch (IOException e) {
            Log.error("An error occurred while checking if Maven is installed: " + e.getMessage());
        }
        Log.error("Maven is not installed or not properly configured in the system's PATH.");
        return false;
    }

    /**
     * Initiates a maven build process for the given project path.
     *
     * @param projectPath is the path to the project to be built.
     * @return true if the build was successful, false otherwise.
     */
    private static boolean mavenBuild(String projectPath) {
        Log.info("Building the project using Maven.");
        if (!isMavenInstalled()) {
            Log.info("Checking if Maven is installed.");
            return false;
        }

        String[] mavenCommand;
        if (includeTestClasses) {
            Log.warn("Hidden flag `--include-test-classes` is turned on. We'll including test classes in WALA analysis");
            mavenCommand = new String[]{MAVEN_CMD, "test-compile", "-f", projectPath + "/pom.xml", "-B", "-V", "-e", "-Drat.skip", "-Dfindbugs.skip", "-Dcheckstyle.skip", "-Dpmd.skip=true", "-Dspotbugs.skip", "-Denforcer.skip", "-Dmaven.javadoc.skip", "-DskipTests", "-Dmaven.test.skip.exec", "-Dlicense.skip=true", "-Drat.skip=true", "-Dspotless.check.skip=true"};
        }
        else
            mavenCommand = new String[]{MAVEN_CMD, "compile", "-f", projectPath + "/pom.xml", "-B", "-V", "-e", "-Drat.skip", "-Dfindbugs.skip", "-Dcheckstyle.skip", "-Dpmd.skip=true", "-Dspotbugs.skip", "-Denforcer.skip", "-Dmaven.javadoc.skip", "-DskipTests", "-Dmaven.test.skip.exec", "-Dlicense.skip=true", "-Drat.skip=true", "-Dspotless.check.skip=true"};

        return buildWithTool(mavenCommand);
    }

    public static boolean gradleBuild(String projectPath) {
        // Adjust Gradle command as needed
        String[] gradleCommand;
        if (GRADLE_CMD.equals("gradlew") || GRADLE_CMD.equals("gradlew.bat")) {
            gradleCommand = new String[]{projectPath + File.separator + GRADLE_CMD, "compileJava", "-p", projectPath};
        } else {
            if (includeTestClasses) {
                Log.warn("Hidden flag `--include-test-classes` is turned on. We'll including test classes in WALA analysis");
                gradleCommand = new String[]{GRADLE_CMD, "compileTestJava", "-p", projectPath};
            }
            else
                gradleCommand = new String[]{GRADLE_CMD, "compileJava", "-p", projectPath};
        }
        return buildWithTool(gradleCommand);
    }

    private static boolean buildProject(String projectPath, String build) {
        File pomFile = new File(String.valueOf(Paths.get(projectPath).toAbsolutePath()), "pom.xml");
        if (build == null) {
            return true;
        } else if (build.equals("auto")) {
            if (pomFile.exists()) {
                Log.info("Found pom.xml in the project directory. Using Maven to build the project.");
                return mavenBuild(Paths.get(projectPath).toAbsolutePath().toString()); // Use Maven if pom.xml exists
            } else {
                Log.info("Did not find a pom.xml in the project directory. Using Gradle to build the project.");
                return gradleBuild(projectPath); // Otherwise, use Gradle
            }
        } else {
            // Update command with a project path
            build = build.replace(MAVEN_CMD, MAVEN_CMD + " -f " + projectPath);
            Log.info("Using custom build command: " + build);
            String[] customBuildCommand = build.split(" ");
            return buildWithTool(customBuildCommand);
        }
    }

    /**
     * Streams the files in the given project path.
     *
     * @param projectPath is the path to the project to be streamed.
     * @return true if the streaming was successful, false otherwise.
     */
    public static List<Path> buildProjectAndStreamClassFiles(String projectPath, String build) throws IOException {
        return buildProject(projectPath, build) ? classFilesStream(projectPath) : new ArrayList<>();
    }

    private static boolean mkLibDepDirs(String projectPath) {
        if (!Files.exists(libDownloadPath)) {
            try {
                Files.createDirectories(libDownloadPath);
            } catch (IOException e) {
                Log.error("Error creating library dependency directory for " + projectPath + ": " + e.getMessage());
                return false;
            }
        }
        return true;
    }
    /**
     * Downloads library dependency jars of the given project so that the jars can be used
     * for type resolution during symbol table creation.
     *
     * @param projectPath Path to the project under javaee
     * @return true if dependency download succeeds; false otherwise
     */
    public static boolean downloadLibraryDependencies(String projectPath, String projectRootPom) throws IOException {
        // created download dir if it does not exist
        String projectRoot = projectRootPom != null ? projectRootPom : projectPath;

        File pomFile = new File((new File(projectRoot)).getAbsoluteFile(), "pom.xml");
        if (pomFile.exists()) {
            libDownloadPath = Paths.get(projectPath, "target", LIB_DEPS_DOWNLOAD_DIR).toAbsolutePath();
            if (mkLibDepDirs(projectPath))
                Log.debug("Dependencies found/created in " + libDownloadPath);
            else
                throw new IllegalStateException("Error creating library dependency directory in " + libDownloadPath);

            if (MAVEN_CMD == null || !commandExists(new File(MAVEN_CMD)).getKey()) {
                String msg = MAVEN_CMD == null ?
                        "Could not find Maven or a valid Maven Wrapper" :
                        MessageFormat.format("Could not verify that {0} exists", MAVEN_CMD);
                Log.error(msg);
                throw new IllegalStateException("Unable to execute Maven command. " +
                        (MAVEN_CMD == null ?
                                "Could not find Maven or a valid Maven Wrapper" :
                                "Attempt failed with message\n" + commandExists(new File(MAVEN_CMD)).getValue()
                        ));
            }
            Log.info("Found pom.xml in the project directory. Using Maven to download dependencies.");
            String[] mavenCommand = {MAVEN_CMD, "--no-transfer-progress", "-f", Paths.get(projectRoot, "pom.xml").toAbsolutePath().toString(), "dependency:copy-dependencies", "-DoutputDirectory=" + libDownloadPath.toString(), "-Doverwrite=true"};
            return buildWithTool(mavenCommand);
        } else if (new File(projectRoot, "build.gradle").exists() || new File(projectRoot, "build.gradle.kts").exists()) {
            libDownloadPath = Paths.get(projectPath, "build", LIB_DEPS_DOWNLOAD_DIR).toAbsolutePath();
                if (mkLibDepDirs(projectPath))
                    Log.debug("Dependencies found/created in " + libDownloadPath);
                else
                    throw new IllegalStateException("Error creating library dependency directory in " + libDownloadPath);

            if (GRADLE_CMD == null || !commandExists(new File(GRADLE_CMD)).getKey()) {
                String msg = GRADLE_CMD == null ?
                        "Could not find Gradle or valid Gradle Wrapper" :
                        MessageFormat.format("Could not verify that {0} exists", GRADLE_CMD);
                Log.error(msg);
                throw new IllegalStateException("Unable to execute Gradle command. " +
                        (GRADLE_CMD == null ?
                                "Could not find Gradle or valid Gradle Wrapper" :
                                "Attempt failed with message\n" + commandExists(new File(GRADLE_CMD)).getValue()
                        ));
            }
            Log.info("Found build.gradle or build.gradle.kts in the project directory. Using Gradle to download dependencies.");
            tempInitScript = Files.writeString(tempInitScript, GRADLE_DEPENDENCIES_TASK);
            String[] gradleCommand;
            if (GRADLE_CMD.equals("gradlew") || GRADLE_CMD.equals("gradlew.bat")) {
                gradleCommand = new String[]{projectRoot + File.separator + GRADLE_CMD, "--init-script", tempInitScript.toFile().getAbsolutePath(), "downloadDependencies", "-PoutputDir=" + libDownloadPath.toString()};
            } else {
                gradleCommand = new String[]{GRADLE_CMD, "--init-script", tempInitScript.toFile().getAbsolutePath(), "downloadDependencies", "-PoutputDir=" + libDownloadPath.toString()};
            }
            return buildWithTool(gradleCommand);
        }
        return false;
    }

    public static void cleanLibraryDependencies() {
        if (noCleanDependencies) {
            return;
        }
        if (libDownloadPath != null) {
            Log.info("Cleaning up library dependency directory: " + libDownloadPath);
            try {
                if (libDownloadPath.toFile().getAbsoluteFile().exists()) {
                    try (Stream<Path> paths = Files.walk(libDownloadPath)) {
                        paths.sorted(Comparator.reverseOrder())  // Delete files first, then directories
                            .map(Path::toFile)
                            .forEach(file -> {
                                if (!file.delete())
                                    Log.warn("Failed to delete: " + file.getAbsolutePath());
                        });
                    }
                }
            } catch (IOException e) {
                Log.warn("Unable to fully delete library dependency directory: " + e.getMessage());
            }
        }
        if (tempInitScript != null) {
            try {
                Files.delete(tempInitScript);
            } catch (IOException e) {
                Log.warn("Error deleting temporary Gradle init script: " + e.getMessage());
            }
        }
    }
}
