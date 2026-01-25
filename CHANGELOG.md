# Changelog


## [unreleased]

### Added
- ASM dependency to enkidu-core to support bytecode reference extraction
- JVM resolution engine that loads classes from the runtime classpath
- JVM resolution engine resolves referenced classes / methods / fields via hierarchy + interface walks
- Engine producing outcomes for missing symbols, class<>interface mismatches (ICCE) descriptor mismatches
- Engine now detects static-vs-instance field access mismatches

Added fixture-based resolver tests covering: removed method, interface→class mismatch (invokeinterface ICCE), descriptor change, and field access ICCE.

### Fixed
- Bytecode type-reference recording to use consistent internal names and correct parameter