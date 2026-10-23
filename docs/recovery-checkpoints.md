# Recovery: per-jam checkpoints and a Restore screen

Status: built, on [`recovery-checkpoints`](https://github.com/cabacon-gmail/scoreboard/tree/recovery-checkpoints) (product) and [`recovery`](https://github.com/cabacon-gmail/scoreboard/tree/recovery) (tests) · Parent plan: [README.md](../README.md) (open question: autosave semantics) · Workstream G in [strangler-fig-next-steps.md](strangler-fig-next-steps.md) · Oct 21, 2026

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

## 3. The Recovery tab

### 3.1 What a restore means for the statsbook

The statsbook is **not live**. It is written only when someone presses **Update** on the IGRF tab: `StatsbookExporter` fills a blank workbook from that game's whole state into `html/game-data/xlsx/<Filename>.xlsx`. A game's `Filename` (`STATS-<date>_<team1>_vs_<team2>`) is fixed once the game has started.

So a game restored with its full history produces a complete statsbook whenever Update is pressed. The SBO's start-mid-game produces one with only the score, timeouts and period.

**Restore (decided Oct 21):**
1. Load the checkpoint into a **new game**, under a new ID, with the **original's `Filename` and name**. The final statsbook and game file keep the names everyone expects.
2. **Abandon the old game**: rename it to `<name> (abandoned <time>)` and give it a new `Filename` with the same suffix, so it can never overwrite the restored game's files. It stays in the games list for reference and can be deleted later.
3. Make the new game the current game.

The restored game starts with the clocks as they were at the checkpoint.

### 3.2 What the tab shows

A **Recovery** tab in Settings. Games come first, so people find theirs by what they know about it; restore points come second.

```
Recovery
------------------------------------------------------------------------------------------
 Games                                        newest first
 > 2026-10-21 18:30   Home Testers vs Away Testers     P2 J14   87 - 95   18:42 left   CURRENT
   2026-10-21 16:00   Black vs White                    Final              112 - 140
   2026-10-14 19:00   Reliability League vs Harness     Final              201 - 167
------------------------------------------------------------------------------------------
 Restore points for Home Testers vs Away Testers
   Saved          Where in the game          Score      Period clock   From
   18:58:02       P2 after jam 14 (lineup)   87 - 95    18:42 left     autosave, 10 s ago
   18:57:40       P2 end of jam 14           87 - 95    18:44 left     checkpoint
   18:55:12       P2 end of jam 13           80 - 91    20:31 left     checkpoint
   18:54:01       P2 end of timeout (Home)   76 - 91    21:03 left     checkpoint
   ...
   18:31:05       P1 start                    0 - 0     30:00 left     checkpoint
   18:20:44       before the game                        30:00 left     backup from a restart
                                                       [Download]   [Restore...]
```

**Each game row:**
- the date and start time, from the game's IGRF;
- both team names;
- the period and current jam, or "Final";
- the score;
- the period clock left;
- whether it is the current game.

**Each restore point:**
- when it was saved;
- where in the game it is (period, jam, and the moment: jam end, timeout end, period start or end);
- the score;
- the period clock left;
- where it comes from (checkpoint, autosave, or backup from a restart).

Autosaves and backups appear as rows of the game they contain. Autosaves are newest of all, but only go back a minute.

**Restore...** opens a confirmation that says what will happen in the crew's words:

> Restore "Home Testers vs Away Testers" to **P2 end of jam 13** (80 – 91, 20:31 left)?
> - A new copy of the game, from that point, becomes the current game. It keeps the same name and statsbook file.
> - The game as it is now is kept, renamed "… (abandoned 18:59)".
> - Clocks will be as they were then. **Check the period clock** before the next jam.

During a jam, the dialog first says a jam is running and asks for confirmation.

**Server side**, in a new `RecoveryServlet`:
- `GET /Recovery/list` returns the games and their restore points as JSON, with the summary values above read from each file.
- `POST /Recovery/restore` does the three steps in 3.1.
- Both need write access, like every other change.

## 4. Tests, written first

Each is a reliability test. Those that fail today get a manifest entry under a new finding, **F19 "no way back past the last minute"**, and flip as the work lands.

| Test | Today | Passes when |
| --- | --- | --- |
| `RestartFidelity`: the probe in section 1, as a test | Passes (guard) | It keeps passing. |
| `JamCheckpoints`: after the reference bout, there is a checkpoint for every jam end, the timeout and each period start and end, each loadable | Fails (F19) | Section 2 is built. |
| `RewindToJam`: play four jams with points and penalties, restore the checkpoint at the end of jam 2 through `/Recovery/restore` | Fails (F19) | The current game is a new copy with jam 2's end state: scores, penalties and lineups from jams 1–2 only. It has the original's name and `Filename`. The original is kept, unchanged except for its "(abandoned …)" name and new `Filename`. The copy's statsbook export has jams 1–2. |
| `RestoreFromAutosave` and `RestoreFromBackup` | Fails (F19) | The same, from the other sources. |
| `RecoveryList` | Fails (F19) | Each game and restore point shows the date, teams, period, jam, score and period clock left, matching the game's state at that point. |
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

## 7. As built (Oct 22, 2026)

Changes from the design, all found while building:
- **No checkpoint at period start.** In the code, a period starts in the same batch of changes as its first jam, so a "period start" checkpoint would already contain the jam. The last jam-end checkpoint of the previous period is the same point, and the list labels it "end of period".
- **Checkpoints also at official score and at statsbook Update.** These are the moments the game file was already written. A restore adds a "restored here" point, and the abandoned game gets a "set aside here" point.
- **A timeout end is a restore point only when a timeout actually ended.** `_endTimeout` also runs when a jam starts after a timeout; that still writes the game file, but adds no restore point.
- **Checkpoints live in `html/game-data/checkpoints/`**, outside the `json/` folder the media watcher lists, so they don't appear as game files.
- **The short list** shows the newest autosave, if it is newer than every restore point, and then the newest restore points. Autosaves come every 10 s and would otherwise fill the list.
- **Element IDs.** A restored copy gets new IDs for the game and every element in it, so it shares nothing with the abandoned game. References outside the game, such as rulesets, are kept.
- **Only from the scoreboard's computer.** A restore must come from a loopback address with no `X-Forwarded-For` or `X-CRG-Forwarded-For` header. The recorder now adds `X-CRG-Forwarded-For` to everything it forwards; otherwise every device behind it would look local.
- **Still to do:** show `CheckpointError` on the SBO, not only on the Recovery page.

Tests:
- The F19 tests fail on `dev` with "F19: the Recovery list is not available (404)", and pass with the change:
  - every jam and timeout boundary is a restore point;
  - the list shows what people recognise a game by;
  - rewind to the end of jam 2;
  - restore from an autosave;
  - restore from a start-up backup;
  - refused with a proxy header;
  - refused through the recorder.
- The restart guard passes on both.
- New product unit tests: `CheckpointsTests` (summary, game IDs, copying a game's IDs) and two in `GameImplTests` (abandon, explicit file name).

## 8. Review (Oct 21, 2026)

1. **Where:** a new Settings tab. Its layout is in 3.2, for discussion.
2. **Retention:** delete after 90 days.
3. **Restore:** a new game with the original's name and statsbook file; the old game is kept as abandoned. The statsbook is only written on demand, so this keeps it whole (3.1).
4. **Finding number:** F19.
