# carve

Static analysis tool for Java/Spring codebases, built on top of [Spoon](https://spoon.gforge.inria.fr/) and [JGraphT](https://jgrapht.org/).

Primary use case: supporting **modernisation of legacy Spring applications** by mapping dependencies and detecting patterns that make splitting into independent services difficult.

> See **[FEATURES.md](FEATURES.md)** for a detailed explanation of every analysis — the algorithms used, why each matters for modernisation, and how to apply it.

## Analysis

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

---

### DB lock and deadlock risk patterns

The analyser identifies two structural patterns that create conditions for database lock contention or deadlock.

**Nested `REQUIRES_NEW`.**  When a `@Transactional` method calls — directly or through a chain — a method annotated `REQUIRES_NEW`, the outer transaction is suspended but keeps its DB locks while the inner transaction opens a second connection. If both transactions touch the same rows, or if the pool cannot supply two concurrent connections, the result is deadlock or connection starvation.

**Cyclic `@Transactional` clusters.** When two or more `@Transactional` methods belong to the same call cycle (SCC), concurrent requests can acquire row locks in different orders, satisfying the classical conditions for deadlock. Resolving the cycle also removes the locking hazard.

> Static analysis cannot determine which rows are locked at runtime. These findings are structural risk patterns to review, not confirmed bugs.

## Build

Requires JDK 21+ (tested with JDK 25).

```bash
./gradlew test          # run all tests
./gradlew shadowJar     # build fat-jar → build/libs/carve-1.0.0-SNAPSHOT.jar
```

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
| `class-graph.html` | **Interactive 3D viewer** — open in a browser. Class-level graph with rotate/zoom/pan, per-project filtering, risk highlighting, and search. |
| `class-graph.gexf` | Class-level graph for [Gephi](https://gephi.org/) — colour/size pre-set, every attribute (project, transactional, external, cyclic, inRisk, methods) available for partitioning and filtering. |
| `call-graph.dot` | Graphviz DOT (method-level), **opt-in via `--dot`** — colour-coded by role (yellow = `@Transactional`, red = external call, orange = both). On large codebases the rendered SVG is an unreadable hairball; use the interactive exports instead. |
| `analysis.json` | JSON report: graph summary, transaction risks, longest paths, cyclic clusters, package coupling, lock risks. |

**Interactive exploration (recommended).** A method-level graph of a large monolith (10k+ nodes) is an unreadable hairball as a static SVG. The tool therefore also emits a **class-level collapse** (one node per class, edges weighted by call count) in two interactive forms:

- `class-graph.html` — self-contained 3D viewer (WebGL), nothing to install; just open it.
- `class-graph.gexf` — load into Gephi for ForceAtlas2 layout, community detection, and rich attribute filtering.

The static method-level DOT is opt-in (`--dot`), mainly useful on small modules:
```bash
java -jar carve.jar src/main/java --dot
dot -Tsvg reports/call-graph.dot -o reports/call-graph.svg
```

## Architecture

```
src/main/java/com/codingful/carve/
├── spring/
│   └── SpringMarkers.java          # all Spring/Jakarta FQN constants and detection logic
├── model/
│   ├── MethodNode.java             # call-graph vertex: identity + Spring metadata
│   ├── ExternalCallType.java       # HTTP, JDBC, JPA, MESSAGING, CACHE
│   ├── SpringComponentType.java    # SERVICE, REPOSITORY, CONTROLLER, …
│   └── TransactionPropagation.java # mirrors Spring's Propagation enum
├── extractor/
│   ├── CallGraphExtractor.java     # Spoon CtScanner: graph construction + CHA resolution
│   ├── ProjectResolver.java        # maps source file paths to named projects (multi-project mode)
│   └── UserDefinedMarkers.java     # loads vendor/custom FQN→ExternalCallType from .properties
├── graph/
│   └── CallGraph.java              # JGraphT wrapper with filtered views
├── analyzer/
│   ├── TransactionAnalyzer.java    # BFS from @Transactional roots → risk detection
│   ├── TransactionRisk.java        # risk record: root + call site + path
│   └── CouplingAnalyzer.java       # SCC + package coupling metrics
├── reporter/
│   ├── DotReporter.java            # Graphviz DOT output
│   └── JsonReporter.java           # JSON report
└── Carve.java             # CLI entry point + programmatic API
```

To add detection for a well-known library (e.g. a gRPC client), add its FQN to `SpringMarkers.java`. For project-specific vendor SDKs, use a `--markers` file instead — this keeps `SpringMarkers` focused on standard library patterns.

**Supported data-access libraries**

| Library | Classes detected | Tagged as |
|---|---|---|
| Spring JDBC | `JdbcTemplate`, `NamedParameterJdbcTemplate` | `JDBC` |
| MyBatis | `SqlSession`, `SqlSessionTemplate` | `JDBC` |
| JPA / Hibernate | `EntityManager` (javax + jakarta) | `JPA` |
| Spring Data | `JpaRepository`, `CrudRepository`, … | `JPA` |

## Known limitations

**Java version support:** Spoon 11.2.1 / ECJ 3.41.0 parses source code up to Java 23. Java 24/25 requires a future Spoon upgrade.

**Spring AOP self-invocation:** `@Transactional` only takes effect on public methods called through the Spring proxy. A method calling `this.otherMethod()` bypasses the proxy and its annotation. The analyser cannot detect self-invocations and conservatively treats all calls as if the annotation is honoured.

**Dynamic dispatch:** interface calls within the analysed source tree are resolved via CHA (see above). Calls that cross the source boundary — third-party interfaces, dynamic proxies, Spring AOP advice, or reflection — are not tracked.

## Stack

| Dependency | Version | Role |
|---|---|---|
| [Spoon](https://github.com/INRIA/spoon) | 11.2.1 | Java AST parsing and type resolution |
| [JGraphT](https://jgrapht.org/) | 1.5.2 | Call graph, SCC, path algorithms |
| [Jackson](https://github.com/FasterXML/jackson) | 3.2.0 | JSON reporting |
| [SLF4J](https://www.slf4j.org/) + Logback | 2.0.13 / 1.5.6 | Logging |
| [JUnit](https://junit.org/) + AssertJ | 6.0.3 / 3.27.7 | Testing |
| [Shadow](https://gradleup.com/shadow/) | 9.0.0 | Fat-jar packaging |
