package com.ibm.cldk.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.ibm.cldk.CodeAnalyzer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuildProjectTest {

    static {
        // BuildProject resolves MAVEN_CMD/GRADLE_CMD at class-initialization time against
        // CodeAnalyzer.projectRootPom, and a null there makes that initialization throw before any
        // test body runs. Pin it to a real directory first.
        CodeAnalyzer.projectRootPom = System.getProperty("java.io.tmpdir");
    }

    private static String real(Path path) throws IOException {
        return path.toRealPath().toString();
    }

    @Test
    void gradleBuildReportsAMissingGradleInsteadOfThrowing(@TempDir Path dir) {
        // A machine with no Gradle on PATH and no wrapper leaves the command null. That is a failed
        // build to report, not a reference to dereference.
        assertFalse(BuildProject.gradleBuild(dir.toString(), null));
    }

    @Test
    void buildRootPrefersTheInputWhenItCarriesItsOwnBuildFile(@TempDir Path dir) throws IOException {
        Path module = Files.createDirectories(dir.resolve("app"));
        Files.writeString(module.resolve("pom.xml"), "<project/>");
        Files.writeString(dir.resolve("pom.xml"), "<project/>");

        assertEquals(real(module), real(Path.of(BuildProject.resolveBuildRoot(module.toString(), dir.toString()))));
    }

    @Test
    void buildRootFallsBackToProjectRootPomWhenTheInputHasNoBuildFile(@TempDir Path dir) throws IOException {
        // `-i <repo-root> -f app`: the repo root carries no build file, so the build belongs in app.
        Path module = Files.createDirectories(dir.resolve("app"));
        Files.writeString(module.resolve("pom.xml"), "<project/>");

        assertEquals(real(module), real(Path.of(BuildProject.resolveBuildRoot(dir.toString(), module.toString()))));
    }

    @Test
    void buildRootFallsBackToAGradleProjectRootPom(@TempDir Path dir) throws IOException {
        Path module = Files.createDirectories(dir.resolve("app"));
        Files.writeString(module.resolve("build.gradle"), "");

        assertEquals(real(module), real(Path.of(BuildProject.resolveBuildRoot(dir.toString(), module.toString()))));
    }

    @Test
    void buildRootKeepsTheInputWhenNeitherCarriesABuildFile(@TempDir Path dir) throws IOException {
        Path module = Files.createDirectories(dir.resolve("app"));

        assertEquals(real(dir), real(Path.of(BuildProject.resolveBuildRoot(dir.toString(), module.toString()))));
    }

    @Test
    void buildRootToleratesAnAbsentProjectRootPom(@TempDir Path dir) throws IOException {
        assertEquals(real(dir), real(Path.of(BuildProject.resolveBuildRoot(dir.toString(), null))));
    }
}
