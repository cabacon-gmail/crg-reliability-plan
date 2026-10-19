# Spike: stepping the JVM's wall clock (preparing F1)

Status: done on Linux; macOS and Windows still need a run on the test desktops · Oct 18, 2026 · Phase 1 design §5

**Result: on Linux, libfaketime can step the JVM's wall clock while leaving its monotonic clock alone, and doing so reproduces F1 in the live product.** A forward step of 5 minutes drained the jam clock and took 5 minutes off the period clock. A backward step froze every game clock. For Phase 3, use libfaketime on Linux, and try it on macOS first. On Windows, step the dedicated desktop's real system clock instead.

## Linux: what was run

Ubuntu 24.04, OpenJDK 21.0.12, libfaketime **0.9.10** built from source:

```
git clone --depth 1 --branch v0.9.10 https://github.com/wolfcw/libfaketime.git
make -C libfaketime/src
```

(On a normal Debian/Ubuntu desktop, `sudo apt install libfaketime` works instead.)

### 1. Clock probe

A 20-line Java program ([`ClockProbe.java`](spike-libfaketime/ClockProbe.java)) printed `System.currentTimeMillis()` and `System.nanoTime()` every 500 ms. A background shell stepped the clock with:

```
echo "+0" > ts
LD_PRELOAD=/path/to/libfaketimeMT.so.1 \
FAKETIME_TIMESTAMP_FILE=$PWD/ts FAKETIME_NO_CACHE=1 FAKETIME_DONT_FAKE_MONOTONIC=1 \
  java -cp . ClockProbe
# mid-run, replacing the file atomically each time:
echo "+5m" > ts.tmp && mv ts.tmp ts
echo "-5m" > ts.tmp && mv ts.tmp ts
```

Measured wall-minus-monotonic: `+1 ms`, then `+300001 ms`, then `-299999 ms`. The monotonic clock was unaffected throughout, so the step is clean in both directions and to the millisecond.

Two pitfalls, both of which cost a run:
- **Use `libfaketimeMT.so.1`, not `libfaketime.so.1`.** With the single-threaded build, the JVM's threads race on re-reading the file. libfaketime then reports `failed to parse FAKETIME timestamp` and **exits the process**.
- **Replace the timestamp file atomically** (write a temp file, then `mv`). Rewriting it in place risks the same parse failure.

### 2. The product under a clock step (F1)

A scratch probe started the product, started a jam, and printed the jam and period clocks every 500 ms while the wall clock was stepped:

| Run | Jam clock | Period clock |
| --- | --- | --- |
| Control (no step) | 120 → 113 s over 7 s, as expected | 1800 → 1793 s |
| +5 min | Dropped to **0.0 s** at the step | Dropped by **~300 s** (1800 → 1498 s) |
| −5 min | **Frozen** at 120.0 s from the step on | **Frozen** at 1800.0 s |
| +5 min, then back to +0 | **Frozen** from the step on, and still frozen after the clock returned | **Frozen** |

The step landed at slightly different moments across runs, because start-up time varies, but each outcome is unambiguous.

**Two mechanisms are visible:**
- `ScoreBoardClock` computes game time as wall time minus an offset. Forward, it replays the missed ticks at once; backward, it waits until wall time catches up.
- `java.util.Timer`, which drives the ticks, schedules on wall time too, so a backward step suspends ticking. **Any F1 fix must therefore replace both** with a monotonic source, for example `System.nanoTime` and a `ScheduledExecutorService`.

**Side observation, not a finding:** the quick-press guard drops any clock-control press in the first second after start-up, because `lastButtonTime` starts at 0 and game time also starts near 0. It has no practical impact, but tests must wait one second after start-up, or set the threshold to 0, before pressing anything.

## macOS: not yet verified

libfaketime supports macOS through `DYLD_INSERT_LIBRARIES` and `DYLD_FORCE_FLAT_NAMESPACE=1`, but two things may get in the way:
- **System Integrity Protection** strips `DYLD_*` variables for protected binaries, including the `/usr/bin/java` launcher stub. Use the full path to a Homebrew or Temurin `java` binary instead.
- **libfaketime's macOS support is less complete than on Linux.** Whether it intercepts the call the JVM uses for `currentTimeMillis` needs to be tested.

To try on the Mac desktop:

```
brew install libfaketime openjdk@21
JAVA=$(brew --prefix openjdk@21)/bin/java
echo "+0" > ts
DYLD_INSERT_LIBRARIES=$(brew --prefix libfaketime)/lib/faketime/libfaketimeMT.1.dylib \
DYLD_FORCE_FLAT_NAMESPACE=1 FAKETIME_TIMESTAMP_FILE=$PWD/ts FAKETIME_NO_CACHE=1 \
FAKETIME_DONT_FAKE_MONOTONIC=1 "$JAVA" -cp . ClockProbe
```

Run that, then change `ts` from another terminal with `echo "+5m" > ts.tmp && mv ts.tmp ts`. If wall-minus-monotonic jumps by 300000 ms, macOS uses the Linux approach. If not, use the Windows approach below with `sudo date`.

## Windows: libfaketime does not exist

Options considered:

| Option | Verdict |
| --- | --- |
| Java agent that redirects `System.currentTimeMillis` | **Rejected.** It is a JIT intrinsic used inside the JDK itself (`java.util.Timer`), so redirecting it reliably means rewriting JDK classes. Too fragile for a test harness. |
| Change the product to read time through an injectable clock | **Rejected for testing.** It would modify code outside `reliability/`, and the fix for F1 will introduce such a seam anyway. |
| **Step the real system clock** on the dedicated desktop | **Recommended.** It is exactly what happens at a venue (NTP correction, someone fixing the clock for Time to Derby). It needs an elevated PowerShell (`Set-Date`), and the Windows Time service paused during the test (`net stop w32time`) so it doesn't step back early. |

Because a system-clock change affects the whole machine, that variant must be **opt-in**: the test runs only when `-Dreliability.allowClockChanges=true` is passed and the process is elevated, and is `SKIPPED` otherwise. It must always restore the clock in a `finally` block, then restart `w32time` and force a resync.

## Recommendation for Phase 3 (F1)

1. **Runner support for a per-class environment.** The F1 class needs `LD_PRELOAD` and the `FAKETIME_*` variables in its child JVM. Add an optional class annotation or manifest-side setting that `ReliabilityMain` applies when launching that class's JVM. This is a small runner change and could land in Phase 2.
2. **One F1 test class with two strategies:**
   - libfaketime on Linux (and on macOS if the probe above succeeds);
   - an opt-in real clock step on Windows (and on macOS as a fallback).
   Both use the oracles from the plan: game clocks within 1 s of a monotonic reference, no jam auto-ended by the step, and no freeze.
3. **Pull F1 forward?** F1 doesn't need the network harness, only per-class environment support (Linux) or opt-in elevation (Windows). It could move from Phase 3 to Phase 2 if you want the most game-visible finding covered sooner.
