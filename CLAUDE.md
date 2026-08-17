# CLAUDE.md

Agent guidance for `codellm-devkit/codeanalyzer-java` (`codeanalyzer`).

Respect the global `~/.claude/CLAUDE.md` instructions strictly.

## What this project is

`codeanalyzer-java` is the CLDK Java static analyzer — a native
[WALA](https://github.com/wala/WALA) implementation for enterprise Java. It extracts a
comprehensive **symbol table** and **call graph** and emits them as the canonical CLDK
`analysis.json`, or projects that same IR into a **Neo4j** property graph (`--emit neo4j`).
It mirrors its [Python](https://github.com/codellm-devkit/codeanalyzer-python) (`canpy`)
and [TypeScript](https://github.com/codellm-devkit/codeanalyzer-typescript) (`cants`)
sibling analyzers, so output-shape parity with them is a first-class concern.

The two halves come from different tools: the **symbol table is syntactic** — built with
[JavaParser](https://javaparser.org/) + its symbol solver over the Eclipse JDT/ECJ
frontend; the **call graph is semantic** — built by WALA using **RTA** (Rapid Type
Analysis) over a class hierarchy, gated behind `-a 2`. Entrypoint discovery is
framework-aware (Spring, JAX-RS, Jakarta, Struts, Camel).

## Architecture — follow the pipeline

The whole analyzer is one orchestrator: `analyze()` inside
`src/main/java/com/ibm/cldk/CodeAnalyzer.java` (a picocli `@Command`). Read it first;
everything else is a stage it calls, in order:

1. **schema short-circuit** — `--emit schema` → `Neo4jEmitter.emitSchema()` and return
   (no project analysis).
2. **dependency resolution** — `BuildProject.downloadLibraryDependencies()` fetches libs
   so JavaParser can resolve types.
3. **symbol table** (syntactic) — `SymbolTable.extractAll()` (or `extract()` for targeted
   `-t` runs, `extractSingle()` for `-s` inline source) → `Map<String, JavaCompilationUnit>`.
4. **call graph** (semantic, `-a 2` only) — `SystemDependencyGraph.construct()` builds the
   WALA scope + class hierarchy, discovers entrypoints (`AnalysisUtils.getEntryPoints`),
   runs the RTA builder, sets cyclomatic complexity back onto callables, and returns
   `List<Dependency>` edges (application-class targets only).
5. **output** — `--emit neo4j` → `Neo4jEmitter.emit()`; otherwise Gson serializes
   `{symbol_table, call_graph, version}` to `analysis.json` (or stdout).

The `analysis.json` shape is the **POJOs in `com.ibm.cldk.entities`** (`JavaCompilationUnit`
is the top symbol-table type; there is no single `JApplication` JSON type — `:JApplication`
is a Neo4j node). The Neo4j schema is a *separate*, versioned contract in
`neo4j/SchemaCatalog.java` — treat it as a contract enforced by conformance tests.

## Directory map

All packages under `src/main/java/com/ibm/cldk`.

| Path | Responsibility |
|------|----------------|
| `CodeAnalyzer.java` | Entry point + picocli CLI + `analyze()` orchestrator — the spine |
| `SymbolTable.java` | Symbol table build (JavaParser/JDT, syntactic) |
| `SystemDependencyGraph.java` | Call graph / SDG build (WALA RTA, semantic); defines `Dependency`/`CallDependency` |
| `entities/` | Output-contract POJOs (Lombok `@Data`): `JavaCompilationUnit`, `Type`, `Callable`, `CallSite`, graph `CallableVertex`/`CallEdge`, … |
| `javaee/` | Framework-aware entrypoint finders (`spring/`, `jax/`, `jakarta/`, `struts/`, `camel/`) + CRUD finders |
| `neo4j/` | Graph projection: `Neo4jEmitter` (facade), `GraphProjector` (IR→rows), `CypherWriter` (snapshot), `BoltWriter` (incremental), `Schema`/`SchemaCatalog` (contract + `SCHEMA_VERSION`) |
| `utils/` | `BuildProject`, `ScopeUtils` (WALA scope), `AnalysisUtils` (entrypoints, app-class filter, complexity), `Log` |
| `src/test/java/com/ibm/cldk` | JUnit 5 tests; fixtures in `src/test/resources/test-applications` (daytrader8, plantsbywebsphere, …) |

## Commands

Build system is **Gradle** (`./gradlew`); Java **11+** (Semeru 17 recommended, GraalVM for
native). No Makefile.

- `./gradlew fatJar` — build `build/libs/codeanalyzer-<version>.jar` (Main-Class
  `com.ibm.cldk.CodeAnalyzer`).
- `java -jar build/libs/codeanalyzer-<version>.jar -i <project> -a 2 -o <outdir>` — run
  the analyzer (`-a 1` = symbol table only, `-a 2` adds the call graph). Also
  `./gradlew run -Pargs=...`.
- `./gradlew test` — run tests. Neo4j Bolt / Testcontainers tests are opt-in:
  `RUN_CONTAINER_TESTS=1 ./gradlew test`.
- `./gradlew nativeCompile -PbinDir=$HOME/.local/bin` — GraalVM native `codeanalyzer` binary.
- `./gradlew spotlessApply` — apply formatting (also runs automatically on `compileJava`).
- `java -jar … --emit schema > schema.neo4j.json` — regenerate the Neo4j schema contract.
- `./gradlew bumpVersion -PbumpType=patch|minor|major` — version bump.

## I implement features myself — you assist

For feature work, **I write the implementation** to stay fluent in my own analyzer.
Act as a helper, not the author:

- **Don't write the feature code** or apply edits to implement it unless I explicitly
  ask ("write this", "implement X", "apply it"). Default to guiding, not doing.
- **Do** move me fast: explain the relevant stage, point at prior art (e.g. an existing
  entrypoint finder in `javaee/` as the template for a new framework), sketch
  signatures/types, outline an approach, and answer questions about the codebase.
- **Review on request:** when I share a diff or push, critique it — correctness,
  **parity with the Python/TypeScript backends**, schema conformance, missing tests, edge
  cases — and suggest concrete improvements.
- Scaffolding like tests or boilerplate is fine **when I ask**; otherwise leave the
  keyboard to me.
- If you think I'm about to go wrong, say so briefly and let me decide — don't pre-empt
  by implementing the fix.

## Rules

1. **Think before coding.** State assumptions explicitly; ask rather than guess. Push
   back when a simpler approach exists. Stop when confused.
2. **Simplicity first.** Guide me toward the minimum idiomatic code that solves the
   problem. Nothing speculative; no abstractions for single-use code.
3. **Issue → branch → work → PR.** Every change starts as an issue, on a branch named
   `feat/issue-XXX`, `fix/issue-XXX`, `chore/issue-XXX`, and lands via a PR.
4. **Guard the contract.** Changes to `com.ibm.cldk.entities` or Neo4j output must keep
   parity with the sibling analyzers and pass `Neo4jSchemaConformanceTest` (which also
   asserts the checked-in `schema.neo4j.json` is current).

## Goal-driven execution, as a teaching loop

Success is measured by the sole fact that **I understand it**. The success criterion:
I can point to the exact line of code where any feature lives, however remote or
obscure, and explain why it's there and how it behaves.

To that end, be my teacher and a Socratic one — not an answer key:

- Lead with questions that make me derive the answer; don't hand me the solution.
- Verify understanding, not just behavior — have me locate and explain the relevant
  LOC, walk edge cases, and predict what a change would do before running it.
- Teach, help improve, and strengthen the weak spots you surface; circle back to them.
- The loop closes when I can **teach it back** and place every feature on a line, not
  merely when the tests pass.
- Over the session, frequently — but not so much that I am stymied — ask spaced
  repetition questions so concepts are internalized.

Learning progress is tracked globally, not per-repo: see the SRS deck and the
"continual learning" defaults in `~/.claude/CLAUDE.md`.

# Auxiliary support tasks

## Tidy up the release announcement

Every `vX.Y.Z` tag makes the release workflow (`.github/workflows/release.yml`) auto-post an
announcement to the **Announcements** discussion (this repo, and the org mirror in
`codellm-devkit/.github`). That body is machine-generated from PR titles — it mis-categorizes
changes, includes `chore(release)` noise, and buries breaking changes.

When you're working in this repo, check whether the latest release's announcement still needs
cleanup, and if so, fix it:

1. **Find it.** `gh release view --json tagName,publishedAt`; then list recent discussions via
   `gh api graphql` (repository → discussions), match category `Announcements` whose title carries
   this release's version (the auto-post titles it `vX.Y.Z`; a cleaned-up one is
   `📣 New Release: codeanalyzer-java X.Y.Z`). Keep the discussion node `id` and read its `body`.
2. **Skip if already done.** If the body starts with `<!-- cleaned-up -->` (or already reads as a
   clear, human-written announcement), do nothing.
3. **Otherwise rewrite it** into a clear, user-facing announcement, grounded in `CHANGELOG.md` and
   the referenced PRs/diff (not the auto-grouping — verify each change; never invent anything):
   - **breaking changes first**, each with a one-line migration step;
   - plain-language highlights (what it does, not the PR title);
   - upgrade lines — `pip install -U "codeanalyzer-java==X.Y.Z"`, or the shell installer
     one-liner (`curl … codeanalyzer-installer.sh | sh`);
   - links to the GitHub release and `CHANGELOG.md`.
4. **Update in place.** Edit the discussion with the GraphQL `updateDiscussion` mutation (don't
   open a new one): set the title to `📣 New Release: codeanalyzer-java X.Y.Z`, prepend
   `<!-- cleaned-up -->` to the body, and mirror the same title and body to the org discussion.
   This task only reads code and edits Discussions — it makes no commits.
