package com.ibm.cldk.javaee;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ibm.cldk.schema.JCallable;
import com.ibm.cldk.schema.JEntrypointReport;
import com.ibm.cldk.schema.JModule;
import com.ibm.cldk.schema.JType;
import com.ibm.cldk.syntactic_analysis.L1Extractor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Which methods the Jakarta finder marks (#263): the servlet / filter / listener lifecycle methods
 * of an entrypoint type, not any method that happens to take a servlet-typed parameter. A helper
 * such as DayTrader's {@code requestDispatch(ctx, req, resp, page)} is ordinary code whose
 * parameters are bound by its callers — marking it an entrypoint made every interprocedural tier
 * refuse to bind them.
 */
class JakartaEntrypointMethodsTest {

    @TempDir
    Path root;

    private Map<String, List<String>> marks(String source) throws Exception {
        Path src = root.resolve("src/main/java/demo");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Web.java"), source, StandardCharsets.UTF_8);
        Map<String, JModule> modules = L1Extractor.extractAll(
                root, "app", null, new LinkedHashMap<>(), 1, 3, "ast", new JEntrypointReport());
        Map<String, List<String>> out = new TreeMap<>();
        for (JModule m : modules.values()) {
            for (Map.Entry<String, JType> e : m.getTypes().entrySet()) {
                for (JCallable c : e.getValue().getCallables().values()) {
                    out.put(e.getKey() + "." + c.getSignature().substring(0, c.getSignature().indexOf('(')),
                            c.getEntrypointFrameworks());
                }
            }
        }
        return out;
    }

    @Test
    void lifecycleMethodsOfAServletAreMarkedAndItsHelpersAreNot() throws Exception {
        Map<String, List<String>> m = marks(
                "package demo;\n"
                        + "import javax.servlet.http.*;\n"
                        + "public class Front extends HttpServlet {\n"
                        + "  protected void doGet(HttpServletRequest req, HttpServletResponse res) { show(req, res, \"/x.jsp\"); }\n"
                        + "  public void init() {}\n"
                        + "  void show(HttpServletRequest req, HttpServletResponse res, String page) {}\n"
                        + "  static String render(HttpServletRequest req) { return \"\"; }\n"
                        + "}\n");
        assertEquals(List.of("jakarta"), m.get("Front.doGet"));
        assertEquals(List.of("jakarta"), m.get("Front.init"));
        assertEquals(List.of(), m.get("Front.show"), "a helper taking req/res is not an entrypoint");
        assertEquals(List.of(), m.get("Front.render"));
    }

    @Test
    void aLifecycleNameOutsideAnEntrypointTypeIsNotMarked() throws Exception {
        Map<String, List<String>> m = marks(
                "package demo;\n"
                        + "import javax.servlet.http.*;\n"
                        + "public class Plain {\n"
                        + "  void doGet(HttpServletRequest req, HttpServletResponse res) {}\n"
                        + "  public void init() {}\n"
                        + "}\n");
        assertEquals(List.of(), m.get("Plain.doGet"));
        assertEquals(List.of(), m.get("Plain.init"));
    }

    @Test
    void filterAndListenerAndEndpointCallbacksAreMarked() throws Exception {
        Map<String, List<String>> m = marks(
                "package demo;\n"
                        + "import javax.servlet.*;\n"
                        + "@WebFilter(\"/*\")\n"
                        + "class Gate implements Filter {\n"
                        + "  public void doFilter(ServletRequest q, ServletResponse s, FilterChain c) {}\n"
                        + "  void log(ServletRequest q) {}\n"
                        + "}\n"
                        + "class Boot implements ServletContextListener {\n"
                        + "  public void contextInitialized(ServletContextEvent e) {}\n"
                        + "}\n"
                        + "@ServerEndpoint(\"/ws\")\n"
                        + "class Sock {\n"
                        + "  @OnMessage public void onText(String msg) {}\n"
                        + "  void send(String msg) {}\n"
                        + "}\n");
        assertEquals(List.of("jakarta"), m.get("Gate.doFilter"));
        assertEquals(List.of(), m.get("Gate.log"));
        assertEquals(List.of("jakarta"), m.get("Boot.contextInitialized"));
        assertEquals(List.of("jakarta"), m.get("Sock.onText"));
        assertEquals(List.of(), m.get("Sock.send"));
    }
}
