package com.ibm.cldk.artifacts;

import com.ibm.cldk.schema.CanId;
import com.ibm.cldk.schema.JArtifact;
import com.ibm.cldk.schema.JBodyNode;
import com.ibm.cldk.schema.JCallEdge;
import com.ibm.cldk.schema.JCallable;
import com.ibm.cldk.schema.JConfigKey;
import com.ibm.cldk.schema.JModule;
import com.ibm.cldk.schema.JType;
import com.ibm.cldk.schema.JViewDispatchEdge;
import com.ibm.cldk.schema.JViewDispatchUnresolved;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The view-dispatch pass (#259, spec 2026-09-11): joins the body nodes that hand a request to a view
 * — {@code RequestDispatcher.forward} / {@code include}, {@code HttpServletResponse.sendRedirect} —
 * to the {@link JArtifact} they reach, and records the ones that reach none. The same shape as
 * {@link ConfigUses}: pure tree-in/records-out, a literal tier at every level, and it never
 * guesses — a target closes on exactly one artifact or it is recorded unresolved with a reason.
 *
 * <p>Detection is by <em>declared receiver type</em>, never by bare method name: {@code forward}
 * and {@code include} are ordinary names, and claiming a project's own would be a confident wrong
 * edge. The consequence is that the servlet API must resolve for the pass to see anything, which is
 * the same condition {@link ConfigUses} already lives with.
 */
public final class ViewDispatches {

    private ViewDispatches() {}

    /** Provenance vocabulary, shared with {@link ConfigUses}: exactly {@code literal|dataflow}. */
    private static final List<String> LITERAL = List.of("literal");
    private static final List<String> DATAFLOW = List.of("dataflow");
    private static final List<String> LITERAL_AND_DATAFLOW = List.of("literal", "dataflow");

    private static final Set<String> DISPATCHER_TYPES = Set.of(
            "javax.servlet.RequestDispatcher", "jakarta.servlet.RequestDispatcher");
    private static final Set<String> RESPONSE_TYPES = Set.of(
            "javax.servlet.http.HttpServletResponse", "jakarta.servlet.http.HttpServletResponse");
    private static final String MODEL_AND_VIEW = "org.springframework.web.servlet.ModelAndView";
    private static final String STRING = "java.lang.String";

    /** Thymeleaf's own defaults, applied when the application declares no resolver keys. */
    private static final String THYMELEAF_PREFIX = "classpath:/templates/";
    private static final String THYMELEAF_SUFFIX = ".html";

    public static final class Result {
        public final List<JViewDispatchEdge> dispatches;
        public final List<JViewDispatchUnresolved> unresolved;

        Result(List<JViewDispatchEdge> dispatches, List<JViewDispatchUnresolved> unresolved) {
            this.dispatches = dispatches;
            this.unresolved = unresolved;
        }
    }

    /** One detected dispatch site, before resolution. */
    private static final class Site {
        final String id;
        final String callee;
        final String via;
        /** The target expression's source text; {@code null} when the site has none to read. */
        final String targetExpr;
        // Dataflow anchors, for a target that is a bare name — the only shape a tier can trace.
        final String callableId;
        final String localId;
        /** The literal the target closed on, by the call site itself or by a tier; else null. */
        final String literal;
        final boolean closedByDataflow;

        Site(String id, String callee, String via, String targetExpr, String callableId,
                String localId) {
            this(id, callee, via, targetExpr, callableId, localId,
                    Literals.stringLiteral(targetExpr), false);
        }

        private Site(String id, String callee, String via, String targetExpr, String callableId,
                String localId, String literal, boolean closedByDataflow) {
            this.id = id;
            this.callee = callee;
            this.via = via;
            this.targetExpr = targetExpr;
            this.callableId = callableId;
            this.localId = localId;
            this.literal = literal;
            this.closedByDataflow = closedByDataflow;
        }

        /** The bare identifier a tier can trace, or null for a literal or a compound expression. */
        String varName() {
            return literal == null && targetExpr != null
                    && Literals.IDENTIFIER.matcher(targetExpr).matches() ? targetExpr : null;
        }

        Site closedTo(String traced) {
            return new Site(id, callee, via, targetExpr, callableId, localId, traced, true);
        }
    }

    public static Result detect(String appName, Map<String, JModule> modules,
            Map<String, JArtifact> artifacts) {
        return detect(appName, modules, artifacts, 1, null);
    }

    public static Result detect(String appName, Map<String, JModule> modules,
            Map<String, JArtifact> artifacts, int analysisLevel, List<JCallEdge> callGraph) {
        List<Site> sites = new ArrayList<>();
        if (modules != null) {
            for (JModule module : modules.values()) {
                collectTypes(appName, module.getTypes(), sites);
            }
        }
        return resolve(sites, artifacts, modules, analysisLevel, callGraph);
    }

    // ----------------------------------------------------------------------------------------
    // Detection
    // ----------------------------------------------------------------------------------------

    private static void collectTypes(String appName, Map<String, JType> types, List<Site> sites) {
        if (types == null) {
            return;
        }
        for (JType type : types.values()) {
            boolean springType = type.getEntrypointFrameworks().contains("spring");
            for (JCallable callable : type.getCallables().values()) {
                // The view-name gate (spec D4): only a Spring entrypoint's `return "x"` is a view
                // name, so `return "home"` in ordinary code never matches a template named home.
                boolean viewNames = springType || callable.getEntrypointFrameworks().contains("spring");
                for (Map.Entry<String, JBodyNode> e : callable.getBody().entrySet()) {
                    Site site = siteOf(appName, callable, e.getKey(), e.getValue(), viewNames);
                    if (site != null) {
                        sites.add(site);
                    }
                }
                collectTypes(appName, callable.getTypes(), sites);
            }
            collectTypes(appName, type.getTypes(), sites);
        }
    }

    private static Site siteOf(String appName, JCallable callable, String localId, JBodyNode node,
            boolean viewNames) {
        String callableId = callable.getId();
        List<String> args = node.getArgumentExpr();
        String arg0 = args != null && !args.isEmpty() ? args.get(0) : null;
        if ("return".equals(node.getKind())) {
            // A String-returning controller method's return IS the view name. A ModelAndView-returning
            // one is anchored on the construction / setViewName site instead, which carries the name.
            if (!viewNames || arg0 == null || !STRING.equals(callable.getReturnType())) {
                return null;
            }
            return new Site(CanId.ordinalId(callableId, localId), callableId, "view-name", arg0,
                    callableId, localId);
        }
        if (!"call".equals(node.getKind())) {
            return null;
        }
        String receiver = node.getReceiverType();
        String method = node.getMethodName();
        String via;
        String target;
        if (node.isConstructorCall() && MODEL_AND_VIEW.equals(receiver)) {
            if (arg0 == null) {
                return null; // `new ModelAndView()` names no view; setViewName will
            }
            via = "view-name";
            target = arg0;
            method = "<init>";
        } else if (method == null) {
            return null;
        } else if ("setViewName".equals(method) && MODEL_AND_VIEW.equals(receiver)) {
            via = "view-name";
            target = arg0;
        } else if (("forward".equals(method) || "include".equals(method))
                && DISPATCHER_TYPES.contains(receiver)) {
            via = method;
            target = dispatcherArgument(node.getReceiverExpr());
        } else if ("sendRedirect".equals(method) && RESPONSE_TYPES.contains(receiver)) {
            via = "redirect";
            target = arg0;
        } else {
            return null;
        }
        String signature = node.getCalleeSignature() != null ? node.getCalleeSignature() : method;
        return new Site(CanId.ordinalId(callableId, localId),
                CanId.externalId(appName, receiver, signature), via, target, callableId, localId);
    }

    /**
     * The argument of the {@code getRequestDispatcher(...)} / {@code getNamedDispatcher(...)} call
     * that produced a dispatcher, read off the dispatching call's receiver expression — the chain
     * {@code ctx.getRequestDispatcher("/x.jsp")} ends at that call. A receiver that is not such a
     * chain (a dispatcher held in a local) has no target to read here.
     */
    // ponytail: a dispatcher stored in a local (`rd.forward(...)`) is non-literal; trace `rd` to its
    // `getRequestDispatcher` initializer if that shape shows up in real code.
    private static String dispatcherArgument(String receiverExpr) {
        if (receiverExpr == null || !receiverExpr.endsWith(")")) {
            return null;
        }
        int at = Math.max(receiverExpr.lastIndexOf("getRequestDispatcher("),
                receiverExpr.lastIndexOf("getNamedDispatcher("));
        if (at < 0) {
            return null;
        }
        int open = receiverExpr.indexOf('(', at);
        String arg = receiverExpr.substring(open + 1, receiverExpr.length() - 1).trim();
        return arg.isEmpty() ? null : arg;
    }

    // ----------------------------------------------------------------------------------------
    // Resolution
    // ----------------------------------------------------------------------------------------

    /**
     * Tiers run over what the previous one could not close, in increasing cost, exactly as in
     * {@link ConfigUses}: a site closed at a lower tier is never recomputed, so
     * {@code view_dispatches(-a 1) ⊆ view_dispatches(-a 3) ⊆ view_dispatches(-a 4)}.
     */
    private static Result resolve(List<Site> sites, Map<String, JArtifact> artifacts,
            Map<String, JModule> modules, int analysisLevel, List<JCallEdge> callGraph) {
        List<JViewDispatchEdge> dispatches = new ArrayList<>();
        List<JViewDispatchUnresolved> unresolved = new ArrayList<>();
        List<String> attempted = analysisLevel >= 3 ? LITERAL_AND_DATAFLOW : LITERAL;

        List<Site> closed = new ArrayList<>();
        List<Site> pending = new ArrayList<>();
        for (Site site : sites) {
            (site.literal != null ? closed : pending).add(site);
        }
        if (analysisLevel >= 3 && !pending.isEmpty()) {
            Map<String, DataflowTiers.Owner> owners = DataflowTiers.owners(modules);
            pending = runTier(pending, closed,
                    s -> DataflowTiers.intra(owners.get(s.callableId), s.localId, s.varName()));
            if (analysisLevel >= 4) {
                DataflowTiers.CallSiteIndex index = new DataflowTiers.CallSiteIndex(owners, callGraph);
                pending = runTier(pending, closed,
                        s -> DataflowTiers.interproc(owners.get(s.callableId), s.varName(), index, owners));
            }
        }

        List<String[]> resolvers = viewResolvers(artifacts);
        for (Site site : closed) {
            String via = site.via;
            String literal = site.literal;
            List<JArtifact> matched;
            if (!"view-name".equals(via)) {
                matched = matchPath(literal, artifacts);
            } else if (literal.startsWith("redirect:") || literal.startsWith("forward:")) {
                // Spring's special view-name prefixes re-dispatch as path targets (spec D4.1).
                via = literal.startsWith("redirect:") ? "redirect" : "forward";
                literal = literal.substring(literal.indexOf(':') + 1);
                matched = matchPath(literal, artifacts);
            } else {
                matched = matchViewName(literal, resolvers, artifacts);
            }
            if (matched.size() == 1) {
                JViewDispatchEdge edge = new JViewDispatchEdge();
                edge.setSrc(site.id);
                edge.setDst(matched.get(0).getId());
                edge.setVia(via);
                // The tier that CLOSED this site, not every tier attempted (same rule as config_uses).
                edge.setProv(new ArrayList<>(site.closedByDataflow ? DATAFLOW : LITERAL));
                dispatches.add(edge);
            } else {
                unresolved.add(unresolved(site, site.literal,
                        matched.isEmpty() ? "no-such-artifact" : "ambiguous", attempted));
            }
        }
        for (Site site : pending) {
            unresolved.add(unresolved(site, null, "non-literal", attempted));
        }
        dispatches.sort(Comparator.comparing(JViewDispatchEdge::getSrc)
                .thenComparing(JViewDispatchEdge::getDst));
        unresolved.sort(Comparator.comparing(JViewDispatchUnresolved::getSite)
                .thenComparing(JViewDispatchUnresolved::getReason)
                .thenComparing(u -> u.getTarget() == null ? "" : u.getTarget()));
        return new Result(dispatches, unresolved);
    }

    /**
     * The artifacts a servlet-context-relative path names: those whose repo-relative path is the
     * path itself or ends with {@code /<path>}, segment-aligned. Any artifact qualifies, not only a
     * view template — a redirect to a static page is still a dispatch to a file. A URL scheme or a
     * query string is not a file: the query is dropped, a scheme never matches.
     */
    private static List<JArtifact> matchPath(String target, Map<String, JArtifact> artifacts) {
        List<JArtifact> out = new ArrayList<>();
        if (artifacts == null || target.contains("://")) {
            return out;
        }
        String path = target;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.isEmpty()) {
            return out;
        }
        for (JArtifact a : artifacts.values()) {
            String p = a.getPath();
            if (p.equals(path) || p.endsWith("/" + path)) {
                out.add(a);
            }
        }
        return out;
    }

    /**
     * The {@code (prefix, suffix)} pairs a view name is expanded through (spec D4.2): the declared
     * {@code spring.mvc.view.*} pair when either key is a literal, and the {@code spring.thymeleaf.*}
     * pair — declared, or Thymeleaf's defaults when not. A key whose value is a {@code ${...}}
     * placeholder is not a literal and contributes nothing; the pass does not guess what it binds to.
     */
    private static List<String[]> viewResolvers(Map<String, JArtifact> artifacts) {
        Map<String, String> keys = new java.util.HashMap<>();
        if (artifacts != null) {
            for (JArtifact a : artifacts.values()) {
                for (JConfigKey k : a.getConfigKeys()) {
                    if (k.getValue() != null && !k.getValue().contains("${")) {
                        keys.putIfAbsent(k.getKey(), k.getValue());
                    }
                }
            }
        }
        List<String[]> out = new ArrayList<>();
        if (keys.containsKey("spring.mvc.view.prefix") || keys.containsKey("spring.mvc.view.suffix")) {
            out.add(new String[] {keys.getOrDefault("spring.mvc.view.prefix", ""),
                    keys.getOrDefault("spring.mvc.view.suffix", "")});
        }
        out.add(new String[] {keys.getOrDefault("spring.thymeleaf.prefix", THYMELEAF_PREFIX),
                keys.getOrDefault("spring.thymeleaf.suffix", THYMELEAF_SUFFIX)});
        return out;
    }

    /** Every artifact any resolver expands {@code name} to; more than one is ambiguous, none is absent. */
    private static List<JArtifact> matchViewName(String name, List<String[]> resolvers,
            Map<String, JArtifact> artifacts) {
        Map<String, JArtifact> out = new java.util.LinkedHashMap<>();
        for (String[] r : resolvers) {
            String prefix = r[0];
            for (String scheme : List.of("classpath:", "file:")) {
                if (prefix.startsWith(scheme)) {
                    prefix = prefix.substring(scheme.length());
                }
            }
            for (JArtifact a : matchPath(prefix + name + r[1], artifacts)) {
                out.putIfAbsent(a.getId(), a);
            }
        }
        return new ArrayList<>(out.values());
    }

    private static List<Site> runTier(List<Site> pending, List<Site> closed,
            Function<Site, String> tier) {
        List<Site> still = new ArrayList<>();
        for (Site site : pending) {
            String traced = site.varName() == null ? null : tier.apply(site);
            if (traced == null) {
                still.add(site);
            } else {
                closed.add(site.closedTo(traced));
            }
        }
        return still;
    }

    private static JViewDispatchUnresolved unresolved(Site site, String target, String reason,
            List<String> attempted) {
        JViewDispatchUnresolved u = new JViewDispatchUnresolved();
        u.setSite(site.id);
        u.setCallee(site.callee);
        u.setTarget(target);
        u.setVia(site.via);
        u.setReason(reason);
        u.setProv(new ArrayList<>(attempted));
        return u;
    }
}
