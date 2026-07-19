<p align="center">
  <img src="docs/logo.png" alt="carve logo" width="120">
</p>

# Carve

![CI](https://github.com/alirux/carve/actions/workflows/ci.yml/badge.svg)
[![Coverage](https://codecov.io/gh/alirux/carve/branch/main/graph/badge.svg)](https://codecov.io/gh/alirux/carve)

Static analysis tool for Java/Spring codebases, built on top of [Spoon](https://spoon.gforge.inria.fr/) and [JGraphT](https://jgrapht.org/).

Primary use case: supporting **modernisation of legacy Spring applications** by mapping dependencies and detecting patterns that make splitting into independent services difficult.

Every report is deterministic — the same source tree always yields the same graph, the same risk paths, the same metrics — and the JSON, CSV and GEXF outputs are structured for machine consumption, so they double as grounding data for an AI assistant planning the modernisation work ([how to use them](docs/AI.md)).

## Analysis

> See **[FEATURES.md](docs/FEATURES.md)** for a detailed explanation of every analysis — the algorithms used, why each matters for modernisation, and how to apply it.

### Transaction risks

The analyser identifies `@Transactional` methods (Spring, `javax`, `jakarta`) that — directly or through a chain of calls — reach an external system while the database transaction is still open.

**Why this matters.** Holding a database connection open during an HTTP call or a message publish ties two latency domains together: a slow downstream service delays the transaction, keeping the connection checked out and potentially exhausting the pool. It also makes the two systems harder to separate into independent services later.

**How it works.** Starting from each `@Transactional` root, the analyser performs a BFS over the call graph, following every reachable method. When it finds a node that invokes a known external client, it records the risk together with the full call path from root to call site.

The traversal respects transaction propagation boundaries: if a called method is annotated with `REQUIRES_NEW`, `NOT_SUPPORTED`, or `NEVER`, the branch is not followed (the outer transaction is suspended there, so the risk does not apply).

**Detected external calls:**
- **HTTP**: `RestTemplate`, `WebClient`, `RestClient`, `FeignClient`, `java.net.http.HttpClient`, OkHttp, Apache HttpClient
- **Messaging**: `KafkaTemplate`, `RabbitTemplate`, `JmsTemplate`

JDBC and JPA calls inside a transaction are expected and are not flagged.

**Interface resolution (CHA).** Spring codebases typically separate the `@Transactional` BO layer from the data-access layer through interfaces. A plain call-graph scan stops at the interface type and never reaches the concrete implementation where the real I/O happens. After scanning all types, the analyser performs a **Class Hierarchy Analysis** pass: for every call to an interface method, it adds edges to every concrete implementation found in the same source tree. This ensures that risks hidden behind interface boundaries — such as a `NotificationService` interface whose implementation calls a REST endpoint — are not silently missed.

---

### Coupling analysis

#### Strongly Connected Components (SCC)

An SCC is a group of methods where every method can reach every other method through the call graph — i.e. there is a call cycle among them. The analyser finds all SCCs with more than one member using the Kosaraju algorithm.

**Why this matters.** Methods in the same SCC are mutually dependent: you cannot extract any one of them into a separate service without breaking a dependency on at least one of the others. Large SCCs are the primary obstacle to splitting a monolith and need to be untangled first.

#### Package instability

For each package, the analyser counts:

- **Ca** (afferent coupling) — number of other packages that depend *on* this package (incoming edges across the package boundary).
- **Ce** (efferent coupling) — number of packages this package depends *on* (outgoing edges across the boundary).

From these it computes:

```
I = Ce / (Ca + Ce)        range [0, 1]
```

The concept of measuring afferent/efferent coupling originates in the structured design work of Constantine & Yourdon (1974); the instability formula applied to OO packages was formalized by Robert C. Martin in the late 1990s.

| Value | Meaning | Implication |
|---|---|---|
| I ≈ 0 | Maximally stable — many depend on it, it depends on nothing | Core API; hard to change without breaking callers |
| I ≈ 1 | Maximally unstable — nothing depends on it, it depends on many | Good extraction candidate; changes are self-contained |

Packages with **high instability and few afferent dependencies** are typically the safest starting point for service extraction. Packages with **low instability** are the shared kernel — stabilise them as internal APIs or extract them last.

On top of the raw numbers the analyser classifies each *application* package into one of three actionable modernisation archetypes, reported under `packageCoupling.hotspots`:

| Archetype | Shape | Principle | Action |
|---|---|---|---|
| **`unstableHubs`** | high `Ca` + high `I` | Stable Dependencies Principle violation | Bottleneck — decompose first |
| **`extractionCandidates`** | low `Ca` + high `I` | Low blast radius | Peel off as a service first |
| **`stableCores`** | high `Ca` + low `I` | Shared kernel | Harden behind ports, extract last |

Each entry has a `score` ranking it within its archetype. See [FEATURES.md §5b](docs/FEATURES.md#5b-package-instability) for thresholds and the full scoring formulas.

---

### DB lock and deadlock risk patterns

The analyser identifies two structural patterns that create conditions for database lock contention or deadlock.

**Nested `REQUIRES_NEW`.**  When a `@Transactional` method calls — directly or through a chain — a method annotated `REQUIRES_NEW`, the outer transaction is suspended but keeps its DB locks while the inner transaction opens a second connection. If both transactions touch the same rows, or if the pool cannot supply two concurrent connections, the result is deadlock or connection starvation.

**Cyclic `@Transactional` clusters.** When two or more `@Transactional` methods belong to the same call cycle (SCC), concurrent requests can acquire row locks in different orders, satisfying the classical conditions for deadlock. Resolving the cycle also removes the locking hazard.

> Static analysis cannot determine which rows are locked at runtime. These findings are structural risk patterns to review, not confirmed bugs.

## Run

```bash
# Single project
java -jar build/libs/carve-1.0.0-SNAPSHOT.jar <source-root> [options]

# Multi-project (analyse two or more source trees together)
java -jar build/libs/carve-1.0.0-SNAPSHOT.jar \
     --source <name>:<path> --source <name>:<path> ... [options]

Options:
  --source <name>:<path>   Named source root (repeatable, for multi-project analysis)
  --classpath <path>       Colon-separated JARs for type resolution
  --java <version>         Java compliance level for source parsing (default: 21, range: 11–23)
  --encoding <name>        Source file encoding (default: UTF-8; use ISO-8859-1 for legacy codebases)
  --markers <file>         Properties file with custom external-call markers (see below)
  --print-risks            Print transaction risk call stacks to console
  --print-paths            Print the 10 longest call chains to console
  --print-cycles           Print cyclic method clusters to console
  --print-lock-risks       Print DB lock/deadlock risk patterns to console
  --dot                    Also write method-level call-graph.dot (Graphviz)
  --output <dir>           Output directory (default: current directory)
```

Arguments are validated up front: a missing source root, a non-existent `--markers`
or `--classpath` entry, an `--output` that is not a directory, or an invalid `--java`
/ `--encoding` value fails immediately with a clear message and a non-zero exit code,
rather than a stack trace partway through the run.

### Source roots vs classpath

`--source` and `--classpath` serve different levels of the dependency graph:

| Code | How to include | What Spoon sees |
|---|---|---|
| Your own modules | `--source name:path` | Full AST — method bodies, annotations, generics |
| Third-party JARs | `--classpath path/to.jar` | Type signatures only — no bodies |
| Everything else | (neither) | Nothing — unresolved references, degraded accuracy |

**Rule of thumb:** every module whose internal logic matters to the analysis should be a `--source` root. Everything else that is needed only for type resolution (Spring JARs, vendor SDKs, utility libraries) goes on `--classpath`. Never put the same code on both — if a module is already a `--source` root its compiled JAR on `--classpath` would duplicate the types.

### Single-project example

```bash
java -jar carve.jar /path/to/myapp/src/main/java \
     --classpath /path/to/myapp/lib/spring-context-6.1.jar:/path/to/myapp/lib/hibernate-core-6.jar \
     --java 17 \
     --encoding ISO-8859-1 \
     --markers my-markers.properties \
     --print-risks \
     --output ./reports
```

Here `--classpath` lists the framework JARs the application depends on. Spoon cannot see their source but uses them to resolve method signatures and supertype hierarchies, which improves CHA accuracy.

### Multi-project example

A common pattern in Spring applications is a web module (controllers, actions) that depends on a core module (services, repositories, domain model). Analysing only one module at a time truncates call chains at the module boundary. Pass both as `--source` roots to get end-to-end paths:

```bash
java -jar carve.jar \
     --source api:/path/to/myapp-api/src/main/java \
     --source core:/path/to/myapp-core/src/main/java \
     --classpath /path/to/lib/spring-webmvc-6.1.jar:/path/to/lib/spring-tx-6.1.jar \
     --markers my-markers.properties \
     --print-risks --print-paths \
     --output ./reports
```

Both modules are parsed from source so cross-module type references resolve fully — no JAR for either module is needed on `--classpath`. The framework JARs are still useful for resolving Spring annotations and interface hierarchies.

In multi-project mode each node is prefixed with its project name, so cross-module call chains are immediately visible:

```
Risk 1/1  [HTTP]
──────────────────────────────────────────────────────────────
[api]  DocumentController.upload()            @Transactional
       └─► [core]  SigningService.sign()      @Transactional
                 └─► [core]  RemoteSigner.call()   ← HTTP
```

With `--print-paths` the 10 longest call chains are printed:

```
Longest call chains: 10

──────────────────────────────────────────────────────────────
Path 1/10  (depth: 8)
──────────────────────────────────────────────────────────────
OrderService.checkout()                  @Transactional
      └─► OrderServiceImpl.process()
            └─► OrderServiceImpl.finalizeOrder()   @Transactional
                  └─► PaymentClient.charge()
                        ...
```

The paths include annotations (`@Transactional`) and external-call markers (`[HTTP]`, `[JDBC]`) on each node where relevant. Results are also included in `analysis.json` under the `longestPaths` key.

With `--print-risks` each detected risk is printed as an indented call stack:

```
Transaction risks: 1

──────────────────────────────────────────────────────────────
Risk 1/1  [HTTP]
──────────────────────────────────────────────────────────────
OrderService.checkout()                     @Transactional
      └─► OrderServiceImpl.process()
            └─► PaymentClient.charge()       ← HTTP
```

### Custom external-call markers

`SpringMarkers` covers the standard Spring/JEE ecosystem, but some projects integrate vendor SDKs or custom RPC clients that are not recognised out of the box. Use a markers properties file to teach the analyser about them:

```properties
# analyzer-markers.properties
# Format: fully-qualified class name (or package prefix ending with '.') = CALL_TYPE
# CALL_TYPE values: HTTP, MESSAGING, JDBC, JPA, CACHE

# Payment gateway SDK — makes HTTPS calls to a remote endpoint
com.acme.payments. = HTTP

# Internal messaging library
com.acme.legacy.mq. = MESSAGING
```

A key ending with `.` matches the entire package subtree; otherwise the match is exact.

**Auto-discovery:** the analyser looks for `analyzer-markers.properties` automatically, first in the parent directory of the source root, then in the current working directory. The `--markers` flag overrides this and accepts any path.

**Local marker files:** the `markers/` directory is gitignored — drop project-specific marker files there (they often reference proprietary vendor class names you don't want in version control) and point to them explicitly:

```bash
java -jar carve.jar src/main/java --markers markers/my-project.properties
```

### Outputs

| File | Description |
|---|---|
| `class-graph.html` | **Interactive 3D class viewer** — open in a browser. One node per class; rotate/zoom/pan, per-project filtering, risk highlighting, and search. |
| `package-graph.html` | **Interactive 3D package viewer** — open in a browser. One node per package, sized by class count; optional "Group by project" clustering; **modernisation hotspots** (unstable hubs / extraction candidates / stable cores) colour-coded with highlight toggle and "only hotspots" filter. |
| `class-graph.gexf` | Class-level graph for [Gephi](https://gephi.org/) — colour/size pre-set, every attribute (project, transactional, external, cyclic, inRisk, methods) available for partitioning and filtering. Edges carry `edgeKind`, `chaWeight` and `implFanOut`, the last as a numeric column so a Gephi range filter isolates the inferred couplings that are genuinely ambiguous. |
| `call-graph.dot` | Graphviz DOT (method-level), **opt-in via `--dot`** — colour-coded by role (yellow = `@Transactional`, red = external call, orange = both). On large codebases the rendered SVG is an unreadable hairball; use the interactive exports instead. |
| `class-edges.csv` | **Class-level edges, one row per coupling** — `source,sourceProject,target,targetProject,weight,chaWeight,implFanOut,edgeKind`. The compact, greppable form of the class graph: `edgeKind=cha` marks an inferred edge, and `implFanOut` says how many implementations that inference was choosing between — `1` is exact, `>1` is a guess worth checking ([details](docs/CHA.md#6b-how-much-an-inferred-edge-is-worth-implfanout)). |
| `analysis.json` | JSON report: graph summary, transaction risks, longest paths, cyclic clusters, package coupling (flat profile + modernisation `hotspots`), lock risks. |

**Interactive exploration (recommended).** A method-level graph of a large monolith (10k+ nodes) is an unreadable hairball as a static SVG. The tool therefore collapses to **class level** and **package level**, each emitted as a self-contained 3D viewer:

- `class-graph.html` — self-contained 3D viewer (WebGL), nothing to install; just open it.
- `package-graph.html` — same viewer at package granularity; includes a "Group by project" toggle that clusters packages by module, plus colour-coded **modernisation hotspots** with a "Highlight hotspots" toggle and an "only hotspots" filter.
- `class-graph.gexf` — load into Gephi for ForceAtlas2 layout, community detection, and rich attribute filtering.

<p align="center">
  <img src="docs/Class Graph grouped by package.jpg" alt="class-graph.html — classes grouped by package" width="720">
  <br><em>class-graph.html — classes coloured by package, risks highlighted in red</em>
</p>

<p align="center">
  <img src="docs/Package Graph grouped by project.jpg" alt="package-graph.html — packages grouped by project" width="720">
  <br><em>package-graph.html — packages sized by class count, grouped by project</em>
</p>

The static method-level DOT is opt-in (`--dot`), mainly useful on small modules:
```bash
java -jar carve.jar src/main/java --dot
dot -Tsvg reports/call-graph.dot -o reports/call-graph.svg
```

### Feeding the output to an AI assistant

> See **[AI.md](docs/AI.md)** for the full guide — which report answers which question, the practices that turned out to be worth the tokens, one that was not, and the caveats to pass along.

The reports work as grounding input for an LLM planning a modernisation, because the numbers are **deterministic and reproducible**: the same source tree yields the same call graph, the same risk paths, the same coupling metrics. An assistant working from source alone has to guess at the call structure, and guesses drift between runs. `class-edges.csv` is the one to hand to a model — the whole class graph, one row per coupling, at a fraction of the GEXF's bytes.

The highest-value use is asking for the *why* rather than the *what*: carve supplies topology the assistant cannot reliably derive, and the assistant supplies intent the topology cannot express. A package flagged as an unstable hub may turn out to be a Strategy pattern with the seam already cut — which reverses its position in the extraction order.

## Known limitations

**Java version support:** source parsing tracks the Java version supported by the bundled Spoon/ECJ (currently up to Java 25). A brand-new Java release may need a Spoon upgrade before its newest syntax is recognised.

**Spring AOP self-invocation:** `@Transactional` only takes effect on public methods called through the Spring proxy. A method calling `this.otherMethod()` bypasses the proxy and its annotation. The analyser cannot detect self-invocations and conservatively treats all calls as if the annotation is honoured.

**Dynamic dispatch:** interface calls within the analysed source tree are resolved via CHA (see above). Calls that cross the source boundary — third-party interfaces, dynamic proxies, Spring AOP advice, or reflection — are not tracked.

## Build

### Prerequisites

- JDK 25 (other versions ≥ 21 work for compilation, but Spoon's Java 25 source support requires the matching toolchain)
- No separate Gradle installation — the wrapper (`./gradlew`) downloads the right version automatically

### Local build

```bash
./gradlew test          # compile + run all tests
./gradlew check         # tests + coverage gate (fails if coverage regresses)
./gradlew shadowJar     # build fat-jar → build/libs/carve-<version>.jar
./gradlew build         # both of the above
```

`check` enforces a JaCoCo coverage floor (90% instructions, 80% branches); a
change that drops below it fails the build.

When built locally the JAR is versioned `dev-SNAPSHOT`. Pass `VERSION=x.y.z` to produce a named build:

```bash
VERSION=1.2.0 ./gradlew shadowJar
# → build/libs/carve-1.2.0.jar
```

### Releases

Releases are created by pushing a version tag. GitHub Actions builds the fat-jar and attaches it to the release automatically:

```bash
git tag v1.0.0
git push origin v1.0.0
```

The tag must follow the `v<semver>` format (e.g. `v1.0.0`, `v1.2.3`). The resulting JAR and release notes are published on the [Releases](../../releases) page.

## Architecture

The code under `src/main/java/com/codingful/carve/` is organised into packages that
mirror the analysis pipeline:

| Package | Responsibility |
|---|---|
| `spring` | Spring/Jakarta FQN constants and stereotype/annotation detection |
| `model` | Call-graph vertex (`MethodNode`) and its enums (component type, external-call type, propagation) |
| `extractor` | Spoon `CtScanner` that builds the graph and resolves calls (CHA), plus project and custom-marker resolution |
| `graph` | JGraphT wrapper with filtered views over the call graph |
| `analyzer` | Transaction-risk, longest-path, coupling (SCC + metrics) and DB-lock-risk analyses |
| `reporter` | Class/package graph collapse and the HTML, GEXF, DOT, JSON and console exporters |
| `util` | Shared FQN string helpers |

The top-level classes wire it together: `Carve` (CLI entry point + pipeline
orchestration), `CarveConfig` (argument parsing and validation), `ReportWriter`
(parallel report generation) and `Analyses` (aggregated results).

To add detection for a well-known library (e.g. a gRPC client), add its FQN to `SpringMarkers.java`. For project-specific vendor SDKs, use a `--markers` file instead — this keeps `SpringMarkers` focused on standard library patterns.

**Supported data-access libraries**

| Library | Classes detected | Tagged as |
|---|---|---|
| Spring JDBC | `JdbcTemplate`, `NamedParameterJdbcTemplate` | `JDBC` |
| MyBatis | `SqlSession`, `SqlSessionTemplate` | `JDBC` |
| JPA / Hibernate | `EntityManager` (javax + jakarta) | `JPA` |
| Spring Data | `JpaRepository`, `CrudRepository`, … | `JPA` |

## Stack

Built on [Spoon](https://github.com/INRIA/spoon) for Java source parsing and type
resolution, and [JGraphT](https://jgrapht.org/) for the call-graph and graph
algorithms.

## License

[AGPL 3.0](LICENSE), compatible with the licenses of its bundled dependencies.
Their attributions and licenses are listed in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)
(also shipped inside the JAR).
