package com.ibm.cldk.artifacts;

import com.ibm.cldk.schema.CanId;
import com.ibm.cldk.schema.JArtifact;
import com.ibm.cldk.schema.JBodyNode;
import com.ibm.cldk.schema.JCallable;
import com.ibm.cldk.schema.JConfigKey;
import com.ibm.cldk.schema.JConfigRead;
import com.ibm.cldk.schema.JConfigUseEdge;
import com.ibm.cldk.schema.JDecorator;
import com.ibm.cldk.schema.JField;
import com.ibm.cldk.schema.JModule;
import com.ibm.cldk.schema.JParameter;
import com.ibm.cldk.schema.JType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The config-use literal tier: joins code that reads configuration to the {@link JConfigKey} records
 * {@link ConfigKeys} flattened out of the repository's artifacts. {@link ConfigKeys} answers "which
 * keys are declared"; this answers "who reads one".
 *
 * <p>Pure tree-in/records-out — it reads the already-built L1 modules and the artifact layer and
 * mints no nodes, so it runs at every analysis level. Two divergences from codeanalyzer-python's
 * equivalent pass are deliberate and recorded in the spec (2026-09-07) and in
 * {@code .claude/SCHEMA_DECISIONS.md} D30:
 *
 * <ul>
 *   <li><b>Annotation reads.</b> Python anchors every read on a {@code call} body node, because
 *       Python's config reads <em>are</em> calls. Java's dominant idiom is {@code @Value("${x}")} /
 *       {@code @ConfigurationProperties}, which has no call site, so {@code src} widens to the
 *       annotated element. Reading only call sites would report almost nothing on a Spring app.
 *   <li><b>Level.</b> The tier runs at L1, not python's L2: a {@code call} body node already carries
 *       {@code argumentExpr}/{@code receiverType}/{@code methodName} at L1, so nothing here needs a
 *       call graph.
 * </ul>
 *
 * <p>Detection is by <em>declared receiver type</em>, never by bare method name — a project's own
 * {@code getProperty(String)} is not a config read, and matching on the name alone would claim it.
 */
public final class ConfigUses {

    private ConfigUses() {}

    /** The literal tier is the only tier this class runs; L3/L4 dataflow widens it separately. */
    private static final List<String> LITERAL = List.of("literal");

    /**
     * A call-site detector. {@code namespaces} is a <em>preference order</em>, not a filter: the
     * first namespace with at least one match wins, so {@code System.getProperty("user.home")}
     * prefers a declared properties key over an identically named environment variable rather than
     * claiming both.
     */
    private static final class CallRule {
        final String declaringType;
        final String method;
        final int keyArg;
        final List<String> namespaces;

        CallRule(String declaringType, String method, int keyArg, String... namespaces) {
            this.declaringType = declaringType;
            this.method = method;
            this.keyArg = keyArg;
            this.namespaces = Arrays.asList(namespaces);
        }
    }

    private static final List<CallRule> CALL_RULES = List.of(
            new CallRule("java.lang.System", "getenv", 0, "env", "dockerfile"),
            new CallRule("java.lang.System", "getProperty", 0, "properties", "env"),
            new CallRule("java.util.Properties", "getProperty", 0, "properties", "yaml"),
            // Environment extends PropertyResolver; a receiver typed as either resolves to its own
            // spelling, so both are listed rather than relying on a supertype walk the schema does
            // not carry.
            new CallRule("org.springframework.core.env.Environment", "getProperty", 0,
                    "properties", "yaml", "env"),
            new CallRule("org.springframework.core.env.Environment", "getRequiredProperty", 0,
                    "properties", "yaml", "env"),
            new CallRule("org.springframework.core.env.PropertyResolver", "getProperty", 0,
                    "properties", "yaml", "env"),
            new CallRule("org.springframework.core.env.PropertyResolver", "getRequiredProperty", 0,
                    "properties", "yaml", "env"));

    private static final String VALUE_ANNOTATION =
            "org.springframework.beans.factory.annotation.Value";
    private static final String CONFIGURATION_PROPERTIES_ANNOTATION =
            "org.springframework.boot.context.properties.ConfigurationProperties";

    /** Namespace preference for the two annotation idioms; same "first non-empty wins" rule. */
    private static final List<String> ANNOTATION_NAMESPACES =
            List.of("properties", "yaml", "env", "dockerfile");

    /** A whole {@code @Value} member that is exactly one placeholder, with an optional default. */
    private static final Pattern PLACEHOLDER = Pattern.compile("^\\$\\{([^:{}]+)(?::.*)?}$");

    public static final class Result {
        public final List<JConfigUseEdge> uses;
        public final List<JConfigRead> unresolved;

        Result(List<JConfigUseEdge> uses, List<JConfigRead> unresolved) {
            this.uses = uses;
            this.unresolved = unresolved;
        }
    }

    /** One detected read, before the resolver decides whether it closed on a declared key. */
    private static final class Read {
        final String site;
        final String callee;
        final String literal;
        final List<String> namespaces;
        /** True for {@code @ConfigurationProperties}: {@code literal} is a prefix, not a key. */
        final boolean prefix;

        Read(String site, String callee, String literal, List<String> namespaces, boolean prefix) {
            this.site = site;
            this.callee = callee;
            this.literal = literal;
            this.namespaces = namespaces;
            this.prefix = prefix;
        }
    }

    /**
     * Detect config reads across the L1 tree and resolve them against the declared keys.
     *
     * @param appName the application segment of every minted {@code @external} ghost id
     * @param modules the L1 modules, keyed by relative file key
     * @param artifacts the repository-artifact layer, whose {@code configKeys} are the declared set
     */
    public static Result detect(String appName, Map<String, JModule> modules,
            Map<String, JArtifact> artifacts) {
        Map<String, List<JConfigKey>> keysByNamespace = keysByNamespace(artifacts);

        List<Read> reads = new ArrayList<>();
        if (modules != null) {
            for (JModule module : modules.values()) {
                collectTypes(appName, module.getTypes(), reads);
            }
        }
        return resolve(reads, keysByNamespace);
    }

    // ----------------------------------------------------------------------------------------
    // Detection
    // ----------------------------------------------------------------------------------------

    private static void collectTypes(String appName, Map<String, JType> types, List<Read> reads) {
        if (types == null) {
            return;
        }
        for (JType type : types.values()) {
            collectType(appName, type, reads);
        }
    }

    private static void collectType(String appName, JType type, List<Read> reads) {
        for (JDecorator d : type.getDecorators()) {
            if (isAnnotation(d, CONFIGURATION_PROPERTIES_ANNOTATION)) {
                String prefix = annotationMember(d, "prefix", "value");
                if (prefix != null && !prefix.isEmpty()) {
                    reads.add(new Read(type.getId(),
                            ghost(appName, CONFIGURATION_PROPERTIES_ANNOTATION),
                            prefix, ANNOTATION_NAMESPACES, true));
                }
            }
        }
        for (JField field : type.getFields().values()) {
            collectValueAnnotations(appName, field.getDecorators(), field.getId(), reads);
        }
        for (JCallable callable : type.getCallables().values()) {
            collectCallable(appName, callable, reads);
        }
        collectTypes(appName, type.getTypes(), reads);
    }

    private static void collectCallable(String appName, JCallable callable, List<Read> reads) {
        collectValueAnnotations(appName, callable.getDecorators(), callable.getId(), reads);
        for (JParameter p : callable.getParameters()) {
            // A parameter is not an addressable node in the v2 tree, so an injected parameter's read
            // is attributed to the callable that declares it.
            collectValueAnnotations(appName, p.getDecorators(), callable.getId(), reads);
        }
        for (Map.Entry<String, JBodyNode> e : callable.getBody().entrySet()) {
            collectCallSite(appName, callable.getId(), e.getKey(), e.getValue(), reads);
        }
        collectTypes(appName, callable.getTypes(), reads);
    }

    private static void collectValueAnnotations(String appName, List<JDecorator> decorators,
            String site, List<Read> reads) {
        for (JDecorator d : decorators) {
            if (!isAnnotation(d, VALUE_ANNOTATION)) {
                continue;
            }
            String member = annotationMember(d, "value");
            // A `@Value` carrying no `${...}` reads no configuration at all (a constant, or a SpEL
            // `#{...}` expression that resolves beans rather than properties). Not a read, so it is
            // not recorded as an unresolved one either — silence here is correct, not a gap.
            if (member == null || !member.contains("${")) {
                continue;
            }
            Matcher m = PLACEHOLDER.matcher(member);
            reads.add(new Read(site, ghost(appName, VALUE_ANNOTATION),
                    m.matches() ? m.group(1) : null, ANNOTATION_NAMESPACES, false));
        }
    }

    private static void collectCallSite(String appName, String callableId, String localId,
            JBodyNode node, List<Read> reads) {
        if (!"call".equals(node.getKind()) || node.getMethodName() == null) {
            return;
        }
        for (CallRule rule : CALL_RULES) {
            if (!rule.method.equals(node.getMethodName())
                    || !rule.declaringType.equals(node.getReceiverType())) {
                continue;
            }
            List<String> args = node.getArgumentExpr();
            String literal = args != null && args.size() > rule.keyArg
                    ? stringLiteral(args.get(rule.keyArg))
                    : null;
            String signature = node.getCalleeSignature() != null
                    ? node.getCalleeSignature()
                    : node.getMethodName();
            reads.add(new Read(CanId.ordinalId(callableId, localId),
                    CanId.externalId(appName, rule.declaringType, signature),
                    literal, rule.namespaces, false));
            return;
        }
    }

    // ----------------------------------------------------------------------------------------
    // Resolution
    // ----------------------------------------------------------------------------------------

    private static Result resolve(List<Read> reads, Map<String, List<JConfigKey>> keysByNamespace) {
        List<JConfigUseEdge> uses = new ArrayList<>();
        List<JConfigRead> unresolved = new ArrayList<>();

        for (Read read : reads) {
            if (read.literal == null) {
                unresolved.add(unresolvedRead(read, null, "non-literal"));
                continue;
            }
            List<JConfigKey> matched = match(read, keysByNamespace);
            if (matched.isEmpty()) {
                unresolved.add(unresolvedRead(read, read.literal, "undefined-key"));
                continue;
            }
            // One edge per matching key, not "exactly one match or nothing" (the same rule
            // codeanalyzer-python's resolver follows): one key legitimately appears in several
            // artifacts — application.properties and application-dev.properties — and a
            // `@ConfigurationProperties` prefix claims every key beneath it by design.
            for (JConfigKey key : matched) {
                JConfigUseEdge edge = new JConfigUseEdge();
                edge.setSrc(read.site);
                edge.setDst(key.getId());
                edge.setProv(new ArrayList<>(LITERAL));
                uses.add(edge);
            }
        }

        uses.sort(Comparator.comparing(JConfigUseEdge::getSrc).thenComparing(JConfigUseEdge::getDst));
        unresolved.sort(Comparator.comparing(JConfigRead::getSite)
                .thenComparing(JConfigRead::getReason)
                .thenComparing(u -> u.getKey() == null ? "" : u.getKey()));
        return new Result(uses, unresolved);
    }

    private static JConfigRead unresolvedRead(Read read, String key, String reason) {
        JConfigRead r = new JConfigRead();
        r.setSite(read.site);
        r.setCallee(read.callee);
        r.setKey(key);
        r.setReason(reason);
        r.setProv(new ArrayList<>(LITERAL));
        return r;
    }

    /** First namespace in the rule's preference order with at least one match wins. */
    private static List<JConfigKey> match(Read read, Map<String, List<JConfigKey>> keysByNamespace) {
        for (String namespace : read.namespaces) {
            List<JConfigKey> candidates = keysByNamespace.get(namespace);
            if (candidates == null) {
                continue;
            }
            List<JConfigKey> matched = new ArrayList<>();
            for (JConfigKey key : candidates) {
                if (read.prefix ? underPrefix(key.getKey(), read.literal)
                        : sameKey(key.getKey(), read.literal, namespace)) {
                    matched.add(key);
                }
            }
            if (!matched.isEmpty()) {
                return matched;
            }
        }
        return List.of();
    }

    private static boolean underPrefix(String declared, String prefix) {
        return declared != null && declared.startsWith(prefix + ".");
    }

    /**
     * Exact match, plus Spring's relaxed binding in the {@code env}/{@code dockerfile} namespaces
     * only: {@code spring.datasource.url} is supplied as {@code SPRING_DATASOURCE_URL}. Without
     * this, every key a deployment declares as an environment variable reads as undefined.
     */
    private static boolean sameKey(String declared, String read, String namespace) {
        if (declared == null) {
            return false;
        }
        if (declared.equals(read)) {
            return true;
        }
        return ("env".equals(namespace) || "dockerfile".equals(namespace))
                && declared.equals(relaxed(read));
    }

    private static String relaxed(String key) {
        return key.replace('.', '_').replace('-', '_').toUpperCase(java.util.Locale.ROOT);
    }

    private static Map<String, List<JConfigKey>> keysByNamespace(Map<String, JArtifact> artifacts) {
        Map<String, List<JConfigKey>> out = new LinkedHashMap<>();
        if (artifacts == null) {
            return out;
        }
        for (JArtifact artifact : artifacts.values()) {
            for (JConfigKey key : artifact.getConfigKeys()) {
                out.computeIfAbsent(key.getNamespace(), k -> new ArrayList<>()).add(key);
            }
        }
        return out;
    }

    // ----------------------------------------------------------------------------------------
    // Small decoders
    // ----------------------------------------------------------------------------------------

    /**
     * The annotation's simple name, matched against the last segment of the qualified name. The
     * decorator records the AST spelling ({@code Value}, or {@code Value} qualified as written), so
     * there is no resolved annotation type to compare against.
     */
    private static boolean isAnnotation(JDecorator decorator, String qualifiedName) {
        String simple = qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1);
        String name = decorator.getName();
        return simple.equals(name) || (name != null && name.endsWith("." + simple));
    }

    /**
     * A decorator argument's value: {@code args} holds {@code "x"} for a single-member annotation
     * and {@code name="x"} for a normal one, so the named members are tried in order and the
     * single-member form is the fallback.
     */
    private static String annotationMember(JDecorator decorator, String... names) {
        List<String> args = decorator.getArgs();
        if (args == null || args.isEmpty()) {
            return null;
        }
        for (String name : names) {
            for (String arg : args) {
                if (arg.startsWith(name + "=")) {
                    return stringLiteral(arg.substring(name.length() + 1));
                }
            }
        }
        return args.size() == 1 && args.get(0).indexOf('=') < 0 ? stringLiteral(args.get(0)) : null;
    }

    /** Decode a Java string-literal expression; {@code null} when the expression is not one. */
    private static String stringLiteral(String expr) {
        if (expr == null || expr.length() < 2 || expr.charAt(0) != '"'
                || expr.charAt(expr.length() - 1) != '"') {
            return null;
        }
        String body = expr.substring(1, expr.length() - 1);
        StringBuilder out = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '"') {
                // An UNESCAPED interior quote means this is not one literal — a concatenation
                // ("a" + "b") arrives here as a single expression — so it belongs in the
                // non-literal bucket rather than being silently truncated to its first half.
                return null;
            }
            if (c == '\\' && i + 1 < body.length()) {
                out.append(body.charAt(++i));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * The {@code @external} ghost for an annotation-driven read. An annotation is not literally a
     * callee, but {@code @Value} injection <em>is</em> a read and the ghost names what performed it,
     * so every unresolved read projects the same {@code JApplication → JExternal} shape as
     * codeanalyzer-python's rather than some reads carrying no endpoint at all.
     */
    private static String ghost(String appName, String annotationType) {
        return CanId.externalId(appName, annotationType, "value()");
    }
}
