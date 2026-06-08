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

package com.ibm.cldk.utils;

import static com.ibm.cldk.utils.ProjectDirectoryScanner.jarFilesStream;

import com.ibm.wala.cast.java.ipa.callgraph.JavaSourceAnalysisScope;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.shrike.shrikeCT.InvalidClassFileException;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.util.config.FileOfClasses;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.jar.JarFile;
import org.apache.commons.io.FileUtils;

public class ScopeUtils {

  private static final String EXCLUSIONS = "";

  /**
   * Env var pointing directly at a directory of {@code .jmod} files for WALA's
   * primordial (JDK) scope. Set by the packaged native distribution to the
   * bundled jmods so a host {@code JAVA_HOME} (which may point at an unrelated
   * JDK version or a JRE with no jmods) cannot shadow them. Falls back to
   * {@code $JAVA_HOME/jmods} when unset, for JVM/dev use.
   */
  private static final String JMODS_DIR_ENV = "CODEANALYZER_JMODS_DIR";

  /**
   * The Std libs.
   */
  public static String[] stdLibs;

  /**
   * Create an javaee scope base on the input
   *
   * @param projectPath The root directory of the project to be analyzed.
   * @return scope The created javaee scope
   * @throws IOException the io exception
   */
  /**
   * Create an javaee scope base on the input
   *
   * @param projectPath     The root directory of the project to be analyzed.
   * @param applicationDeps the application deps
   * @return scope The created javaee scope
   * @throws IOException the io exception
   */
  public static AnalysisScope createScope(String projectPath, String applicationDeps, String build)
      throws IOException {
    Log.info("Create javaee scope.");
    AnalysisScope scope = new JavaSourceAnalysisScope();
    addDefaultExclusions(scope);

    Log.info("Loading Java SE standard libs.");

    Path jmodsDir = resolveJmodsDir();

    String[] stdlibs = Files.walk(jmodsDir)
        .filter(path -> path.toString().endsWith(".jmod"))
        .map(path -> path.toAbsolutePath().toString())
        .toArray(String[]::new);

    for (String stdlib : stdlibs) {
      scope.addToScope(ClassLoaderReference.Primordial, new JarFile(stdlib));
    }
    setStdLibs(stdlibs);

    // -------------------------------------
    // Add extra user provided JARS to scope
    // -------------------------------------
    if (!(applicationDeps == null)) {
      Log.info("Loading user specified extra libs.");
      Objects.requireNonNull(jarFilesStream(applicationDeps)).stream()
          .forEach(
              extraLibJar -> {
                Log.info("-> Adding dependency " + extraLibJar + " to javaee scope.");
                try {
                  scope.addToScope(ClassLoaderReference.Extension, new JarFile(extraLibJar.toAbsolutePath().toFile()));
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              });
    } else {
      Log.warn("No extra libraries to process.");
    }

    Path path = Paths.get(FileUtils.getTempDirectory().getAbsolutePath(), UUID.randomUUID().toString());
    String tmpDirString = Files.createDirectories(path).toFile().getAbsolutePath();
    Path workDir = Paths.get(tmpDirString);
    FileUtils.cleanDirectory(workDir.toFile());

    List<Path> applicationClassFiles = BuildProject.buildProjectAndStreamClassFiles(projectPath, build);
    Log.debug("Application class files: " + String.valueOf(applicationClassFiles.size()));
    if (applicationClassFiles == null) {
      Log.error("No application classes found.");
      throw new RuntimeException("No application classes found.");
    }
    Log.info("Adding application classes to scope.");
    applicationClassFiles.forEach(
        applicationClassFile -> {
          try {
            scope.addClassFileToScope(
                ClassLoaderReference.Application, applicationClassFile.toFile());
          } catch (InvalidClassFileException e) {
            throw new RuntimeException(e);
          }
        });

    return scope;
  }

  /**
   * Resolve the directory containing the JDK {@code .jmod} files used to build
   * WALA's primordial scope. Prefers {@link #JMODS_DIR_ENV} (set by the
   * packaged distribution) over {@code $JAVA_HOME/jmods}.
   *
   * @return an existing directory of jmod files
   */
  public static Path resolveJmodsDir() {
    String bundled = System.getenv(JMODS_DIR_ENV);
    if (bundled != null && !bundled.isBlank()) {
      Path p = Paths.get(bundled);
      if (Files.isDirectory(p)) {
        return p;
      }
      Log.warn(JMODS_DIR_ENV + " is set to '" + bundled
          + "' but it is not a directory; falling back to JAVA_HOME.");
    }

    String javaHome = System.getenv("JAVA_HOME");
    if (javaHome == null || javaHome.isBlank()) {
      throw new RuntimeException("Cannot locate JDK jmods for call-graph analysis: neither "
          + JMODS_DIR_ENV + " nor JAVA_HOME is set.");
    }
    Path jmods = Paths.get(javaHome, "jmods");
    if (!Files.isDirectory(jmods)) {
      throw new RuntimeException("No 'jmods' directory found at " + jmods
          + " (from JAVA_HOME=" + javaHome + "). Set " + JMODS_DIR_ENV
          + " to a directory of .jmod files.");
    }
    return jmods;
  }

  private static AnalysisScope addDefaultExclusions(AnalysisScope scope)
      throws IOException {
    Log.info("Add exclusions to scope.");
    scope.setExclusions(new FileOfClasses(new ByteArrayInputStream(EXCLUSIONS.getBytes(StandardCharsets.UTF_8))));
    return scope;
  }

  private static void setStdLibs(String[] stdlibs) {
    stdLibs = stdlibs;
  }
}
