package com.ibm.cldk.artifacts;

import com.ibm.cldk.schema.JBodyNode;
import com.ibm.cldk.schema.JCallEdge;
import com.ibm.cldk.schema.JCallable;
import com.ibm.cldk.schema.JDdgEdge;
import com.ibm.cldk.schema.JModule;
import com.ibm.cldk.schema.JType;
import com.ibm.cldk.schema.Span;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The two dataflow tiers the config-use and view-dispatch passes share: closing a bare local over
 * its own callable's DDG (L3, {@link #intra}) and a parameter over every call site that binds it
 * (L4, {@link #interproc}). Both answer the same question — "is this name, at this use, one string
 * literal on every path?" — and both refuse rather than guess when it is not. Hoisted out of
 * {@link ConfigUses} (spec 2026-09-07) unchanged when the view-dispatch pass (#259) needed them.
 */
final class DataflowTiers {

    private DataflowTiers() {}

    /** A callable plus the module source its spans slice, for the dataflow tiers. */
    static final class Owner {
        final JCallable callable;
        final String source;

        Owner(JCallable callable, String source) {
            this.callable = callable;
            this.source = source;
        }
    }


    /** Every callable in the tree with the source its spans slice, keyed by callable id. */
    static Map<String, Owner> owners(Map<String, JModule> modules) {
        Map<String, Owner> out = new LinkedHashMap<>();
        if (modules != null) {
            for (JModule module : modules.values()) {
                collect(module.getTypes(), module.getSource(), out);
            }
        }
        return out;
    }

    private static void collect(Map<String, JType> types, String source, Map<String, Owner> out) {
        if (types == null) {
            return;
        }
        for (JType type : types.values()) {
            for (JCallable c : type.getCallables().values()) {
                out.put(c.getId(), new Owner(c, source));
                collect(c.getTypes(), source, out);
            }
            collect(type.getTypes(), source, out);
        }
    }

    /** L3: the one literal every reaching definition of {@code var} closes on at {@code useLocalId}. */
    static String intra(Owner owner, String useLocalId, String var) {
        if (owner == null || owner.callable.getDdg() == null || owner.source == null) {
            return null;
        }
        return IntraTier.reachingLiteral(owner.callable, owner.source, useLocalId, var);
    }

    /** L4: the one literal every call site binds to the parameter named {@code var}. */
    static String interproc(Owner owner, String var, CallSiteIndex sites, Map<String, Owner> owners) {
        return InterprocTier.close(owner, var, sites, owners);
    }

    /** What {@link #interprocAll} closed on: the union of every caller's targets, and how. */
    static final class Closure {
        final List<String> targets;
        /** True when at least one caller closed through {@code extra} rather than a literal. */
        final boolean viaExtra;

        Closure(List<String> targets, boolean viaExtra) {
            this.targets = targets;
            this.viaExtra = viaExtra;
        }
    }

    /**
     * L4, many-valued: as {@link #interproc}, but every caller's argument may close on a
     * <em>set</em> — a literal, a local traced to one, or whatever {@code extra} makes of the raw
     * argument text (the view-dispatch table tier, #261). Closes only when every caller closes; the
     * result is the union, so a caller the tiers cannot read still refuses the whole binding.
     */
    static Closure interprocAll(Owner owner, String var, CallSiteIndex sites,
            Map<String, Owner> owners, Function<String, List<String>> extra) {
        if (owner == null) {
            return null;
        }
        JCallable c = owner.callable;
        int paramIndex = InterprocTier.parameterIndex(c, var);
        if (paramIndex < 0 || InterprocTier.locallyRedefined(c, var) || c.isEntrypoint()) {
            return null;
        }
        List<CallSiteIndex.Site> targeting = sites.byCallee.get(c.getId());
        if (targeting == null || targeting.isEmpty()
                || sites.unresolvedNames.contains(InterprocTier.simpleName(c))) {
            return null;
        }
        Set<String> union = new LinkedHashSet<>();
        boolean viaExtra = false;
        for (CallSiteIndex.Site site : targeting) {
            String one = InterprocTier.siteLiteral(site, paramIndex, owners);
            if (one != null) {
                union.add(one);
                continue;
            }
            List<String> args = site.node.getArgumentExpr();
            List<String> many = args != null && args.size() > paramIndex
                    ? extra.apply(args.get(paramIndex)) : null;
            if (many == null || many.isEmpty()) {
                return null;
            }
            union.addAll(many);
            viaExtra = true;
        }
        return new Closure(new ArrayList<>(union), viaExtra);
    }

    // ----------------------------------------------------------------------------------------
    // L3 intra tier: close a bare name over its own callable's DDG
    // ----------------------------------------------------------------------------------------

    /**
     * Closes {@code env.getProperty(key)} when every DDG-reaching definition of {@code key} at the
     * call site is one and the same string literal.
     */
    static final class IntraTier {

        private IntraTier() {}


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
        static String reachingLiteral(JCallable c, String source, String useLocalId,
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
                return Literals.literalOf(decl.getVariable(0).getInitializer().orElse(null));
            }
            if (expr.isAssignExpr()) {
                com.github.javaparser.ast.expr.AssignExpr assign = expr.asAssignExpr();
                if (assign.getOperator() != com.github.javaparser.ast.expr.AssignExpr.Operator.ASSIGN
                        || !assign.getTarget().isNameExpr()) {
                    return null;
                }
                return Literals.literalOf(assign.getValue());
            }
            return null;
        }
    }

    // ----------------------------------------------------------------------------------------
    // L4 interprocedural tier: close a parameter over the call graph
    // ----------------------------------------------------------------------------------------

    /** Every in-project {@code call} body node, indexed by the callee it resolved to. */
    static final class CallSiteIndex {
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
    static final class InterprocTier {

        private InterprocTier() {}

        static int parameterIndex(JCallable c, String var) {
            for (int i = 0; i < c.getParameters().size(); i++) {
                if (var.equals(c.getParameters().get(i).getName())) {
                    return i;
                }
            }
            return -1;
        }

        static String close(Owner owner, String var, CallSiteIndex sites, Map<String, Owner> owners) {
            if (owner == null) {
                return null;
            }
            JCallable c = owner.callable;
            int paramIndex = parameterIndex(c, var);
            if (paramIndex < 0 || locallyRedefined(c, var)) {
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
        static String siteLiteral(CallSiteIndex.Site site, int paramIndex,
                Map<String, Owner> owners) {
            List<String> args = site.node.getArgumentExpr();
            if (args == null || args.size() <= paramIndex) {
                return null;
            }
            String arg = args.get(paramIndex);
            String direct = Literals.stringLiteral(arg);
            if (direct != null) {
                return direct;
            }
            // ONE hop only, and deliberately not recursive: a chain of forwarding callers is a
            // fixpoint, not a lookup, and this tier is a lookup.
            if (!Literals.IDENTIFIER.matcher(arg).matches() || site.source == null
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
        static boolean locallyRedefined(JCallable c, String var) {
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

        static String simpleName(JCallable c) {
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

    static int[] bytesOf(JBodyNode node) {
        if (node == null) {
            return null;
        }
        Span span = node.getSpan();
        int[] bytes = span == null ? null : span.getBytes();
        return bytes != null && bytes.length >= 2 ? bytes : null;
    }

    /** UTF-8 byte slice of the module source for a node's span; {@code null} when it has none. */
    static String slice(String source, JBodyNode node) {
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

}
