# CLI reference (Enkidu Linkage Doctor)

Command summary:

- `enkidu doctor` — scan targets against a runtime classpath and report linkage failures
- `enkidu compare` — scan the same targets against **two** runtime classpaths and report regressions/deltas

> Tip: all examples below work with `./gradlew :enkidu-cli:run --args="..."` as shown, or with the installed distribution from `:enkidu-cli:installDist`.

---

## `enkidu doctor`

### Exit codes

These are hard-coded in `DoctorCommand`:

- **0** — no failures under the selected policy
- **2** — failures found under the selected policy
- **3** — invalid input / unexpected error (failed fast)

### Required inputs

You must provide:

- targets (`--targets` and/or `--targets-file`)
- runtime classpath (`--classpath` and/or `--classpath-file`)

### Flags

#### Targets

- `--targets <path...>`  
  One or more targets to scan: a **classes directory** or a **jar**. You can pass multiple paths.

- `--targets-file <file>`  
  A text file containing target paths (one per line).  
  Blank lines are ignored. Lines starting with `#` are comments.

Targets from `--targets-file` are loaded first, then `--targets` are appended.

#### Runtime classpath

- `--classpath <path...>`  
  One or more runtime classpath entries (dirs/jars) **in resolution order**.

- `--classpath-file <file>`  
  A text file containing runtime classpath entries (one per line).  
  Blank lines are ignored. Lines starting with `#` are comments.

Classpath entries from `--classpath-file` are loaded first, then `--classpath` are appended.

#### Output

- `--format <json|sarif|html>`  
  Output format. Default is `json`.

- `--output <file>`  
  Write output to a file instead of stdout.

#### Failure policy (CI gating)

- `--fail-on <any|error-only|none>`  
  Controls the doctor command exit code:
  - `any` → exit 2 if there is any failure
  - `error-only` → exit 2 only if any failure has severity `ERROR`
  - `none` → always exit 0 (useful for local exploration)

#### Hardening / MRJAR / best-effort mode

- `--runtime-java-feature <int>`  
  Overrides the Java feature version used for Multi-Release JAR selection.  
  `0` means “use the current JVM feature version”.

- `--continue-on-error`  
  Best-effort scan: continue on invalid bytecode/unreadable jars and record warnings in the report.  
  Default is fail-fast.

#### Performance and caching

- `--jar-scan-cache-dir <dir>`  
  Enables the jar scanning cache (keyed by jar SHA-256). Stores jar entry scans (classes + `META-INF/services`).

- `--jar-scan-parallelism <int>`  
  Parallelism for runtime classpath jar scanning (>= 1). Default is `1`.

- `--target-scan-parallelism <int>`  
  Parallelism for scanning target classfiles (>= 1). Default is `1`.

- `--max-in-flight-target-classes <int>`  
  Bounds queued class scan work-items to limit memory.  
  `0` means use `2×target-scan-parallelism`. Default is `0`.

#### Support bundle (repro)

- `--bundle <path>`  
  Optional repro/support bundle output path.  
  If it ends with `.zip`, a zip bundle is created; otherwise a directory bundle is created.

---

## `enkidu doctor` examples

### Minimal (targets dir + classpath file)

```bash
./gradlew :enkidu-cli:run --args="doctor \
  --targets build/classes/java/main \
  --classpath-file classpath.txt"
```

### Multiple targets (jar + classes)

```bash
./gradlew :enkidu-cli:run --args="doctor \
  --targets build/libs/app.jar build/classes/kotlin/main \
  --classpath-file classpath.txt"
```

### Write SARIF for CI

```bash
./gradlew :enkidu-cli:run --args="doctor \
  --targets build/libs/app.jar \
  --classpath-file classpath.txt \
  --format sarif \
  --output build/reports/enkidu.sarif.json"
```

### Enable jar-scan caching + bounded parallelism

```bash
./gradlew :enkidu-cli:run --args="doctor \
  --targets build/libs/app.jar \
  --classpath-file classpath.txt \
  --jar-scan-cache-dir .enkidu-cache \
  --jar-scan-parallelism 4 \
  --target-scan-parallelism 4 \
  --max-in-flight-target-classes 16"
```

### Best-effort mode (don’t fail fast on unreadable jars)

```bash
./gradlew :enkidu-cli:run --args="doctor \
  --targets build/libs/app.jar \
  --classpath-file classpath.txt \
  --continue-on-error"
```

### Create a repro bundle

```bash
./gradlew :enkidu-cli:run --args="doctor \
  --targets build/libs/app.jar \
  --classpath-file classpath.txt \
  --bundle build/enkidu-bundle.zip"
```

---

## `enkidu compare`

Compares linkage results for the same targets against two runtime classpaths.

Typical use cases (as implemented by `CompareCommand`):

- `testRuntimeClasspath` vs `runtimeClasspath` regressions
- slimmed/shaded production classpath regressions

### Flags

- `--targets <file>` (**required**)  
  File containing one compiled output path per line (targets manifest).

- `--classpath-a <file>` (**required**)  
  Classpath manifest A (one entry per line).

- `--classpath-b <file>` (**required**)  
  Classpath manifest B (one entry per line).

- `--label-a <text>`  
  Label for classpath A. Default is `A`.

- `--label-b <text>`  
  Label for classpath B. Default is `B`.

- `--out <file>`  
  Output file path (JSON). If omitted, prints to stdout.

- `--runtime-java-feature <int>`  
  Same meaning as in `doctor` (Multi-Release JAR selection).

- `--continue-on-error`  
  Same meaning as in `doctor` (best-effort).

- `--jar-scan-cache-dir <dir>`  
  Same meaning as in `doctor`.

- `--jar-scan-parallelism <int>`  
  Same meaning as in `doctor`.

- `--target-scan-parallelism <int>`  
  Same meaning as in `doctor`.

- `--max-in-flight-target-classes <int>`  
  Same meaning as in `doctor`.

### Example

```bash
# targets.txt:
# /path/to/build/classes/java/main
# /path/to/build/libs/app.jar

./gradlew :enkidu-cli:run --args="compare \
  --targets targets.txt \
  --classpath-a classpath-test.txt \
  --classpath-b classpath-prod.txt \
  --label-a testRuntimeClasspath \
  --label-b runtimeClasspath \
  --out build/reports/enkidu-compare.json"
```

---

## Next: understanding failures

Once you have output, use: **[docs/understanding-failures.md](./understanding-failures.md)**
