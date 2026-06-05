import { defineConfig } from "astro/config";
import starlight from "@astrojs/starlight";
import mermaid from "astro-mermaid";
import { pluginCollapsibleSections } from "@expressive-code/plugin-collapsible-sections";
import { pluginLineNumbers } from "@expressive-code/plugin-line-numbers";

// https://astro.build/config
export default defineConfig({
  site: "https://codellm-devkit.github.io",
  base: "/codeanalyzer-java",
  integrations: [
    // Mermaid must run BEFORE Starlight so it can preprocess ```mermaid blocks.
    mermaid({
      theme: "neutral",
      autoTheme: true,
      mermaidConfig: {
        flowchart: { curve: "basis" },
      },
    }),
    starlight({
      title: "codeanalyzer-java",
      tagline: "WALA + Javaparser static analysis for enterprise Java, as one JSON schema.",
      description:
        "codeanalyzer-java is the JVM static-analysis backend behind CodeLLM-DevKit's Java support: a standalone JAR that turns a Java project into a symbol table and call graph, emitted as one versioned JSON schema.",
      logo: {
        src: "./src/assets/logo.png",
        replacesTitle: true,
      },
      favicon: "/favicon.png",
      customCss: ["./src/styles/docs.css"],
      expressiveCode: {
        plugins: [pluginCollapsibleSections(), pluginLineNumbers()],
        styleOverrides: {
          borderRadius: "0.4rem",
          frames: {
            shadowColor: "transparent",
          },
        },
        defaultProps: {
          showLineNumbers: false,
        },
      },
      head: [
        {
          tag: "link",
          attrs: { rel: "preconnect", href: "https://fonts.googleapis.com" },
        },
        {
          tag: "link",
          attrs: {
            rel: "preconnect",
            href: "https://fonts.gstatic.com",
            crossorigin: "",
          },
        },
        {
          tag: "link",
          attrs: {
            rel: "stylesheet",
            href: "https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@400;500;600;700&family=Space+Mono:wght@400;700&display=swap",
          },
        },
      ],
      social: [
        {
          icon: "github",
          label: "codeanalyzer-java on GitHub",
          href: "https://github.com/codellm-devkit/codeanalyzer-java",
        },
        {
          icon: "seti:java",
          label: "codeanalyzer-java releases",
          href: "https://github.com/codellm-devkit/codeanalyzer-java/releases",
        },
        {
          icon: "discord",
          label: "CLDK on Discord",
          href: "https://discord.gg/zEjz9YrmqN",
        },
      ],
      editLink: {
        baseUrl: "https://github.com/codellm-devkit/codeanalyzer-java/edit/docs/",
      },
      sidebar: [
        {
          label: "Start here",
          items: [
            { label: "What is codeanalyzer-java?", slug: "what-is-codeanalyzer" },
            { label: "Quickstart", slug: "quickstart" },
            { label: "Installation", slug: "installing" },
          ],
        },
        {
          label: "Guides",
          items: [
            { label: "Architecture", slug: "guides/architecture" },
            { label: "Analysis levels", slug: "guides/analysis-levels" },
            { label: "Build integration", slug: "guides/build-integration" },
            { label: "Incremental analysis", slug: "guides/incremental-analysis" },
          ],
        },
        {
          label: "CLI Reference",
          items: [
            { label: "Command-line options", slug: "reference/cli" },
            { label: "Examples", slug: "reference/examples" },
          ],
        },
        {
          label: "Output Schema",
          items: [
            { label: "Overview", slug: "schema" },
            { label: "Symbol table", slug: "schema/symbol-table" },
            { label: "Call graph", slug: "schema/call-graph" },
          ],
        },
        {
          label: "Framework Support",
          items: [
            { label: "Entry points", slug: "frameworks/entry-points" },
            { label: "CRUD detection", slug: "frameworks/crud" },
          ],
        },
        {
          label: "Integration",
          items: [
            { label: "Python SDK (CLDK)", slug: "integration/python-sdk" },
          ],
        },
        {
          label: "Project",
          items: [
            { label: "Contributing", slug: "contributing" },
            { label: "Troubleshooting", slug: "troubleshooting" },
          ],
        },
      ],
    }),
  ],
});
