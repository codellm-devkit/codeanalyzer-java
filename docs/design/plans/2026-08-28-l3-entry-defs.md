# L3 `@entry` Defines the Formals — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a callable's formal parameters visible to its own dataflow, so `ddg` edges root at `@entry` and downstream seeding can be semantic instead of textual.

**Architecture:** One substrate change plus a measurement. `DdgBuilder` currently derives every node's def/use set from that node's AST node; `@entry` has none, so formals are invisible. Task 1 threads the callable's parameters into the AST L3 path and seeds `@entry`'s def set with them, through the same `AccessPath` grammar and k-limit every other def uses. Task 2 measures what the L4 summary pass gains for free — that measurement is the deliverable that decides whether `SummaryPass`'s syntactic seeding rules can be retired, which is a **separate, dependent** issue and explicitly not in this plan. Task 3 corrects the parent spec's false parity claim.

**Tech Stack:** Java 11+, JavaParser (the AST L3 engine), Lombok `@Data` models, JUnit 5, Gradle.

**Spec:** `docs/design/specs/l3-intraprocedural-dataflow-design.md` (D25–D29 constrain identity and monotonicity) and `docs/design/specs/schema-v2-l3-l4-design.md` §8 (L3) and D7 (the summary pass, whose parity claim Task 3 corrects). Tracking: [#204](https://github.com/codellm-devkit/codeanalyzer-java/issues/204).

**Precondition:** [#203](https://github.com/codellm-devkit/codeanalyzer-java/pull/203) must land first — `SummaryPass`, `SdgVertices` and `L4GateTest` exist only on that branch, and Task 2 measures them. Branch this work from `main` after that merge, not from `feat/l4-sdg`.

## Global Constraints

- **Additivity:** this only adds `ddg` edges. No existing edge may disappear or change its `var`/`prov`. `prov` stays `["ssa"]` — an entry-rooted def is syntactic, not points-to derived.
- **Identity (D25):** `ddg` endpoints are body-node local ids. `@entry` is already one (`ControlFlowGraph.ENTRY`). No new id form.
- **k-limiting:** formal access paths go through `AccessPath.of(…, k)` like every other path, so `--graph-field-depth` governs them identically. A bare parameter name is a base segment and is never truncated.
- **Determinism:** collect then sort; two runs byte-identical. `DdgBuilder` already sorts its output — do not introduce a hash-ordered collection upstream of it.
- **Engine scope:** the AST engine only. The WALA engine excludes parameter statements by a different mechanism (`WalaPdgBuilder`'s `NormalStatement`-only filter) and is out of scope; the L3 differential gate reports the DDG difference as an empirical delta rather than asserting equality, so a widened delta is tolerated — but Task 2 records it.
- Spotless is auto-applied; run `./gradlew spotlessApply` before each commit.
- Conventional-commit subjects. **No AI/Claude attribution in any commit message, body, or trailer** — hard project rule.
- `./gradlew test` has one pre-existing failure, `CodeAnalyzerIntegrationTest > initializationError` (no Docker daemon). Tests tagged `@Tag("realworld")` run under `./gradlew realWorldConformanceTest` with a 4 GB heap. Neither is this plan's to fix.

## File Structure

| File | Responsibility |
| --- | --- |
| `src/main/java/com/ibm/cldk/syntactic_analysis/dataflow/DdgBuilder.java` (modify) | accept the formals and seed `@entry`'s def set |
| `src/main/java/com/ibm/cldk/syntactic_analysis/L3Overlays.java` (modify) | thread the formals from the caller through to `DdgBuilder` |
| `src/main/java/com/ibm/cldk/syntactic_analysis/CallableBuilder.java` (modify) | pass `callable.getParameters()` (already populated at this point) |
| `src/test/java/com/ibm/cldk/syntactic_analysis/dataflow/DdgBuilderEntryDefsTest.java` (create) | the unit gate for entry-rooted edges |
| `docs/design/plans/2026-08-28-l3-entry-defs.md` (this file, modify) | Task 2 writes its measurement into the Measurement section below |
| `docs/design/specs/schema-v2-l3-l4-design.md` (modify) | D7 parity correction |

---

### Task 1: `@entry` defines the formals

**Files:**
- Modify: `src/main/java/com/ibm/cldk/syntactic_analysis/dataflow/DdgBuilder.java:89` (the `build` signature and its phase-1 loop at `:94-100`)
- Modify: `src/main/java/com/ibm/cldk/syntactic_analysis/L3Overlays.java:59-63`
- Modify: `src/main/java/com/ibm/cldk/syntactic_analysis/CallableBuilder.java:119`
- Test: `src/test/java/com/ibm/cldk/syntactic_analysis/dataflow/DdgBuilderEntryDefsTest.java`

**Interfaces:**
- Consumes: `ControlFlowGraph.ENTRY` (the constant `"@entry"`); `AccessPath.of(Expression, int)` — note it takes an `Expression`, and a formal is a name, not an expression, so the base segment is the parameter's simple name used directly rather than routed through `of`; `JCallable.getParameters()` → `List<JParameter>` with `getName()`, populated in `CallableBuilder` at its line ~83, well before the L3 block at `:118`.
- Produces:
  - `DdgBuilder.build(ControlFlowGraph cfg, int fieldDepth, List<String> formals)` — new three-arg form. Keep the existing two-arg `build(cfg, fieldDepth)` delegating with `List.of()`, so `L3DataflowGateTest` and any other existing caller compile unchanged.
  - `L3Overlays.build(BlockStmt body, Map<String,JBodyNode> existingBody, L1BuildContext ctx, int fieldDepth, List<String> formals)` — new five-arg form; keep the four-arg one delegating with `List.of()`.

- [ ] **Step 1: Write the failing test**

```java
package com.ibm.cldk.syntactic_analysis.dataflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.ibm.cldk.schema.JDdgEdge;
import com.ibm.cldk.syntactic_analysis.L1BuildContext;
import com.ibm.cldk.syntactic_analysis.L3Overlays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * A callable's formals are defined at {@code @entry}, so its own dataflow can see them. Before this,
 * {@code @entry} had no AST node and therefore no defs, and no ddg edge could root at a parameter.
 */
class DdgBuilderEntryDefsTest {

    /** Build the AST-engine L3 overlays for one method's body, with its formals declared. */
    private static List<JDdgEdge> ddgOf(String source, String methodName) {
        MethodDeclaration md = StaticJavaParser.parse(source)
                .findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals(methodName))
                .findFirst().orElseThrow();
        BlockStmt body = md.getBody().orElseThrow();
        List<String> formals = md.getParameters().stream()
                .map(p -> p.getNameAsString()).collect(Collectors.toList());
        L1BuildContext ctx = new L1BuildContext("can://java/t", "T.java", source, 3, 3, "ast");
        return L3Overlays.build(body, new LinkedHashMap<>(), ctx, 3, formals).ddg();
    }

    private static boolean hasEntryEdge(List<JDdgEdge> ddg, String var) {
        return ddg.stream().anyMatch(e -> "@entry".equals(e.getSrc()) && var.equals(e.getVar()));
    }

    @Test
    void aParameterUsedInTheReturnGetsAnEntryRootedEdge() {
        List<JDdgEdge> ddg = ddgOf("class T { int m(int q) { return q; } }", "m");
        assertTrue(hasEntryEdge(ddg, "q"),
                "the formal is defined at @entry and used by the return: " + ddg);
        assertEquals(1, ddg.stream().filter(e -> "@entry".equals(e.getSrc())).count(),
                "exactly one entry-rooted edge for one used formal: " + ddg);
    }

    @Test
    void anUnusedParameterProducesNoEdge() {
        List<JDdgEdge> ddg = ddgOf("class T { int m(int q) { return 1; } }", "m");
        assertTrue(ddg.stream().noneMatch(e -> "@entry".equals(e.getSrc())),
                "a formal nothing reads yields no edge — a def with no use is not a dependence: " + ddg);
    }

    @Test
    void aLocalShadowingTheFormalKillsTheEntryDefinition() {
        // `q` is reassigned before the read, so the read depends on the assignment, not on @entry.
        List<JDdgEdge> ddg = ddgOf("class T { int m(int q) { q = 5; return q; } }", "m");
        assertTrue(ddg.stream().noneMatch(e -> "@entry".equals(e.getSrc())),
                "the reassignment kills the entry def before any use reaches it: " + ddg);
    }

    @Test
    void twoFormalsBothUsedEachGetTheirOwnEdge() {
        List<JDdgEdge> ddg = ddgOf("class T { int m(int a, int b) { return a + b; } }", "m");
        assertTrue(hasEntryEdge(ddg, "a") && hasEntryEdge(ddg, "b"), ddg.toString());
    }

    @Test
    void everyEntryEdgeCarriesSsaProvenance() {
        List<JDdgEdge> ddg = ddgOf("class T { int m(int q) { return q; } }", "m");
        ddg.stream().filter(e -> "@entry".equals(e.getSrc()))
                .forEach(e -> assertEquals(List.of("ssa"), e.getProv(),
                        "an entry def is syntactic, not points-to derived"));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests "com.ibm.cldk.syntactic_analysis.dataflow.DdgBuilderEntryDefsTest"`
Expected: COMPILE FAILURE — the five-arg `L3Overlays.build` does not exist yet. After Step 3's signature lands but before the seeding does, expect `aParameterUsedInTheReturnGetsAnEntryRootedEdge` and `twoFormalsBothUsedEachGetTheirOwnEdge` to fail on the missing edge.

- [ ] **Step 3: Thread the formals through**

`DdgBuilder`:

```java
    /** Existing callers keep working; a callable with no declared formals behaves exactly as before. */
    public static List<JDdgEdge> build(ControlFlowGraph cfg, int fieldDepth) {
        return build(cfg, fieldDepth, List.of());
    }

    /**
     * @param formals the callable's declared parameter names, in declaration order. They are defined
     *     at {@code @entry}: a formal has no defining statement, but it is live on entry, and without
     *     that def no dependence can root at a parameter — which is what forced downstream consumers
     *     to recover parameter flow by matching source text.
     */
    public static List<JDdgEdge> build(ControlFlowGraph cfg, int fieldDepth, List<String> formals) {
```

In the phase-1 loop (`:94-100`), after `collect(cfg.astNode(n), d, u, fieldDepth);`, add:

```java
            if (ControlFlowGraph.ENTRY.equals(n)) {
                // A formal's access path is its bare name — a base segment, which AccessPath never
                // truncates, so this is k-independent by construction.
                d.addAll(formals);
            }
```

`L3Overlays`: add the five-arg overload, keep the four-arg one delegating with `List.of()`, and pass `formals` to `DdgBuilder.build(cfg, fieldDepth, formals)`.

`CallableBuilder:119`: pass the names —

```java
                L3Overlays.L3Result l3 = L3Overlays.build(b, callable.getBody(), ctx,
                        ctx.getGraphFieldDepth(),
                        callable.getParameters().stream().map(JParameter::getName)
                                .collect(Collectors.toList()));
```

Nothing else changes: `reachableFromEntry` already includes `@entry`, the reaching-definitions fixpoint already propagates any def out of any node, and `killed`/`overlaps` already handle a bare base path.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew test --tests "com.ibm.cldk.syntactic_analysis.dataflow.DdgBuilderEntryDefsTest"`
Expected: PASS (5 tests)

- [ ] **Step 5: Run the L3 suites that this moves**

Run: `./gradlew test --tests "com.ibm.cldk.syntactic_analysis.*" --tests "com.ibm.cldk.schema.L3*"`
Expected: PASS. If a pinned L3 edge count moved, that is this change working — update the expected value to the observed one and note in your report which test moved and by how much. Do **not** adjust an assertion to hide a *lost* edge; a disappearing edge violates additivity and is a defect.

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessApply
git add src/main/java/com/ibm/cldk/syntactic_analysis src/test/java/com/ibm/cldk/syntactic_analysis
git commit -m "fix(l3): define a callable's formals at @entry so dataflow can root at a parameter"
```

---

### Task 2: Measure what the summary pass gains

**Files:**
- Modify: `docs/design/plans/2026-08-28-l3-entry-defs.md` (the Measurement section below — this task's deliverable is written findings, not code)
- Modify: `src/test/java/com/ibm/cldk/schema/L4GateTest.java` (only if the pinned overlay counts moved)

**Interfaces:**
- Consumes: Task 1's change; `SummaryPass.apply(modules, callGraph, fieldDepth)`; the `l4-sdg-test` fixture; `L4GateTest`'s pinned counts (`param_in` 6, `param_out` 6, `summary` 5 as of #203).
- Produces: the filled-in Measurement section, which is the input to the dependent follow-on issue that decides whether `SummaryPass`'s syntactic seeding can be retired.

This task deliberately changes no analyzer behavior. Its output is evidence.

- [ ] **Step 1: Capture the before/after deltas on the fixture**

Build the jar at Task 1's commit and at its parent, and diff the emitted documents:

```bash
./gradlew fatJar -x test
java -jar build/libs/codeanalyzer-*.jar -i src/test/resources/test-applications/l4-sdg-test \
  -a 4 --no-build -o /tmp/after
git stash && ./gradlew fatJar -x test && \
java -jar build/libs/codeanalyzer-*.jar -i src/test/resources/test-applications/l4-sdg-test \
  -a 4 --no-build -o /tmp/before && git stash pop
```

Record, per callable: the `ddg` edges added (full `{src,dst,var,prov}` tuples, not a count), and the `summary` edges gained or lost. **A lost summary edge is a defect, not a measurement** — stop and report it.

- [ ] **Step 2: Determine which seeding rules are now redundant**

`SummaryPass.seedsFor` (`SummaryPass.java:224-262`) has three rules: ddg-rooted (`:240-247`), call-argument text (`:248-257`), and whole-body span text (`:258-262`). For each, disable it in a scratch build, re-run the fixture, and record which summary edges disappear. A rule whose removal loses nothing is a candidate for retirement; a rule whose removal loses an edge is still load-bearing and the finding should say which edge and why.

Revert the scratch edits — this task commits no analyzer change.

- [ ] **Step 3: Repeat on one real application**

Run both jars at `-a 4 --no-build` over `src/test/resources/test-applications/daytrader8` (or another fixture of comparable size present in the repo). Record the aggregate `ddg` and `summary` edge deltas and the wall-clock difference. The fixture is too small to show whether entry defs blow up the reaching-definitions fixpoint; this is the check that they do not.

- [ ] **Step 4: Write the Measurement section**

Fill in the section at the end of this file with: the per-callable fixture deltas, the per-rule redundancy findings, the real-application aggregates and timing, and a one-paragraph recommendation on which seeding rules the follow-on issue should retire. Cite concrete edges, not summaries of them.

- [ ] **Step 5: Update the L4 gate if its counts moved**

If `L4GateTest`'s pinned `param_in`/`param_out`/`summary` totals changed, update them to the observed values and state in the commit body which callable accounts for each delta. If they did not move, say so explicitly in the Measurement section — that is itself a finding (semantic seeding reproducing exactly what the regex found on this fixture).

- [ ] **Step 6: Commit**

```bash
./gradlew spotlessApply
git add docs/design/plans/2026-08-28-l3-entry-defs.md src/test/java/com/ibm/cldk/schema/L4GateTest.java
git commit -m "docs(l3): record what entry defs buy the summary pass"
```

---

### Task 3: Correct D7's parity claim

**Files:**
- Modify: `docs/design/specs/schema-v2-l3-l4-design.md` (the D7 row in §6, and §9's "Summary pass (D7)" paragraph)

**Interfaces:**
- Consumes: nothing. Produces: an honest spec.

- [ ] **Step 1: Replace the false claim**

D7 currently reads, in both places, that the summary pass uses hammock-region summaries. It does not: there is no region machinery. At statement granularity a call-site node is already a transformer, so what callee summaries actually contribute at the composition step is the global footprint.

Rewrite both mentions to say what is true: the pass composes bottom-up over the SCC condensation with a monotone fixpoint, reaches its transfer relation at statement granularity rather than by region decomposition, and that region decomposition remains an open refinement rather than an existing precedent. Keep the rest of D7 — SCC condensation, k-limiting, fixpoint, "heaviest unit, sequenced last" — as written; only the parity claim is wrong.

Add one sentence stating that both analyzers persist `cfg` and `cdg` on the callable and compute post-dominators, so what remains to implement is the region decomposition itself.

- [ ] **Step 2: Verify no other document repeats the claim**

Run: `grep -rn "hammock" docs/ .claude/ 2>/dev/null`
Expected: hits only in the file you just corrected. Fix any other occurrence the same way.

- [ ] **Step 3: Commit**

```bash
git add docs/design/specs/schema-v2-l3-l4-design.md
git commit -m "docs(spec): correct D7 — the reference analyzer has no hammock regions"
```

---

## Measurement

Measured at `228f270` ("after") against its parent `53a4029` ("before"). The "before" jar was
built from a throwaway worktree rather than by stashing, so the working branch was never mutated:

```bash
mkdir -p /tmp/m
git worktree add /tmp/l3-before 228f270~1
(cd /tmp/l3-before && ./gradlew fatJar -x test)      # before jar
./gradlew fatJar -x test                              # after jar
# note: build/libs also holds a stale codeanalyzer-2.4.1.jar, so name the jar explicitly
# rather than globbing codeanalyzer-*.jar
java -jar /tmp/l3-before/build/libs/codeanalyzer-3.0.0.jar \
  -i src/test/resources/test-applications/l4-sdg-test -a 4 --no-build -o /tmp/m/before
java -jar build/libs/codeanalyzer-3.0.0.jar \
  -i src/test/resources/test-applications/l4-sdg-test -a 4 --no-build -o /tmp/m/after
git worktree remove /tmp/l3-before                    # when finished
```

**What this measurement does not cover.** There is no `gradle` on PATH in this environment, so
every CLI run needs `--no-build` and the WALA half degrades wherever the target has no compiled
classes. On `l4-sdg-test` that is total: both documents carry **zero** `prov:["points-to"]` edges,
so nothing below says anything about the WALA engine on that fixture. On `daytrader8` it happens
*not* to degrade — that project ships a `target/classes` (155 `.class` files, untracked, left by an
earlier local run), so WALA did run there and emitted 1231 `points-to` edges, **identically on both
sides** (1231 before, 1231 after). That identity is the only positive evidence here that the change
is AST-engine-only as the Global Constraints require; a clean clone without a built `daytrader8`
will not reproduce it and will see the points-to half absent on both sides instead.

Also uncovered: the shadowing risk this plan's own self-review flags (a catch or lambda parameter
sharing a formal's spelling). Nothing below tests it. The 43 spurious `summary` edges found on
daytrader8 are all attributable to a different mechanism, established by construction — restoring
per-parameter seeding removes exactly those 43 and nothing else — but no claim is made here about
whether any of the 1646 new `ddg` edges is itself a shadowing artifact, because that was not
measured.

### Fixture deltas, per callable

Eleven `ddg` edges added, **none lost, none changed**. Every one is rooted at `@entry` with
`prov:["ssa"]`, as the additivity constraint requires. `Heap.get()` takes no parameters and is
correctly untouched.

| Callable | new `ddg` edge | `dst` node kind |
| --- | --- | --- |
| `Chain.a(int)` | `{src:"@entry", dst:"5:9", var:"x", prov:["ssa"]}` | `return` |
| `Chain.b(int)` | `{src:"@entry", dst:"9:9", var:"y", prov:["ssa"]}` | `return` |
| `Chain.c(int)` | `{src:"@entry", dst:"13:9", var:"z", prov:["ssa"]}` | `return` |
| `Heap.put(int)` | `{src:"@entry", dst:"7:9", var:"v", prov:["ssa"]}` | `statement` (`this.box = v;`) |
| `Heap.roundTrip(int)` | `{src:"@entry", dst:"15:9", var:"v", prov:["ssa"]}` | `call` (`put(v);`) |
| `Loops.callFirst(int[])` | `{src:"@entry", dst:"15:9", var:"a", prov:["ssa"]}` | `return` |
| `Loops.first(int[])` | `{src:"@entry", dst:"8:9", var:"q", prov:["ssa"]}` | `loop` (the for-each container) |
| `Mutual.even(int)` | `{src:"@entry", dst:"5:9", var:"n", prov:["ssa"]}` | `branch` (`if (n == 0)`) |
| `Mutual.even(int)` | `{src:"@entry", dst:"8:9", var:"n", prov:["ssa"]}` | `return` (`return odd(n - 1);`) |
| `Mutual.odd(int)` | `{src:"@entry", dst:"12:9", var:"n", prov:["ssa"]}` | `branch` |
| `Mutual.odd(int)` | `{src:"@entry", dst:"15:9", var:"n", prov:["ssa"]}` | `return` |

Aggregate: `ddg` 2 → 13; `summary` 5 → 5; `param_in` 6 → 6; `param_out` 6 → 6.

**`summary` edges: no change at all.** The two documents carry the identical five-edge set —
`Chain.a` `5:16/actual_in:0 → 5:16/actual_out`, `Chain.b` `9:16/actual_in:0 → 9:16/actual_out`,
`Loops.callFirst` `15:16/actual_in:0 → 15:16/actual_out`, `Mutual.even`
`8:16/actual_in:0 → 8:16/actual_out`, `Mutual.odd` `15:16/actual_in:0 → 15:16/actual_out`. Nothing
gained, nothing lost.

**Therefore `L4GateTest`'s pinned counts did not move** and the file is unmodified by this task:
`param_in` 6, `param_out` 6, `summary` 5, exactly as pinned at #203. On this fixture semantic
seeding reproduces the textual result precisely. `Loops.first` is the interesting case: `L4GateTest`'s
javadoc singles it out as the one summary edge that *needs* container-node text seeding, because
`q` is named nowhere but the for-each header. It no longer needs it — `{@entry → 8:9, var q}`
composes with the pre-existing `{8:9 → 9:13, var x}` to reach the `return` sink semantically.

Read that "no change" carefully, though. **Every callable in `l4-sdg-test` has arity 0 or 1** — the
fixture's maximum parameter count is one. The daytrader8 run below shows the change *does* move
`summary` edges, by a mechanism that needs two parameters in one callable to fire. So the stable
count here is not evidence that the change is summary-neutral; it is evidence that this fixture
cannot see the difference. That is a gap in the fixture, and the follow-on should close it. (Closed:
`com/l4/Arity.java` adds a two-parameter callable of which only one parameter flows out, which moves
the fixture's totals to `param_in` 9, `param_out` 7, `summary` 6.)

### Seeding-rule redundancy

Each rule was put behind a system property in a scratch worktree
(`git worktree add /tmp/l3-scratch 228f270`) so one jar covers every combination:
`-Dseed.rule1=0` / `-Dseed.rule2=0` / `-Dseed.rule3=0` disable `seedsFor`'s ddg-rooted, call-argument
text, and whole-body span text rules respectively. With no property set the instrumented jar emits a
**set-identical** `summary` payload to the clean jar on both corpora, so the harness is
behaviour-neutral. The scratch edits live only in `/tmp/l3-scratch`; nothing in this repo changed.

```bash
java -Dseed.rule2=0 -Dseed.rule3=0 -jar /tmp/l3-scratch/build/libs/codeanalyzer-3.0.0.jar \
  -i src/test/resources/test-applications/daytrader8 -a 4 --no-build -o /tmp/m/dt-R1only
```

Summary-edge totals (`l4-sdg-test` / `daytrader8`):

| variant | fixture | daytrader8 | vs. all-rules-on |
| --- | --- | --- | --- |
| all three rules (= shipped) | 5 | 329 | — |
| rule 1 disabled | 5 | 283 | loses 46 on daytrader8 |
| rule 2 disabled | 5 | 329 | loses nothing |
| rule 3 disabled | 5 | 329 | loses nothing |
| **rule 1 only** | **5** | **329** | **set-identical to all three** |
| rule 1 only, *before* the change | **0** | — | — |

Read straight, that is the answer the follow-on wants: **rules 2 and 3 are dead on both corpora.
Disabling either loses zero edges, and rule 1 alone reproduces the entire emitted set — all 5 on the
fixture and all 329 on daytrader8.** And the last row is what the change bought: at `53a4029`, rule 1
alone produced **zero** summary edges on the fixture, because no `ddg` edge could be rooted at a
parameter, so the ddg-rooted rule had nothing to match. It was inert for parameter seeding; the
textual rules were carrying the pass entirely.

Rule 1 was not *entirely* inert before, though. On daytrader8, `dt-noR1` emits 283 edges against
`dt-before`'s 286, so three edges came from rule 1 matching an ordinary (non-entry) `ddg` edge whose
access-path base is a parameter name — e.g. a parameter reassigned in the body, whose reassignment
node then roots an edge. Those three are:

- `TradeDirect.buy(String, String, double, int)` — `338:11/actual_in:1 → 338:11/actual_out`
- `TradeDirect.completeOrder(Integer, boolean)` — `524:19/actual_in:1 → 524:19/actual_out`
- `TradeDirect.sell(String, Integer, int)` — `445:11/actual_in:1 → 445:11/actual_out`

#### The 46-edge gap is not a win — rule 1 now over-approximates across parameters

Rule 1 disabled loses 46 edges but only 3 of those existed before the change, so the change *added*
43 summary edges on daytrader8 (286 → 329, none lost). **All 43 are spurious.** The mechanism:

```java
for (JDdgEdge e : ddg) {
    if (name.equals(base(e.getVar()))) {
        seeds.add(e.getSrc());          // SummaryPass.java:244
    }
}
```

For an ordinary edge, `src` is the node that *defines* the variable, and seeding there is right. For
an entry-rooted edge `src` is `@entry` — the node that defines **every** formal. So the seed set for
*any* parameter collapses to the single shared node `@entry`, and `reaches()`'s BFS then follows
`@entry`'s out-edges for *all* the other parameters too. If one parameter reaches the return, every
parameter with an entry-rooted edge is credited with reaching it.

Confirmed on a purpose-built discriminator rather than inferred. Drop this in a throwaway project
(a `build.gradle` copied from `l4-sdg-test` plus `src/main/java/com/x/T.java`) and run both jars over
it at `-a 4 --no-build`. The layout is verbatim — the node ids cited below are line:column, so
reformatting it renumbers them:

```java
package com.x;

public class T {
    // Only `p` reaches the return. `q` is used by a void call and goes nowhere.
    public int leak(int p, int q) {
        int t = p;
        sink(q);
        return t;
    }

    public void sink(int z) {
    }

    public int caller(int m, int n) {
        return leak(m, n);
    }
}
```

`leak`'s `ddg` after the change is `{@entry → 6:9, var p}`, `{@entry → 7:9, var q}`,
`{6:9 → 8:9, var t}`. Seeding `q` at `@entry` walks the `p` edge to `6:9`, then to the `8:9` return
sink, so `flows(leak)` becomes `{0,1}` and `caller` gains
`{src:"15:16/actual_in:1", dst:"15:16/actual_out"}` — a claim that `n` comes back out of `leak`,
which it cannot: `q` is only handed to a `void` callee. Before the change this edge did not exist,
because the textual rules seeded `q` at `7:9` and at `7:9/actual_in:0`, both dead ends (`sink` is
`void`, so that site has no `actual_out` to bridge to).

`KeySequenceDirect.getNextID` is the same shape in real code. It gains exactly six edges —
`39:13/actual_in:{1,2,3}` and `45:19/actual_in:{1,2,3}`, each `→ actual_out` — at its two
`allocNewBlock(conn, keyName, inSession, inGlobalTxn)` sites. Argument 0 is credited on *both* sides,
so `conn` already flowed to `allocNewBlock`'s return before the change; arguments 1–3 appear only
after it. Reading the callee, `conn` reaches the return through
`stmt = conn.prepareStatement(…)` → `rs = stmt.executeQuery()` → `keyVal = rs.getInt(…)` →
`block = new KeyBlock(keyVal, …)` → `return block`, and `keyName`, `inSession` and `inGlobalTxn`
are swept along with it at the shared `@entry` seed. The first sentence of that is observed; the
def-use chain is read off the source, not extracted from the document.

This is over-approximation, which the L4 weak-update posture explicitly accepts, and no real flow is
lost — so it is not a defect against the plan's constraints. It is a **precision regression in
`SummaryPass`**, not in `DdgBuilder`, and it matters here because it lands on exactly the axis the
retirement decision turns on.

#### A one-line correction removes it

Seeding the *use* site instead of the shared def when the source is `@entry` restores per-parameter
resolution. Tested as a fourth toggle (`-Dseed.entryfix=1`) over
`seeds.add(ENTRYFIX && "@entry".equals(e.getSrc()) ? e.getDst() : e.getSrc())`:

| variant | fixture | daytrader8 |
| --- | --- | --- |
| corrected rule 1 + rules 2,3 | 5 | 286 — **set-identical to `dt-before`** |
| **corrected rule 1 alone** | **5** | **286 — set-identical to `dt-before`** |

All 43 spurious edges disappear, the discriminator's `caller` drops back to one summary edge, and
corrected rule 1 *on its own* reproduces the exact pre-change edge set across 1229 callables. This is
observed, not argued. The reasoning for why it should hold in general — reach from `@entry` along the
`var == name` edges is exactly `{dst} ∪ reach(dst)`, so seeding `dst` is the same set minus the other
parameters' edges — is inference, and the follow-on should re-derive it rather than take it from here.

#### Exact-set restoration, shown rather than asserted

The claim above was originally recorded as counts. Re-derived end to end while shipping the fix, as
**sets** — the extractor emits one `callable-id ⇥ src ⇥ dst` line per summary edge, sorted, so an
empty `diff` is set (indeed sorted-multiset) identity and the matching digest is independent
corroboration. Run from the repo root:

```bash
git worktree add /tmp/l3-seedfix 228f270~1
(cd /tmp/l3-seedfix && ./gradlew fatJar -x test -q)
./gradlew fatJar -x test -q

mkdir -p /tmp/m4
java -jar /tmp/l3-seedfix/build/libs/codeanalyzer-3.0.0.jar \
  -i src/test/resources/test-applications/daytrader8 -a 4 --no-build -o /tmp/m4/before
java -jar build/libs/codeanalyzer-3.0.0.jar \
  -i src/test/resources/test-applications/daytrader8 -a 4 --no-build -o /tmp/m4/after

cat > /tmp/m4/summaries.py <<'PY'
import json, sys
def walk(types, out):
    for t in types.values():
        walk(t.get("types") or {}, out)
        for c in (t.get("callables") or {}).values():
            walk(c.get("types") or {}, out)
            for e in c.get("summary") or []:
                out.append("%s\t%s\t%s" % (c["id"], e["src"], e["dst"]))
d, out = json.load(open(sys.argv[1])), []
for f in d["application"]["symbol_table"].values():
    walk(f.get("types") or {}, out)
print("\n".join(sorted(out)))
PY
python3 /tmp/m4/summaries.py /tmp/m4/before/analysis.json > /tmp/m4/before.txt
python3 /tmp/m4/summaries.py /tmp/m4/after/analysis.json  > /tmp/m4/after.txt
wc -l /tmp/m4/before.txt /tmp/m4/after.txt
diff /tmp/m4/before.txt /tmp/m4/after.txt && echo "EXACT SET MATCH"
shasum -a 256 /tmp/m4/before.txt /tmp/m4/after.txt
git worktree remove /tmp/l3-seedfix
```

```
Preparing worktree (detached HEAD 53a4029)
HEAD is now at 53a4029 feat(l4): interprocedural SDG — param_in/param_out, semantic DDG, summary edges, graph 2.1.0 (#203)
     286 /tmp/m4/before.txt
     286 /tmp/m4/after.txt
     572 total
EXACT SET MATCH
be6b830272fee1fc2af9125ccc1b9bec6dbdffa718d93065d23978f3926d198a  /tmp/m4/before.txt
be6b830272fee1fc2af9125ccc1b9bec6dbdffa718d93065d23978f3926d198a  /tmp/m4/after.txt
```

The same comparison on `l4-sdg-test` (now carrying the two-parameter `Arity` discriminator) is 6
edges on both sides with an empty `diff`. And with rules 2 and 3 disabled in a scratch worktree —
the toggles re-applied on top of the corrected rule 1 — **corrected rule 1 alone** is also 286 and
also `diff`-empty against `before.txt`, which is the row the retirement decision actually rests on:

```bash
java -Dseed.rule2=0 -Dseed.rule3=0 -jar /tmp/l3-r1only/build/libs/codeanalyzer-3.0.0.jar \
  -i "$PWD/src/test/resources/test-applications/daytrader8" -a 4 --no-build -o /tmp/m4/dt-R1only
python3 /tmp/m4/summaries.py /tmp/m4/dt-R1only/analysis.json > /tmp/m4/dt-R1only.txt
wc -l /tmp/m4/before.txt /tmp/m4/dt-R1only.txt
diff /tmp/m4/before.txt /tmp/m4/dt-R1only.txt && echo IDENTICAL
```

```
     286 /tmp/m4/before.txt
     286 /tmp/m4/dt-R1only.txt
     572 total
IDENTICAL
```

**The correction must stay scoped to `@entry`.** Generalising it — `seeds.add(e.getDst())` for
*every* rule-1 edge, which the inference above invites — was measured and **loses three edges**, all
at `TradeDirect.completeOrder(Connection, Integer)` argument 1 (`buy` `338:11`, `completeOrder(Integer,
boolean)` `524:19`, `sell` `445:11`): 283 against `before.txt`'s 286. The mechanism is the *same*
conflation, arriving through a WALA edge instead of a synthetic one — `completeOrder`'s `ddg` carries
`{566:5 → …, var orderID, prov:["points-to"]}`, and `566:5` is the node that defines the returned
`orderData`, so seeding `orderID` there borrows `orderData`'s reach to the `return`.

**A later read of these three edges found they are not a real flow.** The `var` match is nominal, not
semantic: `orderID` here names the field `OrderDataBean.orderID` (`OrderDataBean.java:68`), read off
the returned bean at the `orderData.getOrderID()` call sites this edge's `dst` reaches
(`TradeDirect.java:597,610,623,626,634`) — not `completeOrder(Connection, Integer)`'s own parameter of
the same spelling. That parameter is bound into a JDBC call instead (`stmt.setInt(1,
orderID.intValue())` at `:557`), and the row the query returns only *happens* to carry a column also
named `orderID`; neither engine models that connection. So generalising the correction would not have
dropped a real flow here — it would have removed a same-named-field conflation of exactly the class
the `@entry` fix exists to remove, arriving by name collision with an unrelated field rather than by a
shared synthetic node.

That changes *why* the correction stays scoped to `@entry`, not whether it should. This branch is a
regression fix: its job is restoring the `53a4029` baseline it perturbed — the 286-edge set these
three already belonged to — not auditing every other instance of the same over-seeding. And the L4
posture's asymmetry still applies on its own terms: it forbids under-approximation, and "measured safe
on `l4-sdg-test` and daytrader8" is not the same claim as "safe everywhere" — widening `e.getDst()` to
every rule-1 edge, not just entry-rooted ones, is a precision reform whose burden of proof (that it
drops no real flow on some corpus neither of these two happens to contain) belongs to a follow-on that
can gather it, not to this fix riding in on two corpora's worth of evidence. So `@entry` gets the
correction and ordinary def sites keep `src`, for now.

### Real-application aggregates

`daytrader8`, 1229 callables, 141 source files. Same two jars, `-a 4 --no-build`.

| | before (`53a4029`) | after (`228f270`) | delta |
| --- | --- | --- | --- |
| `ddg` total | 3829 | 5475 | **+1646, 0 lost** |
| — of which `prov:["ssa"]` | 2598 | 4244 | +1646 |
| — of which `prov:["points-to"]` | 1231 | 1231 | **0** |
| — of which rooted at `@entry` | 0 | 1646 | +1646 |
| `summary` total | 286 | 329 | +43, 0 lost (all 43 spurious — above) |
| `param_in` | 1943 | 1943 | 0 |
| `param_out` | 914 | 914 | 0 |

Every added edge is `prov:["ssa"]` and `@entry`-rooted; the WALA-derived half is untouched, which is
the additivity and engine-scope constraint holding on real code.

**Wall clock — no measurable cost.** Three alternating warm runs per side, `/usr/bin/time -p`:

| | run 1 | run 2 | run 3 | median real | median user |
| --- | --- | --- | --- | --- | --- |
| before | 29.98 | 29.98 | 30.14 | **29.98 s** | 62.45 s |
| after | 31.07 | 29.66 | 30.28 | **30.28 s** | 61.59 s |

+0.30 s (+1.0%) on median wall clock, inside the 1.4 s spread of the "after" arm, and median *user*
CPU is slightly **lower** after. A first, discarded pair read 92.99 s (before) against 52.81 s
(after) — that ordering is backwards and both numbers are cold-cache artifacts of first-touching a
35 MB jar and a 20 MB output file; the gap closes entirely once warm. The check this run exists to
make — that seeding `@entry` with every formal does not blow up the reaching-definitions fixpoint on
real code — passes: 43% more `ddg` edges for ~1% more wall clock.

### Recommendation for the follow-on

**Retire rules 2 and 3, but only together with a one-line correction to rule 1 — not before it.**
The redundancy evidence is unambiguous: on both corpora, disabling the call-argument-text rule
(`SummaryPass.java:248-257`) or the whole-body-span-text rule (`:258-262`) loses zero summary edges,
and rule 1 alone reproduces the entire emitted set. That is the retirement case, and entry defs are
what created it — the same experiment run against `53a4029` gives rule 1 alone **zero** edges on the
fixture. But
rule 1 as currently written seeds the shared `@entry` node, which conflates all of a callable's
formals and manufactured 43 false summary edges on daytrader8; retiring the textual rules on top of
that would trade a documented, bounded textual over-approximation for an undocumented semantic one
that is *worse* on exactly the multi-parameter callables the summary pass is most used on. With
`seeds.add(e.getDst())` for entry-rooted edges, corrected rule 1 alone reproduces `daytrader8`'s
pre-change 286-edge set and the fixture's 5-edge set exactly — so the follow-on's order of work
should be: fix the seed, pin the corrected behaviour with a regression test built on the
two-parameter discriminator above, and only then delete `:248-262`. Nothing in the suite can
currently distinguish the two: every test that runs `SummaryPass` — `SummaryPassTest.analyzed()`,
`L4GateTest`, `V2Neo4jSchemaConformanceTest` — runs it over `l4-sdg-test`, and every callable in that
fixture has arity 0 or 1. The fixture needs a multi-parameter callable before the assertion is worth
writing.

Three gaps bound this recommendation. First, both text rules are exercised here only where rule 1
already succeeds; neither corpus contains a case where source text is unavailable to `SummaryPass`
(a module with a null `source` would silently disable rule 3 wholesale — `index()` handles that
case, so it is reachable), so "dead" means dead on the 1243 callables measured, not proven
unreachable. Second, `l4-sdg-test` produced no `points-to` edges at all, so nothing here says how the
rules interact with WALA-derived `ddg` edges beyond the observation that daytrader8's 1231 of them
are unchanged on both sides. Third, the corrected-rule-1 result is measured on two corpora, not
proven; the follow-on should re-derive the argument before relying on it.

---

## Self-Review (performed while writing)

- **Spec coverage:** #204's three goals map to Tasks 1 (entry defs + k-limit parity), 2 (measurement), 3 (spec correction). Its fourth DoD item — `L3 ⊆ L4` still holding and the L4 gate's counts updated — is Task 2 Step 5.
- **Placeholder scan:** the Measurement section is deliberately a template Task 2 fills; every other step carries runnable commands or complete code. Task 2 Step 2's per-rule findings cannot be pre-written because they are the experiment's output, but the procedure and the stop condition (a lost summary edge is a defect) are concrete.
- **Type consistency:** `build(cfg, fieldDepth, formals)` and `L3Overlays.build(..., formals)` use `List<String>` in both the interface block and the code; `JParameter::getName` matches the model; `ControlFlowGraph.ENTRY` is the existing constant, not a new literal.
- **Known risk not designed away:** Task 1 seeds defs from parameter *names*, which collides with a local of the same name in an inner scope — the reaching-definitions kill at the reassignment handles the common case (covered by `aReassignmentKillsTheEntryDefinition`), but a catch parameter or a lambda parameter shadowing a formal is not covered by any test here and is called out in #204's caveats. If Task 2's measurement shows spurious edges from shadowing, that is a finding for the follow-on, not a reason to hold Task 1.
