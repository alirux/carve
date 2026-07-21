# Feeding carve's output to an AI assistant

carve's machine-readable reports make good grounding input for an LLM planning a
modernisation. This document covers which report answers which question, the
practices that turned out to be worth the tokens, one that was not, and the two
caveats to pass along with the data.

Everything here comes from actually doing it: running carve over a real
two-project Spring workspace — a web front end plus a shared back-end library, a
few hundred classes and a few thousand class-to-class edges — and building an
extraction plan from the reports alone. The proportions quoted are from that run.

---

## Table of contents

1. [Why the reports are worth attaching](#1-why-the-reports-are-worth-attaching)
2. [Which report answers which question](#2-which-report-answers-which-question)
3. [Practices that earned their keep](#3-practices-that-earned-their-keep)
4. [One thing not worth trying](#4-one-thing-not-worth-trying)
5. [Two caveats to pass along](#5-two-caveats-to-pass-along)

---

## 1. Why the reports are worth attaching

The numbers are **deterministic and reproducible**: the same source tree yields
the same call graph, the same risk paths, the same coupling metrics. An assistant
asked to plan a service extraction from source alone has to guess at the call
structure, and guesses drift between runs. Given a report, it reasons over a graph
that was actually computed.

That is the division of labour worth aiming for: **carve supplies topology the
assistant cannot reliably derive; the assistant supplies intent the topology
cannot express.** The first two entries in section 3 are both instances of it.

---

## 2. Which report answers which question

| Question | Attach |
|---|---|
| Which packages are unstable hubs? Which `@Transactional` paths reach an external call? Which clusters are cyclic? Which packages depend on which? | `analysis.json` — findings, plus per-package afferent/efferent dependency lists |
| Which **classes** couple these two modules? Is this edge a real call, an exactly-resolved one, or a guess? | `class-edges.csv` — one row per class-to-class edge, with `edgeKind`/`chaWeight`/`implFanOut` |
| The same topology with every node attribute, for a graph tool | `class-graph.gexf` |
| Anything at method granularity | `call-graph.dot` (opt-in via `--dot`) |

`analysis.json` deliberately carries **no edge list**: it reports findings and
package-level coupling rather than the graph itself, which keeps it compact even
on a large multi-module workspace. The class graph is exported separately — as
`class-edges.csv` for reading and filtering, and as `class-graph.gexf` for graph
tools.

**The CSV is the one to hand to a model**: same edges, a fraction of the bytes, one
row per coupling. Size it before attaching, though — a few thousand rows runs to
tens of thousands of tokens, which fits a large context window whole but is not
free. Filtering to the packages under discussion is usually better than pasting it
all.

The two HTML viewers embed the same data but are built for humans; there is no
reason to feed them to a model.

---

## 3. Practices that earned their keep

### Attach the CSV even when the question sounds package-level

`analysis.json` reports findings; the edge list reports *shape*, and the shape is
often the finding.

On the sample workspace the decisive fact was that **more than a third of all
class edges crossed from the front end straight into the back end**, against
roughly one in seven staying inside the front end. That is not a client calling a
service — it is one codebase in two directories, and it constrains every
extraction that follows. No findings section states it; it falls out of counting
rows.

### Ask for the *why*, not the *what* — it can reverse the plan

This is where an assistant earns its place.

A package flagged `unstableHub` — efferent coupling in the dozens, instability
well above 0.8 — reads as *decompose this first*. Reading the source behind it
showed a Strategy interface with one implementation per provider and an explicit
delegator choosing between them at runtime. The fan-out was a **designed variation
point**, not disorder — the seam was already there, and the package went from
hardest to extract to easiest.

Instability cannot distinguish "high `Ce` because it is coupled badly" from "high
`Ce` because it was built to vary", and the two lead to opposite plans. Only
reading the code separates them.

### Send it to the source only where the graph is uncertain

Filter `class-edges.csv` to `edgeKind=cha && implFanOut>1` and ask about those
rows specifically. On the sample workspace that was **about 3% of the graph** — a
reading list, not an audit — and they clustered on the provider integrations,
which is where the interesting decisions were anyway. See
[§5](#5-two-caveats-to-pass-along).

### Ask it to collapse findings by root cause before ranking them

Counts of findings are not counts of problems.

A list of transaction risks running to dozens of entries looked alarming. Grouped
by `externalCallSite`, nearly nine in ten were message publishes and four in five
went through just two call sites — one outbox pattern retires most of the list.
The same applies to lock risks and cyclic clusters.

### Have it sanity-check each archetype against what the package actually is

`extractionCandidates` ranks on low afferent coupling, so a package **at the edge of
the system** would otherwise score top: nothing depends on a controller package, by
construction. That is a leaf, not a bounded context, and "peel it off as a service
first" is the wrong reading. carve now drops the clear-cut case for you — a package
whose Spring components are *all* controllers is left unclassified rather than
ranked.

That leaves the mixed case to the assistant. A package that pairs controllers with
service or repository logic still qualifies, and there the same question applies:
separate *leaf because it is the edge of the system* from *leaf because it is
genuinely self-contained* before acting on the order.

### Triage the risk list

Transaction and lock risks come with full call paths. An assistant can classify
them — a genuine remote call inside a transaction versus a false positive from a
self-invocation the analyser cannot see (see [FEATURES.md
§9](FEATURES.md#9-known-limitations)).

---

## 4. One thing not worth trying

**Validating inferred edges against `pom.xml` / `build.gradle`.**

The idea is sound: a coupling to a module the caller cannot see at build time is
impossible, so the build files should be able to refute phantom edges. But the
granularity is wrong. Build files discriminate *artifacts*, while the ambiguity
class hierarchy analysis introduces lives *inside* one.

On the sample workspace — a two-module dependency chain — the check refuted
**nothing at all**: no inferred edge pointed the wrong way across the only
boundary that existed, and the inferred edges internal to the back-end module —
nearly half of all of them — were beyond anything a build file could say.

It pays off on a workspace with many independent modules, where phantom couplings
between unrelated projects are common (see [CHA.md
§5](CHA.md#5-phantom-couplings-across---source-roots)). Below that, `implFanOut`
is the cheaper signal and needs no extra input.

---

## 5. Two caveats to pass along

**Some edges are inferred rather than observed.** An assistant reading the graph as
a build-dependency map will otherwise report couplings that do not exist. `edgeKind`
tells inferred from observed, but the column to act on is `implFanOut`: an inferred
edge to the *only* implementation of an interface is exact, so telling an assistant
to discard every `cha` row throws away most of a sound graph — on the sample
workspace, nine sound edges sacrificed for every doubtful one removed. Point it at
`edgeKind=cha && implFanOut>1` instead. Full detail in
[CHA.md §6b](CHA.md#6b-how-much-an-inferred-edge-is-worth-implfanout).

**The coupling metrics exclude the *ambiguous* inferred edges.** Afferent/efferent
coupling, instability and the hotspots derived from them are computed over the honest
dependency view — direct edges plus the exactly-resolved inferences — with the phantom
edges (`implFanOut > 1`) filtered out. The `class-edges.csv` you attach still carries
them, tagged, so if you have the assistant build a dependency map from the CSV rather
than from the metrics, tell it to apply the same filter: `edgeKind=cha &&
implFanOut>1` are the rows to drop. See
[CHA.md §7](CHA.md#7-reading-a-report-that-contains-inferred-edges).
