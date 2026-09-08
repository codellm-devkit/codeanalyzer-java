# `can://<app>/java/…` Identity Migration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move `<app>` to the outermost `can://` segment and give the Neo4j application root the `can://` id it already emits in `analysis.json`, so a multi-service application is addressable rather than merely queryable.

**Architecture:** Every durable id is minted in exactly one class, `com.ibm.cldk.schema.CanId`. Change the mint point and the ids move everywhere. The work is then (a) two places that pattern-match on the old shape, (b) the graph root's merge key, (c) the JSON-schema patterns, and (d) fixture churn. No new node kinds, no new edges, no new fields beyond the root's `id`.

**Tech Stack:** Java 11+, Gradle, JUnit 5, JavaParser, Neo4j (Cypher snapshot + Bolt), Gson (`LOWER_CASE_WITH_UNDERSCORES`).

**Spec:** `codellm-devkit/.github` → `docs/design/specs/2026-09-07-can-uri-app-outermost.md` (PR .github#68). Read §4 D1–D3 and §5 before starting.

## Global Constraints

- **codeanalyzer-java only.** The sibling analyzers keep `can://<lang>/<app>/…`. This is a deliberate, recorded parity divergence (spec §6) — do not "fix" python or typescript in this work.
- **Ships in 3.1.1.** `gradle.properties` is already `version=3.1.1`. Do not bump it again.
- **New grammar, exactly:**
  - durable: `can://<app>/java/<file>/<type>/<signature>`
  - external: `can://<app>/java/@external/<binary-type>/<signature>`
  - artifact: `can://<app>/artifact/<rel-path>`
  - config key: `<artifactId>@key/<dotted.key>` and `<artifactId>@key:env/<bare>` — unchanged suffixes
  - ordinal: `<callable-id>@<line>:<col>` / `<callable-id>@<tag>` — unchanged
- **`--app-name` keeps its name and its default** (the input directory's name). No CLI flag is renamed or added.
- **Do not touch `V2SchemaCatalog.SCHEMA_VERSION`.** It stays `2.0.0`; Task 7 decides the payload-level version separately, and #238 recorded "do not bump this to advertise them" for the graph constant.
- Run every gradle command with `JAVA_HOME=~/.sdkman/candidates/java/current`.
- Commit after each task. Branch: `feat/issue-NNN-can-uri-app-outermost`.

---

## File Structure

| File | Responsibility after this change |
| --- | --- |
| `src/main/java/com/ibm/cldk/schema/CanId.java` | **The** mint point. `SCHEME` stops being a constant prefix and becomes app-parameterised. |
| `src/main/java/com/ibm/cldk/syntactic_analysis/L2CallGraph.java` | Stops testing `hint.startsWith(CanId.SCHEME)`; the scheme is no longer a fixed prefix. |
| `src/main/java/com/ibm/cldk/L3WalaOverlays.java` | Its `deriveApplicationId` fallback stops composing `SCHEME + "/unknown"`. |
| `src/main/java/com/ibm/cldk/neo4j/V2GraphProjector.java` | Projects the root's `id`; merges the root on it. |
| `src/main/java/com/ibm/cldk/neo4j/V2SchemaCatalog.java` | `:JApplication` declares `id` and keys on it. |
| `src/main/java/com/ibm/cldk/neo4j/Schema.java` | The uniqueness constraint moves from `name` to `id`. |
| `src/test/resources/schema/analysis.v2.schema.json` | `canId` / `externalCanId` / `artifactCanId` patterns. |
| `schema.neo4j.json` | Regenerated snapshot. |
| `docs/design/plans/2026-09-07-can-uri-app-outermost.md` | This plan. |

---

### Task 1: `CanId` mints the new grammar

**Files:**
- Modify: `src/main/java/com/ibm/cldk/schema/CanId.java`
- Test: `src/test/java/com/ibm/cldk/schema/CanIdTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `CanId.applicationId(String appName)` → `can://<app>`; `CanId.externalId(String appName, String binaryType, String signature)` → `can://<app>/java/@external/<binaryType>/<signature>`; `CanId.artifactId(String appName, String relPath)` → `can://<app>/artifact/<relPath>`; `CanId.LANG` (`"java"`); `moduleId(String applicationId, String fileKey)` keeps its signature but its OUTPUT changes — it now inserts the language segment, yielding `can://<app>/java/<file>`. `childId`, `ordinalId`, `configKeyId`, `configKeyEnvDualMintId`, `purlMaven` keep both their signatures and their behaviour.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void applicationIdPutsTheAppOutermost() {
    assertEquals("can://daytrader8", CanId.applicationId("daytrader8"));
}

@Test
void moduleIdNestsTheLanguageUnderTheApp() {
    String app = CanId.applicationId("daytrader8");
    assertEquals("can://daytrader8/java/src/Foo.java",
            CanId.moduleId(app, "src/Foo.java"));
}

@Test
void externalIdIsAppThenLanguage() {
    assertEquals("can://daytrader8/java/@external/java.util.Map/get(java.lang.Object)",
            CanId.externalId("daytrader8", "java.util.Map", "get(java.lang.Object)"));
}

@Test
void artifactIdNestsUnderTheAppInsteadOfAParallelScheme() {
    // Was can://artifact/<app>/<path> — a third outermost shape. Now one rule.
    assertEquals("can://daytrader8/artifact/pom.xml",
            CanId.artifactId("daytrader8", "pom.xml"));
}

@Test
void everyIdSharesTheApplicationPrefix() {
    // This is what makes the prefix-scoped delete correct, so assert it directly.
    String app = CanId.applicationId("daytrader8");
    for (String id : List.of(
            CanId.moduleId(app, "src/Foo.java"),
            CanId.externalId("daytrader8", "java.util.Map", "get(java.lang.Object)"),
            CanId.artifactId("daytrader8", "pom.xml"))) {
        assertTrue(id.startsWith(app + "/"), id + " must sit under " + app);
    }
}
```

- [ ] **Step 2: Run them and confirm they fail**

Run: `JAVA_HOME=~/.sdkman/candidates/java/current ./gradlew test --tests 'com.ibm.cldk.schema.CanIdTest'`
Expected: FAIL — current ids are `can://java/daytrader8`, `can://artifact/daytrader8/pom.xml`.

- [ ] **Step 3: Change the mint point**

```java
/** The scheme prefix. The language is no longer part of it — see {@link #LANG}. */
public static final String SCHEME = "can://";

/** This analyzer's language segment, which now sits INSIDE the app rather than above it. */
public static final String LANG = "java";

/** {@code can://<app>} — the application root, and the prefix every id below it shares. */
public static String applicationId(String appName) {
    return SCHEME + appName;
}

/** {@code <applicationId>/java/<relative-file-key>} (separators normalized to {@code /}). */
public static String moduleId(String applicationId, String fileKey) {
    String rel = fileKey.replace("\\", "/").replaceFirst("^[./]+", "");
    return applicationId + "/" + LANG + "/" + rel;
}

/** {@code can://<app>/java/@external/<binary-type>/<signature>}. */
public static String externalId(String appName, String binaryType, String signature) {
    return applicationId(appName) + "/" + LANG + "/@external/" + binaryType + "/" + signature;
}

/** {@code can://<app>/artifact/<rel-path>} — language-neutral, now nested under the app. */
public static String artifactId(String appName, String relPath) {
    return applicationId(appName) + "/artifact/" + relPath;
}
```

Leave `childId`, `ordinalId`, `configKeyId`, `configKeyEnvDualMintId` and `purlMaven` untouched — they compose from a parent id and are shape-agnostic.

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `JAVA_HOME=~/.sdkman/candidates/java/current ./gradlew test --tests 'com.ibm.cldk.schema.CanIdTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/ibm/cldk/schema/CanId.java src/test/java/com/ibm/cldk/schema/CanIdTest.java
git commit -m "feat(id): mint can://<app>/java/... with the app outermost"
```

---

### Task 2: Fix the two places that pattern-match the old prefix

**Files:**
- Modify: `src/main/java/com/ibm/cldk/syntactic_analysis/L2CallGraph.java:267`
- Modify: `src/main/java/com/ibm/cldk/L3WalaOverlays.java:375`
- Test: `src/test/java/com/ibm/cldk/schema/L2CallGraphGateTest.java`

**Interfaces:**
- Consumes: `CanId.SCHEME` (now `"can://"`), `CanId.LANG` from Task 1.
- Produces: nothing new.

These are the only two places in `src/main` that assume the id *starts with* the language. Both compile fine after Task 1 and are wrong at runtime, which is why they get their own task and their own test.

- [ ] **Step 1: Write the failing test**

```java
@Test
void anAnonymousCreationHintIsRecognisedUnderTheNewGrammar() {
    // L1 stores a resolved can-id in declaringTypeHint; L2 must recognise it as an id
    // rather than a type name. Under the old grammar it tested startsWith("can://java").
    String app = CanId.applicationId("app");
    String id = CanId.childId(CanId.moduleId(app, "src/A.java"), "A") + "/A()";
    assertTrue(id.startsWith(CanId.SCHEME),
            "an id must be recognisable by scheme alone, not by a language prefix");
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `JAVA_HOME=~/.sdkman/candidates/java/current ./gradlew test --tests 'com.ibm.cldk.schema.L2CallGraphGateTest'`
Expected: FAIL before Task 1 is merged; after Task 1 it passes trivially — the real check is Step 4's full-suite run.

- [ ] **Step 3: Make both sites shape-independent**

`L2CallGraph.java:267` — the test is "is this hint already an id?", which the scheme alone answers:

```java
        if (hint.startsWith(CanId.SCHEME)) {
```

(unchanged source text, but `SCHEME` is now `"can://"` rather than `"can://java"`, so it keeps meaning "is an id" instead of accidentally meaning "is a java id".)

`L3WalaOverlays.java:375` — the fallback composed a bare language prefix:

```java
            return CanId.applicationId("unknown");
```

- [ ] **Step 4: Run the full suite**

Run: `JAVA_HOME=~/.sdkman/candidates/java/current ./gradlew test`
Expected: the only failure is `CodeAnalyzerIntegrationTest` (needs a Docker daemon). Fixture-comparison tests will still fail here — Task 6 fixes those.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/ibm/cldk/syntactic_analysis/L2CallGraph.java src/main/java/com/ibm/cldk/L3WalaOverlays.java src/test/java/com/ibm/cldk/schema/L2CallGraphGateTest.java
git commit -m "fix(id): stop matching ids by their language prefix"
```

---

### Task 3: The graph root carries its id and merges on it

**Files:**
- Modify: `src/main/java/com/ibm/cldk/neo4j/V2SchemaCatalog.java:110-115`
- Modify: `src/main/java/com/ibm/cldk/neo4j/V2GraphProjector.java:75-93`
- Modify: `src/main/java/com/ibm/cldk/neo4j/Schema.java` (the `j_application_name` constraint)
- Test: `src/test/java/com/ibm/cldk/neo4j/V2Neo4jSchemaConformanceTest.java`

**Interfaces:**
- Consumes: `CanId.applicationId` from Task 1.
- Produces: `:JApplication` keyed on `id`, carrying `id`, `name`, `schema_version`, `analyzer_name`, `analyzer_version`, `entrypoint_frameworks`, `entrypoint_report_json`.

- [ ] **Step 1: Write the failing test**

`V2Neo4jSchemaConformanceTest` currently hard-codes `"l4-sdg-test"` at each call site. Add the
constant first, so this task and Task 8 share one name:

```java
    private static final String APP_NAME = "l4-sdg-test";
```

then the test:

```java
@Test
void theApplicationRootIsAddressableByItsCanId() {
    NodeRow app = rows.nodes.stream()
            .filter(n -> n.labels.contains("JApplication"))
            .findFirst().orElseThrow();
    assertEquals("id", app.keyProp, "the root must merge on its id, not a display name");
    assertEquals(CanId.applicationId(APP_NAME), app.value);
    assertEquals(APP_NAME, app.props.get("name"), "name survives as a display property");
    assertTrue(app.labels.contains(RowBuilder.CAN_NODE),
            "the root must carry the index anchor, so the prefix-scoped delete can reach it");
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `JAVA_HOME=~/.sdkman/candidates/java/current ./gradlew test --tests 'com.ibm.cldk.neo4j.V2Neo4jSchemaConformanceTest'`
Expected: FAIL — `keyProp` is `name`, and `:JCanNode` is absent because the merge value is not a `can://` id.

- [ ] **Step 3: Declare and project the id**

`V2SchemaCatalog.java` — the merge key and the new property:

```java
        n.add(node("JApplication", "JApplication", "id",
                new P().put("id", "string").put("name", "string").put("schema_version", "string")
                        .put("analyzer_name", "string").put("analyzer_version", "string")
                        .put("entrypoint_frameworks", "string[]")
                        .put("entrypoint_report_json", "string").done()));
```

`V2GraphProjector.project` — merge on the id the payload already carries:

```java
        String appId = analysis.getApplication().getId();
        appProps.put("id", appId);
        appProps.put("name", appName);
        ...
        NodeRef app = b.node(Arrays.asList("JApplication"), "id", appId, prunedAppProps);
```

`RowBuilder.node` attaches `:JCanNode` automatically once the merge value starts with `can://`, so no change is needed there — that is the point of keying on the id.

`Schema.java` — the constraint follows the key:

```java
            "CREATE CONSTRAINT j_application_id IF NOT EXISTS FOR (a:JApplication) REQUIRE a.id IS UNIQUE",
```

- [ ] **Step 4: Run the conformance tests**

Run: `JAVA_HOME=~/.sdkman/candidates/java/current ./gradlew test --tests 'com.ibm.cldk.neo4j.*'`
Expected: the new test passes; `checkedInSchemaMatchesCatalog` fails until Task 7 regenerates the snapshot.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/ibm/cldk/neo4j/ src/test/java/com/ibm/cldk/neo4j/V2Neo4jSchemaConformanceTest.java
git commit -m "feat(neo4j): the application root carries its can:// id and merges on it"
```

---

### Task 4: Two apps sharing a name stop merging silently

**Files:**
- Test: `src/test/java/com/ibm/cldk/neo4j/V2Neo4jSchemaConformanceTest.java`

**Interfaces:**
- Consumes: Task 3's `:JApplication` keyed on `id`.
- Produces: nothing.

This is the defect that started the whole change, so it gets an explicit test rather than being assumed to fall out of Task 3.

- [ ] **Step 1: Write the test**

```java
@Test
void twoApplicationsProjectAsTwoDistinctRoots() {
    // The multi-service failure mode: before this change both merged onto one :JApplication
    // keyed on the free-text --app-name, with no diagnostic.
    GraphRows a = V2GraphProjector.project(
            V2Emitter.emit("svc-quotes", 1, Map.of(), "test"), "svc-quotes");
    GraphRows b = V2GraphProjector.project(
            V2Emitter.emit("svc-orders", 1, Map.of(), "test"), "svc-orders");

    String ida = a.nodes.stream().filter(n -> n.labels.contains("JApplication"))
            .findFirst().orElseThrow().value;
    String idb = b.nodes.stream().filter(n -> n.labels.contains("JApplication"))
            .findFirst().orElseThrow().value;
    assertNotEquals(ida, idb, "two services must not share a root node");
    assertEquals("can://svc-quotes", ida);
    assertEquals("can://svc-orders", idb);
}
```

- [ ] **Step 2: Run it**

Run: `JAVA_HOME=~/.sdkman/candidates/java/current ./gradlew test --tests 'com.ibm.cldk.neo4j.V2Neo4jSchemaConformanceTest'`
Expected: PASS (Task 3 already delivers this; the test pins it against regression).

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/ibm/cldk/neo4j/V2Neo4jSchemaConformanceTest.java
git commit -m "test(neo4j): pin that two apps project as two roots"
```

---

### Task 5: The conformance schema accepts only the new shape

**Files:**
- Modify: `src/test/resources/schema/analysis.v2.schema.json`

**Interfaces:**
- Consumes: the grammar from Task 1.
- Produces: `canId`, `externalCanId`, `artifactCanId` patterns matching only the new shape.

The old patterns are anchored on the language (`^can://java/`), so they would silently accept nothing after Task 1 — every payload would fail conformance with an unhelpful message. Rewriting them is what makes Task 6's fixture work checkable.

- [ ] **Step 1: Rewrite the three patterns**

```json
    "canId": {
      "type": "string",
      "pattern": "^can://[^/]+/java/",
      "description": "Durable id for nodes at or above callable depth: can://<app>/java/..."
    },

    "externalCanId": {
      "type": "string",
      "pattern": "^can://[^/]+/java/@external/",
      "description": "Id of a symbol outside the project, so an in-project id landing in the external map fails the gate instead of validating happily."
    },

    "artifactCanId": {
      "type": "string",
      "pattern": "^can://[^/]+/artifact/",
      "description": "Id of a repository-artifact node: nested under the app, language-neutral."
    }
```

Note the application root's own id (`can://<app>`, no trailing segment) does **not** match `canId`. Check whether `application.id` references `canId` in this file; if it does, give the root its own `applicationCanId` definition with pattern `^can://[^/]+$` rather than loosening `canId`.

- [ ] **Step 2: Validate the file parses**

Run: `python3 -c "import json; json.load(open('src/test/resources/schema/analysis.v2.schema.json')); print('ok')"`
Expected: `ok`

- [ ] **Step 3: Commit**

```bash
git add src/test/resources/schema/analysis.v2.schema.json
git commit -m "test(schema): conformance patterns admit only can://<app>/java/..."
```

---

### Task 6: Migrate the fixtures

**Files:**
- Modify: every test under `src/test/java` asserting a literal `can://` string (92 references)

**Interfaces:**
- Consumes: everything above.
- Produces: a green suite.

- [ ] **Step 1: Find every literal**

Run: `git grep -n 'can://' -- src/test/java`

- [ ] **Step 2: Rewrite each, preferring composition over literals**

Where a test hard-codes `"can://java/app/src/Foo.java"`, prefer building it so the next grammar change costs nothing:

```java
String app = CanId.applicationId("app");
String module = CanId.moduleId(app, "src/Foo.java");
```

Keep a literal only where the test's *point* is the exact string — `CanIdTest` from Task 1 is the one place that should assert literals.

- [ ] **Step 3: Run the full suite**

Run: `JAVA_HOME=~/.sdkman/candidates/java/current ./gradlew test`
Expected: only `CodeAnalyzerIntegrationTest` fails (Docker).

- [ ] **Step 4: Commit**

```bash
git add src/test/java
git commit -m "test: migrate fixtures to can://<app>/java/..."
```

---

### Task 7: Payload version, snapshot, README

**Files:**
- Modify: `src/main/java/com/ibm/cldk/schema/V2Emitter.java:106`
- Modify: `src/main/java/com/ibm/cldk/syntactic_analysis/L1Cache.java:86`
- Modify: `schema.neo4j.json` (regenerated)
- Modify: `README.md` (§4 Neo4j graph output)

**Interfaces:**
- Consumes: everything above.
- Produces: a detectable version signal.

**Decision required before starting this task.** The id grammar has changed incompatibly, and `analysis.json`'s `schema_version` is `"2.0.0"`. Leaving it there makes the break undetectable; moving it to `"3.0.0"` re-creates the unilateral drift that `V2SchemaCatalog`'s comment and #238 explicitly warn against, because the sibling analyzers stay at `2.0.0`.

The plan recommends **moving the payload `schema_version` to `3.0.0`** and leaving the *graph* `SCHEMA_VERSION` at `2.0.0`, because:
- the payload version is what a reader gates on, and it is now genuinely a different contract;
- the graph constant is the one #238 said not to touch, and #50 owns its re-baseline;
- the divergence is recorded in a committed spec this time, which is what makes it different from the drift that was undone.

Confirm this with the maintainer before implementing — it is the one decision in this plan that is not mechanical.

- [ ] **Step 1: Bump the payload version**

```java
        analysis.setSchemaVersion("3.0.0");
```

and the cache envelope, so a cache written by 3.1.0 is rejected rather than deserialised into old-shape ids:

```java
        envelope.setSchemaVersion("3.0.0");
```

`L1Cache` already discards on analyzer-version change (its javadoc: *"the whole file is discarded when the analyzer version or the application name changes: both are baked into every `can://` id"*), so 3.1.0 → 3.1.1 covers the migration. This bump is belt-and-braces for anyone who pins the version but not the cache.

- [ ] **Step 2: Regenerate the graph snapshot**

```bash
JAVA_HOME=~/.sdkman/candidates/java/current ./gradlew installDist -q
./build/install/codeanalyzer-java/bin/codeanalyzer-java --emit schema > schema.neo4j.json
```

- [ ] **Step 3: Update README §4**

Add, after the paragraph describing `J`-prefixing:

```markdown
Every node id is `can://<app>/java/…`, so `can://<app>` is a prefix of every node the
application emits — which is what the destructive statements scope on. `:JApplication` is keyed on
that id, not on `--app-name`, so two applications analyzed under the same name stay two nodes.
```

- [ ] **Step 4: Run the full suite plus a real payload check**

```bash
JAVA_HOME=~/.sdkman/candidates/java/current ./gradlew test
./build/install/codeanalyzer-java/bin/codeanalyzer-java \
  -i src/test/resources/test-applications/daytrader8 -a 1 --no-build -o /tmp/v/a1
python3 -c "
import json; d=json.load(open('/tmp/v/a1/analysis.json'))
app=d['application']; print('root id:', app['id'])
assert app['id']=='can://daytrader8', app['id']
ids=[m['id'] for m in app['symbol_table'].values()]
assert all(i.startswith('can://daytrader8/java/') for i in ids), ids[:2]
print('ok, all module ids under the app prefix')"
```

Expected: suite green apart from the Docker test; the script prints `ok`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/ibm/cldk/schema/V2Emitter.java src/main/java/com/ibm/cldk/syntactic_analysis/L1Cache.java schema.neo4j.json README.md
git commit -m "feat(schema): payload schema_version 3.0.0 for the new id grammar"
```

---

### Task 8: Verify the properties the spec promises

**Files:**
- Test: `src/test/java/com/ibm/cldk/neo4j/V2Neo4jSchemaConformanceTest.java`

**Interfaces:**
- Consumes: everything above.
- Produces: nothing.

The spec's §7 makes three claims that no earlier task checks end to end. Assert them, because they are the reason for the change.

- [ ] **Step 1: Write the tests**

```java
@Test
void everyProjectedIdSitsUnderTheApplicationPrefix() {
    String prefix = CanId.applicationId(APP_NAME) + "/";
    String root = CanId.applicationId(APP_NAME);
    for (NodeRow n : rows.nodes) {
        if (!RowBuilder.isCanId(n.value)) {
            continue; // JPackage/JAnnotation are name-keyed by design
        }
        assertTrue(n.value.equals(root) || n.value.startsWith(prefix),
                n.value + " escapes the application prefix, so a scoped delete would miss it");
    }
}

@Test
void noIdCarriesTheOldLanguageFirstShape() {
    for (NodeRow n : rows.nodes) {
        assertFalse(n.value.startsWith("can://java/"),
                "old-shape id survived: " + n.value);
        assertFalse(n.value.startsWith("can://artifact/"),
                "old-shape artifact id survived: " + n.value);
    }
}
```

- [ ] **Step 2: Run them**

Run: `JAVA_HOME=~/.sdkman/candidates/java/current ./gradlew test --tests 'com.ibm.cldk.neo4j.V2Neo4jSchemaConformanceTest'`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/ibm/cldk/neo4j/V2Neo4jSchemaConformanceTest.java
git commit -m "test(neo4j): pin the app-prefix and no-old-shape invariants"
```

---

## Migration note for the operator

Not a task, but it must reach the release notes. Old and new ids do not collide, so **re-pushing an existing database produces a second, disconnected copy rather than an update** — and the prefix-scoped delete cannot remove the old copy, because it scopes on the new prefix. Wipe the database, or delete the old nodes explicitly, before the first 3.1.1 push:

```cypher
MATCH (x:JCanNode) WHERE x.id STARTS WITH 'can://java/' DETACH DELETE x;
MATCH (x:JCanNode) WHERE x.id STARTS WITH 'can://artifact/' DETACH DELETE x;
MATCH (a:JApplication) WHERE a.id IS NULL DETACH DELETE a;
```

The third statement is the one that is easy to miss: pre-3.1.1 application roots have no `id` at all, so neither prefix match reaches them.
