# Phase 0 pilot retrospective (P5)

Status: proposed, awaiting acceptance by Charles · Oct 13, 2026

**Verdict: the workflow works, and the remaining findings can get tests.** The F7 pilot went through every step:
- It failed on `dev` for the reason the code review predicted.
- Once listed in the manifest, it was reported as `KNOWN`.
- A simulated fix flipped it to `UNEXPECTED_PASS`.
- `ant -f reliability/build.xml verify` passed on Linux, macOS and Windows.

Six proposed changes are listed at the end. None of them blocks Phase 1.

## What happened

| Step | Result | Where |
| --- | --- | --- |
| P1: fails for the right reason when unlisted | Pass. `NEW_FAILURE` with the F7 message; the log shows the injected exception. | `verify`, all OSes |
| P2: `KNOWN` once listed | Pass. This is check M1. | `verify`, all OSes |
| P3: a simulated fix flips it | Pass. A `try/finally` in `GameImpl`, compiled into a scratch overlay, gives `UNEXPECTED_PASS`. | `verify`, all OSes |
| P4: same result on every OS | Pass. `VERIFY: OK` on Linux (Claude's sandbox), Windows and macOS. | [PR #2](https://github.com/cabacon-gmail/scoreboard/pull/2) |
| M12: the root build is untouched | Pass. The root `ant test` succeeded on Windows. | PR #1, PR #2 |

Elapsed time, on Oct 13, 2026:
- Implementing Phase 0, from approval to PR #1 with Linux results: about one hour.
- Turning the manual checklist into `verify` and adding the mutation checks: about 20 minutes.
- Running on Windows and macOS: your time, not measured here.

## What worked

- **Reading the code predicted the bug exactly.** F7's failure on `dev` matched the finding line for line. That raises confidence in the other findings from the same review.
- **The suite never touched the existing code.** There were no changes outside `reliability/`, `ant test` behaved the same, and the root build never sees the suite. This should also make an upstream PR easy to review.
- **Isolation held on all three OSes.** Each test class got its own JVM and work directory. The product wrote only into those directories, never the repo, and the global state that leaks between classes caused no cross-talk.
- **`verify` checks something real.** Two deliberate runner bugs each turned specific checks red: a non-blocking `UNEXPECTED_PASS` failed M5 and P3, and skipping the `config/` copy failed M1–M3, P1 and P3.

## What was awkward or surprising

1. **The product can't start a game without `config/penalties`.** A scratch run before implementation showed this. The first design gave each class an empty work dir, which would have failed every test. The runner now copies `config/` (minus `autosave/`) into each one.
2. **A failed product start-up leaves the JVM hanging.** The product starts non-daemon threads (timers, the media watcher), so a JVM that hits a start-up error never exits. `ClassRunner` now always calls `System.exit` from a `finally` block. Phase 2's server launcher will need the same guard.
3. **Classes from deleted tests kept running.** Manual check M11 caught `.class` files of deleted test sources being discovered and run. Both compile steps now start from an empty directory.
4. **An em dash printed as `?`** on a non-UTF-8 console. Runner output is now ASCII-only.
5. **The design named the wrong branch.** It said `master`, but upstream works on `dev`. This was caught when the fork was attached.
6. **"No automation" was misread.** You meant no cron and no CI. I took it as no automated checks and wrote a hand-run checklist, which cost one round trip. Turning the checklist into `verify` made it better: it's repeatable, and nobody edits files by hand.
7. **The agent sandbox had no normal Ant.** I ran Ant from Gradle's bundled jars, which lack `ant-junit4`, so the root `ant test` couldn't run there. M12 depends on a desktop run, which is acceptable because `verify` runs it.
8. **P3's simulated fix is tied to the current text of `GameImpl`.** When upstream changes those lines, P3 reports SKIP rather than FAIL. That's fine for a pilot, but simulated fixes shouldn't be required for every finding.
9. **The F7 test injects its trigger.** A listener throws on purpose during the undo. The test proves the fragility: there is no `try/finally`, so any exception in that window leaves the controls dead. It does not show how often a real exception happens there. Each test should say which kind it is.

## Proposed changes

| # | Change | Where | Why |
| --- | --- | --- | --- |
| 1 | Add a generic "fails for the right reason" check to `verify`. For every manifest entry, run its class with that entry removed, and expect `NEW_FAILURE` with a failure text that matches `messagePattern`. | `SelfCheck` | Turns P1 into a permanent guard for every finding, not just F7. |
| 2 | Each manifest entry's `notes` says whether the trigger is **natural** (a realistic sequence of crew actions) or **injected** (a fault planted by the test). | `expected-failures.json` convention, `reliability/README.md` | Keeps the scorecard honest about what each test proves (item 9). |
| 3 | A simulated fix (P3-style) is optional per finding. Use it only where a fix is a small, obvious patch. | Phase 1 design | Item 8: the text anchors break as upstream changes. |
| 4 | The first test of each new layer is a pilot: the protocol harness in Phase 2, faults in Phase 3, the browser in Phase 4. Each pilot runs the P1–P4 loop and gets a short retro before the rest of its layer. | Plan, roadmap | The pilot caught real design gaps (items 1–3) cheaply. |
| 5 | Move the desktop setup instructions (JDK, Ant, Git, OneDrive caveat) into `reliability/README.md`. | Fork | They currently exist only in chat. |
| 6 | Phase 2's server launcher always kills the server process tree, with a timeout, and never relies on the server exiting cleanly. | Phase 2 design | Item 2. |

## Next steps, if accepted

1. Merge PR #2 into `reliability`.
2. Apply changes 1, 2 and 5 in a small follow-up PR.
3. Draft the Phase 1 design: F9, F12, F13 and F14 as in-JVM tests. The F12 rename-lock case runs on the Windows desktop.
