package com.ibm.cldk.artifacts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.cldk.schema.CanId;
import com.ibm.cldk.schema.JArtifact;
import com.ibm.cldk.schema.JModule;
import com.ibm.cldk.schema.JViewDispatchUnresolved;
import com.ibm.cldk.syntactic_analysis.L1Extractor;
import com.ibm.cldk.syntactic_analysis.L2CallGraph;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The view-dispatch dataflow tiers: closing a non-literal target over the callable's own DDG (L3)
 * and over the call graph (L4), with the refusals that keep the pass from guessing. Same shape as
 * {@link ConfigDataflowTierTest}.
 */
class ViewDispatchDataflowTierTest {

    private static final String APP = "view-dataflow-test";

    @TempDir
    Path root;

    private ViewDispatches.Result run(String source, int analysisLevel) throws Exception {
        ServletApiStubs.write(root);
        ServletApiStubs.write(root, "src/main/webapp/pages/x.jsp", "<%= 1 %>");
        ServletApiStubs.write(root, "src/main/webapp/y.jsp", "<%= 2 %>");
        ServletApiStubs.write(root, "src/main/java/demo/Front.java", source);
        Map<String, JModule> modules = L1Extractor.extractAll(
                root, APP, null, new LinkedHashMap<>(), Math.max(analysisLevel, 1), 3, "ast");
        Map<String, JArtifact> artifacts = ArtifactDiscovery.discover(root, APP, true, 262144);
        L2CallGraph.Result l2 = analysisLevel >= 2
                ? L2CallGraph.build(APP, modules, null, false)
                : null;
        return ViewDispatches.detect(APP, modules, artifacts, analysisLevel,
                l2 == null ? null : l2.callGraph());
    }

    private static List<String> edges(ViewDispatches.Result r) {
        return r.dispatches.stream()
                .map(e -> e.getVia() + " " + e.getProv() + " -> " + e.getDst())
                .collect(Collectors.toList());
    }

    private static JViewDispatchUnresolved only(ViewDispatches.Result r) {
        assertEquals(1, r.unresolved.size(), "exactly one unresolved record");
        return r.unresolved.get(0);
    }

    private static final String HEAD =
            "package demo;\n"
                    + "import javax.servlet.http.*;\n"
                    + "public class Front extends HttpServlet {\n";

    private static final String LOCAL_VARIABLE = HEAD
            + "  void doGet(HttpServletRequest req, HttpServletResponse res) {\n"
            + "    String page = \"/pages/x.jsp\";\n"
            + "    req.getRequestDispatcher(page).forward(req, res);\n"
            + "  }\n}\n";

    @Test
    void aLocalIsNonLiteralAtLevelOne() throws Exception {
        ViewDispatches.Result r = run(LOCAL_VARIABLE, 1);
        assertTrue(r.dispatches.isEmpty());
        JViewDispatchUnresolved u = only(r);
        assertEquals("non-literal", u.getReason());
        assertEquals(List.of("literal"), u.getProv());
    }

    @Test
    void aLocalClosesOverTheDdgAtLevelThree() throws Exception {
        ViewDispatches.Result r = run(LOCAL_VARIABLE, 3);
        assertEquals(List.of("forward [dataflow] -> " + CanId.artifactId(APP, "src/main/webapp/pages/x.jsp")),
                edges(r));
        assertTrue(r.unresolved.isEmpty());
    }

    @Test
    void twoDisagreeingDefinitionsStayNonLiteral() throws Exception {
        ViewDispatches.Result r = run(HEAD
                + "  void doGet(HttpServletRequest req, HttpServletResponse res, boolean b) {\n"
                + "    String page = \"/pages/x.jsp\";\n"
                + "    if (b) { page = \"/y.jsp\"; }\n"
                + "    req.getRequestDispatcher(page).forward(req, res);\n"
                + "  }\n}\n", 3);
        assertTrue(r.dispatches.isEmpty());
        JViewDispatchUnresolved u = only(r);
        assertEquals("non-literal", u.getReason());
        assertEquals(List.of("literal", "dataflow"), u.getProv(), "both tiers were attempted");
    }

    @Test
    void aTracedLiteralThatNamesNoFileIsNoSuchArtifact() throws Exception {
        ViewDispatches.Result r = run(HEAD
                + "  void doGet(HttpServletRequest req, HttpServletResponse res) {\n"
                + "    String page = \"/servlet/Other\";\n"
                + "    req.getRequestDispatcher(page).forward(req, res);\n"
                + "  }\n}\n", 3);
        JViewDispatchUnresolved u = only(r);
        assertEquals("no-such-artifact", u.getReason());
        assertEquals("/servlet/Other", u.getTarget());
        assertEquals(List.of("literal", "dataflow"), u.getProv());
    }

    // The helper takes servlet-typed parameters like DayTrader's requestDispatch does: since #263
    // the Jakarta finder marks only lifecycle methods, so the interprocedural tier binds `page`
    // from the call site instead of refusing it as container-bound.
    private static final String PARAMETER = HEAD
            + "  void doGet(HttpServletRequest req, HttpServletResponse res) { show(req, res, \"/y.jsp\"); }\n"
            + "  void show(HttpServletRequest req, HttpServletResponse res, String page) {\n"
            + "    req.getRequestDispatcher(page).forward(req, res);\n"
            + "  }\n}\n";

    @Test
    void aParameterStaysNonLiteralAtLevelThree() throws Exception {
        ViewDispatches.Result r = run(PARAMETER, 3);
        assertTrue(r.dispatches.isEmpty());
        assertEquals("non-literal", only(r).getReason());
    }

    @Test
    void aParameterClosesOverTheCallGraphAtLevelFour() throws Exception {
        ViewDispatches.Result r = run(PARAMETER, 4);
        assertEquals(List.of("forward [dataflow] -> " + CanId.artifactId(APP, "src/main/webapp/y.jsp")),
                edges(r));
    }

    @Test
    void twoCallersWithDifferentPagesStayNonLiteral() throws Exception {
        ViewDispatches.Result r = run(HEAD
                + "  void a(HttpServletRequest req, HttpServletResponse res) { show(req, res, \"/y.jsp\"); }\n"
                + "  void b(HttpServletRequest req, HttpServletResponse res) { show(req, res, \"/pages/x.jsp\"); }\n"
                + "  void show(HttpServletRequest req, HttpServletResponse res, String page) {\n"
                + "    req.getRequestDispatcher(page).forward(req, res);\n"
                + "  }\n}\n", 4);
        assertTrue(r.dispatches.isEmpty());
        assertEquals("non-literal", only(r).getReason());
    }
}
