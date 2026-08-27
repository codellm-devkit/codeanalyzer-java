package com.ibm.cldk.artifacts;

import com.ibm.cldk.schema.CanId;
import com.ibm.cldk.schema.JDependency;
import com.ibm.cldk.utils.Log;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Extracts declared dependencies from a Maven {@code pom.xml} via the JDK DOM parser — the complete,
 * exact path (no guessing): {@code groupId:artifactId} is the ecosystem-native name, {@code version}
 * is the version spec (a literal or an unresolved {@code ${...}} property reference), and {@code scope}
 * maps onto the canonical scope vocabulary.
 *
 * <p>Only the {@code <dependencies>} directly under the project (and under
 * {@code <dependencyManagement>}) are read; transitive resolution is out of scope, so every dependency
 * here is {@code direct = true}. A malformed document yields an empty map with a logged warning — a
 * parse failure never prevents the enclosing artifact (with its raw text) from being inventoried.
 */
final class MavenDependencyParser {

    private MavenDependencyParser() {}

    static Map<String, JDependency> parse(String artifactId, byte[] bytes, String relPath) {
        Map<String, JDependency> deps = new LinkedHashMap<>();
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            // Harden against XXE: this is untrusted repository input, not a trusted config.
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setNamespaceAware(false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new ByteArrayInputStream(bytes));

            NodeList dependencyNodes = doc.getElementsByTagName("dependency");
            for (int i = 0; i < dependencyNodes.getLength(); i++) {
                Node node = dependencyNodes.item(i);
                if (node.getNodeType() != Node.ELEMENT_NODE) {
                    continue;
                }
                Element dep = (Element) node;
                String groupId = childText(dep, "groupId");
                String artifactName = childText(dep, "artifactId");
                if (artifactName == null || artifactName.isEmpty()) {
                    continue;
                }
                String nativeName = (groupId == null || groupId.isEmpty())
                        ? artifactName
                        : groupId + ":" + artifactName;

                JDependency d = new JDependency();
                d.setId(CanId.dependencyId(artifactId, nativeName));
                d.setName(nativeName);
                d.setEcosystem("maven");
                d.setDirect(true);
                String version = childText(dep, "version");
                if (version != null && !version.isEmpty()) {
                    d.setVersionSpec(version);
                    // A literal (non-property) version is also the resolved version at this depth.
                    if (!version.contains("${")) {
                        d.setResolvedVersion(version);
                    }
                }
                d.setScope(canonicalScope(childText(dep, "scope")));
                deps.put(nativeName, d);
            }
        } catch (Exception e) {
            Log.warn("Could not parse Maven dependencies from " + relPath + " (" + e.getMessage()
                    + "); the artifact is still inventoried with its raw text");
            return new LinkedHashMap<>();
        }
        return deps;
    }

    /** Text of the first direct child element with the given tag, trimmed; null when absent. */
    private static String childText(Element parent, String tag) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node c = children.item(i);
            if (c.getNodeType() == Node.ELEMENT_NODE && tag.equals(c.getNodeName())) {
                String text = c.getTextContent();
                return text == null ? null : text.trim();
            }
        }
        return null;
    }

    /** Map a Maven scope onto the canonical scope vocabulary; absent Maven scope defaults to runtime
     * (Maven's own {@code compile} default). Unrecognized scopes fall to {@code unknown}. */
    private static String canonicalScope(String mavenScope) {
        if (mavenScope == null || mavenScope.isEmpty()) {
            return "runtime";
        }
        switch (mavenScope) {
            case "compile":
            case "runtime":
                return "runtime";
            case "provided":
            case "system":
                return "development";
            case "test":
                return "test";
            case "import":
                return "build";
            default:
                return "unknown";
        }
    }
}
