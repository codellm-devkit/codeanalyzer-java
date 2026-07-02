# Schema decisions (codeanalyzer-java)

Auditable record of schema-affecting design decisions, in the style of the sibling analyzers'
`.claude/SCHEMA_DECISIONS.md`. Every entry was decided with the maintainer.

## Level-3 full SDG (issue #171, 2026-07-01)

| # | Concept | Options considered | **Decision** | Rationale |
|---|---|---|---|---|
| 1 | Level mapping | (a) `-a 2` emits SDG too (pre-Mar-2025 behavior); (b) new `-a 3` | **new `-a 3`**; `-a 2` stays call-graph-only, byte-identical | level-2 perf/output untouched; matches the CLDK level ladder. Follow-up on python-sdk: default to 3, dial down on request |
| 2 | Slicer dependence options | no-heap only; no-heap + knob; full always | **`--sdg-data-deps <no-heap\|full>`, default `no-heap`** (`NO_HEAP_NO_EXCEPTIONS` + `NO_EXCEPTIONAL_EDGES`; `full` = `DataDependenceOptions.FULL` + `ControlDependenceOptions.FULL`) | old fast settings by default; heap dependence is opt-in because it is an order of magnitude slower |
| 3 | Call-graph builder feeding the SDG | RTA; 0-1-CFA conditional; 0-1-CFA always | **RTA** (`Util.makeRTABuilder`), unchanged | fast, proven on fixtures; 0-1-CFA was tried (979b298) and abandoned; adequate for no-heap deps |
| 4 | SDG edge shape | method-level `JGraphEdges` only; statement-level `program_graphs` | **both**: method-level `system_dependency_graph` (zero SDK model changes) **and** statement-level `program_graphs` per the level-3 contract | the SDK's existing `JGraphEdges` field validates today; `program_graphs` is the forward contract the SDK/SCIP indexing adapts to |
| 5 | Node identity in `program_graphs` | AST-node source-span order (contract wording) | **SSA instruction order**: `node_id` 0 = synthetic `ENTRY`, then SSA instructions by `iindex`, last = `EXIT`; source lines from ECJ/CAst positions, `-1` sentinel when unavailable | WALA nodes are SSA instructions, not AST nodes; instruction order is deterministic across runs on identical content — the property the contract actually needs |
| 6 | CFG edge kinds | full shared vocabulary | shared vocabulary with documented approximations: `true`/`false` by conditional-branch successor order, `loop_back` when target iindex < source iindex, `exception` from WALA exceptional successors, else `fallthrough`/`return` | WALA's SSACFG doesn't label edges; these derivations are deterministic and recorded here rather than invented ad hoc |
| 7 | Cross-function `sdg_edges` | full HRB vocabulary | `CALL`, `PARAM_IN`, `PARAM_OUT` now; **no `SUMMARY` edges yet** (follow-up) | WALA computes HRB summaries lazily inside `Slicer`; exposing them is real extra work and not needed for the graph itself |
| 8 | Precision posture | — | sound-leaning, over-approximate; `ReflectionOptions.NONE` (unchanged); application classes only (`GraphSlicer.prune`) | matches level 2; documented unsoundness, not silently absorbed |
| 9 | Neo4j projection of the SDG | one type + `type` property; per-kind types | **per-kind relationship types `J_CONTROL_DEP`/`J_DATA_DEP`/`J_HEAP_DATA_DEP`** (`JCallable`→`JCallable`, props `weight`/`source_kind`/`destination_kind`, same resolved-gating as `J_CALLS`); schema 1.0.0 → 1.1.0 (additive) | the writers MERGE one relationship per (type, src, dst), so a pair with both control and data dependence would lose one with a single type; WALA's `Dependency` enum is closed (exactly these three), so the vocabulary is total. Statement-level CPG (`CFGNode` etc.) stays a follow-up |
| 10 | Neo4j default analysis level | keep 1; default 3 | **`--emit neo4j` defaults to `-a 3`** (an explicit `-a` still wins) | the graph is the consumer that wants the full SDG; python-sdk mirrors the same contract (python-sdk#228) |
