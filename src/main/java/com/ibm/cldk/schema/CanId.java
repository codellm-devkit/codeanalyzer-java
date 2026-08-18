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
}
