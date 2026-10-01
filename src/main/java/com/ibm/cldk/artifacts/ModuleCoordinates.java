package com.ibm.cldk.artifacts;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * The name a build module gives <em>itself</em> — Maven's {@code <artifactId>}, Gradle's
 * {@code rootProject.name}.
 *
 * <p>This is the coordinate the {@code can://} module segment is built from, and the reason it is
 * read from the manifest rather than taken from the directory name: a declared coordinate survives
 * a rename, a relocated checkout and a differently-laid-out CI workspace, where a path-derived
 * segment would make every id a function of where the tree happens to sit on disk.
 *
 * <p>Every method is total — a missing, malformed or hostile manifest yields
 * {@link Optional#empty()} rather than throwing, because a module with no readable coordinate is a
 * module that simply gets no prefix, not an analysis failure.
 */
public final class ModuleCoordinates {

    private ModuleCoordinates() {}

    /** {@code rootProject.name = 'x'} / {@code rootProject.name = "x"}, Groovy and Kotlin alike. */
    private static final Pattern GRADLE_ROOT_NAME =
            Pattern.compile("rootProject\\s*\\.\\s*name\\s*=\\s*[\"']([^\"']+)[\"']");

    /**
     * The coordinate declared by the manifest in {@code directory}, or empty when it declares none.
     *
     * <p>Maven is consulted first because a directory carrying both is a Maven module with a Gradle
     * build bolted alongside far more often than the reverse.
     */
    public static Optional<String> nameOf(Path directory) {
        Path pom = directory.resolve("pom.xml");
        if (Files.isRegularFile(pom)) {
            return mavenArtifactId(pom);
        }
        for (String settings : new String[] {"settings.gradle", "settings.gradle.kts"}) {
            Path path = directory.resolve(settings);
            if (Files.isRegularFile(path)) {
                return gradleRootName(path);
            }
        }
        // A bare build.gradle deliberately yields nothing. Gradle's project name lives in the
        // settings file, and falling back to the directory name here would quietly reintroduce the
        // location-dependence this class exists to avoid.
        return Optional.empty();
    }

    /**
     * {@code /project/artifactId} — a <em>direct</em> child of the root element only.
     *
     * <p>Descending would find {@code <parent><artifactId>} and every
     * {@code <dependency><artifactId>}, each of which names a different module. A pom that inherits
     * its artifactId from a parent declares none of its own and correctly yields empty.
     */
    private static Optional<String> mavenArtifactId(Path pom) {
        try {
            DocumentBuilder builder =
                    ManifestParsers.newSecureDocumentBuilderFactory().newDocumentBuilder();
            Element root = builder.parse(new ByteArrayInputStream(Files.readAllBytes(pom)))
                    .getDocumentElement();
            if (root == null) {
                return Optional.empty();
            }
            NodeList children = root.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child.getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }
                String name = child.getLocalName() != null ? child.getLocalName() : child.getNodeName();
                if ("artifactId".equals(name)) {
                    return nonBlank(child.getTextContent());
                }
            }
            return Optional.empty();
        } catch (ParserConfigurationException | SAXException | IOException e) {
            // Exactly the checked contract of the DOM helpers plus the read. A pom declaring a
            // DOCTYPE arrives here as a SAXException, which is the categorical refusal
            // ManifestParsers documents for manifests, and it means "no coordinate", not "fail".
            return Optional.empty();
        }
    }

    private static Optional<String> gradleRootName(Path settings) {
        try {
            Matcher matcher =
                    GRADLE_ROOT_NAME.matcher(Files.readString(settings, StandardCharsets.UTF_8));
            return matcher.find() ? nonBlank(matcher.group(1)) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            // RuntimeException covers the unreadable-as-UTF-8 case (MalformedInputException arrives
            // wrapped) and an oversized file; neither is an analysis failure.
            return Optional.empty();
        }
    }

    private static Optional<String> nonBlank(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String trimmed = text.trim();
        // A coordinate is one path segment of an id. One carrying a separator would silently add a
        // level to the containment path, so it is refused rather than sanitized.
        return trimmed.indexOf('/') >= 0 || trimmed.indexOf('\\') >= 0
                ? Optional.empty()
                : Optional.of(trimmed);
    }
}
