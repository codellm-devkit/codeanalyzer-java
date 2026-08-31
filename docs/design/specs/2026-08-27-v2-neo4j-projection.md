# Schema v2 Neo4j projection + v2-by-default

**Status:** accepted · 2026-08-27 · tracks [#198](https://github.com/codellm-devkit/codeanalyzer-java/issues/198)

## Contract-impact triage

| Question | Answer |
| --- | --- |
| Changes `analysis.json` v2 shape? | No. Projects the existing v2 model; JSON emission unchanged per schema. |
| Changes the graph contract? | Yes — new graph generation `2.0.0` (labels/rels below), replacing the v1 graph as the default projection. |
| Changes CLI defaults? | Yes — `--schema` defaults to **v2** everywhere (json and neo4j); `--schema v1` opts into the legacy model. Breaking → **version 3.0.0**. |
| Repos touched | `codeanalyzer-java` (this spec's PR). Docs: repo-root `schema.neo4j.json` artifact replaced by the v2 catalog. |

## Decisions (user-approved 2026-08-27)

1. **Node model (convergence spec).** Call sites are `JBodyNode` rows (`kind == "call"`), not `JCallSite` nodes. Parameters flatten to `JCallable.parameters_json`; javadoc collapses to a `docstring` property; non-doc comments are not projected (they remain in JSON). Merge-label pattern: `JSymbol` over `JType`/`JCallable`/`JExternal`, keyed on the `can://` id.
2. **v1/v2 coexistence: replace, same labels.** v2 keeps the `J*` label family; `JApplication.schema_version` stamps the generation. The v2 wipe traversal includes **v1's** containment rel types (`J_HAS_UNIT`, `J_DECLARES_TYPE`, `J_HAS_CALLSITE`, `J_HAS_PARAMETER`, `J_HAS_COMMENT`, …) as well as v2's, so a v2 push of an app cleanly replaces that app's v1 graph. One app name = one graph, latest push wins.
3. **Graph is always full-depth** (keystone rule): `--emit neo4j` forces max implemented depth — L3 (`--l3-engine` still honored, `ast` default) — and external symbols on. Passing `-a`/`--analysis-level` or `--graph-field-depth` together with `--emit neo4j` is a hard non-zero error. Applies to the v2 path; `--schema v1 --emit neo4j` keeps its legacy behavior byte-identical.
4. **`--schema` defaults to v2 globally.** `--schema v1` selects the legacy JSON and legacy graph. Major bump to **3.0.0**.
5. **`--emit schema` always emits the v2 catalog** (`schema_version: "2.0.0"`), regardless of `--schema`. Repo-root `schema.neo4j.json` is regenerated as the v2 artifact and conformance-tested (byte-match). The v1 catalog is no longer emitted.

## Graph vocabulary (graph `SCHEMA_VERSION = "2.0.0"`)

Uses `J`/`J_` namespacing so the graph can share a database with a sibling language's analyzer; language extras are additive at the leaves per the parity clause.

### Node labels (label / merge label / key)

| Label | Merge | Key | Projects from | Notes |
| --- | --- | --- | --- | --- |
| `JApplication` | `JApplication` | `name` | envelope + application | `schema_version`, `analyzer_name`, `analyzer_version` |
| `JModule` | `JModule` | `id` | `symbol_table[file_key]` | `file_key`, `package`, `content_hash`, `_module` |
| `JType` | **`JSymbol`** | `id` | `module.types` (incl. nested) | `kind` (class/interface/enum/record/annotation_decl), `name`, `modifiers`, `extends`/`implements` string fallbacks, `docstring`, spans, `is_entrypoint`, `_module` |
| `JCallable` | **`JSymbol`** | `id` | `types.*.callables` | `signature`, `name`, `return_type`, `parameters_json`, `modifiers`, `code` (span-sliced from module `source`), `docstring`, `cyclomatic_complexity`, spans, `is_entrypoint`, `_module` |
| `JExternal` | **`JSymbol`** | `id` | `external_symbols` + unresolved call/import targets | `name`, `module`; shared (no `_module`) |
| `JField` | `JField` | `id` | `types.*.fields` | java's `PyAttribute` analogue; `name`, `type`, `initializer`, `modifiers`, spans, `_module` |
| `JVariable` | `JVariable` | `id` | callable `variable_declarations` | `name`, `type`, `initializer`, spans, `_module` |
| `JEnumConstant` | `JEnumConstant` | `id` | `types.*.enum_constants` | additive java kind |
| `JRecordComponent` | `JRecordComponent` | `id` | `types.*.record_components` | additive java kind |
| `JBodyNode` | `JBodyNode` | `id` (global ordinal `<callable-id>@<local>`) | `callables.*.body{}` | `kind`, call metadata (`method_name`, `receiver_type`, `arguments_json`, …), spans, `_module` |
| `JPackage` | `JPackage` | `name` | unresolved import targets | shared |
| `JAnnotation` | `JAnnotation` | `name` | annotation applications | shared; application args ride on the `J_ANNOTATED_BY` edge, never the node |

`Artifact`/`Package` (un-prefixed, cross-language) are **reserved, not emitted** — the java v2 model has no artifacts/dependencies facts yet.

### Relationship types

| Type | From → To | Props | Source |
| --- | --- | --- | --- |
| `J_HAS_MODULE` | JApplication → JModule | — | symbol_table |
| `J_DECLARES` | JModule/JType/JCallable → JType/JCallable | — | containment (top-level, nested, local/anonymous) |
| `J_HAS_METHOD` | JType → JCallable | — | callables map |
| `J_HAS_FIELD` | JType → JField | — | fields |
| `J_DECLARES_VAR` | JCallable → JVariable | — | variable declarations |
| `J_HAS_ENUM_CONSTANT` | JType → JEnumConstant | — | additive |
| `J_HAS_RECORD_COMPONENT` | JType → JRecordComponent | — | additive |
| `J_HAS_BODY_NODE` | JCallable → JBodyNode | — | body{} |
| `J_RESOLVES_TO` | JBodyNode → JCallable/JExternal | — | `body.callee` (`can://` id) |
| `J_CALLS` | JCallable/JExternal → JCallable/JExternal | `weight` int, `prov` string[] | `call_graph` |
| `J_EXTENDS` | JType → JType | — | deferred/gated; string fallback on node |
| `J_IMPLEMENTS` | JType → JType | — | additive java edge, same gating |
| `J_IMPORTS` | JModule → JModule/JPackage/JExternal | `spellings` string[], `is_static` bool, `is_wildcard` bool | imports aggregated per target |
| `J_ANNOTATED_BY` | JType/JCallable/JField → JAnnotation | `expression`, `arguments_json` | annotation applications |
| `J_CFG_NEXT` | JBodyNode → JBodyNode | `kind`, `_k` | `cfg` (`_k = kind`) |
| `J_CDG` | JBodyNode → JBodyNode | — | `cdg` |
| `J_DDG` | JBodyNode → JBodyNode | `var`, `prov` string[], `_k` | `ddg` (`_k = var\|prov`) |
| `J_PARAM_IN` / `J_PARAM_OUT` / `J_SUMMARY` | JBodyNode → JBodyNode | `var` / `var` / — | reserved for L4; declared in catalog |

Constraints derive one-per-`(merge_label, key)` via `uniquenessConstraints()`. Indexes: `JCallable.name`, `JType.name`, fulltext `j_code_fts` on `JCallable[code, docstring]`.

### Known reference bugs not copied

The wipe traversal must include `J_HAS_BODY_NODE` and every v2 **and v1** containment type (decision 2), or L3 body nodes survive the wipe.

## Architecture

Reuse unchanged: `GraphRows`, `RowBuilder` (add the optional edge `_k` discriminant), `CypherWriter`, `BoltWriter`, `BoltSink`/`BoltConfig`, reflective driver loading, batching (500 file / 1000 bolt). New: `V2GraphProjector` (pure `(Analysis, appName) → GraphRows`), `V2SchemaCatalog` (2.0.0 catalog + derived constraints). `Neo4jEmitter` dispatches on schema version. v1 `GraphProjector`/`Schema`/`SchemaCatalog` remain for `--schema v1`, minus the catalog emission (decision 5).

## CLI matrix (post-change, v3.0.0)

| Invocation | Behavior |
| --- | --- |
| `--emit json` (no `--schema`) | **v2** analysis.json |
| `--schema v1 --emit json` | legacy v1 JSON, byte-identical to 2.4.x |
| `--emit neo4j` | v2 graph, forced full depth (L3 + externals), wipe covers v1+v2 |
| `--schema v1 --emit neo4j` | legacy v1 graph, byte-identical to 2.4.x |
| `--emit neo4j` + `-a`/`--graph-field-depth` | error, non-zero exit (v2 path) |
| `--emit schema` | v2 catalog (2.0.0), no `-i` needed, `--schema` ignored |

## Release plan

1. `codeanalyzer-java` **3.0.0**: this spec's PR (projection + default flip + catalog). One PR closes #198.

## Definition of done

Carried by #198 (updated): cypher-shell round-trip clean; graph counts equal `analysis.json` counts exact-set (modules, types, callables, body nodes, call/cfg/cdg/ddg edges, modulo containment edges); v2 push replaces a pre-existing v1 graph of the same app with zero leftover v1-only nodes; `--schema v1` output byte-identical to 2.4.x for json and neo4j; conformance test byte-matches the checked-in v2 `schema.neo4j.json`; `-a` + `--emit neo4j` exits non-zero.
