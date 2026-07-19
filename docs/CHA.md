# Class hierarchy analysis: inferred edges & phantom couplings

This document explains what **class hierarchy analysis (CHA)** does to the call
graph, why it deliberately over-approximates, the phantom couplings it produces
on multi-project runs, and how inferred edges are marked so they can be told
apart from real ones.

It matters most for the multi-`--source` case, where several separately-built
projects are analysed in one model.

---

## Table of contents

1. [TL;DR](#1-tldr) 2. [Why CHA exists](#2-why-cha-exists) 3. [How the resolution
works](#3-how-the-resolution-works) 4. [Why it
over-approximates](#4-why-it-over-approximates) 5. [Phantom couplings across
`--source` roots](#5-phantom-couplings-across---source-roots) 6. [How inferred
edges are marked](#6-how-inferred-edges-are-marked) 6b. [How much an inferred edge
is worth: `implFanOut`](#6b-how-much-an-inferred-edge-is-worth-implfanout) 7.
[Reading a report that contains inferred
edges](#7-reading-a-report-that-contains-inferred-edges) 8. [Options considered,
and why this one](#8-options-considered-and-why-this-one) 9. [Test
map](#9-test-map)

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
- Inference is not the same as guessing. When the interface has a single
  implementation, CHA resolves it exactly. `implFanOut` records how many
  candidates there were, so the guessed edges can be separated from the sound
  ones — on a real monolith that is 10% of them, not all (§6b).

| Concern | Behaviour |
|---|---|
| Risk hidden behind a DAO interface | found, thanks to CHA |
| A second implementation that is never injected here | edge added anyway (over-approximation) |
| Implementation in an unrelated `--source` project | edge added anyway (**phantom**) |
| Telling inferred from observed | `edgeKind` / `chaWeight`, see §6 |
| Telling *exact* inference from a guess | `implFanOut`, see §6b |
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

During the scan the extractor records, for every concrete class, the interfaces it
implements. After all types are scanned,
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
large multi-module workspace, `Function`-based handlers and
`Optional`/`Stream.map` produced inferred edges by the thousand, many of them
between services with no Maven dependency between them.

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
| `class-graph.gexf` | edge columns `edgeKind` (`direct`/`cha`), `chaWeight` and `implFanOut` (§6b) — partition or filter on them in Gephi |
| `class-edges.csv` | an `edgeKind` column (`direct`/`cha`) plus `chaWeight` and `implFanOut` (§6b), one row per class-to-class edge — the compact form of the class graph |
| `call-graph.dot` | dashed edges, grey when CHA resolved the only implementation and amber when it was choosing between several (§6b); the tooltip carries the count |
| `class-graph.html`, `package-graph.html` | amber links for the **ambiguous** edges only (§6b), blue for exactly-resolved inferences, grey for call sites; an *Inferred edges* filter (`show all` / `hide ambiguous only` / `hide all inferred`) with its own help beside the control, separate inferred and ambiguous counts in the header, and the colour key in the legend |

---

## 6b. How much an inferred edge is worth: `implFanOut`

`edgeKind` says an edge was inferred. It does not say whether the inference was a
*guess*, and most of the time it is not.

CHA over-approximates only when it has a choice to make. If the interface has
**one** implementation in the analysed source, CHA does not guess — it resolves
the only candidate, and the resulting edge is as sound as one read off a call
site. The over-approximation described in §4 begins at two.

So every inferred edge carries `implFanOut`: **how many implementations CHA was
choosing between when it created that edge.**

| `implFanOut` | Meaning | How to treat it |
|---|---|---|
| `0` | Not an inferred edge (`edgeKind=direct`) | Observed in the source |
| `1` | The interface had exactly one implementation | Sound — inferred, but exact |
| `n > 1` | One of `n` candidates; `n-1` of these edges are wrong | Genuine over-approximation; check the source |

The count is of implementing classes that **actually declare the called method**.
A class that implements the interface but inherits that particular method is not
an alternative at that call site, and does not inflate the number.

Collapsed to class level, an edge takes the **maximum** fan-out across the
inferred calls behind it. The maximum, not the average: the column reports the
worst ambiguity the edge rests on, so a single exactly-resolved call cannot mask
an ambiguous one.

### Why this matters more than it sounds

The blanket advice in §7 — *"for coupling questions, exclude inferred edges"* —
turns out to throw away mostly good data. On a real Spring monolith, of the edges
CHA inferred:

| `implFanOut` | Share of inferred edges |
|---|---|
| `1` — exact, the interface had one implementation | **~90%** |
| `> 1` — guessed | **~10%** |

Dropping every inferred edge therefore discards nine sound ones for each doubtful
one it removes. The guessed tenth is what to look at — and it is not spread
evenly: it clusters in the provider-integration packages, where a Strategy
interface has one implementation per vendor and the choice really is made at
runtime.

That concentration is itself a finding. Ambiguous CHA edges mark the places where
the codebase has a deliberate variation point, which is usually also where a
service boundary already exists.

### Practical filters

```bash
# The couplings worth verifying by hand — usually a few dozen rows
awk -F, '$8=="cha" && $7>1' class-edges.csv

# A dependency map: observed edges plus the exactly-resolved ones
awk -F, 'NR==1 || $8=="direct" || $7==1' class-edges.csv
```

The second is the honest dependency view. Excluding `edgeKind=cha` wholesale, as
earlier versions of this document advised, is the conservative one.

In Gephi, `implFanOut` is declared as an **integer** column on edges, so it takes
a *Range* filter: set it to `[2, ∞)` to see only the couplings CHA guessed at, or
exclude that range to get the dependency view above. `edgeKind` remains available
as a string partition when the coarser direct/inferred split is what you want.

In both HTML viewers, the **Inferred edges** control offers the same three
views without leaving the page:

| Setting | Keeps | Use it to |
|---|---|---|
| `show all` (default) | everything | see the whole graph, amber marking the guesses |
| `hide ambiguous only` | call sites + exact inferences | read the graph as a dependency map — the equivalent of the second `awk` above |
| `hide all inferred` | call sites only | see what a plain call-site scan would have found, and how much rests on CHA |

The last two are **not** nested. *hide all inferred* drops only edges resting
*entirely* on inference, so an edge with at least one real call site survives it
even when other calls behind it were inferred — a few amber edges can therefore
remain. They have direct evidence, so keeping them is correct; it just means the
two settings answer different questions rather than one being stricter.

The header reports the two counts separately (`… · N inferred · M ambiguous`), so
the split is visible without changing the filter.

### What it still cannot tell you

`implFanOut=1` means unambiguous *in the analysed source*. An implementation that
lives outside the `--source` roots, or is supplied by a dynamic proxy, is
invisible either way — see §3. And `implFanOut>1` does not say **which** candidate
is real; that lives in Spring wiring or, as often, in runtime data. The column
narrows where to look, it does not answer the question.

---

## 7. Reading a report that contains inferred edges

**For transaction and lock risks, keep them.** They are the reason the analysis
sees anything at all; a risk path through an inferred edge is a path worth
checking, even if the implementation turns out not to be the one wired here.

**For dependency and coupling questions, exclude the *ambiguous* ones.** Comparing
carve's output against `pom.xml` / `build.gradle` will otherwise show dependencies
that do not exist. But exclude on `implFanOut > 1`, not on `edgeKind = cha`:
an inferred edge to the only implementation of an interface is a real dependency,
and dropping it understates the coupling as badly as keeping a phantom overstates
it (§6b).

One caveat, stated plainly: the **package-coupling section is not filtered**.
[`CouplingAnalyzer.analysePackageCoupling`](../src/main/java/com/codingful/carve/analyzer/CouplingAnalyzer.java)
walks every edge without distinguishing them, so afferent/efferent coupling,
instability and the derived hotspots still include the inferred contributions.
`summary.chaEdges` tells you how many edges are inferred overall, but not which
couplings depend on them — for that, filter `class-edges.csv` on `implFanOut`
(§6b), the GEXF on the same column in Gephi, or the *hide ambiguous only* option
in either HTML viewer.

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
| Fan-out of 1 for a single-implementation interface | `CsvReporterTest.GIVEN_an_interface_with_one_implementation_...` |
| Fan-out reported for an ambiguous interface | `CsvReporterTest.GIVEN_an_interface_with_several_implementations_...` |
| Worst fan-out wins when an edge mixes both | `CsvReporterTest.GIVEN_an_edge_resting_on_calls_of_differing_ambiguity_...` |
| Package-level edge kind | `PackageGraphModelTest.GIVEN_a_package_coupling_resting_only_on_cha_...` |
| GEXF edge columns | `GexfReporterTest.GIVEN_a_cha_inferred_call_...`, `GexfReporterTest.GIVEN_a_call_site_in_the_source_...` |
| GEXF fan-out, exact and ambiguous | `GexfReporterTest.GIVEN_an_interface_with_one_implementation_...`, `GexfReporterTest.GIVEN_an_interface_with_several_implementations_...` |
| GEXF fan-out declared numeric, so Gephi can range-filter | `GexfReporterTest.GIVEN_the_edge_columns_...` |
| Risk found through an interface (why CHA exists) | `CallGraphExtractorTest.GIVEN_an_http_call_hidden_behind_an_interface_...` |
