# Phase 0 — Foundations: design

Status: ready to implement · Parent plan: [README.md](../README.md) · Oct 13, 2026

Phase 0 builds the machinery every later reliability test depends on: a self-contained `reliability/` tree in a fork of `rollerderby/scoreboard`, a JSON manifest of expected failures, and a runner that judges each run against that manifest. Everything in Phase 0 is started on demand by a person: there are no scheduled runs and no CI. The suite's own correctness is checked programmatically by one command, `ant -f reliability/build.xml verify` (section 12). Phase 0 adds exactly one test for a real finding: a pilot for F7 (section 13), used to validate the plan and the workflow end to end. The remaining findings get tests only after the pilot works and its retrospective is reviewed. Scheduled runs start in Phase 1.

This document is written so an agent can implement it without further questions. Where a choice was open, it is decided here.

## 1. Decisions already made

| Topic | Decision |
| --- | --- |
| Code home | A fork of `rollerderby/scoreboard` under `cabacon-gmail`. All new files live under `reliability/` at the repo root. |
| Coupling to existing code | None. No existing file is modified: not `build.xml`, `src/`, `tests/`, `html/`, `.circleci/`, `.gitignore` or `package.json`. `ant test` and `ant zip` behave exactly as before. |
| Language and level | Java, compiled with `source`/`target` 1.8, JUnit 4.13.2 and Hamcrest 2.2 from `test-libs/`. |
| Dependencies | Only jars already in the repo: `lib/deps/*.jar` (jackson-jr is used for JSON) and `test-libs/*.jar`. No downloads, no new jars. |
| Manifest | JSON, parsed with jackson-jr (`com.fasterxml.jackson.jr.ob.JSON`). |
| Where it runs | Dedicated desktops running Linux, Windows and macOS, started on demand. No scheduled jobs and no CI in Phase 0. |
| How Phase 0 is verified | Programmatically, by the `verify` target (section 12). Nobody edits files by hand to check the runner. |
| Known-issue mechanism | The manifest only. No annotations or JUnit rules on test classes. |

## 2. Prerequisites (owner: Charles)

1. Create the fork `cabacon-gmail/scoreboard` from `rollerderby/scoreboard` on GitHub. (The agent session cannot create forks.)
2. Give the Claude GitHub App access to the fork, as was done for `crg-reliability-plan`.
3. Each test desktop has a JDK (11–21 is fine; it compiles to 1.8), Apache Ant 1.10+, and git on its `PATH`.

## 3. Branching

- Upstream develops on `dev` (its default branch); `master` there is an older branch and is not used. The fork's `dev` mirrors upstream `dev` and is not committed to directly.
- A long-lived branch `reliability` carries the suite. It is created from upstream `dev` at the start of Phase 0 and periodically merged forward from upstream.
- Phase 0 work happens on `reliability-phase0`, branched from `reliability`, and lands as one pull request into `reliability` in the fork.

## 4. Directory layout

```
reliability/
  README.md                       how to run, how to add a test, how the manifest works
  build.xml                       standalone Ant build (section 5)
  expected-failures.json          the manifest (section 6)
  .gitignore                      ignores build/
  src/                            runner and shared harness code (production-quality, no tests here)
    com/carolinarollergirls/scoreboard/reliability/
      runner/
        ReliabilityMain.java      entry point and CLI (section 7)
        ClassRunner.java          runs one test class in-process, writes partial results
        Manifest.java             model + loader + validation
        ManifestEntry.java
        TestResult.java           one test method's raw outcome
        Verdict.java              enum (section 8)
        Evaluator.java            pure function: results + manifest -> verdicts
        RateOutcome.java          parsing of rate-test failure messages
        Report.java               writes results.json, summary.txt, scorecard.json
      support/
        RateTest.java             helper for race tests (section 9)
  tests/                          reliability tests, mirroring product packages
    com/carolinarollergirls/scoreboard/reliability/canary/
      CanaryReliabilityTest.java
    com/carolinarollergirls/scoreboard/reliability/smoke/
      ProductLoadsReliabilityTest.java
    com/carolinarollergirls/scoreboard/core/game/
      UndoReliabilityTest.java      the F7 pilot (section 13)
  build/                          generated, git-ignored
```

Naming rules:

- Reliability test classes end in `ReliabilityTest` and live under `reliability/tests/`. They never match the existing `tests/**/*Tests.java` pattern.
- Later layer-1 tests that need package-private product access go in the product's own package under `reliability/tests/` (for example `reliability/tests/com/carolinarollergirls/scoreboard/json/FanOutReliabilityTest.java`).
- Java source follows the repo's `.clang-format`, as `src/` does.

## 5. Build: `reliability/build.xml`

A standalone Ant project, run from the repo root as `ant -f reliability/build.xml <target>`. The default target is `reliability`. It must not be imported by, or import, the root `build.xml`, except through the one `<ant>` call that compiles the product.

Properties (all paths relative to `reliability/`):

| Property | Value |
| --- | --- |
| `repo.dir` | `..` |
| `product.classes` | `${repo.dir}/build-ant` (where root `ant compile` puts classes) |
| `build.dir` | `build` |
| `report.dir` | `build/report` |
| `work.dir` | `build/work` |
| `manifest.file` | `expected-failures.json` |
| `class.timeout.seconds` | `600` |
| `rate.scale` | `1.0` |
| `only` | empty; when set, passed to the runner as `--only <value>` |

Targets:

| Target | Depends on | Does |
| --- | --- | --- |
| `product` | — | `<ant antfile="${repo.dir}/build.xml" dir="${repo.dir}" target="compile" inheritAll="false"/>`. Builds product classes into `build-ant/` exactly as upstream does. |
| `compile-runner` | `product` | Compiles `src/` to `build/runner-classes`. Classpath: product classes, `lib/deps/*.jar`, JUnit and Hamcrest. |
| `compile-tests` | `compile-runner` | Compiles `tests/` to `build/test-classes`. Same classpath plus runner classes. |
| `reliability` | `compile-tests` | Runs `ReliabilityMain` in a forked JVM with the arguments in section 7, then fails the build if its exit code is not 0. |
| `clean` | — | Deletes `build/`. Never touches product build output. |

All `javac` tasks use `source="1.8" target="1.8" includeantruntime="false" debug="true"` with `-Xlint:all -Xlint:-serial -Xlint:-options`, matching the root build.

## 6. The manifest: `reliability/expected-failures.json`

```json
{
  "schemaVersion": 1,
  "expectedFailures": [
    {
      "test": "com.carolinarollergirls.scoreboard.reliability.canary.CanaryReliabilityTest#plantedFailureIsReportedAsKnown",
      "finding": "F0",
      "kind": "single",
      "messagePattern": "canary: intentional failure",
      "notes": "Permanent canary proving the manifest mechanism works on every run."
    }
  ]
}
```

Fields:

| Field | Required | Rule |
| --- | --- | --- |
| `schemaVersion` | yes | Must be `1`. |
| `test` | yes | `fully.qualified.ClassName#methodName`. Unique across the manifest. |
| `finding` | yes | Matches `^F[0-9]+$`. `F0` is reserved for the canary. |
| `kind` | yes | `single` or `rate`. |
| `messagePattern` | yes for `single` | A Java regex, matched with `Matcher.find()` against the failure text (below). Must compile. |
| `baselineRate` | yes for `rate` | Number in (0, 1]: the failure rate observed when the entry was added. |
| `notes` | no | Free text for humans. |

The **failure text** is the `toString()` of the thrown `Throwable` (class name, colon, message). A timeout produces the text `TIMEOUT after <n>s`.

Validation runs before any test. Any of these is an infrastructure error (exit code 2) naming the entry: invalid JSON, wrong `schemaVersion`, a missing required field, an unknown field, a duplicate `test`, a malformed `finding`, a regex that doesn't compile, or `baselineRate` out of range.

## 7. Runner: `ReliabilityMain`

### Command line

```
java -cp <classpath> com.carolinarollergirls.scoreboard.reliability.runner.ReliabilityMain
  --manifest <path>              required
  --test-classes <dir>           required; scanned recursively for *ReliabilityTest.class
  --report-dir <dir>             required; created if missing, emptied first
  --work-dir <dir>               required; each class gets a fresh subdirectory
  --classpath <cp>               required; classpath for the child JVMs
  --class-timeout <seconds>      default 600
  --only <regex>                 optional; run only classes whose name matches (find)
  --in-process                   optional; run classes in this JVM instead of forking (for debugging one class)
```

### Behaviour

1. Load and validate the manifest (section 6).
2. Discover test classes: every `*ReliabilityTest.class` under `--test-classes`, excluding inner classes (names containing `$`), sorted by name, then filtered by `--only`.
3. Run each class in **its own child JVM** by default, because the product keeps global state (`ScoreBoardClock`, the static `coreLock`, `GameImpl`'s static thresholds, non-daemon timer threads). The child command is `java -cp <classpath> -Dreliability.workDir=<work-dir>/<ClassName> -Dreliability.rateScale=<scale> com.carolinarollergirls.scoreboard.reliability.runner.ClassRunner <ClassName> <partial-results-file>`, with the working directory set to that work subdirectory. Before starting the child, the runner creates that subdirectory and copies the repo's `config/` into it, excluding `config/autosave/`. The product resolves `config/penalties/*.json` relative to the current directory and fails to create a game without it. The child's stdout and stderr go to `<report-dir>/logs/<ClassName>.log`.
4. `ClassRunner` uses `JUnitCore` with a `RunListener` to record each method as a `TestResult`: `test`, `status` (`passed`, `failed`, `ignored`), `failureText`, `durationMs`. It writes them as JSON to the partial-results file, then calls `System.exit(0)` from a `finally` block, even if loading the class or writing results throws. The explicit exit is required because the product starts non-daemon threads (timers, the media watcher): a failed product start-up without it leaves the JVM hanging until the class timeout.
5. If the child JVM exceeds `--class-timeout`, the parent kills it. Every method of that class that has no recorded result gets `status=failed` and `failureText=TIMEOUT after <n>s`. If the child exits without writing results, every method gets `status=failed` and `failureText=CHILD_CRASH exit=<code>`. To know the method names in these cases, the parent lists them by reflection: public, non-static, `@Test`-annotated methods.
6. Evaluate verdicts (section 8), write the report (section 10), print the summary to stdout, and exit with code 0 (no blocking verdicts), 1 (at least one blocking verdict) or 2 (infrastructure error).

## 8. Verdicts

`Evaluator` is a pure function from (list of `TestResult`, `Manifest`) to a verdict per test, plus a verdict for each manifest entry that matched no result. It has no I/O, so finer-grained unit tests can be added later if `verify` turns out not to be enough.

| Verdict | Condition | Blocks the run |
| --- | --- | --- |
| `PASS` | Not in the manifest; passed. | No |
| `NEW_FAILURE` | Not in the manifest; failed. | Yes |
| `KNOWN` | `single` entry; failed; failure text matches `messagePattern`. | No |
| `WRONG_FAILURE` | `single` entry; failed; failure text does not match. Something else broke. | Yes |
| `UNEXPECTED_PASS` | In the manifest (either kind); passed. Message: "issue <finding> looks fixed — remove its manifest entry". | Yes |
| `KNOWN_RATE` | `rate` entry; failed with a parseable rate message (section 9). Reports observed rate against `baselineRate`. | No |
| `RATE_REGRESSION` | Same as `KNOWN_RATE`, but observed rate > 1.5 × `baselineRate`. | No (warning only) |
| `WRONG_FAILURE` (rate) | `rate` entry; failed, but the failure text is not a rate message. | Yes |
| `SKIPPED` | Ignored with `@Ignore`, listed or not. | No (warning only) |
| `STALE_ENTRY` | Manifest entry whose test was not run, and `--only` was not given. | Yes |

When `--only` is given, entries for classes that were filtered out are left out of evaluation instead of being reported as `STALE_ENTRY`.

## 9. Rate tests: `RateTest` helper

Race-dependent tests (F6, F11, F14 in later phases) repeat a scenario many times and report a failure rate. Phase 0 delivers the helper and its parsing; no real rate test is written yet.

```java
public final class RateTest {
    /** Runs body `iterations × reliability.rateScale` times (at least 1).
     *  Passes only if every iteration passes. Otherwise throws AssertionError with
     *  the message "RATE failures=<f> iterations=<n> rate=<f/n with 6 decimals>; first: <first failure toString>". */
    public static void run(int iterations, Iteration body);

    public interface Iteration { void run(int index) throws Exception; }
}
```

`RateOutcome.parse(failureText)` extracts `failures`, `iterations` and `rate` from that message format, and returns empty for any other text. `rate.scale` defaults to `1.0`; pass `-Drate.scale=<n>` to run more iterations.

## 10. Report output (`build/report/`)

| File | Contents |
| --- | --- |
| `results.json` | One object per test: `test`, `finding` (or null), `kind`, `status`, `verdict`, `failureText`, `durationMs`, and for rate tests `observedRate` and `baselineRate`. |
| `summary.txt` | A fixed-width table sorted by verdict severity, then by test name, followed by a line of counts per verdict and the overall result. The same text is printed to stdout. |
| `scorecard.json` | The run-level record for later trend tracking (fields below). |
| `logs/<ClassName>.log` | Child JVM output. |

`scorecard.json` fields:

```json
{
  "scorecardVersion": 1,
  "startedAt": "2026-10-14T09:00:00Z",
  "durationSeconds": 42.1,
  "gitCommit": "a739f0e…",
  "gitBranch": "reliability",
  "host": "desktop-name",
  "os": "Linux 6.8 amd64",
  "javaVersion": "21.0.12",
  "manifestSchemaVersion": 1,
  "wifiProfileVersion": null,
  "verdictCounts": { "PASS": 2, "KNOWN": 1, "NEW_FAILURE": 0, "...": 0 },
  "knownIssuesOpen": { "F0": 1 },
  "blocking": false
}
```

`gitCommit` and `gitBranch` come from running `git rev-parse HEAD` and `git rev-parse --abbrev-ref HEAD` in the repo root. If git is unavailable, both are null. `wifiProfileVersion` stays null until Phase 3. The time is UTC in ISO-8601.

## 11. Phase 0 tests

### Canary — `CanaryReliabilityTest`

- `plantedFailureIsReportedAsKnown`: always throws `AssertionError("canary: intentional failure")`. It is listed in the manifest under `F0`, so a healthy run reports it as `KNOWN`.
- `plantedPassIsReportedAsPass`: always passes and is not listed.

Both stay in the suite permanently. If the mechanism ever breaks, the canary turns the run red.

### Smoke — `ProductLoadsReliabilityTest`

- `scoreBoardStartsInWorkDir`: asserts `Version.load()` returns true, then constructs `new ScoreBoardImpl(false)`. It asserts that `getCurrentGame()` is not null and that `html/images/teamlogo` now exists under the child's working directory, not the repo. This proves that product classes, `release.properties` and `lib/deps` are on the child classpath, and that `BasePath` (the current directory) points at the per-class work dir, which Phase 1 relies on. The test must pass and is not listed in the manifest.

### Self-check fixtures (`reliability/selfcheck/fixtures/`)

`verify` (section 12) needs tests with known outcomes, so it has its own fixture classes. They are compiled to a separate directory and never discovered by a normal run:

- `BasicFixtureReliabilityTest`: `alwaysFails` (message `fixture: intentional failure`) and `alwaysPasses`.
- `PassFixtureReliabilityTest`: one passing test.
- `SleepFixtureReliabilityTest`: sleeps 60 s, for the timeout check.
- `RateFixtureReliabilityTest`: `RateTest.run(10, …)` failing exactly iteration 3.

## 12. Verification: `ant -f reliability/build.xml verify`

Every check below is programmatic, and run on demand by a person. `ant -f reliability/build.xml verify` runs them all and prints one PASS, FAIL or SKIP line per check. It writes `reliability/build/verify/verify-summary.txt` and `verify-results.json`, and fails the build if any check fails. `-Dverify.skip.roottests=true` skips the slow root `ant test` (M12 is then SKIP). `-Dverify.base.ref=<ref>` changes M13's comparison base, which defaults to `origin/dev`.

`verify` never edits tracked files. `SelfCheck` (in `reliability/selfcheck/src/`) runs `ReliabilityMain` in a child JVM for each scenario, against a temporary manifest under `reliability/build/verify/manifests/`. It uses either the real test classes or the self-check fixtures (section 11), and inspects the exit code, `results.json` and output.

| # | Check | How `verify` does it | Passes when |
| --- | --- | --- | --- |
| M1 | Normal run | Real tests, real manifest. | Exit 0; every manifest entry is `KNOWN`/`KNOWN_RATE`/`RATE_REGRESSION`; canary failure `KNOWN`, canary pass and smoke `PASS`; no blocking verdicts. |
| M2 | Report files | Inspects M1's report. | `results.json`, `summary.txt`, `scorecard.json`, one log per class; every scorecard field in section 10; `blocking: false`. |
| M3 | Work dir isolation | Lists the repo's `html/` before and after M1. | Unchanged; each class work dir has `config/` (without `autosave/`); smoke work dir has `html/images/teamlogo`. |
| M4 | New failure | Fixtures, empty manifest. | Exit 1; `alwaysFails` is `NEW_FAILURE`. |
| M5 | Unexpected pass | Manifest lists `alwaysPasses`. | Exit 1; `UNEXPECTED_PASS` with "looks fixed". |
| M6 | Wrong failure | `alwaysFails` listed with pattern `something else`. | Exit 1; `WRONG_FAILURE`. |
| M7 | Stale entry | Manifest lists `BasicFixture…#noSuchMethod`. | Exit 1; `STALE_ENTRY`. |
| M8 | `--only` filter | M7's manifest, `--only PassFixture`. | Exit 0; only `PassFixture` ran; no `STALE_ENTRY`. |
| M9 | Invalid manifests | Five manifests: invalid JSON, duplicate entry, finding `X1`, regex `(`, unknown field. | Each exits 2 before any test runs, with the right message; all but the JSON case name the entry. |
| M10 | Timeout | Sleep and pass fixtures, `--class-timeout 5`. | Exit 1; sleep test is `NEW_FAILURE` with `TIMEOUT after 5s`; pass fixture still ran; run took well under 60 s. |
| M11 | Rate verdicts | Rate fixture with baseline 0.1, then 0.05. | 0.1: `KNOWN_RATE`, exit 0. 0.05: `RATE_REGRESSION`, exit still 0. |
| M12 | Existing build untouched | Runs the root `test` target (which compiles first) before the other checks. | Root build succeeds. SKIP with `-Dverify.skip.roottests=true`. |
| M13 | No existing file changed | `git diff --name-only <base>...HEAD -- . ':(exclude)reliability'` | Empty. SKIP if the base ref is not fetched. |

## 13. Pilot test: F7

Before writing tests for any other finding, we take one real test through the whole workflow to validate the plan, the runner and the process. F7 is the pilot because it is deterministic, needs no harness beyond the in-JVM layer, and exercises a `single` manifest entry. If the pilot shows the workflow needs to change, we change this design and the plan before going further.

### The test: `UndoReliabilityTest`

Location: `reliability/tests/com/carolinarollergirls/scoreboard/core/game/UndoReliabilityTest.java`. It uses only public product API and follows the setup pattern of the existing `GameImplTests`.

- `@Before`: `ScoreBoardClock.getInstance().stop()`; `GameImpl.setQuickClockThreshold(0L)`; `Version.load()`; `sb = new ScoreBoardImpl(false)`; `sb.postAutosaveUpdate()`; `g = (GameImpl) sb.getCurrentGame().get(CurrentGame.GAME)`.
- `controlsStillWorkAfterUndoRestoreFails`:
    1. `g.startJam()`, then assert `g.isInJam()`. This also records the undo snapshot.
    2. Add a one-shot `ScoreBoardListener` to `g`. While armed, the first event whose property is `Game.IN_JAM` disarms it and throws `IllegalStateException("injected listener failure")`. This stands in for any bug in a listener that runs during an undo.
    3. Arm it and call `g.clockUndo(false)` inside a try/catch that swallows `RuntimeException`. The real server swallows it the same way, in the WebSocket and JSON-setter handlers.
    4. Call `g.startJam()`, then `assertTrue("F7: Start Jam had no effect after an undo whose restore failed", g.isInJam())`.

On today's `dev` the test fails at step 4: the exception leaves `restoreRunning` set, so `startJam()` silently does nothing (`GameImpl.restoreSnapshot` and `clockUndo`). This was confirmed with a scratch run of these exact steps against `dev` at `95286c1`: the undo threw the injected exception, and the second `startJam()` left `isInJam()` false.

Manifest entry:

```json
{
  "test": "com.carolinarollergirls.scoreboard.core.game.UndoReliabilityTest#controlsStillWorkAfterUndoRestoreFails",
  "finding": "F7",
  "kind": "single",
  "messagePattern": "F7: Start Jam had no effect after an undo whose restore failed",
  "notes": "Pilot test. Fails while an exception during undo leaves restoreRunning set."
}
```

### Pilot workflow

P1 and P3 are checked by `verify`; P2 is the same as M1. P4 is running `verify` on each OS; P5 is a written review.

| # | Step | Expected |
| --- | --- | --- |
| P1 | `verify` runs the pilot alone with a manifest that omits its entry. | Build fails; `NEW_FAILURE` with the F7 message. The class log shows the injected exception. This confirms it fails for the reason in the finding. |
| P2 | Add the manifest entry. Run the full suite. | Exit 0; `KNOWN` for F7 alongside the canary and smoke results. |
| P3 | `verify` copies `GameImpl.java` to a scratch directory, moves `restoreRunning = false` into a `finally` in `restoreSnapshot`, restarts the global clock if the restore throws in `clockUndo`, compiles it to an overlay placed first on the classpath, and runs the pilot. The source tree is never touched. If upstream changes `GameImpl` so the edit no longer applies, P3 is SKIP. | Build fails; `UNEXPECTED_PASS` naming F7. This proves a real fix would flip the test. |
| P4 | Run `ant -f reliability/build.xml verify` on Linux, macOS and Windows. | `VERIFY: OK` on each OS. |
| P5 | Write `docs/pilot-retro.md` in `crg-reliability-plan`: what worked, what was awkward, time spent, and proposed changes to this design or the plan. | Reviewed and accepted by Charles before any other finding gets a test. |

## 14. Acceptance criteria (the Phase 0 gate)

Phase 0 is done when `ant -f reliability/build.xml verify` reports `VERIFY: OK`, with no FAIL, on at least one Linux, one macOS and one Windows desktop, and the pilot retrospective (P5) is accepted. M12 must PASS, not SKIP, on at least one of them.

The implementing agent runs `verify` in its own environment before opening a pull request and pastes the summary into it. A person runs it on the other OSes.

## 15. Out of scope for Phase 0

- Tests for any finding other than the F7 pilot. They start only after the pilot retrospective is accepted.
- The protocol harness, device simulators, fault proxy and libfaketime (Phases 2–3).
- Scheduled or nightly runs, their scripts, and archiving of past reports (Phase 1).
- CI integration on CircleCI or GitHub Actions.
- Charts or dashboards of the scorecard history. Phase 0 only writes `scorecard.json` for each run.
- Moving to Java 11. Reconsider at the start of Phase 2, which needs `java.net.http.WebSocket`.
