# Enkidu pit-project (testbed)

This repo is a **small, intentionally broken Java project** designed to showcase what **Enkidu Linkage Doctor** detects **before runtime**.

It produces:
- **targets**: a tiny `app` jar containing bytecode references
- **runtime classpath manifests**: ordered classpaths that simulate common “works in IDE, fails in prod” scenarios

The whole point is that the **same app bytecode** is analyzed against different **runtime classpath orders/variants**.

---

## What this testbed demonstrates

### 1) Missing / mismatched method (NoSuchMethodError-style)
The app is compiled against `lib-api-v1` where `demo.lib.Lib#foo()` exists.

At “runtime” we use `lib-runtime-v2` where `foo()` is gone and only `foo(int)` exists.

Expected Enkidu signal:
- `DESCRIPTOR_MISMATCH` (or `MISSING_METHOD` depending on reporting policy)
- evidence pointing to the winning jar on the runtime classpath

### 2) Illegal access risk (IllegalAccessError-style)
The app calls `demo.lib.Lib#foo()` from a different package.

At “runtime” we use `lib-runtime-restrict` where `foo()` becomes **package-private**.

Expected Enkidu signal:
- `ILLEGAL_ACCESS_RISK`

### 3) Classpath shadowing / duplicates
The runtime classpath contains **two copies** of `demo.lib.Lib`:

1. `lib-runtime-v2` (first, wins) – broken for the app
2. `lib-shadow-good` (second, shadowed) – would have worked, but is ignored at runtime

Expected Enkidu signal:
- winner vs shadowed jars recorded in evidence

### 4) SPI / ServiceLoader broken provider
The runtime classpath includes `spi-provider-broken` which ships:

`META-INF/services/demo.spi.Greeter` → `demo.spi.impl.MissingGreeter`

…but that class does not exist.

Expected Enkidu signal:
- SPI provider failure classification (once Milestone M is enabled)

---

## Repo structure

- `app/` – the bytecode target (calls `demo.lib.Lib#foo()` and uses `ServiceLoader`)
- `lib-api-v1/` – compile-time API
- `lib-runtime-v2/` – runtime variant that breaks method resolution
- `lib-runtime-restrict/` – runtime variant that breaks access rules
- `lib-shadow-good/` – shadowed “correct” copy to showcase classpath ordering
- `spi-api/` – service interface
- `spi-provider-good/` – correct provider
- `spi-provider-broken/` – broken provider (services file references a missing class)

---

## Build + generate Enkidu manifests

```bash
./gradlew clean enkiduPit
```

This writes classpath manifests under:

```
build/enkidu/
  missing-method.classpath.txt
  illegal-access.classpath.txt
  shadowing.classpath.txt
  spi-broken.classpath.txt
  compareA.classpath.txt
  compareB.classpath.txt
```

Each file is **one path per line**, in **runtime classpath order**.

---

## Run Enkidu on each scenario

### Targets
Use the app jar as the target:

```
app/build/libs/app.jar
```

### 1) Missing method / descriptor mismatch
```bash
enkidu doctor \
  --targets app/build/libs/app.jar \
  --classpath build/enkidu/missing-method.classpath.txt \
  --out build/enkidu/out/missing-method \
  --format json,html \
  --fail-on error
```

### 2) Illegal access
```bash
enkidu doctor \
  --targets app/build/libs/app.jar \
  --classpath build/enkidu/illegal-access.classpath.txt \
  --out build/enkidu/out/illegal-access \
  --format json,html \
  --fail-on error
```

### 3) Shadowing / duplicates
```bash
enkidu doctor \
  --targets app/build/libs/app.jar \
  --classpath build/enkidu/shadowing.classpath.txt \
  --out build/enkidu/out/shadowing \
  --format json,html \
  --fail-on error
```

### 4) Broken SPI provider (Milestone M)
```bash
enkidu doctor \
  --targets app/build/libs/app.jar \
  --classpath build/enkidu/spi-broken.classpath.txt \
  --out build/enkidu/out/spi-broken \
  --format json,html \
  --fail-on error
```

### Compare mode (A vs B)
```bash
enkidu compare \
  --targets app/build/libs/app.jar \
  --classpath-a-manifest build/enkidu/compareA.classpath.txt \
  --classpath-b-manifest build/enkidu/compareB.classpath.txt \
  --out build/enkidu/out/compare
```

---

## Why this repo exists

This is not a “real” application.

It is a **controlled failure lab** so you can:
- demo Enkidu’s output determinism
- demonstrate jar winner vs shadowing behavior
- show signature mismatch vs access failures
- exercise compare mode (tests vs prod-like runtime)
- later, exercise SPI checks and duplicate impact scoring
