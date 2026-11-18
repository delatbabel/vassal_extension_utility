# VASSAL bug: one bad character in the preferences file and VASSAL will not start

This note documents a bug in the VASSAL engine (not in this utility), reported eleven times
between 2024 and 2026 and fixed by
[#15353](https://github.com/vassalengine/vassal/pull/15353). It is the clearest case in this
folder of a defect that was *already understood* — the handling exists, with a comment
explaining why, one class away from where it was needed.

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

The conversion becomes one `Prefs.loadProperties`, used by all three callers, with its own
exception type so a corrupted file can be distinguished from a disk error.

- **`ReadOnlyPrefs`** lets it fall through to the `catch (IOException)` the class already had,
  which reports it with `ReadErrorDialog.error` exactly as any other unreadable preferences file
  is reported. See [below](#review-silence-was-the-wrong-default) — the first version of this
  logged it and said nothing.
- **`Prefs.save`** keeps a copy of the unreadable file as `<name>.corrupted` and writes a
  readable one in its place. Discarding a file nobody could parse without keeping it would be
  the wrong kind of robustness.

### Review: silence was the wrong default

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

Both earlier notes in this folder could revert the source and rerun the new tests. Here that
does not compile: the tests reference `Prefs.loadProperties` and `Prefs.CorruptedException`,
which do not exist before the fix.

The way through is a throwaway test written against the **old** API only — construct a
`ReadOnlyPrefs` over a corrupt file, and call `save()` on a `Prefs` over one — run against
unmodified `release-3.8`. Both fail with

```
Unexpected exception thrown: java.lang.IllegalArgumentException: Malformed \uxxxx encoding.
```

the reporters' message exactly, and both pass with the fix. The committed tests then cover the
new API as well. It is worth doing this way round rather than skipping the red check: a test
that has never failed has never been shown to test anything.

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
