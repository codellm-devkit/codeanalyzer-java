# Application identity namespaces (supersedes draft workspace-root design)

The unmerged `--workspace-root` proposal is withdrawn. File keys, module suffixes and artifact
paths remain relative to the selected `--input` directory. No path prefix rekeying or URI
translation is performed.

`application_name` is the supplied `--app-name`, otherwise the input basename. `identity_namespace`
is the supplied `--namespace`, otherwise the resolved application name. Both segments are always
present: `can://<namespace>/<application>`, including `can://inventory/inventory`.

Names retain case and Unicode spelling. UTF-8 bytes outside ASCII letters/digits/`-._~` are
percent-encoded with uppercase escapes. Empty or whitespace-only names, controls, unpaired
surrogates, slashes/backslashes and whole-name `.`/`..` are invalid. Explicit invalid flags never
fall back to defaults. Legacy schema v1 rejects `--namespace`.

The existing schema family remains `2.0.0`. The analysis envelope and graph application anchors
carry `identity_version: 2`; anchors retain raw `application_name` and `identity_namespace`.
Missing persisted metadata is unsupported, not defaulted. Regenerate from source and retire legacy
outputs explicitly. Cache identity includes the canonical namespace/application root and marker.

Java keeps its existing `/java/<file>/<type>/<signature>` suffixes and body ordinals. External
symbols and artifacts share the canonical application root; `pkg:` identities remain unchanged.
Canonical-parent builders forward their root unchanged. No mutable global namespace is used.

Bolt checks the selected existing application anchor before DDL or data writes. Incremental hashes,
module purges, orphan pruning and artifact reconciliation stay within that selected application.
Shared-artifact retention rules remain intact. Offline snapshots validate incoming metadata but
cannot inspect the target database; execution is an explicit regeneration operation.
