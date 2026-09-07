package com.ibm.cldk.artifacts;

import com.ibm.cldk.schema.CanId;
import com.ibm.cldk.schema.JArtifact;
import com.ibm.cldk.schema.JBodyNode;
import com.ibm.cldk.schema.JCallEdge;
import com.ibm.cldk.schema.JCallable;
import com.ibm.cldk.schema.JConfigKey;
import com.ibm.cldk.schema.JConfigRead;
import com.ibm.cldk.schema.JConfigUseEdge;
import com.ibm.cldk.schema.JDdgEdge;
import com.ibm.cldk.schema.JDecorator;
import com.ibm.cldk.schema.JField;
import com.ibm.cldk.schema.JModule;
import com.ibm.cldk.schema.JParameter;
import com.ibm.cldk.schema.JType;
import com.ibm.cldk.schema.Span;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
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

    /** Provenance vocabulary, shared with codeanalyzer-python: exactly {@code literal|dataflow}. */
    private static final List<String> LITERAL = List.of("literal");
    private static final List<String> DATAFLOW = List.of("dataflow");
    private static final List<String> LITERAL_AND_DATAFLOW = List.of("literal", "dataflow");

    /** A bare Java identifier — the only key-argument shape a dataflow tier can trace. */
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*$");

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

        // Dataflow anchors — set only for a call-site read whose key argument is a bare name, which
        // is the only shape either tier can close. Null everywhere else (a literal already closed, an
        // annotation read has no callable, a compound expression has no name to trace).
        final String callableId;
        final String localId;
        final String keyName;

        /** True when a dataflow tier, not the call site itself, supplied {@code literal}. */
        final boolean closedByDataflow;

        Read(String site, String callee, String literal, List<String> namespaces, boolean prefix) {
            this(site, callee, literal, namespaces, prefix, null, null, null, false);
        }

        Read(String site, String callee, String literal, List<String> namespaces, boolean prefix,
                String callableId, String localId, String keyName) {
            this(site, callee, literal, namespaces, prefix, callableId, localId, keyName, false);
        }

        Read(String site, String callee, String literal, List<String> namespaces, boolean prefix,
                String callableId, String localId, String keyName, boolean closedByDataflow) {
            this.site = site;
            this.callee = callee;
            this.literal = literal;
            this.namespaces = namespaces;
            this.prefix = prefix;
            this.callableId = callableId;
            this.localId = localId;
            this.keyName = keyName;
            this.closedByDataflow = closedByDataflow;
        }

        /** As this read, closed to {@code closed} by a dataflow tier. */
        Read closedTo(String closed) {
            return new Read(site, callee, closed, namespaces, prefix, callableId, localId, keyName,
                    true);
        }
    }

    /** A callable plus the module source its spans slice, for the dataflow tiers. */
    private static final class Owner {
        final JCallable callable;
        final String source;

        Owner(JCallable callable, String source) {
            this.callable = callable;
            this.source = source;
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
        return detect(appName, modules, artifacts, 1, null);
    }

    /**
     * As above, running the dataflow tiers over the overlays {@code analysisLevel} made available:
     * the intra tier at {@code >= 3} (the callable's own DDG) and the interprocedural tier at
     * {@code >= 4} (that plus {@code callGraph}).
     *
     * <p>Additive by construction: a read the literal tier closed is never handed to a tier, and a
     * tier only ever converts an unresolved read into an edge. So
     * {@code config_uses(-a 1) ⊆ config_uses(-a 3) ⊆ config_uses(-a 4)}, the same contract the DDG's
     * own {@code ssa} → {@code points-to} widening keeps.
     */
    public static Result detect(String appName, Map<String, JModule> modules,
            Map<String, JArtifact> artifacts, int analysisLevel, List<JCallEdge> callGraph) {
        Map<String, List<JConfigKey>> keysByNamespace = keysByNamespace(artifacts);

        List<Read> reads = new ArrayList<>();
        Map<String, Owner> owners = new LinkedHashMap<>();
        if (modules != null) {
            for (JModule module : modules.values()) {
                collectTypes(appName, module.getTypes(), module.getSource(), reads, owners);
            }
        }
        return resolve(reads, keysByNamespace, owners, analysisLevel, callGraph);
    }

    // ----------------------------------------------------------------------------------------
    // Detection
    // ----------------------------------------------------------------------------------------

    private static void collectTypes(String appName, Map<String, JType> types, String source,
            List<Read> reads, Map<String, Owner> owners) {
        if (types == null) {
            return;
        }
        for (JType type : types.values()) {
            collectType(appName, type, source, reads, owners);
        }
    }

    private static void collectType(String appName, JType type, String source, List<Read> reads,
            Map<String, Owner> owners) {
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
            collectCallable(appName, callable, source, reads, owners);
        }
        collectTypes(appName, type.getTypes(), source, reads, owners);
    }

    private static void collectCallable(String appName, JCallable callable, String source,
            List<Read> reads, Map<String, Owner> owners) {
        owners.put(callable.getId(), new Owner(callable, source));
        collectValueAnnotations(appName, callable.getDecorators(), callable.getId(), reads);
        for (JParameter p : callable.getParameters()) {
            // A parameter is not an addressable node in the v2 tree, so an injected parameter's read
            // is attributed to the callable that declares it.
            collectValueAnnotations(appName, p.getDecorators(), callable.getId(), reads);
        }
        for (Map.Entry<String, JBodyNode> e : callable.getBody().entrySet()) {
            collectCallSite(appName, callable.getId(), e.getKey(), e.getValue(), reads);
        }
        collectTypes(appName, callable.getTypes(), source, reads, owners);
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
            String arg = args != null && args.size() > rule.keyArg ? args.get(rule.keyArg) : null;
            String literal = stringLiteral(arg);
            // The bare name a dataflow tier can trace. Only a plain identifier qualifies: a field
            // access or any compound expression has no single local for the DDG to close over.
            String keyName = literal == null && arg != null && IDENTIFIER.matcher(arg).matches()
                    ? arg
                    : null;
            String signature = node.getCalleeSignature() != null
                    ? node.getCalleeSignature()
                    : node.getMethodName();
            reads.add(new Read(CanId.ordinalId(callableId, localId),
                    CanId.externalId(appName, rule.declaringType, signature),
                    literal, rule.namespaces, false, callableId, localId, keyName));
            return;
        }
    }

    // ----------------------------------------------------------------------------------------
    // Resolution
    // ----------------------------------------------------------------------------------------

    private static Result resolve(List<Read> reads, Map<String, List<JConfigKey>> keysByNamespace,
            Map<String, Owner> owners, int analysisLevel, List<JCallEdge> callGraph) {
        List<JConfigUseEdge> uses = new ArrayList<>();
        List<JConfigRead> unresolved = new ArrayList<>();

        // `prov` lists every tier ATTEMPTED, not only the one that closed a given read, so a
        // still-unresolved record says how hard the analyzer tried before giving up.
        List<String> attempted = analysisLevel >= 3 ? LITERAL_AND_DATAFLOW : LITERAL;

        // Tiers run over what the previous tier could not close, in increasing cost. A read closed at
        // a lower tier is never recomputed, which is what makes the level chain a superset chain.
        List<Read> closed = new ArrayList<>();
        List<Read> pending = new ArrayList<>();
        for (Read read : reads) {
            (read.literal != null ? closed : pending).add(read);
        }
        if (analysisLevel >= 3) {
            pending = runTier(pending, closed, read -> IntraTier.close(read, owners));
        }
        if (analysisLevel >= 4) {
            CallSiteIndex sites = new CallSiteIndex(owners, callGraph);
            pending = runTier(pending, closed, read -> InterprocTier.close(read, owners, sites));
        }

        for (Read read : closed) {
            List<JConfigKey> matched = match(read, keysByNamespace);
            if (matched.isEmpty()) {
                unresolved.add(unresolvedRead(read, read.literal, "undefined-key", attempted));
                continue;
            }
            // One edge per matching key, not "exactly one match or nothing" (the same rule
            // codeanalyzer-python's resolver follows): one key legitimately appears in several
            // artifacts -- application.properties and application-dev.properties -- and a
            // `@ConfigurationProperties` prefix claims every key beneath it by design.
            for (JConfigKey key : matched) {
                JConfigUseEdge edge = new JConfigUseEdge();
                edge.setSrc(read.site);
                edge.setDst(key.getId());
                // The tier that CLOSED this read, not every tier attempted: an edge's provenance is
                // where its literal came from. A literal-tier edge therefore reads `["literal"]` at
                // every analysis level, which is what makes the superset chain checkable by equality
                // rather than only by (src, dst).
                edge.setProv(new ArrayList<>(read.closedByDataflow ? DATAFLOW : LITERAL));
                uses.add(edge);
            }
        }
        for (Read read : pending) {
            unresolved.add(unresolvedRead(read, null, "non-literal", attempted));
        }

        uses.sort(Comparator.comparing(JConfigUseEdge::getSrc).thenComparing(JConfigUseEdge::getDst));
        unresolved.sort(Comparator.comparing(JConfigRead::getSite)
                .thenComparing(JConfigRead::getReason)
                .thenComparing(u -> u.getKey() == null ? "" : u.getKey()));
        return new Result(uses, unresolved);
    }

    /**
     * Run one tier over {@code pending}, moving what it closes into {@code closed} and returning the
     * rest. A read the tier traces to a literal leaves the pending set even if that literal matches
     * no declared key: "traced to {@code foo}, which nobody declares" is a different and more useful
     * fact than "could not trace it".
     */
    private static List<Read> runTier(List<Read> pending, List<Read> closed,
            Function<Read, String> tier) {
        List<Read> stillPending = new ArrayList<>();
        for (Read read : pending) {
            String literal = read.keyName == null ? null : tier.apply(read);
            if (literal == null) {
                stillPending.add(read);
            } else {
                closed.add(read.closedTo(literal));
            }
        }
        return stillPending;
    }

    private static JConfigRead unresolvedRead(Read read, String key, String reason,
            List<String> attempted) {
        JConfigRead r = new JConfigRead();
        r.setSite(read.site);
        r.setCallee(read.callee);
        r.setKey(key);
        r.setReason(reason);
        r.setProv(new ArrayList<>(attempted));
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

    // ----------------------------------------------------------------------------------------
    // L3 intra tier: close a bare name over its own callable's DDG
    // ----------------------------------------------------------------------------------------

    /**
     * Closes {@code env.getProperty(key)} when every DDG-reaching definition of {@code key} at the
     * call site is one and the same string literal.
     */
    private static final class IntraTier {

        private IntraTier() {}

        static String close(Read read, Map<String, Owner> owners) {
            Owner owner = owners.get(read.callableId);
            if (owner == null || owner.callable.getDdg() == null || owner.source == null) {
                return null;
            }
            return reachingLiteral(owner.callable, owner.source, read.localId, read.keyName);
        }

        /**
         * The one string literal every reaching definition of {@code var} closes on at
         * {@code useLocalId} — {@code null} if nothing reaches, if any reaching def is not a literal
         * assignment, or if two reaching defs disagree.
         *
         * <p><b>Only {@code ssa} edges are consulted, and that is load-bearing.</b> At {@code -a 4}
         * the ddg also carries {@code points-to} edges, which may-alias this variable's use to an
         * unrelated write that is not a {@code name = "literal"} shape. Since any non-closing
         * reaching def kills resolution, letting an alias edge in would REMOVE an edge the ssa-only
         * L3 set resolved cleanly — a widening that breaks {@code -a 3 ⊆ -a 4}, which is exactly what
         * the additive contract forbids. A bare local can only be rebound by its own name, so
         * widening past {@code ssa} here adds no soundness, only noise.
         *
         * <p><b>Span containment, not id equality.</b> The CFG/DDG is statement-level while a
         * {@code call} body node is keyed by its own narrower span, so a def's recorded <em>use</em>
         * site is the enclosing statement — which coincides with the call's own id only when the call
         * is a bare expression statement. Containment covers that and the common
         * {@code return env.getProperty(key);} nesting without special-casing either.
         */
        private static String reachingLiteral(JCallable c, String source, String useLocalId,
                String var) {
            JBodyNode use = c.getBody().get(useLocalId);
            int[] useBytes = bytesOf(use);
            if (useBytes == null) {
                return null;
            }
            Set<String> literals = new LinkedHashSet<>();
            boolean reached = false;
            for (JDdgEdge edge : c.getDdg()) {
                if (!var.equals(edge.getVar()) || !edge.getProv().contains("ssa")) {
                    continue;
                }
                int[] dstBytes = bytesOf(c.getBody().get(edge.getDst()));
                if (dstBytes == null
                        || !(dstBytes[0] <= useBytes[0] && useBytes[1] <= dstBytes[1])) {
                    continue; // some other reference to `var`, not this call's
                }
                reached = true;
                String literal = assignLiteral(source, c.getBody().get(edge.getSrc()));
                if (literal == null) {
                    // Any non-closing reaching def kills the resolution rather than being skipped:
                    // two paths assigning different things means the read is genuinely ambiguous, and
                    // picking one would be a confident wrong answer.
                    return null;
                }
                literals.add(literal);
            }
            return reached && literals.size() == 1 ? literals.iterator().next() : null;
        }

        /**
         * The string constant a single definition closes on. Accepts only a single-target
         * {@code <name> = "literal"} — a declarator with one variable, or a plain assignment. A
         * compound assignment, a multi-declarator statement, or a formal-parameter binding (no span)
         * correctly never closes.
         */
        private static String assignLiteral(String source, JBodyNode def) {
            String text = slice(source, def);
            if (text == null) {
                return null;
            }
            com.github.javaparser.ast.stmt.Statement stmt;
            try {
                stmt = com.github.javaparser.StaticJavaParser.parseStatement(
                        text.endsWith(";") ? text : text + ";");
            } catch (RuntimeException e) {
                return null;
            }
            if (!stmt.isExpressionStmt()) {
                return null;
            }
            com.github.javaparser.ast.expr.Expression expr = stmt.asExpressionStmt().getExpression();
            if (expr.isVariableDeclarationExpr()) {
                com.github.javaparser.ast.expr.VariableDeclarationExpr decl =
                        expr.asVariableDeclarationExpr();
                if (decl.getVariables().size() != 1) {
                    return null;
                }
                return literalOf(decl.getVariable(0).getInitializer().orElse(null));
            }
            if (expr.isAssignExpr()) {
                com.github.javaparser.ast.expr.AssignExpr assign = expr.asAssignExpr();
                if (assign.getOperator() != com.github.javaparser.ast.expr.AssignExpr.Operator.ASSIGN
                        || !assign.getTarget().isNameExpr()) {
                    return null;
                }
                return literalOf(assign.getValue());
            }
            return null;
        }
    }

    // ----------------------------------------------------------------------------------------
    // L4 interprocedural tier: close a parameter over the call graph
    // ----------------------------------------------------------------------------------------

    /** Every in-project {@code call} body node, indexed by the callee it resolved to. */
    private static final class CallSiteIndex {
        /** callee id → the call sites targeting it, each with its owning callable. */
        final Map<String, List<Site>> byCallee = new LinkedHashMap<>();
        /** Simple names of calls whose callee never resolved — the completeness spoilers. */
        final Set<String> unresolvedNames = new LinkedHashSet<>();

        static final class Site {
            final JCallable caller;
            final String source;
            final String localId;
            final JBodyNode node;

            Site(JCallable caller, String source, String localId, JBodyNode node) {
                this.caller = caller;
                this.source = source;
                this.localId = localId;
                this.node = node;
            }
        }

        CallSiteIndex(Map<String, Owner> owners, List<JCallEdge> callGraph) {
            for (Owner owner : owners.values()) {
                for (Map.Entry<String, JBodyNode> e : owner.callable.getBody().entrySet()) {
                    JBodyNode node = e.getValue();
                    if (!"call".equals(node.getKind())) {
                        continue;
                    }
                    if (node.getCallee() == null) {
                        if (node.getMethodName() != null) {
                            unresolvedNames.add(node.getMethodName());
                        }
                        continue;
                    }
                    byCallee.computeIfAbsent(node.getCallee(), k -> new ArrayList<>())
                            .add(new Site(owner.callable, owner.source, e.getKey(), node));
                }
            }
        }
    }

    /**
     * Closes {@code String read(String name) { return System.getenv(name); }} when {@code name} is a
     * parameter that the callable never rebinds and <em>every</em> call site targeting it supplies
     * the same literal.
     *
     * <p><b>Every</b> is the word doing the work. A callee whose call-site set is incomplete must not
     * close: a caller the analyzer could not see may supply a different key, and answering from the
     * callers it did see would be a confident wrong answer. Known ceiling, stated rather than
     * papered over: a {@code public} method can be called from outside the analyzed project
     * entirely, which no in-project call graph can rule out — the same whole-application assumption
     * codeanalyzer-python's tier makes.
     */
    private static final class InterprocTier {

        private InterprocTier() {}

        static String close(Read read, Map<String, Owner> owners, CallSiteIndex sites) {
            Owner owner = owners.get(read.callableId);
            if (owner == null) {
                return null;
            }
            JCallable c = owner.callable;
            int paramIndex = -1;
            for (int i = 0; i < c.getParameters().size(); i++) {
                if (read.keyName.equals(c.getParameters().get(i).getName())) {
                    paramIndex = i;
                    break;
                }
            }
            if (paramIndex < 0 || locallyRedefined(c, read.keyName)) {
                return null;
            }
            // A framework, not an in-project caller, supplies an entrypoint's arguments, so its
            // call-site set is complete only by accident.
            if (c.isEntrypoint()) {
                return null;
            }
            List<CallSiteIndex.Site> targeting = sites.byCallee.get(c.getId());
            if (targeting == null || targeting.isEmpty()
                    || sites.unresolvedNames.contains(simpleName(c))) {
                return null;
            }
            Set<String> literals = new LinkedHashSet<>();
            for (CallSiteIndex.Site site : targeting) {
                String literal = siteLiteral(site, paramIndex, owners);
                if (literal == null) {
                    return null;
                }
                literals.add(literal);
            }
            return literals.size() == 1 ? literals.iterator().next() : null;
        }

        /** The literal a call site passes at {@code paramIndex}, directly or via one caller-side hop. */
        private static String siteLiteral(CallSiteIndex.Site site, int paramIndex,
                Map<String, Owner> owners) {
            List<String> args = site.node.getArgumentExpr();
            if (args == null || args.size() <= paramIndex) {
                return null;
            }
            String arg = args.get(paramIndex);
            String direct = stringLiteral(arg);
            if (direct != null) {
                return direct;
            }
            // ONE hop only, and deliberately not recursive: a chain of forwarding callers is a
            // fixpoint, not a lookup, and this tier is a lookup.
            if (!IDENTIFIER.matcher(arg).matches() || site.source == null
                    || site.caller.getDdg() == null) {
                return null;
            }
            return IntraTier.reachingLiteral(site.caller, site.source, site.localId, arg);
        }

        /**
         * Whether {@code var} is rebound anywhere in the body. A parameter's only definition should be
         * the synthetic formal binding, which carries no span; a def with a real span means a caller's
         * argument is not provably what the read sees.
         */
        private static boolean locallyRedefined(JCallable c, String var) {
            if (c.getDdg() == null) {
                return false;
            }
            for (JDdgEdge edge : c.getDdg()) {
                if (var.equals(edge.getVar()) && bytesOf(c.getBody().get(edge.getSrc())) != null) {
                    return true;
                }
            }
            return false;
        }

        private static String simpleName(JCallable c) {
            String signature = c.getSignature();
            if (signature == null) {
                return "";
            }
            int paren = signature.indexOf('(');
            return paren < 0 ? signature : signature.substring(0, paren);
        }
    }

    // ----------------------------------------------------------------------------------------
    // Span slicing shared by both tiers
    // ----------------------------------------------------------------------------------------

    private static int[] bytesOf(JBodyNode node) {
        if (node == null) {
            return null;
        }
        Span span = node.getSpan();
        int[] bytes = span == null ? null : span.getBytes();
        return bytes != null && bytes.length >= 2 ? bytes : null;
    }

    /** UTF-8 byte slice of the module source for a node's span; {@code null} when it has none. */
    private static String slice(String source, JBodyNode node) {
        int[] bytes = bytesOf(node);
        if (source == null || bytes == null) {
            return null;
        }
        byte[] raw = source.getBytes(StandardCharsets.UTF_8);
        if (bytes[0] < 0 || bytes[1] > raw.length || bytes[0] >= bytes[1]) {
            return null;
        }
        return new String(raw, bytes[0], bytes[1] - bytes[0], StandardCharsets.UTF_8);
    }

    private static String literalOf(com.github.javaparser.ast.expr.Expression expr) {
        return expr != null && expr.isStringLiteralExpr()
                ? unescape(expr.asStringLiteralExpr().getValue())
                : null;
    }

    /** Decode a Java string-literal expression; {@code null} when the expression is not one. */
    private static String stringLiteral(String expr) {
        if (expr == null || expr.length() < 2 || expr.charAt(0) != '"'
                || expr.charAt(expr.length() - 1) != '"') {
            return null;
        }
        return unescape(expr.substring(1, expr.length() - 1), true);
    }

    /** Decode Java escapes in a literal's body; JavaParser hands back the raw escaped text. */
    private static String unescape(String body) {
        return unescape(body, false);
    }

    private static String unescape(String body, boolean rejectInteriorQuote) {
        StringBuilder out = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '"' && rejectInteriorQuote) {
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
