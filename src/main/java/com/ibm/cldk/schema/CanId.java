package com.ibm.cldk.schema;

/**
 * Canonical {@code can://} id construction for schema v2.
 *
 * <p>Durable ids (&ge; callable) are containment paths
 * {@code can://java/<app>/<file>/<type>/<callable-signature>}; ordinal ids (&lt; callable) are
 * {@code <callable-id>@<tag>} where {@code <tag>} is a source position {@code line:col} (real
 * nodes) or a synthetic tag (e.g. {@code entry}). Pure functions; ids are opaque handles (the
 * {@code <file>} segment itself may contain {@code /}).
 */
public final class CanId {

    private CanId() {}

    /** The scheme + language segment for this analyzer's ids. */
    public static final String SCHEME = "can://java";

    /** {@code can://java/<app>}. */
    public static String applicationId(String appName) {
        return SCHEME + "/" + appName;
    }

    /** {@code <applicationId>/<relative-file-key>} (separators normalized to {@code /}). */
    public static String moduleId(String applicationId, String fileKey) {
        String rel = fileKey.replace("\\", "/").replaceFirst("^[./]+", "");
        return applicationId + "/" + rel;
    }

    /** {@code <parentId>/<segment>} — one downward step in the containment path. */
    public static String childId(String parentId, String segment) {
        return parentId + "/" + segment;
    }

    /** {@code <callableId>@<tag>} — an ordinal id for a body node within a callable. */
    public static String ordinalId(String callableId, String tag) {
        return callableId + "@" + tag;
    }

    /**
     * {@code can://java/<app>/@external/<binary-type>/<signature>} — a callable outside the project.
     * Positionally parallel to an in-project callable id with {@code @external} in the file slot (D19);
     * the type is a <em>binary</em> name ({@code java.util.Map$Entry}) so the id is unambiguous and
     * joins WALA natively.
     */
    public static String externalId(String appName, String binaryType, String signature) {
        return applicationId(appName) + "/@external/" + binaryType + "/" + signature;
    }

    /**
     * {@code can://artifact/<app>/<rel-path>} — a language-neutral artifact id. The {@code artifact}
     * segment is deliberately chosen over {@code java} so a sibling-language analyzer scanning the same
     * repository lands on the same node rather than a duplicate.
     */
    public static String artifactId(String appName, String relPath) {
        return "can://artifact/" + appName + "/" + relPath;
    }

    /**
     * {@code <artifactId>@key/<dotted.key>} — a configuration key nested under its defining artifact,
     * making it discoverable and addressable as a sub-node.
     */
    public static String configKeyId(String artifactId, String dottedKey) {
        return artifactId + "@key/" + dottedKey;
    }

    /** {@code pkg:maven/<group>/<name>} — a two-segment Package URL for Maven coordinates. */
    public static String purlMaven(String group, String name) {
        return "pkg:maven/" + group + "/" + name;
    }
}
