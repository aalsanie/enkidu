# Enkidu Linkage Doctor — Quick Start

Before starting, you need two things:

- **Targets**: what you want to validate (compiled classes directory or a jar)
- **Runtime classpath manifest**: the exact runtime classpath (ordered) you intend to run/ship with

---

## 1) Prepare a runtime classpath manifest (`classpath.txt`)

Create a text file containing the **runtime classpath in order**, one entry per line.

Example: `classpath.txt`

```txt
/home/username/.m2/repository/com/google/guava/guava/33.1.0-jre/guava-33.1.0-jre.jar
/home/username/.m2/repository/org/slf4j/slf4j-api/2.0.13/slf4j-api-2.0.13.jar
/home/username/projects/my-app/build/resources/main
/home/username/projects/my-app/build/classes/kotlin/main
```

Notes:
- **Order matters** (it decides which jar “wins” if duplicates exist).
- Paths can be absolute or relative to where you run the command.
- Include runtime directories too (resources/classes), not just jars.

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
enkidu doctor   --targets build/classes/kotlin/main   --classpath classpath.txt   --out build/enkidu   --format json   --fail-on error
```

Validate a jar:

```bash
enkidu doctor   --targets app/build/libs/app.jar   --classpath classpath.txt   --out build/enkidu   --format json,sarif,html   --fail-on error
```

Compare two classpaths (only if your build supports the `compare` command):

```bash
enkidu compare   --targets build/classes/java/main   --classpath-a-manifest /path/to/classpathA.txt   --classpath-b-manifest /path/to/classpathB.txt   --out build/enkidu/compare.json
```

---

## Exit codes

- `0` no failures under the selected policy
- `2` failures found (fail the build)
- `3` invalid inputs / configuration / unreadable classpath

---

## Outputs

- **JSON**: canonical, deterministic machine output (diffable)
- **SARIF**: CI annotations / PR checks
- **HTML**: shareable report for humans

---

## IntelliJ plugin (fast feedback)

Use the Linkage Doctor tool window:

1) Select a module
2) Choose a classpath provider (IDE runtime or manifest file)
3) Run
4) Inspect failures (grouped), navigate to call sites when available, copy evidence/classpath for reproduction

---

## Gradle usage (verification gate, CLI-driven)

The most valuable workflow is failing CI *before* a runtime crash.

Minimal wiring pattern:
- generate `classpath.txt` from your build tooling (ordered runtime classpath)
- run `enkidu doctor` from `check`

```kotlin
// build.gradle.kts (wiring pattern)
// The exact way you invoke `enkidu doctor` depends on how you consume the CLI artifact in your build.

tasks.register("enkiduDoctor") {
    group = "verification"
    description = "Fails if runtime linkage will break on the shipped classpath."
    dependsOn("classes")

    doLast {
        // 1) Write the runtime classpath (ordered) to classpath.txt.
        // 2) Invoke:
        //    enkidu doctor --targets ... --classpath classpath.txt --out ... --format ... --fail-on error
    }
}

tasks.named("check") {
    dependsOn("enkiduDoctor")
}
```
