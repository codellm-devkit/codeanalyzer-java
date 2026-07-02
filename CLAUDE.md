# codeanalyzer-java

The CLDK Java analyzer. Parses an enterprise Java project with
[JavaParser](https://javaparser.org/) (symbol table) and [WALA](https://github.com/wala/WALA)
(call graph / system dependency graph) and emits the **canonical CLDK `analysis.json`** — a
symbol table plus a dependency graph — so the [CLDK Python SDK](../python-sdk) can consume it
via `CLDK(language="java").analysis(...)`. It can alternatively project the same IR into a
**Neo4j property graph** (`--emit neo4j`).

It is the Java sibling of `codeanalyzer-python` and `codeanalyzer-typescript`.

## Requirements

- Java 11+ to run the jar; Java 17+ (Semeru or similar) to build. GraalVM 21+ only for
  `nativeCompile`. Install via [SDKMan!](https://sdkman.io).
- Gradle via the checked-in wrapper (`./gradlew`) — never a system Gradle.

## Build / test / run

```bash
./gradlew fatJar          # → build/libs/codeanalyzer-<version>.jar (the deliverable)
./gradlew test            # JUnit 5; Testcontainers suites need RUN_CONTAINER_TESTS=1 + Docker/Podman
./gradlew spotlessApply   # formatting (runs automatically before compileJava)
./gradlew nativeCompile -PbinDir=$HOME/.local/bin   # optional GraalVM native binary

java -jar build/libs/codeanalyzer-*.jar -i <project> -a 2 -o <outdir>
```

Version lives in `gradle.properties` (bump with `./gradlew bumpVersion -PbumpType=patch|minor|major`).
Releases are tag-triggered via GitHub Actions (`.github/workflows/`); a lockstep job releases the
thin PyPI distribution (`packaging/pypi/`).

## CLI

```
codeanalyzer -i <project> [options]

  -i, --input <path>           project root to analyze
  -s, --source-analysis <str>  analyze a single string of Java source instead of a project
  -o, --output <dir>           write <dir>/analysis.json (omit ⇒ JSON to stdout)
  -a, --analysis-level <1|2|3> 1 = symbol table (default); 2 = + RTA call graph;
                               3 = + full system dependency graph (WALA slicer)
      --graphs <cfg,pdg,sdg>   program_graphs sections to emit at -a 3 (default all)
      --sdg-data-deps <d>      no-heap (default) | full — slicer data-dependence depth at -a 3
  -b, --build-cmd <cmd>        custom build command (default: auto-detect mvn/gradle)
      --no-build               skip building the target app (use if already built)
  -t, --target-files <f>...    restrict analysis to specific files (incremental)
      --emit <json|neo4j|schema>  output target (default json)
      --app-name / --neo4j-*   Neo4j anchor name and Bolt connection (see README §5)
  -v, --verbose                logs to console
```

stdout is a clean JSON channel when `-o` is omitted; diagnostics go through `utils/Log`.

## Architecture (`src/main/java/com/ibm/cldk/`)

- `CodeAnalyzer.java` — picocli entrypoint; orchestrates symbol table → graph → emitter.
- `SymbolTable.java` — JavaParser + symbol solver; builds `Map<path, JavaCompilationUnit>`.
- `SystemDependencyGraph.java` — WALA-based graph construction: `ScopeUtils` builds the
  analysis scope (ECJ/CAst source-level front end), `AnalysisUtils.getEntryPoints` seeds
  entrypoints, then an RTA call-graph build; edges are serialized from a JGraphT graph.
- `entities/` — the Lombok data model that **is** the `analysis.json` schema
  (`JavaCompilationUnit`, `Type`, `Callable`, `CallSite`, `CallEdge`, `SystemDepEdge`, …).
  Schema changes here must stay in lockstep with the Python SDK's `cldk.models.java` models.
- `javaee/` — Jakarta/Java-EE entrypoint detection helpers.
- `neo4j/` — the property-graph projection: `GraphProjector` (IR → rows), `CypherWriter`
  (snapshot), `BoltWriter` (live incremental push; loaded reflectively via `BoltSink` so the
  native image prunes the driver), `SchemaCatalog` (`schema.neo4j.json` contract).
- `utils/` — scope/build helpers (`BuildProject` auto-builds the target app), logging.

## Output contract

```jsonc
{
  "symbol_table":     { "<abs/or/rel path .java>": JavaCompilationUnit, ... },
  "call_graph":       [ { "source": {...}, "target": {...}, "type": "CALL_DEP", "weight": ... } ],  // -a 2+
  "system_dependency_graph": [ ... ],  // -a 3: method-level CONTROL_DEP/DATA_DEP edges (JGraphEdges shape)
  "program_graphs":   { "schema_version": ..., "functions": { "<fqsig>": { "cfg": ..., "pdg": ... } },
                        "sdg_edges": [ ... ] },  // -a 3: statement-level, keyed by (signature, node_id)
  "version": "<analyzer version>"
}
```

- Callable signatures are Java method signatures (`<init>` for constructors); call-graph edge
  endpoints must always resolve to a real symbol-table `Callable` — no dangling edges. Callables
  discovered only by WALA (e.g. compiler-generated) are back-filled into the symbol table via
  `createAndPutNewCallableInSymbolTable`.
- Neo4j labels are `J`-prefixed (`:JType`, `J_CALLS`, `J_DATA_DEP`) so Java/Python/TS graphs
  can share a database. The contract is `schema.neo4j.json`; `Neo4jSchemaConformanceTest` keeps
  the projector and the contract in sync — regenerate it when the model changes.
  `--emit neo4j` defaults to the full SDG analysis (`-a 3`); an explicit `-a` dials down.

## Analysis levels

- **Level 1** — symbol table only (JavaParser; no WALA, no build of the target app needed
  beyond dependency resolution).
- **Level 2** — plus the WALA graph: entrypoint-seeded RTA call graph over application
  classes, and cyclomatic complexity stamped onto symbol-table callables.
- **Level 3** — plus the full system dependency graph from WALA's slicer: method-level
  `system_dependency_graph` edges and statement-level `program_graphs` (CFG + PDG per
  callable, cross-function CALL/PARAM_IN/PARAM_OUT edges). Data dependence defaults to
  no-heap; `--sdg-data-deps=full` widens it. Schema decisions: `.claude/SCHEMA_DECISIONS.md`.
  Levels 1/2 output and timings must never be affected by level-3 code.

## Tests

- Unit/integration tests in `src/test/java`; fixture apps in
  `src/test/resources/test-applications/` (daytrader8 is the big end-to-end fixture; the
  small ones each pin a regression — build-tool quirks, records, init blocks, generics
  signature collisions, …).
- Container-backed tests (`Neo4jBoltWriterTest`) are opt-in: `RUN_CONTAINER_TESTS=1 ./gradlew test`.

## Conventions

- Work is issue-driven: GitHub issue → branch named `minor/issue-<N>-<slug>` (or
  `major|patch/` by semver impact) → PR to `main`.
- Spotless formatting is enforced at compile time; Lombok for entities; logs via `utils/Log`,
  never `System.out` (stdout is the JSON channel).
- The `dist/`, `node_modules/`, `.astro/` dirs at the repo root are packaging/website
  artifacts — not part of the analyzer build.
