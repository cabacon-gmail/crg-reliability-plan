# Phase 0 — Foundations: design

Status: ready to implement · Parent plan: [README.md](../README.md) · Oct 13, 2026

Phase 0 builds the machinery every later reliability test depends on: a self-contained `reliability/` tree in a fork of `rollerderby/scoreboard`, a JSON manifest of expected failures, a runner that judges each run against that manifest, and nightly scripts for the dedicated test desktops. It adds no tests for real findings; those start in Phase 1.

This document is written so an agent can implement it without further questions. Where a choice was open, it is decided here.

## 1. Decisions already made

| Topic | Decision |
| --- | --- |
| Code home | A fork of `rollerderby/scoreboard` under `cabacon-gmail`. All new files live under `reliability/` at the repo root. |
| Coupling to existing code | None. No existing file is modified: not `build.xml`, `src/`, `tests/`, `html/`, `.circleci/`, `.gitignore` or `package.json`. `ant test` and `ant zip` behave exactly as before. |
| Language and level | Java, compiled with `source`/`target` 1.8, JUnit 4.13.2 and Hamcrest 2.2 from `test-libs/`. |
| Dependencies | Only jars already in the repo: `lib/deps/*.jar` (jackson-jr is used for JSON) and `test-libs/*.jar`. No downloads, no new jars. |
| Manifest | JSON, parsed with jackson-jr (`com.fasterxml.jackson.jr.ob.JSON`). |
| Where it runs | Dedicated desktops running Linux, Windows and macOS: manual runs plus a nightly scheduled job. No CI job in Phase 0. |
| Known-issue mechanism | The manifest only. No annotations or JUnit rules on test classes. |

## 2. Prerequisites (owner: Charles)

1. Create the fork `cabacon-gmail/scoreboard` from `rollerderby/scoreboard` on GitHub. (The agent session cannot create forks.)
2. Give the Claude GitHub App access to the fork, as was done for `crg-reliability-plan`.
3. Each test desktop has a JDK (11–21 is fine; it compiles to 1.8), Apache Ant 1.10+, and git on its `PATH`.

## 3. Branching

- Upstream develops on `dev` (its default branch); `master` there is an older branch and is not used. The fork's `dev` mirrors upstream `dev` and is not committed to directly.
- A long-lived branch `reliability` carries the suite. It is created from upstream `dev` at the start of Phase 0 and periodically merged forward from upstream.
- Phase 0 work happens on `reliability-phase0`, branched from `reliability`, and lands as one pull request into `reliability` in the fork.
- Nightly runs check out `reliability`.

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
  selftest/                       unit tests of the runner itself
    com/carolinarollergirls/scoreboard/reliability/runner/
      ManifestTest.java
      EvaluatorTest.java
      RateOutcomeTest.java
      EndToEndTest.java
    resources/                    sample manifests used by the self-tests
  scripts/
    run-nightly.sh                Linux and macOS
    run-nightly.ps1               Windows
    com.crg.reliability.nightly.plist   launchd sample for macOS
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

Targets:

| Target | Depends on | Does |
| --- | --- | --- |
| `product` | — | `<ant antfile="${repo.dir}/build.xml" dir="${repo.dir}" target="compile" inheritAll="false"/>`. Builds product classes into `build-ant/` exactly as upstream does. |
| `compile-runner` | `product` | Compiles `src/` to `build/runner-classes`. Classpath: product classes, `lib/deps/*.jar`, JUnit and Hamcrest. |
| `compile-tests` | `compile-runner` | Compiles `tests/` to `build/test-classes`. Same classpath plus runner classes. |
| `compile-selftest` | `compile-runner` | Compiles `selftest/` to `build/selftest-classes`, and copies `selftest/resources/` there. |
| `selftest` | `compile-selftest`, `compile-tests` | Runs the self-tests with Ant's `<junit fork="true" haltonfailure="yes">`. A self-test failure stops the build. |
| `reliability` | `selftest` | Runs `ReliabilityMain` in a forked JVM with the arguments in section 7, then fails the build if its exit code is not 0. |
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
  --in-process                   optional; run classes in this JVM instead of forking (used by self-tests)
```

### Behaviour

1. Load and validate the manifest (section 6).
2. Discover test classes: every `*ReliabilityTest.class` under `--test-classes`, excluding inner classes (names containing `$`), sorted by name, then filtered by `--only`.
3. Run each class in **its own child JVM** by default, because the product keeps global state (`ScoreBoardClock`, the static `coreLock`, `GameImpl`'s static thresholds, non-daemon timer threads). The child command is `java -cp <classpath> -Dreliability.workDir=<work-dir>/<ClassName> -Dreliability.rateScale=<scale> com.carolinarollergirls.scoreboard.reliability.runner.ClassRunner <ClassName> <partial-results-file>`, with the working directory set to that work subdirectory. The child's stdout and stderr go to `<report-dir>/logs/<ClassName>.log`.
4. `ClassRunner` uses `JUnitCore` with a `RunListener` to record each method as a `TestResult`: `test`, `status` (`passed`, `failed`, `ignored`), `failureText`, `durationMs`. It writes them as JSON to the partial-results file, then calls `System.exit(0)`. The explicit exit is required because product timers are non-daemon threads.
5. If the child JVM exceeds `--class-timeout`, the parent kills it. Every method of that class that has no recorded result gets `status=failed` and `failureText=TIMEOUT after <n>s`. If the child exits without writing results, every method gets `status=failed` and `failureText=CHILD_CRASH exit=<code>`. To know the method names in these cases, the parent lists them by reflection: public, non-static, `@Test`-annotated methods.
6. Evaluate verdicts (section 8), write the report (section 10), print the summary to stdout, and exit with code 0 (no blocking verdicts), 1 (at least one blocking verdict) or 2 (infrastructure error).

## 8. Verdicts

`Evaluator` is a pure function from (list of `TestResult`, `Manifest`) to a verdict per test, plus a verdict for each manifest entry that matched no result. It has no I/O so it can be unit-tested exhaustively.

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

`RateOutcome.parse(failureText)` extracts `failures`, `iterations` and `rate` from that message format, and returns empty for any other text. `rate.scale` is set to `1.0` for manual runs; the nightly scripts pass `4.0`.

## 10. Report output (`build/report/`)

| File | Contents |
| --- | --- |
| `results.json` | One object per test: `test`, `finding` (or null), `kind`, `status`, `verdict`, `failureText`, `durationMs`, and for rate tests `observedRate` and `baselineRate`. |
| `summary.txt` | A fixed-width table sorted by verdict severity, then by test name, followed by a line of counts per verdict and the overall result. The same text is printed to stdout. |
| `scorecard.json` | The run-level record for trend tracking (fields below). |
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

### Self-tests (`selftest/`, run by the `selftest` target)

- `ManifestTest`: loads valid samples. Rejects each validation error in section 6 with a message naming the entry.
- `EvaluatorTest`: one test per row of the verdict table, plus `--only` filtering and the `STALE_ENTRY` rule.
- `RateOutcomeTest`: round-trips `RateTest`'s message format; rejects other text; checks `rate.scale` rounding (at least 1 iteration).
- `EndToEndTest`: runs `ReliabilityMain --in-process` against the canary class with three temp manifests and checks the exit code and verdicts:
  1. The real manifest: exit 0, verdicts `KNOWN` and `PASS`.
  2. The canary entry removed: exit 1, `NEW_FAILURE`.
  3. A manifest that also lists `plantedPassIsReportedAsPass`: exit 1, `UNEXPECTED_PASS`.

## 12. Nightly scripts

Both scripts take the same settings from environment variables:

| Variable | Default | Meaning |
| --- | --- | --- |
| `CRG_RELIABILITY_REPO` | the repo containing the script | Path to the fork checkout. |
| `CRG_RELIABILITY_BRANCH` | `reliability` | Branch to test. |
| `CRG_RELIABILITY_HISTORY` | `~/.crg-reliability/history` (`%USERPROFILE%\.crg-reliability\history` on Windows) | Where run reports are archived. |
| `CRG_RELIABILITY_KEEP` | `90` | Number of archived runs kept. |

Steps, identical in both scripts:

1. `git fetch origin`, then `git checkout <branch>` and `git reset --hard origin/<branch>`. The checkout is dedicated to testing, so a hard reset is intended. Refuse to run if the working tree path contains no `reliability/build.xml`.
2. `ant -f reliability/build.xml clean reliability -Drate.scale=4.0`, capturing combined output to `run.log`.
3. Copy `reliability/build/report/` and `run.log` to `<history>/<UTC timestamp>-<short commit>/`.
4. Delete the oldest archived runs beyond `CRG_RELIABILITY_KEEP`.
5. Exit with Ant's exit code.

`run-nightly.sh` is POSIX `sh` with `set -eu`, and must work on both Linux and macOS (no GNU-only flags; use `date -u +%Y%m%dT%H%M%SZ`). `run-nightly.ps1` targets Windows PowerShell 5.1 and uses `$LASTEXITCODE`.

`reliability/README.md` documents scheduling for each OS:

- Linux: a crontab line running at 02:30 local time.
- macOS: the `com.crg.reliability.nightly.plist` launchd sample (`StartCalendarInterval` 02:30), with the `launchctl load` command.
- Windows: a `schtasks /Create /SC DAILY /ST 02:30 /TN CRG-Reliability-Nightly /TR "powershell -NoProfile -ExecutionPolicy Bypass -File <path>\run-nightly.ps1"` command.

## 13. Acceptance criteria (the Phase 0 gate)

Run all of these from the root of a fresh clone of the fork on branch `reliability-phase0`. They must hold on at least one Linux, one macOS and one Windows desktop.

1. `ant -f reliability/build.xml` exits 0. The summary shows `KNOWN` for the planted failure, and `PASS` for the planted pass and the smoke test.
2. Deleting the canary entry from `expected-failures.json` makes the same command exit non-zero with `NEW_FAILURE`. Restore it afterwards.
3. Changing `plantedFailureIsReportedAsKnown` to pass makes the command exit non-zero with `UNEXPECTED_PASS`. Revert afterwards.
4. `ant test` and `ant compile` behave exactly as on upstream `dev`, and do not compile or run anything under `reliability/`.
5. `git diff --stat <upstream dev>...HEAD -- . ':(exclude)reliability'` prints nothing: no existing file was changed.
6. `scripts/run-nightly.sh` (on Linux and macOS) and `scripts/run-nightly.ps1` (on Windows) each produce an archived run with `scorecard.json` whose `blocking` is `false`.
7. Every self-test passes, and `selftest` runs as part of the default target.

## 14. Out of scope for Phase 0

- Any test for findings F1–F17 (Phase 1 onward).
- The protocol harness, device simulators, fault proxy and libfaketime (Phases 2–3).
- CI integration on CircleCI or GitHub Actions.
- Charts or dashboards of the scorecard history. Phase 0 only archives the JSON.
- Moving to Java 11. Reconsider at the start of Phase 2, which needs `java.net.http.WebSocket`.
