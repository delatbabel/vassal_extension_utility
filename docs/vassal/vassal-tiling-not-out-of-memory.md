# VASSAL bug: "ran out of memory" when it did nothing of the kind

This note documents a bug in the VASSAL engine (not in this utility), carried by **thirty**
open automated reports and fixed by
[#15354](https://github.com/vassalengine/vassal/pull/15354). Sixteen of the thirty are not
memory failures at all, and each of those launched a doomed subprocess ten to eighteen times
before saying so.

## Symptom

```
java.util.concurrent.ExecutionException: java.io.IOException: Possibly ran out of memory.
Tiling failed with return value == 1
 at java.base/java.util.concurrent.FutureTask.report(FutureTask.java:122)
 at java.base/java.util.concurrent.FutureTask.get(FutureTask.java:191)
 at java.desktop/javax.swing.SwingWorker.get(SwingWorker.java:612)
 at VASSAL.launch.AbstractLaunchAction$LaunchTask.done(AbstractLaunchAction.java:517)
```

The module does not open. The message names a cause, which is what makes this family different
from the others here: the four quoted frames are an `ExecutionException` wrapper (see
[vassal-record-module-load-crash.md](vassal-record-module-load-crash.md)), but the *message*
reads like a diagnosis. It is a guess.

## What the thirty logs actually say

Every open ABR carrying `Tiling failed with return value` was fetched and classified:

| | what the log says | VASSAL's message |
|---:|---|---|
| **16** | `ClassNotFoundException: VASSAL.tools.image.tilecache.ZipFileImageTiler` — the tiler never ran | wrong |
| **13** | a genuine `OutOfMemoryError` inside the tiler | right |
| **1** | [#14193](https://github.com/vassalengine/vassal/issues/14193): a module path containing `æ` reaching the child as `?` | wrong, and a different bug |

A classifier that keys on the English `Caused by:` finds only ten of the sixteen. The other six
logs are Italian (`Causato da:`) or Chinese, and in the Chinese ones the prefix arrives as
mojibake. **Group ABR logs on the exception class name, never on the surrounding English.**

## Root cause

The tiler already says whether memory was the problem.
`vassal-app/src/main/java/VASSAL/tools/image/tilecache/ZipFileImageTiler.java`:

```java
System.exit(thrown instanceof OutOfMemoryError ? 2 : 1);
```

`TilingHandler.runSlicer` ignored it:

```java
if (maxheap < maxheap_limit) {
  // The tiler possibly ran out of memory; we can't reliably detect
  // this, so assume it did. Try again with 50% more max heap.
  maxheap *= 1.5;
```

The comment is the bug: we *can* reliably detect it, and the exit code is how. Assuming
otherwise costs more than a wrong message. From #14173's log:

```
Tiling failed with return value == 1   MaxHeap=264  MaxHeapLimit=49011  PhysMemory:49011
Tiling with 264 possibly ran out of memory. Retrying tiling with 50% more (396).
   … fourteen launches …
```

Fourteen JVMs, each dying in about 200 ms with the same `ClassNotFoundException`, `-Xmx` wound
from 264 MB to 49 GB, and then the user told to find more memory. The sixteen ran between ten
and eighteen attempts each.

The real cause was in the log the whole time, three lines above the first retry:

```
WARN  VASSAL.tools.logging.LoggedOutputStream - Error: Could not find or load main class
      VASSAL.tools.image.tilecache.ZipFileImageTiler
java.io.IOException: EOF before tiler started
```

It reached the log and nowhere else, because the child's stderr is pumped straight to
`System.err` and never looked at.

Two further signals were also being thrown away. `runSlicer` already distinguishes `EOF before
tiler started` from `EOF before tiler finished` — that is, whether the child ever printed
`TILER READY` — and the final message already had two forms, `"Probably"` for exit 2 and
`"Possibly"` otherwise. The distinction was half-made and reached neither the retry decision
nor the report.

## The fix

- **Retry only on exit code 2.** Any other value means the tiler did not blame memory; and if
  it never announced itself, the code is the JVM's own and the tiler did not run at all.
  Neither can be helped by more heap.
- **Report what happened.** The child's stderr is teed as it is pumped, keeping the first 4 KB,
  so its first line goes into the exception instead of a guess.

The thirteen genuine out-of-memory reports behave exactly as before.

## What it deliberately does not fix

Why `ZipFileImageTiler` cannot be loaded on those sixteen machines. The classpath handed to the
child is `System.getProperty("java.class.path")`, usually the relative `lib\Vengine.jar`, which
makes a working-directory problem the obvious candidate — the parent inherits its own
directory to the child, so a relative path should resolve the same for both, but
`ProcessLauncher` also has a `launch(File workDir, OutputStream, OutputStream, String...)`
overload which **silently drops `workDir`**:

```java
return launch(null, outP, errP, args);   // workDir discarded
```

Latent, as it happens: no caller passes a non-null one today.

The candidate fails anyway. [#14277](https://github.com/vassalengine/vassal/issues/14277)
passes an **absolute** classpath and fails identically, so a relative path is not the whole
story. The cause is environmental and varies; what the engine can do is stop multiplying it by
eighteen and stop misnaming it, which should also make the next round of reports diagnosable.

Resisting the urge to "fix" the classpath as well is the point. A change that cannot be shown
to fix anything, bundled into a PR that can, costs review attention and muddies what the
evidence supports.

## Testing a subprocess protocol

`runSlicer` is driven by what a child process prints, so the test gives it a child: `FakeTiler`
speaks just enough of the protocol to occupy each of the four interesting states — never
starts, fails after starting, exits 2, succeeds — and `TilingHandlerRetryTest` overrides
`makeSlicerCommandLine` to launch it and records the heap of every launch. A real subprocess,
so what is tested is what happens to a real one.

Red check: against unmodified `release-3.8` a tiler which never starts returns `300` — that is,
200 × 1.5, retry with more heap — where it must return `SLICER_GAVE_UP`. One assertion, and it
is the retry storm.

As with [the corrupt-prefs fix](vassal-corrupt-prefs-startup.md), the committed test references
new API (`OUT_OF_MEMORY`, `failureMessage()`) and so cannot compile against the old source; the
red check is run with a throwaway cut-down written against the old API only.

### Count your tests from a clean directory

Surefire reports accumulate in `target/surefire-reports`. Running the suite, reverting, running
a throwaway, and running again leaves reports from all of them, and totalling the `.txt` files
gives a number that is too high — which is how three pull-request descriptions here first
claimed 854, 860 and 868 tests when the real figure was 802 in each case. `rm -rf
vassal-app/target/surefire-reports` before the run that you intend to quote.

## What this means for the utility

Indirectly, quite a lot. The extension utility drives VASSAL's own engine in a subprocess for
[Refresh Counters](../../CLAUDE.md#refresh-counters), and has already been bitten by the same
shape of problem: a subprocess that failed to launch surfaced as *"No scenarios were
refreshed"* with nothing anywhere saying why, because `SwingWorker.done()` never asked for the
background exception. The response there was the same as this fix — capture everything the
child prints to a log file, and report the exit code and the last non-protocol lines when a run
produces no result.

The rule both arrive at: **a subprocess's own output is the only account of why it failed, so
capture it before you interpret its exit code — and if you must guess, do not put the guess in
the error message.**
