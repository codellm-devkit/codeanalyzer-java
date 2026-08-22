# L1 output comparison: legacy v1 schema vs canonical schema v2

Generated 2026-08-19 from `codeanalyzer-2.4.1`. Each of ten real-world fixture applications was analysed
twice — once with the default (v1) emitter, once with `--schema v2` — and the payloads diffed field by
field. The purpose is to catch silent information loss in the migration: every metric where v2 records
less than v1 is either explained or fixed.

## How to reproduce

```bash
./gradlew fatJar
JAR=build/libs/codeanalyzer-2.4.1.jar
APP=src/test/resources/test-applications/spring-petclinic
java -jar $JAR -i $APP -o output/spring-petclinic/v1 -a 1           # legacy
java -jar $JAR -i $APP -o output/spring-petclinic/v2 --schema v2    # canonical
```

Payloads land in `output/<app>/<schema>/analysis.json` (`output/` is git-ignored). The figures in this
document are generated from those files, so it cannot drift from the data.

## Runs

All twenty runs exited 0 and left the fixture submodules clean.

| Application | v1 time | v2 time | v1 size | v2 size |
| --- | --- | --- | --- | --- |
| `spring-petclinic` | 5s | 4s | 2.4M | 2.9M |
| `cargotracker` | 17s | 4s | 4.7M | 5.8M |
| `commons-lang` | 623s | 148s | 128M | 142M |
| `quarkuscoffeeshop-counter` | 3s | 3s | 1.5M | 1.9M |
| `quarkuscoffeeshop-barista` | 3s | 2s | 425K | 575K |
| `quarkuscoffeeshop-kitchen` | 2s | 2s | 342K | 460K |
| `quarkuscoffeeshop-inventory` | 3s | 3s | 415K | 581K |
| `quarkuscoffeeshop-domain` | 2s | 2s | 343K | 491K |
| `daytrader8` | 5s | 4s | 8.5M | 10M |
| `plantsbywebsphere` | 3s | 2s | 3.0M | 3.7M |

**v2 is consistently faster.** It never builds per-callable `code` strings, so it never invokes
JavaParser's `LexicalPreservingPrinter` — the dominant cost on large projects. (A second-order effect:
each v2 run reused dependency jars the preceding v1 run had already downloaded.)

**Incremental caching** (`-c/--cache-dir`) reuses modules whose files are byte-for-byte unchanged,
skipping both the parse and the build: a second `commons-lang` run drops from 130s to 4s. The timings
above are all cold runs, so they measure the emitters rather than the cache.

**v2 payloads are somewhat larger** even though per-callable `code` is gone: source text is stored once
per module rather than duplicated per callable, but that saving is outweighed by spans on every node
(`start`/`end`/`bytes`), per-node comments, local variables, and the resolved call-site facts.

## Totals across all ten applications

| Metric | v1 | v2 | Delta |
| --- | --- | --- | --- |
| modules | 1081 | 1081 | +0 |
| types | 1581 | 1814 | +233 |
| callables | 13594 | 13850 | +256 |
| fields | 3727 | 3761 | +34 |
| parameters | 9877 | 10077 | +200 |
| call sites | 94501 | 94917 | +416 |
| local variables | 11650 | 11639 | -11 * |
| comment entries | 39170 | 9710 | -29460 * |
| enum constants | 338 | 338 | +0 |
| record components | 2 | 2 | +0 |
| initializer blocks | 30 | 29 | -1 * |
| entrypoint types | 95 | 95 | +0 |
| entrypoint callables | 258 | 258 | +0 |
| CRUD facts | 107 | 0 | -107 * |

\* explained below. Of the four, two (`local variables`, `initializer blocks`) turn out to be v1
over-counting rather than v2 losses; one (`comment entries`) is mostly v1 duplication with a small real
gap; and one (`CRUD facts`) is deliberately deferred.

**Type resolution is at parity:** 95.7% of v1 parameter types and 95.8% of v2 parameter types are
fully qualified. v2 additionally resolves a callee signature on 94152 of 94917 call sites (99%), which v1
recorded only on its separate `call_sites` entries.

**Identity:** all 1081 v1 `symbol_table` keys are absolute filesystem paths; v2 has 0 absolute keys —
every key is project-relative, which the canonical schema requires for stable caching and SDK lookups.

**Anonymous classes:** 215 are modelled as their own `type` nodes across the ten applications.
**Body text:** 13589 callables carry a `body_span`.

## Per-application detail

Metrics where the two schemas differ, per application. Blank means exact parity.

| Application | types | callables | call sites | locals | initializers | comments | CRUD |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `spring-petclinic` | +3 | +2 |  |  |  | -225 |  |
| `cargotracker` | +2 | +4 | +78 | +3 |  | -501 | -77 |
| `commons-lang` | +218 | +237 | +296 | -14 | -1 | -25342 |  |
| `quarkuscoffeeshop-counter` | +3 | +3 | +3 |  |  | -71 |  |
| `quarkuscoffeeshop-barista` | +1 | +1 | +2 |  |  | -16 |  |
| `quarkuscoffeeshop-kitchen` | +1 | +1 | +3 |  |  | -65 |  |
| `quarkuscoffeeshop-inventory` | +1 | +1 | +3 |  |  | -23 |  |
| `quarkuscoffeeshop-domain` |  |  | +12 |  |  | -6 |  |
| `daytrader8` | +4 | +7 | +17 |  |  | -2244 | -30 |
| `plantsbywebsphere` |  |  | +2 |  |  | -967 |  |

## Where v2 recovers more than v1

- **Types, callables and call sites.** v1 keyed its flat type map by fully-qualified name and skipped
  declarations without one, so **local classes declared inside method bodies were dropped entirely**;
  v2 nests them under the enclosing callable. v1's call-site scan also missed **explicit constructor
  chaining** (`this(...)` / `super(...)`), which v2 emits as `call` nodes so L2 can resolve those edges.
- **Anonymous inner classes** are modelled as `type` nodes (`$anon$0`, `$anon$1`, … in declaration
  order) under the callable that declares them, so their methods, initializers, locals and call sites
  are attributed to them. v1 recursed into anonymous bodies and mis-attributed those facts to the
  *enclosing type*.
- **Resolved call-site facts** — callee signature, receiver expression and type, argument types — sit on
  the body `call` nodes.
- **Structured annotation arguments.** v1 stored annotations as flat strings (`@RequestMapping("/x")`);
  v2 records `{name, args[], span}`, so routes and column names are machine-readable without re-parsing.

## Where v2 records less, and why

### Comment entries (-29460): v1 double-counting, plus one real gap

v1 filled every node's `comments` with `getAllContainedComments()`, so a comment inside a method was
also listed on that method's type and on the compilation unit. On `spring-petclinic` v1 emits 341
comment entries of which only **163 are distinct** (a 2.09x duplication factor); v2 emits 116, each
attached to exactly one node.

The remaining ~47 distinct comments v2 does not carry are **comments inside method bodies**, which
have no declaration to attach to. They stay recoverable from `module.source`, and they belong on the
statement nodes that arrive at L3 — but today they are absent from the tree. This is the one
outstanding information gap.

### Local variables (-11) and initializer blocks (-1): v1 over-counting

Both remaining deltas are **v1 defects**, not v2 losses — v2 is the more accurate of the two.

*Locals.* v1 collected a callable's locals with a recursive `findAll(VariableDeclarator)`, which also
matches **field declarations inside anonymous classes**. In `AtomicInitializerObjectTest`:

```java
final AtomicInitializer<Object> initializer = new AtomicInitializer<Object>() {
    final AtomicBoolean firstRun = new AtomicBoolean(true);   // a field of the anonymous class
    ...
};
```

v1 reports the enclosing method's locals as `[initializer, firstRun]`, promoting the anonymous class's
field to a method local. v2 reports `[initializer]` and records `firstRun` under
`$anon$0.fields`, where it belongs. Every one of the remaining local-variable differences is this
pattern.

*Initializer blocks.* v1 populated a type's `initialization_blocks` with a recursive `findAll`, so a
`static { ... }` block in a nested class was counted **twice**: once on the nested class and again on
its enclosing type. `LocaleUtils` shows this — v1 reports one block on `LocaleUtils` and one on
`LocaleUtils.SyncAvoid`, though only `SyncAvoid` has a block. v2 counts it once, on `SyncAvoid`.

### CRUD facts (-107): tracked separately

v2 carries no CRUD data yet. This is deliberate and tracked in codeanalyzer-java issue 187, which also
covers the Neo4j `JCrudOperation`/`JCrudQuery` families that the graph projection needs.

## Body text: v1 `code` versus a v2 slice

v2 has no per-callable `code` string — body text is a slice of `module.source`. That equivalence needs
care, because a callable's own `span` covers the **whole declaration** (modifiers, signature and body),
whereas v1's `code` was the `{ … }` **block alone**. `callable.body_span` delimits the block, so:

```
source[body_span.bytes]   ==   v1 callable.code      (byte for byte)
source[span.bytes]        ==   declaration + body
```

A test compares the two emitters directly on the same source for methods, constructors and initializer
blocks, so this cannot regress silently. Note that the canonical schema defines `get_method_body(sig)`
as `module.source[callable.span.bytes]`, which is *not* v1's `code` semantics — a discrepancy worth
resolving upstream.

## Outstanding follow-ups

1. **Attach body-internal comments** to the statement nodes introduced at L3.
2. **CRUD enrichment** — codeanalyzer-java issue 187.
3. Consider a more compact span encoding if payload size becomes a concern.

