# `--workspace-root`: repo-relative file keys

**Status:** accepted · 2026-10-24 · tracks [#274](https://github.com/codellm-devkit/codeanalyzer-java/issues/274)

## Problem

Every v2 file key is relative to `--input`. When the analyzed project sits inside a larger
repository (`services/orders/` in a monorepo), keys read `src/main/java/.../App.java` rather than
the repository path `services/orders/src/main/java/.../App.java`. Tooling that speaks repository
paths — git, IDEs, PR diffs, scanners — cannot join on them without knowing and re-adding the
prefix.

## Contract-impact triage

| Question | Answer |
| --- | --- |
| Changes `analysis.json` v2 shape? | No. No node, edge or field is added, removed or renamed. |
| Changes v2 id/key meaning? | Yes, opt-in: with `--workspace-root` the `<file>` segment is relative to the workspace root instead of `--input`. Default output is byte-identical. |
| Changes CLI? | Yes — one new optional flag. Additive → **minor, 3.4.0**. |
| Repos touched | `codeanalyzer-java` (this spec's PR). |

## Decisions (user-approved 2026-10-24)

1. **New optional flag `--workspace-root <dir>`.** When set, every path-derived v2 key is computed
   relative to it rather than to `--input`:
   - `application.symbol_table` keys (`L1Extractor.fileKey`);
   - `module.id` and every durable id below it (`can://<app>/java/<file>/…`), since they are built
     from the file key;
   - artifact keys and ids (`can://<app>/artifact/<rel>`, `ArtifactDiscovery`).

   Unset, behavior is unchanged: the root is `--input`.
2. **The app segment does not move.** `can://<app>` stays `--app-name`, else the `--input`
   directory name. Only the `<file>` segment changes, so the L1 cache key, the Neo4j `can://<app>/`
   prefix wipe/prune and `@external` ids keep their current scoping.
3. **`--input` must be inside the workspace root.** Both are resolved to absolute, normalized
   paths; if `--input` is not the root or a descendant of it, exit non-zero at argument validation.
   This keeps the schema invariant intact: keys are relative, never absolute, never `..`.
4. **v2 only.** v1 keys are absolute paths, so there is nothing to relativize. `--workspace-root`
   with `--schema v1` is a hard argument error.
5. **No new schema field.** Consumers read source text from `module.source` and never resolve a
   key against the filesystem, so the output need not record which root keys are relative to.
6. **Language-neutral semantics.** Artifact and `@external` ids are deliberately shared across
   sibling analyzers over the same `<app>`. Sibling analyzers that adopt a workspace root are
   expected to use these same semantics (decisions 1–4), so a repository-relative artifact id is
   the same node whichever analyzer emitted it.

## Implementation notes

- Thread one `keyRoot` path (workspace root if set, else `--input`) to the two relativization
  sites: `L1Extractor.fileKey` (via `extractAll`) and `ArtifactDiscovery.discover`. Source
  discovery, builds and dependency download still run against `--input`.
- `CanId.moduleId` strips leading `[./]+` from the key. Decision 3 makes `..` keys unreachable;
  pin that with a test rather than relying on the strip.
- `L3WalaOverlays.deriveApplicationId` recovers the app id by stripping the file key off a module
  id; it is key-shape agnostic, but cover it with a nested-root fixture.
- Incremental cache: keys change when the flag is toggled, so a cache written without the flag
  simply misses for a run with it. No migration; document it.

## Verification

- Unit: nested fixture (`<tmp>/repo/services/app/…`) run with `--workspace-root <tmp>/repo` —
  every `symbol_table` key, module id and artifact id starts with `services/app/`; no key is
  absolute or contains `..`.
- Unit: without the flag, output is byte-identical to today's.
- CLI: `--input` outside the root → non-zero exit with a clear message; `--workspace-root` with
  `--schema v1` → non-zero exit.
- L2/L3 on the nested fixture: call-graph `src`/`dst` and L3 overlay ids carry the same
  repo-relative module ids.
- Neo4j: an incremental push with the flag wipes/prunes only that app's nodes.

## Release plan

Ships in **codeanalyzer-java 3.4.0** as an opt-in flag; default output is unchanged, so no
consumer needs to move in lockstep. README documents the flag, the repo-relative keys it produces,
and the cache miss on toggling it.

## Addendum: Bolt artifact reconciliation (user-approved 2026-10-28)

### Problem

The Bolt writer never deletes an `:Artifact` or `:ConfigKey`. They carry no `_module`, so they
are treated as shared nodes: MERGE-upserted, never purged. Toggling `--workspace-root` on an app
that already has a graph changes every artifact id (`can://<app>/artifact/pom.xml` becomes
`can://<app>/artifact/services/app/pom.xml`), so the old-keyed artifacts survive alongside the
new ones. Each still hangs off `:JApplication` via `HAS_ARTIFACT` and still declares its
`:Package`s. The same leak exists without the flag, for any deleted or renamed artifact, and
for the outgoing edges of an artifact whose content changed (a dependency removed from
`pom.xml` keeps its `DECLARES_DEPENDENCY` edge; a removed yaml key keeps its `:ConfigKey`).
The Cypher snapshot does not have this problem, because its `can://<app>/` prefix wipe
rebuilds artifacts from scratch.

### Triage

| Question | Answer |
| --- | --- |
| Changes `analysis.json` / `schema.neo4j.json` shape? | No. Write semantics only. |
| Changes cross-analyzer graph semantics? | Yes, narrowly: a Java Bolt full run now removes Java's own claim on artifacts and config keys it no longer emits, and deletes a node only when no other analyzer claims it. Sibling-owned nodes and edges are never touched. |
| Repos touched | `codeanalyzer-java` now. `codeanalyzer-python` (`neo4j/bolt.py`) and `codeanalyzer-typescript` (`build/neo4j/bolt.ts`) have the same gap; tracked, filed when picked up. |

### Ownership: what is Java's

The artifact layer's vocabulary is shared (`:Artifact`, `:ConfigKey`, `:Package`,
`HAS_ARTIFACT`, `DEFINES_CONFIG`, `DECLARES_DEPENDENCY`, `LOCKS` are unprefixed and identical in
all three analyzers), but the facts each analyzer writes under it are not: Java parses Maven and
Gradle manifests and its own set of config formats; Python writes `pkg:pypi` dependencies and
toml/ini/json config keys; TypeScript writes `pkg:npm`. Ownership is recoverable without a new
property:

| Fact | Java's when |
| --- | --- |
| an `:Artifact` / `:ConfigKey` node | it carries `:JCanNode` (every `can://` node Java writes gets it, `RowBuilder.node`) |
| `HAS_ARTIFACT` | its source is this app's `:JApplication` |
| `DECLARES_DEPENDENCY` / `LOCKS` | its target `:Package` id starts with `pkg:maven/` |
| `DEFINES_CONFIG` | shared fact; kept while any analyzer still claims the key |

A node claimed by another analyzer carries that analyzer's marker (`:PyCanNode`, `:TSCanNode`),
so "claimed by someone else" is `any(l IN labels(x) WHERE l ENDS WITH 'CanNode' AND
l <> 'JCanNode')`.

### Decisions

1. **Release, don't delete.** On a full Bolt run, for every `:Artifact` under
   `can://<app>/artifact/` that carries `:JCanNode` and is not among this run's artifact ids, and
   every `:ConfigKey` under that prefix that carries `:JCanNode` and is not among this run's
   config-key ids: remove `:JCanNode`; if no other `*CanNode` marker remains, `DETACH DELETE`
   it. A node another analyzer still claims survives with that analyzer's edges intact. An
   artifact with no marker at all was not written by Java and is left alone.
2. **Java-owned artifact edges are rebuilt on every full run.** `sha256` is one property every
   writer `SET`s, so a sibling's push can make a changed manifest look unchanged to Java; a sha
   diff is therefore unsound on shared nodes. Instead, each full run deletes this app's
   Java-owned artifact edges (`HAS_ARTIFACT` from its `:JApplication`;
   `DECLARES_DEPENDENCY`/`LOCKS` from its artifacts to `pkg:maven/` packages) and re-upserts
   them from the run. That also covers `LOCKS` going stale when a manifest changes and the lock
   file does not. Artifact edges number tens to hundreds, so the cost is negligible.
3. **Full runs only.** Releasing and rebuilding run only when `fullRun`. v2 is always a full run
   (`--target-files` is rejected), so this covers every v2 push.
4. **Ordering.** One write transaction runs release → delete → edge purge, then nodes and edges
   are upserted as today. The ids to keep come from the run's rows, not the DB.
5. **Non-goals.** `:Package` nodes left with no edges are not deleted (they sit outside the app
   prefix and are shared across apps). The Cypher snapshot keeps its broader wipe, which
   `DETACH DELETE`s every `:JCanNode` under the app, sibling-shared artifacts included. Aligning
   it with decision 1 is a recorded follow-up.

### Verification

- Bolt (Testcontainers), failing before the fix: push without `--workspace-root`, then push the
  same project with it; assert every `:Artifact`/`:ConfigKey` under `can://<app>/artifact/`
  carries the repo-relative id, no old-keyed artifact remains, and `:JApplication` has exactly
  as many `HAS_ARTIFACT` edges as the run emitted.
- Bolt: change a manifest (drop a dependency, drop a config key); re-push; assert the dropped
  `DECLARES_DEPENDENCY` edge and `:ConfigKey` are gone.
- Bolt, polyglot: seed a sibling-shaped artifact (`:Artifact:PyCanNode`, a `pkg:pypi`
  `DECLARES_DEPENDENCY`, a `:ConfigKey:PyCanNode`) on an id Java also emits and on one it does
  not; after Java's re-push both survive with their sibling edges, and the shared one keeps
  `:PyCanNode`.
- Bolt: a second application in the same database keeps all its artifacts.

### Docs

README §4.2: the Bolt writer reconciles this app's artifacts on a full run. It releases Java's
claim on artifacts and config keys it no longer emits, deletes them only when no other analyzer
claims them, and rebuilds Java-owned artifact edges. §4.1: state that the snapshot's wipe is
broader. Update the `BoltWriter` / `CypherWriter.DESCENDANTS` comments that say Bolt never
deletes artifacts. Releases with 3.4.0; no lockstep with siblings.
