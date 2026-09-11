package com.ibm.cldk.artifacts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.ibm.cldk.schema.CanId;
import com.ibm.cldk.schema.JArtifact;
import com.ibm.cldk.schema.JBodyNode;
import com.ibm.cldk.schema.JCallable;
import com.ibm.cldk.schema.JModule;
import com.ibm.cldk.schema.JType;
import com.ibm.cldk.schema.JViewDispatchEdge;
import com.ibm.cldk.schema.JViewDispatchUnresolved;
import com.ibm.cldk.syntactic_analysis.L1Extractor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The view-dispatch literal tier end to end over the servlet API: real L1 extraction plus the
 * artifact layer, then {@link ViewDispatches}. The fixture carries every outcome at once — three
 * resolved mechanisms, a URL that is not a file, a variable target, and an ambiguous name — because
 * the failure this pass guards against is a confident wrong edge, which only shows up when the
 * unresolved buckets are checked alongside the resolved one.
 *
 * <p>The servlet API is stubbed as fixture source so receiver types resolve: detection is by
 * declared receiver type, never by bare method name, exactly as {@link ConfigUses} does it.
 */
class ViewDispatchesTest {

    private static final String APP = "view-dispatch-test";

    @TempDir
    static Path root;

    private static ViewDispatches.Result result;
    private static Map<String, JModule> modules;

    static void write(String rel, String text) throws Exception {
        Path f = root.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, text, StandardCharsets.UTF_8);
    }

    @BeforeAll
    static void analyze() throws Exception {
        write("src/main/java/javax/servlet/RequestDispatcher.java",
                "package javax.servlet;\npublic interface RequestDispatcher {\n"
                        + "  void forward(ServletRequest q, ServletResponse s);\n"
                        + "  void include(ServletRequest q, ServletResponse s);\n}\n");
        write("src/main/java/javax/servlet/ServletRequest.java",
                "package javax.servlet;\npublic interface ServletRequest {\n"
                        + "  RequestDispatcher getRequestDispatcher(String path);\n}\n");
        write("src/main/java/javax/servlet/ServletResponse.java",
                "package javax.servlet;\npublic interface ServletResponse {}\n");
        write("src/main/java/javax/servlet/ServletContext.java",
                "package javax.servlet;\npublic interface ServletContext {\n"
                        + "  RequestDispatcher getRequestDispatcher(String path);\n}\n");
        write("src/main/java/javax/servlet/http/HttpServletRequest.java",
                "package javax.servlet.http;\n"
                        + "public interface HttpServletRequest extends javax.servlet.ServletRequest {}\n");
        write("src/main/java/javax/servlet/http/HttpServletResponse.java",
                "package javax.servlet.http;\n"
                        + "public interface HttpServletResponse extends javax.servlet.ServletResponse {\n"
                        + "  void sendRedirect(String location);\n}\n");
        write("src/main/java/javax/servlet/http/HttpServlet.java",
                "package javax.servlet.http;\npublic abstract class HttpServlet {\n"
                        + "  public javax.servlet.ServletContext getServletContext() { return null; }\n}\n");

        write("src/main/webapp/pages/x.jsp", "<%= 1 %>");
        write("src/main/webapp/y.jsp", "<%= 2 %>");
        write("src/main/webapp/WEB-INF/z.jsp", "<%= 3 %>");
        write("src/main/webapp/dup.jsp", "<%= 4 %>");
        write("src/main/webapp/other/dup.jsp", "<%= 5 %>");

        write("src/main/java/demo/Front.java",
                "package demo;\n"
                        + "import javax.servlet.http.*;\n"
                        + "public class Front extends HttpServlet {\n"
                        + "  protected void doGet(HttpServletRequest req, HttpServletResponse res) {\n"
                        + "    getServletContext().getRequestDispatcher(\"/pages/x.jsp\").forward(req, res);\n"
                        + "    req.getRequestDispatcher(\"/y.jsp\").include(req, res);\n"
                        + "    res.sendRedirect(\"/WEB-INF/z.jsp\");\n"
                        + "    getServletContext().getRequestDispatcher(\"/servlet/Other\").forward(req, res);\n"
                        + "    req.getRequestDispatcher(\"/dup.jsp\").forward(req, res);\n"
                        + "  }\n"
                        + "  void dyn(HttpServletRequest req, HttpServletResponse res, String page) {\n"
                        + "    req.getRequestDispatcher(page).forward(req, res);\n"
                        + "  }\n"
                        + "}\n");

        modules = L1Extractor.extractAll(root, APP, null, new LinkedHashMap<>(), 1, 3, "ast");
        Map<String, JArtifact> artifacts = ArtifactDiscovery.discover(root, APP, true, 262144);
        result = ViewDispatches.detect(APP, modules, artifacts);
    }

    /** The ordinal id of the {@code n}-th call to {@code method} inside {@code callable}, in source order. */
    private static String site(String callable, String method, int n) {
        for (JModule m : modules.values()) {
            for (JType t : m.getTypes().values()) {
                for (JCallable c : t.getCallables().values()) {
                    if (!c.getSignature().startsWith(callable + "(")) {
                        continue;
                    }
                    List<String> ids = c.getBody().entrySet().stream()
                            .filter(e -> "call".equals(e.getValue().getKind())
                                    && method.equals(e.getValue().getMethodName()))
                            .map(e -> CanId.ordinalId(c.getId(), e.getKey()))
                            .sorted(ViewDispatchesTest::bySourcePosition)
                            .collect(Collectors.toList());
                    return ids.get(n);
                }
            }
        }
        throw new AssertionError("no callable " + callable);
    }

    private static int bySourcePosition(String a, String b) {
        String[] x = a.substring(a.lastIndexOf('@') + 1).split(":");
        String[] y = b.substring(b.lastIndexOf('@') + 1).split(":");
        int line = Integer.compare(Integer.parseInt(x[0]), Integer.parseInt(y[0]));
        return line != 0 ? line : Integer.compare(Integer.parseInt(x[1]), Integer.parseInt(y[1]));
    }

    private static String artifact(String rel) {
        return CanId.artifactId(APP, rel);
    }

    private static String edge(JViewDispatchEdge e) {
        return e.getSrc() + " -[" + e.getVia() + " " + e.getProv() + "]-> " + e.getDst();
    }

    @Test
    void theThreeServletMechanismsResolveToTheirArtifacts() {
        List<String> expected = List.of(
                site("doGet", "forward", 0) + " -[forward [literal]]-> " + artifact("src/main/webapp/pages/x.jsp"),
                site("doGet", "include", 0) + " -[include [literal]]-> " + artifact("src/main/webapp/y.jsp"),
                site("doGet", "sendRedirect", 0) + " -[redirect [literal]]-> " + artifact("src/main/webapp/WEB-INF/z.jsp"));
        assertEquals(expected.stream().sorted().collect(Collectors.toList()),
                result.dispatches.stream().map(ViewDispatchesTest::edge).collect(Collectors.toList()));
    }

    @Test
    void aServletUrlIsNoSuchArtifact() {
        JViewDispatchUnresolved u = unresolved(site("doGet", "forward", 1));
        assertEquals("/servlet/Other", u.getTarget());
        assertEquals("forward", u.getVia());
        assertEquals("no-such-artifact", u.getReason());
        assertEquals(List.of("literal"), u.getProv());
        assertEquals(CanId.externalId(APP, "javax.servlet.RequestDispatcher",
                "forward(javax.servlet.ServletRequest, javax.servlet.ServletResponse)"), u.getCallee());
    }

    @Test
    void aNameMatchingTwoArtifactsIsAmbiguous() {
        JViewDispatchUnresolved u = unresolved(site("doGet", "forward", 2));
        assertEquals("/dup.jsp", u.getTarget());
        assertEquals("ambiguous", u.getReason());
    }

    @Test
    void aVariableTargetIsNonLiteralAtLevelOne() {
        JViewDispatchUnresolved u = unresolved(site("dyn", "forward", 0));
        assertNull(u.getTarget());
        assertEquals("forward", u.getVia());
        assertEquals("non-literal", u.getReason());
        assertEquals(List.of("literal"), u.getProv());
    }

    @Test
    void nothingElseIsRecorded() {
        assertEquals(3, result.dispatches.size());
        assertEquals(3, result.unresolved.size());
    }

    private static JViewDispatchUnresolved unresolved(String site) {
        return result.unresolved.stream().filter(u -> site.equals(u.getSite())).findFirst()
                .orElseThrow(() -> new AssertionError("no unresolved record at " + site + "; have "
                        + result.unresolved.stream().map(JViewDispatchUnresolved::getSite)
                                .collect(Collectors.toList())));
    }
}
