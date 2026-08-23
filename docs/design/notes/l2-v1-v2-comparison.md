# L2 output comparison: legacy v1 call graph vs canonical schema v2

Generated 2026-08-22 from the `codeanalyzer-2.4.1` build on the issue-181 branch. Each of ten
real-world fixture applications was analysed at analysis level 2 with the default (v1) emitter and with
`--schema v2` (in both `--external-calls` modes), and the call graphs diffed. This is the L2 companion
to [`l1-v1-v2-comparison.md`](l1-v1-v2-comparison.md): that note tracked the containment tree, this one
tracks the edge overlay.

**Both emitters build the application and run the same WALA RTA over the same compiled bytecode.** The
differences below are not differences in call-graph construction — WALA behaves identically in both.
They are differences in what each emitter does with that graph, and in the second analysis (`declared`)
that v2 adds on top of it. Producing this comparison also surfaced a real scope bug, now fixed; that
story comes first because it moves every commons-lang number below.

## How to reproduce

```bash
./gradlew fatJar
JAR=build/libs/codeanalyzer-2.4.1.jar
APP=src/test/resources/test-applications/commons-lang
java -jar $JAR -i $APP -o out/v1  -a 2                                # legacy WALA-only graph
java -jar $JAR -i $APP -o out/v2  -a 2 --schema v2                    # declared + rta, in-project only
java -jar $JAR -i $APP -o out/v2x -a 2 --schema v2 --external-calls   # + external targets homed
```

Payloads land in `output/<app>/<schema>/analysis.json` (`output/` is git-ignored); the figures are
computed by `output/l2/analyze.py`. External-off numbers are the external-on run minus its `@external`
edges/symbols (the in-project edges are identical).

## A scope bug this comparison surfaced — and its fix

The first pass showed WALA producing almost nothing on `commons-lang`: **21 edges** for a 625-file
library. Investigation (`-a 2 -v` logs the class-hierarchy counts) found the cause was *upstream of the
call-graph algorithm*: WALA's class hierarchy held 41,106 total classes but only **14 application
classes** of the 435 compiled, so RTA ran over ~3% of the project. The 421 "missing" classes were not
dropped — they were bound to WALA's **Extension** (library) loader, because `commons-lang` declares a
released copy of itself (`org.apache.commons:commons-lang3:3.20.0:test`, for benchmarks) and the
analyzer's `dependency:copy-dependencies` pulls it. WALA binds a duplicated class to the parent loader,
so `isApplicationClass` excluded them; the 14 survivors were exactly the classes *new* since 3.20.0.

It was therefore never RTA, the class-loader factory, or the bytecode version (all classes are Java-8
bytecode). The fix is in `ScopeUtils`: build the application classes first and **skip any dependency jar
that redefines one**, keeping the project's own bytecode authoritative. Because scope construction is
shared, v1 and v2 — and future L3/L4, which build IRs and pointer analysis over the same hierarchy —
all benefit.

| commons-lang | before fix | after fix |
| --- | ---: | ---: |
| application classes in CHA | 14 | **435** |
| WALA entrypoints | 73 | **5,141** |
| v1 edges | 21 | **16,183** |
| v2 `rta` app→app edges | 37 | **6,976** |
| v2 edges (`--external-calls`) | 34,399 | **66,533** |

All figures below are **post-fix**. The other nine fixtures are unaffected (none depends on a released
copy of itself — verified: `daytrader8`'s payload is byte-identical before and after), so their numbers
carry over unchanged.

## Runs

Eighteen of twenty runs exited 0; **v1 `-a 2` failed outright on two applications** (see below). Sizes
shown are the `--external-calls` run; the default (external-off) payload is smaller.

| Application | v1 time | v2 time | v1 size | v2 size |
| --- | --- | --- | --- | --- |
| `spring-petclinic` | 22s | 25s | 2.5M | 4.3M |
| `cargotracker` | 20s | 17s | 5.6M | 8.0M |
| `commons-lang` | 637s | 155s | 148M | 202M |
| `quarkuscoffeeshop-counter` | 53s | 15s | 1.8M | 2.8M |
| `quarkuscoffeeshop-barista` | 13s | 12s | 452K | 846K |
| `quarkuscoffeeshop-kitchen` | 11s | 11s | 371K | 648K |
| `quarkuscoffeeshop-inventory` | 15s | 14s | 453K | 755K |
| `quarkuscoffeeshop-domain` | **failed** | 3s | — | 563K |
| `daytrader8` | 14s | 13s | 10.4M | 16.0M |
| `plantsbywebsphere` | **failed** | 3s | — | 4.3M |

## The shape change

v1 emits a flat `call_graph` array of `CALL_DEP` records, each with a `source`/`target` *display vertex*
(`file_path`, `type_declaration`, `signature`, `callable_declaration`) and a string `weight`. The graph
is WALA RTA filtered to application-class targets; endpoints are display names, so nothing joins it to
the symbol table.

v2 emits identity edges keyed to the containment tree — `call_graph` (one edge per `(src, dst)` of
durable `can://` ids, with `prov` ⊆ `["declared","rta"]` and an integer `weight`) and, under
`--external-calls`, `external_symbols` homing out-of-project targets — and backfills each resolved
`call` body node's `callee`, the site↔edge correspondence v1 never had.

```jsonc
// v1: display-name record, WALA-only, application targets only
{"type":"CALL_DEP","weight":"1",
 "source":{"file_path":"…/StringUtils.java","type_declaration":"…lang3.StringUtils","signature":"abbreviate(java.lang.String, int)","callable_declaration":"abbreviate(String, int)"},
 "target":{"file_path":"…/StringUtils.java","type_declaration":"…lang3.StringUtils","signature":"abbreviate(java.lang.String, java.lang.String, int, int)","callable_declaration":"…"}}

// v2: identity edge with provenance (both analyses attest this one)
{"src":"can://java/commons-lang/…/StringUtils/abbreviate(java.lang.String, int)",
 "dst":"can://java/commons-lang/…/StringUtils/abbreviate(java.lang.String, java.lang.String, int, int)",
 "prov":["declared","rta"],"weight":1}
```

## Totals across all ten applications

v2 is shown in both modes: **default** (external off, matching v1's application-only graph) and
**`--external-calls`** (external targets homed).

| Metric | v1 | v2 (default) | v2 (`--external-calls`) |
| --- | ---: | ---: | ---: |
| call-graph edges | 19,647 | 23,499 | 85,298 |
| — attested by `declared` | — | 20,019 | 41,181 |
| — attested by `rta` | 19,647 † | 9,540 | 54,671 |
| — attested by both | — | ⊆ above | 10,554 |
| edges to external / library | 0 (dropped) | 0 (dropped) | 61,799 |
| distinct external symbols | 0 | 0 | 7,797 |
| self-edges (direct recursion) | 0 | 153 | 153 |
| fabricated `<<implicit>>` callables | 685 | 0 | 0 |
| call sites linked to a callee | 0 | 93,675 / 94,917 | 93,675 / 94,917 |

† v1's whole graph is its RTA graph — the closest analogue to v2's `rta` column, but counted very
differently and *not* a like-for-like row. v1's raw count runs far higher for structural reasons, only
a small part of which is the fabricated callables; see "Why v1's raw count exceeds v2's `rta`" below.

## Per-application call graph

`v2 declared` / `v2 rta` are the in-project edge counts each analysis attests (an edge attested by both
is in both). Blank v1 cells are the two runs that failed.

| Application | v1 edges | v2 default | v2 `--external-calls` | v2 `declared` (in-proj) | v2 `rta` (in-proj) | external syms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `spring-petclinic` | 83 | 291 | 1,990 | 282 | 79 | 618 |
| `cargotracker` | 909 | 858 | 3,018 | 777 | 520 | 611 |
| `commons-lang` | 16,183 | 19,610 | 66,533 | 16,605 | 6,976 | 4,422 |
| `quarkuscoffeeshop-counter` | 260 | 270 | 1,333 | 264 | 149 | 333 |
| `quarkuscoffeeshop-barista` | 33 | 40 | 381 | 40 | 13 | 218 |
| `quarkuscoffeeshop-kitchen` | 40 | 27 | 249 | 27 | 11 | 148 |
| `quarkuscoffeeshop-inventory` | 47 | 37 | 216 | 33 | 26 | 126 |
| `quarkuscoffeeshop-domain` | — | 10 | 63 | 10 | 0 | 18 |
| `daytrader8` | 2,092 | 1,876 | 10,857 | 1,501 | 1,766 | 1,211 |
| `plantsbywebsphere` | — | 480 | 658 | 480 | 0 | 92 |

## Why v1's raw count exceeds v2's `rta`

v1's raw edge count (16,183 on commons-lang) sits well above v2's in-project `rta` (6,976), and only a
small part is the fabricated callables. v1 emits an edge for every WALA app→app edge under WALA's own
names, with no requirement that an endpoint be a *declared* method; v2 counts an edge only when both
endpoints join a callable present in the source tree. Categorizing v1's commons-lang edges by whether
v2 can join each endpoint:

| v1 edge population | count | v2 |
| --- | ---: | --- |
| both endpoints joinable | 9,610 | kept (≈ v2's `rta`) |
| synthetic-method endpoint (`lambda$`, `access$`, `$values`, `<clinit>`, bridge) | 5,507 | dropped — no source declaration |
| fabricated `<<implicit>>` callable | 426 | dropped |
| nested-type-param signature (`Map$Entry` vs `Map.Entry`) | 423 | dropped — join mismatch |
| anonymous / local class (`Outer$1` vs `$anon$N`) | 217 | dropped — different numbering |

So ~40% of v1's edges touch an endpoint v2 structurally cannot, or deliberately will not, join —
**compiler synthetics dominate, not the fabricated callables**. The residual between the 9,610 joinable
edges and v2's 6,976 is counting granularity: v1 dedups by a four-field display vertex, demangles
`$`→`.` (collapsing distinct types), and drops self-edges, where v2 dedups by `(src, dst)` can-id and
keeps them. Normalising the representations, the two graphs overlap on the great majority of edges — v2
is not missing real calls, it is declining to emit edges to endpoints with no real source callable (the
no-dangling / no-fabrication contract). The same forces apply to every app; commons-lang is shown
because its post-fix graph is the largest.

## `--external-calls`: default off for v1 parity

v1 kept only edges whose target is an application class. To keep the default v2 `-a 2` at parity during
the migration, external targets are gated behind `--external-calls` (off by default). With it off, a
call resolving out of the project is dropped exactly like an unresolved one — no `callee`, no edge, no
`external_symbols` entry (an empty map is omitted, not emitted as `{}`), so the default graph is
in-project only, as v1's was. With it on, out-of-project targets are homed so no edge dangles — 61,799
edges to 7,797 external symbols across the ten apps, the bulk of the full graph. The `declared`/`rta`
in-project edges are identical in both modes; only the external overlay differs.

## Where v2 records more than v1

- **A second, build-free analysis.** v2's 41,181 `declared` edges come from JavaParser's symbol solver
  over every resolved call site — no build, no reachability pruning. It is the analysis v1 never ran.
  Example (`declared`-only, so WALA's RTA missed it):
  `StringUtils.compare(String, String) → Strings.compare(String, String)`.
- **External targets are navigable, not dropped** (with `--external-calls`): e.g.
  `StringUtils.abbreviate(String, String, int, int) → java.lang.IllegalArgumentException.<init>(String)`,
  homed under `external_symbols` — invisible in v1.
- **Call sites are linked to edges.** v2 backfills `callee` on 93,675 of 94,917 `call` body nodes
  (98.7%); v1 had no correspondence between its `call_sites` and `CALL_DEP` edges.
- **Provenance distinguishes the analyses**, e.g. the both-attested
  `StringUtils.abbreviate(String, int) → StringUtils.abbreviate(String, String, int, int)` vs a
  `declared`-only or `rta`-only edge.
- **Self-edges are kept** (153); v1 dropped every one via a `!source.equals(target)` guard.

## Where v1 does things v2 deliberately does not

Neither is a v2 loss — both are v1 defects v2 corrects.

- **Fabricated `<<implicit>>` callables (685).** When RTA resolved a call to a compiler-generated
  member, v1 invented a graph vertex with `file_path: "<<implicit>>"`, pointing at no source. v2
  synthesizes the real callable into the tree at L1 (implicit constructors, record accessors, enum
  `values()`/`valueOf`) so the edge lands on a genuine node, or homes/drops a genuinely external or
  synthetic target.
- **Ambiguous demangled names.** v1 built endpoint names with `.replace("$", ".")`, conflating a nested
  type `Map$Entry` with a package member `Map.Entry`. v2 ids carry the binary name, which is also the
  spelling WALA emits — what lets the `rta` overlay join the `declared` graph.

## What RTA contributes, now that the scope bug is fixed

With the full application admitted, WALA RTA is productive again, and its value over static resolution
is real and app-dependent:

- On **`daytrader8`**, RTA finds **1,766** in-project edges against `declared`'s **1,501**: dispatch
  fan-out on interface-heavy EE code, exactly what `rta`-only provenance (44,117 edges across the ten
  apps, mostly to homed library targets) captures.
- On **`commons-lang`**, `declared` still leads in-project (16,605 vs `rta`'s 6,976) — a
  static-utility library has less dynamic dispatch — but RTA now attests 6,976 in-project edges, up
  from 37, many of them overlapping `declared`, where before the fix it saw almost nothing.

The two analyses are complementary and `prov` records which found each edge; crucially, the graph no
longer *depends* on WALA loading a codebase, because `declared` stands alone when RTA is thin or absent.

## v1 fails where v2 degrades: the two build failures

`quarkuscoffeeshop-domain` and `plantsbywebsphere` both failed v1 `-a 2` (exit 1, no output): v1's call
graph requires a successful build. v2 emitted 63 and 658 edges respectively — with `rta` empty, because
the same build failure denied WALA its input, but `declared` needs only the dependency jars. This is the
design's "`-a 2` never fails for want of a build".

## Outstanding follow-ups

1. **Dataflow overlays (L3/L4)** — `cfg`, `cdg`, `ddg`, `param_in`/`param_out` remain unpopulated;
   `prov` gains `ssa` and `points-to` when they land. They build on the same WALA hierarchy, so the
   scope fix above is a prerequisite for them to cover the full application.
2. **Whole-jar dependency skip.** The scope fix skips a dependency jar entirely when it redefines *any*
   application class — correct for a self-artifact (100% overlap); a partial-overlap jar would also
   lose its non-colliding classes (their edges then fall to external/phantom). A per-entry exclusion is
   possible if this proves to matter.
3. **Nested-type parameters in `rta` signatures** and **anonymous/local numbering** (`$anon$N` vs
   javac's `Outer$1`) still cost a small number of `rta` joins; the `declared` edge attests those, so no
   edge is lost, only the `rta` tag.
