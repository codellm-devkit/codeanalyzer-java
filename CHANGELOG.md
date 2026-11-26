# Changelog

## [3.4.1] - 2026-11-26

- Updated the `neo4j-java-driver` to the latest `4.x` version.

## [3.4.0] - 2026-10-31

- Add `--namespace` and namespace-qualified application IDs.
- Scope embedded references, graph reconciliation and caches to the selected application.
- Validate stored identity metadata before connected writes.

Regenerate existing analyses and graphs from source for the new ID roots.
