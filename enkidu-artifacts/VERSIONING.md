# Enkidu Artifacts Versioning Policy

`enkidu-artifacts` defines Enkidu’s **public, versioned report contract**.
It is treated as a *stable API surface* for other Enkidu modules (core/export/cli/plugin)
and for downstream consumers.

This module has two kinds of versioning:

## 1) SemVer (module version)

The published artifact version follows **Semantic Versioning**:

- **MAJOR** — breaking change to the public contract (DTOs / enums / semantics)
- **MINOR** — backward-compatible additions (new fields with defaults, new failure types that don’t break parsing)
- **PATCH** — bug fixes / clarifications that do not change the contract

## 2) Schema namespace version (package version)

The report contract is namespaced by schema version:

- `io.enkidu.artifacts.v1` is **Schema v1**
- Any breaking change that would break existing readers requires a **new schema package** (e.g. `v2`),
  keeping `v1` intact.

### What counts as breaking (requires new schema package + MAJOR bump)

Examples:
- removing/renaming fields
- changing field types
- changing enum wire names
- changing meaning/semantics in a way that makes old reports ambiguous

### What is backward-compatible (MINOR bump, stays in the same schema package)

Examples:
- adding new optional fields with defaults
- adding new failure types **if readers can safely ignore unknown types**
- adding new non-breaking metadata fields

## CI enforcement

CI enforces a minimal policy:
- Schema packages must be under `io.enkidu.artifacts.v<NUMBER>`
- The primary schema for this repo is expected to be `v1` until a breaking contract upgrade is introduced.

This is a guardrail, not a substitute for review.
