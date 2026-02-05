# Enkidu Linkage Doctor — Quick Start

Before starting, you need two things:

- **Targets**: what you want to validate (compiled classes directory or a jar)
- **Runtime classpath manifest**: the exact runtime classpath (ordered) you intend to run/ship with

If you want the full CLI reference (all flags + exit codes + examples), see: **[docs/cli.md](./docs/cli.md)**

---

## 1) Prepare a runtime classpath manifest (`classpath.txt`)

Create a text file containing the **runtime classpath in order**, one entry per line.

- Blank lines are ignored
- Lines starting with `#` are comments

Example:

```txt
# runtime classpath entries, in resolution order
/path/to/libs/slf4j-api-2.0.13.jar
/path/to/libs/logback-classic-1.5.6.jar
/path/to/libs/logback-core-1.5.6.jar
/path/to/app/runtime/classes-dir
```

> **Important:** order matters. Enkidu uses this order to determine classpath winners vs shadowed entries.

---

## 2) Run the CLI (doctor)

You can run via Gradle:

```bash
./gradlew :enkidu-cli:run --args="doctor \
  --targets /path/to/compiled/classes \
  --classpath-file classpath.txt \
  --format json"
```

Or build a runnable distribution script:

```bash
./gradlew :enkidu-cli:installDist
# then run the generated script:
./enkidu-cli/build/install/enkidu-cli/bin/enkidu-cli doctor \
  --targets /path/to/compiled/classes \
  --classpath-file classpath.txt \
  --format json
```

---

## 3) Export formats

- `--format json` (default): deterministic JSON report (stable ordering)
- `--format sarif`: for CI/GitHub code scanning integration
- `--format html`: single-file shareable report

To write to a file instead of stdout:

```bash
./gradlew :enkidu-cli:run --args="doctor \
  --targets build/classes/kotlin/main \
  --classpath-file classpath.txt \
  --format html \
  --output build/reports/enkidu.html"
```

---

## 4) Understand and fix failures

Use: **[docs/understanding-failures.md](./docs/understanding-failures.md)**

It explains what each failure means (e.g., `MISSING_METHOD`, `DESCRIPTOR_MISMATCH`, `DUPLICATE_CLASS_SHADOWING`, SPI issues) and the typical fix directions.
