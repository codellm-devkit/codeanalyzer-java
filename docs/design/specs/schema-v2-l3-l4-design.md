# Design Spec — codeanalyzer-java: canonical schema v2 migration + L3/L4 dataflow

> **Status:** Accepted — point-in-time provenance, not a living doc. Live plan & status: [Epic codellm-devkit/.github#42](https://github.com/codellm-devkit/.github/issues/42). Decisions ledger: [`.claude/SCHEMA_DECISIONS.md`](../../../.claude/SCHEMA_DECISIONS.md).

## 1. Summary

Migrate `codeanalyzer-java` from the legacy v1 output (`{symbol_table, call_graph, version}`, rich `JGraphEdges`, per-callable `code`, `is_*` boolean type flags) to the **canonical schema v2** — one additive Code Property Graph (a tree of `application → module → type → callable → body` nodes with typed edge overlays) — and grow it to **analysis level 4**, in **both projections** (`analysis.json` and Neo4j). Concretely this adds:

- **L1/L2 in v2 shape** — the additive tree with `can://` ids, per-module `source` blob, byte-offset spans, `body{}` `call` nodes, and identity-only `call_graph` edges. (Keeps the JavaParser + WALA compute guts; rewrites only the emission layer.)
- **L3 — intraprocedural dataflow** — the rest of `body{}` (statements) plus `cfg`/`cdg`/`ddg` (syntactic, `prov:["ssa"]`) edge overlays, per callable.
- **L4 — interprocedural SDG** — synthetic `formal_in/out` + `actual_in/out` vertices, `param_in`/`param_out`/`summary` edges, and semantic `ddg` (`prov:["points-to"]`).

The python-sdk Java models migrate to v2 in lockstep behind a **frozen public API** (two-layer model), as a later rung.

**CLDK context:** CLDK is actively migrating v1 → v2. `codeanalyzer-python` is the reference pilot (v2 + L3/L4 are on its `main`). The v2 keystone is a *proposal we refine as we implement*; this spec records the Java-specific refinements.

**Independence note:** This effort is designed **independent of** the `origin/minor/issue-171-full-SDG` branch. That branch is a useful proof that WALA can compute the CFG/PDG/SDG facts (RTA + `SDG`/`ModRef`, source lines via `IMethod.getSourcePosition`), but it emits the **old v1 shape** and collapses intra/inter-procedural into a single "level 3". We reuse the *approach*, not the code.

---

## 2. Contract-Impact Triage

**Does this change schema v2 output?** Yes — it is the v2 migration itself plus two new levels (new node kinds, edge families, ids, and the envelope).

| Change type | Analyzers | SDKs | Docs |
| --- | --- | --- | --- |
| Schema v2 migration + new analysis levels (L3/L4) for Java | `codeanalyzer-java` (emission layer + Neo4j projection) | `python-sdk` (Java models → v2 views; new `get_program_dependency_graph`) | user-facing schema/levels docs (later, `finishing-cldk-work`) |

**Repos touched:** `codeanalyzer-java`, `python-sdk`. (TS SDK not in scope for this effort.)

---

## 3. Scope & non-goals

**In scope**
- `codeanalyzer-java`: v2 emission for L1/L2; new L3 and L4; Neo4j v2 projection; CLI contract alignment; conformance/monotonicity/determinism gates.
- `python-sdk`: Java model migration to v2 behind a stable public API (later rung; usually starts after the analyzer's v2 release is cut).

**Non-goals**
- Slicing / taint engines in the analyzer. The analyzer is a **pure graph provider**; slicing/taint/reachability are **SDK queries** over the emitted graph (`cldk-sdk-frontend`).
- Migrating sibling analyzers (Go/TS) — parity is preserved by holding the shared vocabulary; their migration is separate.
- Framework/entrypoint enrichment redesign — the existing detectors carry forward; enrichment is an orthogonal precision axis, not a level.
- A `msgpack` format, incremental L3/L4 caching beyond recording dependency metadata (aspirational).

---

## 4. The canonical v2 model (recap)

One structure — a CPG — in two projections that must agree.

- **Containment tree** (single-parent): `application → symbol_table{file→module} → types{}/functions{} → callables{} → body{local-id→node}`.
- **Typed edge overlays** (multi-valued): `call_graph`, `cfg`, `cdg`, `ddg`, `param_in`, `param_out`, `summary`. Intra-callable overlays hang on the callable; cross-callable on the application. Each edge is `{src, dst, …attrs}`; **the list name is the type** (no `type` field); **no dangling endpoints**.
- **Identity:** durable `can://java/<app>/<file>/<type>/<callable-signature>` ids for nodes ≥ callable; ordinal `…@<line>:<col>` (real) / `…@<tag>` (synthetic) ids within a callable.
- **Levels are additive**: `L1 ⊆ L2 ⊆ L3 ⊆ L4` (superset modulo the sanctioned `callee: null → id` refinement and the L3-ssa ⊆ L4-adds-points-to DDG rule).
- **Conventions:** snake_case keys everywhere (Gson `LOWER_CASE_WITH_UNDERSCORES`); absence = "no fact" (no `null` except the `callee` slot); `max_level` in the envelope is authoritative.

## 5. Level model

| Level | Grows (nodes) | Adds (edges) | Engine | Flag |
| --- | --- | --- | --- | --- |
| **L1** | tree to callable depth + `call` nodes in `body` (`callee:null`); `source`; byte spans; `can://` ids | — | JavaParser (existing) | `-a 1` |
| **L2** | backfill `callee` (`null→id`) | `call_graph` (callable→callable, identity-only; `prov:["declared","rta"]`) + `external_symbols` | JavaParser declares (`declared`); WALA RTA attests + extends (`rta`) | `-a 2` |
| **L3** | rest of `body` (statements) + `@entry`/`@exit` | `cfg`, `cdg`, `ddg` (syntactic, `prov:["ssa"]`) | WALA `SSACFG`/dominance/SSA def-use | `-a 3` |
| **L4** | `formal_in/out`, `actual_in/out` synthetic vertices | `param_in`, `param_out`, `summary`; semantic `ddg` (`prov:["points-to"]`) | WALA `SDG`+`ModRef`+pointer analysis + own summary pass | `-a 4` |

---

## 6. Design decisions (schema-design-loop outcomes)

Recorded in [`.claude/SCHEMA_DECISIONS.md`](../../../.claude/SCHEMA_DECISIONS.md); divergences from the canonical keystone and the Python pilot are noted per decision.

| # | Decision | Choice | Rationale / divergence |
| --- | --- | --- | --- |
| D1 | Transition posture | **Pure canonical v2** | Drop per-callable `code` (SDK slices `module.source[span.bytes]`); drop legacy flat `start_line/end_line` (use `span`); call sites are **only** `body` `call` nodes (no `call_sites[]`). Cleaner than the Python pilot (which kept legacy fields); SDK views reconstruct the old surface. |
| D2 | Annotations | **Structured `decorators:[{name,args,span}]`** | Java annotations carry meaningful args (`@RequestMapping("/x")`, `@Column(name=…)`) needed by framework/CRUD/entrypoint analysis. Diverges from the Python pilot's flat list; matches keystone. |
| D3 | Metrics & cross-refs | **Nested per keystone** — `metrics:{cyclomatic}`, `refs:{types:[id],fields:[id]}` | Forward-compatible/extensible; SDK views expose old flat names. |
| D4 | Type kinds | **Single `kind`** ∈ `class\|interface\|enum\|record\|annotation` + `nesting:{parent?,is_local?}` | Replaces the `is_interface/is_enum/is_record/is_nested/...` boolean pile. |
| D5 | L3 CFG engine & granularity | **WALA engine → project to source-statement `line:col` nodes** | WALA computes SSACFG/dominance/def-use (heap-ready for L4); project each SSA instruction to its enclosing source statement via `IMethod.getSourcePosition` + JavaParser statement spans. **Fallback:** if source-fidelity proves unresolvable, revisit hand-building the CFG from the JavaParser AST (how Python/TS/Go do it). |
| D6 | L4 points-to precision | **RTA default + `--precision {rta,0-cfa,0-1-cfa}`** | RTA is proven to scale (0-1-CFA was found not to); coarse heap precision ⇒ conservative semantic `ddg`. Precision tunable per project. |
| D7 | L4 summary edges | **Own summary pass** (bottom-up over the SCC condensation, k-limited, monotone fixpoint) | Bottom-up composition with monotone fixpoint over SCC-condensation DAG, mirroring the Python pilot's approach (which operates at statement granularity, not region decomposition); keystone-conformant. Heaviest L4 unit; lands last. |
| D8 | `can://` scheme for Java | `can://java/<app>/<file>/<type>/<signature>` | Java analog of the pilot's `can://python/…`; built from the existing `signatureOf()`. |
| D9 | Neo4j namespace | Keep the **`J_`** relationship prefix | Existing convention (`J_CALLS`, …); dual-label `JSymbol` merge pattern already present. |

---

## 7. L1 / L2 — v2 emission (field-by-field)

Keep JavaParser (L1) and WALA RTA (L2) as the compute layer; add a **v2 emitter** that walks the existing in-memory model objects and produces the v2 tree. The single genuinely new L1 datum is **byte offsets** on spans (thread through from JavaParser token positions); `source` is the file text already read.

| Canonical v2 | Java source today | Mapping |
| --- | --- | --- |
| envelope `{schema_version:"2.0.0", language:"java", max_level, k_limit?, analyzer{name,version}, application}` | flat `{symbol_table, call_graph, version}` | new envelope; `version` → `analyzer.version`; `max_level` from `-a` |
| `application{id, kind, symbol_table, call_graph, param_in, param_out, external_symbols}` | (implicit) | `id = can://java/<app>`; `external_symbols{}` homes unresolved call targets |
| `module{id, kind:"module", span, source, package, imports, types, functions, content_hash}` | `JavaCompilationUnit` | file-keyed by **relative** path; add `source`, byte spans |
| `type.kind ∈ {class,interface,enum,record,annotation}`, `base_types[]`, `interfaces[]`, `modifiers[]`, `decorators[]`, `nesting`, `callables{}`, `fields{}` | `JType` (`is_*` booleans, `extends_list`, `implements_list`, `annotations`) | D4 (kind+nesting), D2 (structured decorators), rename extends/implements |
| `callable{id, kind, signature, span, parameters[], return_type, error_channel[], modifiers, decorators, metrics{cyclomatic}, refs{types,fields}, body{}, cfg,cdg,ddg,summary}` | `Callable` (`code`, `thrown_exceptions`, `cyclomatic_complexity`, `referenced_types`, `accessed_fields`, `callSites`) | D1 (drop `code`/flat lines/`call_sites[]`), D3 (nested metrics/refs), `thrown_exceptions → error_channel` |
| `body` `call` node `{kind:"call", span, callee:(null→id), arguments:[local-id]}` | `CallSite` list | call sites become body `call` nodes at L1; `callee` backfills at L2 |
| `field{id, kind:"field", type, modifiers[], decorators[], span}` | `Field` | direct map |
| `call_graph:[{src,dst,prov,weight}]` | rich `JGraphEdges`/`SystemDepEdge` | **identity-only**; unresolved targets → `external_symbols` (edge only when resolved) |

**Callable/param kinds (Java):** `method`, `constructor`, `initializer` (static/instance init blocks), `lambda`. Parameters ordered; `is_variadic` for varargs.

**Precision posture (L2):** the call graph has two producers, distinguished by `prov` (see D18). JavaParser's symbol solver *declares* every edge to a resolved target (`prov:["declared"]`, needs only dependency jars, no build); WALA RTA *attests and extends* it with dynamic-dispatch fan-out (`prov:["rta"]`, needs a build). This inverts the earlier "Java's only call graph is WALA's" posture. Unresolved sites keep `callee:null`, skip the edge, never crash; `--no-rta` or a failed build degrades to `declared`-only rather than failing the level.

---

## 8. L3 — intraprocedural (CFG / CDG / DDG)

> **Detailed design:** [`l3-intraprocedural-dataflow-design.md`](./l3-intraprocedural-dataflow-design.md). It refines this section into **two interchangeable engines** (`--l3-engine ast|wala`, AST default), which **revises D5** (WALA-engine / AST-fallback → AST-default / WALA-opt-in) as D28. The sketch below stands; the detailed doc is authoritative on engine posture and edge representation.

**Body node kinds:** `statement`, `call` (L1), `return`, `branch`, `loop`, `switch`, + synthetic `entry`/`exit` (one each per callable, no span).

**CFG construction (D5):** WALA `SSACFG` + `ISSABasicBlock` as the engine; project each SSA instruction to its enclosing **source statement** (`IMethod.getSourcePosition(iindex)` → `line:col`, grouped by the JavaParser statement span from L1). Multi-exit normalized to a single `@exit`. Every node reachable from `@entry` and reaching `@exit`.

**Java-specific lowering** — each needs a documented rule **and a fixture** before the CFG gate:
- **Checked exceptions:** an `exception` edge (per `throws` type / per potentially-throwing call) to the nearest enclosing handler, else `@exit`. Over-approximate.
- **`try`/`catch`/`finally`** incl. **`finally` duplication** and **try-with-resources** (implicit close in a synthetic finally).
- **`synchronized`** blocks (monitor enter/exit regions).
- **Static / instance initializer blocks** — their own CFGs (callable kind `initializer`).
- **`switch`** — classic fall-through (`switch_case`) and arrow form.
- **Labeled `break`/`continue`** — edges to the labeled target.

CFG edge `kind` ∈ `fallthrough|true|false|switch_case|loop_back|exception|return|break|continue`.

**CDG:** post-dominators on the reverse CFG (Cooper–Harper–Kennedy), rooted at `@exit`; infinite loops get a synthetic edge to `@exit` first. Control dependence via Ferrante–Ottenstein–Warren; emit as `cdg` edges.

**DDG (syntactic):** def-use from WALA SSA. `ddg.var` = **k-limited access path** `base(.field|[*])*` (default **k=3**, `--graph-field-depth`); over the limit collapses to `…*` and conservatively aliases deeper. Bases tagged: local, parameter, receiver (`this`), field, captured. Every `ddg` edge `prov:["ssa"]`. Aliased writes are **not** resolved here (that is L4).

**PDG** = `cdg ∪ ddg` over the same body nodes (bookkeeping, no separate section).

**Determinism:** assign body-node ids by sorted source position; collect then sort; never emit during parallel fan-out. `-j N` byte-identical to `-j 1`.

---

## 9. L4 — interprocedural (the SDG)

**Synthetic vertices** in `body{}`:
- `formal_in{of:<param>}`, `formal_out{of:$ret | by-ref param}` — children of the callable.
- `actual_in{of:argN, parent:<callsite local-id>}`, `actual_out{of:$ret, parent}` — children of the `call` node.

Global/static state modeled as **extra** formal/actual vertices (rides the same mechanism).

**Cross-function edges:** `param_in` (actual_in→formal_in) and `param_out` (formal_out→actual_out) at application scope; `summary` (actual_in→actual_out, same call) on the callable.

**Engine:** WALA `SDG<InstanceKey>(callGraph, pointerAnalysis, new ModRef<>(), dataOpts, controlOpts)`, pruned to application scope with `GraphSlicer.prune`. WALA `Statement.Kind`s (`PARAM_CALLER/CALLEE`, `*_RET_*`, `HEAP_*`) map to the synthetic vertices / edges.

**Points-to precision (D6):** default **RTA** (reuse the L2 `rta` overlay's pointer analysis). The dependency is on that overlay specifically, not on L2 as such — so a future decision to make RTA optional at L2 (`--no-rta`, D18) must not silently strand L4, which would then need to build its own RTA. `--precision {rta,0-cfa,0-1-cfa}` opts into richer analysis (0-CFA/0-1-CFA rebuild the graph for L4).

**Semantic DDG:** `DataDependenceOptions.FULL` + `ModRef` yields alias/heap-derived def-use, emitted as **additional** `ddg` edges tagged `prov:["points-to"]`. L3's `prov:["ssa"]` edges are untouched — this preserves `L3 ⊆ L4` (weak-update / over-approximate posture; no strong updates that would remove an edge).

**Summary pass (D7):** a dedicated pass composing bottom-up over the SCC-condensation DAG (Tarjan), k-limited, iterated to a monotone fixpoint within each SCC. The Python pilot reaches its transfer relation at statement granularity rather than via region decomposition; region decomposition remains an open refinement for either analyzer. Both analyzers persist `cfg` and `cdg` on the callable and compute post-dominators; what remains to implement is the region decomposition itself. Produces the `summary` (actual_in→actual_out) edges that make later SDK slicing/taint context-sensitive without re-descending into callees. Heaviest unit; sequenced last.

**Cost controls:** flag-gated (nothing at L4 runs unless `-a 4`); k-limiting mandatory for termination; summaries content-hashed/cached with recorded dependency metadata (incremental re-analysis aspirational); parallel-by-construction wavefront over the SCC DAG, `-j N` byte-identical to `-j 1`.

### 9a. Implementation delta

What the L4 branch actually shipped, against the sketch above. Output conforms; the construction does not, and two vertex families were deferred.

**Deferred (not built):**
- **Global/static state as extra formal/actual vertices.** No such vertices are emitted. Static-field *flow* is not lost: `L4WalaOverlays` maps a `StaticFieldKey` heap location to the field name, so static-mediated dependence still rides the `prov:["points-to"]` ddg — it is just not addressable as a vertex a consumer can enumerate.
- **`formal_out` for by-ref parameters.** `SdgVertices` emits exactly one `@formal_out` per callable, for `$ret`, and only when the callable returns a value. Java has no by-ref parameters; mutation-through-a-reference-argument therefore has no dedicated vertex and shows up (if at all) as points-to ddg.

**Built differently (engine deviations):**
- **`param_in`/`param_out` are derived, not sliced.** They come straight from the v2 tree — body `call` nodes with L2-backfilled `callee`, wired to the callee's parameter list — rather than from a WALA `SDG<InstanceKey>` pruned with `GraphSlicer.prune`. The edges are structurally determined by the call graph alone, so the derived result is identical and byte-deterministic, and it avoids re-opening the whole-program ModRef closure that OOMs at 4 GB over a JDK-inclusive call graph (`WalaAnalysis.emptyDefaultingMap`). Recorded in `docs/design/plans/2026-08-27-l4-sdg.md`.
- **Semantic ddg comes from per-method PDGs, not a whole-program SDG.** `L4WalaOverlays` primes mod/ref restricted to application-scope CG nodes (the bounded answer to that same OOM), re-runs `WalaPdgBuilder` per application method, and projects caller-side `HeapStatement`s onto their call's own `NormalStatement` so the interprocedural round trip lands on real body nodes. Consequence: library-mediated heap flow is conservatively absent.

**No `SDG` object is ever constructed** — a reader looking for one will not find it.

---

## 10. CLI contract

On the existing Picocli app: `-a 1..4` (default 1), `--graph-field-depth <k>` (default 3), `--precision {rta,0-cfa,0-1-cfa}` (default rta), `--emit {json,neo4j,schema}`, `--app-name <name>`, `-j <n>`, keeping `-i/-o/-t/--build/--skip-tests/--eager/--lazy/-c`.

Rules: `--emit neo4j` runs at **full implemented depth** and errors if combined with `-a`/`--graph-field-depth`; `--emit schema` needs no `-i`; unrecognized/unimplemented flag values exit **non-zero** with a clear message; **stdout carries only JSON** (WALA stdout already suppressed — formalize), **all logs/progress/errors to stderr**; partial resolution still exits `0`.

## 11. Neo4j v2 projection

Reuse the existing `neo4j/` skeleton (`GraphProjector`/`RowBuilder`/`GraphRows`/`CypherWriter`/`BoltWriter`/`SchemaCatalog`/`Schema`, dual-label `JSymbol` merge, `_module` provenance). Migrate `SchemaCatalog` (major bump; families rename) to v2, merge-keyed by `can://` id:

- **Node families:** `JApplication`, `JModule`, `JSymbol` (+ specific type kind label), `JCallable`, `JField`, `JParameter`; at L3/L4 body/CFG nodes (merge key = `…@line:col`/`@tag`, props `kind`,`start_line`,`end_line`,`_module`).
- **Relationships (`J_` prefix):** `J_HAS_MODULE`, `J_DECLARES`, `J_HAS_CALLABLE`, `J_HAS_FIELD`, `J_RESOLVES_TO`, `J_CALLS`(weight,prov), `J_EXTENDS`, `J_IMPLEMENTS`; at L3/L4 `J_HAS_BODY_NODE`, `J_HAS_CFG_NODE`, `J_CFG_NEXT`(kind), `J_CDG`, `J_DDG`(var,prov), `J_PARAM_IN`, `J_PARAM_OUT`, `J_SUMMARY`.
- **Depth rule:** `--emit neo4j` always full depth; deferred-edge (no-dangling) gate; cross-projection gate asserts node/edge counts match JSON at `max_level` (modulo explicit `HAS_*` containment).

## 12. python-sdk migration (later rung)

Two-layer model: canonical models once + **Java views** preserving every public accessor's name/signature/return type (`JCallable`/`JType`/`JApplication`; `get_call_graph`, `get_system_dependency_graph`, **new** `get_program_dependency_graph`; `AnalysisLevel` gains L3/L4). Document the sanctioned semantic shifts (nx node keys → `can://` ids; `.code`/`.call_sites` become computed views; rich edges retire; envelope keys change). Major SDK version bump; pin the analyzer only **after** its v2 release is cut.

**Open question (resolve at the frontend rung):** the SDK schema-contract prescribes a shared `cldk/models/cpg/` layer, but `python-sdk` `origin/release/2.0` instead shows per-language `projections.py` (no `cpg/`). Reconcile which pattern Java follows then, with a targeted read of `release/2.0`.

## 13. Module architecture

Refactor the current static `SymbolTable`/`SystemDependencyGraph` toward the analyzer skeleton, as much as serves the work (not gratuitous): `core` orchestrator (delegates each stage), `syntactic_analysis` (L1 builder), `semantic_analysis/{call_graph, dataflow}` (L2 + the L3/L4 builders split per stage: cfg, dominance/cdg, defuse/ddg, sdg, summaries), `schema/` (v2 Lombok models), `neo4j/` (existing emitter), keep the framework/entrypoint detectors behind their seam. Lombok `@Data`/builders as today; Gson `LOWER_CASE_WITH_UNDERSCORES`.

---

## 14. Testing & gates

Per-level conformance gate is a hard gate (no advance while red). Fixture project under `src/test/resources` exercising each construct with **specific-value** assertions.

- **L1:** validates against SDK `Application`; `symbol_table` keyed by relative paths (none absolute/`..`); `source` present; `get_method_body` slice = `module.source[span.bytes]`; `call` nodes carry `callee:null`; `can://` ids stable across runs; cache reuse.
- **L2:** no dangling edge endpoints; non-empty `prov`; `callee` backfilled on resolved sites; a **named** expected `(src,dst)` edge + a cross-package edge; superset over L1.
- **L3:** CFG (every node a real span; single `@entry`/`@exit`; reachability; documented edges incl. `exception`); dominance (post-dom tree at `@exit`; hand-computed control deps match); **PDG backward slice** over `cdg ∪ ddg` equals a hand-computed node set exactly (loop-carried + shadowed-scope cases).
- **L4:** no dangling `param_*`/`summary`; `param_in`/`param_out` arity matches params; a `summary` edge for a known transitive flow `a→b→c`; semantic `prov:["points-to"]` ddg present and **added to** (not replacing) L3's `ssa` edges; mutual-recursion SCC fixpoint terminates.
- **Cross-cutting:** monotonicity `-a1 ⊆ -a2 ⊆ -a3 ⊆ -a4` (CI superset gate); cross-projection (Neo4j full-depth counts match JSON at `max_level`); two-tier identity (`can://` stable; `@line:col` carries a column); determinism (`-j N` == `-j 1`); flag-validation (unimplemented value ⇒ non-zero + message).

Slice/taint gates are **frontend** gates (SDK), not analyzer gates.

---

## 15. Decomposition & release plan

**Live tracking is the epic, not this spec.** Filed as an **Epic + 8-child sub-issue stack** in [codellm-devkit/.github#42](https://github.com/codellm-devkit/.github/issues/42) — one PR per child across `codeanalyzer-java` and `python-sdk`. The epic is the single source of truth for scope and status; the shape below is a snapshot for orientation only.

Shape (snapshot): L1 tree → L2 `call_graph` → L3 (`cfg`/`cdg`/`ddg`) → L4 SDG (`param_in`/`param_out` + semantic `ddg`) → L4 summary pass → **consolidated Neo4j projection** → python-sdk v2 views → SDK slice/taint queries. The JSON levels land first; **all Neo4j projection work — the base relabel plus the L3/L4 overlays — is one consolidated pass after L4** (issue #182), so the graph schema is mapped once against the fully-stabilized JSON schema rather than reworked per level. L3 can ship/tag before L4; the summary pass lands after the rest of L4. **Consequence:** the v2 default-output flip (below), gated on the Neo4j projection, moves to post-L4; the analyzer major can still be cut on the JSON levels beforehand.

**Release / lockstep (enduring):**
- Analyzer = **major** release (breaking output; CHANGELOG *Changed/Breaking*). L1–L4 are independently shippable behind `-a`, so cut the major once L1/L2 v2 (± L3) are green and grow L4 in a follow-up minor.
- SDK = **major** release; pins the analyzer **only after** the analyzer's v2 release is cut (until then old SDK models won't parse v2).
- Neo4j `schema.neo4j.json` and the JSON schema move in lockstep.

**Gate:** implementation rungs start only after the spec is committed **and** the tracking record exists — both satisfied (this spec; epic #42 + 8 children).

---

## 16. Risks & open questions

1. **SSA → source-statement projection fidelity (D5).** Multiple SSA instructions per line, synthetic phi/pi with no source, compiler-introduced temporaries. Mitigation: group by enclosing statement span; fold/drop synthetic nodes; **fallback to AST-built CFG (Option B)** if unresolvable — recorded as a live option, not a silent one.
2. **Summary-pass cost/termination (D7).** Mitigated by mandatory k-limiting + bounded label sets + SCC fixpoint; content-hash caching. Heaviest unit; can ship after the rest of L4.
3. **RTA heap precision (D6).** Semantic `ddg` under RTA is conservative/sparse; acceptable (still monotonic). `--precision` provides the upgrade path; document known unsoundness (reflection, dynamic dispatch, unmodeled natives).
4. **SDK cpg vs per-language projections.** `release/2.0` diverges from the schema-contract's `cpg/` prescription; resolve at the frontend rung.
5. **WALA version.** Consider bumping WALA (the prior SDG branch used 1.6.10) if L3/L4 need newer APIs; validate the call-graph baseline is unchanged.
6. **Debug info dependency.** Source positions rely on the ECJ/CAst source frontend; confirm positions are present for all app classes; degrade gracefully (sentinel) where absent.

## 17. References

- Keystone: `cldk-devtools/…/canonical-schema.md`
- Methods: `…/level-1-symbol-table.md`, `…/level-2-call-graph.md`, `…/level-3-intraprocedural-dataflow.md`, `…/level-4-interprocedural-sdg.md`
- Projections/CLI/testing: `…/neo4j-projection.md`, `…/cli-contract.md`, `…/testing-and-validation.md`, `…/analyzer-architecture.md`
- SDK: `…/cldk-sdk-frontend/references/schema-contract.md`
- Migration: `…/designing-cldk-changes/references/schema-migration.md`
- Reference pilot: `codeanalyzer-python` `main` (`codeanalyzer/schema/py_schema.py`, `codeanalyzer/dataflow/*`, `codeanalyzer/schema/ids.py`)
- Prior WALA SDG work (approach only, not reused): `codeanalyzer-java` `origin/minor/issue-171-full-SDG`
