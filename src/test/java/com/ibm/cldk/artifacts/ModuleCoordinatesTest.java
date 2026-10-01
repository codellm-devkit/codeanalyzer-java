package com.ibm.cldk.artifacts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModuleCoordinatesTest {

    @Test
    void readsTheProjectArtifactIdFromAPom(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion>"
                        + "<groupId>org.apache.geronimo</groupId>"
                        + "<artifactId>daytrader-web-service</artifactId>"
                        + "<version>1.0</version></project>");
        assertEquals(Optional.of("daytrader-web-service"), ModuleCoordinates.nameOf(dir));
    }

    @Test
    void ignoresAnArtifactIdThatBelongsToTheParentOrADependency(@TempDir Path dir) throws IOException {
        // The module's own coordinate is the one directly under <project>; a parent's or a
        // dependency's spelling names something else entirely and must not be mistaken for it.
        Files.writeString(dir.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion>"
                        + "<parent><groupId>g</groupId><artifactId>the-parent</artifactId><version>1</version></parent>"
                        + "<dependencies>"
                        + "<dependency><groupId>g</groupId><artifactId>a-dependency</artifactId></dependency>"
                        + "</dependencies></project>");
        assertTrue(ModuleCoordinates.nameOf(dir).isEmpty());
    }

    @Test
    void readsRootProjectNameFromSettingsGradle(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("settings.gradle"), "rootProject.name = 'daytrader-account'\n");
        assertEquals(Optional.of("daytrader-account"), ModuleCoordinates.nameOf(dir));
    }

    @Test
    void aGradleModuleWithNoDeclaredNameHasNoCoordinate(@TempDir Path dir) throws IOException {
        // Deliberately NOT the directory name: falling back to the path would reintroduce exactly
        // the location-dependence a declared coordinate exists to avoid.
        Files.writeString(dir.resolve("build.gradle"), "plugins { id 'java' }\n");
        assertTrue(ModuleCoordinates.nameOf(dir).isEmpty());
    }

    @Test
    void aDirectoryWithNoManifestHasNoCoordinate(@TempDir Path dir) {
        assertTrue(ModuleCoordinates.nameOf(dir).isEmpty());
    }

    @Test
    void aMalformedPomIsEmptyRatherThanThrowing(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), "<project><artifactId>unclosed");
        assertTrue(ModuleCoordinates.nameOf(dir).isEmpty());
    }

    @Test
    void aPomDeclaringADoctypeIsRefused(@TempDir Path dir) throws IOException {
        // Manifests keep the categorical DOCTYPE refusal that ManifestParsers documents.
        Files.writeString(dir.resolve("pom.xml"),
                "<!DOCTYPE project [<!ENTITY x \"y\">]>\n"
                        + "<project><artifactId>sneaky</artifactId></project>");
        assertTrue(ModuleCoordinates.nameOf(dir).isEmpty());
    }
}
