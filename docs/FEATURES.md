# Features & Algorithms

This document explains each analysis the tool performs: **what** it computes, **how** the algorithm works, **why** it matters when modernising a legacy Spring application, and **how** to use it in practice.

The overarching goal is the same throughout: a monolith is easiest to break apart where the code is *loosely coupled* and where *transaction boundaries* are clean. Every feature here exists to find the places that violate those properties, so you can fix them before — or as part of — extracting a service.

---

## Table of contents

1. [Foundation: the call graph](#1-foundation-the-call-graph)
2. [Framework awareness: markers & interface resolution (CHA)](#2-framework-awareness-markers--interface-resolution-cha)
3. [Transaction risk analysis](#3-transaction-risk-analysis)
4. [Longest call chains](#4-longest-call-chains)
5. [Coupling analysis: SCC & package instability](#5-coupling-analysis-scc--package-instability)
6. [DB lock & deadlock risk patterns](#6-db-lock--deadlock-risk-patterns)
7. [Multi-project analysis](#7-multi-project-analysis)
8. [Reporting: DOT, JSON, console](#8-reporting-dot-json-console)
9. [Known limitations](#9-known-limitations)

---

## 1. Foundation: the call graph

### What it is

Every other analysis runs on top of a single data structure: a **directed call graph**. Each vertex is a method (`MethodNode`, identified by `declaringTypeFqn#methodSignature`); each edge `A → B` means "method A contains an invocation of method B".

Nodes carry the Spring metadata the analyses need:

- whether the method is `@Transactional` (and with which propagation / `readOnly`),
- its Spring stereotype (`@Service`, `@Repository`, `@Controller`, …),
- which external-call categories it makes (`HTTP`, `JDBC`, `JPA`, `MESSAGING`, `CACHE`).

### How it is built

The graph is constructed by `CallGraphExtractor`, a Spoon [`CtScanner`](https://spoon.gforge.inria.fr/) that walks the AST of every source type. Construction is **two-phase**:

1. **Scan phase** — for each `visitCtMethod` the extractor creates/updates a node and reads its annotations; for each `visitCtInvocation` it adds an edge to the *statically declared* target type, and tags the target node if its declaring class matches a known external-call client. It also records, for every concrete class, which interfaces it implements (the data CHA needs in phase 2).

2. **Resolution phase** — `resolveInterfaceCalls()` runs once after all types are scanned. See [§2](#2-framework-awareness-markers--interface-resolution-cha).

Spoon resolves types from source; passing the application's JARs via `--classpath` improves resolution accuracy but is not required (`--no-classpath` mode degrades gracefully).

### Why it matters for modernisation

The call graph is the map of the territory. "Which code reaches which code" is the fundamental question behind every extraction decision — you cannot move a method to a new service without knowing everything it transitively calls and everything that transitively calls it.

### How to use it

You normally don't interact with it directly — it's the substrate for the analyses below. It is emitted as `call-graph.dot` for visual inspection (see [§8](#8-reporting-dot-json-console)).

---

## 2. Framework awareness: markers & interface resolution (CHA)

These two mechanisms make the call graph *meaningful* for a Spring codebase, where the interesting behaviour hides behind framework classes and interfaces.

### 2a. External-call markers

#### What it is

A registry that recognises when a method invocation crosses a process/network boundary. `SpringMarkers` holds the fully-qualified names of standard clients:

| Category | Recognised clients |
|---|---|
| `HTTP` | `RestTemplate`, `WebClient`, `RestClient`, Feign, `java.net.http.HttpClient`, OkHttp, Apache HttpClient |
| `JDBC` | `JdbcTemplate`, `NamedParameterJdbcTemplate`, MyBatis `SqlSession` / `SqlSessionTemplate` |
| `JPA` | `EntityManager` (`javax` + `jakarta`), Spring Data repositories |
| `MESSAGING` | `KafkaTemplate`, `RabbitTemplate`, `JmsTemplate` |

When the extractor sees an invocation whose declaring type matches one of these, it tags the node with the category.

#### Custom markers for vendor SDKs

Real codebases call proprietary SDKs that the tool can't know about — for example a payment-gateway or document-signing SDK (`com.acme.payments.*`) that talks SOAP/HTTPS to a remote server but whose classes live in closed vendor JARs. `UserDefinedMarkers` lets you teach the analyser about these via a `.properties` file:

```properties
# analyzer-markers.properties
com.acme.payments. = HTTP        # trailing dot = whole-package prefix match
com.acme.legacymq. = MESSAGING
```

Matching is exact-FQN first, then longest-prefix. The file is loaded via `--markers <file>`, or auto-discovered as `analyzer-markers.properties` next to the source root or in the working directory.

#### Why it matters for modernisation

External calls are the seams of a distributed system. Knowing where they happen tells you (a) which methods already cross a boundary — natural candidates for becoming a service call — and (b), combined with transaction data, where you have a latency/consistency hazard ([§3](#3-transaction-risk-analysis)). Without custom markers, a method that calls a vendor SDK looks like inert local code and the most dangerous remote calls in the system stay invisible.

#### How to use it

1. Run once without markers and review the detected external calls.
2. For every vendor/in-house client that performs I/O but isn't recognised, add a line to `analyzer-markers.properties`.
3. Re-run. Iterate until the external-call map matches reality.

### 2b. Class Hierarchy Analysis (CHA) — interface resolution

#### What it is

Spring layers almost always call *interfaces* (`OrderService`, `NotificationDao`), while the I/O happens in the *implementation* (`OrderServiceImpl` with a `JdbcTemplate`). A naive static scan records an edge to the interface method and stops there — so the transaction risk hiding in the implementation is never reached.

CHA closes this gap. After scanning, for every edge whose target is an interface method, it adds edges from the same caller to **every concrete implementation of that interface found in the source tree**.

#### How the algorithm works

```
1. Build an index:  "declaringTypeFqn#methodName" → [concrete MethodNodes]
2. For each existing edge  caller → target:
      impls ← classes implementing target.declaringType   (collected during scan)
      for each impl:
          for each node matching  impl#target.methodName:
              add edge  caller → node
```

A single pass handles the common one-level case (BO → DAO interface → DAO impl). Deeper interface-to-interface chains are then traversed naturally by the BFS/DFS in the downstream analyses, because the edges CHA added are ordinary graph edges.

#### Why it matters for modernisation

This is what makes the transaction analysis trustworthy on real Spring code. The motivating bug: the tool initially reported **zero** transaction risks for a codebase that clearly had them, precisely because the risky calls sat behind DAO interfaces. CHA was the fix.

#### How to use it

Automatic — it always runs. To benefit, simply make sure the implementations are part of the analysed source tree (CHA only resolves implementations it can see; third-party or dynamically-proxied implementations are out of scope — see [§9](#9-known-limitations)).

---

## 3. Transaction risk analysis

### What it is

Finds `@Transactional` methods that — directly or through a chain of calls — reach an **external call while the database transaction is still open**. HTTP and messaging calls are flagged; JDBC/JPA are *not*, because database access inside a transaction is expected.

### How the algorithm works

For each transactional root, a **breadth-first search** walks the call graph:

```
for each @Transactional root node:
    BFS over successors:
        if current node makes a risky external call (HTTP / MESSAGING):
            record a TransactionRisk(root, callSite, types, path)
        for each successor:
            enqueue it ONLY IF it stays within the same transaction scope
```

The crucial subtlety is **transaction-scope propagation**. The traversal does not blindly follow every edge; it stops at propagation boundaries that suspend or refuse the current transaction:

| Propagation on callee | Traversal |
|---|---|
| `REQUIRED`, `SUPPORTS`, `MANDATORY`, `NESTED`, or no annotation | **continues** — same physical transaction |
| `REQUIRES_NEW`, `NOT_SUPPORTED`, `NEVER` | **stops** — a new/suspended scope begins, so the outer tx no longer applies |

The concrete root-to-site call path is reconstructed with JGraphT's `BFSShortestPath`, so each reported risk comes with the exact chain of methods that produces it.

### Why it matters for modernisation

This is the flagship analysis. Holding a pooled DB connection open across a network round-trip is one of the most damaging anti-patterns in a monolith:

- **Resource exhaustion** — a slow downstream service keeps the connection checked out; under load the pool drains and unrelated requests block.
- **Coupling that resists decomposition** — the database commit and the remote call share a fate (the remote call's success/failure is entangled with the local transaction). You cannot cleanly split the two systems until this is untangled, typically by moving the remote call *outside* the transaction or adopting an explicit pattern (outbox, saga, compensating action).

Each reported path is a concrete, prioritised work item for the modernisation backlog.

### How to use it

```bash
java -jar carve.jar src/main/java --markers analyzer-markers.properties --print-risks
```

- `--print-risks` prints each risk as an indented call stack from the transactional root down to the offending external call.
- The same data lands in `analysis.json` under `transactionRisks` (root, call site, call types, full path).
- In `call-graph.dot`, the edges along risk paths are drawn in **red**.

Triage workflow: start with HTTP-in-transaction risks (the most acute), follow the printed path to the call site, and decide whether to hoist the call out of the transaction or introduce an async/outbox boundary.

---

## 4. Longest call chains

### What it is

Reports the **N longest simple call chains** (default 10) through the application code — the deepest "spaghetti" paths in the system.

### How the algorithm works

Two phases, over the application-only subgraph (library stubs excluded):

1. **Depth phase** — a memoised DFS computes, for each node, the length of the longest simple path reachable from it (`maxReachableDepth`). Nodes currently on the DFS stack are skipped, which breaks cycles (a deliberately conservative approximation: depth through a cyclic region may be undercounted, but acyclic stretches are exact and the pass stays fast).

2. **Reconstruction phase** — starting from the deepest candidate nodes, it greedily follows, at each step, the unvisited successor with the highest memoised depth, producing a concrete path. Results are de-duplicated and sorted longest-first.

### Why it matters for modernisation

Long call chains are a proxy for **deep, tangled control flow**. They tend to:

- thread through many packages/layers, so any change ripples widely;
- make a single request span a large surface of the codebase, which is exactly what makes a request hard to carve out into an independent service;
- concentrate hidden transactional and I/O behaviour (a long chain under a `@Transactional` root is a prime suspect for the risks in [§3](#3-transaction-risk-analysis)).

Shortening or decoupling these chains is a high-leverage refactoring target, and the chains often reveal natural seams where one layer hands off to another.

### How to use it

```bash
java -jar carve.jar src/main/java --print-paths
```

- `--print-paths` prints each chain as an indented tree, annotating nodes with `@Transactional` and external-call markers (`[HTTP]`, `[JDBC]`, …) so you can see at a glance whether a long chain also carries a transaction or I/O hazard.
- The same data is in `analysis.json` under `longestPaths` (depth + node ids).

Read a long chain top-to-bottom and look for a point where the responsibility clearly shifts — that's a candidate boundary for splitting a class or extracting a collaborator.

---

## 5. Coupling analysis: SCC & package instability

Two complementary lenses on how entangled the code is.

### 5a. Strongly Connected Components (cycle detection)

#### What it is

A **Strongly Connected Component (SCC)** is a maximal set of methods where every method can reach every other through the call graph — i.e. a knot of mutual recursion/cyclic dependency. The analyser reports all SCCs with more than one member.

#### How the algorithm works

`CouplingAnalyzer.findCyclicClusters()` runs JGraphT's **Kosaraju** strong-connectivity inspector over the application-only subgraph, keeps components of size > 1, and sorts them largest-first.

Use `--print-cycles` to print clusters to the console. Results are also written to `analysis.json` under `cyclicClusters`, and cyclic nodes are drawn with a **double border** in `call-graph.dot`.

#### Why it matters for modernisation

Methods in the same SCC are **mutually dependent**: you cannot extract any one of them into a separate service without dragging the others along or breaking a cycle. Large SCCs are the single biggest obstacle to splitting a monolith — they must be untangled (by inverting a dependency, introducing an interface/event, or splitting a class) *before* extraction is feasible. Cycle size is a direct measure of "how stuck" a region is.

### 5b. Package instability

#### What it is

For each package, a coupling profile based on cross-package edges:

- **Ca** (afferent coupling) — number of packages that depend **on** this one (incoming).
- **Ce** (efferent coupling) — number of packages this one depends **on** (outgoing).
- **Instability** `I = Ce / (Ca + Ce)`, ranging from 0 to 1.

| `I` | Meaning | Modernisation implication |
|---|---|---|
| ≈ 0 | Maximally **stable** — many depend on it, it depends on little | Shared kernel / core API. Hard to change; stabilise as an internal contract and extract last. |
| ≈ 1 | Maximally **unstable** — depends on much, nothing depends on it | Leaf. Changes are self-contained; **safest first candidate** for extraction. |

#### How the algorithm works

`analysePackageCoupling()` iterates every edge; when source and target packages differ it records an efferent dependency for the source package and an afferent dependency for the target. Each package's `Ca`/`Ce` are the sizes of those dependency *sets* (distinct packages, not raw edge counts), and `I` follows from the formula.

The afferent/efferent coupling concept comes from Constantine & Yourdon's structured-design work (1974); the instability metric for OO packages was formalised by Robert C. Martin in the late 1990s.

#### Why it matters for modernisation

Instability tells you the **order** in which to attack the monolith. Unstable leaf packages are low-risk to pull out (nothing depends on them, so extraction breaks no callers). Stable core packages are where the whole system rests — extract them prematurely and everything moves at once. Sorting packages by instability gives a defensible extraction sequence.

#### How to use it

Package coupling is written to `analysis.json` under `packageCoupling`. The section has two parts:

- **`packages`** — the flat profile of every package (`Ca`, `Ce`, `instability`, the dependency sets, and an `applicationCode` flag), sorted by descending instability.
- **`hotspots`** — the *actionable* view: only application packages, classified into three modernisation archetypes (library/JDK packages are excluded, being stable by definition). Each entry carries a `score` ranking it **within** its archetype, and the three lists are sorted by descending score.

The archetypes turn the raw `Ca`/`Ce`/`I` triple into a decision:

| Archetype (JSON key) | Design principle | Shape | What to do | `score` |
|---|---|---|---|---|
| **`unstableHubs`** | *Stable Dependencies Principle* violation — what many depend on should be stable, but this isn't | high `Ca` **and** high `I` | The architectural bottleneck. Untangle it **first** (split by sub-domain, invert dependencies) — nothing extracts cleanly until it's broken up. | `Ca · I` |
| **`extractionCandidates`** | *Low blast radius* — few depend on it, so removing it breaks little | low `Ca`, high `I`, substantial `Ce` | The safest **first** packages to peel off as a separate service/module. | `Ce · I` |
| **`stableCores`** | *Stable abstractions / shared kernel* — heavily depended on and already stable | high `Ca` **and** low `I` | Protect behind explicit ports/interfaces; extract last, don't rewrite. | `Ca · (1 − I)` |

The `score` is a **relative** ranking metric, not a normalised 0–1 value: it scales with `Ca`/`Ce`, which have no upper bound, so the score is **unbounded above** (a bigger, more entangled package simply scores higher). It is only ever compared *within* one archetype. Given the classification thresholds below, the **effective minimum is `3.5`** for all three archetypes (`5 × 0.70`); anything classified scores at least that.

Packages sitting near the "main sequence" (balanced `Ca`/`Ce`, e.g. a domain model with `I ≈ 0.5`) are intentionally left unclassified — they belong to no single archetype. The classification thresholds (`Ca ≥ 5` for a hub, `I ≥ 0.70` for unstable, `Ca ≤ 3` for a leaf, `I ≤ 0.30` for stable) live as named constants in `CouplingAnalyzer`.

A typical reading: start service-extraction from the top of `extractionCandidates`, schedule the `unstableHubs` for decomposition, and harden the `stableCores` as internal contracts.

---

## 6. DB lock & deadlock risk patterns

Static analysis cannot determine *which tables or rows* a method touches at runtime — that information lives in SQL/JPQL strings, not in the call graph. What the call graph *can* reveal is the **structural conditions** under which lock contention or deadlock becomes possible. Two patterns are detected.

### 6a. REQUIRES_NEW nested inside an open transaction

#### What it is

When a `@Transactional` method calls — directly or transitively — a method annotated with `REQUIRES_NEW`, the outer transaction is suspended but **its database locks are still held** while the inner transaction runs on a second connection. If both transactions access the same rows, or if the connection pool cannot supply two concurrent connections, the result is deadlock or connection starvation.

#### How the algorithm works

The same BFS used in the transaction risk analysis ([§3](#3-transaction-risk-analysis)) is run from every `@Transactional` root, following the same propagation rules. When a node with `REQUIRES_NEW` is encountered it is **recorded as a lock risk** rather than silently treated as a scope boundary. Traversal stops there — the inner scope is not followed further. Results are deduplicated by `(outerRoot, requiresNewSite)` pair, keeping the shortest path.

#### Why it matters for modernisation

`REQUIRES_NEW` is used deliberately — audit logs, payment authorisations, and outbox records are common examples where you need the commit to succeed regardless of the outer transaction's fate. The locking hazard is real but easy to miss because the annotation *looks* like it cleanly isolates concerns. The analysis surfaces each pair so you can judge whether the inner and outer transactions can plausibly contend on the same rows.

#### How to use it

```bash
java -jar carve.jar src/main/java --print-lock-risks
```

Each finding prints the full call path from the outer `@Transactional` root to the `REQUIRES_NEW` site:

```
Nested-tx 1/2
──────────────────────────────────────────────────────────────
OrderService.processOrder()         @Transactional
  └─► AuditService.record()         @Transactional(REQUIRES_NEW)
```

---

### 6b. @Transactional methods in cyclic call clusters

#### What it is

When two or more `@Transactional` methods belong to the same Strongly Connected Component (see [§5a](#5a-strongly-connected-components-cycle-detection)), concurrent requests can enter them in different orders. Each request starts its own database transaction and acquires row locks in the order it visits entities. If two concurrent requests traverse the cycle in opposite directions, classical deadlock conditions arise.

#### How the algorithm works

`LockRiskAnalyzer.findCyclicTransactionRisks()` filters the SCC clusters already computed by `CouplingAnalyzer` ([§5a](#5a-strongly-connected-components-cycle-detection)), keeping only clusters that contain **at least two `@Transactional` methods**. The threshold of two is deliberate: a single `@Transactional` node in a cycle creates no independent transaction entry points within that cycle (`REQUIRED` calls join the caller's existing transaction), whereas two or more create the possibility of separate concurrent transactions acquiring the cycle's locks in different orders. Results are sorted by transactional-node count descending.

#### Why it matters for modernisation

This finding exposes two problems at once: a structural cycle that prevents service extraction ([§5a](#5a-strongly-connected-components-cycle-detection)) and a locking hazard that worsens with load. Resolving the cycle — by inverting a dependency, introducing an event/callback, or splitting a class — removes both problems simultaneously.

#### How to use it

Same `--print-lock-risks` flag. Each finding lists the cluster members with `@Transactional` nodes marked ⚠:

```
Cyclic-tx 1/1  (4 methods, 2 @Transactional)
──────────────────────────────────────────────────────────────
  InvoiceService.createInvoice()    @Transactional ⚠
  OrderService.finalise()           @Transactional ⚠
  InvoiceService.link()
  OrderService.updateStatus()
```

Lock risk findings are written to `analysis.json` under `lockRisks`, with two sub-arrays: `nestedRequiresNew` and `cyclicTransactional`.

> **Important caveat.** These are structural risk patterns, not confirmed bugs. Whether contention actually occurs depends on which rows each transaction accesses at runtime. Use the findings as a prioritised review list.

---

## 7. Multi-project analysis

### What it is

Analyses two or more Maven/Gradle modules — or independent projects — as a single unified call graph, with each method attributed to the named project whose source tree it came from.

The motivating case is a Spring application split across several modules: a web module with `@RestController` classes that depends on a core module with the `@Transactional` services and `@Repository` data-access code. Analysing only one module at a time gives an incomplete picture: risks in the core are never reached from the web entry points, and the longest call chains are truncated at the module boundary.

### How it works

All source roots are loaded into a single Spoon model. Spoon resolves types across roots, so CHA and other cross-module references work the same as within a module. `ProjectResolver` maps each type back to its source root by matching the source file's absolute path against the known root paths (longest prefix wins, for nested roots). Application nodes are tagged with `projectName` immediately after construction.

```bash
java -jar carve.jar \
     --source api:/path/to/api/src/main/java \
     --source core:/path/to/core/src/main/java \
     --classpath /path/to/lib/spring-webmvc.jar:/path/to/lib/spring-tx.jar \
     --print-risks --print-paths
```

### What you gain

**Full end-to-end paths.** A transaction risk path that starts in the `api` controller and reaches an HTTP call in the `core` service is now reported as a single chain, with each step labelled by its project:

```
Risk 1/1  [HTTP]
──────────────────────────────────────────────────────────────
[api]  OrderController.submit()         @Transactional
       └─► [core]  OrderService.place() @Transactional
                 └─► [core]  PaymentClient.charge()   ← HTTP
```

**Cross-project cycle detection.** If a method in `api` calls into `core` and a method in `core` calls back into `api` (through an interface or a shared utility), the SCC analysis catches it as a cycle spanning both projects. A cycle between modules means the two JARs are physically interchangeable but logically coupled — they cannot be deployed or versioned independently.

**Per-project method counts in JSON.** The `summary` section of `analysis.json` gains a `projects` field:

```json
"projects": { "api": 42, "core": 158 }
```

**Visual separation in the DOT graph.** Each node label is prefixed with its project name (`[api]` / `[core]`), making the flow of control across module boundaries immediately visible when the graph is rendered.

### Why it matters for modernisation

Realistic Spring applications are rarely single-module. The split into modules is often the *intended* dependency direction (`api` → `core`), but the coupling analysis may reveal violations — `core` packages that import `api` types, or cyclic SCCs that span both modules. These violations are the root cause of the modules being undifferentiable at extraction time.

Multi-project analysis gives you the full picture that single-module analysis cannot: entry points, execution paths, and transaction scopes all within the same graph, across the true boundary you intend to harden.

### How to use it

1. Identify all modules that share a transaction or a call path you want to trace end-to-end.
2. Pass each module's source root with a `--source name:path` flag. All modules included this way are fully parsed from source — cross-module type references resolve automatically. Do **not** also put those same modules on `--classpath`; that would duplicate the types.
3. Pass third-party framework JARs (Spring, JPA, etc.) via `--classpath` if you want Spoon to resolve their type hierarchies accurately. This is optional but improves CHA quality.
4. Run with `--print-risks` and `--print-paths` to see full cross-module chains, and `--print-cycles` to catch dependency cycles that span module boundaries.
5. Check `analysis.json` → `summary.projects` to see the per-module method count breakdown.

---

## 8. Reporting: interactive viewers, DOT, JSON, console

The tool always writes four files (`class-graph.html`, `package-graph.html`, `class-graph.gexf`, `analysis.json`), optionally writes a fifth (`call-graph.dot`, via `--dot`), and can additionally print to the console.

### Multi-level collapse — the interactive viewers (recommended)

A method-level graph of a real monolith easily exceeds 10,000 nodes and 20,000 edges. Rendered as a static SVG it is an unreadable "hairball": the image is technically correct but conveys nothing. No renderer fixes this — the problem is showing every method at once.

The tool collapses the method-level graph at **two levels**, each emitted as a self-contained interactive viewer:

#### Class-level collapse (`class-graph.html`)

One node per class, edges weighted by underlying method-call count; each class node aggregates the metadata of its methods (project, `@Transactional`, external-call types, cyclic membership, risk membership). On a large codebase this is a 10–15× reduction (e.g. ~8,000 methods → ~600 classes).

**`class-graph.html`** — a self-contained 3D viewer built on [3d-force-graph](https://github.com/vasturiano/3d-force-graph) (WebGL/Three.js). Open it in a browser; nothing to install. Supports rotate / zoom / pan, per-project filtering, a "risks only" filter, risk highlighting, and class search. In multi-project mode nodes are coloured by project so the module boundary is visible; otherwise by role (transactional / external / both).

Also emitted as **`class-graph.gexf`** — the same class-level graph in [Gephi](https://gephi.org/)'s native format, with colour and size pre-set via `viz:` attributes. Every aggregated attribute is a Gephi column, so you can partition, filter, and re-colour by project, role, cyclicity, or method count, and run ForceAtlas2 / community detection.

#### Package-level collapse (`package-graph.html`)

One node per Java package, sized proportionally to the number of classes it contains, edges weighted by cross-package class-to-class calls. Attributes are the union of the underlying class nodes (transactional, external-call, cyclic, risk). This is typically a 5–20× further reduction from the class-level graph — ideal for the first architectural overview.

**`package-graph.html`** — same 3D viewer technology as `class-graph.html`. Includes a **"Group by project" checkbox** that clusters packages by module using a secondary spring layout, making cross-project dependencies immediately visible.

It also surfaces the **modernisation hotspots** (see [§5b](#5b-package-instability)): each package classified as an `unstableHub`, `extractionCandidate` or `stableCore` is drawn in a dedicated colour, with a **"Highlight hotspots"** toggle (on by default, works in both role- and project-colour modes) and an **"Only modernisation hotspots"** filter. Hovering or selecting a package shows its archetype and `score`. The controls and legend appear only when the graph actually contains hotspots.

Why these two levels: for an architectural conversation — "which modules depend on which, where are the cycles" — the package is the right starting unit, and the class level shows the next layer of detail. Method-level specifics live in the console reports and `analysis.json`.

### `call-graph.dot` (Graphviz, method-level — opt-in via `--dot`)

Off by default: on a real monolith the rendered SVG is the unreadable hairball that motivated the interactive exports above. Enable it with `--dot` when you actually want a method-level picture — mainly on a small module. Colour-coded node roles:

| Colour | Meaning |
|---|---|
| Light yellow | `@Transactional` |
| Light coral (red) | makes an external call |
| Orange | both transactional **and** external call — the highest-attention nodes |
| Light grey | library / non-application node |
| White | plain application code |

Edges on transaction-risk paths are drawn red/bold. Cyclic nodes (members of an SCC) get a **double border**. In multi-project mode each node label is prefixed with its project name. Render with:

```bash
java -jar carve.jar src/main/java --dot
dot -Tsvg call-graph.dot -o call-graph.svg
```

On a small module this is a useful whiteboard artefact: orange nodes with red edges leading into them *are* your modernisation hot-spots.

### `analysis.json`

Machine-readable report with six top-level sections:

| Key | Content |
|---|---|
| `summary` | Vertex / edge counts, application vs library breakdown; `projects` map in multi-project mode |
| `transactionRisks` | Each risk: transactional root, external-call site, call types, full path (node ids) |
| `longestPaths` | Top 10 chains: depth + ordered node id list |
| `cyclicClusters` | Each SCC with size > 1: member count + sorted node id list |
| `packageCoupling` | Object with `packages` (flat per-package Ca, Ce, instability, dependency sets, `applicationCode`, sorted by descending instability) and `hotspots` (application packages grouped into `unstableHubs`, `extractionCandidates`, `stableCores`, each with a per-archetype `score`) |
| `lockRisks` | Two sub-arrays: `nestedRequiresNew` (outer root + REQUIRES_NEW site + path) and `cyclicTransactional` (cluster members + transactional subset) |

Feed it into dashboards, diff across releases to track whether risks go up or down, or use it as a CI gate.

### Console (`--print-risks`, `--print-paths`, `--print-cycles`, `--print-lock-risks`)

Human-readable indented output for quick inspection during a working session:

- `--print-risks` — indented call stack from each `@Transactional` root to the external-call site
- `--print-paths` — the 10 longest call chains, annotated with `@Transactional` and `[HTTP]`/`[JDBC]` markers
- `--print-cycles` — lists each cyclic cluster with its members sorted by class and method name
- `--print-lock-risks` — nested-REQUIRES_NEW paths and cyclic-`@Transactional` clusters (see [§6](#6-db-lock--deadlock-risk-patterns))

---

## 9. Known limitations

Be aware of these when interpreting results during a modernisation effort:

- **Java version** — Spoon 11.2.1 / ECJ 3.41.0 parses up to Java 23. Java 24/25 sources await a future Spoon upgrade.
- **Spring AOP self-invocation** — `@Transactional` only takes effect through the Spring proxy. A `this.otherMethod()` call bypasses the proxy and its annotation. The analyser cannot detect self-invocation and conservatively assumes the annotation is honoured (may over-report).
- **Dynamic dispatch beyond the source tree** — CHA resolves interface calls to implementations *in the analysed source*. Third-party interfaces, dynamic proxies, Spring AOP advice, and reflection are not tracked (may under-report).
- **Cyclic depth approximation** — the longest-path search undercounts depth through strongly-connected regions by design (to guarantee termination on simple paths).
- **Encoding** — legacy codebases not in UTF-8 (e.g. ISO-8859-1) must pass `--encoding` to avoid `MalformedInputException`.
- **Multi-project classpath** — modules included via `--source` are fully resolved from source. Only third-party JARs (Spring, JPA, vendor SDKs) should go on `--classpath`; never put a `--source` module's compiled JAR on `--classpath` as well — that duplicates types and can confuse resolution.
- **Lombok** — generated members are not modelled (source is parsed without running Lombok), so coupling through generated accessors is recovered heuristically and `@Builder` chains are not. When Lombok is detected the tool warns on the console and flags every report. See [LOMBOK.md](LOMBOK.md) for the full behaviour, fixes, and tradeoffs.
