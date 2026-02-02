# Changelog


## [unreleased]

### Added
- ServiceLoader/SPI validation: detects broken `META-INF/services/*` entries (missing provider/service, type mismatch, non-instantiable providers) and flags packaging overwrite/merge risks.
- Duplicate-class impact analysis: compares winner vs shadowed copies via bytecode hash + public ABI shape, scores shadowing risk, and highlights dangerous duplicates vs benign identical copies.
- Report evidence extended to include SPI and duplicate-risk details across JSON, SARIF, HTML, and IntelliJ UI.
- New fixtures and tests reproducing common SPI failures and duplicate-risk cases.
- jar-scan cache keyed by jar SHA-256 for fast repeat scans on large classpaths
- Parallel classpath jar indexing with bounded concurrency
- Streaming target analysis with bounded in-flight work to avoid holding large byte arrays/lists in memory
- CLI knobs for performance tuning: jar scan parallelism, target scan parallelism, in-flight limits, and optional cache directory
- Repro bundle output for `enkidu doctor` via `--bundle` (zip or directory). The bundle contains ordered `classpath.txt`, `targets.txt`, `bundle.json`.

### Fixed
- JarIndex winner selection now strictly follows classpath order across directories and jars

## [0.2.0]
### Added
- Added access checker
- Added IntelliJ plugin UI wiring test

### FixedK
- Detect (static, instance) mismatches for both methods and fields

### Removed
- Removed severity and message from the key and included symbol kind


## [0.1.1]
### Added
- ASM dependency to enkidu-core to support bytecode reference extraction
- JVM resolution engine that loads classes from the runtime classpath
- JVM resolution engine resolves referenced classes / methods / fields via hierarchy + interface walks
- Engine producing outcomes for missing symbols, class<>interface mismatches (ICCE) descriptor mismatches
- Engine now detects static-vs-instance field access mismatches
- Reports are readable and actionable
- Added fixture-based resolver tests
- For a set of fixtures, suggested fix resolves the issue
- CLI integration tests run against fixtures
- Added exporters
- intellij plugin module
- Users can reproduce scans in CLI by copying the classpath manifest
- Engine mode that runs resolution under two classpaths and reports

### Fixed
- Bytecode type-reference recording to use consistent internal names
- Fixed UI options overlapped by window