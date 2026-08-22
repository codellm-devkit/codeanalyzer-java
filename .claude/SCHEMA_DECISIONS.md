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

### D4 — Type kinds: single `kind`; nesting via containment
`type.kind ∈ {class, interface, enum, record, annotation}` replaces the v1
`is_interface`/`is_enum`/`is_record`/`is_nested`/… boolean pile.

**Nesting/locality is encoded by containment, not a `nesting` field** (refined
2026-08 after checking the Python pilot): member/inner types live under the
enclosing type's `types{}`; local classes under the enclosing callable's
`types{}`; and the `can://…/Outer/Inner` id path records the parent. Parent and
is-local are therefore derivable from tree position — no `nesting` object is
emitted. The keystone lists a `nesting:{parent?,is_local?}` field, but full
containment subsumes it, matching how `codeanalyzer-python` models it
(`PyClass.types` for inner classes, `PyCallable.types` for local classes).

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

**L1 refinements (2026-08, during CallableBuilder):**
- **Signature is shared, not duplicated.** The v1 type-erasure logic moved to
  `syntactic_analysis.Signatures.typeErasure(CallableDeclaration)`; both the v1
  symbol table and the v2 `CallableBuilder` call it, so ids match. It falls back to
  the plain AST signature when no symbol solver is configured (pure syntactic parse),
  so it never throws.
- **Ordinal-id anchor = invoked-name position.** A `call` body node's tag (and the
  local-ids in its `arguments`) use the *method-name* `line:col`, not the whole
  expression's begin — so chained calls `a.b().c()` get distinct ids instead of
  colliding on the shared expression start.
- **L1 resolves types with the JavaParser symbol solver** (corrected 2026-08 — an earlier note here
  wrongly said L1 stayed syntactic). The keystone's L1 guide expects the resolver to populate type
  fields when the structural tool resolves, and the v1 symbol table did exactly this, so v2 matches:
  `base_types`/`interfaces`, field/parameter/return/local types, `error_channel`, and `refs.types`
  are **resolved qualified names** (`java.lang.String`), and the callable `signature` uses
  **erased** resolved parameter types (`m(java.util.List, java.lang.String)`) — which is why the
  durable id depends on the solver being configured. Resolution failures degrade to the AST spelling
  (never crash) and are memoized per spelling. `refs.fields` are qualified by their declaring type
  (`p.Foo.count`); promoting them to full `can://` ids needs cross-module resolution (L2+).
- **Degradation is per parameter, not per signature.** An earlier all-or-nothing `try/catch` around
  the whole parameter list meant one unresolvable parameter dropped *every* parameter to its AST
  spelling — emitting `m(List, Mystery)` on the declaration side against `m(java.util.List, Mystery)`
  on the call side, so L2 could never join the edge. Each parameter is erased independently and only
  the ones that fail degrade. This is shared with v1 (above), so v1 signatures improve too.
- **`callable.kind ∈ {method, constructor}`.** Direct members only (via
  `getMethods()`/`getConstructors()`); nested-type methods hang under their own type,
  local (method-body) classes under `callable.types` (D4 containment).

### D10 — L1 emission: body keys, null policy, call sites, spans

Refinements settled while building L1 (2026-08), each checked against the keystone **and**
`codeanalyzer-python`:

- **`body` is keyed by the bare local id** (`line:col`), not the full `<callable-id>@line:col`.
  The keystone keys `body` "by the node's local id" and its worked example shows `"15:2"` /
  `"@entry"`; the pilot does `key = f"{cs.start_line}:{cs.start_column}"`. The full form is derived
  only where cross-callable ids are needed (L4's application-scope `param_in`/`param_out`).
- **L3 must not overwrite an L1 `call` node.** A bare call statement resolves to the same local id
  as its `call` node; per the keystone's example the call node *is* that statement, so L3 adds the
  remaining statements around it and never rewrites its `kind` (rewriting would break the additive
  invariant). A call nested in a larger statement (`int y = bar(x);`) yields two distinct nodes.
- **Call sites include constructors.** `new Foo()` and explicit `this(...)`/`super(...)` chaining are
  emitted as `call` nodes alongside method invocations — L2 resolves all three into `call_graph`
  edges, so omitting them would silently drop constructor edges. Anchor: the invoked name (method
  name, or instantiated type name), which also keeps chained calls `a.b().c()` distinct.
- **`arguments` are positional addresses, not node references.** They carry argument `line:col`
  local ids for tooling, but no `body` node need exist at those positions: expression nodes are
  optional in the keystone (`--materialize-expressions`, **not implemented here**) and L4's
  `actual_in{of:"argN", parent}` is the canonical way arguments become real nodes. The no-dangling
  invariant governs *edges*, which these are not.
- **No nulls are emitted — absence encodes "no fact"** (`V2Json` deliberately omits
  `serializeNulls()`). This includes the `callee` refinement slot: the key is absent at L1 and
  appears once L2 resolves the site. The keystone's `callee: null` example is illustrative; the pilot
  likewise drops it via `exclude_none`.
- **Varargs: `type` keeps the element type + `is_variadic` flag** (keystone's `param.is_variadic?`),
  so `String...` stays distinguishable from a real `String[]` parameter.
- **`module.span` covers the whole file**, computed from the source rather than the compilation
  unit's AST range (which ends inconsistently around trailing whitespace), so
  `module.source[span.bytes] == module.source` always holds.
- **A call site's `callee_signature` must be joinable against the target callable's `signature`.** A
  resolved constructor's name is its *class* name, while the declaration side emits `<init>`, so the
  callee side normalises to `<init>` too. Without this every constructor edge would be unjoinable and
  L2 would silently drop it (88 of petclinic's call sites).
- **Call sites with no source range are skipped.** They cannot be addressed by a `line:col` id, and
  fabricating one would both invent a location and collide with every other rangeless node, silently
  overwriting entries in `body`.
- **Metrics are scope-filtered like every other callable fact.** `metrics.cyclomatic` counts only branch
  points belonging to the callable itself; those inside a nested or anonymous class belong to that
  class's callables and would otherwise be counted twice.
- **`module.content_hash` is SHA-256 hex of the UTF-8 source** — for incremental caching and the
  Neo4j writer's per-module diffing; never identity (the `id` is).

### D14 — Incremental caching keyed on `content_hash`

`module.content_hash` exists so an unchanged file need not be re-analysed, and the v2 path now uses it:
with `-c/--cache-dir`, modules are persisted to `analysis_cache.json` and reused when the file on disk
still hashes to the same value. The reuse skips **parsing** as well as building — the extractor
enumerates and hashes files itself rather than parsing a whole source root up front — which is where the
cost actually is: `commons-lang` (625 files) goes from 130s cold to 4s warm.

- **Caching is opt-in.** No `--cache-dir`, no cache file; the analyzer never writes into a project
  uninvited. `--eager` ignores an existing cache, which is also how a caller recovers from one they
  distrust.
- **The cache is invalidated wholesale when the application name or analyzer version changes**, because
  both are baked into every `can://` id — a module cached under different settings would carry wrong
  ids. A missing, corrupt or mismatched cache degrades to a full rebuild and is never fatal.

### D13 — Anonymous classes are modelled; body text is recovered via `body_span`

Both refinements came out of a field-by-field v1-vs-v2 comparison over ten real-world applications
(`docs/design/notes/l1-v1-v2-comparison.md`).

- **Anonymous inner classes get their own `type` node**, keyed positionally (`$anon$0`, `$anon$1`, … in
  declaration order) under the callable that declares them, exactly as named local classes are. v1
  recursed into anonymous bodies and mis-attributed their initializers and locals to the *enclosing
  type*; simply excluding them (the first v2 attempt) lost those facts instead. Modelling them closed
  the measured gap exactly: initializer blocks and local variables went from -10/-20 to parity.
- **`callable.body_span` delimits the body block.** v2 drops v1's per-callable `code` string (D1) on
  the basis that body text is a slice of `module.source` — but the callable's own `span` covers the
  *whole declaration*, so slicing it yields signature + body, not v1's body-only `code`. `body_span`
  is the span of the `{ … }` block, so `source[body_span.bytes]` reproduces v1's `code` byte for byte
  (pinned by `BodyTextParityTest`, which compares against the v1 emitter directly) without
  reintroducing duplicated text. Absent when there is no body (abstract/interface methods).
  **Canonical note:** the keystone defines `get_method_body(sig)` as `module.source[callable.span.bytes]`,
  which is *not* v1's `code` semantics; the discrepancy is worth resolving in the canonical schema.
- **Two v1 counting bugs surfaced by the comparison, which v2 deliberately does not reproduce.** v1
  collected a callable's locals with a recursive `findAll(VariableDeclarator)`, so a **field declared in an
  anonymous class** was reported as a local of the enclosing method; v2 records it as a field of the
  anonymous class. And v1 filled a type's `initialization_blocks` recursively, counting a nested class's
  `static { … }` block **twice** — once on the nested class and once on its enclosing type; v2 counts it
  once. Where v2's totals are lower than v1's for these two metrics, v2 is the more accurate.

### D15 — Constructs L1 silently dropped, and the call-site facts v2 owes v1

A pre-merge review of the L1 emitter, read against the v1 entities field by field. Three constructs
produced no output at all, and one group of facts had lapsed relative to v1.

- **A compact constructor is not a `CallableDeclaration`.** `record Point(int x, int y) { Point {…} }`
  parses to a `CompactConstructorDeclaration`, which extends `BodyDeclaration` directly, so a member
  loop matching only `CallableDeclaration` dropped the record's canonical constructor entirely — body,
  call sites, metrics and all. Its **signature comes from the record components**, not from its own
  (empty) parameter list: `<init>(int, int)`, so a `new Point(1, 2)` site can join it. Its
  `parameters` stay empty, because the components are already on the type as `record_components` and
  duplicating them would double-count. A record that declares *no* constructor still emits none — L1
  reads declarations, and the implicit canonical constructor is not one.
- **An enum constant with a class body is an anonymous subclass, and gets a type.** `PLUS { int
  apply(…) {…} }` was unmodelled, so its overriding methods were absent from the output and L2 could
  resolve no call into or out of them. Constants are not in `getMembers()`, so they are walked
  separately, and each body becomes a member type keyed **`$enum$<NAME>`** — following the existing
  `$`-marks-synthetic convention (`$anon$0`, `<clinit>$0()`) and avoiding collision with a nested type
  that happens to share the constant's name. `base_types` is the enum itself, which is what the body
  specialises. A constant with no body gets no type.
- **A nested anonymous class in a field initializer was emitted twice.** The hoisting pass that finds
  anonymous classes outside any callable used an unfiltered `findAll`, so an anonymous class nested
  inside another one appeared both correctly nested *and* hoisted onto the enclosing type —
  double-counting its callables and metrics. `AstScopes.belongsDirectlyTo` was generalised from
  `BlockStmt` to any `Node` so a field declaration can be a scope boundary too.
- **Partial parses are announced.** JavaParser can return a usable AST *despite* problems, replacing
  what it could not parse with an error node. The module then looks structurally complete while the
  recovered region's call sites, locals and local types are simply absent — indistinguishable, to the
  strict conformance gate included, from a genuinely empty method. It is still emitted (dropping the
  file is worse) but now warns, as does a run that discovers zero modules.
- **Call sites carry the facts v1's `CallSite` carried.** D1 has the SDK reconstruct the old
  `.call_sites` surface from body nodes, so anything v1 exposed there must remain reconstructible.
  Four had lapsed: `method_name`, `return_type`, `comment` (from the call's parent statement), and the
  callee's accessibility. Accessibility is a **single enum** (`public`/`protected`/`private`/
  `package_private`) rather than v1's four booleans, whose `is_unspecified` conflated "unknown" with
  "package-private"; per D12 the key is simply absent when the callee is unresolvable. `return_type`
  prefers an enclosing cast's type over JavaParser's inference through it, matching v1, and is the
  instantiated type for a constructor call.
- **An initializer's `error_channel` is what it throws.** An initializer block cannot declare
  `throws`, so v1 carried `InitializationBlock.thrownExceptions`; v2's initializer callables had no
  counterpart. It is now populated from the throws in the block — scope-filtered like every other
  callable fact, and reaching *nested* throws, where v1 only scanned top-level statements.
- **`code_start_line` is dropped, extending D1.** It is exactly `body_span.start[0]` (D13), and where
  there is no body v1 had to assert the sentinel `-1` while absence already encodes "no fact" (D10).
  Nothing consumes it: the only reader, `GraphProjector`, is v1-only per D11.

### D16 — Generic declarations carry `type_parameters`

Neither v1 nor v2 recorded a type-parameter clause, which left generic signatures unreconstructable
from the emitted facts. A parameter declared `T` resolves to the bare spelling `T` — a type variable
has no qualified name — and `declaration` omits the clause because JavaParser's
`getDeclarationAsString` does. A consumer seeing `type: "T"` therefore had no way to learn what `T`
ranges over. `type_parameters` on both `type` and `callable` supplies exactly that, and nothing else:
this is a new fact, not a reshaping of an existing one.

- **Order is declaration order**, because a type argument binds to a parameter by position.
- **An unbounded parameter has no `bounds`.** The implicit `extends Object` is not written in the
  source, and fabricating it would make an unbounded parameter indistinguishable from one explicitly
  bounded by `Object` — the absence-is-no-fact rule of D10.
- **Bounds keep their type arguments** (`java.lang.Comparable<? super T>`), matching every other
  resolved type field; only *signatures* erase.
- **Genericity is keyed off `NodeWithTypeParameters`**, not an `instanceof` chain, which puts the
  language rule in one place: classes, interfaces, records, methods and constructors can be generic;
  enums, annotation types, anonymous classes and enum-constant bodies cannot. A compact constructor
  cannot declare its own either, and a generic record's belong to the record.
- **Signatures are unaffected**, so ids and call-site joins are untouched. A type-variable parameter
  erases to its bound on *both* sides of the join — declaration and resolved call site agree — so the
  addition is purely additive.

### D12 — L1 type resolution: library dependencies are always attempted

- **Dependency jars go on the solver's path.** L1 downloads the project's library dependencies before
  parsing and adds a `JarTypeSolver` per jar, so third-party types resolve to qualified names
  (`org.springframework.ui.Model`, `org.springframework.data.domain.Page<…Owner>`) instead of bare
  spellings. Skipping this made v2 resolution strictly worse than v1's; it is now verified on a real
  Spring application. A download failure only thins resolution — it warns, never fails the analysis.
- **Reflection is JRE-only.** A classpath-wide `ReflectionTypeSolver` resolves the *analyzer's own*
  dependencies (WALA, Guava, JavaParser, …) as if the analysed project depended on them, inventing
  qualified names that are simply wrong. Project types come from source roots, library types from the
  dependency jars, and reflection covers only the JDK.
- **Resolution-derived flags are absent when unknown.** `is_static_call` is a `Boolean`: when the
  callee cannot be resolved, staticness is genuinely unknown and the key is omitted rather than
  emitted as `false`, which would assert "not static". Syntactically evident flags
  (`is_constructor_call`) stay primitive.

### D11 — L1 conformance oracle and gate

- **Oracle:** emitted output is validated against an in-repo JSON Schema,
  `src/test/resources/schema/analysis.v2.schema.json`, because the SDK's v2 models do not exist yet.
  The schema is **strict** (`additionalProperties: false`) so a renamed or stray key fails the gate
  instead of reaching consumers, and it encodes the structural invariants directly: `can://java/` id
  prefixes, `line:col`/`@tag` body keys via `propertyNames`, relative `symbol_table` keys, and
  `[from, to)` byte spans. Replace it with the SDK models once they land.
- **The gate runs at two scales.** In-repo fixtures run in the default `test` task on every change.
  Whole real-world applications (the git-submodule fixtures) take minutes under full symbol
  resolution, so they are tagged `realworld`, excluded from `test`, and run via
  `./gradlew realWorldConformanceTest`. They are not optional — scale-dependent problems
  (unresolvable dependencies, unusual constructs, memory) only appear there.
- **v2 is opt-in for now.** `--schema v2` emits the canonical envelope; `v1` stays the default until
  the rest of the migration lands, so existing consumers are unaffected. Unsupported combinations
  (`-a > 1`, `--emit neo4j`, `--source-analysis`, `--target-files`, unknown `--schema`) exit non-zero
  with a clear message rather than silently emitting a different shape.

### D9 — Neo4j namespace: keep the `J_` relationship prefix
Existing convention (`J_CALLS`, …); dual-label `JSymbol` merge pattern retained.
`SchemaCatalog` takes a major bump (families rename v1→v2).

### Scope guard
The analyzer is a **pure graph provider**: it emits the CFG/PDG/SDG substrate and
stops. Slicing, taint, and reachability are **SDK queries** over the emitted graph
(`cldk-sdk-frontend`) — never analyzer features. No `taint_flows` section, no
sources/sinks policy in the analyzer.
