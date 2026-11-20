# Reading the error log attached to a VASSAL bug report

An automated bug report (an issue labelled **ABR** on [vassalengine/vassal](https://github.com/vassalengine/vassal/issues)) quotes a stack trace in its body and links to the reporter's full error log. The quoted stack is frequently useless on its own — it is truncated, and the `Caused by` that says what actually went wrong is only in the attachment.

Fetching that attachment trips people up, because it is stored **brotli-compressed** behind a redirect. Get the fetch wrong and you see either nothing or binary noise, neither of which looks like "you asked for it the wrong way".

## The command

```bash
curl -sL --compressed "https://vassalengine.org/files/gh/<sha>" -o abr.log
```

Both flags are needed, and leaving either out fails differently:

| | result |
|---|---|
| `curl -sL --compressed` (front URL) | **the log** |
| `curl -s --compressed`, no `-L` | **0 bytes** — the front URL is a 301 with an empty body |
| `curl -sL`, no `--compressed` | **raw brotli** — binary noise starting `1b 62 10 60` |
| `curl -s --compressed` on `https://obj.vassalengine.org/tracker/gh/<sha>` | the log (the redirect target, if you would rather skip the hop) |

`--compressed` only asks for brotli if your curl was built with it; check with `curl -V | grep brotli`. Without it, fetch the bytes and decompress separately (Python's `brotli` module, or the `brotli` CLI).

A quick way to tell you have it right: the issue body states the log's size, e.g. "Error Log | 7924 bytes". That is the **decompressed** size, so it is a direct check that the decode worked. The compressed object is several times smaller — 1,386 bytes in that example.

## Why it is worth the trouble

Two recent reports had no cause at all in the issue body.

**[#15297](https://github.com/vassalengine/vassal/issues/15297)** quoted four frames ending at `ModuleManager.launch`. The log had the rest:

```
Caused by: java.lang.NullPointerException: Cannot invoke
"sun.awt.shell.Win32ShellFolder2.getIShellFolder()" because the return value of
"sun.awt.shell.Win32ShellFolderManager2.getNetwork()" is null
```

— an unreachable Windows shell folder, reached through a `JFileChooser` the preferences built during start-up. Nothing in the issue body pointed anywhere near it.

**[#15298](https://github.com/vassalengine/vassal/issues/15298)** quoted an `ExecutionException` wrapping an `ArrayIndexOutOfBoundsException` with no location. The log gave the frame that mattered, `FixedScaleOpTiledBitmapImpl$TileOp.eval`, which is the whole bug.

## What else the log gives you

Beyond the full stack, the surrounding lines usually settle questions the report does not answer:

- **OS, Java and VASSAL versions**, and whether the bundled JRE is in use (`Java home C:\Program Files\VASSAL-3.8.0\jre`).
- **Which module and version**, and the path it was loaded from — often a different module from the one named in the issue title, because the log covers the whole session.
- **`TilingHandler` lines** — `No images to tile` versus a tiling run, which matters for anything touching the image pipeline.
- **Earlier warnings**, which have usually crashed nothing — `Source: (No Image) Error: Image not
  found` is routine — but are occasionally the whole trigger. In
  [#15368](https://github.com/vassalengine/vassal/issues/15368) the four quoted frames name the
  fault exactly, and the two lines immediately *above* the trace are what say why the module would
  not open: an `Image not found` warning naming the die face, then `Builder - Error building
  VASSAL.build.module.AnimatedDiceButton`, which is the component build rethrowing. Read upwards
  from the trace as well as downwards. See
  [vassal-animated-dice-missing-image.md](vassal-animated-dice-missing-image.md).
- **Timestamps.** The gap between loading the module and the failure tells you whether it happened during load or minutes later in play. In #15298 it was 43 seconds after load, during a repaint, which ruled out the load path.
- **The parent's own log lines, set beside the child's.** When VASSAL launches a subprocess it
  logs the command line first, and the subprocess logs its own failure afterwards. Comparing the
  two character by character is what found
  [#14193](https://github.com/vassalengine/vassal/issues/14193): the parent logged
  `La Bataille d'Auerstædt`, the child reported `La Bataille d'Auerst?dt`, and the one changed
  character is the entire bug. No encoding fault is ever visible in the issue body, because the
  body quotes the *parent's* exception, which is about the child's exit code. See
  [vassal-ansi-arg-mangling.md](vassal-ansi-arg-mangling.md).

Treat everything in the log as the reporter's own data: it carries their file paths and Windows user name.

## Finding which reports are worth opening

There are hundreds of open ABRs, so the first question is usually which of them are the same bug.
The issue body is poor at answering that — see the five notes listed at the end of
[vassal-room-list-concurrency.md](vassal-room-list-concurrency.md) for the ways its four quoted
frames mislead — but it is good enough to *group* by, because the frames are at least consistent
for any one fault.

Pull every open ABR body and key each on `(exception type, topmost VASSAL frame)`. The issues
list endpoint returns bodies, so this is four calls, not hundreds:

```bash
for p in 1 2 3 4; do
  gh api "repos/vassalengine/vassal/issues?labels=ABR&state=open&per_page=100&page=$p" \
    --jq '.[] | {n: .number, d: .created_at[0:10], t: .title, b: .body}'
done > open_abrs.jsonl
```

Taking the **topmost `VASSAL.` frame** rather than the topmost frame of all is what makes the
grouping work: it skips the JDK and library plumbing that varies between reports of one bug.

Of 346 open ABRs in November 2026, **84 quote no VASSAL frame at all** — those need the log
before they can even be filed into a family. The rest group like this (families of three or more):

| reports | exception | topmost VASSAL frame |
|---|---|---|
| 79 | `ExecutionException` | `launch.AbstractLaunchAction$LaunchTask.done` |
| 28 | `RuntimeException` | `launch.Launcher.<init>` |
| 23 | `IllegalBuildException` | `build.Builder.create` |
| 16 | `IllegalStateException` | `counters.Decorator.setState` |
| 11 | `InvocationTargetException` | `launch.StartUp.initUIProperties` |
| 10 | `ExecutionException` | `tools.opcache.OpCache.getFuture` |
| 7 | `NullPointerException` | `command.Command.execute` |
| 6 | `NoSuchElementException` | `tools.SequenceEncoder$Decoder.nextToken` |
| 6 | `NullPointerException` | `build.widget.MapWidget.remove` |
| 5 | `IllegalArgumentException` | `build.module.Map.positionOf` |
| 3 each | | `launch.ModuleManager.launch`, `tools.opcache.OpCache$Request.done`, `counters.PieceVisitorDispatcher.accept`, `boardPicker.board.Region.move`, `counters.Decorator.getProperty`, `counters.Decorator.getOutermost`, `configure.ConfigureTree$AddAction.doIt` |

Two things to read off it.

**The big buckets are wrappers, not bugs.** The 79 on `LaunchTask.done` and the 28 on
`Launcher.<init>` are an `ExecutionException` and a `RuntimeException` wrapping whatever actually
failed; they are one *path*, not one fault, and the real cause is only in the log
([vassal-record-module-load-crash.md](vassal-record-module-load-crash.md) is one of the 79). The
same goes for the 23 on `Builder.create`.

**The useful ones are the middling buckets with a specific frame.** Six reports on
`MapWidget.remove` turned out to be one two-line bug
([vassal-mapwidget-delete-npe.md](vassal-mapwidget-delete-npe.md)); its frames were identical in
all six, and nobody had put them together. A frame that names a concrete method rather than a
dispatch or wrapper is where to look first.

**A wrapper bucket can still hide a family — split it on the message.** The 79 on
`LaunchTask.done` are one path, but 30 of them carry `Tiling failed with return value` and 16 of
*those* are a single fault ([vassal-tiling-not-out-of-memory.md](vassal-tiling-not-out-of-memory.md)).
Likewise all 11 on `StartUp.initUIProperties` are one fault
([vassal-corrupt-prefs-startup.md](vassal-corrupt-prefs-startup.md)) — the only bucket so far that
was unanimous. When a bucket is large, group it again on the exception message, then on the
`Caused by` class from the logs.

**A bucket can also be one message covering many faults — then only the logs separate them.**
The inverse of the case above: twenty-two reports share the message `failed to load class <X>`,
and they are at least eight unrelated faults, grouped by the one thing none of them is, because
the message blames the class named by an XML tag for anything that goes wrong anywhere beneath it
([vassal-builder-load-vs-build.md](vassal-builder-load-vs-build.md)). No amount of regrouping on
the body helps; every one of them has to be read from its attached log.

**Group on the exception class, never the English around it.** Six of those 16 tiling logs are
Italian (`Causato da:`) or Chinese, and in the Chinese ones the prefix is mojibake. A classifier
keying on `Caused by:` silently loses a third of the family.

Two caveats. The grouping sees only what the body quotes, so a bug whose reports quote disjoint
frame sets is scattered across several keys and may look like several singletons — the
apply-on-move recursion is five reports under three different keys. And a singleton is not
necessarily rare: #15107 is the only report of a race that any busy peer-to-peer session can hit.
