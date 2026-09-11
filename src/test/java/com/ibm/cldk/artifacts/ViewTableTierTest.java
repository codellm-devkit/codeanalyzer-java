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
 * The table tier (spec § 4.5, #261): a dispatch target that is a call into a static string table
 * closes on every entry of the table as a may-dispatch, {@code prov: ["table"]}. The refusals
 * matter as much as the closures: a return that is not a table lookup, or a table without a
 * literal initializer, must not close.
 */
class ViewTableTierTest {

    private static final String APP = "view-table-test";

    @TempDir
    Path root;

    private static final String PAGES =
            "package demo;\n"
                    + "public class Pages {\n"
                    + "  static int mode = 0;\n"
                    + "  static String[][] table = {\n"
                    + "    { \"/a.jsp\", \"/b.jsp\", \"/app?action=x\" },\n"
                    + "    { \"/aImg.jsp\", \"/bImg.jsp\", \"/app?action=x\" } };\n"
                    + "  static String page(int i) { return table[mode][i]; }\n"
                    + "  static String computed(int i) { return \"/\" + i + \".jsp\"; }\n"
                    + "  static String[] loaded;\n"
                    + "  static String fromLoaded(int i) { return loaded[i]; }\n"
                    + "}\n";

    private static final String HEAD =
            "package demo;\n"
                    + "import javax.servlet.http.*;\n"
                    + "public class Front extends HttpServlet {\n";

    private ViewDispatches.Result run(String front, int analysisLevel) throws Exception {
        ServletApiStubs.write(root);
        for (String v : List.of("a", "b", "aImg", "bImg", "c")) {
            ServletApiStubs.write(root, "src/main/webapp/" + v + ".jsp", "<%= 1 %>");
        }
        if (!java.nio.file.Files.exists(root.resolve("src/main/java/demo/Pages.java"))) {
            ServletApiStubs.write(root, "src/main/java/demo/Pages.java", PAGES);
        }
        ServletApiStubs.write(root, "src/main/java/demo/Front.java", front);
        Map<String, JModule> modules = L1Extractor.extractAll(
                root, APP, null, new LinkedHashMap<>(), Math.max(analysisLevel, 1), 3, "ast");
        Map<String, JArtifact> artifacts = ArtifactDiscovery.discover(root, APP, true, 262144);
        L2CallGraph.Result l2 = analysisLevel >= 2 ? L2CallGraph.build(APP, modules, null, false) : null;
        return ViewDispatches.detect(APP, modules, artifacts, analysisLevel,
                l2 == null ? null : l2.callGraph());
    }

    private static List<String> targets(ViewDispatches.Result r) {
        return r.dispatches.stream()
                .map(e -> e.getProv() + " " + e.getDst().substring(e.getDst().lastIndexOf('/') + 1))
                .sorted().collect(Collectors.toList());
    }

    @Test
    void aTableLookupClosesOnEveryEntryAtLevelOne() throws Exception {
        ViewDispatches.Result r = run(HEAD
                + "  void doGet(HttpServletRequest req, HttpServletResponse res) {\n"
                + "    req.getRequestDispatcher(Pages.page(1)).include(req, res);\n"
                + "  }\n}\n", 1);
        assertEquals(List.of("[table] a.jsp", "[table] aImg.jsp", "[table] b.jsp", "[table] bImg.jsp"),
                targets(r), "every page in the table, the servlet URL contributing nothing");
        assertTrue(r.unresolved.isEmpty());
        assertEquals(1, r.dispatches.stream().map(e -> e.getSrc()).distinct().count(), "one site");
    }

    @Test
    void aTableNamingNoFileIsNoSuchArtifactWithTableAttempted() throws Exception {
        ServletApiStubs.write(root, "src/main/java/demo/Pages.java",
                PAGES.replace(".jsp", ".missing"));
        ViewDispatches.Result r = run(HEAD
                + "  void doGet(HttpServletRequest req, HttpServletResponse res) {\n"
                + "    req.getRequestDispatcher(Pages.page(1)).include(req, res);\n"
                + "  }\n}\n", 1);
        assertTrue(r.dispatches.isEmpty());
        JViewDispatchUnresolved u = r.unresolved.get(0);
        assertEquals("no-such-artifact", u.getReason());
        assertEquals(List.of("literal", "table"), u.getProv());
    }

    @Test
    void aComputedReturnDoesNotClose() throws Exception {
        ViewDispatches.Result r = run(HEAD
                + "  void doGet(HttpServletRequest req, HttpServletResponse res) {\n"
                + "    req.getRequestDispatcher(Pages.computed(1)).include(req, res);\n"
                + "  }\n}\n", 1);
        assertTrue(r.dispatches.isEmpty());
        assertEquals("non-literal", r.unresolved.get(0).getReason());
    }

    @Test
    void aTableWithoutALiteralInitializerDoesNotClose() throws Exception {
        ViewDispatches.Result r = run(HEAD
                + "  void doGet(HttpServletRequest req, HttpServletResponse res) {\n"
                + "    req.getRequestDispatcher(Pages.fromLoaded(1)).include(req, res);\n"
                + "  }\n}\n", 1);
        assertTrue(r.dispatches.isEmpty());
        assertEquals("non-literal", r.unresolved.get(0).getReason());
    }

    private static final String PARAMETER = HEAD
            + "  void a() { show(Pages.page(0)); }\n"
            + "  void b() { show(\"/c.jsp\"); }\n"
            + "  void show(String page) {\n"
            + "    getServletContext().getRequestDispatcher(page).include(null, null);\n"
            + "  }\n}\n";

    @Test
    void aParameterBoundToATableAndALiteralUnionsAtLevelFour() throws Exception {
        ViewDispatches.Result r = run(PARAMETER, 4);
        assertEquals(List.of("[table] a.jsp", "[table] aImg.jsp", "[table] b.jsp", "[table] bImg.jsp",
                "[table] c.jsp"), targets(r));
    }

    @Test
    void theParameterStaysNonLiteralBelowLevelFour() throws Exception {
        ViewDispatches.Result r = run(PARAMETER, 3);
        assertTrue(r.dispatches.isEmpty());
        assertEquals("non-literal", r.unresolved.get(0).getReason());
    }
}
