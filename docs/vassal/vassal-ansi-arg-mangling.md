# VASSAL bug: a module path the ANSI code page cannot carry reaches the child as `?`

This note documents a bug in the VASSAL engine (not in this utility), reported as
[#14193](https://github.com/vassalengine/vassal/issues/14193) and fixed by
[#15360](https://github.com/vassalengine/vassal/pull/15360). A module stored in a folder whose
name contains an accented letter cannot be tiled, and the failure is reported as running out of
memory.

It is the one report in the thirty-strong
["ran out of memory"](vassal-tiling-not-out-of-memory.md) family with a cause of its own.

## Symptom

```
java.util.concurrent.ExecutionException: java.io.IOException: Possibly ran out of memory.
Tiling failed with return value == 1
```

The same four `SwingWorker` frames as every other failure on the module-loading path, and a
message that is a guess rather than a diagnosis.

## The log gives it away in two adjacent lines

The parent logs the command line it is about to run. The path in it is **correct**:

```
INFO VASSAL.tools.io.ProcessLauncher - launching ...\java -classpath lib\Vengine.jar -Xmx399M
  -Duser.home=C:\Users\... VASSAL.tools.image.tilecache.ZipFileImageTiler
  D:\Games\Wargames\Tabletop Vassal\La Bataille d'Auerstædt\La_Bataille_d'Auerstedt_1.18.vmod
  C:\Users\...\AppData\Local\VASSAL\tiles\98a43d48... 256 256
```

Six hundred milliseconds later the child dies on the same path with one character replaced:

```
ERROR VASSAL.tools.image.tilecache.ZipFileImageTiler - main
java.nio.file.InvalidPathException: Illegal char <?> at index 54:
  D:\Games\Wargames\Tabletop Vassal\La Bataille d'Auerst?dt\La_Bataille_d'Auerstedt_1.18.vmod
	at VASSAL.tools.io.ZipArchive.<init>(ZipArchive.java:113)
	at VASSAL.tools.image.tilecache.ZipFileImageTiler.writeToStream(ZipFileImageTiler.java:181)
```

Ten launches follow — heap 399, 598, 897, 1345, 2017, 3025, 4537, 6805, 10207, 12093 MB — each
dying in about six hundred milliseconds with the identical exception, before the user is told
the machine ran out of memory.

**The transferable trick:** when a parent logs the command line it passes and the child reports
a path, compare them character by character. A difference is an encoding fault, and nothing in
the issue body will ever show it, because the issue body quotes the parent's exception.

## Root cause

VASSAL hands the command line to `CreateProcessW`, so it leaves the parent JVM as UTF-16 and
arrives in the child's process block intact. The child is `java.exe`, whose launcher reads it
back with `GetCommandLineA` — the ANSI entry point. Windows converts through the system ANSI
code page (`CP_ACP`) and substitutes `?` for every character that code page has no room for.

So whether an argument survives is a property of **the machine's ANSI code page**, which is
whatever `GetACP()` returns.

`VASSAL/tools/io/ArgEncoding.java` exists precisely to work around this: an argument that will
not survive is URL-encoded, and the receiving end decodes it after `--encoded-args`. Its test
for when that is needed was:

```java
  // ProcessBuilder on Windows misencodes arguments containing Unicode code
  // points outside of the windows-1252 encoding. ...

  private static boolean aboveFF(String s) {
    return s.chars().anyMatch(c -> c > 0xFF);
  }

  public static boolean requires(String s) {
    return SystemUtils.IS_OS_WINDOWS && s != null && aboveFF(s);
  }
```

Two separate errors in that one line, and the comment states the first of them outright:

1. **The code page is not always windows-1252.** It is the system's, and most of the others
   lack characters windows-1252 holds.
2. **`> 0xFF` is not "outside windows-1252" even on a windows-1252 machine.** windows-1252 fills
   0x80–0x9F with characters whose code points are above U+00FF.

`æ` is U+00E6, so `requires()` returned false, the path went raw, and this machine's code page
had no `æ`.

### Measured

Running the reported path through a candidate's encoder, and `€` (U+20AC) beside it:

| code page | holds `æ` (U+00E6) | holds `€` (U+20AC) |
|---|---|---|
| windows-1252 | yes | yes |
| windows-1254, windows-1257 | yes | yes |
| windows-1250, windows-1251, windows-1253 | **no** | yes |
| Shift_JIS | **no** | no |
| GBK | **no** | yes |
| UTF-8 | yes | yes |
| US-ASCII | **no** | no |

The old predicate returns `false` for the reported path — so it is under-encoded on five of the
eight — and `true` for `€`, which windows-1252 holds and which never needed encoding at all. It
is wrong in both directions.

The reporter's code page is one of the rows without `æ`; the log does not say which, and nothing
in the diagnosis depends on knowing.

## The fix

`sun.jnu.encoding` is derived from `GetACP()` during JVM startup, so the parent can ask what the
child will decode with rather than assume it:

```java
  static boolean requires(String s, Charset cs) {
    // CharsetEncoder is not thread-safe, so take a fresh one each time.
    return s != null && !cs.newEncoder().canEncode(s);
  }

  public static boolean requires(String s) {
    return SystemUtils.IS_OS_WINDOWS && requires(s, ARG_CHARSET);
  }
```

Where the charset cannot be determined — the property absent, or a name `Charset.forName`
rejects — the fallback is US-ASCII. That encodes a little more than it must and is never wrong,
since the receiving end decodes whatever is sent encoded.

The two-argument overload is package-private so the tests can drive windows-1250, windows-1252
and UTF-8 directly, and therefore run on Linux CI without a Windows host.

### Three callers, none of which changes

`TilingHandler.makeSlicerCommandLine`, `LaunchRequest.toArgs` and `ModuleManagerUpdateHelper`
all already emit `--encoded-args` and their receivers already decode it. Fixing the predicate
therefore also fixes **launching** a module from such a folder, and the update helper — not just
tiling it. The bug was never specific to the tiler; the tiler is just where it was reported,
because tiling is the first subprocess a large module reaches.

### Still Windows-only, deliberately

On Unix the parent writes arguments in the **default** charset — UTF-8 since JEP 400 — while the
child decodes them with **`sun.jnu.encoding`**. The question there is whether those two agree,
which is a different predicate; and the Unix failure mode is a broken C library locale, which
wants the locale repaired rather than the arguments encoded. Widening the guard would have been
a claim the evidence does not support.

### What the fix does not reach

`-classpath` and `-Duser.home=` in `TilingHandler.makeSlicerCommandLine` travel as plain argv
and are read by the child JVM itself, so they cannot be URL-encoded — there is nothing on the
far side to decode them. A non-ASCII **install path** or **Windows user name** therefore still
mangles. Flagged on the pull request as a known gap rather than guessed at.

## The Linux half of the same subject

The same system property, failing differently. When the C library locale is unusable — unset,
`C`/`POSIX`, or naming locale data that is not installed, which is routine inside a Flatpak —
glibc falls back to `C`, `sun.jnu.encoding` becomes ASCII, and the JVM cannot decode non-ASCII
**filenames at all**. Listing a home directory is enough to throw:

```
java.nio.file.InvalidPathException: Malformed input or input contains unmappable characters:
  /home/<user>/Vídeos
```

A scan of the ABR corpus finds **seven open reports** of this —
[#15314](https://github.com/vassalengine/vassal/issues/15314),
[#12585](https://github.com/vassalengine/vassal/issues/12585),
[#12838](https://github.com/vassalengine/vassal/issues/12838),
[#13300](https://github.com/vassalengine/vassal/issues/13300),
[#13553](https://github.com/vassalengine/vassal/issues/13553),
[#13599](https://github.com/vassalengine/vassal/issues/13599),
[#15240](https://github.com/vassalengine/vassal/issues/15240) — plus closed #12839 and #15241.

Two facts shape that fix and are worth recording here, because both are counter-intuitive:

- **`sun.jnu.encoding` cannot be set with `-D`.** It is fixed during JVM startup, before
  command-line properties are applied, so `-Dsun.jnu.encoding`, `-Dfile.encoding` and
  `_JAVA_OPTIONS` all leave it where it was. Only the environment can change it.
- **`LC_ALL` is what has to be set.** A `LANG` naming missing locale data makes
  `setlocale(LC_ALL, "")` fail as a whole, which defeats a per-category `LC_CTYPE` — even though
  `locale charmap` then cheerfully reports UTF-8, so `charmap` alone is not a sufficient test.

That work is [#15319](https://github.com/vassalengine/vassal/pull/15319), a separate change on
branch `fix/15314-filename-encoding-locale`. Note that it names only #15314, #15241 and #15240
as fixed — the other four open reports above carry the same `sun.jnu.encoding` cause and are
worth linking to it.

## Tests

`vassal-app/src/test/java/VASSAL/tools/io/ArgEncodingTest.java`, eight cases, all driving the
charset explicitly:

| case | expectation |
|---|---|
| reported path under windows-1250 | needs encoding — **the red check** |
| reported path under windows-1252, under UTF-8 | does not |
| ASCII path under four charsets | does not |
| `€` under windows-1252 | does not — the old code encoded it needlessly |
| a character windows-1252 lacks | does |
| `null` | does not |
| encode → decode | round-trips, and is pure ASCII in between |

**Red check.** The committed test uses the new two-argument overload, so it cannot compile
against the old source. Instead the *predicate body* is reverted behind the new signature,
which isolates exactly the thing that changed. Both halves of the error then fail, in opposite
directions:

```
ArgEncodingTest.reportedPathNeedsEncodingWhereCodePageLacksIt:55 expected: <true> but was: <false>
ArgEncodingTest.characterAboveFFWhichTheCodePageHoldsNeedsNoEncoding:86 expected: <false> but was: <true>
```

**Suite.** 806 tests, 0 failures. The baseline at `919b88b02` is 798, measured in a clean
worktree rather than assumed — the figure quoted in earlier notes here was 802, from an older
upstream tip. As [the tiling note](vassal-tiling-not-out-of-memory.md#count-your-tests-from-a-clean-directory)
says, clear `vassal-app/target/surefire-reports` before the run you intend to quote.

## What this means for the utility

This one is not academic. `MainWindow`'s Refresh Counters worker launches the engine exactly the
way VASSAL launches the tiler:

```java
cmd.add(launcher.getAbsolutePath());
cmd.add("-Xmx" + refreshHeapMb() + "m");
cmd.add("-cp");
cmd.add(engine.getAbsolutePath() + File.pathSeparator + ourCode);
cmd.add(RUNNER_CLASS);
cmd.add(job.getAbsolutePath());
```

On Windows every one of those crosses through the ANSI code page, and two of them are exposed:

- **`-cp`** carries the installed `Vengine.jar` path and this application's own jar path. Neither
  can be URL-encoded, for the same reason VASSAL's own `-classpath` cannot: the child JVM reads
  them itself, before any of our code runs. A user whose Windows account name is not ASCII has a
  non-ASCII path to this application's jar.
- **the job file** is `File.createTempFile("refresh-counters", ".job")`, which lands in
  `java.io.tmpdir` — on Windows typically `C:\Users\<name>\AppData\Local\Temp\`, so it carries
  the account name too.

The module path and the `.vsav` paths are *not* exposed, because they travel inside the job file
rather than on the command line — which is the right pattern, and the one to extend. A file
whose own name is ASCII can carry any path at all; the general answer to this whole class of
problem is to put paths in a file, not in argv.

Nothing is broken today for an ASCII-named Windows account, and nothing at all on Linux. But if
Refresh Counters is ever reported failing on Windows with "Cannot run program" or a path error
naming a `?`, this is the cause. Two mitigations are available and neither has been implemented
yet:

- **Give the job file an ASCII home** rather than `java.io.tmpdir` — it is our file, so we choose
  where it goes and what it is called.
- **Get the absolute classpath off the command line.** VASSAL itself already passes
  `-classpath lib\Vengine.jar`, a path relative to the working directory, and
  `ProcessBuilder.directory()` reaches `CreateProcessW` as `lpCurrentDirectory` without passing
  through the code page. A jar manifest `Class-Path` is relative for the same reason and would
  do as well. Putting it in the `CLASSPATH` environment variable is **not** established as safe:
  `ProcessBuilder` does pass the environment block as UTF-16, but the JVM launcher reads
  `CLASSPATH` with the C library's `getenv`, so that may convert through the code page like
  everything else. It would have to be tested on a Windows host before being relied on.
