# `cha-demo` — direct vs inferred edges, in one run

A three-project workspace built to produce **both** kinds of call-graph edge, so
the marking of class-hierarchy-analysis edges can be seen end to end (see
[CHA.md](../../../../../docs/CHA.md)).

The projects are `orders`, `billing` and `notify`. Only `orders → notify` is a
real dependency; `billing` shares nothing with the others except the JDK
`Function` interface.

## Run it

```bash
D=src/test/resources/fixtures/cha-demo
java -jar build/libs/carve-*.jar \
  --source orders:$D/orders \
  --source billing:$D/billing \
  --source notify:$D/notify \
  --output build/cha-demo-out --dot
```

Expected: `Edges : 17  (5 inferred by class hierarchy analysis)`.

## What each edge demonstrates

| Kind | Edge | Why |
|---|---|---|
| direct | `OrderController → OrderService` | call on a concrete class |
| direct | `OrderService → OrderRepository` | call site on the interface itself |
| direct | `OrderService → NotificationGateway` | a **real** cross-project dependency |
| direct | `BillingService → InvoiceFormatter` | `apply()` on a concrete type — not inferred |
| cha | `OrderService → JdbcOrderRepository` | the case CHA exists for: risk hidden behind a DAO interface |
| cha | `OrderService → InMemoryOrderRepository` | fan-out: a second implementation that is never the injected one |
| cha | `LabelRenderer → InvoiceFormatter` | **phantom**: shared `Function`, no relationship between the projects |

At package level the contrast is the point of the fixture: `orders → notify` is
direct, `orders → billing` is inferred — a coupling that appears in the report
but would not appear in any `pom.xml`.

`OrderService` is `@Transactional` and reaches a `RestTemplate` in `notify`, so
the run also produces two transaction risks — the risk paths run *through* an
inferred edge, which is why those edges are kept rather than dropped.

## Note

No test drives this fixture today; it is a manual playground for the reports.
The equivalent behaviour is pinned by unit tests — see the test map in
[CHA.md](../../../../../docs/CHA.md#9-test-map).
