<p align="left">
  <img src="./enkidu-intellij-plugin/src/main/resources/icons/logo.png" alt="Shamash Logo" width="180"/>
</p>

[![current release](https://img.shields.io/badge/release-0.2.0-green)](https://github.com/aalsanie/enkidu/releases) | [![install plugin](https://img.shields.io/badge/jetbrains-plugin-blue)](https://plugins.jetbrains.com/plugin/29920-enkidu-linkage-doctor) | [![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg)](https://www.apache.org/licenses/LICENSE-2.0.html)

# Enkidu

Enkidu Linkage Doctor catches **runtime linkage failures before runtime**.

It scans your compiled bytecode and resolves every referenced class/method/field against the **exact runtime classpath you plan to ship**. When something won’t link, Enkidu tells you:

- what will fail (missing class/method/field, ABI mismatch, access, shadowing, SPI landmine)
- where it will fail (call site)
- which jar “wins” vs which is shadowed (and why that matters)
- a concrete fix direction (align versions / exclude duplicates / add missing dep / merge SPI, etc.)

---

## What it detects

- **Missing symbols**: missing classes/methods/fields (`NoClassDefFoundError`, `NoSuchMethodError`, `NoSuchFieldError`)
- **Binary incompatibility / descriptor mismatch**: type shape doesn’t match (`IncompatibleClassChangeError`, signature mismatches)
- **Access/visibility issues**: runtime access problems (including module/visibility constraints when available)
- **Classpath shadowing / duplicates**: same FQCN in multiple jars → winner vs shadowed, and whether the winner breaks call sites
- **ServiceLoader / SPI problems**: missing providers, wrong provider types, overwritten providers

---

## Why this exists

Compilation doesn’t guarantee runtime linkage:
- compile vs runtime classpaths differ
- tests don’t hit every call path (and often run with different deps)
- failures surface late and waste time (dependency pinball + unreadable stacktraces)

Enkidu makes the classpath explicit and checks linkage deterministically.

---

## How to use it

- **CLI**: run Enkidu against a target (classes dir or jar) and a runtime classpath manifest.
- **IntelliJ plugin**: same idea, but faster feedback inside the IDE (run → inspect failures → navigate/copy evidence).

Quick start: see **[QUICK_START.md](./QUICK_START.md)**

---

## Changelog
See **[CHANGELOG.md](./CHANGELOG.md)**

---

## License
**[Apache-2.0](./LICENSE)**
