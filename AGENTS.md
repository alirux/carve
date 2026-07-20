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
   Refactoring the production code under test to make it testable without mocks is
   explicitly allowed — extract a pure function, change visibility, split a class,
   replace `System.exit` with a thrown exception, introduce a seam. Prefer a
   behaviour-preserving refactor over reaching for a mock.

3. **Test behaviours, not coverage.** Aim for assertions that would fail under
   mutation: cover empty *and* populated inputs, single *and* multiple items,
   ordering, joining, and format variants. 100% line coverage is not the goal —
   pinned behaviour is. If a surviving mutant has no possible killing input, that
   signals redundant/dead code to remove, not a test to add.
   **A useless test is a wrong test — don't write it, and delete it if it exists.**
   Useless means it tests the obvious, merely restates the implementation
   (tautology — e.g. asserting `SET.contains(x)` for every `x` already in that
   `SET`), or exists only to push the coverage number up. Cover the real behaviour
   with a meaningful test (often from real source / a real collaborator) instead.

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
   would be tautological. Apply all three tiers in the first draft: before
   considering a test file done, scan it for repeated contract strings and extract
   constants up front.

### Spoon-based tests — one gotcha

When a test asserts on Spoon source-file positions (e.g. `ProjectResolver.resolve`),
base temp paths on `@TempDir.toRealPath()`. On macOS `@TempDir` is `/var/...` while
Spoon reports the canonical `/private/var/...`, so `path.startsWith(root)` fails.

Older Spoon needed `setComplianceLevel(21)` to dodge `Unrecognized option : -25`
under a JDK-25 JVM. This is fixed from **Spoon 11.4.0 (ECJ 3.46.0)**, which
supports JDK 25 natively — both an explicit level 25 and the inherited JVM level
build fine, so no workaround is needed.

## Commit messages

- Describe **what** changed and why, not **how** it was implemented. Keep
  mechanism/refactoring-technique details out of the body. Coverage deltas and
  the behaviour/feature covered are welcome.
- Do **not** add a `Co-Authored-By` trailer.
- **Before every commit, run the full test suite and make sure it is green**
  (`./gradlew test`, or `./gradlew check` to include the coverage gate). Never
  commit with failing or unrun tests.
- **Before every commit, check that the markdown docs (`README.md`, `AGENTS.md`,
  `THIRD-PARTY-NOTICES.md`, …) are consistent with what is being committed** —
  update them in the same change if the code, conventions, structure, or commands
  they describe have moved. Treat stale docs as part of the diff, not a follow-up.
  In particular, update `THIRD-PARTY-NOTICES.md` whenever the commit changes a
  bundled runtime dependency (an `implementation`/`runtimeOnly` entry in
  `build.gradle.kts`).
- **When a commit touches the HTML report templates
  (`src/main/resources/com/codingful/carve/reporter/*.html`), check the README
  screenshots** — `docs/Class Graph grouped by package.jpg` and
  `docs/Package Graph grouped by project.jpg`. They show the viewer's control
  panel and legend, so a new filter, a renamed control, a changed edge colour or
  an extra figure in the header caption makes them wrong, and a screenshot that
  contradicts the surrounding text is worse than no screenshot.
  Text-only edits inside the page do not need a retake; anything visible in the
  panel, the legend or the header does. Retaking them is a manual step (open the
  report in a browser, same framing, same grouping toggle) — if it cannot be done
  in the same commit, say so explicitly rather than letting it pass silently.

## Releases

Tags follow `v<semver>` and pushing one publishes a release. Before creating a
release tag:

1. **Check for breaking changes** since the previous release tag — diff the
   public API surface and the CLI options/behaviour.
2. If there are breaking changes, **verify the requested version is bumped per
   semantic versioning** (a breaking change requires a major bump; in `0.x`, a
   minor bump conventionally signals it).
3. If the requested version does **not** match what semver requires, do not tag
   silently — ask the user to choose between:
   1. proceed with the version as given,
   2. use the semver-correct version you propose, or
   3. cancel.

## Naming & confidentiality

- Never commit real client or vendor names into the repository (code, tests,
  fixtures, docs, commit messages). Use generic placeholders (`acme`, `app.web`,
  `OrderService`, …).
