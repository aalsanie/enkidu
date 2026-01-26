# Changelog


## [unreleased]

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

### Fixed
- Bytecode type-reference recording to use consistent internal names