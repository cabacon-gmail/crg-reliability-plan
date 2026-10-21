# Phase 2 pilot retrospective

Status: draft · Pilot: F6 in [PR #5](https://github.com/cabacon-gmail/scoreboard/pull/5) · Design: [phase2-protocol-harness.md](phase2-protocol-harness.md) · Oct 20, 2026

**Verdict: the harness works, and the rest of Phase 2 can go ahead.** The F6 pilot exercised the whole path:
- the launcher started the packaged jar;
- two devices connected, each through its own proxy;
- a DSL-built trace was replayed in real time;
- the observer supplied ground truth;
- the oracle failed for the predicted reason.

What went wrong was the prediction itself. The review's F6 scenario turned out to be guarded on `dev`, so the pilot had to switch scenarios part-way through (item 1).

## What happened

| Step | Result | Where |
| --- | --- | --- |
| P1: fails for the right reason when unlisted | Pass. `NEW_FAILURE`: "F6: a Timeout press from another device 1.2 s later started a second timeout (2 timeouts, running one owned by '', Team 1 in timeout=false)". | Linux |
| P2: `KNOWN` once listed | Pass. This is check M1. | `verify`: Linux, Windows |
| H1–H4: harness checks | Pass, including the mutation test of H4 (an `expect` that always passes turns H4 red). | `verify`: Linux, Windows |
| P4: same result on every OS | Linux: 20 passed, 1 skipped (F12 is Windows-only). Windows: 21 passed. **macOS: pending.** | PR #5 |

## What worked

- **One replayer for everything.** The pilot is about 15 lines of DSL. The same trace is saved as a `.crgtrace.jsonl` that replays on its own, which is exactly the path a captured real-game trace will take.
- **Testing through the wire, not the internals.** The test knows only `/WS/` paths. It will judge a rewritten core without changes.
- **Killing the process tree from the start.** Pilot retro change 6 paid off: no orphaned servers on Linux or Windows, including after `kill -9` and a restart (H1).
- **The observer as ground truth.** The bug shows up in the observer's state, not just in one device's mirror. That separates "the server is wrong" from "a device missed an update".

## What was awkward or surprising

1. **The review mispredicted the F6 scenario.** It said two Stop presses close together would end a jam and then start a lineup. A probe on `dev` showed both cases are guarded: a second Stop during lineup does nothing, and the same button within 1 s is ignored. The real exposures are the edges of the 1 s quick-press guard: the same button more than 1 s apart, or different buttons less than 1 s apart. The design doc is corrected. The review was right for Phase 0's F7, but it is not infallible.
2. **The quick-press guard also catches tests.** The first button press within 1 s of start-up is treated as a correction of nothing, and dropped. The DSL scenarios now leave at least 1.5 s before the first press. Real crews never hit this, but every trace author will.
3. **Real-time replay sets a floor on test length.** The pilot takes about 7 s of wall time because the server's clocks are real. Fine for the scenarios here, but the 5-minute reference bout will dominate a full run. That is acceptable for on-demand runs on dedicated desktops.
4. **The Java 11 move was invisible.** The desktops already had JDK 21, and the product build stayed on Java 8 (M12). Nobody noticed, which is the right outcome.

## Added while building the rest of Phase 2

These came up after the pilot, while applying change 1 below to F10, F11 and F17:

5. **A probe caught a false pass.** The first F17 run passed. The server had answered the upload with a 500, because it only knows a device once that device's page has opened the WebSocket, so a bare HTTP request never reached the vulnerable code. The test now makes an ordinary upload first and treats its failure as a setup error. **Every test that could pass by doing nothing needs a control step like this.**
6. **The F11 `Register` race did not reproduce.** 1,000 displays connecting and registering at once, during a running jam, all got the team names. The review's theory depends on a timing window that localhost, let alone Wi-Fi, doesn't open. The test stays as a guard. The other half of F11 (a lost delta) needs Phase 3's fault proxy.
7. **Stressing F11 found F14 over the wire.** When ten devices connect at the same moment, the fan-out race from Phase 1 refuses some of their WebSocket handshakes (72% of rounds). That is what a crew sees when venue Wi-Fi comes back and every screen reconnects together. It now has its own protocol-level test.

8. **Three predictions did not hold at realistic load.** F12 (`kill -9`), F15 (a stalled overlay) and, on this machine, F2 (statsbook export) all stayed within their limits. Each test stays as a guard. Each also names what would make it bite: power loss rather than a process kill; a client registering far more than a real screen; a slower laptop. That is worth checking against real crew reports before spending fix effort on them.
9. **Bout tests changed the cost of `verify`.** The reference bout, F12 and F15 add about 20 minutes. Change 3 below is now built: `@Slow` classes, `-Dquick=true` for runs, and `-Dverify.quick=true` for verify.

## Proposed changes

| # | Change | Where | Why |
| --- | --- | --- | --- |
| 1 | **Probe before writing a finding's test.** Run a short scratch probe against `dev` to confirm the exact trigger, then write the test. Record the probe's result in the test's Javadoc. | Plan, test conventions | Item 1. It's cheap (minutes) and stops a test from being built around a guarded case. |
| 2 | The DSL warns when a button press comes within 1.5 s of the trace start or of the previous different-button press on another device, unless the trace says the timing is intended (`.quick()`). | `Bout` | Item 2: makes the guard visible to trace authors. |
| 3 | Tag a test `@Slow` (run-time category) if it runs more than 60 s, and let the runner skip slow tests with `-Dquick=true`. `verify` always runs everything. | Runner | Item 3: keeps the edit-run loop fast while the reference bout exists. |

## Next steps, if accepted

1. Merge PR #5 into `reliability` once macOS reports `VERIFY: OK`.
2. Build the rest of Phase 2 on `reliability-phase2b`, in the design's order, applying changes 1–3.
