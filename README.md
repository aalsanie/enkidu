# Enkidu (Linkage Doctor)

Enkidu Linkage Doctor checks whether the code you compiled will still work at runtime by reading your compiled bytecode and resolving every referenced class, method, and field against the exact jars on your runtime classpath. It then tells you what will fail, where it will fail (down to the call site), which jar/version caused it (or which jar is missing / being shadowed), and what change is most likely to fix it.

Given your compiled classes (or jar) and the runtime classpath you plan to ship, it simulates JVM linkage for those bytecode references and reports what will fail, where (call site), why (missing/mismatched symbols or types), which jar “wins” vs which is shadowed, and a concrete fix plan (upgrade/align versions, exclude conflicts, remove duplicates, fix shading/SPI).

---

## What it does

Enkidu simulates JVM linkage on your *real* runtime classpath and flags failures you typically only discover late; often after merge, after deploy, or only in prod-like environments.

---

## What it detects

**1) Missing symbols**
- `NoClassDefFoundError` / `ClassNotFoundException`: referenced class not present at runtime  
- `NoSuchMethodError`: referenced method not found on the resolved runtime type  
- `NoSuchFieldError`: referenced field missing  

**2) Incompatible type shape**
- `IncompatibleClassChangeError`: class↔interface mismatch, static↔instance mismatch  
- Method present but signature differs (descriptor mismatch / binary incompatibility)  

**3) Access + visibility**
- `IllegalAccessError` risks: visibility changes (package-private/protected), and JPMS/module access constraints when available  

**4) Classpath shadowing / duplicates**
- Same FQCN in multiple jars: reports the winner vs shadowed copies and whether the winner is ABI-incompatible with call sites  

**5) ServiceLoader / SPI landmines**
- Broken `META-INF/services/*` providers (missing classes)  
- Provider exists but doesn’t implement the service at runtime (type mismatch)  
- Providers overwritten/ignored due to packaging/merging choices  

**6) “Works in IDE, fails in prod” gap**
- Compare two classpaths (e.g., `testRuntimeClasspath` vs `prodRuntimeClasspath`)  
- Report regressions caused by slimming/relocation/proguard/shadowing  

---

## Why use it instead of “just running the app”?

Because these failures often **don’t show up in compilation** (your compile classpath might be different than your runtime classpath), are **not reliably caught by tests** (tests may run with different dependencies or never hit the failing code path), and are **expensive to debug** when they surface late (hard-to-read stacktraces, dependency/version pinball, and failures that only reproduce in prod-like environments).

---

## What it offers beyond “a report”

It offers actionability:

- A “doctor note” per failure: exact call chain, resolved jar, missing jar, conflicting jar.
- A grouped “fix plan”: smallest change set that resolves the most issues.
- Outputs suitable for CI gates (exit codes), and exports to share.

---

## Quick start
see [quick start](./QUICK_START.md)

---

## Changelog
[changelog](./CHANGELOG.md)

---

## License

[Apache-2.0](./LICENSE)
