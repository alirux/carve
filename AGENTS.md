# AGENTS.md

Conventions for anyone (human or AI agent) working in this repository. These are
project-wide and committed on purpose — follow them for every change.

Carve is a Spoon + JGraphT tool that analyses Spring codebases for modernisation
(call graph, transaction risks, coupling, lock risks). See [README.md](README.md)
for the architecture and CLI.

## Testing

**Framework:** JUnit 6 + AssertJ. Run with `./gradlew test` (coverage report at
`build/reports/jacoco/test/jacocoTestReport.xml`).

1. **BDD method names**, literal form `GIVEN_..._WHEN_..._THEN_...` — the markers
   `GIVEN`/`WHEN`/`THEN` in uppercase, the descriptive parts in snake_case, e.g.
   `GIVEN_a_cycle_WHEN_finding_longest_paths_THEN_terminates_with_a_simple_path`.
   Long, non-idiomatic-for-Java names are fine; readability of the scenario wins.

2. **Classical / Detroit school — no mocks of any kind.** Use real domain objects
   and real collaborators (a real `CallGraph`, a real Spoon model over `@TempDir`
   files, etc.). The shared real-object builder is
   `src/test/java/com/codingful/carve/support/TestNodes.java`.

3. **Test behaviours, not coverage.** Aim for assertions that would fail under
   mutation: cover empty *and* populated inputs, single *and* multiple items,
   ordering, joining, and format variants. 100% line coverage is not the goal —
   pinned behaviour is. If a surviving mutant has no possible killing input, that
   signals redundant/dead code to remove, not a test to add.

4. **Avoid fragile hardcoded strings**, in three tiers:
   - **Format field names** (JSON/XML output) → deserialize into typed DTO records
     so each name is declared once and checked by the compiler (see
     `JsonReporterTest`).
   - **Pass-through values** where the output must equal an input → assert against
     the input object, never repeat the literal (e.g. `node.score()` vs
     `hotspot.score()`, `hotspot.archetype().jsonKey()`).
   - **Test-data strings reused as both input and lookup key** → extract a named
     constant so input and assertion cannot drift.
   Keep a literal only for a genuine *transform output* (e.g.
   `shortLabel("com.acme.web") == "acme.web"`) — deriving it via production code
   would be tautological.

### Spoon-based tests — one gotcha

When a test asserts on Spoon source-file positions (e.g. `ProjectResolver.resolve`),
base temp paths on `@TempDir.toRealPath()`. On macOS `@TempDir` is `/var/...` while
Spoon reports the canonical `/private/var/...`, so `path.startsWith(root)` fails.

Older Spoon needed `setComplianceLevel(21)` to dodge `Unrecognized option : -25`
under a JDK-25 JVM. This is fixed from **Spoon 11.4.0 (ECJ 3.46.0)**, which
supports JDK 25 natively — both an explicit level 25 and the inherited JVM level
build fine, so no workaround is needed. (Some existing tests still set level 21;
it is harmless, just no longer required.)

## Commit messages

- Describe **what** changed and why, not **how** it was implemented. Keep
  mechanism/refactoring-technique details out of the body. Coverage deltas and
  the behaviour/feature covered are welcome.
- Do **not** add a `Co-Authored-By` trailer.

## Naming & confidentiality

- Never commit real client or vendor names into the repository (code, tests,
  fixtures, docs, commit messages). Use generic placeholders (`acme`, `app.web`,
  `OrderService`, …).
