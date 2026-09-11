package com.ibm.cldk.artifacts;

import com.ibm.cldk.schema.CanId;
import com.ibm.cldk.schema.JArtifact;
import com.ibm.cldk.schema.JBodyNode;
import com.ibm.cldk.schema.JCallEdge;
import com.ibm.cldk.schema.JCallable;
import com.ibm.cldk.schema.JModule;
import com.ibm.cldk.schema.JType;
import com.ibm.cldk.schema.JViewDispatchEdge;
import com.ibm.cldk.schema.JViewDispatchUnresolved;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private static final List<String> LITERAL = List.of("literal");

    private static final Set<String> DISPATCHER_TYPES = Set.of(
            "javax.servlet.RequestDispatcher", "jakarta.servlet.RequestDispatcher");
    private static final Set<String> RESPONSE_TYPES = Set.of(
            "javax.servlet.http.HttpServletResponse", "jakarta.servlet.http.HttpServletResponse");

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

        Site(String id, String callee, String via, String targetExpr) {
            this.id = id;
            this.callee = callee;
            this.via = via;
            this.targetExpr = targetExpr;
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
        return resolve(sites, artifacts);
    }

    // ----------------------------------------------------------------------------------------
    // Detection
    // ----------------------------------------------------------------------------------------

    private static void collectTypes(String appName, Map<String, JType> types, List<Site> sites) {
        if (types == null) {
            return;
        }
        for (JType type : types.values()) {
            for (JCallable callable : type.getCallables().values()) {
                for (Map.Entry<String, JBodyNode> e : callable.getBody().entrySet()) {
                    Site site = siteOf(appName, callable.getId(), e.getKey(), e.getValue());
                    if (site != null) {
                        sites.add(site);
                    }
                }
                collectTypes(appName, callable.getTypes(), sites);
            }
            collectTypes(appName, type.getTypes(), sites);
        }
    }

    private static Site siteOf(String appName, String callableId, String localId, JBodyNode node) {
        if (!"call".equals(node.getKind()) || node.getMethodName() == null) {
            return null;
        }
        String method = node.getMethodName();
        String receiver = node.getReceiverType();
        String via;
        String target;
        if (("forward".equals(method) || "include".equals(method))
                && DISPATCHER_TYPES.contains(receiver)) {
            via = method;
            target = dispatcherArgument(node.getReceiverExpr());
        } else if ("sendRedirect".equals(method) && RESPONSE_TYPES.contains(receiver)) {
            via = "redirect";
            List<String> args = node.getArgumentExpr();
            target = args != null && !args.isEmpty() ? args.get(0) : null;
        } else {
            return null;
        }
        String signature = node.getCalleeSignature() != null ? node.getCalleeSignature() : method;
        return new Site(CanId.ordinalId(callableId, localId),
                CanId.externalId(appName, receiver, signature), via, target);
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

    private static Result resolve(List<Site> sites, Map<String, JArtifact> artifacts) {
        List<JViewDispatchEdge> dispatches = new ArrayList<>();
        List<JViewDispatchUnresolved> unresolved = new ArrayList<>();
        for (Site site : sites) {
            String literal = Literals.stringLiteral(site.targetExpr);
            if (literal == null) {
                unresolved.add(unresolved(site, null, "non-literal"));
                continue;
            }
            List<JArtifact> matched = matchPath(literal, artifacts);
            if (matched.size() == 1) {
                JViewDispatchEdge edge = new JViewDispatchEdge();
                edge.setSrc(site.id);
                edge.setDst(matched.get(0).getId());
                edge.setVia(site.via);
                edge.setProv(new ArrayList<>(LITERAL));
                dispatches.add(edge);
            } else {
                unresolved.add(unresolved(site, literal,
                        matched.isEmpty() ? "no-such-artifact" : "ambiguous"));
            }
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

    private static JViewDispatchUnresolved unresolved(Site site, String target, String reason) {
        JViewDispatchUnresolved u = new JViewDispatchUnresolved();
        u.setSite(site.id);
        u.setCallee(site.callee);
        u.setTarget(target);
        u.setVia(site.via);
        u.setReason(reason);
        u.setProv(new ArrayList<>(LITERAL));
        return u;
    }
}
