package com.ibm.cldk.artifacts;

import com.ibm.cldk.schema.CanId;
import com.ibm.cldk.schema.JDependency;
import com.ibm.cldk.utils.Log;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort extraction of declared dependencies from a Gradle build script. Gradle build scripts are
 * arbitrary Groovy/Kotlin programs, so there is no exact static path short of running the build; this
 * parser recognizes the common declaration forms and <em>logs the ones it cannot</em> rather than
 * guessing — {@code pom.xml} is the exact path, and {@code gradle.lockfile} carries the resolved graph.
 *
 * <p>Recognized: string-notation declarations in both quote styles across the standard configurations —
 * {@code implementation 'group:name:version'}, {@code api("group:name:version")},
 * {@code testImplementation group: 'g', name: 'n', version: 'v'}. The configuration name maps onto the
 * canonical scope vocabulary; a version with an interpolation ({@code $var} / {@code ${var}}) is kept
 * as the spec but left unresolved. Map-notation and dynamically-computed coordinates are counted and
 * logged as gaps.
 */
final class GradleDependencyParser {

    private GradleDependencyParser() {}

    // configuration 'group:name:version'  — single- or double-quoted, optional parentheses.
    private static final Pattern STRING_NOTATION = Pattern.compile(
            "(?m)^\\s*(\\w+)\\s*\\(?\\s*[\"']([^\"'\\s]+?):([^\"'\\s]+?):([^\"'\\s]+?)[\"']");

    // configuration 'group:name'  (no version — version comes from a BOM/platform or lockfile).
    private static final Pattern STRING_NOTATION_NO_VERSION = Pattern.compile(
            "(?m)^\\s*(\\w+)\\s*\\(?\\s*[\"']([^\"'\\s:]+?):([^\"'\\s:]+?)[\"']\\s*\\)?\\s*$");

    // configuration group: 'g', name: 'n', version: 'v'  (version optional).
    private static final Pattern MAP_NOTATION = Pattern.compile(
            "(?m)^\\s*(\\w+)\\s+group:\\s*[\"']([^\"']+)[\"']\\s*,\\s*name:\\s*[\"']([^\"']+)[\"']"
                    + "(?:\\s*,\\s*version:\\s*[\"']([^\"']+)[\"'])?");

    /** Any line whose first token is a known configuration and that mentions a dependency-like coordinate
     * — used to count declarations the specific patterns above did not capture. */
    private static final Pattern CONFIG_LINE = Pattern.compile(
            "(?m)^\\s*(implementation|api|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly|"
                    + "testCompileOnly|annotationProcessor|developmentOnly|providedRuntime|providedCompile)\\b.*");

    static Map<String, JDependency> parse(String artifactId, byte[] bytes, String relPath) {
        Map<String, JDependency> deps = new LinkedHashMap<>();
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        int recognized = 0;

        recognized += collect(STRING_NOTATION, text, artifactId, deps, /*hasName*/ true);
        recognized += collect(MAP_NOTATION, text, artifactId, deps, true);
        recognized += collectNoVersion(text, artifactId, deps);

        // Count declaration lines we did not turn into a dependency, so the gap is visible rather than
        // silently swallowed (per the analyzer's best-effort-with-logged-gaps contract for Gradle).
        int declarationLines = count(CONFIG_LINE, text);
        int gaps = declarationLines - recognized;
        if (gaps > 0) {
            Log.warn("Gradle parse of " + relPath + ": " + recognized + " of ~" + declarationLines
                    + " dependency declarations recognized; " + gaps + " use a form this best-effort "
                    + "parser does not read (map/platform/computed coordinates). pom.xml or "
                    + "gradle.lockfile is the exact path.");
        }
        return deps;
    }

    private static int collect(
            Pattern pattern, String text, String artifactId, Map<String, JDependency> deps, boolean hasName) {
        Matcher m = pattern.matcher(text);
        int n = 0;
        while (m.find()) {
            String config = m.group(1);
            String group = m.group(2);
            String name = m.group(3);
            String version = m.groupCount() >= 4 ? m.group(4) : null;
            addDependency(deps, artifactId, config, group, name, version);
            n++;
        }
        return n;
    }

    private static int collectNoVersion(String text, String artifactId, Map<String, JDependency> deps) {
        Matcher m = STRING_NOTATION_NO_VERSION.matcher(text);
        int n = 0;
        while (m.find()) {
            addDependency(deps, artifactId, m.group(1), m.group(2), m.group(3), null);
            n++;
        }
        return n;
    }

    private static void addDependency(
            Map<String, JDependency> deps,
            String artifactId,
            String config,
            String group,
            String name,
            String version) {
        String nativeName = group + ":" + name;
        if (deps.containsKey(nativeName)) {
            return; // first declaration wins; keep output deterministic
        }
        JDependency d = new JDependency();
        d.setId(CanId.dependencyId(artifactId, nativeName));
        d.setName(nativeName);
        // Gradle coordinates live in the Maven coordinate space (groupId:artifactId), so the ecosystem
        // is "maven" for both build tools — a consumer reads one dependency identity space. Which tool
        // declared a dep stays recoverable from the containing artifact's format (gradle vs xml).
        d.setEcosystem("maven");
        d.setDirect(true);
        if (version != null && !version.isEmpty()) {
            d.setVersionSpec(version);
            if (!version.contains("$")) {
                d.setResolvedVersion(version);
            }
        }
        d.setScope(canonicalScope(config));
        deps.put(nativeName, d);
    }

    private static int count(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /** Map a Gradle configuration name onto the canonical scope vocabulary. */
    private static String canonicalScope(String config) {
        switch (config) {
            case "implementation":
            case "api":
            case "runtimeOnly":
            case "providedRuntime":
                return "runtime";
            case "compileOnly":
            case "developmentOnly":
            case "providedCompile":
            case "annotationProcessor":
                return "development";
            case "testImplementation":
            case "testRuntimeOnly":
            case "testCompileOnly":
                return "test";
            default:
                return "unknown";
        }
    }
}
