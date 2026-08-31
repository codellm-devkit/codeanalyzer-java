package com.ibm.cldk.artifacts;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Pure dependency-manifest and lockfile readers: text in, records out. No file I/O, no execution,
 * no mutation of anything — {@code DependencyView} (a later task in this layer) is what touches
 * disk and assembles these records into the emitted {@code JDependency} model.
 *
 * <p>This mirrors codeanalyzer-python's {@code artifacts/parsers.py} {@code (records, partial)}
 * convention: a whole-file parse failure (a malformed {@code pom.xml}) differs from a per-line one
 * (an unrecognized Gradle declaration). The former keeps the artifact but flags extraction; the
 * latter is silently skipped and the file still succeeds. The vocabulary is deliberately free-form
 * strings rather than enums, matching v1.3.0: {@code kind} is one of {@code
 * runtime|dev|optional|build} — four values, even though Maven has six scopes.
 */
public final class ManifestParsers {

    private ManifestParsers() {}

    /** A parsed declaration, before reconciliation. Mirrors python's frozen {@code RawDep}. */
    public static final class RawDep {
        public final String group;
        public final String name;
        public final String spec;
        public final String kind; // runtime|dev|optional|build
        public final List<String> extras;

        RawDep(String group, String name, String spec, String kind, List<String> extras) {
            this.group = group;
            this.name = name;
            this.spec = spec;
            this.kind = kind;
            this.extras = extras;
        }
    }

    /** Records plus a partial flag — an unparseable manifest keeps its artifact and flags extraction. */
    public static final class ParseResult {
        public final List<RawDep> deps;
        public final boolean partial;

        ParseResult(List<RawDep> deps, boolean partial) {
            this.deps = deps;
            this.partial = partial;
        }
    }

    // Configuration keyword, then a 'g:a:v' or "g:a:v" string literal on the same line. Deliberately
    // shallow, exactly as python's setup.py reader is deliberately static: a build.gradle is a
    // program (variables, `ext {}` properties, version catalogs, multi-line calls), and evaluating
    // it is out of scope. An interpolated version like "$springVersion" is captured verbatim as
    // `spec` with no resolved value -- a known gap, not a bug, matching the reference's identical
    // setup.py gap. A line spanning a call across multiple lines, or a project(...) reference with
    // no coordinate literal at all, simply does not match and is skipped like any other line.
    private static final Pattern GRADLE_DEP_LINE = Pattern.compile(
            "\\b(implementation|api|compileOnly|runtimeOnly|testImplementation|annotationProcessor)\\b"
                    + "[^'\"]*['\"]([^:'\"]+):([^:'\"]+):([^'\"]*)['\"]");

    /** Dispatch on basename. An unknown basename returns an empty, non-partial result. */
    public static ParseResult parseManifest(String path, String text) {
        String base = basename(path);
        try {
            if ("pom.xml".equals(base)) {
                return new ParseResult(parsePomDependencies(text), false);
            }
            if ("build.gradle".equals(base) || "build.gradle.kts".equals(base)) {
                return new ParseResult(parseGradleDependencies(text), false);
            }
        } catch (Exception e) {
            // Whole-file failure (malformed XML, or any other unexpected exception type): keep the
            // artifact but flag extraction, rather than letting one bad manifest fail the analysis.
            return new ParseResult(List.of(), true);
        }
        return new ParseResult(List.of(), false);
    }

    /** name -> resolved version, from a lockfile. Never throws; a malformed lock returns empty. */
    public static Map<String, String> parseLockPins(String path, String text) {
        try {
            if ("gradle.lockfile".equals(basename(path))) {
                return parseGradleLockfile(text);
            }
        } catch (Exception e) {
            return Map.of();
        }
        return Map.of();
    }

    // ---- pom.xml --------------------------------------------------------------------------

    private static List<RawDep> parsePomDependencies(String text)
            throws ParserConfigurationException, SAXException, IOException {
        Document doc = newSecureDocumentBuilder().parse(new InputSource(new StringReader(text)));
        Element project = doc.getDocumentElement();
        List<RawDep> out = new ArrayList<>();
        // Only /project/dependencies/dependency -- a nested <dependencyManagement><dependencies> is
        // a version constraint, not a declared dependency, and is a different (non-direct) child.
        for (Element dependencies : directChildren(project, "dependencies")) {
            for (Element dependency : directChildren(dependencies, "dependency")) {
                String scope = childText(dependency, "scope");
                String effectiveScope = scope.isEmpty() ? "compile" : scope; // Maven's own default
                if ("import".equals(effectiveScope)) {
                    continue; // BOM inclusion, not a dependency at all
                }
                List<String> extras = new ArrayList<>();
                String classifier = childText(dependency, "classifier");
                if (!classifier.isEmpty()) {
                    extras.add(classifier);
                }
                if ("true".equalsIgnoreCase(childText(dependency, "optional"))) {
                    // Kind is derived solely from <scope> (see kindForMavenScope) -- there is no
                    // separate boolean slot on RawDep for "optional" -- so the flag instead lands in
                    // `extras`, the same free-vocabulary bucket a classifier uses.
                    extras.add("optional");
                }
                out.add(new RawDep(
                        childText(dependency, "groupId"),
                        childText(dependency, "artifactId"),
                        childText(dependency, "version"), // "" when inherited from a parent or a BOM
                        kindForMavenScope(effectiveScope),
                        List.copyOf(extras)));
            }
        }
        return out;
    }

    // The one judgement call in this file; everything else here is transcription. Maven has six
    // scopes and RawDep.kind has four slots. compile/runtime keep their obvious "runtime" meaning;
    // test maps to dev. provided and system both fall to build: neither ships at runtime, both are
    // "present only so the build compiles," the same bucket a build-system requirement occupies.
    // import never reaches here -- the caller skips it outright, since it is BOM inclusion rather
    // than a dependency.
    private static String kindForMavenScope(String scope) {
        switch (scope) {
            case "test":
                return "dev";
            case "provided":
            case "system":
                return "build";
            default: // "compile", "runtime"
                return "runtime";
        }
    }

    private static DocumentBuilder newSecureDocumentBuilder() throws ParserConfigurationException {
        // pom.xml is untrusted repository content, not a file this process authored. Hardened per
        // OWASP's XXE prevention cheat sheet: disallowing DOCTYPE outright is the categorical
        // blocker (no DTD means no custom entities of any kind, external or internal); the two
        // external-entity toggles are kept as explicit defense in depth alongside it.
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        return factory.newDocumentBuilder();
    }

    private static List<Element> directChildren(Element parent, String tagName) {
        List<Element> children = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && tagName.equals(node.getNodeName())) {
                children.add((Element) node);
            }
        }
        return children;
    }

    private static String childText(Element parent, String tagName) {
        List<Element> children = directChildren(parent, tagName);
        return children.isEmpty() ? "" : children.get(0).getTextContent().trim();
    }

    // ---- build.gradle / build.gradle.kts ---------------------------------------------------

    private static List<RawDep> parseGradleDependencies(String text) {
        List<RawDep> out = new ArrayList<>();
        for (String line : text.split("\n", -1)) {
            Matcher m = GRADLE_DEP_LINE.matcher(line);
            if (!m.find()) {
                continue; // shallow regex: a line outside this shape is skipped, not a failure
            }
            out.add(new RawDep(
                    m.group(2), m.group(3), m.group(4), kindForGradleConfiguration(m.group(1)), List.of()));
        }
        return out;
    }

    private static String kindForGradleConfiguration(String configuration) {
        switch (configuration) {
            case "testImplementation":
                return "dev";
            case "compileOnly":
            case "annotationProcessor":
                return "build";
            default: // implementation, api, runtimeOnly
                return "runtime";
        }
    }

    // ---- gradle.lockfile --------------------------------------------------------------------

    private static Map<String, String> parseGradleLockfile(String text) {
        // "com.group:artifact:1.2.3=compileClasspath,runtimeClasspath" -> {"com.group:artifact": "1.2.3"}.
        // Comment lines and the "empty=<configuration>" marker Gradle writes for a configuration
        // with no locked dependencies both lack a second colon on their left-hand side and are
        // skipped along with anything else that does not fit the g:a:v shape.
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : text.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String coordinate = trimmed.substring(0, eq);
            int firstColon = coordinate.indexOf(':');
            int secondColon = firstColon < 0 ? -1 : coordinate.indexOf(':', firstColon + 1);
            if (secondColon < 0) {
                continue;
            }
            out.put(coordinate.substring(0, secondColon), coordinate.substring(secondColon + 1));
        }
        return out;
    }

    private static String basename(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }
}
