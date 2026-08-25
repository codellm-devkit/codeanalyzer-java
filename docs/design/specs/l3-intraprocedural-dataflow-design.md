# Design Spec — codeanalyzer-java L3: intraprocedural dataflow (CFG / CDG / DDG), two engines over one contract

> **Status:** AST engine implemented & merged (#183, PR #195); **WALA engine in progress (#194)** — its §5.2 (Engine B) and §7 (differential gate) guide that work. Elaborates §8 of the parent spec and **revises decision D5** (see §6). Parent: [`schema-v2-l3-l4-design.md`](./schema-v2-l3-l4-design.md). Live plan & status: [Epic codellm-devkit/.github#42](https://github.com/codellm-devkit/.github/issues/42) (sub-issues #183, #194). Decisions ledger: [`.claude/SCHEMA_DECISIONS.md`](../../../.claude/SCHEMA_DECISIONS.md).

## 1. Summary

L3 completes each callable's `body{}` with statement nodes plus synthetic `@entry`/`@exit`, and lays three **intraprocedural** overlays on the callable: `cfg` (control flow), `cdg` (control dependence), and `ddg` (data dependence). **The L3/L4 line is intraprocedural vs interprocedural:** L3 stays within one callable; the interprocedural reach — `param_in`/`param_out`/`summary`, the SDG — is L4. Within that intraprocedural scope the two engines differ in DDG **precision**: the AST engine is object-insensitive syntactic (`prov:["ssa"]`); the WALA engine adds heap/field du-pairs resolved by the reused L2 RTA pointer analysis (`prov:["points-to"]`).

The parent spec (§8) sketches L3 with a single WALA engine and an AST fallback (D5). This spec refines that into **two engines used as alternatives** (one per run) that produce the *same* schema over the *same* node space, selected by `--l3-engine {ast,wala}`:

- **AST engine (JavaParser)** — the **default**. Build-free, exact source `line:col`, works on any Java version. Object-insensitive syntactic DDG, consistent with L1 and L2's `declared` edges.
- **WALA engine** — opt-in. Reuses the L2 `rta` call graph + pointer analysis and runs WALA's native per-method **PDG** (`SSACFG`, dominance-frontier control dependence, intraprocedural scalar + heap data dependence), plus the WALA→source-statement mapping (§5.2 B.1) that L4 reuses.

Both pass one **conformance gate**; a **differential gate** cross-validates `cfg` and `cdg` (which must agree) and turns the DDG difference into an **empirical delta report** — the engines compute DDG at different precision, so their DDGs are *compared* to guide engine selection, not asserted equal (§7.2). Designing both — implementing later, one per PR, AST first — gives L3 two independent implementations of one spec, mirroring how `declared` and `rta` coexist at L2.

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
- **Interprocedural analysis** (L4) — the L3/L4 boundary: no `param_in`/`param_out`/`summary`, no SDG, no def-in-callee-reaching-use-in-caller reach. Both engines' DDGs stay strictly within one callable.
- **Points-to precision above RTA.** The WALA engine reuses the L2 **RTA** pointer analysis for intraprocedural heap du-pairs; sharpening to 0-CFA / 0-1-CFA (`--precision`, D6) is L4. The AST engine does no alias resolution at all — object-insensitive syntactic, field-sensitive up to `k` (its documented limitation, §4.5.1).
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
| `ddg` | `{src, dst, var, prov}` | `prov ∈ ["ssa"] \| ["points-to"]` — `ssa` for scalar/syntactic du-pairs, `points-to` for the WALA engine's RTA-resolved heap du-pairs (§4.5); `var` = k-limited access path; multiple ddg edges on one node are distinguished by `var`, not by endpoint identity |

**PDG** = `cdg ∪ ddg` over the same nodes (bookkeeping; no separate section). The backward-slice gate runs over this union — which is *why* all three overlays must share one `line:col` node space.

### 4.3 CFG well-formedness (engine-independent)

Exactly one `@entry` and one `@exit` per callable; every node reachable from `@entry` and reaching `@exit`. Multi-exit (multiple `return`/`throw`) normalized to the single `@exit`. An infinite loop with no path to `@exit` gets a synthetic edge to `@exit`, so post-dominance is defined.

### 4.4 Java lowering — the *semantics* both engines must realize

Each construct has a documented expected CFG shape **and a fixture**; the differential gate checks the two engines agree.

- **Checked exceptions** — an `exception` edge per `throws` type / per potentially-throwing call to the nearest enclosing handler, else `@exit`. Over-approximate (no exception-type refinement at L3).
- **`try`/`catch`/`finally`** — the `finally` runs on **every** exit from the try: normal completion, each catch, and abrupt exits (`return`/`break`/`continue`) and uncaught `throw`s that leave the region. It is modeled as a **single `finally` node** whose completion fans out to the *union* of every exit's continuation (a sound over-approximation); abrupt exits inside the try are rerouted through the enclosing `finally`(ies), innermost first. **Try-with-resources** analyses like a plain try (the implicit `close()` has no source position, so no distinct node).
- **`synchronized`** — monitor-enter/exit regions.
- **Static / instance initializer blocks** — their own CFGs (callable `kind:"initializer"`).
- **`switch`** — classic fall-through (`switch_case` edges) and arrow form (no fall-through).
- **Labeled `break`/`continue`** — edges to the labeled target (`break`/`continue` kinds).

#### 4.4.1 `finally` modeling and engine parity

The single-node fan-out is not merely the AST engine's compromise — it is the fixed point *both* engines reach under `line:col` identity. `javac` compiles `finally` by **duplicating** the block along each exit path (plus a catch-all handler that runs it and rethrows), so WALA's *bytecode* CFG holds several precise, single-successor copies. But the WALA engine projects instructions back to source `line:col` (§5.2 B.1), and every copy shares the original source positions — so the copies **collapse to one node** and their edges merge, yielding the same union fan-out. Neither engine can be more precise than the other on `finally` at source granularity, so the differential gate (§7.2) can *require* they agree on the `finally` node and its reachability. The one expected difference is **exception-edge density**: WALA's catch-all lets any instruction throw into the `finally`, whereas the AST engine only edges from statements that syntactically contain a call/allocation — a pinned divergence.

The precise per-path model (distinct `finally` copies, each with one successor) is recoverable **only if the one-node-per-`line:col` invariant is relaxed** — e.g. suffixed ids like `@line:col#return` — at the cost of complicating DDG, CDG, and every consumer that joins on body-node ids. Recorded as a deliberate non-choice: doable if a future need for path-precise `finally` outweighs the identity simplicity.

### 4.5 DDG semantics (intraprocedural; the two engines differ by precision)

L3's DDG is the **intraprocedural** def-use relation of a single method. (The L3/L4 line is intraprocedural vs interprocedural — §9; L4 adds the def-in-callee-reaching-use-in-caller reach via `param`/`summary` edges.) The two engines compute the intraprocedural DDG by **different analyses** and are used as **alternatives** — one per run — so their DDGs are *not* required to agree; §7.2 turns their difference into an empirical report that guides engine selection.

- **AST engine — object-insensitive syntactic.** Def-use over **k-limited access paths** `base(.field | [*])*` (default `k=3`, `--graph-field-depth`; past `k` collapse to `…*`; array indices collapse to `[*]`, index-insensitive; bases tagged `local | parameter | receiver | field | captured`), matched by **spelling**: `o1.f` and `o2.f` are distinct, a base reassignment prefix-kills `o.*`. Build-free and exact on scalars and unaliased locals, but **unsound under aliasing** (§4.5.1). Edges carry `prov:["ssa"]`.
- **WALA engine — RTA points-to (sound, over-approximate).** Scalar def-use from SSA (`prov:["ssa"]`) plus **heap/field du-pairs from the per-method WALA PDG over the reused L2 RTA pointer analysis** (`prov:["points-to"]`). RTA is **type-based**, not allocation-site-based, so the heap DDG is **sound but imprecise**: it recovers *every* real aliased du-pair (no misses — §4.5.1) but gives one abstract cell per type, so it conflates distinct objects of the same type and does only **weak updates** (no kills) — emitting spurious du-pairs between unrelated same-type accesses. Precise kills and allocation-site separation come from L4's higher-precision PA (`--precision 0-cfa`, D6). Needs a build (§5.2).

`prov` names **how** a du-pair was derived — `ssa` (SSA/syntactic) or `points-to` (pointer-analysis heap) — **not the level**: both appear at L3, and `points-to` recurs at L4. Consumers must read the level from the overlay / `max_level`, never infer it from `prov`.

`L3 ⊆ L4` is **intraprocedural ⊆ intraprocedural + interprocedural**: L4 keeps every L3 edge and adds the interprocedural reach (and may raise pointer-analysis precision via `--precision {rta,0-cfa,0-1-cfa}`, D6). A per-method PDG's heap deps are *informed* by callees' `ModRef` summaries (what a call site mods/refs), but the **edges stay within the method** (L3); the interprocedural *propagation* is what L4 turns on.

#### 4.5.1 The AST engine's object-insensitive limitation (which the WALA engine mitigates)

The AST engine matches heap access paths by spelling, so it cannot separate def-use that reaches the *same object through different references*. Where `a` and `b` name the same object (`class Box { int f; }`):

**(1) Missed dependence.**

```java
Box a = new Box();
Box b = a;          // b aliases a
a.f = compute();    // def of a.f     ← D
int x = b.f;        // use of b.f     ← U   (reads what D wrote)
```

Syntactically `a.f ≠ b.f`, so the AST engine emits **no `ddg` edge D→U**.

**(2) Stale def kept *and* real def missed.**

```java
Box a = new Box();
Box b = a;          // b aliases a
a.f = 1;            // def1 of a.f    ← D1
b.f = 2;            // overwrites a.f through the alias   ← D2
int x = a.f;        // use of a.f     ← U   (truly reads 2)
```

`b.f = 2` does not kill `a.f` (different spelling), so the AST engine keeps a **spurious** D1→U and **misses** the real D2→U; array indices collapse to `arr[*]`, conflating `arr[i]`/`arr[j]`.

**The WALA engine mitigates but does not fully resolve these — because RTA is type-based.** RTA knows a `Box.f` store may reach a `Box.f` load, so it recovers the **missed dependence** of (1), D→U — the soundness win. But RTA gives *one* abstract cell per type, so it cannot separate `a` from an unrelated `Box c`, and it does only **weak updates** (no kills): in (2) it emits the real D2→U *and* keeps the stale D1→U, and it also connects `a.f` to a wholly unrelated `c.f`. So the WALA heap DDG is **sound (no missed aliased du-pairs) but over-approximate (spurious same-type du-pairs, no precise kills)**. Eliminating the stale/spurious edges needs strong updates and allocation-site precision — L4's 0-CFA (D6). This is precisely the *two-directional* difference §7.2's empirical DDG comparison surfaces: the AST engine misses real aliased pairs but emits none spuriously, while the WALA engine catches all real pairs but adds spurious same-type ones — **neither dominates**, which is why they are precision alternatives, not redundant oracles. Interprocedural du-pairs — a def in a callee reaching a use in the caller — are invisible to *both* at L3; that reach is L4/SDG.

### 4.6 Determinism & monotonicity

- Ids assigned by sorted source position; collect-then-sort; never emit during parallel fan-out; `-j N` byte-identical to `-j 1`.
- `L1 ⊆ L2 ⊆ L3`: L3 adds only the new overlay keys and body statement nodes; everything L1/L2 emitted is unchanged (modulo the sanctioned `callee` backfill).

## 5. The two engines

### 5.1 Engine A — JavaParser / AST (default)

Pure source analysis; no build, no WALA. Three passes on the callable's statement AST.

- **A.1 Statement-body + CFG.** Walk the callable's body AST; emit a body node per statement at its `line:col` (kind by construct); build CFG edges from structured control-flow semantics — sequential → `fallthrough`; `if` → `true`/`false`; loops → body + `loop_back` + exit; `switch` → `switch_case` (classic) or arrow edges; `return`/`throw` → `@exit`; labeled `break`/`continue` → the loop/label target; `try`/`catch`/`finally` with a single `finally` node and abrupt exits rerouted through it (§4.4.1); try-with-resources; `synchronized` regions; exceptional edges per §4.4. Columns come straight from JavaParser spans — exact, no recovery.
- **A.2 CDG.** Post-dominator tree rooted at `@exit` (Cooper–Harper–Kennedy); control dependence via Ferrante–Ottenstein–Warren; emit `cdg` edges. Infinite loops get the synthetic `@exit` edge first, so post-dominance is total.
- **A.3 DDG.** Classic monotone reaching-definitions worklist: per-statement gen/kill over the k-limited access-path domain, iterated to a fixpoint over the CFG; emit a `ddg` edge (with `var`) per def that may reach a use. Prefix-kill on base reassignment; `[*]` for arrays; `prov:["ssa"]`. Operates directly on statement nodes, so endpoints are native `line:col` — no mapping.

**Properties:** build-free; exact `line:col`; any Java version; immune to WALA coverage/shadowing and within-line ambiguity. **Cost:** three hand-written passes (textbook, bounded, testable).

### 5.2 Engine B — WALA (opt-in)

Reuses the L2 `rta` machinery — the same `ScopeUtils.createScope` → CHA → RTA **call graph + pointer analysis** — and needs a build (the #181 dependency-shadow fix applies; inherits WALA's coverage and Java-version limits). Runs post-build.

- **B.1 The WALA→source mapping (node identity only).** WALA recovers an instruction's source **line** reliably (the bytecode line table, via `IBytecodeMethod.getBytecodeIndex` + `getLineNumber`) but **no column** — so the WALA engine adopts the column from the matched AST statement, never synthesizes one. Per instruction: (1) **line-cover** — candidate statements whose L1 span covers the line; (2) several candidates → **content disambiguation**, matching the SSA op (invoked method / defined variable) against each candidate's AST; (3) **no source position** (SSA phi/pi, compiler temporaries) → attribute to the governing block's statement, or drop a pure SSA artifact; (4) **still ambiguous** (contentless co-located statements on one line) → emit the node at the line with a **sentinel column `line:0`** (a valid `localId`, distinct from any real statement) rather than mis-attributing, and log the count. (All spike-confirmed: lines reliable, columns never in bytecode.) B.1 is the *only* place the AST is consulted, and the component L4 reuses.
- **B.2 CFG / CDG / DDG — native WALA.** **CFG** from `SSACFG` + `ISSABasicBlock`, each instruction projected to its node via B.1, normalized to one `@exit`, edge `kind` from block terminators. (`javac`'s duplicated `finally` copies share a source line and collapse to the one `finally` node the AST engine also produces — §4.4.1.) **CDG and DDG** from the per-method WALA **PDG** over the reused RTA call graph + pointer analysis. The PDG labels every edge with a `Dependency`, which maps onto the schema directly: `CONTROL_DEP`→`cdg`; `DATA_DEP`→`ddg` scalar (`prov:["ssa"]`); `HEAP_DATA_DEP`→`ddg` heap (`prov:["points-to"]`). Only `NORMAL→NORMAL` edges are kept; the `param`/heap-interface statements are the method's SDG boundary and belong to L4.

  Two spike-confirmed mechanics keep it intraprocedural and tractable. **(a) Empty-defaulting global mod/ref maps.** The PDG constructor takes per-node `mod`/`ref` maps; WALA's *global* `ModRef` closure over a JDK-inclusive call graph is L4-scale (it OOMs at 4 GB). Passing maps that return an empty (non-null-backed) location set for every node drops the interprocedural heap-param statements while the PDG still computes each method's heap du-pairs from its **own per-instruction `getMod`/`getRef`** — exactly the intraprocedural subset L3 wants. **(b) Lazy heap edges.** `HEAP_DATA_DEP` edges materialize only when the *unlabeled* `getSuccNodes(N)`/`getPredNodes(N)` is called (not during `populate()` and not via the labeled accessor), so the builder primes every node once before reading labeled edges. The dependence analysis itself is entirely WALA's own.

**Properties:** genuinely native (WALA's dominance-frontier CDG and PDG data dependence); sound heap DDG at RTA precision; the same PDG L4's SDG extends interprocedurally. **Cost:** build + RTA; the B.1 node-identity mapping; WALA coverage/version limits.

### 5.3 Trade-offs

The two engines are interchangeable *at the contract* but not equivalent in how they get there. The differences below are why `ast` is the default and `wala` is still worth building.

| Dimension | Engine A — AST (JavaParser) | Engine B — WALA |
| --- | --- | --- |
| **Build required** | **None** — pure source; runs on unbuildable / partially-buildable projects | **Yes** — needs compiled classes on the classpath (like L2 `rta`); degrades to a clear error, leaving L2 intact |
| **Node identity (`line:col`)** | **Native, exact** — straight from JavaParser spans | **Recovered** via B.1 (line + content); within-line ambiguity on multi-statement lines; no-source-position instructions folded/dropped |
| **Edge coverage** | Every **parsed** callable | Only callables in WALA's **analysis scope** (compiled, admitted by the scope loader); others get no overlay |
| **CFG fidelity** | **Source-structural** — matches what is written, and the schema's source `line:col` | **Execution-faithful** — reflects the compiler's actual lowering (short-circuits, desugared enhanced-for / TWR / string-concat, synthetic blocks); truer to what runs, but must be projected back to source shape |
| **Scalar (local) DDG** | Hand-rolled reaching-defs — **correctness is ours to prove** (scoping/shadowing) | PDG scalar (SSA) data dependence — **exact by construction**, mature, high-confidence |
| **Heap / aliased DDG** | **Object-insensitive syntactic** — misses aliased du-pairs, keeps stale ones (§4.5.1); no build | **RTA points-to** — **sound but over-approximate** (type-based): recovers all aliased du-pairs, but conflates same-type objects and does weak updates (`prov:["points-to"]`); needs a build |
| **Maintenance** | Three **textbook passes we own** (no analysis engine invoked) | Mature passes; the one incremental cost is the **bespoke B.1 mapping** — WALA itself is already a dependency (v1 and L2 `rta`) |
| **Speed / cost** | Fast, per-file, no build or whole-program IR | Build + IR construction; **reuses L2 `rta`'s scope/IR** when `rta` already ran, otherwise builds its own |
| **L4 alignment** | Not reusable for L4 (L4 is WALA-based) | **Same IR + B.1 mapping L4 needs** — the proving ground for L4's SDG/points-to |
| **Determinism** | Straightforward (source order) | Achievable with fixed block-iteration / SSA value-number order |

**Why AST is the default.** L3's deliverable is *source-faithful* `line:col` overlays; the AST engine produces them with exact identity, no build, on any parsed source, and covers every callable. Its one real risk — the correctness of the hand-written CFG lowering — is contained by a documented rule and a fixture per construct (§4.4, §10). It is the better *product* default.

**Why WALA is still built.** Three payoffs the AST engine can't give. (1) **Sound heap DDG** — the reused RTA pointer analysis recovers the aliased du-pairs the AST engine misses (§4.5.1), so the WALA engine sees heap data flow invisible to the syntactic engine; it is sound but over-approximate at RTA (spurious same-type pairs, no precise kills — those await L4), whereas the AST engine is precise-on-spelling but unsound on aliasing. Complementary failure modes, which is why §7.2 reports the delta rather than asserting agreement. (2) **Confidence on CFG/CDG** — bytecode-derived control flow and PDG control dependence are execution-faithful and mature, so the WALA engine is an independent oracle that keeps the hand-rolled AST CFG/CDG passes honest (the differential gate cross-validates exactly these, §7.2). (3) **L4 readiness** — L4's SDG *must* use WALA; the per-method PDG and the B.1 instruction→statement mapping are exactly the machinery L4 extends interprocedurally, so building them at L3 de-risks L4 rather than being throwaway.

**Where they diverge (and it's fine).** CFG and CDG are held to agreement on the defined subset (§7.2); the pinned divergences there — exceptional-edge shape and within-line attribution — are the *expected* cost of source-structural vs. execution-faithful views, asserted as divergences rather than bugs. DDG is deliberately **not** held to agreement: the two engines compute it at different precision (object-insensitive syntactic vs RTA points-to), so §7.2 reports their delta empirically — how many heap du-pairs the WALA engine adds and the AST engine misses — to drive engine selection rather than to flag a bug.

## 6. Design decisions

Recorded in [`.claude/SCHEMA_DECISIONS.md`](../../../.claude/SCHEMA_DECISIONS.md). D25–D29 are new; **D28 revises D5** of the parent spec, and **D29 with the revised D27** record the two-engine precision split (§4.5).

| # | Decision | Choice | Rationale / divergence |
| --- | --- | --- | --- |
| D25 | L3 edge endpoints | **Body-node local ids** (`line:col`/`@tag`), not `can://` ids | Overlays are intra-callable; local ids match the `body{}` keys. Grounded in the keystone's own `ddg` example. |
| D26 | L3 engine posture | **Two engines used as alternatives**, `--l3-engine ast\|wala`, `ast` default; differential gate cross-validates CFG/CDG, reports DDG delta | Two implementations of one contract → a differential oracle for CFG/CDG (as `declared`/`rta` at L2), and an empirical DDG comparison for engine selection. |
| D27 (revised) | DDG precision & the L3/L4 line | **L3 = intraprocedural, L4 = interprocedural.** Both engines' DDGs stay within one callable and differ only in *precision*: AST = object-insensitive syntactic (`prov:["ssa"]`); WALA = scalar SSA (`prov:["ssa"]`) **+ RTA points-to heap** (`prov:["points-to"]`) via the reused L2 pointer analysis | RTA points-to *at L3* is deliberate: without it the WALA L3 engine computes too few du-pairs to be practically useful (§4.5). `prov` names the *derivation method*, not the level. Sharpening past RTA (0-CFA, D6) and the interprocedural reach are L4. Supersedes the earlier "syntactic; aliasing→L4" reading. |
| D28 | CFG/DDG derivation (**revises D5**) | AST engine builds cfg/cdg/ddg on the JavaParser AST (exact `line:col`, build-free) as the **default**; WALA engine via the native per-method PDG + the instruction→statement mapping is **opt-in** | D5 made WALA the engine and AST the fallback. Because body nodes are keyed `line:col` and the identity gate requires a real column — which WALA-over-bytecode lacks — nodes come from the AST regardless (B.1 recovers identity); only the *edges* differ by engine, so the exact, build-free AST engine is the natural default. `L3 ⊆ L4` still holds: L4 keeps every L3 edge — scalar `ssa` and (WALA engine) intraprocedural heap `points-to` — and *adds* the interprocedural reach, never removing an L3 edge. |
| D29 | WALA L3 engine internals | **Native per-method WALA PDG** over the reused L2 RTA call graph + pointer analysis: `cfg`←`SSACFG`, `cdg`←PDG control dependence (dominance frontiers), `ddg`←PDG intraprocedural data dependence (scalar `ssa` + heap `points-to`, `NORMAL→NORMAL` only). B.1 recovers node `line:col` — line from the bytecode line table, **sentinel `line:0`** when within-line disambiguation fails. | Keeps the engine true to native WALA (no hand-rolled DDG); the intra/inter split matches Horwitz–Reps–Binkley PDG/SDG and WALA's own `PDG`/`SDG` architecture. Spike-confirmed: lines recover reliably, columns never (bytecode `LineNumberTable` has no columns). |

## 7. Engine selection & the differential gate

- `--l3-engine {ast, wala}`, **default `ast`**. Exactly one engine per run; both emit the identical schema, so a consumer can't tell which produced a payload except by the run's flags.

### 7.1 Alternatives, not an overlay — and why

The engines are **used alternatively** (one per run), not unioned into one graph the way L2 overlays `declared` and `rta`. That precedent deliberately does **not** transfer, for three reasons:

- **CFG/CDG cannot be unioned.** The two produce *different graph shapes* — source-structural vs execution-faithful lowering. Merging their edges yields a graph that satisfies *neither* engine's well-formedness (single `@entry`/`@exit`, reachability; §4.3) — not a richer CFG, a malformed one. One engine's CFG must be authoritative per run.
- **A DDG union would mix two precisions, not add signal.** The engines compute the intraprocedural DDG at *different precision* — object-insensitive syntactic (AST) vs RTA points-to (WALA, §4.5) — and are used as alternatives, one per run. Unioning their `ddg` sets into one graph would blend a sound-at-RTA heap edge set with an object-insensitive one that both misses real edges and keeps stale ones: the result is neither engine's DDG and honors neither's precision contract. §7.2 instead *compares* the two as an empirical delta. Contrast L2, where `declared` (static resolution) and `rta` (dynamic-dispatch fan-out) are complementary tiers of the *same* precision question — which is why L2 *is* an overlay.
- **Provenance is per-run, not a cross-engine overlay.** `prov` records *how* a du-pair was derived — `ssa` (scalar/syntactic) or `points-to` (RTA heap) — within whichever engine ran (§4.5), not the level and not the engine. The WALA engine emits both `ssa` and `points-to` in one run; the AST engine emits only `ssa`. This is *not* the L4 overlay: at L4 `points-to` is an additional tier layered on the *same run's* `ssa` edges, whereas at L3 you pick one engine and get its DDG — the AST engine's `ssa`-only set or the WALA engine's `ssa`+`points-to` set, never a union across engines. That is what keeps `L2 ⊆ L3 ⊆ L4` a clean *per-run* tier union rather than a cross-engine merge.

A best-of-both *composition* — WALA edges per-callable where it analysed one, AST edges for callables outside WALA's scope — is a conceivable future selection policy, but it is a per-callable **fallback** (still one engine's shape per callable), not an edge-level overlay. Out of scope here.

### 7.2 Differential gate — CFG/CDG cross-validated, DDG reported

On the fixtures, run both engines. The gate has two distinct jobs, because only two of the three overlays are meant to agree:

- **CFG + CDG — a hard cross-validation gate.** Assert the engines agree on the CFG node set + reachability (including the single `finally` node and that it is reached on every exit path, §4.4.1) and on `cdg` edges. The AST engine is the reference oracle; a mismatch outside the pinned set fails the build. **Pinned, documented divergences** (asserted as divergences, not bugs): exceptional-edge shape and density (WALA's catch-all lets any instruction throw into a handler/`finally`; the AST engine edges only from statements that syntactically throw), within-line attribution on multi-statement lines (AST exact; WALA via B.1), and any **sentinel `line:0`** nodes the WALA engine emits when B.1 disambiguation fails.
- **DDG — an empirical delta report, not an agreement assertion.** The two DDGs are computed at *different precision* (§4.5), so they are **not** required to coincide and coincidence is not the goal. Instead the gate emits a report: how many du-pairs each engine finds, how many the WALA engine adds via RTA points-to that the AST engine misses (aliased heap du-pairs, §4.5.1), and how many object-insensitive edges the AST engine keeps that RTA kills. This quantifies the data flow one engine sees and the other doesn't — the signal that drives engine selection in practice — captured alongside the L3 metrics report.

## 8. CLI contract

On the existing Picocli app: `-a 3` emits L3 under `--schema v2`; `--l3-engine {ast,wala}` (default `ast`); `--graph-field-depth <k>` (default 3). The `ast` engine needs no build; the `wala` engine builds (like `rta`) and degrades to a **clear error** (not a crash) when the build/scope is unavailable, leaving L2 intact. `--target-files` downgrades to L1; `--emit neo4j` stays v1-only (this increment). Unrecognized flag values exit non-zero; stdout carries only JSON.

## 9. Schema additions

The oracle already anticipates L3 (`max_level ≤ 4`; `bodyNode.kind` includes the L3 kinds; `cfg`/`cdg`/`ddg` present as arrays). This increment **tightens** them, mirroring how L2 added `callEdge`:

- `cfgEdge` — required `src`, `dst`, `kind`; `src`/`dst` are `localId` (`line:col` or an `@tag`, per the schema's existing `$defs/localId`); `kind` a closed enum (the nine of §4.2). `additionalProperties:false`.
- `cdgEdge` — required `src`, `dst` (`localId`). `additionalProperties:false`.
- `ddgEdge` — required `src`, `dst`, `var`, `prov`; `prov` an array over `enum ["ssa","points-to"]` (`ssa` for scalar/syntactic du-pairs — the only value the AST engine emits; `points-to` for the WALA engine's RTA-resolved heap du-pairs, §4.5), `minItems:1`. `additionalProperties:false`.
- the `cfg`/`cdg`/`ddg` arrays `$ref` their edge defs, whose endpoints reuse the schema's **existing** `localId` def (no new def needed).
- an `L3SchemaOracleTest` asserting each way a plausible L3 payload can be wrong (bad `kind`, non-local endpoint, missing `var`, empty `prov`, stray key), written before the producer.

## 10. Testing & gates

Per-level conformance gate is a hard gate. A fixture project exercises each construct with specific-value assertions. **Every gate item runs against both engines.**

1. **CFG gate** — every non-synthetic node has a real span; exactly one `@entry`/`@exit`; every node reachable from `@entry` and reaching `@exit`; each Java construct emits its documented edges including `exception`.
2. **Dominance gate** — post-dominator tree rooted at `@exit`; hand-computed control deps for `if`/loop/early-return match the `cdg` edges exactly.
3. **PDG backward-slice gate** — reverse reachability over `cdg ∪ ddg` of a named variable at a named line equals a hand-computed node set **exactly**, under the **AST engine's** object-insensitive DDG semantics. Cases: loop-carried, shadowed scope, and an **aliased-field** case (pins the AST engine's object-insensitive imprecision — which the WALA engine resolves, §4.5.1).
4. **`L2 ⊆ L3`** — analyse one fixture at both levels; L3 contains everything L2 emitted plus the new overlay keys and statement nodes.
5. **Determinism** — `-j N` byte-identical to `-j 1`; ids stable across runs.
6. **Differential gate** — Engine A vs Engine B **agree on CFG + CDG** (the defined subset; pinned divergences — exceptional edges, within-line attribution, sentinel `line:0` — asserted as such), and the **DDG delta is reported** empirically (§7.2), not asserted equal. Realworld-tagged (the WALA engine builds), like L2's `rta` end-to-end test.

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
8. Spec amendments + ledger (parent §8; `SCHEMA_DECISIONS` D25–D29).

L3 can ship/tag (AST engine, steps 1–5) before the WALA engine (6–7). The Neo4j overlay is a separate follow-up after #182. Live tracking is the epic, not this spec.

## 12. Risks & open questions

| Risk | Mitigation |
| --- | --- |
| AST-CFG lowering correctness (finally duplication, exceptions, TWR) | A documented rule + a fixture per construct; the CFG gate |
| WALA within-line mapping ambiguity (B.1) | Content-based disambiguation + **sentinel `line:0`** when it fails (never mis-attributes) + the CFG/CDG differential gate against the exact AST engine |
| Two engines double the maintenance | Shared contract + shared gate; the WALA engine is opt-in and can lag |
| AST engine's DDG imprecision surprises consumers | `prov:["ssa"]` labels it; an aliased-field fixture pins it; the **WALA engine already resolves it at L3** (RTA heap, `prov:["points-to"]`), and L4 sharpens further |
| WALA engine inherits build/coverage limits | Same posture as L2 `rta` (degrade to a clear error; L2 intact); the #181 scope-shadow fix applies |

## 13. References

- Parent spec: [`schema-v2-l3-l4-design.md`](./schema-v2-l3-l4-design.md) (L3 = §8; engine decision = D5, revised here as D28)
- Decisions ledger: [`.claude/SCHEMA_DECISIONS.md`](../../../.claude/SCHEMA_DECISIONS.md)
- L2 comparison (WALA scope-shadow fix, `rta` posture): [`../notes/l2-v1-v2-comparison.md`](../notes/l2-v1-v2-comparison.md)
- Epic & sub-issue: [codellm-devkit/.github#42](https://github.com/codellm-devkit/.github/issues/42), codeanalyzer-java #183
- Keystone L3 method: `cldk-devtools/…/level-3-intraprocedural-dataflow.md`
- Reference pilot: `codeanalyzer-python` `main` (`codeanalyzer/dataflow/*`)
