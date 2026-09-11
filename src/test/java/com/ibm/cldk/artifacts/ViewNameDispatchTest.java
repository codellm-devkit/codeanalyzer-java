package com.ibm.cldk.artifacts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.cldk.schema.CanId;
import com.ibm.cldk.schema.JArtifact;
import com.ibm.cldk.schema.JModule;
import com.ibm.cldk.syntactic_analysis.L1Extractor;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Spring view names (spec 2026-09-11 D3/D4): a controller's {@code return "home"},
 * {@code new ModelAndView("home")} and {@code setViewName("home")} resolve against the view
 * resolver's prefix and suffix — Thymeleaf's defaults, or the {@code spring.mvc.view.*} /
 * {@code spring.thymeleaf.*} config keys when declared — and a {@code return "home"} outside a
 * Spring entrypoint is not a dispatch at all. {@code return} body nodes exist from L3, so the tree
 * is extracted at 3.
 */
class ViewNameDispatchTest {

    private static final String APP = "view-name-test";

    @TempDir
    Path root;

    private ViewDispatches.Result run(String controller, String properties, String... views)
            throws Exception {
        ServletApiStubs.write(root, "src/main/java/org/springframework/stereotype/Controller.java",
                "package org.springframework.stereotype;\npublic @interface Controller {}\n");
        ServletApiStubs.write(root,
                "src/main/java/org/springframework/web/bind/annotation/GetMapping.java",
                "package org.springframework.web.bind.annotation;\n"
                        + "public @interface GetMapping { String value() default \"\"; }\n");
        ServletApiStubs.write(root, "src/main/java/org/springframework/web/servlet/ModelAndView.java",
                "package org.springframework.web.servlet;\npublic class ModelAndView {\n"
                        + "  public ModelAndView() {}\n  public ModelAndView(String view) {}\n"
                        + "  public void setViewName(String view) {}\n}\n");
        for (String view : views) {
            ServletApiStubs.write(root, view, "<html/>");
        }
        if (properties != null) {
            ServletApiStubs.write(root, "src/main/resources/application.properties", properties);
        }
        ServletApiStubs.write(root, "src/main/java/demo/Home.java", controller);

        Map<String, JModule> modules = L1Extractor.extractAll(
                root, APP, null, new LinkedHashMap<>(), 3, 3, "ast");
        Map<String, JArtifact> artifacts = ArtifactDiscovery.discover(root, APP, true, 262144);
        for (JArtifact a : artifacts.values()) {
            if (ConfigKeys.isEligible(a)) {
                a.setConfigKeys(ConfigKeys.extract(
                        a, DependencyView.readFromDisk(root, a.getPath()), true).keys);
            }
        }
        return ViewDispatches.detect(APP, modules, artifacts, 3, null);
    }

    private static List<String> edges(ViewDispatches.Result r) {
        return r.dispatches.stream()
                .map(e -> e.getVia() + " " + e.getProv() + " -> " + e.getDst())
                .sorted()
                .collect(Collectors.toList());
    }

    private static String art(String rel) {
        return CanId.artifactId(APP, rel);
    }

    private static final String CONTROLLER =
            "package demo;\n"
                    + "import org.springframework.stereotype.Controller;\n"
                    + "import org.springframework.web.bind.annotation.GetMapping;\n"
                    + "import org.springframework.web.servlet.ModelAndView;\n"
                    + "@Controller\n"
                    + "public class Home {\n"
                    + "  @GetMapping(\"/\") String home() { return \"home\"; }\n"
                    + "  @GetMapping(\"/r\") String back() { return \"redirect:/y.jsp\"; }\n"
                    + "  @GetMapping(\"/m\") ModelAndView mav() { return new ModelAndView(\"admin/users\"); }\n"
                    + "  @GetMapping(\"/s\") ModelAndView set() {\n"
                    + "    ModelAndView m = new ModelAndView(); m.setViewName(\"home\"); return m;\n"
                    + "  }\n"
                    + "  @GetMapping(\"/n\") int count() { return 3; }\n"
                    + "}\n"
                    + "class Plain {\n"
                    + "  String home() { return \"home\"; }\n"
                    + "}\n";

    @Test
    void thymeleafDefaultsResolveControllerViewNames() throws Exception {
        ViewDispatches.Result r = run(CONTROLLER, null,
                "src/main/resources/templates/home.html",
                "src/main/resources/templates/admin/users.html",
                "src/main/webapp/y.jsp");
        assertEquals(List.of(
                "redirect [literal] -> " + art("src/main/webapp/y.jsp"),
                "view-name [literal] -> " + art("src/main/resources/templates/admin/users.html"),
                "view-name [literal] -> " + art("src/main/resources/templates/home.html"),
                "view-name [literal] -> " + art("src/main/resources/templates/home.html")),
                edges(r));
        assertTrue(r.unresolved.isEmpty(), "a `return 3` and a non-controller `return \"home\"` are not dispatches");
    }

    @Test
    void declaredMvcPrefixAndSuffixResolveJspViews() throws Exception {
        ViewDispatches.Result r = run(
                "package demo;\n"
                        + "import org.springframework.stereotype.Controller;\n"
                        + "import org.springframework.web.bind.annotation.GetMapping;\n"
                        + "@Controller\npublic class Home {\n"
                        + "  @GetMapping(\"/\") String home() { return \"home\"; }\n}\n",
                "spring.mvc.view.prefix=/WEB-INF/jsp/\nspring.mvc.view.suffix=.jsp\n",
                "src/main/webapp/WEB-INF/jsp/home.jsp");
        assertEquals(List.of("view-name [literal] -> " + art("src/main/webapp/WEB-INF/jsp/home.jsp")),
                edges(r));
    }

    @Test
    void aViewNameWithNoTemplateIsNoSuchArtifact() throws Exception {
        ViewDispatches.Result r = run(
                "package demo;\n"
                        + "import org.springframework.stereotype.Controller;\n"
                        + "import org.springframework.web.bind.annotation.GetMapping;\n"
                        + "@Controller\npublic class Home {\n"
                        + "  @GetMapping(\"/\") String home() { return \"missing\"; }\n}\n",
                null);
        assertTrue(r.dispatches.isEmpty());
        assertEquals(1, r.unresolved.size());
        assertEquals("no-such-artifact", r.unresolved.get(0).getReason());
        assertEquals("missing", r.unresolved.get(0).getTarget());
        assertEquals("view-name", r.unresolved.get(0).getVia());
    }
}
