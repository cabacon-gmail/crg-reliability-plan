# Recovery: per-jam checkpoints and a Restore screen

Status: draft for review · Parent plan: [README.md](../README.md) (open question: autosave semantics) · Workstream G in [strangler-fig-next-steps.md](strangler-fig-next-steps.md) · Oct 21, 2026

**Decided (Oct 21, 2026).** Build options 1 and 2 from the recovery discussion:
- per-game checkpoints at every jam boundary, kept for the whole game;
- a Restore screen that brings a game back to any checkpoint.

Options 3–6 are recorded below for later. This partly answers the open autosave-semantics question (section 6).

## 1. The problem, verified

Officials report that when something goes wrong, they end up starting a new game with the current points and time, and lose everything else. The code and a probe confirm this, and show where it comes from.

**A crash and restart is not the problem.**
- **What the probe did:** it played a jam with points and a penalty, then killed CRG (`kill -9`) 13 s into the next jam and restarted it after 5 s.
- **What came back exactly:** the score, jam number, in-jam state, the jam and period clocks (still running), and the penalty, still attached to its jam.
- **What was lost:** up to 10 s of changes (the autosave interval), the undo history, and the downtime (the clocks resume from their saved values).

**What hurts is that there is no way back past about one minute:**

| Copy | When it's written | How far back |
| --- | --- | --- |
| `config/autosave/scoreboard-N-secs-ago.json` | Every 10 s, the whole scoreboard | 60 s (7 rotating files) |
| `config/autosave/backup/<time>/` | Each time CRG starts | One set per restart; not shown in the UI |
| `html/game-data/json/<game>.json` | End of each jam, timeout and period, for one game | **Only the latest**: `_prev` is deleted after each write |
| Undo | In memory | One step; lost on restart |

When the game state itself is wrong (an operator mistake, or a bug such as F6's extra timeout or F9's half-applied Start Jam), the autosave faithfully keeps the wrong state, and a restart brings it back. Going back further means stopping CRG and renaming files by hand, inside a 60-second window.

So crews use the SBO's "start mid-game" new game. It carries over exactly:
- both teams' points;
- timeouts and official reviews taken;
- the period;
- the jam number;
- the period clock.

Per-jam scores, lineups, penalties, box trips, skater stats and the officials roster stay in the abandoned game, and the statsbook ends up split across two games.

## 2. Checkpoints at every jam boundary

`JSONStateSnapshotter` already writes the game's own JSON at the right moments: the end of each jam, timeout and period, and on export. Today it overwrites one file each time. Instead it will also keep a copy each time:

```
html/game-data/json/checkpoints/<game file name>/<yyyy-MM-dd_HHmmss>_P<period>J<jam>_<reason>.json
```

where `<reason>` is `jam-end`, `timeout-end`, `period-end` or `export`.

| Decision | Choice | Why |
| --- | --- | --- |
| Format | Exactly the format of today's game file: `{"state": …}` holding `ScoreBoard.Game(<id>)` and `ScoreBoard.Version` | A checkpoint can also be loaded with Settings, Data, Import JSON, and on another laptop. |
| Where | Under `html/game-data/`, so it is served at `/game-data/json/checkpoints/…` | Any device can download a checkpoint. That is useful when the laptop dies (option 5, light). |
| Also at | The start of each period | Gives "before the period started" as a restore point. |
| Writing | Temp file, then rename, as the game file does today. Failures go to the log and to a new `ScoreBoard.Game(<id>).CheckpointError` value the SBO can show. | Visibility (open question 4). |
| Retention | Every checkpoint of a game is kept. At start-up, checkpoint folders whose newest file is older than 90 days are deleted. | A game is about 60 jam boundaries, roughly 30–60 MB uncompressed. A season of games fits easily; old games don't pile up for ever. |
| Undo | Unchanged | Out of scope. |

## 3. The Restore screen

A **Recovery** tab in Settings lists, for each game:
- its checkpoints (newest first);
- the last minute's autosaves;
- the start-up backups.

Each row shows the time, the reason, the period and jam, and the score. A row has two actions:
- **Restore as a copy:**
  - The server loads the checkpoint's game under a **new game ID**, named `<game name> (restored P2 J12)`.
  - The new game becomes the current game, the same way the SBO switches games.
  - The original game is left untouched, so a restore can never make things worse; the crew can delete the original later.
  - Restoring from an autosave or backup takes just that game's keys from the file.
- **Download:** saves the checkpoint as a file.

Server side, in a new `RecoveryServlet`:
- `GET /Recovery/list` returns the rows as JSON.
- `POST /Recovery/restore` with the source and the file restores a copy.
- Both need write access, like every other change.

**After a restore,**
- clocks come back as saved, so the screen reminds the crew to check the period clock;
- a restore during a jam asks for confirmation first.

**Statsbook:** the restored copy holds the full history up to its checkpoint, so its export is complete.

## 4. Tests, written first

Each is a reliability test. Those that fail today get a manifest entry under a new finding, **F18 "no way back past the last minute"**, and flip as the work lands.

| Test | Today | Passes when |
| --- | --- | --- |
| `RestartFidelity`: the probe in section 1, as a test | Passes (guard) | It keeps passing. |
| `JamCheckpoints`: after the reference bout, there is a checkpoint for every jam end, the timeout and each period start and end, each loadable | Fails (F18) | Section 2 is built. |
| `RewindToJam`: play four jams with points and penalties, restore the checkpoint at the end of jam 2 through `/Recovery/restore` | Fails (F18) | The current game is a new copy with jam 2's end state: scores, penalties and lineups from jams 1–2 only. The original game is unchanged. The copy's statsbook export has jams 1–2. |
| `RestoreFromAutosave` and `RestoreFromBackup` | Fails (F18) | The same, from the other sources. |
| Product unit tests for the ID rewriting and the list | — | With the change. |

## 5. Not now: options for later

| # | Option | What it covers | Depends on |
| --- | --- | --- | --- |
| 3 | **Continue as a copy** instead of the SBO's start-mid-game: copy the broken game with all its history (`GameImpl` already has a Copy command), then let the crew set the clocks and score on the copy | Keeps the statsbook whole when no checkpoint is right | — |
| 4 | **Journal**: snapshots plus every accepted command, so a restore can reach any moment (e.g. "just before the second Timeout press") | Exact point-in-time recovery, and diagnosis | In-core recorder (C2); replay is only fully deterministic with the clock seam (E) |
| 5 | **Warm standby**: a second laptop that keeps a recent copy of the state (polling `/SaveJSON`, or the recorder pointed at it) and can take over | The scoreboard laptop dying | Real reports of how often that happens |
| 6 | **Consistency checks in the server**: the harness's game invariants (score equals the sum of jam scores, one running timeout, jam numbers in sequence), with a warning on the SBO when one breaks | Spotting damage while a good checkpoint is still recent | — |

## 6. What this settles in the autosave question

| Question | Answer, for now |
| --- | --- |
| 1. Loss window | Unchanged: up to 10 s on a crash (verified). Power loss is still untested. |
| 2. History | Any jam, timeout or period boundary of the game, for the whole game, restored from the Recovery tab by whoever has write access. |
| 3. Consistency | A checkpoint is written between operator actions, at a boundary. A restore is all-or-nothing, and never replaces the original game. |
| 4. Visibility | Checkpoint failures are shown on the SBO, not just logged. Autosave failures are still open. |
| 5. Source of truth | The live game. Checkpoints and autosaves are copies to restore from; the statsbook is exported from whichever game is current. |
| 6. Scope | Autosave stays whole-scoreboard; checkpoints are per game. |
| 7. Versions | Unchanged: checkpoints go through the same version upgrade (`updateToCurrentVersion`) as imports. |
| 8. Architecture | Snapshots at boundaries now; the journal (option 4) is the later step, and fits the in-core recorder. |

F12 and F13 stay provisional, with their current expectations.

## 7. For review

1. **Where the Recovery screen lives:** a new Settings tab (proposed), or on the SBO next to "start new game"?
2. **Retention:** 90 days, or never delete automatically?
3. **Naming** of a restored game, and whether the original should be marked as abandoned.
4. **The new finding number F18** for "no way back", so the tests follow the usual manifest flow.
