package com.ibm.cldk.artifacts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ibm.cldk.artifacts.ArtifactClassifier.Classification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Branch-complete table for {@link ArtifactClassifier}: every recognized kind/format pairing plus the
 * catch-all. Classification never fails, so an untested branch that returns the wrong kind would ship
 * silently — this table is the guard against that. The empty string in the expected-format column
 * means {@code null} (no recognized format).
 */
class ArtifactClassifierTest {

    @ParameterizedTest(name = "{0} -> {1}/{2}")
    @CsvSource({
        // --- Build manifests (Java) ---
        "pom.xml,                              build_manifest,      xml",
        "build.gradle,                         build_manifest,      gradle",
        "settings.gradle,                      build_manifest,      gradle",
        "build.gradle.kts,                     build_manifest,      gradle-kts",
        "settings.gradle.kts,                  build_manifest,      gradle-kts",
        "sub/module/pom.xml,                   build_manifest,      xml",

        // --- Dependency lockfiles ---
        "gradle.lockfile,                      dependency_lockfile, gradle-lockfile",

        // --- Containers ---
        "Dockerfile,                           container,           dockerfile",
        "Dockerfile.dev,                       container,           dockerfile",
        "docker-compose.yml,                   container,           yaml",
        "docker-compose.yaml,                  container,           yaml",
        "compose.yaml,                         container,           yaml",
        ".dockerignore,                        container,           ''",

        // --- Deployment manifests (Kubernetes / Helm) ---
        "Chart.yaml,                           deployment_manifest, yaml",
        "values.yaml,                          deployment_manifest, yaml",
        "k8s/deployment.yaml,                  deployment_manifest, yaml",
        "deploy/kubernetes/svc.yml,            deployment_manifest, yaml",

        // --- Infrastructure as code ---
        "main.tf,                              infrastructure,      hcl",
        "vars.tfvars,                          infrastructure,      hcl",

        // --- CI ---
        ".github/workflows/ci.yml,             ci,                  yaml",
        ".github/workflows/release.yaml,       ci,                  yaml",
        ".gitlab-ci.yml,                       ci,                  yaml",
        "Jenkinsfile,                          ci,                  ''",

        // --- Scripts ---
        "scripts/run.sh,                       script,              ''",
        "install.bash,                         script,              ''",
        "Makefile,                             script,              ''",

        // --- Documentation ---
        "README.md,                            documentation,       markdown",
        "docs/guide.rst,                       documentation,       ''",
        "NOTES.adoc,                           documentation,       ''",

        // --- Configuration ---
        "application.properties,               configuration,       properties",
        "application.yml,                      configuration,       yaml",
        "config.yaml,                          configuration,       yaml",
        "logback.xml,                          configuration,       xml",
        ".env,                                 configuration,       dotenv",
        ".env.local,                           configuration,       dotenv",
        "pyproject.toml,                       configuration,       toml",
        "setup.ini,                            configuration,       ini",
        "nginx.conf,                           configuration,       conf",
        "app.cfg,                              configuration,       cfg",

        // --- Data ---
        "seed.json,                            data,                json",
        "rows.csv,                             data,                csv",
        "schema.sql,                           data,                sql",

        // --- Catch-all: polyglot manifests are out of scope for the Java analyzer, so they land in
        //     'other' (still inventoried by hash/size/path), NOT build_manifest. Locking this in keeps
        //     the Java-only scope honest and visible. ---
        "package.json,                         data,                json",
        "go.mod,                               other,               mod",
        "requirements.txt,                     other,               txt",
        "Gemfile,                              other,               ''",
        "LICENSE,                              other,               ''",
        "logo.png,                             other,               png",
    })
    void classifies(String path, String expectedKind, String expectedFormat) {
        Classification c = ArtifactClassifier.classify(path.trim());
        assertEquals(expectedKind.trim(), c.kind, "kind for " + path);
        String wantFormat = expectedFormat == null ? null : expectedFormat.trim();
        if (wantFormat != null && wantFormat.isEmpty()) {
            wantFormat = null;
        }
        assertEquals(wantFormat, c.format, "format for " + path);
    }

    @Test
    void backslashPathsAreNormalized() {
        Classification c = ArtifactClassifier.classify("src\\main\\resources\\application.properties");
        assertEquals("configuration", c.kind);
        assertEquals("properties", c.format);
    }
}
