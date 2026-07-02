![logo](./docs/assets/logo.png)

Native WALA implementation of source code analysis tool for Enterprise Java Applications.

`codeanalyzer` extracts a comprehensive **symbol table** and **call graph** from Java applications
and emits them either as the canonical `analysis.json`, or as a **Neo4j property graph**
(`--emit neo4j`) — a `graph.cypher` snapshot or a live, incremental push over Bolt. See
[§5. Neo4j graph output](#5-neo4j-graph-output).

## Quick install

Grab the latest release jar and a `codeanalyzer` launcher (requires a Java 11+ runtime):

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
  -a, --analysis-level=<n>   Level of analysis: 1 (symbol table); 2 (call graph);
                               3 (full system dependency graph). Default: 1.
                               Level 2 adds J_CALLS edges to the graph.
      --graphs=<sections>    Comma-separated program_graphs sections to emit at
                               analysis level 3: cfg, pdg, sdg. Default: all.
      --sdg-data-deps=<d>    Depth of the slicer's data dependence at analysis
                               level 3: no-heap (fast, default) | full
                               (heap-carried dependence; much slower).
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

## 4. Full system dependency graph (`-a 3`)

At analysis level 3, `codeanalyzer` builds the **full system dependency graph** — control *and*
data dependence — from WALA's slicer on top of the level-2 RTA call graph, and emits two extra
sections in `analysis.json`:

- **`system_dependency_graph`** — method-level dependence edges in the same shape as
  `call_graph` (`source`/`target` callable, `type` = `CONTROL_DEP`/`DATA_DEP`,
  `source_kind`/`destination_kind` = the WALA statement kinds, `weight`). This is the field the
  CLDK Python SDK's `JApplication.system_dependency_graph` models.
- **`program_graphs`** — statement-level graphs keyed by `(signature, node_id)`: for each
  application callable a **CFG** (nodes = SSA instructions with source lines, synthetic
  `ENTRY`=0/`EXIT`=last; edges labeled `fallthrough`/`true`/`false`/`switch_case`/`loop_back`/
  `exception`/`return`) and a **PDG** (`CDG` + `DDG` edges over the same nodes), plus
  cross-function **`sdg_edges`** (`CALL`, `PARAM_IN`, `PARAM_OUT`). Scope the sections with
  `--graphs cfg,pdg,sdg`.

```sh
codeanalyzer -i /path/to/project -a 3 -o ./out                     # full SDG, no-heap data deps
codeanalyzer -i /path/to/project -a 3 --sdg-data-deps=full -o ./out # + heap-carried dependence
```

By default data dependence runs with WALA's `NO_HEAP_NO_EXCEPTIONS`/`NO_EXCEPTIONAL_EDGES`
options (fast); `--sdg-data-deps=full` opts into heap-carried data dependence, which is
substantially slower and only as precise as the RTA builder's type-based pointer analysis.

**Known unsoundness** (documented, unchanged from level 2): reflection is not modeled
(`ReflectionOptions.NONE`), dynamic class loading and JNI are invisible, and dispatch precision
is RTA. `SUMMARY` edges (transitive callee summaries) are not yet emitted. Levels 1 and 2 are
completely unaffected by any of this — nothing SDG-related runs below `-a 3`.

## 5. Neo4j graph output

`codeanalyzer` can project the analysis IR into a [Neo4j](https://neo4j.com/) property graph instead
of `analysis.json`. The graph is a **lossless** projection of the IR: compilation units, types,
callables, fields, parameters, call sites, variables, enum constants, record components,
initialization blocks, CRUD operations/queries, comments, annotations and packages are all
first-class nodes and relationships, and it adds `J_CALLS` edges from the call graph (`-a 2`+)
plus `J_CONTROL_DEP`/`J_DATA_DEP`/`J_HEAP_DATA_DEP` edges from the system dependency graph
(`-a 3`). **`--emit neo4j` defaults to the full SDG analysis (`-a 3`)** — pass an explicit
`-a 1`/`-a 2` to dial down.
Every field of the Lombok entity model is represented (scalars as node properties — maps such as a
field's per-variable initializers are kept as a `*_json` property since Neo4j has no map type;
comments are `:JComment` nodes in addition to the convenience `docstring` property).

The full contract (node labels, their keys and typed properties, relationship types and endpoints,
plus the constraint/index DDL) lives in [`schema.neo4j.json`](./schema.neo4j.json) and is visualized
in [`neo4j-schema.drawio`](./neo4j-schema.drawio). All node labels are `J`-prefixed and relationship
types `J_`-prefixed (e.g. `:JType`, `:JCallable`, `J_CALLS`) so a Java graph can share a Neo4j
database with the Python (`Py*`/`PY_*`) and TypeScript (`TS*`/`TS_*`) backends without colliding.
`SCHEMA_VERSION` is stamped onto the `:JApplication` node of every emitted graph.

### 5.1. Cypher snapshot (no database required)

```sh
codeanalyzer -i /path/to/project -a 2 --emit neo4j -o ./out
# → writes ./out/graph.cypher  (a self-contained, re-runnable script)
cypher-shell -u neo4j -p <password> < ./out/graph.cypher
```

The snapshot is **not** incremental: it constraints, scopes-wipes this application's prior subgraph,
then `UNWIND … MERGE`-loads the full truth.

### 5.2. Live incremental push over Bolt

```sh
codeanalyzer -i /path/to/project -a 2 --emit neo4j \
  --neo4j-uri bolt://localhost:7687 --neo4j-user neo4j --neo4j-password <password>
```

The Bolt writer reads the database's current state and updates **only what changed**: it diffs each
compilation unit's `content_hash`, replaces just the changed units' subgraphs (idempotent
`MERGE` upserts), and — on a full run — prunes units whose source file vanished. Combine with
`--target-files` for a targeted, partial re-push (orphan pruning is then skipped).

### 5.3. Schema contract

```sh
codeanalyzer --emit schema -o ./out   # → ./out/schema.neo4j.json (no project analysis needed)
codeanalyzer --emit schema            # → prints the contract to stdout
```

### 5.4. Verifying the writers

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
