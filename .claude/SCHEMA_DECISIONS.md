# Schema Decisions — codeanalyzer-java

Living ledger of schema-design decisions, recorded per cldk-devtools
`schema-design-loop.md`. Each entry: the decision, its rationale, and any
divergence from the canonical keystone or the Python reference pilot. This file
is kept **current** as decisions evolve during implementation; the design spec
(`docs/design/specs/schema-v2-l3-l4-design.md`) is point-in-time provenance.

## Effort: canonical schema v2 migration + L3/L4 dataflow (2026-08)

Migrate `codeanalyzer-java` from legacy v1 (`{symbol_table, call_graph, version}`)
to the canonical v2 CPG, and grow it to analysis level 4. Anchored on the keystone
and on `codeanalyzer-python` (the v2 + L3/L4 reference pilot, on its `main`).

### D1 — Transition posture: pure canonical v2
Drop per-callable `code` (SDK slices `module.source[span.bytes]`), drop legacy flat
`start_line`/`end_line` (use `span`), and express call sites **only** as `body`
`call` nodes (no `call_sites[]`). **Divergence:** the Python pilot kept those legacy
fields additively for a smoother SDK transition; Java goes clean. SDK Java views
reconstruct the old surface (`.code`, `.call_sites`).

### D2 — Annotations: structured decorators
`decorators:[{name, args[], span}]`, not flat strings. Java annotations carry
meaningful arguments (`@RequestMapping("/x")`, `@Column(name=…)`) needed by
framework/CRUD/entrypoint analysis. **Divergence:** the Python pilot used flat
`decorators:List[str]`.

### D3 — Metrics & cross-refs: nested per keystone
`metrics:{cyclomatic}` and `refs:{types:[id], fields:[id]}`. Forward-compatible/
extensible. **Divergence:** the Python pilot and current Java keep these flat
(`cyclomatic_complexity`, `referenced_types`, `accessed_fields`); SDK views expose
the old flat names.

### D4 — Type kinds: single `kind` + `nesting`
`type.kind ∈ {class, interface, enum, record, annotation}` plus
`nesting:{parent?, is_local?}`, replacing the v1 `is_interface`/`is_enum`/
`is_record`/`is_nested`/… boolean pile.

### D5 — L3 CFG engine & granularity: WALA engine → source-statement nodes
Use WALA as the analysis engine (`SSACFG` + dominance + SSA def-use — heap-ready for
L4), but emit **source-statement-level** body nodes keyed by `line:col`: project each
SSA instruction to its enclosing source statement via `IMethod.getSourcePosition` +
JavaParser statement spans; fold/drop synthetic phi/pi nodes.
**Fallback (recorded, not silent):** if SSA→source-statement fidelity proves
unresolvable, revisit hand-building the CFG from the JavaParser AST (as Python/TS/Go do).

### D6 — L4 points-to precision: RTA default + `--precision`
Default RTA (reuse the L2 call-graph pointer analysis; proven to scale — 0-1-CFA was
found not to). Expose `--precision {rta,0-cfa,0-1-cfa}`. Coarse heap precision ⇒
conservative but sound-leaning semantic `ddg`.

### D7 — L4 summary edges: own summary pass
Compute `summary` (actual_in→actual_out) edges via a dedicated pass — hammock regions
composed bottom-up over the SCC-condensation DAG (Tarjan), k-limited to a monotone
fixpoint — mirroring `codeanalyzer-python` (`summaries.py`/`scc.py`). WALA's HRB
summaries are lazily computed inside its Slicer and not cleanly exposable. Heaviest
L4 unit; lands last.

### D8 — Identity: `can://java/<app>/<file>/<type>/<signature>`
Java analog of the pilot's `can://python/…`; built from the existing `signatureOf()`.
Ordinal ids `…@<line>:<col>` (real) / `…@<tag>` (synthetic) within a callable.

### D9 — Neo4j namespace: keep the `J_` relationship prefix
Existing convention (`J_CALLS`, …); dual-label `JSymbol` merge pattern retained.
`SchemaCatalog` takes a major bump (families rename v1→v2).

### Scope guard
The analyzer is a **pure graph provider**: it emits the CFG/PDG/SDG substrate and
stops. Slicing, taint, and reachability are **SDK queries** over the emitted graph
(`cldk-sdk-frontend`) — never analyzer features. No `taint_flows` section, no
sources/sinks policy in the analyzer.
