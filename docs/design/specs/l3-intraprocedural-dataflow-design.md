# Design Spec — codeanalyzer-java L3: intraprocedural dataflow (CFG / CDG / DDG), two engines over one contract

> **Status:** Proposed — detailed L3 design; elaborates §8 of the parent spec and **revises decision D5** (see §6). Parent: [`schema-v2-l3-l4-design.md`](./schema-v2-l3-l4-design.md). Live plan & status: [Epic codellm-devkit/.github#42](https://github.com/codellm-devkit/.github/issues/42) (sub-issue #183). Decisions ledger: [`.claude/SCHEMA_DECISIONS.md`](../../../.claude/SCHEMA_DECISIONS.md).

## 1. Summary

L3 completes each callable's `body{}` with statement nodes plus synthetic `@entry`/`@exit`, and lays three **syntactic, intra-callable** overlays on the callable: `cfg` (control flow), `cdg` (control dependence), and `ddg` (data dependence, `prov:["ssa"]`). No interprocedural analysis and no alias resolution — those are L4.

The parent spec (§8) sketches L3 with a single WALA engine and an AST fallback (D5). This spec refines that into **two interchangeable engines** that produce the *same* schema over the *same* node space, selected by `--l3-engine {ast,wala}`:

- **AST engine (JavaParser)** — the **default**. Build-free, exact source `line:col`, works on any Java version. The syntactic tier, consistent with L1 and L2's `declared` edges.
- **WALA engine** — opt-in. Reuses WALA's `SSACFG` / dominators / SSA def-use, and carries the WALA→source-statement mapping that L4 needs anyway.

Both pass one **conformance gate**; a **differential gate** cross-checks them where they must agree. Designing both — implementing later, one per PR, AST first — gives L3 a differential oracle (two independent implementations of one spec), mirroring how `declared` and `rta` coexist at L2.

**This increment is JSON-only.** The L3 Neo4j overlay is deferred until the Neo4j v2 base relabel (#182); `--emit neo4j` stays v1-only here.

## 2. Contract-impact triage

**Does this change schema v2 output?** Yes — it types the already-reserved `cfg`/`cdg`/`ddg` arrays on the callable and starts emitting the L3 body-node kinds (all already in the schema enum). Additive: `L2 ⊆ L3`.

| Change type | Analyzers | SDKs | Docs |
| --- | --- | --- | --- |
| L3 intraprocedural overlays (`cfg`/`cdg`/`ddg`) for Java, JSON projection | `codeanalyzer-java` (`syntactic_analysis` dataflow builders + schema defs) | `python-sdk` (later rung: L3 views, `get_program_dependency_graph`) | this spec + parent §8 |

## 3. Scope & non-goals

**In scope**
- Statement-body completion + `@entry`/`@exit`; `cfg`/`cdg`/`ddg` overlays, JSON projection.
- Two engines behind `--l3-engine {ast,wala}`, one shared contract, one conformance gate + a differential gate.
- Schema: tighten `cfg`/`cdg`/`ddg` (typed edge defs + `localId`) and an L3 schema-oracle test.

**Non-goals**
- **Interprocedural analysis** (L4): no `param_in`/`param_out`/`summary`, no SDG.
- **Alias / points-to resolution.** The DDG is *syntactic* — object-insensitive, field-sensitive up to `k`. Aliased def-use is L4's semantic DDG (`prov:["points-to"]`).
- **Neo4j overlay** this increment (gated on #182).
- **Slicing / taint** — SDK queries over the emitted graph, not analyzer features.

## 4. The shared contract (both engines emit exactly this)

### 4.1 Node identity

- Body nodes are keyed by **local id**: `line:col` for real source nodes, `@tag` for synthetics (`@entry`, `@exit`). This is L1's convention; L1 already emits `call` nodes, and L3 adds the rest.
- L3 body-node kinds (all already in the schema's `bodyNode.kind` enum): `statement`, `return`, `branch` (the `if`/conditional test), `loop` (the loop test), `switch` (the selector), plus the L1 `call`, plus synthetic `entry`/`exit` (one each per callable, **no span**).
- Overlay edge endpoints are these **local ids**, relative to the enclosing callable — **not** `can://` ids. (Contrast L2's `call_graph`, which is application-scope and cross-callable, so it uses full `can://` ids.)

### 4.2 Edge representations

All three overlays hang on the `callable`; `src`/`dst` are body-node local ids.

| Overlay | Shape | Notes |
| --- | --- | --- |
| `cfg` | `{src, dst, kind}` | `kind ∈ fallthrough \| true \| false \| switch_case \| loop_back \| exception \| return \| break \| continue` |
| `cdg` | `{src, dst}` | control dependence; no attributes |
| `ddg` | `{src, dst, var, prov:["ssa"]}` | `var` = k-limited access path; multiple ddg edges on one node are distinguished by `var`, not by endpoint identity |

**PDG** = `cdg ∪ ddg` over the same nodes (bookkeeping; no separate section). The backward-slice gate runs over this union — which is *why* all three overlays must share one `line:col` node space.

### 4.3 CFG well-formedness (engine-independent)

Exactly one `@entry` and one `@exit` per callable; every node reachable from `@entry` and reaching `@exit`. Multi-exit (multiple `return`/`throw`) normalized to the single `@exit`. An infinite loop with no path to `@exit` gets a synthetic edge to `@exit`, so post-dominance is defined.

### 4.4 Java lowering — the *semantics* both engines must realize

Each construct has a documented expected CFG shape **and a fixture**; the differential gate checks the two engines agree.

- **Checked exceptions** — an `exception` edge per `throws` type / per potentially-throwing call to the nearest enclosing handler, else `@exit`. Over-approximate (no exception-type refinement at L3).
- **`try`/`catch`/`finally`** — incl. **`finally` duplication** (finally reached on both normal and exceptional exit of the try) and **try-with-resources** (implicit `close()` in a synthetic finally).
- **`synchronized`** — monitor-enter/exit regions.
- **Static / instance initializer blocks** — their own CFGs (callable `kind:"initializer"`).
- **`switch`** — classic fall-through (`switch_case` edges) and arrow form (no fall-through).
- **Labeled `break`/`continue`** — edges to the labeled target (`break`/`continue` kinds).

### 4.5 DDG semantics (syntactic — identical for both engines)

- Def-use over **k-limited access paths** `base(.field | [*])*`, default `k=3` (`--graph-field-depth`); past `k`, collapse to `…*` and conservatively alias deeper. Array indices collapse to `[*]` (index-insensitive). Bases tagged `local | parameter | receiver (this) | field | captured`.
- **Object-insensitive, field-sensitive.** Access paths are matched **syntactically** by base spelling: `o1.f` and `o2.f` are distinct; a base reassignment (`o = …`) **prefix-kills** `o.*`. Precise for same-object same-spelling (`this.f`, unaliased locals); it deliberately **does not resolve aliasing**, so it can both miss aliased def-use and keep a stale def across an aliased write (illustrated in §4.5.1). Those are L4's job (points-to). The gate's expected sets are computed under *these* semantics, and a fixture pins an aliased-field case so the imprecision is documented, not discovered.
- Object/allocation-site precision is **not** an L3 option: allocation-site tracking *is* a points-to analysis (0-CFA's heap abstraction), scoped to L4. Flag for #184 — reconsider whether L4's default precision should be `0-cfa` (allocation-site) rather than `rta` (type-based).

#### 4.5.1 Limitation — intraprocedural DDG without points-to (affects both engines)

The L3 DDG resolves heap (field/array) def-use by **syntactic access-path spelling**, because separating definitions and uses that reach the *same object through different references* requires knowing what each reference points to — a points-to analysis, which is L4. This is a property of the **level, not the engine.** *Scalar* (local) def-use is exact at L3 either way; the limitation is specifically about **heap locations reached through aliases**. The AST engine matches access paths syntactically; the WALA engine — whose SSA gives precise *scalar* def-use for free — still cannot disambiguate *heap* locations at L3 without `ModRef`/pointer analysis, so under the shared contract it resolves field def-use the same syntactic way. **Both engines therefore produce identical heap-aliasing errors.**

Two illustrations, where `a` and `b` name the same object (`class Box { int f; }`):

**(1) Missed dependence (false negative).**

```java
Box a = new Box();
Box b = a;          // b aliases a
a.f = compute();    // def of a.f     ← D
int x = b.f;        // use of b.f     ← U   (reads what D wrote)
```

The real dependence D → U flows through the alias, but syntactically `a.f ≠ b.f`, so **no `ddg` edge D→U is emitted**.

**(2) Stale def kept *and* real def missed (false positive + false negative at once).**

```java
Box a = new Box();
Box b = a;          // b aliases a
a.f = 1;            // def1 of a.f    ← D1
b.f = 2;            // overwrites a.f through the alias   ← D2
int x = a.f;        // use of a.f     ← U   (truly reads 2)
```

`b.f = 2` does not kill `a.f` (different spelling), so D1 still reaches U → a **spurious** edge D1→U; and the true reaching def D2→U is **missed** (different spelling). Array element def-use is worse still — indices collapse to `[*]`, so `arr[i]` and `arr[j]` are one location.

**What L4 fixes.** Pointer analysis (L4, `--precision {rta,0-cfa,0-1-cfa}`) knows `a` and `b` share an allocation and emits the correct heap def-use as **additional** `ddg` edges tagged `prov:["points-to"]`; L3's `prov:["ssa"]` edges are retained (weak-update posture), preserving `L3 ⊆ L4`. The syntactic edges are labelled `ssa` precisely so a consumer can distinguish the two tiers and treat the L3 heap edges as best-effort. (Def-use that flows through a *callee* is likewise invisible at L3 — that interprocedural dimension is also L4/SDG.)

### 4.6 Determinism & monotonicity

- Ids assigned by sorted source position; collect-then-sort; never emit during parallel fan-out; `-j N` byte-identical to `-j 1`.
- `L1 ⊆ L2 ⊆ L3`: L3 adds only the new overlay keys and body statement nodes; everything L1/L2 emitted is unchanged (modulo the sanctioned `callee` backfill).

## 5. The two engines

### 5.1 Engine A — JavaParser / AST (default)

Pure source analysis; no build, no WALA. Three passes on the callable's statement AST.

- **A.1 Statement-body + CFG.** Walk the callable's body AST; emit a body node per statement at its `line:col` (kind by construct); build CFG edges from structured control-flow semantics — sequential → `fallthrough`; `if` → `true`/`false`; loops → body + `loop_back` + exit; `switch` → `switch_case` (classic) or arrow edges; `return`/`throw` → `@exit`; labeled `break`/`continue` → the loop/label target; `try`/`catch`/`finally` with finally duplication; try-with-resources synthetic close; `synchronized` regions; exceptional edges per §4.4. Columns come straight from JavaParser spans — exact, no recovery.
- **A.2 CDG.** Post-dominator tree rooted at `@exit` (Cooper–Harper–Kennedy); control dependence via Ferrante–Ottenstein–Warren; emit `cdg` edges. Infinite loops get the synthetic `@exit` edge first, so post-dominance is total.
- **A.3 DDG.** Classic monotone reaching-definitions worklist: per-statement gen/kill over the k-limited access-path domain, iterated to a fixpoint over the CFG; emit a `ddg` edge (with `var`) per def that may reach a use. Prefix-kill on base reassignment; `[*]` for arrays; `prov:["ssa"]`. Operates directly on statement nodes, so endpoints are native `line:col` — no mapping.

**Properties:** build-free; exact `line:col`; any Java version; immune to WALA coverage/shadowing and within-line ambiguity. **Cost:** three hand-written passes (textbook, bounded, testable).

### 5.2 Engine B — WALA (opt-in)

Reuses WALA IR; needs a build (like L2 `rta`) and inherits WALA's coverage (the #181 dependency-shadow fix applies) and Java-version limits.

- **B.1 The WALA→source-statement mapping (the crux — how `line:col` is recovered).** WALA gives an instruction only a *line* (`IMethod.getSourcePosition`; `getFirstCol() == -1`), never a column — so the WALA engine **never synthesizes a column**. Instead it maps each instruction to an L1/AST statement node and adopts *that node's* exact `line:col` (already computed at L1) as the edge endpoint. The mapping heuristics, in order:
  1. **Line-cover match** — candidate statements whose L1 span covers the instruction's line.
  2. **Content disambiguation** (several candidates — multiple statements per line is common) — match the SSA instruction's operation (the variable it defs/uses, the method it invokes) against each candidate's AST and pick the match. Uses semantics, not the missing column.
  3. **No source position** (SSA phi/pi, compiler-introduced temporaries, other synthetic instructions with no line) — attribute to the governing block's statement, or drop when it is a pure SSA artifact with no source counterpart. This is the case the parent spec's risk #1 flags.
  4. **Still ambiguous** — attach to the innermost covering statement and log an over-approximation count.

  This mapping is the component L4's semantic DDG reuses, and the sole source of the within-line ambiguity risk — contained by steps 3–4 plus the differential gate against the exact AST engine.
- **B.2 CFG / CDG / DDG.** CFG from WALA `SSACFG` + `ISSABasicBlock`, each instruction projected to its statement via B.1, normalized to one `@exit`, edge `kind` recovered from block terminators. CDG from WALA dominators/post-dominators on the reverse CFG. DDG from WALA SSA def-use (`DefUse`), each def/use mapped via B.1, `var` from the SSA value's source variable + access path, `prov:["ssa"]`; heap def-use beyond scalars is **not** resolved (no points-to) — same syntactic contract as Engine A.

**Properties:** reuses mature WALA passes; node-derivation aligned with L4. **Cost:** build dependency; columns recovered via B.1; within-line ambiguity; WALA coverage/version limits.

### 5.3 Trade-offs

The two engines are interchangeable *at the contract* but not equivalent in how they get there. The differences below are why `ast` is the default and `wala` is still worth building.

| Dimension | Engine A — AST (JavaParser) | Engine B — WALA |
| --- | --- | --- |
| **Build required** | **None** — pure source; runs on unbuildable / partially-buildable projects | **Yes** — needs compiled classes on the classpath (like L2 `rta`); degrades to a clear error, leaving L2 intact |
| **Node identity (`line:col`)** | **Native, exact** — straight from JavaParser spans | **Recovered** via B.1 (line + content); within-line ambiguity on multi-statement lines; no-source-position instructions folded/dropped |
| **Edge coverage** | Every **parsed** callable | Only callables in WALA's **analysis scope** (compiled, admitted by the scope loader); others get no overlay |
| **CFG fidelity** | **Source-structural** — matches what is written, and the schema's source `line:col` | **Execution-faithful** — reflects the compiler's actual lowering (short-circuits, desugared enhanced-for / TWR / string-concat, synthetic blocks); truer to what runs, but must be projected back to source shape |
| **Scalar (local) DDG** | Hand-rolled reaching-defs — **correctness is ours to prove** (scoping/shadowing) | SSA def-use is **exact by construction** — mature, high-confidence |
| **Heap / aliased DDG** | Syntactic, object-insensitive (§4.5.1) | Syntactic, object-insensitive (§4.5.1) — **parity**; both need L4 points-to |
| **Maintenance** | Three **textbook passes we own** (no analysis engine invoked) | Mature passes; the one incremental cost is the **bespoke B.1 mapping** — WALA itself is already a dependency (v1 and L2 `rta`) |
| **Speed / cost** | Fast, per-file, no build or whole-program IR | Build + IR construction; **reuses L2 `rta`'s scope/IR** when `rta` already ran, otherwise builds its own |
| **L4 alignment** | Not reusable for L4 (L4 is WALA-based) | **Same IR + B.1 mapping L4 needs** — the proving ground for L4's SDG/points-to |
| **Determinism** | Straightforward (source order) | Achievable with fixed block-iteration / SSA value-number order |

**Why AST is the default.** L3's deliverable is *source-faithful* `line:col` overlays; the AST engine produces them with exact identity, no build, on any parsed source, and covers every callable. Its one real risk — the correctness of the hand-written CFG lowering — is contained by a documented rule and a fixture per construct (§4.4, §10). It is the better *product* default.

**Why WALA is still built.** Two payoffs the AST engine can't give. (1) **Confidence** — SSA scalar def-use and bytecode-derived control flow are execution-faithful and mature, so the WALA engine is an independent oracle that keeps the hand-rolled AST passes honest (the differential gate, §7). (2) **L4 readiness** — L4's SDG *must* use WALA, and the B.1 instruction→statement mapping is exactly the machinery L4 reuses; building it at L3 de-risks L4 rather than being throwaway.

**Where they diverge (and it's fine).** The two are held to agreement only on the defined subset (§7); the pinned divergences — exceptional-edge shape and within-line attribution — are the *expected* cost of source-structural vs. execution-faithful views, asserted as divergences rather than bugs. On heap/aliased DDG they are equal and equally limited (§4.5.1) until L4.

## 6. Design decisions

Recorded in [`.claude/SCHEMA_DECISIONS.md`](../../../.claude/SCHEMA_DECISIONS.md). D25–D28 are new; **D28 revises D5** of the parent spec.

| # | Decision | Choice | Rationale / divergence |
| --- | --- | --- | --- |
| D25 | L3 edge endpoints | **Body-node local ids** (`line:col`/`@tag`), not `can://` ids | Overlays are intra-callable; local ids match the `body{}` keys. Grounded in the keystone's own `ddg` example. |
| D26 | L3 engine posture | **Two interchangeable engines**, `--l3-engine ast\|wala`, `ast` default, differential gate | Two implementations of one contract → a differential oracle, as `declared`/`rta` are at L2. |
| D27 | DDG precision | **Syntactic** — object-insensitive, field-sensitive, k-limited access paths | Aliasing deferred to L4 (`prov:["points-to"]`); allocation-site precision is L4's 0-CFA. `prov:["ssa"]` labels the tier. |
| D28 | CFG/DDG derivation (**revises D5**) | AST engine builds cfg/cdg/ddg on the JavaParser AST (exact `line:col`, build-free) as the **default**; WALA engine via `SSACFG` + the instruction→statement mapping is **opt-in** | D5 made WALA the engine and AST the fallback. Because body nodes are keyed `line:col` and the identity gate requires a real column — which WALA-over-bytecode lacks — nodes must come from the AST regardless; only the *edges* can differ by engine, so the exact, build-free AST engine is the natural default. `L3 ⊆ L4` still holds: L4 *adds* `points-to` edges over the same nodes, never removing the `ssa` ones. |

## 7. Engine selection & the differential gate

- `--l3-engine {ast, wala}`, **default `ast`**. Exactly one engine per run; both emit the identical schema, so a consumer can't tell which produced a payload except by the run's flags.

### 7.1 Alternatives, not an overlay — and why

The engines are **used alternatively** (one per run), not unioned into one graph the way L2 overlays `declared` and `rta`. That precedent deliberately does **not** transfer, for three reasons:

- **CFG/CDG cannot be unioned.** The two produce *different graph shapes* — source-structural vs execution-faithful lowering. Merging their edges yields a graph that satisfies *neither* engine's well-formedness (single `@entry`/`@exit`, reachability; §4.3) — not a richer CFG, a malformed one. One engine's CFG must be authoritative per run.
- **A DDG union would be redundant, not complementary.** Both engines implement the *same* syntactic, object-insensitive DDG semantics (§4.5); their `ddg` sets are *meant* to coincide, and where they differ it is engine imprecision (the pinned divergences below), not extra signal. Contrast L2, where `declared` (static resolution) and `rta` (dynamic-dispatch fan-out) are genuinely complementary — which is why L2 *is* an overlay.
- **Provenance is the tell.** At L3 every edge is `prov:["ssa"]` regardless of engine — the engine is an *implementation* choice, not an analysis tier. Overlay/union is how different *tiers* share one list: `declared`+`rta` at L2, and the real cross-level overlay `ssa`+`points-to` at L4 that L3's edges participate in. Keeping the engine out of `prov` is what keeps `L2 ⊆ L3 ⊆ L4` a clean tier union.

A best-of-both *composition* — WALA edges per-callable where it analysed one, AST edges for callables outside WALA's scope — is a conceivable future selection policy, but it is a per-callable **fallback** (still one engine's shape per callable), not an edge-level overlay. Out of scope here.

### 7.2 Differential gate

On the fixtures, run both engines and assert they agree where they must — CFG node set + reachability, `cdg` edges, and `ddg` edges on constructs where the syntactic semantics coincide. **Pinned, documented divergences:** exceptional-edge shape (WALA infers from bytecode handlers; AST over-approximates from `throws`/throwing calls), and within-line attribution on multi-statement lines (AST exact; WALA via B.1). The AST engine is the reference. The gate *uses* the divergence as a cross-check rather than merging it away.

## 8. CLI contract

On the existing Picocli app: `-a 3` emits L3 under `--schema v2`; `--l3-engine {ast,wala}` (default `ast`); `--graph-field-depth <k>` (default 3). The `ast` engine needs no build; the `wala` engine builds (like `rta`) and degrades to a **clear error** (not a crash) when the build/scope is unavailable, leaving L2 intact. `--target-files` downgrades to L1; `--emit neo4j` stays v1-only (this increment). Unrecognized flag values exit non-zero; stdout carries only JSON.

## 9. Schema additions

The oracle already anticipates L3 (`max_level ≤ 4`; `bodyNode.kind` includes the L3 kinds; `cfg`/`cdg`/`ddg` present as arrays). This increment **tightens** them, mirroring how L2 added `callEdge`:

- `cfgEdge` — required `src`, `dst`, `kind`; `src`/`dst` are `localId` (`^\d+:\d+$` or `^@[a-z_]+$`); `kind` a closed enum (the nine of §4.2). `additionalProperties:false`.
- `cdgEdge` — required `src`, `dst` (`localId`). `additionalProperties:false`.
- `ddgEdge` — required `src`, `dst`, `var`, `prov`; `prov` an array of `enum ["ssa"]` at L3 (L4 adds `points-to`), `minItems:1`. `additionalProperties:false`.
- a `localId` def; the `cfg`/`cdg`/`ddg` arrays `$ref` their edge defs.
- an `L3SchemaOracleTest` asserting each way a plausible L3 payload can be wrong (bad `kind`, non-local endpoint, missing `var`, empty `prov`, stray key), written before the producer.

## 10. Testing & gates

Per-level conformance gate is a hard gate. A fixture project exercises each construct with specific-value assertions. **Every gate item runs against both engines.**

1. **CFG gate** — every non-synthetic node has a real span; exactly one `@entry`/`@exit`; every node reachable from `@entry` and reaching `@exit`; each Java construct emits its documented edges including `exception`.
2. **Dominance gate** — post-dominator tree rooted at `@exit`; hand-computed control deps for `if`/loop/early-return match the `cdg` edges exactly.
3. **PDG backward-slice gate** — reverse reachability over `cdg ∪ ddg` of a named variable at a named line equals a hand-computed node set **exactly**, under the syntactic DDG semantics. Cases: loop-carried, shadowed scope, and an **aliased-field** case (pins the object-insensitive imprecision).
4. **`L2 ⊆ L3`** — analyse one fixture at both levels; L3 contains everything L2 emitted plus the new overlay keys and statement nodes.
5. **Determinism** — `-j N` byte-identical to `-j 1`; ids stable across runs.
6. **Differential gate** — Engine A vs Engine B agree on the defined subset; pinned divergences asserted as such. Realworld-tagged (the WALA engine builds), like L2's `rta` end-to-end test.

Slice/taint gates are **frontend** (SDK) gates, not analyzer gates.

## 11. Decomposition & release

Sequenced so the default (AST) engine — the reference — lands and can ship first; the WALA engine and the differential gate follow. One commit per step, TDD.

1. Schema defs + `L3SchemaOracleTest` (tighten `cfg`/`cdg`/`ddg`; `localId`; edge defs).
2. AST engine — statement-body + CFG (Java lowering rules + per-construct fixtures).
3. AST engine — CDG (post-dominance).
4. AST engine — DDG (reaching-defs, k-limited access paths).
5. L3 conformance gate (CFG / dominance / PDG-slice) on the AST engine; `L2 ⊆ L3`; determinism.
6. WALA engine — the B.1 mapping, then CFG/CDG/DDG on it.
7. Differential gate (A vs B; pin divergences).
8. Spec amendments + ledger (parent §8; `SCHEMA_DECISIONS` D25–D28).

L3 can ship/tag (AST engine, steps 1–5) before the WALA engine (6–7). The Neo4j overlay is a separate follow-up after #182. Live tracking is the epic, not this spec.

## 12. Risks & open questions

| Risk | Mitigation |
| --- | --- |
| AST-CFG lowering correctness (finally duplication, exceptions, TWR) | A documented rule + a fixture per construct; the CFG gate |
| WALA within-line mapping ambiguity (B.1) | Content-based disambiguation + innermost-statement fallback + the differential gate against the exact AST engine |
| Two engines double the maintenance | Shared contract + shared gate; the WALA engine is opt-in and can lag |
| Syntactic DDG imprecision surprises consumers | `prov:["ssa"]` labels it; an aliased-field fixture pins it; L4 adds the sound `points-to` overlay |
| WALA engine inherits build/coverage limits | Same posture as L2 `rta` (degrade to a clear error; L2 intact); the #181 scope-shadow fix applies |

## 13. References

- Parent spec: [`schema-v2-l3-l4-design.md`](./schema-v2-l3-l4-design.md) (L3 = §8; engine decision = D5, revised here as D28)
- Decisions ledger: [`.claude/SCHEMA_DECISIONS.md`](../../../.claude/SCHEMA_DECISIONS.md)
- L2 comparison (WALA scope-shadow fix, `rta` posture): [`../notes/l2-v1-v2-comparison.md`](../notes/l2-v1-v2-comparison.md)
- Epic & sub-issue: [codellm-devkit/.github#42](https://github.com/codellm-devkit/.github/issues/42), codeanalyzer-java #183
- Keystone L3 method: `cldk-devtools/…/level-3-intraprocedural-dataflow.md`
- Reference pilot: `codeanalyzer-python` `main` (`codeanalyzer/dataflow/*`)
