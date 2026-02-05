# Understanding failures (what they mean + typical fixes)

This guide explains the failures Enkidu reports and how to interpret them.

Enkidu’s report failure types (artifacts v1) are:

- `MISSING_CLASS`
- `MISSING_METHOD`
- `MISSING_FIELD`
- `INCOMPATIBLE_CLASS_CHANGE`
- `DESCRIPTOR_MISMATCH`
- `ILLEGAL_ACCESS_RISK`
- `DUPLICATE_CLASS_SHADOWING`
- `SPI_PROVIDER_BROKEN`
- `SPI_PROVIDER_TYPE_MISMATCH`
- `CLASSPATH_COMPARE_REGRESSION`

The *surface symptom* at runtime is usually one of:

- `NoClassDefFoundError` / `ClassNotFoundException`
- `NoSuchMethodError`
- `NoSuchFieldError`
- `IncompatibleClassChangeError`
- `IllegalAccessError`

Enkidu reports them *ahead of time*, with evidence (call site, resolved jar/winner, shadowed jars, etc).

---

## `MISSING_CLASS`

**Runtime symptom**
- `NoClassDefFoundError` or `ClassNotFoundException`

**Meaning**
A class referenced by your bytecode is not present on the provided runtime classpath.

**Common causes**
- dependency missing from runtime but present in compile/test
- runtime classpath slimming (exclude/relocation/shadow) removed it
- wrong classpath order (the entry that contained it is no longer included)

**Typical fixes**
- add the missing dependency to the runtime classpath
- ensure the dependency is in the shipped artifact
- if it “exists” in another jar, confirm the winner isn’t shadowing it away

---

## `MISSING_METHOD`

**Runtime symptom**
- `NoSuchMethodError`

**Meaning**
Your bytecode references a method signature that is not found on the resolved runtime type.

**Common causes**
- version skew: compiled against newer API, shipped older jar
- transitive dependency changed due to BOM/platform alignment
- shading/relocation replaced the class with a different ABI shape

**Typical fixes**
- align versions (upgrade/downgrade the runtime jar so the method exists)
- enforce dependency constraints / BOM alignment
- if duplicates exist, eliminate the wrong winner (exclude, reorder, remove duplicate jar)

---

## `MISSING_FIELD`

**Runtime symptom**
- `NoSuchFieldError`

**Meaning**
Your bytecode references a field that does not exist on the resolved runtime type.

**Common causes**
- same as `MISSING_METHOD`: version skew, wrong jar winner, shading.

**Typical fixes**
- align versions
- remove duplicates / ensure correct class wins
- avoid mixing incompatible major versions

---

## `INCOMPATIBLE_CLASS_CHANGE`

**Runtime symptom**
- `IncompatibleClassChangeError`

**Meaning**
The runtime type shape is incompatible with what the call site expects.

This covers cases like:
- class vs interface mismatch
- static vs instance mismatch
- other binary-incompatible shape changes that aren’t just “missing method”

**Typical fixes**
- align versions (compile and runtime must agree on whether the owner is a class/interface)
- remove shadowing duplicates that change the owner type
- avoid mixing major versions across modules

---

## `DESCRIPTOR_MISMATCH`

**Runtime symptom**
- often manifests as `NoSuchMethodError` (because a different signature is effectively “missing”)
- may also show up as linkage failure when method/field exists but with different descriptor

**Meaning**
The member exists, but the **binary descriptor** differs from what the call site expects
(e.g., different parameter types, return type, or field type).

**Typical fixes**
- align versions (this is a classic “compiled against X, shipped Y” problem)
- if you control the API: avoid binary-incompatible changes across modules without bumping
- if shading: verify relocated classes are not mixing old/new signatures

---

## `ILLEGAL_ACCESS_RISK`

**Runtime symptom**
- `IllegalAccessError` (or module access errors when JPMS is involved)

**Meaning**
Enkidu detected that a referenced member/type may not be accessible at runtime (visibility and module constraints when available).

**Typical fixes**
- ensure the member is public/protected as needed
- avoid split-packages / illegal reflective access patterns
- if JPMS modules are present: open/exports correctly (or adjust runtime/module boundaries)

---

## `DUPLICATE_CLASS_SHADOWING`

**Runtime symptom**
- can manifest as any of the above, depending on which copy wins:
  - `NoSuchMethodError`, `NoSuchFieldError`, ICCE, etc.

**Meaning**
The same fully-qualified class name (FQCN) exists in more than one jar on the runtime classpath.
Only **one** will be used (classpath winner), and others are ignored (shadowed).

Enkidu reports:
- who wins
- who is shadowed
- (when available) whether the duplicates are identical byte-for-byte or ABI-shape differs

**Typical fixes**
- remove duplicates (exclude the unwanted jar)
- align dependency versions so only one copy is present
- if shading created duplicates: fix relocation rules so packages don’t collide

---

## `SPI_PROVIDER_BROKEN`

**Runtime symptom**
- `ServiceConfigurationError` at runtime (ServiceLoader failures)
- or “feature silently missing” if the provider isn’t discovered

**Meaning**
A `META-INF/services/<Service>` file references providers that cannot be loaded.

Examples:
- provider class missing
- service type missing
- provider not instantiable

**Typical fixes**
- ensure provider and service types are shipped on the runtime classpath
- if shading/merging: ensure service files are merged correctly
- verify the provider’s jar is not shadowed/removed

---

## `SPI_PROVIDER_TYPE_MISMATCH`

**Runtime symptom**
- `ServiceConfigurationError: <provider> not a subtype`
- or runtime `ClassCastException` patterns around service lookup

**Meaning**
The provider class exists, but at runtime it does not implement/extend the service type.

Common in classpath-split situations where:
- service type is loaded from jar A
- provider was compiled against a different copy of the service from jar B

**Typical fixes**
- eliminate duplicate copies of the service type (one must win)
- align versions so provider and service agree
- avoid shading that duplicates API packages

---

## `CLASSPATH_COMPARE_REGRESSION`

**Meaning**
Only produced by `enkidu compare`. Indicates a symbol that links under classpath A but fails under classpath B (a regression),
or a jar winner change that caused linkage behavior to change.

**Typical fixes**
- identify what changed in classpath B (removed dependency, different version, different ordering)
- align versions / restore missing jars
- avoid slimming/shading that removes required runtime symbols

---

## Reading Enkidu evidence (what to look at)

When you see a failure, focus on:

1) **Call site** — what class/method referenced the symbol  
2) **Resolved runtime owner** — which class/jar Enkidu resolved as the winner  
3) **Shadowed duplicates** — duplicates are the #1 cause of “works in IDE, fails in prod” surprises  
4) **Fix suggestions** — Enkidu’s fix planner provides deterministic, path-first suggestions

If you want the authoritative list of failure types, see:
`enkidu-artifacts/src/main/kotlin/io/enkidu/artifacts/v1/ReportV1.kt`
