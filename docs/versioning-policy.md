# Versioning policy

This repo publishes and consumes **Enkidu artifacts** (the JSON contract and Kotlin model types under `io.enkidu.artifacts.v1`).

## What “artifacts” are

Artifacts are the **contract** between:

- the core engine (`enkidu-core`) that produces reports
- exporters (`enkidu-export`) that serialize reports to JSON/HTML/SARIF/etc.
- the CLI (`enkidu-cli`) and the IntelliJ plugin (`enkidu-intellij-plugin`) that display and ship the results

Because multiple components depend on the same contract, **breaking changes must be deliberate and versioned**.

## Policy

### 1) SemVer for artifacts, schema-versioned packages

- `enkidu-artifacts` follows Semantic Versioning.
- Contract types live under a schema package, e.g. `io.enkidu.artifacts.v1`.
- **Breaking changes** do not happen in-place: they require a new schema package (`v2`) and a SemVer **major** bump.

### 2) What counts as breaking

Breaking changes include (non-exhaustive):

- removing/renaming a field in a report model
- changing a field’s meaning or type
- changing default values such that previously-valid reports become invalid
- changing JSON representation in a way that causes consumers to fail

### 3) What counts as non-breaking

Non-breaking changes include:

- adding optional fields with safe defaults
- adding new enums in a way consumers can ignore (and JSON writers remain forward-compatible)
- adding new report sections under new top-level fields

### 4) Determinism is part of the contract

Artifacts must remain stable across platforms and runs:

- JSON output must be deterministic
- snapshot/golden tests must remain green

CI enforces this via:
- golden/snapshot tests in `enkidu-artifacts` + `enkidu-export`
- reproducible Gradle archives
- a working-tree dirtiness guard after the build

### 5) Release discipline

Releases are tag-driven:

- A release tag **must** match `project.version` (`v<version>`)
- Tagged builds must not produce `-SNAPSHOT` versions
- The GitHub Actions release workflow publishes build artifacts consistently
