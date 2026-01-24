# Enkido Linkage Doctor — Quick Start

---

## 1) Prepare a runtime classpath manifest (`classpath.txt`)

Create a text file containing the **runtime classpath in order**, one entry per line.  
This should match the exact jars/directories you intend to run/ship with.

**Example: `classpath.txt`**
```txt
/home/username/.m2/repository/com/google/guava/guava/33.1.0-jre/guava-33.1.0-jre.jar
/home/username/.m2/repository/org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar
/home/username/projects/my-app/build/resources/main
/home/username/projects/my-app/build/classes/kotlin/main
```

Notes:
- Order matters (it affects which jar “wins” when duplicates exist).
- Paths can be absolute or relative to where you run the command.
- Include any runtime directories your app relies on (e.g., `build/resources/main`).

---

## 2) Choose your targets

Targets are what you want to validate:

- a **compiled classes directory** (e.g., `build/classes/...`)
- or a **jar** you plan to run/ship

Examples:
- Kotlin/JVM: `build/classes/kotlin/main`
- Java: `build/classes/java/main`
- Jar: `app/build/libs/app.jar`

---

## 3) Run the CLI

Validate a compiled classes directory:

```bash
enkido doctor   --targets build/classes/kotlin/main   --classpath classpath.txt   --out build/enkido   --format json   --fail-on error
```

Validate a jar:

```bash
enkido doctor   --targets app/build/libs/app.jar   --classpath classpath.txt   --out build/enkido   --format json,sarif,html   --fail-on error
```

---

## Exit codes (CI-friendly)

- `0` no failures under the selected policy  
- `2` failures found (fail the build)  
- `3` invalid inputs / configuration / unreadable classpath  

---

## Outputs

Enkido can produce exports suitable for humans and CI systems:

- **JSON**: canonical, deterministic machine-readable output
- **SARIF**: CI annotations and PR checks
- **HTML**: shareable report for investigation and collaboration

Even if you only care about “CI fail” or the IntelliJ UI, Enkido benefits from a stable report model so results are reproducible and diffable.

---

## IntelliJ plugin

The IntelliJ plugin is the fast feedback loop:

- Run Enkidu Linkage Doctor against the current project output and a chosen runtime classpath profile
- Group failures by root cause (missing symbol, duplicate/shadowing, descriptor mismatch, SPI/provider issues)
- Navigate to call sites where line info is available
- Copy a “doctor note” (evidence + jar winners/losers) into issues/PRs
- Generate a previewable fix snippet / fix plan for (version alignment, excludes, missing deps, shading/SPI merges)

---

## Gradle usage (verification gate, CLI-driven)

The most valuable workflow is failing CI *before* a runtime crash. Wire Enkido as a verification step by generating `classpath.txt` from your build tooling and passing it to the CLI.

A minimal wiring pattern (invoking the CLI) looks like this:

```kotlin
// build.gradle.kts (wiring pattern)
// The exact way you invoke `enkido doctor` depends on how you consume the CLI artifact in your build.

tasks.register("enkidoDoctor") {
  group = "verification"
  description = "Fails if runtime linkage will break on the shipped classpath."
  dependsOn("classes")

  doLast {
    // 1) Write the runtime classpath (ordered) to classpath.txt.
    // 2) Invoke Enkido with:
    //    enkido doctor --targets ... --classpath classpath.txt --out ... --format ... --fail-on error
  }
}

tasks.named("check") {
  dependsOn("enkidoDoctor")
}
```
