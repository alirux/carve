# Class hierarchy analysis: inferred edges & phantom couplings

This document explains what **class hierarchy analysis (CHA)** does to the call
graph, why it deliberately over-approximates, the phantom couplings it produces
on multi-project runs, and how inferred edges are marked so they can be told
apart from real ones.

It matters most for the multi-`--source` case, where several separately-built
projects are analysed in one model.

---

## Table of contents

1. [TL;DR](#1-tldr)
2. [Why CHA exists](#2-why-cha-exists)
3. [How the resolution works](#3-how-the-resolution-works)
4. [Why it over-approximates](#4-why-it-over-approximates)
5. [Phantom couplings across `--source` roots](#5-phantom-couplings-across---source-roots)
6. [How inferred edges are marked](#6-how-inferred-edges-are-marked)
7. [Reading a report that contains inferred edges](#7-reading-a-report-that-contains-inferred-edges)
8. [Options considered, and why this one](#8-options-considered-and-why-this-one)
9. [Test map](#9-test-map)

---

## 1. TL;DR

- Spring code calls interfaces; the interesting behaviour lives in the
  implementation. CHA links a call on an interface method to the implementations
  visible in the analysed source, so the analyses can see through the boundary.
- It links a call site to **every** visible implementation — including ones that
  call site can never reach. That is intentional: under-reporting a transaction
  risk is worse than over-reporting one.
- With several `--source` roots and no classpath, the type universe spans
  projects that are not linked at build time, so a call on a shared interface
  (typically `Function`/`Consumer`/`Supplier`, or `Optional`/`Stream.map`) gains
  edges to implementations in projects the caller has no dependency on.
- Those edges are kept, but **tagged**. An edge counts as inferred only when
  *every* underlying call was; one real call site makes it direct.

| Concern | Behaviour |
|---|---|
| Risk hidden behind a DAO interface | found, thanks to CHA |
| A second implementation that is never injected here | edge added anyway (over-approximation) |
| Implementation in an unrelated `--source` project | edge added anyway (**phantom**) |
| Telling the two kinds apart | `edgeKind` / `chaWeight`, see §6 |
| Package coupling metrics | **not** filtered — see §7 |

---

## 2. Why CHA exists

A layered Spring codebase almost always calls an *interface*
(`OrderRepository`), while the I/O happens in the *implementation*
(`JdbcOrderRepository`, holding the `JdbcTemplate`). A plain static scan records
an edge to the interface method and stops there.

The motivating bug: the tool reported **zero** transaction risks on a codebase
that clearly had them, because every risky call sat behind a DAO interface. CHA
was the fix, and it is what makes the transaction and lock analyses trustworthy.

---

## 3. How the resolution works

During the scan the extractor records, for every concrete class, the interfaces
it implements. After all types are scanned,
[`CallGraphExtractor.resolveInterfaceCalls`](../src/main/java/com/codingful/carve/extractor/CallGraphExtractor.java)
runs once:

```
1. Index every method node by  "declaringTypeFqn#methodName"
2. For each existing edge  caller → target:
      impls ← classes implementing target.declaringType
      for each impl:
          for each node matching  impl#target.methodName:
              add edge  caller → node        (tagged as inferred)
```

A single pass covers the common one-level case (BO → DAO interface → DAO impl).
Deeper interface-to-interface chains are traversed naturally afterwards, because
the edges CHA added are ordinary graph edges.

Only implementations **in the analysed source** are resolved. Third-party
interfaces, dynamic proxies, Spring AOP advice and reflection are out of scope —
that direction under-reports rather than over-reports.

---

## 4. Why it over-approximates

CHA has no notion of which implementation is actually wired at a given call site;
it only knows the static type. So a call on `OrderRepository.save(...)` is linked
to `JdbcOrderRepository` **and** `InMemoryOrderRepository`, even though Spring
injects exactly one of them.

This is a deliberate trade. The alternative — an RTA-style filter that keeps only
implementations instantiated on a reachable path — misfires on Spring code
precisely where it matters: beans are injected, not `new`-ed, so the filter would
drop the very edges CHA was introduced to find, turning over-reported risks into
missed ones.

---

## 5. Phantom couplings across `--source` roots

Multiple `--source` roots are loaded into **one** Spoon model. CHA's type
universe is that whole model, so it does not stop at a project boundary. Two
projects that share nothing but a JDK interface still get linked:

```java
// project "alpha" — no dependency on, and no reference to, beta
public class AlphaService {
    private Function<String, String> transform;
    public String run(String in) { return transform.apply(in); }   // ← call site
}

// project "beta"
public class BetaHandler implements Function<String, String> {
    public String apply(String s) { return s.toUpperCase(); }       // ← linked by CHA
}
```

`AlphaService → BetaHandler` appears in the graph although `alpha` never mentions
`beta` in any form. The imprecision scales with functional interfaces: on a real
44-module workspace, `Function`-based handlers and `Optional`/`Stream.map`
produced over a thousand inferred edges, many of them between services with no
Maven dependency between them.

Running each project on its own produces no such edge — the phantom is a product
of analysing them together.

---

## 6. How inferred edges are marked

[`CallGraph.addChaEdge`](../src/main/java/com/codingful/carve/graph/CallGraph.java)
tags every edge CHA creates. An edge that already exists because a real call site
produced it stays **direct** — direct evidence is the stronger claim, and CHA
rediscovering it does not weaken it.

Collapsed to class and package level, an edge is inferred only when **every**
underlying method call was inferred; a single real call site makes the whole
edge direct.

| Output | How inferred edges appear |
|---|---|
| Console | `Edges : 17  (5 inferred by class hierarchy analysis)` |
| `analysis.json` | `summary.chaEdges` |
| `class-graph.gexf` | edge columns `edgeKind` (`direct`/`cha`) and `chaWeight` — partition or filter on them in Gephi |
| `call-graph.dot` | dashed grey edges |
| `class-graph.html`, `package-graph.html` | amber links, a *Hide inferred (CHA) edges* filter, an inferred count in the header, and a help note in the legend |

---

## 7. Reading a report that contains inferred edges

**For transaction and lock risks, keep them.** They are the reason the analysis
sees anything at all; a risk path through an inferred edge is a path worth
checking, even if the implementation turns out not to be the one wired here.

**For dependency and coupling questions, exclude them.** Comparing carve's output
against `pom.xml` / `build.gradle` will otherwise show dependencies that do not
exist.

One caveat, stated plainly: the **package-coupling section is not filtered**.
[`CouplingAnalyzer.analysePackageCoupling`](../src/main/java/com/codingful/carve/analyzer/CouplingAnalyzer.java)
walks every edge without distinguishing them, so afferent/efferent coupling,
instability and the derived hotspots still include the inferred contributions.
`summary.chaEdges` tells you how many edges are inferred overall, but not which
couplings depend on them — for that, filter `edgeKind` in the GEXF, or use the
HTML filter.

---

## 8. Options considered, and why this one

| Option | Verdict |
|---|---|
| **RTA-style filter** — keep only implementations instantiated on a reachable path | Rejected. Spring beans are injected, not `new`-ed, so it would drop legitimate edges and reintroduce the bug CHA fixed (§4). |
| **Scope CHA to a `--source` boundary** | Plausible, not implemented. Removes the phantoms outright, but also loses real cross-module couplings (interface in `core`, implementation in `impl`, caller in `api` is a common Maven layout), so it needs an opt-out flag. |
| **Mark the edges** | Implemented. Additive, removes nothing, and makes the imprecision measurable — which is also what a boundary-scoping change would need in order to be evaluated. |
| **Document only** | Insufficient on its own; done as well (this file, plus `FEATURES.md` §2b and §9). |

---

## 9. Test map

| Behaviour | Test |
|---|---|
| Inferred edge tagged; direct edge not downgraded | `CallGraphTest.GIVEN_a_cha_edge_...`, `CallGraphTest.GIVEN_an_edge_already_observed_at_a_call_site_...` |
| Only the interface-to-implementation hop is tagged | `CallGraphExtractorTest.GIVEN_a_call_resolved_through_an_interface_...` |
| Phantom cross-project edge (the reproducer) | `CallGraphExtractorTest.GIVEN_independent_source_roots_sharing_an_interface_...` |
| Class-level edge kind, mixed direct + inferred | `ClassGraphModelTest.GIVEN_only_cha_calls_...`, `ClassGraphModelTest.GIVEN_a_class_pair_joined_by_both_...` |
| Package-level edge kind | `PackageGraphModelTest.GIVEN_a_package_coupling_resting_only_on_cha_...` |
| GEXF edge columns | `GexfReporterTest.GIVEN_a_cha_inferred_call_...`, `GexfReporterTest.GIVEN_a_call_site_in_the_source_...` |
| Risk found through an interface (why CHA exists) | `CallGraphExtractorTest.GIVEN_an_http_call_hidden_behind_an_interface_...` |
