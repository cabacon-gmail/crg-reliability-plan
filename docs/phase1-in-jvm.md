# Phase 1 — In-JVM findings: design

Status: draft for review · Parent plan: [README.md](../README.md) · Builds on: [Phase 0](phase0-foundations.md), [pilot retrospective](pilot-retro.md) · Oct 13, 2026

Phase 1 adds reliability tests for four more findings: F9, F12, F13 and F14. All four run inside one JVM, through the runner, manifest and `verify` that Phase 0 built. Each failure below was reproduced against `dev` in a scratch run before this design was written. Phase 1 also includes a time-boxed libfaketime spike, which prepares F1 for Phase 3.

Like Phase 0, everything runs on demand on the test desktops: there are no scheduled runs and no CI. This document is written so an agent can implement it without further questions.

## 1. Decisions

| Topic | Decision |
| --- | --- |
| Scope | Tests for F9, F12, F13 and F14, the support code they need, and the libfaketime spike. No other findings. |
| Branch | `reliability-phase1` from `reliability` in `cabacon-gmail/scoreboard`, merged by pull request. |
| Coupling | Unchanged from Phase 0. Only files under `reliability/` change; M13 enforces it. |
| Verification | `ant -f reliability/build.xml verify` must report `VERIFY: OK` on Linux, macOS and Windows. M14 and M15 (from PR #3) cover every new manifest entry. No per-finding simulated fix (P3-style) is required. |
| Scheduling | None. The "nightly runs" item in the Phase 1 roadmap is dropped, since you clarified that "no automation" means no cron and no CI. |
| Platform-specific tests | A test that can only reproduce its finding on one OS uses a JUnit assumption elsewhere. The runner reports it as `SKIPPED` there, which doesn't fail the run. |

## 2. Evidence from scratch runs against `dev`

| Finding | Scratch result |
| --- | --- |
| F9 | A listener throwing on `IN_JAM` during Start Jam leaves `inJam=true`, the jam clock **not running**, and jam number 1. |
| F13 | The newest autosave has one value that won't parse. The loader rejects it and falls back to the previous file (marker = A, correct), but a setting that existed **only in the rejected file** is still present after the load. |
| F14 | With listeners registering and unregistering on another thread, `JSONStateManager.updateState` throws `ConcurrentModificationException` to its caller in 50, 56 and 68 of 200 iterations of 500 updates each, across three runs. |
| F12 | Not reproducible on Linux, where renaming a file someone holds open succeeds. On Windows, a file held open by `java.io.FileInputStream` can't be renamed. Antivirus, backup and sync tools hold files open the same way. Must be confirmed on your Windows desktop: step 6.2. |

The research also turned up two harness problems that Phase 1 must fix first (section 3):
- **Logging a stack trace crashes without `Main`.** The product's `Logger.printStackTrace` throws `NullPointerException` when no logger is installed, and only `Main` installs one. A test that reaches any error-logging path dies, and the product's non-daemon threads then keep its JVM alive until the class timeout.
- **Autosaves are empty without a JSON listener.** `ScoreBoardImpl` doesn't attach the `ScoreBoardJSONListener`; `Main` does. Without it, `JSONStateManager` holds no state and every autosave is empty.

## 3. Support code (in `reliability/src/`)

| Change | What it does |
| --- | --- |
| `ClassRunner` installs a fallback product logger | Before running the class, if `Logger.getInstance()` is null, install a logger that prints each line to stdout (and so to the class log). This prevents the NPE-then-hang described in section 2. |
| `support/CapturingLogger` | `extends com.carolinarollergirls.scoreboard.utils.Logger`. Its constructor calls `setLogger(this)`. It keeps every line in a synchronized list and also prints it. Tests use it to assert what was logged. |
| `support/ProductFixture` | `ProductFixture.start()` repeats `Main`'s set-up so tests don't each hand-roll it. It stops the global clock (`ScoreBoardClock.getInstance().stop()`), sets `GameImpl.setQuickClockThreshold(0L)`, calls `Version.load()`, creates `new ScoreBoardImpl(false)`, attaches `new ScoreBoardJSONListener(sb, sb.getJsm())`, and calls `sb.postAutosaveUpdate()`. It exposes `sb()` and `game()` (the current `GameImpl`). |
| `support/Platform` | `isWindows()` (from `os.name`), and `assumeWindows(String why)`, which calls `org.junit.Assume.assumeTrue(why, isWindows())`. |
| `SelfCheck` M14 | If an entry's test is `SKIPPED` when run unlisted, M14 records "skipped on this OS" for that entry instead of failing. M14 itself fails only if **every** OS run skips it, which a person checks by running `verify` on Windows. |

The F7 pilot test keeps its own set-up. It is not migrated to `ProductFixture`.

## 4. Tests

All four classes live under `reliability/tests/` and follow the README's conventions:
- assertion messages start with the finding ID;
- each has a specific `messagePattern`;
- each note starts with `Trigger:`.

Every product object is created inside the class's own work dir; autosave dirs are relative paths there.

### 4.1 F9: no transactional integrity

`reliability/tests/com/carolinarollergirls/scoreboard/core/game/TransactionReliabilityTest.java`

Test `startJamIsAllOrNothingWhenAListenerFails`:
1. `ProductFixture.start()`; `g = game()`; `jc = g.getClock(Clock.ID_JAM)`.
2. Add a one-shot `ScoreBoardListener` to `g`. On the first event whose property is `Game.IN_JAM` and whose value is `true`, it disarms itself and throws `IllegalStateException("injected listener failure")`.
3. Call `g.startJam()` inside a try/catch that swallows `RuntimeException` and prints it.
4. `assertEquals("F9: Start Jam was half-applied after a listener failed (inJam=" + g.isInJam() + ", jam clock running=" + jc.isRunning() + ")", g.isInJam(), jc.isRunning())`.

Expected on `dev`: fails, with `inJam=true` and jam clock running `false`.

Manifest entry:
- `messagePattern`: `F9: Start Jam was half-applied`
- `notes`: `Trigger: injected (a listener throws while Start Jam is in progress). Start Jam is left half-applied: in a jam with the jam clock stopped.`

### 4.2 F12: a locked autosave file silently stops autosaving (Windows)

`reliability/tests/com/carolinarollergirls/scoreboard/json/AutosaveLockReliabilityTest.java`

Test `latestChangeSurvivesWhenNewestAutosaveIsLocked`:
1. `Platform.assumeWindows("F12's file lock only blocks renames on Windows")`.
2. `ProductFixture.start()`. Create `dir = new File("autosave-f12")` and `saver = new AutoSaveJSONState(sb.getJsm(), dir, false)`.
3. `sb.getSettings().set("Reliability.Marker", "A")`; `saver.run()`.
4. Open `new java.io.FileInputStream(new File(dir, "scoreboard-0-secs-ago.json"))` and keep it open. Use `java.io`, not NIO: NIO opens files with delete-sharing, which wouldn't block the rename.
5. `sb.getSettings().set("Reliability.Marker", "B")`; `saver.run()`.
6. Close the stream.
7. Create a fresh `ScoreBoardImpl sb2`. Run `sb2.runInBatch(() -> AutoSaveJSONState.loadAutoSave(sb2, dir))`, then `sb2.postAutosaveUpdate()`.
8. `assertEquals("F12: the latest change was not autosaved while the previous autosave file was locked", "B", sb2.getSettings().get("Reliability.Marker"))`.

Expected on `dev`, on Windows only: fails with marker A. Renaming file 0 to file 1 fails, so renaming the temp file onto file 0 fails too, and the temp file is deleted. Every later save fails the same way while the lock lasts, and nothing reports it. On Linux and macOS the test is `SKIPPED`.

Manifest entry:
- `messagePattern`: `F12: the latest change was not autosaved`
- `notes`: `Trigger: injected (another process holds the newest autosave file open, as antivirus or sync tools do). Windows only; SKIPPED elsewhere.`

### 4.3 F13: a rejected autosave leaks into the restored state

`reliability/tests/com/carolinarollergirls/scoreboard/json/AutosaveLoadReliabilityTest.java`

Shared set-up for both tests:
- `new CapturingLogger()`, then `ProductFixture.start()`.
- Create `dir = new File("autosave-f13")` and `saver = new AutoSaveJSONState(sb.getJsm(), dir, false)`.
- Set `Reliability.Marker` = `A` and call `saver.run()`.
- Set `Reliability.Marker` = `B` and `Reliability.OnlyInNewest` = `present`, then call `saver.run()`.

Now `scoreboard-0-secs-ago.json` holds B and `scoreboard-10-secs-ago.json` holds A.

Test `rejectedNewestAutosaveLeavesNoTrace` (the finding):
1. In `scoreboard-0-secs-ago.json`, replace the first `"ScoreBoard.Game(<id>).Clock(Jam).MaximumTime" : <digits>` with the same key and the value `"not-a-number"`. Use a regex; fail the test with `setup:` if no such key exists.
2. Load into a fresh `ScoreBoardImpl sb2` as in F12, step 7.
3. `assertEquals("setup: loader did not fall back to the previous autosave", "A", marker)`.
4. `assertNull("F13: state from the rejected newest autosave leaked into the restored state", sb2.getSettings().get("Reliability.OnlyInNewest"))`.

Expected on `dev`: fails at step 4, because `OnlyInNewest` is `present`. Child entries are created in the loader's first pass, before the bad value throws, and the fallback never removes them.

Test `truncatedNewestAutosaveFallsBackCleanly` (a guard that passes today):
1. Cut `scoreboard-0-secs-ago.json` to half its length.
2. Load as above.
3. Assert marker `A` and `OnlyInNewest` null.
4. Assert that `CapturingLogger` recorded a line naming `scoreboard-0-secs-ago.json`.

Expected on `dev`: passes, because the JSON fails to parse before anything is applied. It is not listed in the manifest. A future change that makes truncation leak would turn it into `NEW_FAILURE`.

Manifest entry, for the finding only:
- `messagePattern`: `F13: state from the rejected newest autosave leaked`
- `notes`: `Trigger: injected (the newest autosave holds a value this version cannot parse, e.g. written by a newer version or damaged). Entries that exist only in the rejected file survive the fallback.`

### 4.4 F14: connect and disconnect race in the state fan-out

`reliability/tests/com/carolinarollergirls/scoreboard/json/FanOutReliabilityTest.java`

Test `updatesSurviveClientsConnectingAndDisconnecting`. It calls `RateTest.run(200, i -> { … })`. Each iteration does the following:
1. Create `jsm = new JSONStateManager(false)`.
2. Start a "churn" thread that loops until stopped. Each loop registers 20 **distinct** `JSONStateListener` instances whose `sendUpdates` does nothing, then unregisters them. They must be distinct objects: anonymous classes, not lambdas. Non-capturing lambdas are one shared instance, and the first prototype tripped over exactly that.
3. On the test thread, call `jsm.updateState("ScoreBoard.Reliability.Value", n)` 500 times. Catch any `Throwable` and remember it.
4. Stop the churn thread and join it, with a 10 s cap.
5. If anything was caught, throw `AssertionError("F14: updateState threw while clients connected/disconnected: " + t)`.

Expected on `dev`: fails in roughly 25–35% of iterations with `ConcurrentModificationException`. In the real server that exception escapes into the core thread, inside whatever clock tick or button press triggered the update.

Manifest entry:
- `kind`: `rate`
- `baselineRate`: the rate measured on Linux during implementation, rounded to two decimals.
- `notes`: `Trigger: natural (clients connecting and disconnecting while the scoreboard changes, as tablets do on venue Wi-Fi). Rate depends on the machine.`

200 iterations make an all-pass run, which would be `UNEXPECTED_PASS`, practically impossible even at a much lower rate. At 5% the chance is about 3 × 10⁻⁵. If a desktop's rate is above 1.5× the baseline, it reports `RATE_REGRESSION`, which is a warning only.

## 5. libfaketime spike (preparing F1, about one day)

The question: can libfaketime step the JVM's wall clock (`System.currentTimeMillis`) while leaving `System.nanoTime` alone, on the test desktops? Phase 3's F1 test depends on the answer.

1. **Linux:** run a 20-line Java program that prints `currentTimeMillis` and `nanoTime` every 500 ms. Use `LD_PRELOAD=libfaketime.so.1`, `FAKETIME_DONT_FAKE_MONOTONIC=1` and `FAKETIME_TIMESTAMP_FILE`, and edit the timestamp file mid-run to step +5 min and then −5 min.
2. **macOS:** the same with `DYLD_INSERT_LIBRARIES`. System Integrity Protection may block it for system Java; note the result.
3. **Windows:** libfaketime does not exist. Record the fallback, a Java agent that redirects `System.currentTimeMillis`, and estimate its size.
4. Write the result to `crg-reliability-plan/docs/spike-libfaketime.md`: what worked on each OS, the exact commands, and a recommendation for Phase 3.

The spike produces no test and changes nothing in the fork.

## 6. Verification and acceptance

### 6.1 What `verify` covers

Nothing new needs to be built beyond the M14 change in section 3:
- M1 runs the full suite, so F9, F13 and F14 must be `KNOWN` or `KNOWN_RATE`, F12 `SKIPPED` off Windows, and the F13 truncation guard `PASS`.
- M14 runs each entry unlisted and checks it fails for its own reason.
- M15 checks every note declares its trigger.
- M13 checks that nothing outside `reliability/` changed.

### 6.2 Phase 1 gate

1. `ant -f reliability/build.xml verify` reports `VERIFY: OK` on Linux, macOS and Windows, with M12 passing on at least one of them.
2. **Windows confirms F12:** `AutosaveLockReliabilityTest` is `KNOWN` there, not `SKIPPED` and not `UNEXPECTED_PASS`. If Windows does not block the rename, F12's test is wrong. Stop and revise section 4.2 before merging.
3. The F14 baseline in the manifest comes from a real Linux measurement, and the pull request records the rate observed on each OS.
4. `docs/spike-libfaketime.md` exists, with a recommendation.

The implementing agent runs `verify` on Linux before opening the pull request and pastes the summary into it. You run Windows and macOS.

## 7. Out of scope, and open questions

Out of scope:
- Findings F1–F6, F8, F10, F11 and F15–F17 (Phases 2–4).
- F12 durability across `kill -9`, which needs the process harness (Phase 2).
- Scheduled runs and CI.

Open questions for you:
- **Autosave history.** The autosaves cover only the last ~60 s (7 files × 10 s), and older backups are made only at start-up. Is longer recovery history a requirement, for example being able to restore the state from two minutes ago? If so, it becomes its own test. Today it's a product decision, not a bug.
- **How should a failed autosave be reported?** F12's test checks only that the latest change survives. If you also want "the operator is told", we need to agree what they see before a test can assert it: for example, a state key the SBO screen shows as a warning.
