# L2 output comparison: legacy v1 call graph vs canonical schema v2

Generated 2026-08-22 from the `codeanalyzer-2.4.1` build on the issue-181 branch. Each of ten
real-world fixture applications was analysed twice at analysis level 2 — once with the default (v1)
emitter, once with `--schema v2` — and the call graphs diffed. This is the L2 companion to
[`l1-v1-v2-comparison.md`](l1-v1-v2-comparison.md): that note tracked the containment tree, this one
tracks the edge overlay.

**Both emitters build the application and run the same WALA RTA over the same compiled bytecode.** The
differences below are therefore *not* differences in call-graph construction — WALA behaves identically
in both. They are differences in what each emitter does with that graph, and in the second analysis v2
adds on top of it.

## How to reproduce

```bash
./gradlew fatJar
JAR=build/libs/codeanalyzer-2.4.1.jar
APP=src/test/resources/test-applications/commons-lang
java -jar $JAR -i $APP -o output/l2/commons-lang/v1 -a 2                 # legacy WALA-only graph
java -jar $JAR -i $APP -o output/l2/commons-lang/v2 --schema v2 -a 2     # declared + rta overlay
```

`--schema v2 --no-rta` skips the build and emits the `declared` edges alone. Payloads land in
`output/<app>/<schema>/analysis.json` (`output/` is git-ignored); the figures below are computed from
those files by `output/l2/analyze.py`, so they cannot drift from the data.

## The shape change

v1 emits a flat `call_graph` array of `CALL_DEP` records, each carrying a `source` and `target`
*display vertex* (`file_path`, `type_declaration`, `signature`, `callable_declaration`) and a string
`weight`. The graph is WALA's RTA graph, filtered to edges whose target is an application class;
endpoints are display names, not ids, so nothing joins it to the symbol table.

v2 emits two application-scope surfaces keyed to the containment tree:

- **`call_graph`** — one edge per `(src, dst)`, both durable `can://` ids, with `prov` (the set-union
  of the analyses that attest it, from `["declared", "rta"]`) and an integer `weight`.
- **`external_symbols`** — out-of-project callables the edges point at, homed under `@external`
  can-ids so no edge dangles.

and it backfills each resolved `call` body node's `callee` with the target id — the correspondence
between a call site and a graph edge that v1 never had.

```jsonc
// v1: a display-name record, WALA-only, application targets only
{"type": "CALL_DEP", "weight": "1",
 "source": {"file_path": "<<implicit>>", "type_declaration": "…owner.Pet", "signature": "Pet()", "callable_declaration": "<init>()"},
 "target": {"file_path": "<<implicit>>", "type_declaration": "…model.NamedEntity", "signature": "NamedEntity()", "callable_declaration": "<init>()"}}

// v2: identity edges with provenance; a library target homed rather than dropped
{"src": "can://java/commons-lang/…/ArrayUtils/MathBridge/addExact(int, int)",
 "dst": "can://java/commons-lang/@external/java.lang.Math/addExact(int, int)",
 "prov": ["declared", "rta"], "weight": 1}
```

## Runs

Eighteen of twenty runs exited 0. **v1 `-a 2` failed outright on two applications** (see below); v2
emitted a call graph on all ten.

| Application | v1 time | v2 time | v1 size | v2 size |
| --- | --- | --- | --- | --- |
| `spring-petclinic` | 22s | 25s | 2.5M | 4.3M |
| `cargotracker` | 20s | 17s | 5.6M | 8.0M |
| `commons-lang` | 622s | 147s | 128M | 181M |
| `quarkuscoffeeshop-counter` | 53s | 15s | 1.8M | 2.8M |
| `quarkuscoffeeshop-barista` | 13s | 12s | 452K | 846K |
| `quarkuscoffeeshop-kitchen` | 11s | 11s | 371K | 648K |
| `quarkuscoffeeshop-inventory` | 15s | 14s | 453K | 755K |
| `quarkuscoffeeshop-domain` | **failed** | 3s | — | 563K |
| `daytrader8` | 14s | 13s | 10.4M | 16.0M |
| `plantsbywebsphere` | **failed** | 3s | — | 4.3M |

**v2 is faster where it matters.** On `commons-lang` v2 runs in 147s against v1's 622s — v1 pays for
per-callable `code` strings (the L1 note's finding); both run the same WALA RTA on top.

## What each side contains, and how it reconciles

The reconciliation is the point of this note. v1's whole graph is WALA RTA restricted to in-project
targets. v2 keeps that same RTA graph (as its `rta` provenance), homes the external targets v1 drops,
**and adds a second analysis — `declared`, from JavaParser's symbol solver — that v1 never ran.**

| Metric | v1 | v2 |
| --- | --- | --- |
| call-graph edges | 3,485 | 53,164 |
| — in-project (app→app) | 3,485 | 20,520 |
| — to external / library | 0 (dropped) | 32,644 |
| edges attested by `declared` (JavaParser) | — | 41,181 |
| edges attested by `rta` (WALA) | 3,485 | 15,512 |
| — of which in-project | 3,485 | 2,601 |
| — of which external | 0 | 12,911 |
| distinct external symbols | 0 | 5,265 |
| self-edges (direct recursion) | 0 | 74 |
| fabricated `<<implicit>>` callables | 164 | 0 |
| call sites linked to a callee | 0 | 93,675 / 94,917 (98.7%) |

**v1's 3,485 edges reconcile against v2's `rta` in-project edges (2,601), not against v2's total.** They
are the same WALA graph's application-to-application edges, comparable in magnitude. They are not
identical: in aggregate v1 runs somewhat higher because it also emits edges into the 164 fabricated
`<<implicit>>` callables and identifies edges by the full display vertex, where v2 dedups on
`(src, dst)` and drops targets absent from the tree — though on an individual application either can
edge ahead (commons-lang is 21 for v1 against 37 for v2). Everything else in v2 — the 12,911 external
`rta` edges and the entire 41,181-edge `declared` analysis — is net new.

## Per-application: the same WALA graph, plus what v2 adds

`v1 edges` and `v2 rta app→app` are the same analysis (WALA RTA, in-project). `v2 rta →ext` is the
library edges v1 drops; `v2 declared` is the analysis v1 never ran; `v2 total` is the emitted graph.

| Application | v1 edges | v2 rta app→app | v2 rta →ext | v2 declared | v2 total |
| --- | --- | --- | --- | --- | --- |
| `spring-petclinic` | 83 | 79 | 925 | 1,127 | 1,990 |
| `cargotracker` | 909 | 520 | 1,771 | 1,456 | 3,018 |
| `commons-lang` | 21 | 37 | 660 | 33,728 | 34,399 |
| `quarkuscoffeeshop-counter` | 260 | 149 | 894 | 574 | 1,333 |
| `quarkuscoffeeshop-barista` | 33 | 13 | 261 | 160 | 381 |
| `quarkuscoffeeshop-kitchen` | 40 | 11 | 170 | 115 | 249 |
| `quarkuscoffeeshop-inventory` | 47 | 26 | 131 | 119 | 216 |
| `quarkuscoffeeshop-domain` | — | 0 | 0 | 63 | 63 |
| `daytrader8` | 2,092 | 1,766 | 8,099 | 3,181 | 10,857 |
| `plantsbywebsphere` | — | 0 | 0 | 658 | 658 |

## Where v2 records more than v1

- **A second, build-free analysis.** v2's 41,181 `declared` edges come from JavaParser's symbol solver
  over every resolved call site — no build, no reachability pruning. This is the bulk of v2's graph and
  has no v1 analogue.
- **External targets are homed, not dropped.** v1 filters its graph to application classes, so every
  call into the JDK or a library vanishes — including the 12,911 library edges its own RTA found. v2
  homes 5,265 distinct external symbols and keeps all 32,644 edges reaching them, so `List.add`,
  `String.valueOf` and framework entry points are navigable rather than invisible.
- **Call sites are linked to edges.** v2 backfills `callee` on 93,675 of 94,917 `call` body nodes
  (98.7%); the rest are honest-unresolved sites and the record `equals`/`hashCode`/`toString` blind
  spot. v1 had no correspondence between its `call_sites` and its `CALL_DEP` edges.
- **Self-edges are kept** (74); v1 dropped every one via a `!source.equals(target)` guard.

## Where v1 did things v2 deliberately does not

Neither is a v2 loss — both are v1 defects v2 corrects.

- **Fabricated `<<implicit>>` callables (164).** When RTA resolved a call to a compiler-generated member
  (a default constructor, `values()`), v1 invented a graph vertex with `file_path: "<<implicit>>"` —
  pointing at no source. v2 instead synthesizes the real callable into the tree at L1 (implicit
  constructors, record accessors, enum `values()`/`valueOf`) so the edge lands on a genuine node, or
  homes / drops a target that is genuinely external or synthetic. No edge points at a fabricated
  location.
- **Ambiguous demangled names.** v1 built endpoint names with `.replace("$", ".")`, so a nested type
  `Map$Entry` and a package member `Map.Entry` are indistinguishable. v2 ids carry the binary name
  (`java.util.Map$Entry`) — unambiguous, and the exact spelling WALA emits, which is what lets the `rta`
  overlay join the `declared` graph.

## What RTA actually contributes, and why it is an overlay not the producer

RTA's in-project yield varies enormously by application, and this is the empirical case for the
issue-181 decision to make JavaParser the producer and WALA the overlay:

- On **`commons-lang`**, WALA RTA finds **37** in-project edges; `declared` finds **16,605**. WALA
  resolves almost no internal edges here, so a WALA-only graph (v1) is nearly empty (21 edges) while
  v2's `declared` analysis is near-complete (it backfills 83,013 of 83,949 call sites). This is the same
  WALA in both emitters — v1 is not "worse at WALA," it simply has nothing else.
- On **`daytrader8`**, the opposite: WALA RTA finds **1,766** in-project edges against `declared`'s
  **1,501**. The dispatch fan-out RTA adds over static resolution is real and dominant on
  interface-heavy EE code — which is exactly what `rta`-only provenance (11,983 edges across the ten
  apps) captures.

So the two analyses are complementary, `prov` records which found each edge, and the graph no longer
depends on WALA being productive on a given codebase. Why WALA RTA resolves so few in-project edges on
`commons-lang` specifically is a WALA-analysis question outside this comparison; what matters here is
that v2's coverage does not hinge on it.

## v1 fails where v2 degrades: the two build failures

`quarkuscoffeeshop-domain` and `plantsbywebsphere` both failed v1 `-a 2` (exit 1, no output): v1's call
graph requires a successful build, and when the build did not produce the classes WALA needed, the whole
analysis aborted. v2 emitted 63 and 658 `declared` edges respectively — with `rta` empty, because the
same build failure denied WALA its input. This is the design's "`-a 2` never fails for want of a build":
`declared` edges need only the dependency jars, so a build failure downgrades the overlay rather than
failing the level.

## Outstanding follow-ups

1. **Dataflow overlays (L3/L4)** — `cfg`, `cdg`, `ddg`, `param_in`/`param_out` remain unpopulated;
   `prov` gains `ssa` and `points-to` when they land.
2. **Nested-type parameters in `rta` signatures.** WALA's `getClassName()` renders a nested-type
   parameter with `$` while JavaParser renders it dotted, so the rare edge whose signature contains a
   nested-type parameter fails the `rta` join and is dropped — the `declared` edge still attests it.
3. **Anonymous/local numbering** (`$anon$N` vs javac's `Outer$1`) means RTA edges touching anonymous
   classes can fail the identity join and are dropped; no edge is lost, only the `rta` tag.
