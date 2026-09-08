package com.ibm.cldk.schema;

/**
 * Canonical {@code can://} id construction for schema v2.
 *
 * <p>Durable ids (&ge; callable) are containment paths
 * {@code can://<app>/java/<file>/<type>/<callable-signature>}; ordinal ids (&lt; callable) are
 * {@code <callable-id>@<tag>} where {@code <tag>} is a source position {@code line:col} (real
 * nodes) or a synthetic tag (e.g. {@code entry}). Pure functions; ids are opaque handles (the
 * {@code <file>} segment itself may contain {@code /}).
 */
public final class CanId {

    private CanId() {}

    /** The scheme prefix. The language is no longer part of it — see {@link #LANG}. */
    public static final String SCHEME = "can://";

    /** This analyzer's language segment, which now sits INSIDE the app rather than above it. */
    public static final String LANG = "java";

    /** {@code can://<app>} — the application root, and the prefix every id below it shares. */
    public static String applicationId(String appName) {
        return SCHEME + appName;
    }

    /** {@code <applicationId>/java/<relative-file-key>} (separators normalized to {@code /}). */
    public static String moduleId(String applicationId, String fileKey) {
        String rel = fileKey.replace("\\", "/").replaceFirst("^[./]+", "");
        return applicationId + "/" + LANG + "/" + rel;
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
     * {@code can://<app>/java/@external/<binary-type>/<signature>} — a callable outside the project.
     * Positionally parallel to an in-project callable id with {@code @external} in the file slot (D19);
     * the type is a <em>binary</em> name ({@code java.util.Map$Entry}) so the id is unambiguous and
     * joins WALA natively.
     */
    public static String externalId(String appName, String binaryType, String signature) {
        return applicationId(appName) + "/" + LANG + "/@external/" + binaryType + "/" + signature;
    }

    /**
     * {@code can://<app>/artifact/<rel-path>} — a language-neutral artifact id, nested under the app
     * rather than under a language. The {@code artifact} segment is deliberately chosen over
     * {@code java} so a sibling-language analyzer scanning the same repository lands on the same
     * node rather than a duplicate. {@code CypherWriter.DESCENDANTS} cites that sharing by name as
     * the reason the artifact/config-key subtree stays outside every wipe.
     */
    public static String artifactId(String appName, String relPath) {
        return applicationId(appName) + "/artifact/" + relPath;
    }

    /**
     * {@code <artifactId>@key/<dotted.key>} — a configuration key nested under its defining artifact,
     * making it discoverable and addressable as a sub-node.
     */
    public static String configKeyId(String artifactId, String dottedKey) {
        return artifactId + "@key/" + dottedKey;
    }

    /**
     * {@code <artifactId>@key:env/<bareKey>} — a compose-recognized environment variable
     * dual-minted into namespace {@code env} from a yaml artifact's {@code
     * services.<name>.environment} block (see {@code ConfigKeys}). Deliberately a DIFFERENT
     * delimiter from {@link #configKeyId}'s {@code "@key/"}, not a prefixed dotted key routed
     * through it: a plain yaml dotted path is an unrestricted string (any map key can be quoted to
     * contain literally anything), so it can legitimately equal {@code "env.<name>"} itself — e.g.
     * a top-level {@code env:} block one level deep — which a text prefix inside {@link
     * #configKeyId}'s argument could not be proven never to collide with.
     *
     * <p>The two id forms share the identical {@code <artifactId>@key} prefix and diverge at a
     * fixed character position right after it ({@code :} here vs. {@code configKeyId}'s {@code /}),
     * before either side has consumed any dotted-key or variable-name content — so the two can
     * never collide for any {@code dottedKey}/{@code bareKey} whatsoever, not merely for whatever
     * shape a test happens to construct.
     *
     * <p><b>Why the {@code :} separator, and not a plain {@code "env."} prefix.</b> Minting this as
     * {@code <artifactId>@key/env.<NAME>} would provably self-collide: a yaml dotted path can itself
     * begin with the segment {@code env.}, so one document carrying both a top-level {@code env:}
     * block and a compose {@code environment:} block yields two different records under one id.
     * Guarding only the bare-name case misses that dotted-path case. {@code
     * ArtifactModelTest#configKeyEnvDualMintIdNeverCollidesWithConfigKeyId} pins it. <b>Do not
     * "simplify" this to a dot-prefixed spelling.</b>
     *
     * <p>The consequence, stated plainly: {@code ConfigKey} is an un-prefixed cross-language merge
     * target (see {@link #artifactId}), so for a {@code docker-compose.yml} in a polyglot repo an
     * analyzer using the colliding spelling would mint a <i>different</i> node for the same
     * environment variable, splitting the very nodes that un-prefixed label exists to share. That
     * split is the price of not shipping a known id collision; convergence has to happen on the
     * side that still collides.
     */
    public static String configKeyEnvDualMintId(String artifactId, String bareKey) {
        return artifactId + "@key:env/" + bareKey;
    }

    /** {@code pkg:maven/<group>/<name>} — a two-segment Package URL for Maven coordinates. */
    public static String purlMaven(String group, String name) {
        return "pkg:maven/" + group + "/" + name;
    }
}
