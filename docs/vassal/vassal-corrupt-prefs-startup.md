# VASSAL bug: one bad character in the preferences file and VASSAL will not start

This note documents a bug in the VASSAL engine (not in this utility), reported eleven times
between 2024 and 2026 and fixed upstream by
[78a7a04a8](https://github.com/vassalengine/vassal/commit/78a7a04a8d75d56484caafd9b35a16f9d1be031c).
It is the clearest case in this folder of a defect that was *already understood* — the handling
exists, with a comment explaining why, one class away from where it was needed.

It is also this folder's one case of **being beaten to the fix**, which is worth as much as the
diagnosis; see [Overtaken](#overtaken-while-the-pr-was-open). What remains of the work done here
is [#15353](https://github.com/vassalengine/vassal/pull/15353), now scoped to the saving half,
which the upstream commit did not touch.

## Symptom

VASSAL does not start. The automated report quotes four frames, three of them `java.desktop`
reflection:

```
java.lang.reflect.InvocationTargetException: null
 at java.desktop/java.awt.EventQueue.invokeAndWait(EventQueue.java:1371)
 at java.desktop/java.awt.EventQueue.invokeAndWait(EventQueue.java:1346)
 at java.desktop/javax.swing.SwingUtilities.invokeAndWait(SwingUtilities.java:1480)
 at VASSAL.launch.StartUp.initUIProperties(StartUp.java:107)
```

`InvocationTargetException: null` is what `invokeAndWait` throws when the runnable threw; the
message is null because the wrapper has none. Nothing in those four frames says what failed.

## The family

Every open report under this frame — all eleven — is the same fault. This is the first family
in these notes that turned out to be *unanimous*.

| issue | version | | issue | version |
|---|---|---|---|---|
| [#13504](https://github.com/vassalengine/vassal/issues/13504) | 3.7.12 | | [#14019](https://github.com/vassalengine/vassal/issues/14019) | 3.7.16 |
| [#13767](https://github.com/vassalengine/vassal/issues/13767) | 3.7.15 | | [#14020](https://github.com/vassalengine/vassal/issues/14020) | 3.7.16 |
| [#13768](https://github.com/vassalengine/vassal/issues/13768) | 3.7.15 | | [#14458](https://github.com/vassalengine/vassal/issues/14458) | 3.7.19 |
| [#13817](https://github.com/vassalengine/vassal/issues/13817) | 3.7.15 | | [#14755](https://github.com/vassalengine/vassal/issues/14755) | 3.7.22 |
| [#13818](https://github.com/vassalengine/vassal/issues/13818) | 3.7.15 | | [#13819](https://github.com/vassalengine/vassal/issues/13819) | 3.7.15 |
| [#13820](https://github.com/vassalengine/vassal/issues/13820) | 3.7.15 | | | |

Four of them (#13817–#13820) were filed on the same day, which is one person retrying.

## Root cause

The logs all carry the same `Caused by`:

```
Caused by: java.lang.IllegalArgumentException: Malformed \uxxxx encoding.
	at java.base/java.util.Properties.loadConvert(Properties.java:664)
	at java.base/java.util.Properties.load(Properties.java:410)
	at VASSAL.preferences.ReadOnlyPrefs.<init>(ReadOnlyPrefs.java:51)
	at VASSAL.preferences.ReadOnlyPrefs.getGlobalPrefs(ReadOnlyPrefs.java:78)
	at VASSAL.launch.StartUp.lambda$initUIProperties$0(StartUp.java:149)
```

`Properties.load` reports a malformed file by throwing `IllegalArgumentException`. It is
**unchecked**, so it escapes every caller written to expect an I/O failure — and a method that
reads a file is exactly such a caller.

VASSAL knew this. `Prefs.read` has converted it for years, with the reason in a comment:

```java
catch (IllegalArgumentException e) {
  // This happens if we have a corrupted prefs file, which is not
  // a very friendly exception for Properties.load() to throw when
  // doing I/O.
  throw new IOException("Corrupted prefs file", e);
}
```

Two siblings call `Properties.load` without it: `ReadOnlyPrefs.<init>` and `Prefs.save`.

**`ReadOnlyPrefs` is the one that stops VASSAL**, because of where it runs. The global
preferences are read through it from `StartUp.initUIProperties`, inside
`SwingUtilities.invokeAndWait`, before any window exists. The exception crosses the
`invokeAndWait` boundary, arrives as `InvocationTargetException`, and start-up is over.

Ten of the eleven logs then show the *second* read of the same file, through `Prefs.read`,
being handled correctly — `java.io.IOException: Corrupted prefs file`, reported through
`ProblemDialog`. The two sit a few lines apart in the same log. The handling is there; it is
just not on the path that matters.

### What makes a preferences file malformed

`\u` must be followed by four hex digits. A Windows path stored in a preference without its
separators escaped is enough: `C:\users\…` is a `\u` followed by `sers`, and `s` is not hex.
`Properties.store` escapes correctly, so VASSAL does not write these files itself — but
anything that edits one by hand, or a partial write, can produce one.

Note that `Properties.load` fills the map as it parses, so a file that goes bad on line 40
leaves the first 39 entries loaded. Reading is not all-or-nothing.

## A second, quieter half

`Prefs.save` reads the existing file too, to merge in values this `Prefs` does not own:

```java
final InputStream in = new BufferedInputStream(Channels.newInputStream(ch));
storedValues.load(in);      // unguarded
```

So a user whose preferences file had gone bad could not save **any** preference afterwards,
for as long as the file sat there. No report names this, because the start-up crash comes
first — but it is the same defect, and it is why the fix touches all three readers rather than
only the one that was reported.

## The fix

- **`ReadOnlyPrefs`** converts the unchecked exception into an `IOException`, which the class's
  existing handler already reports with `ReadErrorDialog`. This is what [78a7a04a8][c] landed,
  and it is what closes all eleven reports.
- **`Prefs.save`** keeps a copy of the unreadable file as `<name>.corrupted` and writes a
  readable one in its place. Discarding a file nobody could parse without keeping it would be
  the wrong kind of robustness. This is [#15353][p], still open.

[c]: https://github.com/vassalengine/vassal/commit/78a7a04a8d75d56484caafd9b35a16f9d1be031c
[p]: https://github.com/vassalengine/vassal/pull/15353

The saving half has one trap the obvious implementation walks into. `save()` holds an exclusive
`FileLock` over the preferences file for the whole read-modify-write, so keeping a copy with
`Files.copy` — which opens a second handle — fails on Windows, where that lock is mandatory
rather than advisory. The copy has to be taken **through the channel `save()` already holds**:

```java
  private void keepCorrupted(FileChannel ch) {
    final Path to = file.toPath().resolveSibling(file.getName() + ".corrupted");
    try {
      ch.position(0);
      try (OutputStream out = Files.newOutputStream(to)) {
        Channels.newInputStream(ch).transferTo(out);
      }
    }
    catch (IOException e) {
      // best effort: failing to keep the copy must not fail the save
    }
  }
```

A Linux-only test would never have caught that, and nor would review. It is the kind of thing
only reading the lock semantics finds.

## Overtaken while the PR was open

[#15353][p] opened covering both halves. Four days later, rebasing it onto `main` conflicted —
and the conflict *was* the fix: [78a7a04a8][c], "Report a corrupt module prefs file as a read
error instead of a bug", had landed the `ReadOnlyPrefs` guard on 15 November 2026, under
**#14177, #14176, #14098 and #13910**. Four issue numbers, none of them among the eleven found
here, and the same defect.

Three things in that are worth keeping.

**The duplicate-hunting cut both ways.** Grouping eleven reports under one frame was the useful
part of the analysis, and it still is — but it searched the *open* issues for reports of the
bug, and never searched the *commits* for a fix. Two people can work the same ABR queue and find
the same fault through two disjoint sets of duplicates. The check that was missing is cheap:
before responding to review on an old bug, re-read the file on the current base, not the branch.

**The symptom came back with no CI.** The first sign was not a conflict notice but the *absence*
of check runs on the PR: GitHub will not run a `pull_request` workflow when it cannot compute
the merge. A PR sitting with no checks at all, where its siblings have five each, means
`mergeable_state: dirty` far more often than it means a queue backlog —

```
gh api repos/<owner>/<repo>/pulls/<n> --jq .mergeable_state
```

— and that is a one-line question worth asking before investigating anything else.

**The rebase improved the surviving half.** The `Files.copy` above was in the original branch and
would have shipped. Being forced back through the code on a new base is what surfaced the
`FileLock` problem.

The correction was made on all eleven issues, naming the commit that actually fixes them, rather
than left to be discovered by whoever closes them.

### Review: silence was the wrong default — and then moot

The exchange below happened on the reading half, and was overtaken a few hours later by
[78a7a04a8][c], whose own test asserts exactly the behaviour it argues for. It is kept because
the reasoning is the point, not the patch.


`ReadOnlyPrefs` originally logged the `CorruptedException` and carried on without a dialog, on
the reasoning that everything it offers has a default and that whichever `Prefs` went on to read
the same file for real would report the problem anyway. uckelman asked the obvious question in
review — *how will the user know there was a problem?* — and the reasoning does not survive it.

It holds for `V_Global`, which a full `Prefs` does read. It does not hold for a module's
preferences: `AbstractLaunchAction` reads those through `ReadOnlyPrefs` on its own, to find the
heap size, and the `Prefs` for that module is built later, inside the child process. So there was
a real case where nobody was told at all.

And even where the reasoning did hold, it was answering the wrong question. A user whose
preferences have gone back to their defaults should not have to work out why by noticing.

Two things were worth checking before making it a dialog, since this runs before any window
exists:

- **It cannot produce a run of dialogs.** `ReadOnlyPrefs` has exactly two call sites —
  `StartUp.initUIProperties` and `AbstractLaunchAction` — and neither is in a loop. Had it been
  constructed once per module in a list, a dialog would have been the wrong answer.
- **It cannot deadlock start-up.** `ReadErrorDialog.error` reaches `DialogUtils.enqueue`, which
  submits to an executor rather than showing anything inline, so nothing blocks inside the
  `SwingUtilities.invokeAndWait` that `initUIProperties` runs in.

The general lesson is the one the review supplies: *degrading gracefully* and *saying nothing*
are different things, and it is easy to reach for the second while believing you have done the
first. The test now asserts the report, not just the survival.

## Red-checking a fix that adds API

The first version of this fix introduced `Prefs.loadProperties` and `Prefs.CorruptedException`,
a shared conversion for all three readers. That made the usual red check impossible: reverting
the source and rerunning the new tests does not *compile*, because the tests name API that does
not exist before the fix.

The way through is a throwaway test written against the **old** API only — construct a
`ReadOnlyPrefs` over a corrupt file, and call `save()` on a `Prefs` over one — run against
unmodified upstream. Both fail with

```
Unexpected exception thrown: java.lang.IllegalArgumentException: Malformed \uxxxx encoding.
```

the reporters' message exactly, and both pass with the fix. It is worth doing this way round
rather than skipping the red check: a test that has never failed has never been shown to test
anything.

The shared helper is gone from the surviving PR — with the reading half fixed upstream in its
own idiom, a refactor of three call sites into one would have been rewriting a colleague's
just-landed commit to no behavioural end. `PrefsSaveTest` names no new API, so it red-checks the
ordinary way, and two of its four tests fail against the current base.

### A smaller trap, worth knowing

Java processes unicode escapes **before** parsing, and in comments as well as code. A Javadoc
block containing `\uxxxx` does not compile:

```
error: illegal unicode escape
```

and so does `"C:\\users"` in a string literal, because `\u` preceded by an *even* number of
backslashes is still an escape. Writing the string as `"C:" + BS + "users"` avoids it.

## What this means for the utility

The extension utility keeps its own settings in
`~/.vassal-extension-utility/recent-files.properties` and `ui.properties`, read with
`Properties.load`, so the same exception could escape here too. It does not stop the
application: both readers already fail soft — `RecentFilesStore` catches `Exception` around
the load, and `UiTheme.applySaved()` falls back to Light — which is the behaviour this fix
gives VASSAL. The lesson to keep is the one `Prefs.read`'s comment records: **`Properties.load`
signals a bad file with an unchecked exception**, so `catch (IOException)` around it is not
enough, and every place that reads a properties file needs the same guard rather than one of
them.
