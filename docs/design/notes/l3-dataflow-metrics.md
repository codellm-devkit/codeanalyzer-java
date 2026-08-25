# L3 dataflow metrics: AST engine over the real-world fixtures

Generated 2026-08-24 from the `codeanalyzer-2.4.1` build on the `enhancement/issue-183-l3-dataflow`
branch. Each of the ten real-world fixture applications was analysed at analysis level 3 with the
default AST engine (`--schema v2 --l3-engine ast`), and the emitted `cfg`/`cdg`/`ddg` overlays counted.
This is the L3 companion to [`l1-v1-v2-comparison.md`](l1-v1-v2-comparison.md) and
[`l2-v1-v2-comparison.md`](l2-v1-v2-comparison.md).

Unlike the L1/L2 notes, this is **not** a v1-vs-v2 comparison — L3 is new; there is no v1 equivalent. It
is a **baseline** of the AST engine's output, laid out so the same table gains a second set of columns
when the WALA L3 engine lands (#194) and the two engines are compared. The AST engine is source-only, so
these numbers need no build: they are the exact structural output of the parser-driven passes.

## How to reproduce

```bash
./gradlew fatJar
JAR=build/libs/codeanalyzer-2.4.1.jar
APP=src/test/resources/test-applications/commons-lang
java -jar $JAR -i $APP -o output/commons-lang/l3 -a 3 --schema v2 --no-rta --no-build
```

`--no-rta --no-build` because L3's AST engine needs neither a build nor the WALA RTA overlay; dependency
resolution only affects type *names*, not `cfg`/`cdg`/`ddg` counts. `output/l3/run.sh` batches all ten
apps and `output/l3/analyze.py` computes the figures below (`output/` is git-ignored).

## Per-app counts

The columns fall into three groups. **Structure:** `modules` / `types` / `callables`, and
`callables w/ body` (those carrying a `cfg`; the difference from `callables` is the
abstract/interface/native methods, which have no body). **Nodes:** `body nodes` — the total completed
body-node count across all callables (`call` + `statement` + `return` + `branch` + `loop` + `switch` +
`@entry`/`@exit`; kind breakdown below). **Edges** — each overlay is an edge list:

- `cfg edges` — control-flow edges between body nodes.
- `cdg edges` — control-dependence edges.
- `ddg edges` — data-dependence edges; each is **one intraprocedural def→use pair** for a k-limited
  access path (deduped by `(def-site, use-site, var)`), so this column is the du-pair count.

| app | modules | types | callables | callables w/ body | body nodes | cfg edges | cdg edges | ddg edges (du-pairs) |
|---|---|---|---|---|---|---|---|---|
| cargotracker | 112 | 115 | 661 | 553 | 4,743 | 2,297 | 470 | 814 |
| commons-lang | 625 | 1,130 | 11,447 | 10,595 | 128,961 | 72,991 | 13,141 | 36,869 |
| daytrader8 | 141 | 148 | 1,221 | 1,090 | 8,884 | 7,028 | 4,448 | 2,593 |
| plantsbywebsphere | 36 | 37 | 481 | 463 | 3,261 | 2,672 | 1,561 | 1,153 |
| spring-petclinic | 49 | 49 | 227 | 182 | 2,287 | 962 | 177 | 414 |
| quarkuscoffeeshop-barista | 21 | 21 | 73 | 56 | 468 | 279 | 64 | 118 |
| quarkuscoffeeshop-counter | 42 | 42 | 242 | 206 | 1,812 | 968 | 182 | 448 |
| quarkuscoffeeshop-domain | 19 | 19 | 109 | 93 | 446 | 260 | 30 | 107 |
| quarkuscoffeeshop-inventory | 19 | 19 | 105 | 93 | 542 | 364 | 67 | 134 |
| quarkuscoffeeshop-kitchen | 17 | 17 | 59 | 45 | 365 | 223 | 56 | 98 |
| **total** | **1,081** | **1,597** | **14,625** | **13,376** | **151,769** | **88,044** | **20,196** | **42,748** |

Node totals (`body nodes`) and edge totals (`cfg`/`cdg`/`ddg edges`) are the two things a WALA-engine
run must be compared against kind-by-kind.

## Edge and node kinds (all apps)

**CFG edges by kind:** `fallthrough` 66,435 · `return` 7,323 · `false` 4,962 · `true` 4,957 ·
`exception` 2,754 · `loop_back` 1,027 · `switch_case` 339 · `break` 197 · `continue` 50 (= 88,044).

**Body nodes by kind:** `call` 94,627 · `statement` 18,053 · `entry` 13,376 · `exit` 13,376 ·
`return` 7,323 · `branch` 3,765 · `loop` 1,203 · `switch` 46 (= 151,769).

## Observations

- **Internal consistency holds at scale.** `@entry` = `@exit` = `with_cfg` = 13,376 (exactly one of each
  per callable with a body); the `return` body-node count equals the `return` CFG-edge count (7,323) —
  each `return` edges once to `@exit`; `true` and `false` counts track each other (4,957 / 4,962). The
  real-world conformance gate independently asserts well-formedness (single entry/exit, every node
  reachable) across four of these apps.
- **`call` nodes dominate the body (62%).** They come from L1 (one per call site) and real code is
  call-dense; the L3 pass adds the 18,053 non-call `statement` nodes plus the branch/loop/switch tests
  and synthetic entry/exit. `cfg` edges are ~75% `fallthrough` — most control flow is straight-line.
- **Data dependence is ~2× control dependence** (42,748 `ddg` vs 20,196 `cdg`), the usual shape; `ddg`
  is the syntactic, object-insensitive tier (`prov:["ssa"]`) — L4 will add the `points-to` overlay.
- **`exception` edges (2,754)** come only from statements that syntactically throw (calls/allocations or
  `throw`) inside a `try`, routed to the enclosing catch/finally. `daytrader8` and `plantsbywebsphere`
  carry disproportionately high `cdg` (4,448 and 1,561) — deeper nesting and more guarded control flow.
- **Output size is the one caution.** `commons-lang`'s level-3 `analysis.json` is ~222 MB (625 files,
  full statement bodies + three overlays); L3 amplifies the L1 payload substantially. This is the
  concern [#191](https://github.com/codellm-devkit/codeanalyzer-java/issues/191) tracks at L1, now more
  pronounced at L3.

## WALA engine — per-app counts

Generated 2026-08-25 on the `enhancement/issue-181-l2-call-graph` branch (`-a 3 --schema v2
--l3-engine wala`; WALA 1.6.7 — no version bump required). Apps with pre-compiled class files used
`--no-build`; apps without compiled classes were given `auto`-build and recorded the outcome.

### How to reproduce

```bash
./gradlew fatJar
JAR=build/libs/codeanalyzer-2.4.1.jar
APP=src/test/resources/test-applications/commons-lang
java -jar $JAR -i $APP -o output/l3-wala/commons-lang -a 3 --schema v2 --l3-engine wala --no-build
```

`output/l3-wala/run.sh` batches all ten apps; `output/l3-wala/analyze.py` computes the figures
below. Apps that needed a build used auto-detect (Maven or Gradle based on project layout).

### Coverage model

The WALA engine covers only callables reachable in the RTA call graph. Unreachable private/package
methods, and any callable in an app that could not be built or analysed, carry no overlay; their
body nodes come from L1 (call nodes only, since the AST L3 body-completion hook is suppressed when
`--l3-engine wala`). Two apps had zero WALA coverage:

- **plantsbywebsphere** — Gradle 7.6 build fails on Java 21 class-file format (major version 65);
  WALA engine degrades cleanly (no crash, L2 intact).
- **quarkuscoffeeshop-domain** — no compiled class files and Maven parent pom absent from the
  fixture tree; WALA engine degrades cleanly.

The `wala_covered` column counts callables for which WALA emitted a CFG overlay (the callable was
in the RTA call graph scope). Callables outside that scope get no L3 overlay from WALA.
`sentinel_nodes` counts `line:0` nodes where B.1 within-line disambiguation failed; **zero across
all ten apps**, confirming the mapping resolved source lines without ambiguity.

### Per-app counts (WALA engine)

| app | modules | types | callables | wala\_covered | body | cfg edges | cdg edges | ddg edges | ddg ssa | ddg pts | sentinel |
|---|---|---|---|---|---|---|---|---|---|---|---|
| cargotracker | 112 | 115 | 661 | 495 | 4,484 | 4,915 | 2,302 | 1,315 | 1,299 | 16 | 0 |
| commons-lang | 625 | 1,130 | 11,447 | 4,208 | 104,207 | 47,779 | 19,628 | 9,161 | 8,755 | 406 | 0 |
| daytrader8 | 141 | 148 | 1,221 | 1,087 | 8,869 | 14,935 | 8,325 | 2,656 | 2,581 | 75 | 0 |
| plantsbywebsphere | 36 | 37 | 481 | **0** (build failed) | 1,265 | 0 | 0 | 0 | 0 | 0 | — |
| spring-petclinic | 49 | 49 | 227 | 88 | 1,907 | 872 | 364 | 96 | 94 | 2 | 0 |
| quarkuscoffeeshop-barista | 21 | 21 | 73 | 42 | 414 | 448 | 219 | 179 | 174 | 5 | 0 |
| quarkuscoffeeshop-counter | 42 | 42 | 242 | 163 | 1,647 | 2,017 | 921 | 658 | 650 | 8 | 0 |
| quarkuscoffeeshop-domain | 19 | 19 | 109 | **0** (no classes) | 119 | 0 | 0 | 0 | 0 | 0 | — |
| quarkuscoffeeshop-inventory | 19 | 19 | 105 | 87 | 520 | 685 | 286 | 221 | 221 | 0 | 0 |
| quarkuscoffeeshop-kitchen | 17 | 17 | 59 | 35 | 322 | 400 | 199 | 154 | 153 | 1 | 0 |
| **total (8 covered apps)** | | | **14,035** | **6,205** | **123,754** | **72,051** | **32,244** | **14,440** | **13,927** | **513** | **0** |

`ddg ssa` — scalar def-use pairs with `prov:["ssa"]`; `ddg pts` — heap/aliased du-pairs with
`prov:["points-to"]` (the RTA-resolved, pointer-analysis-derived pairs the AST engine cannot
produce).

**CFG edges by kind (WALA, 8 covered apps):**
`fallthrough` 44,552 · `exception` 11,781 · `return` 6,077 · `false` 4,655 · `true` 4,099 ·
`loop_back` 582 · `switch_case` 305 (= 72,051).

**Body nodes by kind (WALA, 8 covered apps):**
`call` 94,627 · `statement` 6,616 · `entry` 6,205 · `exit` 6,205 · `return` 6,191 · `branch` 3,211 ·
`loop` 658 · `switch` 41 (= 123,754).

Notable: `call` count (94,627) is identical to the AST engine — call nodes come from L1 and are
engine-independent. Statement/branch/loop counts are lower in WALA because only covered callables
have body completion.

### CFG edge-kind comparison

The `exception` edge count is strikingly different: AST emits 2,754; WALA emits 11,781 (4.3×). This
is the pinned divergence from §4.4.1: WALA's bytecode catch-all routes every potentially-throwing
instruction into the enclosing handler/`finally`, whereas the AST engine edges only from statements
that syntactically contain a call/allocation. This is expected and asserted as a pinned divergence
by the differential gate.

## DDG delta: WALA vs AST engine

The DDG comparison has two parts: a raw totals comparison (dominated by coverage), and a
per-covered-callable comparison that normalises for coverage.

### Raw totals (all callables)

| app | AST ddg | WALA ddg | WALA ddg\_ssa | WALA ddg\_pts (heap) | delta |
|---|---|---|---|---|---|
| cargotracker | 814 | 1,315 | 1,299 | 16 | +501 |
| commons-lang | 36,869 | 9,161 | 8,755 | 406 | −27,708 |
| daytrader8 | 2,593 | 2,656 | 2,581 | 75 | +63 |
| plantsbywebsphere | 1,153 | 0 | 0 | 0 | −1,153 (no coverage) |
| spring-petclinic | 414 | 96 | 94 | 2 | −318 |
| quarkuscoffeeshop-barista | 118 | 179 | 174 | 5 | +61 |
| quarkuscoffeeshop-counter | 448 | 658 | 650 | 8 | +210 |
| quarkuscoffeeshop-domain | 107 | 0 | 0 | 0 | −107 (no coverage) |
| quarkuscoffeeshop-inventory | 134 | 221 | 221 | 0 | +87 |
| quarkuscoffeeshop-kitchen | 98 | 154 | 153 | 1 | +56 |
| **total** | **42,748** | **14,440** | **13,927** | **513** | **−28,308** |

The raw-total delta is dominated by coverage: WALA covers only 42.4% of callables across all apps
(6,205 of 14,625), and commons-lang alone accounts for −27,708. The negative total reflects the
coverage gap, **not** a precision deficit on the covered set.

### Per-covered-callable comparison (apples-to-apples)

Matching exactly the callables where WALA emitted a CFG overlay, and comparing AST DDG on those
same callables:

| app | wala\_covered | AST ddg on covered | WALA ddg on covered | WALA ssa | WALA pts | delta | ratio |
|---|---|---|---|---|---|---|---|
| cargotracker | 495 | 513 | 1,315 | 1,299 | 16 | +802 | 2.56× |
| commons-lang | 4,208 | 8,824 | 9,161 | 8,755 | 406 | +337 | 1.04× |
| daytrader8 | 1,087 | 2,592 | 2,656 | 2,581 | 75 | +64 | 1.02× |
| spring-petclinic | 88 | 73 | 96 | 94 | 2 | +23 | 1.32× |
| quarkuscoffeeshop-barista | 42 | 69 | 179 | 174 | 5 | +110 | 2.59× |
| quarkuscoffeeshop-counter | 163 | 324 | 658 | 650 | 8 | +334 | 2.03× |
| quarkuscoffeeshop-inventory | 87 | 122 | 221 | 221 | 0 | +99 | 1.81× |
| quarkuscoffeeshop-kitchen | 35 | 55 | 154 | 153 | 1 | +99 | 2.80× |
| **total (8 apps)** | **6,205** | **12,572** | **14,440** | **13,927** | **513** | **+1,868** | **1.15×** |

On the covered callable set, WALA finds **+15% more du-pairs** than AST (ratio 1.15). The gains
come from two sources:
- **Heap (`points-to`) du-pairs** — 513 edges that the AST engine cannot produce at all (it has no
  pointer analysis). These are the aliased and field-indirection du-pairs RTA resolves. Ranges from
  0 (quarkuscoffeeshop-inventory) to 406 (commons-lang).
- **SSA scalar du-pairs exceeding AST** — WALA's PDG `NORMAL→NORMAL` DATA_DEP edges also exceed AST
  in most apps. This is partly because SSA assigns a unique definition per variable per
  block, which can expose more direct def-use pairs than AST's reaching-defs approximation.

### The phi-mediated-scalar limitation (honestly stated)

WALA's `NORMAL→NORMAL` filter retains only edges between `NormalStatement` nodes. A `PhiStatement`
(the SSA phi at a control-flow merge) is **not** a NormalStatement, so:

- **A→phi and phi→B edges are both dropped.** If `x_1` is defined at `A` (normal), merged through
  a phi at a join point into `x_2`, and used at `B` (normal), WALA emits **no edge A→B**.
- The AST reaching-defs algorithm would emit A→B (the def of `x` reaches the use via the merge),
  so **the AST engine finds more phi-mediated scalar deps** for loop-carried and branch-merge
  patterns.

In practice this means WALA's scalar DDG is **sparse on phi-heavy methods** (deep loops,
multi-branch merges). The per-covered ratio is > 1 overall because the heap (`points-to`) gains and
direct-SSA gains outweigh the phi loss on these apps, but individual methods with dense loop-carried
state can have more AST scalar deps than WALA scalar deps. This limitation is the documented
cost of the `NORMAL→NORMAL` filter; it is the design choice, not a bug (the filter drops SDG
interprocedural interface nodes, and phi nodes cannot be precisely attributed to a source statement
anyway). Engine selection guidance: choose WALA when heap/aliasing data flow matters; choose AST
when loop-carried scalar deps dominate and source-exact spanning is required.

## Observations (WALA engine)

- **No WALA version bump required.** WALA 1.6.7 already provides all required APIs: `PDG`,
  `ModRef`, `Dependency` kinds (`DATA_DEP`, `HEAP_DATA_DEP`, `CONTROL_DEP`), and `SSACFG`. No
  dependency change was needed for any of Tasks 1–8.
- **No sentinel nodes** (`line:0`) across all eight covered apps (0/0 total). The B.1 source-line
  mapping resolved every instruction to a real source line without ambiguity fallback. This is
  stronger than the spec required (sentinels are allowed); real apps apparently avoid the
  multi-statement same-line patterns that would trigger them.
- **CFG edge density is higher in WALA** (72,051 for 6,205 callables = 11.6 edges/callable) than
  in AST (88,044 for 13,376 callables = 6.6 edges/callable). The most significant driver is
  exception edges (11,781 WALA vs 2,754 AST) from WALA's catch-all bytecode lowering — pinned
  divergence, not a bug.
- **CDG is richer in WALA** — for the covered set, WALA CDG (32,244) is proportionally larger than
  AST CDG (20,196 across 13,376 callables; ~1.5 cdg/callable AST vs ~5.2 cdg/callable WALA on the
  covered set). The wider CDG comes from WALA's bytecode dominance frontiers capturing more
  exception-path control dependences that are implicit in source.
- **Coverage** ranges from 35/59 (59%, quarkuscoffeeshop-kitchen) to 1,087/1,221 (89%, daytrader8).
  commons-lang has the lowest coverage (36.7%) — it has many static utility methods that are not
  reachable from the generic entrypoints RTA uses, so those callables get no WALA overlay.

## Extending to the WALA engine — original expectation vs outcome

The section originally written to receive these numbers (now filled in above) anticipated:

- **Should match:** CFG node set and reachability — confirmed: both engines use the same `line:col`
  node space (call nodes from L1, statement/branch/etc. from L3 body-completion); the differential
  gate asserts CFG+CDG agreement on the defined subset.
- **Expected to differ (pinned):** `exception`-edge density — confirmed (11,781 WALA vs 2,754 AST,
  4.3×); within-line attribution is not an issue (0 sentinel nodes). These are asserted as
  divergences by the differential gate, not flagged as bugs.
