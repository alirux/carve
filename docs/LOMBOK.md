# Lombok, type resolution & project attribution

This document explains how the tool behaves on codebases that use **Lombok**,
why the naive behaviour loses information, and the two targeted fixes that were
implemented — together with their limits and the tradeoffs against the
alternative (delombok).

It is written for the multi-`--source` (multi-project) case, where each source
root is given a name and every type must be attributed to exactly one project.

---

## Table of contents

1. [TL;DR](#1-tldr)
2. [Why Lombok is invisible to the model](#2-why-lombok-is-invisible-to-the-model)
3. [How attribution works](#3-how-attribution-works)
4. [The two problems Lombok caused](#4-the-two-problems-lombok-caused)
5. [Fix 1 — type-level attribution](#5-fix-1--type-level-attribution)
6. [Fix 2 — receiver-type fallback](#6-fix-2--receiver-type-fallback)
7. [Known limit — builder chains](#7-known-limit--builder-chains)
8. [Why not delombok](#8-why-not-delombok)
9. [How the tool signals it](#9-how-the-tool-signals-it)
10. [Test map](#10-test-map)

---

## 1. TL;DR

- The tool parses source with a plain Spoon launcher in `noClasspath` mode and
  **does not run Lombok** ([`Carve.buildSpoonModel`](../src/main/java/com/codingful/carve/Carve.java)).
  Lombok-generated members (getters, setters, `@Builder`, `equals`/`hashCode`,
  constructors) therefore **do not exist** in the model.
- Consequences, and how they are handled today:

| Concern | Naive behaviour | After the fixes |
|---|---|---|
| A `@Data`/`@Value`/`record` DTO's **project** | absent or attributed via a call-site stub → `""` | attributed from its own source file (**Fix 1**) |
| A method-less DTO's **presence** in the graph | missing unless someone calls it | always a node (**Fix 1**) |
| **Coupling** `caller → DTO` via a Lombok getter | edge dropped onto an `unknown` node | edge kept, pointed at the DTO (**Fix 2**) |
| Coupling via a `@Builder` **chain** | lost | still lost (**known limit**, §7) |

No classpath, no Lombok jar, and no delombok are required.

---

## 2. Why Lombok is invisible to the model

The Spoon launcher is created plain and analysis-only:

- a plain `new Launcher()`,
- `setShouldCompile(false)` — no compilation,
- `setNoClasspath(...)` when no classpath is supplied — unresolved references are
  tolerated rather than fatal.

Spoon parses the *source text*. It sees the `@Data`/`@Value`/`@Builder`
annotations on a class, but it does **not** execute Lombok's annotation
processor, so the members Lombok would generate are never added to the AST.

Practical effect for a class like:

```java
@Data
public class Money { private java.math.BigDecimal amount; }
```

- The **type** `Money` exists, with a valid position and a real `.java` file.
- `Money.getAmount()`, `setAmount(...)`, `equals`, `hashCode`, the constructor —
  **none of them exist** as `CtMethod`s. `money.getMethods()` is empty.

This is the single fact everything else follows from.

---

## 3. How attribution works

Attribution happens at two levels.

**Per type (the source of truth).**
[`ProjectResolver.resolve`](../src/main/java/com/codingful/carve/extractor/ProjectResolver.java)
maps a type to a project by the **path of its source file**: it returns the
first `--source` root that is a prefix of the file path, roots sorted
longest-first so the most specific nested root wins. If the file matches no root,
or the type has no valid position (truly synthetic types), it returns `""`.

**Per package (aggregation).**
[`PackageGraphModel.collapse`](../src/main/java/com/codingful/carve/reporter/PackageGraphModel.java)
must pick one project per package. It uses a **majority vote**: the project with
the most classes in that package wins.

Neither level needs Lombok — as long as the *type* is attributed, both work.

---

## 4. The two problems Lombok caused

Consider a controller in project `api` that uses a `Money` DTO from project
`core`:

```java
// api/PriceController.java
public java.math.BigDecimal show(Money m) { return m.getAmount(); }
```

**Problem A — the DTO loses its project.**
A pure Lombok DTO has no declared methods, so it produces no method node.
Attribution used to be derived only from method nodes, so the type was either
absent from the class graph or represented only by a call-site *stub* — and stubs
never pass through `ProjectResolver`, so the DTO ended up attributed to `""`
instead of `core`.

**Problem B — the coupling is lost.**
The call `m.getAmount()` cannot be bound (the method is absent). In `noClasspath`
mode Spoon then leaves the invocation's declaring type **null**, so the call
target degraded to an `unknown`, non-application node. The edge
`PriceController → Money` was dropped: the coupling to the DTO disappeared, it
was not merely mislabelled.

---

## 5. Fix 1 — type-level attribution

**Idea.** Attribute every *type* directly from its own source file, independently
of whether it has any methods.

**Implementation.**
- [`CallGraph`](../src/main/java/com/codingful/carve/graph/CallGraph.java) holds a
  registry `fqn → TypeInfo(label, project)`.
- [`CallGraphExtractor.enterType`](../src/main/java/com/codingful/carve/extractor/CallGraphExtractor.java)
  registers each type via `ProjectResolver.resolve(type)`. Overrides for
  `visitCtEnum` / `visitCtRecord` / `visitCtAnnotationType` were added so **enums
  and records** are covered too — records are the modern DTO and were the biggest
  gap.
- [`ClassGraphModel.collapse`](../src/main/java/com/codingful/carve/reporter/ClassGraphModel.java)
  seeds one node per registered type first, then overlays method aggregates. A
  method-less DTO now appears as a node with the authoritative project.

**Tradeoff.** Every parsed type becomes a class-graph node, so method-less types
(DTOs, marker interfaces, records) show up as **isolated nodes** (degree 0). This
is inventory, not a hairball — isolated nodes add no edges. Measured on the
tool's own source: **+4 class nodes** (42 → 46), all 46 types attributed
authoritatively. On a DTO-heavy monolith the delta is proportional to the number
of value objects — which is exactly the population we want attributed.

**What it does not do.** It fixes *attribution* and *presence*, not *coupling*.
A used DTO would still appear isolated without Fix 2.

---

## 6. Fix 2 — receiver-type fallback

**Idea.** When a call cannot be bound, recover the owning type from the **static
type of the receiver** instead of dropping the edge.

**Implementation.**
[`CallGraphExtractor.resolveDeclaringType`](../src/main/java/com/codingful/carve/extractor/CallGraphExtractor.java):
if `exec.getDeclaringType()` is null, fall back to `invocation.getTarget().getType()`.
For `m.getAmount()` where `m` is a field, parameter or local of a declared type,
that resolves to `com.acme.core.Money`, so the target becomes application code and
the edge `PriceController → Money` is kept.

**Deliberately slim.** The fallback recovers the *type* only; it does **not**
attribute the project on the stub. Attribution is Fix 1's job (the type registry),
so the two responsibilities stay cleanly separated:

- **presence + attribution** → type registry (Fix 1),
- **coupling** → receiver fallback (Fix 2).

---

## 7. Known limit — builder chains

The receiver fallback needs the receiver's type to be known. A `@Builder` chain
breaks that:

```java
Money.builder().amount(x).build();
```

The intermediate receivers (`Money.builder()`, `.amount(x)`) return the generated
`MoneyBuilder`, which is itself absent from the model and therefore unresolved.
Measured: `builder`/`amount`/`build` all resolve to non-application `void`/
`unknown` targets, so the coupling `Factory → Money` **is lost**.

The DTO itself is still present and attributed to `core` (Fix 1 is independent of
call resolution) — only the *edge* is missing. This is the one scenario that
would justify delombok; it is pinned by a test so any change is deliberate.

---

## 8. Why not delombok

Running Lombok inside Spoon (delombok) is the only option that materialises the
generated members themselves, so it is the most *complete* for Lombok. It was not
chosen because it is not *clean* for this tool's purpose:

| | Type registry + receiver fallback | Delombok |
|---|---|---|
| DTO attributed to its project | ✅ | ⚠️ depends on generated-node positions carrying the file |
| DTO present even if never used | ✅ | ✅ |
| Coupling `caller → DTO` via getter | ✅ | ✅ |
| Coupling via `@Builder` chain | ❌ (§7) | ✅ |
| Runs without a classpath | ✅ | ❌ needs lombok.jar at the project's exact version |
| Free of version/compiler fragility | ✅ | ❌ tied to Lombok/JDT/JDK compatibility |
| Adds boilerplate coupling (generated `equals`/`hashCode` over fields) | ✅ none | ❌ yes |

The tool cares about **attribution** and **coupling**, not the accessor bodies —
which carry no architectural signal. The two targeted fixes cover both at near-zero
cost, so delombok remains reserved for the builder-chain gap, if it ever proves
material on a real project.

---

## 9. How the tool signals it

Because the caveats above are silent by nature, the tool surfaces them whenever a
`lombok.*` annotation is seen in the analysed source
([`CallGraphExtractor.enterType`](../src/main/java/com/codingful/carve/extractor/CallGraphExtractor.java)
→ `CallGraph.lombokDetected()`):

- **Console** — a `WARN` log during extraction, and a caveat line in the
  *Analysis complete* summary.
- **JSON** — a `summary.lombokAnnotatedTypes` count in `analysis.json`.
- **HTML** — a note in the class-graph and package-graph panels when the count is
  non-zero.

The signal is a heads-up on data completeness, not an error: attribution stays
correct, and getter coupling is recovered (§6); only `@Builder` chains (§7) are
genuinely missing.

---

## 10. Test map

| Behaviour | Test |
|---|---|
| Type attributed; generated members absent from model | `ProjectResolverTest.GIVEN_a_lombok_annotated_type_...` |
| Getter coupling recovered from receiver type | `CallGraphExtractorTest.GIVEN_calls_to_a_lombok_getter_and_a_hand_written_method_...` |
| Method-less DTO appears attributed | `ClassGraphModelTest.GIVEN_a_method_less_lombok_dto_that_nobody_calls_...` |
| Builder-chain coupling lost, DTO still attributed | `CallGraphExtractorTest.GIVEN_a_lombok_builder_chain_...` |
| Lombok detected / not detected | `CallGraphExtractorTest.GIVEN_a_lombok_annotated_type_WHEN_extracting_THEN_lombok_is_detected` |
