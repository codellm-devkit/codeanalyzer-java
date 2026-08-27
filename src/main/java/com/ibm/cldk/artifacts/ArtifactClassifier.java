package com.ibm.cldk.artifacts;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Classifies a repository file into the closed {@code artifact_kind} set and, where recognizable, a
 * document {@code format}. Classification is by filename and extension only — cheap, deterministic,
 * and independent of file contents — so it never fails; anything unrecognized lands in {@code other},
 * the catch-all that guarantees coverage (a file is inventoried even when no parser understands it).
 *
 * <p>The kind vocabulary is the closed set enforced by the JSON Schema conformance gate:
 * {@code build_manifest}, {@code dependency_lockfile}, {@code configuration},
 * {@code deployment_manifest}, {@code container}, {@code infrastructure}, {@code ci}, {@code script},
 * {@code documentation}, {@code data}, {@code other}.
 */
final class ArtifactClassifier {

    private ArtifactClassifier() {}

    /** The closed-set kind plus the recognized document format (nullable). */
    static final class Classification {
        final String kind;
        final String format;

        Classification(String kind, String format) {
            this.kind = kind;
            this.format = format;
        }
    }

    /** Classify by repo-relative path (POSIX separators expected but not required). */
    static Classification classify(String relPath) {
        String norm = relPath.replace('\\', '/');
        int slash = norm.lastIndexOf('/');
        String name = slash < 0 ? norm : norm.substring(slash + 1);
        String lower = name.toLowerCase(Locale.ROOT);
        String ext = extension(lower);

        // --- Build manifests -------------------------------------------------------------------
        if (lower.equals("pom.xml")) {
            return new Classification("build_manifest", "xml");
        }
        if (lower.equals("build.gradle") || lower.equals("settings.gradle")) {
            return new Classification("build_manifest", "gradle");
        }
        if (lower.equals("build.gradle.kts") || lower.equals("settings.gradle.kts")) {
            return new Classification("build_manifest", "gradle-kts");
        }

        // --- Dependency lockfiles --------------------------------------------------------------
        if (lower.equals("gradle.lockfile")) {
            return new Classification("dependency_lockfile", "gradle-lockfile");
        }

        // --- Containers ------------------------------------------------------------------------
        if (lower.equals("dockerfile") || lower.startsWith("dockerfile.")) {
            return new Classification("container", "dockerfile");
        }
        if (lower.equals("docker-compose.yml")
                || lower.equals("docker-compose.yaml")
                || lower.equals("compose.yml")
                || lower.equals("compose.yaml")) {
            return new Classification("container", "yaml");
        }
        if (lower.equals(".dockerignore")) {
            return new Classification("container", null);
        }

        // --- Deployment manifests (Kubernetes / Helm) ------------------------------------------
        if (lower.equals("chart.yaml") || lower.equals("values.yaml")) {
            return new Classification("deployment_manifest", "yaml");
        }
        if (norm.contains("/k8s/") || norm.contains("/kubernetes/") || norm.startsWith("k8s/")) {
            if (ext.equals("yaml") || ext.equals("yml")) {
                return new Classification("deployment_manifest", "yaml");
            }
        }

        // --- Infrastructure as code ------------------------------------------------------------
        if (ext.equals("tf") || ext.equals("tfvars")) {
            return new Classification("infrastructure", "hcl");
        }

        // --- CI ----------------------------------------------------------------------------------
        if (norm.contains(".github/workflows/") && (ext.equals("yml") || ext.equals("yaml"))) {
            return new Classification("ci", "yaml");
        }
        if (lower.equals(".gitlab-ci.yml") || lower.equals("jenkinsfile") || lower.equals(".travis.yml")) {
            return new Classification("ci", ext.equals("yml") ? "yaml" : null);
        }

        // --- Scripts -----------------------------------------------------------------------------
        if (ext.equals("sh") || ext.equals("bash") || lower.equals("makefile")) {
            return new Classification("script", null);
        }

        // --- Documentation -----------------------------------------------------------------------
        if (ext.equals("md") || ext.equals("markdown") || ext.equals("rst") || ext.equals("adoc")) {
            return new Classification("documentation", ext.equals("md") ? "markdown" : null);
        }

        // --- Configuration -----------------------------------------------------------------------
        // Ordered after the more specific YAML kinds above so an application.yml is configuration but
        // a docker-compose.yml is a container.
        if (ext.equals("properties")) {
            return new Classification("configuration", "properties");
        }
        if (ext.equals("yml") || ext.equals("yaml")) {
            return new Classification("configuration", "yaml");
        }
        if (ext.equals("xml")) {
            return new Classification("configuration", "xml");
        }
        if (lower.startsWith(".env")) {
            return new Classification("configuration", "dotenv");
        }
        if (ext.equals("toml") || ext.equals("ini") || ext.equals("conf") || ext.equals("cfg")) {
            return new Classification("configuration", ext);
        }

        // --- Data --------------------------------------------------------------------------------
        if (ext.equals("json") || ext.equals("csv") || ext.equals("sql")) {
            return new Classification("data", ext);
        }

        // --- Catch-all ---------------------------------------------------------------------------
        return new Classification("other", ext.isEmpty() ? null : ext);
    }

    /** Lowercase extension without the dot, or "" when there is none. A leading-dot name (a dotfile
     * such as {@code .env}) has no extension. */
    private static String extension(String lowerName) {
        int dot = lowerName.lastIndexOf('.');
        if (dot <= 0) {
            return "";
        }
        return lowerName.substring(dot + 1);
    }

    static Classification classify(Path relPath) {
        return classify(relPath.toString());
    }
}
