![logo](./docs/assets/logo.png)

Native WALA implementation of source code analysis tool for Enterprise Java Applications.

`codeanalyzer` extracts a comprehensive **symbol table** and **call graph** from Java applications
and emits them either as the canonical `analysis.json`, or as a **Neo4j property graph**
(`--emit neo4j`) — a `graph.cypher` snapshot or a live, incremental push over Bolt. See
[§4. Neo4j graph output](#4-neo4j-graph-output).

## Quick install

From PyPI, with a bundled JVM. Installs a `canjv` launcher:

```sh
pip install codeanalyzer-java
canjv -i /path/to/project -a 1 -o ./out
```

> [!IMPORTANT]
> **The bundled JVM is a JRE, and it is only enough for `-a 1`.** `pip install` pulls
> [`jdk4py`](https://pypi.org/project/jdk4py/), whose runtime has `java` but **no `javac`**.
>
> **`-a 2` and above need a real JDK on `JAVA_HOME`.** From level 2 the analyzer shells out to
> your project's Maven or Gradle wrapper to resolve dependencies and compile the application —
> WALA reads the *compiled classes*, not the source — so a JRE cannot get you a call graph:
>
> ```sh
> export JAVA_HOME=/path/to/a/real/jdk   # Java 11+, must contain bin/javac
> canjv -i /path/to/project -a 2 -o ./out
> ```
>
> **Without it the analyzer still exits 0.** It degrades and says so on stderr, but a caller
> reading only the exit code sees success:
>
> ```
> [WARN]  RTA call graph unavailable (RuntimeException: No application classes found.); emitting declared edges only
> [WARN]  L4 semantic ddg unavailable (WALA build failed); emitting the derived SDG vertices and param edges only
> ```
>
> You get declared call edges and the syntactic DDG; you do not get the RTA overlay or the
> alias-aware `prov: ["points-to"]` edges. Check for those warnings, not just the exit status.
>
> `--emit neo4j` always projects at full depth regardless of `-a`, so it needs a JDK too.

Or grab the latest release jar and a `codeanalyzer` launcher (requires a Java 11+ runtime):

```sh
curl --proto '=https' --tlsv1.2 -LsSf https://github.com/codellm-devkit/codeanalyzer-java/releases/latest/download/codeanalyzer-installer.sh | sh
# or with wget:
wget -qO- https://github.com/codellm-devkit/codeanalyzer-java/releases/latest/download/codeanalyzer-installer.sh | sh
```

Overrides: `CODEANALYZER_INSTALL_DIR` (default `~/.local/bin`), `CODEANALYZER_VERSION` (default `latest`).
Prefer to build from source? See [§2. Building `codeanalyzer`](#2-building-codeanalyzer).

## 1. Prerequisites

Before you begin, ensure you have met the following requirements:

* You have a Linux/MacOS/WSL machine.
* You have installed the latest version of [SDKMan!](sdkman.io/)

### 1.1. Install SDKMan!
1. Install SDKMan!
   Open your terminal and enter the following command:

   ```bash
   curl -s "https://get.sdkman.io" | bash
   ```

   Follow the on-screen instructions to complete the installation.

2. Open a new terminal or source the SDKMan! scripts:

   ```bash
   source "$HOME/.sdkman/bin/sdkman-init.sh"
   ```

## 2. Building `codeanalyzer`

### 2.1. Install Java 11 or above

1. You can list all available GraalVM versions with:

   ```bash
   sdk list java | grep sem
   ```
   You should see the following:
   ```
    Semeru     |     | 21.0.2       | sem     |            | 21.0.2-sem
               |     | 21.0.1       | sem     |            | 21.0.1-sem
               |     | 17.0.10      | sem     |            | 17.0.10-sem
               |     | 17.0.9       | sem     |            | 17.0.9-sem
               |     | 11.0.22      | sem     | installed  | 11.0.22-sem
               |     | 11.0.21      | sem     |            | 11.0.21-sem
   ```

2. Install Java 11 or above (we'll go with 17.0.10-sem):

   ```bash
   sdk install java 17.0.10-sem
   ```

3. Set Java 17 as the current Java version:

   ```bash
   sdk use java 17.0.10-sem
   ```

### 2.2. Build `codeanalyzer`

Clone the repository (if you haven't already) and navigate into the cloned directory.

Run the Gradle wrapper script to build the project. This will compile the project using GraalVM native image.

```bash
./gradlew fatJar
```

### 2.3. Using `codeanalyzer`

The jar will be built at `build/libs/codeanalyzer-<version>.jar`. It may be used as follows:

```help
Usage: codeanalyzer [-hvV] [--no-build] [--no-clean-dependencies]
                    [-a=<analysisLevel>] [-b=<build>] [-f=<projectRootPom>]
                    [-i=<input>] [-o=<output>] [-s=<sourceAnalysis>]
                    [--emit=<emit>] [--app-name=<appName>]
                    [--neo4j-uri=<uri>] [--neo4j-user=<user>]
                    [--neo4j-password=<password>] [--neo4j-database=<db>]
                    [-t=<targetFiles>]...
Analyze java application.
  -i, --input=<input>        Path to the project root directory.
  -s, --source-analysis=<s>  Analyze a single string of java source code instead
                               of the project.
  -o, --output=<output>      Destination directory to save the output graphs. By
                               default, the analysis JSON is printed to the console.
  -b, --build-cmd=<build>    Custom build command. Defaults to auto build.
      --no-build             Do not build your application (use if already built).
  -a, --analysis-level=<n>   Level of analysis: 1 (symbol table) or 2 (call graph).
                               Default: 1. Level 2 adds J_CALLS edges to the graph.
  -t, --target-files=<f>...  Restrict analysis to specific files (incremental).
      --emit=<emit>          Output target: json (analysis.json, default) |
                               neo4j (graph.cypher or live Bolt push) |
                               schema (the Neo4j schema.neo4j.json contract).
      --app-name=<name>      Logical application name for the graph :JApplication
                               anchor (default: input dir name).
      --neo4j-uri=<uri>      Push the graph to a live Neo4j over Bolt (incremental);
                               omit to write graph.cypher. Falls back to the
                               NEO4J_URI environment variable.
      --neo4j-user=<user>    Neo4j username (env: NEO4J_USERNAME, default: neo4j).
      --neo4j-password=<pw>  Neo4j password (env: NEO4J_PASSWORD, default: neo4j).
      --neo4j-database=<db>  Neo4j database name (env: NEO4J_DATABASE, default:
                               server default).
  -v, --verbose              Print logs to console.
  -h, --help                 Show this help message and exit.
  -V, --version              Print version information and exit.
```


## 3. Installing `codeanalyzer` as a native binary (once built, no JVM will be required for running `codeanalyzer`)

To install `codeanalyzer`, follow these steps:

### 3.1. Install GraalVM using SDKMan

1. You can list all available GraalVM versions with:

   ```bash
   sdk list java | grep graal
   ```

2. Install GraalVM 17 or above (we'll go with 21.0.2-graalce):

   ```bash
   sdk install java 21.0.2-graalce
   ```

3. Set GraalVM 21 as the current Java version:

   ```bash
   sdk use java 21.0.2-graalce
   ```

### 3.2. Build the Project

Clone the repository (if you haven't already) and navigate into the cloned directory.

Run the Gradle wrapper script to build the project. This will compile the project using GraalVM native image.

```bash
./gradlew nativeCompile -PbinDir=$HOME/.local/bin
```

**Note: `-PbinDir` is optional. If not provided, this command places the binaries in  `build/bin`.**

### 3.3. Using `codeanalyzer`

Assuming the path you provided in `-PbinDir` (in my case `$HOME/.local/bin`) is in your `$PATH`, after installation, you can use `codeanalyzer` by following the below format:

   ```help
   Usage: codeanalyzer [-hqV] [-d=<appDeps>] [-e=<extraLibs>] -i=<input>
                       -o=<outDir>
   Convert java binary (*.jar, *.ear, *.war) to a neo4j graph.
     -d, --app-deps=<appDeps>   Path to the application dependencies.
     -e, --extra-libs=<extraLibs>
                                Path to the extra libraries.
     -h, --help                 Show this help message and exit.
     -i, --input=<input>        Path to the input jar(s).
     -o, --output=<outDir>      Destination directory to save the output graphs.
     -q, --quiet                Don't print logs to console.
     -V, --version              Print version information and exit.
   ```

There is a sample application in `src/test/resources/sample_apps/daytrader8/binaries/`. You can use this to test the tool.

   ```sh
   codeanalyzer  -i src/test/resources/sample_apps/daytrader8/binaries/ 
   ```

This will produce print the SDG on the console. Explore other flags to save the output to a JSON.

## 4. Neo4j graph output

`codeanalyzer` can project the analysis IR into a [Neo4j](https://neo4j.com/) property graph instead
of `analysis.json`. The graph is a **lossless** projection of the IR: compilation units, types,
callables, fields, parameters, call sites, variables, enum constants, record components,
initialization blocks, CRUD operations/queries, comments, annotations and packages are all
first-class nodes and relationships, and (at `-a 2`) it adds `J_CALLS` edges from the call graph.
Every field of the Lombok entity model is represented (scalars as node properties — maps such as a
field's per-variable initializers are kept as a `*_json` property since Neo4j has no map type;
comments are `:JComment` nodes in addition to the convenience `docstring` property).

The full contract (node labels, their keys and typed properties, relationship types and endpoints,
plus the constraint/index DDL) lives in [`schema.neo4j.json`](./schema.neo4j.json) and is visualized
in [`neo4j-schema.drawio`](./neo4j-schema.drawio). Java-specific node labels are `J`-prefixed and
relationship types `J_`-prefixed (e.g. `:JType`, `:JCallable`, `J_CALLS`) so a Java graph can share a
Neo4j database with another language's backend without colliding. The exceptions are deliberate:
`:Artifact`, `:Package` and `:ConfigKey` and their containment edges carry **no** prefix, because a
build manifest or a configuration key is not a Java concept — a sibling-language analyzer scanning
the same repository lands on the same nodes instead of a per-language duplicate.
`SCHEMA_VERSION` is stamped onto the `:JApplication` node of every emitted graph.

Every node id starts with `can://<app>/` — `can://<app>/java/…` for code, `can://<app>/artifact/…`
for build manifests and config keys, `can://<app>/@external/…` for library symbols — so
`can://<app>` is a prefix of every node the application emits, which is what the destructive
statements scope on. (`:Package` is the exception: it is keyed on a `pkg:` purl, sits under no
application, and no wipe reaches it.) `:JApplication` is keyed on that id, not on the free-text
`--app-name`, so the root is addressable by the same id its
descendants are prefixed with. The id is derived from `--app-name` (`can://<app-name>`), so it does
**not** disambiguate two services analyzed under the same name — those still merge onto one root.
Give each service its own `--app-name` if they share a database.

**Polyglot ids.** The `/java/` segment is what makes a code node this analyzer's. Three id families
deliberately omit it and are therefore **language-neutral, shared merge targets**: `:Artifact`,
`:ConfigKey` and `@external` symbols. A sibling analyzer over the same `<app>` mints byte-identical
ids for them, so a `pom.xml`, a config key or `java.util.Map#get` is *one* node in a merged graph
rather than a per-language duplicate. That sharing is the point, and its cost is accepted: two
analyzers' notions of a library symbol are not necessarily the same thing, and merging them says
they are. The consequence for writes: because these shared nodes sit inside `can://<app>/`, a Cypher
snapshot's prefix wipe rebuilds them, so a cross-language edge into a shared `:Artifact` is dropped
by one analyzer's snapshot and restored on the other's next push.

**Configuration reads.** `DEFINES_CONFIG` says which artifact declares a key; `J_USES_CONFIG` says
which code reads one. Its source is whichever node the read was attributed to — a `:JBodyNode` for
a call site (`System.getenv("X")`, `env.getProperty("X")`), or the `:JField` / `:JCallable` /
`:JType` carrying a `@Value("${x}")` or `@ConfigurationProperties` annotation — so a consumer must
not assume it is always a body node. A read that matched no declared key is kept as
`J_READS_CONFIG_UNRESOLVED` rather than dropped, so a read nobody can trace stays as visible as one
that resolves. The literal tier runs at every level; `-a 3` and `-a 4` widen it over the dataflow
graph (`prov: ["dataflow"]`).

**Entrypoint coverage.** `:JApplication` carries `entrypoint_frameworks` and
`entrypoint_report_json`, and every entrypoint node carries `entrypoint_frameworks` naming the
framework finders that recognised it. The report is present **even when empty**: the detection pass
under-approximates by design, so an empty `frameworks_detected` next to a populated `rulesets` is
what distinguishes "this application has no entrypoints" from "the pass found nothing".

### 4.1. Cypher snapshot (no database required)

```sh
codeanalyzer -i /path/to/project -a 2 --emit neo4j -o ./out
# → writes ./out/graph.cypher  (a self-contained, re-runnable script)
cypher-shell -u neo4j -p <password> < ./out/graph.cypher
```

The snapshot is **not** incremental: it constraints, scopes-wipes this application's prior subgraph,
then `UNWIND … MERGE`-loads the full truth. The wipe is everything under `can://<app>/`, plus a
containment traversal that also replaces a prior v1 (pre-`3.0.0`) graph of the same application.
That scope **includes** this application's `:Artifact` and `:ConfigKey` nodes — the snapshot rebuilds
them, rather than leaving stale ones to accumulate with nothing ever cleaning them up. The one
consequence: a sibling-language analyzer's edge into a shared `:Artifact` is dropped by a Java
snapshot and restored on that analyzer's next push. The Bolt writer does **not** do this — its
deletions are scoped per module, not per application.

### 4.2. Live incremental push over Bolt

```sh
codeanalyzer -i /path/to/project -a 2 --emit neo4j \
  --neo4j-uri bolt://localhost:7687 --neo4j-user neo4j --neo4j-password <password>
```

The Bolt writer reads the database's current state and updates **only what changed**: it diffs each
compilation unit's `content_hash`, replaces just the changed units' subgraphs (idempotent
`MERGE` upserts), and — on a full run — prunes units whose source file vanished. Combine with
`--target-files` for a targeted, partial re-push (orphan pruning is then skipped).

### 4.3. Schema contract

```sh
codeanalyzer --emit schema -o ./out   # → ./out/schema.neo4j.json (no project analysis needed)
codeanalyzer --emit schema            # → prints the contract to stdout
```

### 4.4. Verifying the writers

A no-container conformance test (`Neo4jSchemaConformanceTest`) asserts the projector never emits a
label/relationship/property the catalog doesn't declare, and that `schema.neo4j.json` is current. A
Testcontainers-backed integration test (`Neo4jBoltWriterTest`) spins up a real Neo4j and exercises
the Bolt writer (full push, idempotent re-push, orphan pruning). The container suite is **opt-in**
(it needs Docker/Podman) and runs only when `RUN_CONTAINER_TESTS` is set:

```sh
RUN_CONTAINER_TESTS=1 ./gradlew test
```

## FAQ

1. After making a few code changes, my native binary gives random exceptions. But, my code works perfectly with `java -jar`.

   The `reflect-config.json` is most likely out of date. Plese follow the below instructions:

      a. Build the fatjar using `./gradlew fatJar`

      b. Run the following

      ```sh
      java -agentlib:native-image-agent=config-output-dir=src/main/resources/META-INF/native-image-config -jar build/libs/codeanalyzer-1.0.jar -i src/test/resources/sample.applications/daytrader8/source -a 2 -v
      ```

      c. Then build using the instructions in [§3.3](./README.md#33-build-the-project).

   The problem should be resolved.

2. I passed `--no-build`, but Maven still ran and wrote jars into my project.

   Expected, and the flag's description undersells it. `--no-build` suppresses the *application*
   build that WALA needs — it does not suppress **dependency resolution**, which runs at every
   level so that third-party types resolve to qualified names instead of degrading to bare
   spellings. That step invokes your project's wrapper:

   ```sh
   mvnw --no-transfer-progress -f <pom> dependency:copy-dependencies \
        -DoutputDirectory=<project>/target/_library_dependencies -Doverwrite=true --fail-never
   ```

   So it writes into `<project>/target/_library_dependencies/` even under `--no-build`, and
   prints `BUILD SUCCESS`. It compiles nothing.

3. With `--no-build` I get `No application classes found` and no call graph.

   `--no-build` means "I have already built this application" — the analyzer takes you at your
   word and skips the compile. WALA then finds no classes under `target/classes`, because
   dependency resolution (above) does not produce any. Either build the project first, or drop
   `--no-build` and give the analyzer a real JDK (see the note in [Quick install](#quick-install)
   — the bundled `jdk4py` runtime has no `javac`).

   Degrading here is deliberate, not a bug: you still get the tree, the declared call graph, the
   syntactic CFG/CDG/DDG and (at `-a 4`) the SDG vertices, `param_in`/`param_out` and summaries.
   Only the WALA-derived overlays — the RTA edges and the alias-aware `prov: ["points-to"]` ddg
   edges — are missing.

4. How do I tell a degraded run from a complete one in a script?

   Pass `--strict`. By default a degraded run exits **0** and reports the loss only as a `WARN` on
   stderr, which is fine interactively and useless in a pipeline. `--strict` turns any missing
   overlay into a non-zero exit, names what was lost, and writes no `analysis.json` — so a caller
   cannot pick up a thin payload believing it is complete:

   ```console
   $ codeanalyzer -i ./app -a 2 --no-build --strict
   error: analysis degraded and --strict was requested:
     - RTA overlay: no entrypoints; call graph is declared edges only
   Build the project (or drop --no-build) to get these overlays, or rerun without --strict to
   accept the degraded output.
   ```

   It is opt-in on purpose: degrading is a supported mode, and defaulting to failure would break
   every caller who relies on it.

## LICENSE

```LICENSE
Copyright IBM Corporation 2023, 2024

Licensed under the Apache Public License 2.0, Version 2.0 (the "License");
you may not use this file except in compliance with the License.

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
