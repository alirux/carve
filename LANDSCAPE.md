# Tool landscape

> **Disclaimer:** This comparison was generated with an automated tool and has not been manually verified. It should be treated as a starting point for a proper evaluation, not as a reliable reference. If you spot inaccuracies or know of tools that should be included, feedback is very welcome — feel free to open an issue.

This document positions `carve` relative to existing tools in the Java static analysis and modernisation space.

---

## The closest analogue: jQAssistant

[jQAssistant](https://jqassistant.org/) is the most honest comparison. It scans compiled bytecode, loads everything into a **Neo4j** graph database, and lets you query it with Cypher. It has plugins for Spring, JPA, and MyBatis. The concept is similar: build a dependency graph and run analyses on top of it.

Key differences:

- Works on **bytecode**, not source — requires compiled artifacts
- It is a general-purpose platform: the analyses are Cypher queries you write yourself, not built-in capabilities
- Requires a running Neo4j instance — non-trivial infrastructure for a one-shot analysis
- No built-in transaction risk detection or Spring propagation semantics

---

## Academic program analysis frameworks

**Soot / SootUp** (McGill University) and **WALA** (IBM Research) are the best-known frameworks in this space. They operate on bytecode and support much more sophisticated analyses: points-to, RTA, VTA, full interprocedural data flow.

Why they are not the right fit here:

- Steep learning curve, hostile APIs designed for researchers
- No Spring domain awareness — you would have to build all the domain logic from scratch on top of them
- Overkill for the practical goal of identifying modernisation risks in a Spring codebase

---

## Modernisation-oriented tools

**Windup / Konveyor** (Red Hat) targets Java EE → cloud migration. It produces an inventory of the application (annotations, resources, patterns) and a report estimating migration effort. It does not perform call graph analysis or detect transaction risks — it is more of a migration checklist than a structural analysis.

**OpenRewrite** (Moderne) automatically transforms code (e.g. Spring Boot 2 → 3 upgrade recipes). Different goal entirely: it *executes* the migration rather than helping you understand what needs to change and why.

**Mono2Micro** (IBM) uses AI to suggest how to decompose a monolith into microservices, based on runtime behavioural analysis and clustering. Completely different approach — not static analysis.

---

## Coupling analysis tools

**Sonargraph** (commercial) analyses package and module dependencies with metrics similar to Ca/Ce/instability. It is probably the closest competitor on the coupling analysis side. It does not perform transaction risk analysis.

**Structure101** and **Lattix** (both commercial) focus on Dependency Structure Matrices — useful for visualising layering violations but not for transaction-level risks.

---

## The gap

Looking across the landscape, what is missing everywhere is the combination of:

| Capability | jQAssistant | Soot/WALA | Windup | Sonargraph | carve |
|---|---|---|---|---|---|
| Works on source (no build required) | — | — | — | — | ✓ |
| Spring annotation semantics | partial | — | partial | — | ✓ |
| Transaction risk detection | — | — | — | — | ✓ |
| Propagation-aware BFS | — | — | — | — | ✓ |
| Package instability metrics | — | — | — | ✓ | ✓ |
| SCC cycle detection | via Cypher | ✓ | — | ✓ | ✓ |
| Lightweight single JAR | — | — | — | — | ✓ |
| Custom vendor SDK markers | — | — | — | — | ✓ |
| Multi-project source analysis | — | — | — | — | ✓ |

The most original contribution is not the call graph itself (jQAssistant does that) nor the coupling metrics (Sonargraph does those), but the **intersection of call graph traversal and Spring transactional semantics**: a BFS that respects propagation boundaries and surfaces HTTP/messaging calls that happen while a database transaction is open. This is the most expensive anti-pattern in real Spring monoliths, and no existing tool targets it specifically.
