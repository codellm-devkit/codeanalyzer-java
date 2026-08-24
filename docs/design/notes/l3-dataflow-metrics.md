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

## Extending to the WALA engine (#194)

When the WALA L3 engine lands, re-run the same batch with `--l3-engine wala` and add a second set of
columns (and per-kind breakdowns) beside these. The expectations, per the design (§7.2):

- **Should match:** the CFG node set and reachability (both engines key nodes by AST `line:col`,
  including the single `finally` node — see §4.4.1), and `cdg`/`ddg` where the syntactic semantics
  coincide. Divergence in these is a bug in one engine.
- **Expected to differ (pinned):** `exception`-edge *density* — WALA's bytecode catch-all lets any
  instruction throw into a handler/`finally`, whereas the AST engine edges only from statements that
  syntactically throw — and within-line attribution on multi-statement lines. These become the
  documented divergences the differential gate asserts as such.
