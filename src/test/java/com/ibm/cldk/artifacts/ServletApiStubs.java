package com.ibm.cldk.artifacts;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The slice of the servlet API the view-dispatch tests need, written as fixture source so receiver
 * types resolve without a jar on the classpath.
 */
final class ServletApiStubs {

    private ServletApiStubs() {}

    static void write(Path root) throws Exception {
        write(root, "src/main/java/javax/servlet/RequestDispatcher.java",
                "package javax.servlet;\npublic interface RequestDispatcher {\n"
                        + "  void forward(ServletRequest q, ServletResponse s);\n"
                        + "  void include(ServletRequest q, ServletResponse s);\n}\n");
        write(root, "src/main/java/javax/servlet/ServletRequest.java",
                "package javax.servlet;\npublic interface ServletRequest {\n"
                        + "  RequestDispatcher getRequestDispatcher(String path);\n}\n");
        write(root, "src/main/java/javax/servlet/ServletResponse.java",
                "package javax.servlet;\npublic interface ServletResponse {}\n");
        write(root, "src/main/java/javax/servlet/ServletContext.java",
                "package javax.servlet;\npublic interface ServletContext {\n"
                        + "  RequestDispatcher getRequestDispatcher(String path);\n}\n");
        write(root, "src/main/java/javax/servlet/http/HttpServletRequest.java",
                "package javax.servlet.http;\n"
                        + "public interface HttpServletRequest extends javax.servlet.ServletRequest {}\n");
        write(root, "src/main/java/javax/servlet/http/HttpServletResponse.java",
                "package javax.servlet.http;\n"
                        + "public interface HttpServletResponse extends javax.servlet.ServletResponse {\n"
                        + "  void sendRedirect(String location);\n}\n");
        write(root, "src/main/java/javax/servlet/http/HttpServlet.java",
                "package javax.servlet.http;\npublic abstract class HttpServlet {\n"
                        + "  public javax.servlet.ServletContext getServletContext() { return null; }\n}\n");
    }

    static void write(Path root, String rel, String text) throws Exception {
        Path f = root.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, text, StandardCharsets.UTF_8);
    }
}
