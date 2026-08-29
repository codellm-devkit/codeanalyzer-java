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

D7 currently reads, in both places, that the summary pass mirrors `codeanalyzer-python`'s hammock-region summaries. It does not: that repo has no region machinery at all — searching its dataflow package for `hammock` or `region` returns nothing, and its `summaries.py` module docstring states the opposite posture deliberately ("at statement granularity a callsite node is already a transformer, so the composition step callee summaries actually contribute is the *global footprint*").

Rewrite both mentions to say what is true: the pass composes bottom-up over the SCC condensation with a monotone fixpoint (which both analyzers do), that `codeanalyzer-python` reaches its transfer relation at statement granularity rather than by region decomposition, and that region decomposition remains an open refinement for either analyzer rather than an existing precedent. Keep the rest of D7 — SCC condensation, k-limiting, fixpoint, "heaviest unit, sequenced last" — as written; only the parity claim is wrong.

Add one sentence recording that Java is better positioned than the reference for a future region pass, since it already persists both `cfg` and `cdg` on the callable and has Cooper–Harvey–Kennedy post-dominators in `CdgBuilder`.

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

*Task 2 fills this in. Leave the headings; replace the placeholder text.*

### Fixture deltas, per callable

*(new `ddg` edges as full tuples; `summary` edges gained)*

### Seeding-rule redundancy

*(per rule: what disappears when it is disabled)*

### Real-application aggregates

*(edge deltas and wall-clock, on a fixture of realistic size)*

### Recommendation for the follow-on

*(which rules to retire, and what must stay)*

---

## Self-Review (performed while writing)

- **Spec coverage:** #204's three goals map to Tasks 1 (entry defs + k-limit parity), 2 (measurement), 3 (spec correction). Its fourth DoD item — `L3 ⊆ L4` still holding and the L4 gate's counts updated — is Task 2 Step 5.
- **Placeholder scan:** the Measurement section is deliberately a template Task 2 fills; every other step carries runnable commands or complete code. Task 2 Step 2's per-rule findings cannot be pre-written because they are the experiment's output, but the procedure and the stop condition (a lost summary edge is a defect) are concrete.
- **Type consistency:** `build(cfg, fieldDepth, formals)` and `L3Overlays.build(..., formals)` use `List<String>` in both the interface block and the code; `JParameter::getName` matches the model; `ControlFlowGraph.ENTRY` is the existing constant, not a new literal.
- **Known risk not designed away:** Task 1 seeds defs from parameter *names*, which collides with a local of the same name in an inner scope — the reaching-definitions kill at the reassignment handles the common case (covered by `aLocalShadowingTheFormalKillsTheEntryDefinition`), but a catch parameter or a lambda parameter shadowing a formal is not covered by any test here and is called out in #204's caveats. If Task 2's measurement shows spurious edges from shadowing, that is a finding for the follow-on, not a reason to hold Task 1.
