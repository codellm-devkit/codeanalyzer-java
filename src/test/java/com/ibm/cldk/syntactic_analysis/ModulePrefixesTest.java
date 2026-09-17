package com.ibm.cldk.syntactic_analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModulePrefixesTest {

    private static Path pom(Path dir, String artifactId) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>g</groupId>"
                        + "<artifactId>" + artifactId + "</artifactId><version>1</version></project>");
        return dir;
    }

    private static Path java(Path moduleDir, String rel) throws IOException {
        Path file = moduleDir.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class X {}");
        return file;
    }

    @Test
    void theInputItselfIsAModuleAndItsCoordinateLeadsTheIdPath(@TempDir Path dir) throws IOException {
        pom(dir, "daytrader-web-service");
        Path file = java(dir, "src/main/java/com/foo/Bar.java");

        ModulePrefixes prefixes = ModulePrefixes.resolve(dir, List.of(file));

        assertEquals("daytrader-web-service/src/main/java/com/foo/Bar.java", prefixes.idPath(file));
    }

    @Test
    void aReactorChildCarriesItsOwnCoordinateNotTheAggregatorsAndNotItsDirectoryName(@TempDir Path dir)
            throws IOException {
        pom(dir, "daytrader-parent");
        Path child = pom(dir.resolve("web"), "daytrader-web-service");
        Path file = java(child, "src/main/java/com/foo/Bar.java");

        ModulePrefixes prefixes = ModulePrefixes.resolve(dir, List.of(file));

        // Nearest enclosing manifest wins: the child's coordinate, and the path below it is
        // relative to the child, not to the input.
        assertEquals("daytrader-web-service/src/main/java/com/foo/Bar.java", prefixes.idPath(file));
    }

    @Test
    void noManifestAnywhereLeavesTheInputRelativePathUnprefixed(@TempDir Path dir) throws IOException {
        Path file = java(dir, "src/main/java/com/foo/Bar.java");

        ModulePrefixes prefixes = ModulePrefixes.resolve(dir, List.of(file));

        assertEquals("src/main/java/com/foo/Bar.java", prefixes.idPath(file));
    }

    @Test
    void theSearchIsBoundedAtTheInputSoAManifestAboveItIsInvisible(@TempDir Path dir) throws IOException {
        pom(dir, "the-enclosing-repo");
        Path input = Files.createDirectories(dir.resolve("service"));
        Path file = java(input, "src/main/java/com/foo/Bar.java");

        ModulePrefixes prefixes = ModulePrefixes.resolve(input, List.of(file));

        assertEquals("src/main/java/com/foo/Bar.java", prefixes.idPath(file));
    }

    @Test
    void aCoordinateClaimedByTwoModuleDirectoriesIsAppliedToNeither(@TempDir Path dir) throws IOException {
        // Vendored duplicates: several services each build one shared internal library under the
        // same module name. Applying the prefix would collide their ids, so it applies to neither.
        Path a = pom(dir.resolve("svc-a/lib"), "daytrader-core");
        Path b = pom(dir.resolve("svc-b/lib"), "daytrader-core");
        Path fileA = java(a, "src/main/java/com/shared/Bean.java");
        Path fileB = java(b, "src/main/java/com/shared/Bean.java");

        ModulePrefixes prefixes = ModulePrefixes.resolve(dir, List.of(fileA, fileB));

        assertEquals("svc-a/lib/src/main/java/com/shared/Bean.java", prefixes.idPath(fileA));
        assertEquals("svc-b/lib/src/main/java/com/shared/Bean.java", prefixes.idPath(fileB));
        assertEquals(List.of("daytrader-core"), List.copyOf(prefixes.contested().keySet()));
        assertEquals(2, prefixes.contested().get("daytrader-core").size());
    }

    @Test
    void oneModuleDirectoryClaimingACoordinateTwiceIsNotAConflict(@TempDir Path dir) throws IOException {
        // Two files of the SAME module resolve to one manifest directory; that is not a collision.
        pom(dir, "daytrader-web-service");
        Path one = java(dir, "src/main/java/com/foo/Bar.java");
        Path two = java(dir, "src/main/java/com/foo/Baz.java");

        ModulePrefixes prefixes = ModulePrefixes.resolve(dir, List.of(one, two));

        assertEquals("daytrader-web-service/src/main/java/com/foo/Bar.java", prefixes.idPath(one));
        assertEquals("daytrader-web-service/src/main/java/com/foo/Baz.java", prefixes.idPath(two));
        assertTrue(prefixes.contested().isEmpty());
    }

    @Test
    void separatorsAreNormalizedToForwardSlashes(@TempDir Path dir) throws IOException {
        pom(dir, "svc");
        Path file = java(dir, "src/main/java/com/foo/Bar.java");

        assertTrue(ModulePrefixes.resolve(dir, List.of(file)).idPath(file).indexOf('\\') < 0);
    }
}
