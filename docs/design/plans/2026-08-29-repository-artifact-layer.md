# Repository-Artifact Layer for Java — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Emit the repository-artifact layer — non-source file inventory, declared dependencies, and flattened config keys — in `analysis.json` and the Neo4j graph.

**Architecture:** A new `com.ibm.cldk.artifacts` package with four seams: a repo-wide discovery walk that classifies every non-source file and never drops one, pure text-in/records-out manifest parsers, a dependency-view assembler that reconciles manifests against lockfiles, and a config-key flattener. The v2 schema gains three application-anchored node types; the v2 Neo4j projector gains three un-prefixed labels that are already reserved in its catalog. Nothing existing is renamed.

**Tech Stack:** Java 11+, Lombok `@Data`, Gson `LOWER_CASE_WITH_UNDERSCORES`, JDK DOM for XML, `java.util.Properties`, SnakeYAML (new dependency), JUnit 5, Gradle.

**Spec:** `repository-artifact-layer.md` in `codellm-devkit/.github`, tracked by epic [.github#45](https://github.com/codellm-devkit/.github/issues/45), Java child [#197](https://github.com/codellm-devkit/codeanalyzer-java/issues/197). **Read [.github#48](https://github.com/codellm-devkit/.github/issues/48) first** — it records that the spec mandates an `artifact_kind` enum, a `scope` enum, and an ecosystem mapping table that was never actually built anywhere. This plan follows what ships rather than what the spec mandates, on the user's explicit instruction; #48 is where that contradiction gets reconciled.

## Global Constraints

- **Emulate v1.3.0, not the spec.** No `artifact_kind` enum, no `scope` enum, no ecosystem table. Bare strings throughout: `format` (one value), `roles` (many), `kind` (`runtime|dev|optional|build`), `ecosystem` (the literal `"maven"`).
- **Never drop a file.** A file matching no rule is still inventoried — `format="text"`, `roles=["unknown"]`, or `format="binary"` when it will not decode. Coverage beats classification. The one exclusion is a `.java` file no rule names; the symbol table owns those.
- **Artifacts hang off the application as sibling maps.** `symbol_table` stays strictly code-keyed. Only `config_keys` nest, inside their owning artifact.
- **`sha256` and `size_bytes` always describe the whole file**, never the captured prefix.
- **Extraction reads from disk, never from `source`.** A truncated or suppressed capture must not silently degrade parsing.
- **Absence means "no fact":** null and empty-optional fields are omitted from JSON, matching how `param_in`/`summary` already behave.
- **Determinism:** collect then sort; two runs byte-identical. Sort dependencies by `(name, declaredIn)` and config keys by `key`.
- **Levels do not gate this layer.** It is L1 data — identical at `-a 1` and `-a 4`. (`config_use`, which would be gated by level, is out of scope here.)
- **Additive only.** No existing model field, CLI flag, or graph label changes meaning.
- Conventional-commit subjects. **No AI/Claude attribution in any commit message, body, or trailer** — hard project rule.
- Spotless is auto-applied; run `./gradlew spotlessApply` before each commit.
- `./gradlew test` has one pre-existing failure, `CodeAnalyzerIntegrationTest > initializationError` (no Docker daemon). `@Tag("realworld")` tests run under `./gradlew realWorldConformanceTest`. Neither is this plan's to fix.

## Where Java cannot be literal

Four places the reference's approach does not port. Each is a decision this plan makes explicitly rather than leaving to an implementer:

1. **Maven scope has no slot in the four-value `kind` vocabulary.** Map `compile`/`runtime` → `runtime`, `test` → `dev`, and **`provided`/`system` → `build`** (both are compile-time-only). `import` scope is not a dependency at all — it is BOM inclusion, and is skipped. Record this mapping in a comment; it is the one place the emulation is a judgement rather than a transcription.
2. **`purl` is two-segment.** A Maven coordinate is `pkg:maven/<groupId>/<artifactId>`, so a flat `name` field splits. `JDependency` carries `group` **and** `name`; `name` alone remains the map/sort key, with `group` additive.
3. **No PEP 503 normalization.** Maven coordinates are case-sensitive and already canonical. Do not port `normalize_name`.
4. **`provides_imports` is a lookup, not a heuristic.** A language whose distribution name differs from its import name needs an alias table; Java packages are declared, so there is nothing to resolve. **This plan does not implement `provides_imports` or `unresolved_imports` at all** — doing it properly means reading package indexes out of resolved jars, which is a separate unit of work. Emit neither field rather than emitting a guess.

## Out of scope

`config_use` / `J_USES_CONFIG` (a detector-rule mechanism; Java's `@Value`-style annotation injection is not a call site, so it needs its own design); transitive Maven resolution beyond what a lockfile states; `provides_imports`/`unresolved_imports` per item 4 above; POM parent and BOM-import inheritance beyond the local file (Maven inheritance is recursive — out here, worth its own issue).

## File Structure

| File | Responsibility |
| --- | --- |
| `schema/JArtifact.java` (create) | the artifact node |
| `schema/JDependency.java` (create) | the dependency node |
| `schema/JConfigKey.java` (create) | the config-key node, nested in its artifact |
| `schema/JApplication.java` (modify) | `artifacts` map + `dependencies` list |
| `schema/CanId.java` (modify) | `artifactId`, `configKeyId`, `purlMaven` |
| `artifacts/ArtifactDiscovery.java` (create) | the walk, the RULES table, text capture |
| `artifacts/ManifestParsers.java` (create) | pure parsers: pom.xml, Gradle, lockfiles |
| `artifacts/DependencyView.java` (create) | assemble + reconcile manifests against locks |
| `artifacts/ConfigKeys.java` (create) | flatten `.properties`, YAML, XML descriptors |
| `CodeAnalyzer.java` (modify) | three flags + the pipeline call |
| `neo4j/V2SchemaCatalog.java` (modify) | `Artifact`/`Package`/`ConfigKey` + four edges, graph 2.2.0 |
| `neo4j/V2GraphProjector.java` (modify) | project them |
| `build.gradle` (modify) | SnakeYAML |
| `schema.neo4j.json` (regenerate) | the contract artifact |

---

### Task 1: Schema models and id grammar

**Files:**
- Create: `src/main/java/com/ibm/cldk/schema/{JArtifact,JDependency,JConfigKey}.java`
- Modify: `src/main/java/com/ibm/cldk/schema/JApplication.java`, `CanId.java`
- Test: `src/test/java/com/ibm/cldk/schema/ArtifactModelTest.java`

**Interfaces produced:**

```java
// JArtifact — mirrors PyArtifact (py_schema.py:501-519)
private String id;                       // can://artifact/<app>/<rel-path>
private String kind = "artifact";
private String path;                     // repo-relative, '/'-separated; also the map key
private String format;                   // xml|yaml|json|properties|gradle|dockerfile|text|binary
private List<String> roles = new ArrayList<>();
private long sizeBytes;
private String sha256;                   // ALWAYS the whole file
private String source = "";              // "" for binary or when capture is off
private boolean textTruncated;
private String extraction = "none";      // none|partial|full
private List<JConfigKey> configKeys = new ArrayList<>();

// JDependency — mirrors PyDependency (py_schema.py:555-570), split name + additive group
private String group;                    // Maven groupId (additive)
private String name;                     // Maven artifactId
private String ecosystem = "maven";
private String spec = "";                // the declared version range/spec, verbatim
private String kind = "runtime";         // runtime|dev|optional|build
private List<String> extras = new ArrayList<>();   // Maven classifiers
private String declaredIn = "";          // JArtifact id
private boolean direct = true;           // false for lockfile-only pins
private String lockedVersion;            // null when unpinned — omitted from JSON
private List<String> prov = new ArrayList<>();     // declared|lockfile|heuristic

// JConfigKey — mirrors PyConfigKey (py_schema.py:484-498)
private String id;                       // <artifact-id>@key/<dotted.key>
private String key;                      // dotted; numeric segments for list indices
private String namespace;                // properties|yaml|xml|env|dockerfile
private String value;                    // null unless --artifact-text
private Span span;                       // best-effort; null is acceptable
private List<String> references = new ArrayList<>();  // raw tokens, in order, deduped
```

`JApplication` gains `Map<String, JArtifact> artifacts` and `List<JDependency> dependencies`, both omitted when empty by the same rule `paramIn` follows. `CanId` gains:

```java
    /** Language-NEUTRAL artifact id — the `java` segment is deliberately replaced by `artifact`
     *  so a sibling analyzer over the same repository lands on the same node. */
    public static String artifactId(String appName, String relPath) {
        return "can://artifact/" + appName + "/" + relPath;
    }

    public static String configKeyId(String artifactId, String dottedKey) {
        return artifactId + "@key/" + dottedKey;
    }

    public static String purlMaven(String group, String name) {
        return "pkg:maven/" + group + "/" + name;
    }
```

- [ ] **Step 1: Write the failing test**

```java
package com.ibm.cldk.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The artifact layer's wire shape and id grammar. */
class ArtifactModelTest {

    @Test
    void artifactIdIsLanguageNeutral() {
        assertEquals("can://artifact/myapp/deploy/docker-compose.yml",
                CanId.artifactId("myapp", "deploy/docker-compose.yml"),
                "the scheme carries `artifact`, not `java` — sibling analyzers must land on this node");
    }

    @Test
    void configKeyIdNestsUnderItsArtifact() {
        String art = CanId.artifactId("myapp", "src/main/resources/application.yml");
        assertEquals(art + "@key/server.port", CanId.configKeyId(art, "server.port"));
    }

    @Test
    void purlIsTwoSegmentForMaven() {
        assertEquals("pkg:maven/org.apache.commons/commons-lang3",
                CanId.purlMaven("org.apache.commons", "commons-lang3"));
    }

    @Test
    void unsetOptionalsAreOmittedNotNulled() {
        JDependency d = new JDependency();
        d.setGroup("org.example");
        d.setName("widget");
        String json = V2Json.compact().toJson(d);
        assertFalse(json.contains("locked_version"), "an unpinned dependency omits the key: " + json);
        assertTrue(json.contains("\"ecosystem\":\"maven\""), json);
        assertTrue(json.contains("\"direct\":true"), json);
    }

    @Test
    void applicationOmitsTheLayerWhenEmpty() {
        JApplication app = new JApplication();
        app.setId("can://java/x");
        String json = V2Json.compact().toJson(app);
        assertFalse(json.contains("artifacts"), json);
        assertFalse(json.contains("dependencies"), json);
    }

    @Test
    void configKeysNestInsideTheirArtifact() {
        JArtifact a = new JArtifact();
        a.setPath("application.properties");
        a.setFormat("properties");
        JConfigKey k = new JConfigKey();
        k.setKey("server.port");
        k.setNamespace("properties");
        a.getConfigKeys().add(k);
        String json = V2Json.compact().toJson(a);
        assertTrue(json.contains("\"config_keys\""), json);
        assertTrue(json.contains("\"server.port\""), json);
    }
}
```

- [ ] **Step 2: Run to verify it fails** — `./gradlew test --tests "com.ibm.cldk.schema.ArtifactModelTest"` → COMPILE FAILURE (types undefined).

- [ ] **Step 3: Implement** the three `@Data` POJOs with the fields above, the two `JApplication` fields (as `null`-defaulted so they are omitted when the layer produces nothing — follow how `paramIn` is declared), and the three `CanId` helpers. Match the surrounding javadoc style: explain *why* a field exists, not what it holds. On `JDependency.group`, note it is additive over the reference, which has no analogue. On `artifactId`, note the neutral scheme is deliberate.

- [ ] **Step 4: Run to verify it passes** — same command, 6 tests PASS.

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply
git add src/main/java/com/ibm/cldk/schema src/test/java/com/ibm/cldk/schema/ArtifactModelTest.java
git commit -m "feat(artifacts): schema models for artifact, dependency and config key"
```

---

### Task 2: Discovery walk, classification, and text capture

**Files:**
- Create: `src/main/java/com/ibm/cldk/artifacts/ArtifactDiscovery.java`
- Test: `src/test/java/com/ibm/cldk/artifacts/ArtifactDiscoveryTest.java`

**Interfaces produced:**

```java
public static Map<String, JArtifact> discover(
        Path projectDir, String appName, boolean captureText, int textMaxBytes)
```

Returns a map keyed by repo-relative `/`-separated path, iteration order sorted by key.

**Behaviour to emulate** (`discovery.py`): walk every regular file under `projectDir`; skip a path any of whose segments is in the ignore set; classify by a **first-match-wins** rules table; a pattern containing `/` matches the repo-relative path, otherwise the basename. Read bytes once — `sha256` and `sizeBytes` come from the full bytes regardless of what is captured.

The Java rules table, adapted (Maven/Gradle/Spring instead of pip/setuptools):

```java
    // First match wins — order matters. Specific names precede generic globs, so
    // `pom.xml` is a manifest while any other `*.xml` is a config candidate.
    private static final List<Rule> RULES = List.of(
            new Rule("pom.xml", "xml", List.of("dependency-manifest")),
            new Rule("build.gradle", "gradle", List.of("dependency-manifest", "tool-config")),
            new Rule("build.gradle.kts", "gradle", List.of("dependency-manifest", "tool-config")),
            new Rule("settings.gradle", "gradle", List.of("tool-config")),
            new Rule("settings.gradle.kts", "gradle", List.of("tool-config")),
            new Rule("gradle.lockfile", "text", List.of("dependency-manifest")),
            new Rule("gradle.properties", "properties", List.of("tool-config")),
            new Rule("gradle/libs.versions.toml", "text", List.of("dependency-manifest")),
            new Rule("ivy.xml", "xml", List.of("dependency-manifest")),
            new Rule("Dockerfile", "dockerfile", List.of("container-image")),
            new Rule("*.dockerfile", "dockerfile", List.of("container-image")),
            new Rule("docker-compose*.yml", "yaml", List.of("service-topology")),
            new Rule("docker-compose*.yaml", "yaml", List.of("service-topology")),
            new Rule("compose.yml", "yaml", List.of("service-topology")),
            new Rule("compose.yaml", "yaml", List.of("service-topology")),
            new Rule("k8s/*.yml", "yaml", List.of("service-topology")),
            new Rule("k8s/*.yaml", "yaml", List.of("service-topology")),
            new Rule("Chart.yaml", "yaml", List.of("service-topology")),
            new Rule("values.yaml", "yaml", List.of("service-topology")),
            new Rule("application*.yml", "yaml", List.of("tool-config")),
            new Rule("application*.yaml", "yaml", List.of("tool-config")),
            new Rule("application*.properties", "properties", List.of("tool-config")),
            new Rule("bootstrap*.yml", "yaml", List.of("tool-config")),
            new Rule("logback*.xml", "xml", List.of("tool-config")),
            new Rule("web.xml", "xml", List.of("tool-config")),
            new Rule("persistence.xml", "xml", List.of("tool-config")),
            new Rule("beans.xml", "xml", List.of("tool-config")),
            new Rule("*.tf", "text", List.of("iac")),
            new Rule(".github/workflows/*.yml", "yaml", List.of("ci")),
            new Rule(".github/workflows/*.yaml", "yaml", List.of("ci")),
            new Rule(".gitlab-ci.yml", "yaml", List.of("ci")),
            new Rule("Jenkinsfile", "text", List.of("ci")),
            new Rule(".env", "text", List.of("env")),
            new Rule(".env.*", "text", List.of("env")),
            new Rule("LICENSE*", "text", List.of("legal")),
            new Rule("NOTICE*", "text", List.of("legal")),
            new Rule("*.md", "text", List.of("docs")),
            new Rule("*.adoc", "text", List.of("docs")),
            new Rule("*.properties", "properties", List.of("tool-config")),
            new Rule("*.yml", "yaml", List.of("unknown")),
            new Rule("*.yaml", "yaml", List.of("unknown")),
            new Rule("*.json", "json", List.of("unknown")),
            new Rule("*.xml", "xml", List.of("unknown")));
```

Ignore set, scoped to Java build output:

```java
    private static final Set<String> IGNORED = Set.of(
            ".git", ".hg", ".svn", "target", "build", "out", "bin",
            ".gradle", ".mvn", ".idea", ".settings", "node_modules",
            ".codeanalyzer", "_library_dependencies");
```

Note `build` and `target` here are the same class of exclusion the L1 extractor learned in #199 — build output is not project code. This set matches a path **segment**, so a *file* named `build` is not excluded; use a directory check rather than a check over every segment including the leaf, which has that bug.

Capture (`_capture_source`, `discovery.py:79-91`), including the exemption:

```java
    // A dependency manifest is always captured whole when capture is on: the cap exists to bound
    // bulk assets, not the files extraction depends on. `--no-artifact-text` still empties it.
    int cap = roles.contains("dependency-manifest") ? raw.length : textMaxBytes;
```

Byte cap, not character cap; decode the prefix with a decoder that drops a split multi-byte character rather than throwing.

**Never-drop rules**, in order: undecodable → `format="binary"`, `source=""`, roles kept if rule-matched else `["unknown"]`; decodable and rule-matched → as the rule says; decodable, no rule, name contains `.` → `format="text"`, `roles=["unknown"]`; decodable, no rule, extensionless, first two bytes `#!` → `roles=["script"]`; **a `.java` file no rule names is skipped entirely** — the symbol table owns it.

- [ ] **Step 1: Write the failing test** — cover, at minimum: a `pom.xml` gets `dependency-manifest`; an unmatched `.txt` is still inventoried as `text`/`unknown`; a file under `target/` is absent; a file under `build/` is absent; a `.java` file is absent; a non-UTF-8 file becomes `binary` with `source=""` but keeps its `sha256` and `sizeBytes`; a file over the cap is truncated with `textTruncated=true` while `sha256` still hashes the whole file; a `pom.xml` over the cap is **not** truncated; `captureText=false` yields identical inventory with every `source` empty; two runs produce identical key order. Use `@TempDir` and write the fixture files inline, as `L1ExtractorTest` does.

- [ ] **Step 2: Run to verify it fails** — COMPILE FAILURE.

- [ ] **Step 3: Implement.** Read `discovery.py` first. Use `Files.walk` with a `try`-with-resources, sort the paths, and build a `TreeMap` so iteration order is the key order. Compute `sha256` with `MessageDigest.getInstance("SHA-256")` and hex-encode lowercase.

- [ ] **Step 4: Run to verify it passes.**

- [ ] **Step 5: Commit**

```bash
./gradlew spotlessApply
git add src/main/java/com/ibm/cldk/artifacts src/test/java/com/ibm/cldk/artifacts
git commit -m "feat(artifacts): repo-wide discovery walk with never-drop inventory"
```

---

### Task 3: Manifest and lockfile parsers

**Files:**
- Create: `src/main/java/com/ibm/cldk/artifacts/ManifestParsers.java`
- Test: `src/test/java/com/ibm/cldk/artifacts/ManifestParsersTest.java`

**Interfaces produced:**

```java
/** A parsed declaration, before reconciliation. Immutable. */
public static final class RawDep {
    public final String group;
    public final String name;
    public final String spec;
    public final String kind;      // runtime|dev|optional|build
    public final List<String> extras;
}

/** Records plus a partial flag — an unparseable manifest keeps its artifact and flags extraction. */
public static final class ParseResult {
    public final List<RawDep> deps;
    public final boolean partial;
}

/** Dispatch on basename. An unknown basename returns an empty, non-partial result. */
public static ParseResult parseManifest(String path, String text);

/** name -> resolved version, from a lockfile. Never throws; a malformed lock returns empty. */
public static Map<String, String> parseLockPins(String path, String text);
```

Parsers, each with its failure behaviour:

| Trigger (basename) | Library | Extracts | Kind | On failure |
| --- | --- | --- | --- | --- |
| `pom.xml` | JDK DOM (`DocumentBuilderFactory`, **`setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)`** and external entities disabled — it parses untrusted repo content) | `/project/dependencies/dependency` → `groupId`, `artifactId`, `version` → `spec`, `classifier` → `extras`, `<scope>` mapped per the Global Constraints table, `<optional>true</optional>` → `optional`; `import`-scoped entries skipped | from `<scope>` | `([], partial=true)` |
| `build.gradle` / `.kts` | shallow regex | `implementation`/`api`/`compileOnly`/`runtimeOnly`/`testImplementation`/`annotationProcessor` with a `'g:a:v'` or `"g:a:v"` string literal | `testImplementation` → `dev`, `compileOnly`/`annotationProcessor` → `build`, else `runtime` | a line that does not match is skipped; the file never fails |
| `gradle.lockfile` | line reader | `g:a:v=configurations` → `{"g:a": "v"}` | n/a | empty map |

The Gradle reader is deliberately shallow and purely static: a `build.gradle` is a program, and evaluating it is out of scope. Interpolated versions (`$springVersion`) yield the literal text as `spec` and no `lockedVersion` — record that as a known gap in a comment rather than attempting resolution.

- [ ] **Step 1: Write the failing test** — one case per format plus: a malformed `pom.xml` returns `partial=true` and no records; a `pom.xml` with `<scope>test</scope>` yields `kind=dev`; `provided` and `system` both yield `build`; an `import`-scoped entry is absent; a Gradle line with an interpolated version keeps the literal spec; a malformed lockfile returns an empty map rather than throwing. Include an XXE probe — a `pom.xml` with a `<!DOCTYPE ... SYSTEM "file:///etc/passwd">` must not resolve it.

- [ ] **Step 2: Run to verify it fails.**

- [ ] **Step 3: Implement.** Read `parsers.py` first for the shape. Keep this class pure — text in, records out, no file I/O, no execution.

- [ ] **Step 4: Run to verify it passes.**

- [ ] **Step 5: Commit** — `feat(artifacts): pom.xml, Gradle and lockfile dependency parsers`

---

### Task 4: The dependency view

**Files:**
- Create: `src/main/java/com/ibm/cldk/artifacts/DependencyView.java`
- Test: `src/test/java/com/ibm/cldk/artifacts/DependencyViewTest.java`

**Interfaces produced:**

```java
/** Assemble declared dependencies, reconcile them against lock pins, and set each artifact's
 *  `extraction`. Mutates the artifacts' `extraction` in place; returns the sorted dependency list. */
public static List<JDependency> build(Path projectDir, Map<String, JArtifact> artifacts);
```

Emulating `dependencies.py`'s two steps that matter here:

1. **Declared.** For each artifact with role `dependency-manifest`, re-read its text **from disk** (not from `source` — capture may be off or truncated) and `parseManifest`. Each record becomes a `JDependency` with `declaredIn` = that artifact's id and `prov=["declared"]`. `partial=true` sets that artifact's `extraction="partial"`, otherwise `"full"`.
2. **Lock backfill.** For each lockfile artifact, `parseLockPins`. A pin whose `group:name` matches a declared dependency sets its `lockedVersion`. A pin with **no** declaration becomes its own `JDependency` with `direct=false`, `prov=["lockfile"]`, `kind="runtime"`, `declaredIn` = the lock artifact's id. This contradicts the spec's "transitive out of scope" — see [.github#48](https://github.com/codellm-devkit/.github/issues/48).

Lock extraction status: `"full"` when pins were found **or** the lock text is blank; `"partial"` when it has content but yielded nothing.

Sort by `(name, declaredIn)`. Do **not** implement `provides_imports` or `unresolved_imports` — see the plan's "Where Java cannot be literal", item 4.

- [ ] **Step 1: Write the failing test** — a declared dependency carries `prov=["declared"]` and `direct=true`; a lock pin matching it sets `lockedVersion` without duplicating it; a lock pin with no declaration appears with `direct=false` and `prov=["lockfile"]`; a malformed manifest sets its artifact to `partial` while the run still succeeds; ordering is stable across two runs; a manifest whose `source` was suppressed by `--no-artifact-text` still parses (this is the from-disk rule, and it is the one most likely to regress).

- [ ] **Step 2–5:** fail, implement (read `dependencies.py` first), pass, commit — `feat(artifacts): dependency view with lockfile reconciliation`

---

### Task 5: Config-key flattening

**Files:**
- Create: `src/main/java/com/ibm/cldk/artifacts/ConfigKeys.java`
- Modify: `build.gradle` (SnakeYAML)
- Test: `src/test/java/com/ibm/cldk/artifacts/ConfigKeysTest.java`

**Interfaces produced:**

```java
/** (keys, ok). `ok=false` means a parse failure; a format with no flattener returns (empty, true). */
public static Result extract(JArtifact artifact, String text, boolean captureValue);
public static boolean isEligible(JArtifact artifact);
```

**New dependency.** YAML needs `org.yaml:snakeyaml`. It is present transitively in the Gradle cache but **not declared**, so add it explicitly — relying on a transitive is how a build breaks when an unrelated dependency drops it. This is the plan's one new dependency; hand-writing a YAML parser is not a few lines, and Spring Boot's `application.yml` is the single most valuable config source in a Java repo.

Namespaces and flatteners, emulating `config_keys.py`:

- **`properties`** — `java.util.Properties` handles `key=value`, `key:value`, `\` continuations, and `#`/`!` comments natively. Namespace `properties`. Last-wins.
- **`yaml`** — SnakeYAML `load`, then flatten to dotted paths with **numeric segments for list indices** (`servers.0.host`). Namespace `yaml`. Dual-mint compose environment blocks into namespace `env` keyed on the bare variable name, with the id disambiguated by an `env.` prefix so `X` as a yaml path and `X` as an env var do not collide.
- **`xml`** — JDK DOM (same hardening as Task 3), flattened as element paths with numeric segments for repeated siblings; attributes as `path@attr`. Namespace `xml`. Keep it simple and say so.
- **`dockerfile`** — `ENV K=v` → namespace `env`; `ARG K[=default]` → namespace `dockerfile` with an `arg.` id prefix. Two accepted gaps: no BuildKit heredoc awareness, no multi-stage `FROM ... AS` scoping. Record both in comments rather than rediscovering them.

`references[]` — raw recognized tokens, verbatim with sigils, in order, deduplicated. Java's dominant form is `${VAR}` (Spring placeholder and Maven property alike), plus `$VAR`. Match `${...}` first and mask it before the bare form, so `${A}` does not also yield `$A`.

`value` is populated only when `captureValue` is true; keys, namespaces, spans and references are extracted regardless. Spans are best-effort and `null` is acceptable — a documented, accepted gap.

- [ ] **Step 1: Write the failing test** — per format: a `.properties` file yields dotted keys and last-wins; a Spring `application.yml` yields `server.port` and a list index; an XML descriptor yields an element path; a Dockerfile yields `ENV` in `env` and `ARG` in `dockerfile` without id collision; `${DB_HOST}` appears in `references` while `$DB_HOST` in the same value does not double-count; `captureValue=false` keeps keys and references but nulls `value`; a malformed YAML returns `ok=false` and does not throw; a binary artifact is not eligible.

- [ ] **Step 2–5:** fail, implement (read `config_keys.py` first), pass, commit — `feat(artifacts): config-key flattening for properties, yaml, xml and dockerfile`

---

### Task 6: Wire into the pipeline

**Files:**
- Modify: `src/main/java/com/ibm/cldk/CodeAnalyzer.java`
- Test: `src/test/java/com/ibm/cldk/CodeAnalyzerV2CliTest.java` (append)

**Interfaces produced:** three flags, and the pipeline call.

```java
    @Option(names = { "--artifact-text" }, negatable = true,
            description = "Capture non-source file text into artifact nodes (default: true).")
    public static boolean artifactText = true;

    @Option(names = { "--artifact-text-max-bytes" },
            description = "Byte cap on captured artifact text (default: 262144). "
                    + "Dependency manifests are exempt — they are always captured whole.")
    public static int artifactTextMaxBytes = 262144;
```

No `--resolve-installed` flag: it would exist only for a distribution-name/import-name mismatch, which Java does not have.

The call site goes in `analyzeV2`, **after** `L2CallGraph.build` and beside `SdgVertices.apply`, because it is application-scope data assembled once. It runs at **every** level — this is L1 data, invariant across `-a`:

```java
        Map<String, JArtifact> artifacts =
                ArtifactDiscovery.discover(Paths.get(input), application, artifactText, artifactTextMaxBytes);
        List<JDependency> dependencies = DependencyView.build(Paths.get(input), artifacts);
        for (JArtifact a : artifacts.values()) {
            if (ConfigKeys.isEligible(a)) {
                // Re-read from disk: `source` may be truncated or suppressed, and extraction
                // must not silently degrade with a capture flag.
                ConfigKeys.Result r = ConfigKeys.extract(a, readFully(a), artifactText);
                a.setConfigKeys(r.keys);
                // A pre-existing "partial" from the dependency pass is never overwritten.
                if (!r.ok) {
                    a.setExtraction("partial");
                } else if ("none".equals(a.getExtraction())) {
                    a.setExtraction("full");
                }
            }
        }
```

Then attach both to the application through a `V2Emitter` overload, following the shape the `paramIn`/`paramOut` overload already established.

- [ ] **Step 1: Write the failing test** — a `-a 1` run over a fixture with a `pom.xml` and an `application.properties` emits `artifacts` and `dependencies`; the same run at `-a 4` emits **byte-identical** artifact/dependency/config sections (level invariance is the property most likely to break); `--no-artifact-text` keeps the inventory identical while emptying every `source` and nulling every `value`; a project with no non-source files omits both keys entirely.

- [ ] **Step 2–5:** fail, implement, pass, commit — `feat(artifacts): emit the repository-artifact layer at every analysis level`

---

### Task 7: Neo4j projection, graph contract 2.2.0

**Files:**
- Modify: `src/main/java/com/ibm/cldk/neo4j/V2SchemaCatalog.java`, `V2GraphProjector.java`
- Modify: `schema.neo4j.json` (regenerate)
- Test: `src/test/java/com/ibm/cldk/neo4j/V2Neo4jSchemaConformanceTest.java` (extend)

This is the part [#197](https://github.com/codellm-devkit/codeanalyzer-java/issues/197) originally deferred on a premise that PR #203 invalidated. `V2SchemaCatalog` already **reserves** un-prefixed `Artifact` and `Package` labels; this task emits them and adds `ConfigKey`.

Vocabulary — **nodes and containment edges are neutral, this-analyzer's-claims are `J_`-prefixed**:

| Label | Merge | Key | Props |
| --- | --- | --- | --- |
| `Artifact` | `Artifact` | `id` | `id`, `path`, `format`, `roles[]`, `size_bytes`, `sha256`, `source`, `text_truncated`, `extraction` |
| `Package` | `Package` | `id` | `id`, `ecosystem`, `group`, `name` |
| `ConfigKey` | `ConfigKey` | `id` | `id`, `key`, `namespace`, `value`, `references[]`, `start_line`, `end_line` |

| Edge | From → To | Props |
| --- | --- | --- |
| `HAS_ARTIFACT` | JApplication → Artifact | — |
| `DEFINES_CONFIG` | Artifact → ConfigKey | — |
| `DECLARES_DEPENDENCY` | Artifact → Package | `spec`, `kind`, `extras[]`, `prov[]`, `direct`, `_k` |
| `LOCKS` | Artifact → Package | `version` |

`_k` on `DECLARES_DEPENDENCY` is `kind`, because one manifest may declare the same package under two kinds, and without the discriminant the plain MERGE collapses them. Use the `keyedEdge` machinery the L4 overlay added.

`Package` nodes exist **only in the graph** — JSON carries the bare dependency. Mint them from `CanId.purlMaven`. Constraints derive automatically from `uniquenessConstraints()`, so each new label brings its own.

Bump `SCHEMA_VERSION` to `"2.2.0"` — additive. Bumping matters even with no consumers yet: without it a consumer cannot detect the layer's presence from the version.

- [ ] **Step 1: Extend the conformance test first** — assert `Artifact`, `Package` and `ConfigKey` rows are emitted (not merely declared), that `HAS_ARTIFACT`/`DEFINES_CONFIG`/`DECLARES_DEPENDENCY`/`LOCKS` all appear, and that every projected property is declared in the catalog. Point the pipeline at a fixture with a `pom.xml`, a lockfile and an `application.properties` so all four edges are non-empty.

- [ ] **Step 2: Run to verify it fails.**

- [ ] **Step 3: Implement**, then regenerate the artifact:

```bash
./gradlew fatJar -x test && java -jar build/libs/codeanalyzer-*.jar --emit schema > schema.neo4j.json
```

- [ ] **Step 4: Verify** — the conformance byte-match passes, then load a real `graph.cypher` into a throwaway Neo4j 5 container and confirm `MATCH ()-[r:DECLARES_DEPENDENCY]->() RETURN count(r)` is non-zero and no endpoint dangles. `podman` is available; `docker` is not.

- [ ] **Step 5: Commit** — `feat(artifacts): project Artifact/Package/ConfigKey; graph contract 2.2.0`

---

## Self-Review (performed while writing)

- **Spec coverage:** #197's "In (this PR)" items map to Tasks 1–6; its deferred Neo4j section becomes Task 7, per the update recorded on the issue. The three vocabularies #197 inherits from the spec (`artifact_kind`, `scope`, the ecosystem table) are deliberately **not** implemented — see the plan header and [.github#48](https://github.com/codellm-devkit/.github/issues/48).
- **Placeholder scan:** every task carries runnable commands and either complete code or a table specifying each case. Tasks 3–5 name the reference file to read first rather than reproducing hundreds of lines of parser logic — the contract is stated here, the rationale lives there.
- **Type consistency:** `RawDep`/`ParseResult` in Task 3 are consumed by Task 4's `build`; `ConfigKeys.Result` in Task 5 is consumed by Task 6's wiring; `CanId.purlMaven` from Task 1 is used in Task 7. `JArtifact.extraction` is written by both Task 4 and Task 6, with the never-overwrite-partial rule stated in both.
- **Known risks not designed away:** the Gradle reader is regex-shallow and will miss anything computed; POM parent and BOM inheritance is out of scope, so a multi-module build reports only what each POM states locally; XML config flattening is net-new with no reference to check against. All three are named where they occur rather than discovered later.
