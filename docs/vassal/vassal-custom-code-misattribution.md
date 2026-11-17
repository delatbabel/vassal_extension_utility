# VASSAL reports a module's own code as a VASSAL bug

This note documents a defect in the VASSAL engine's error reporting (not in this utility), found
while working on [#15225](https://github.com/vassalengine/vassal/issues/15225) and fixed by
[#15348](https://github.com/vassalengine/vassal/pull/15348). It is the reason roughly one automated
bug report in fourteen is filed against VASSAL for a fault VASSAL cannot fix.

## Symptom

[#15225](https://github.com/vassalengine/vassal/issues/15225) quotes this, under the heading
*[Great Battles of Alexander: Deluxe Edition] IllegalArgumentException*:

```
java.lang.IllegalArgumentException: Invalid state list change
 at gboa.StateDelta.edit(StateDelta.java:41)
 at gboa.StateDelta.edit(StateDelta.java:37)
 at gboa.StateDelta.edit(StateDelta.java:37)
 at gboa.StateDelta.apply(StateDelta.java:31)
```

Every frame is in `gboa.StateDelta`. That class is not VASSAL's — it is compiled Java the Great
Battles of Alexander module ships inside its own `.vmod`. The player saw *"VASSAL has detected a
bug"* and was offered the report which became this issue.

## What the log adds

Fetched as [reading-abr-error-logs.md](reading-abr-error-logs.md) describes, the log gives the
frames the issue body cuts off:

```
at gboa.SharedState$DeltaCommand.executeCommand(SharedState.java:30)
at VASSAL.command.Command.execute(Command.java:61)
at VASSAL.command.Command.execute(Command.java:73)
at VASSAL.build.module.GameState.loadFastForward(GameState.java:908)
at VASSAL.build.module.GameState$4.actionPerformed(GameState.java:213)
```

and the sequence that led to it. The module loaded at 22:45:34; the same `펠리움시작.vlog` was
loaded at 22:47:56 and again at 22:48:54; the failure came at 22:51:19, under six minutes into the
session.

`GameState$4` is the fourth anonymous action built in `GameState.addTo()`, and line 213 of the
3.7.30 source is its `loadFastForward(true)` — **File → Load Continuation, Append**. So the
module's own `DeltaCommand` was replayed over a game whose state list was not what the delta
expected, and the module's own validation rejected it. That is a module-side fault, and arguably a
user-side one: the log was being replayed onto a game that had already consumed it.

**Nothing crashed.** `Command.execute` (identical in 3.7.30 and on `release-3.8`) catches
everything a command throws:

```java
  public void execute() {
    try {
      executeCommand();
    }
    catch (Throwable t) {
      handleFailure(t);
      ...
    }

    for (final Command cmd : seq) {
      try {
        cmd.execute();
      }
      catch (Throwable t) {
        handleFailure(t);
      }
    }
  }

  private void handleFailure(Throwable t) {
    ThrowableUtils.throwRecent(OutOfMemoryError.class, t);
    if (t instanceof Error) {
      throw (Error) t;
    }
    else {
      ErrorDialog.bug(t);     // <-- "VASSAL has detected a bug"
    }
  }
```

So the two `Command.execute` frames in the trace are where the throwable was *caught*, not where it
escaped. Play continued. The only thing that happened is that `ErrorDialog.bug` told the player
VASSAL was broken and offered to file a report about it.

## It is not one report, it is a family

Every ABR-labelled issue on the tracker was fetched and its body parsed for the first
`at <class>.<method>(` frame. Classes in VASSAL's own packages, in the Java platform, and in the
libraries VASSAL bundles (`org.netbeans`, `org.jdesktop`, `org.apache`, `javazoom`, `bsh`,
`org.litesoft`, …) were excluded; what remains is code that can only have come out of a module
archive.

| | count |
|---|---|
| ABRs on the tracker | 1,696 (348 open, 1,348 closed) |
| of those, with a parseable stack trace in the body | 1,532 (291 open, 1,241 closed) |
| **of those, with module-supplied code as the topmost frame** | **108 (7.0%)** — 33 open, 75 closed |
| distinct module code packages | 12 |

Counted across open and closed together:

| package | reports | module |
|---|---|---|
| `ca.mkiefte.*` | 35 | Twilight Struggle |
| `tdc.*` | 17 | GTS The Greatest Day, Operation Mercury, Sword/Juno/Gold |
| `rsr.*` | 17 | Red Star Rising |
| `foobarius.*` | 13 | Eldritch Horror |
| `VASL.*` | 9 | VASL, VSQL |
| `mic.*` | 7 | Star Wars X-Wing |
| `turn.*` | 4 | 18xx, No Better Place To Die |
| `PathsOfGlory.*` | 2 | Paths of Glory |
| `gboa.*`, `Inventory.*`, `avl.*`, `ancients.*` | 1 each | GBoA, TRC3, AVL Solo, C&C Ancients |

The 33 open ones are #12472 #13510 #13576 #13577 #13585 #13586 #13587 #13606 #13977 #13993 #14003
#14058 #14063 #14064 #14106 #14112 #14119 #14120 #14130 #14131 #14312 #14492 #14570 #14604 #14619
#14718 #14773 #14831 #14836 #14905 #15225 #15342 #15343. **None of them has a single comment on
it.** The closed ones were closed variously as completed and as not planned, because there was
nothing else to do with them.

Two caveats on the method. It keys on the *topmost* frame, so a module fault that throws from
inside a VASSAL frame is not counted — the figure is a floor, not a ceiling. And 164 ABRs carry no
parseable trace in the body at all; those were left out rather than guessed at.

## Why the trace said nothing

VASSAL does know which classes came from the module: `VASSAL.tools.DataArchive` **is** the class
loader for them (`DataArchive extends SecureClassLoader`, and its `findClass` reads
`<name>.class` out of the archive and calls `defineClass`). None of that reached the stack trace.

`StackTraceElement` has carried `getClassLoaderName()` since Java 9, and the printed form puts the
loader's name before the class — `at loader//pkg.Class.method(...)`. VASSAL got neither, and the
reason is a near miss. `DataArchive` does override `getName()`:

```java
  @Override
  public String getName() {
    return archive == null ? "data archive" : archive.getName(); //NON-NLS
  }
```

but that is not the name the VM records. The VM uses the name passed to the `ClassLoader(String
name, ClassLoader parent)` constructor, and `DataArchive` called `super(parent)` — the unnamed
form. (`ClassLoader.getName()` is *not* final, which is why the override compiles at all; it simply
has no bearing on stack traces.)

Measured with a stand-in loader built the way `DataArchive` is — overriding `getName()`, calling
the unnamed `super`:

```
java.lang.IllegalArgumentException: Invalid state list change
	at custom.Boom.go(Boom.java:2)
--- frame metadata ---
custom.Boom  classLoaderName=null  moduleName=null
defining loader of custom.Boom: L2$Loader@1dbd16a6 name=Module.vmod
```

The same loader, differing only in calling `super("Module.vmod", parent)`:

```
java.lang.IllegalArgumentException: Invalid state list change
	at Module.vmod//custom.Boom.go(Boom.java:2)
--- frame metadata ---
custom.Boom  classLoaderName=Module.vmod  moduleName=null
```

One constructor argument is the whole difference between a trace that identifies the module's code
and one that does not.

## The fix

[#15348](https://github.com/vassalengine/vassal/pull/15348), in two commits so the second can be
dropped independently of the first.

### 1. Name the loader

`DataArchive`'s loader is named `module:<archive file name>`, so a frame of module code prints as

```
at module:GBoADE_A01_A0115_2.vmod//gboa.StateDelta.edit(StateDelta.java:41)
```

A future report of this kind is triageable from the issue body, without opening the log.
`JarArchive` reads from VASSAL's own jar rather than from a module, so it is named `vassal` and can
never be mistaken for one. `ArchiveWriter`, which is the module open in the editor, gets the name
too. Nothing else changes: `getName()` answers exactly as before, and delegation and class defining
are untouched.

### 2. Ask whose code failed before filing a report

`ErrorDialog.bug` is the one funnel — both `Command.handleFailure` and the uncaught-exception
handler (`VASSAL.launch.ExceptionHandler`) reach it — so the question is asked in exactly one
place. The new `VASSAL.tools.bug.CustomCode.blame(Throwable)` follows the throwable to its root
cause, since that is where the failure began, and takes the first frame which is **not** in the
Java platform: the throw itself frequently happens inside `Objects.requireNonNull`, a collection
accessor or a reflective call made by the code actually at fault. If the class in that frame was
loaded out of a module archive, the player gets a dialog naming it and asking them to report it to
the module's maintainer, with the trace in the details pane, and nothing is filed against VASSAL.

The attribution is deliberately one-directional: **the module is blamed only when its own code is
the innermost non-platform frame.** A VASSAL class which fails while called from module code is
still VASSAL's bug and is still reported as one.

VASSAL already drew this distinction in two narrower places, which is the best argument that the
shape of the fix is right: `Error.custom_class_error` ("…which is provided by this module. Please
report this problem to the module maintainer.") for a class that fails to load or instantiate, and
`Dialogs.incompatible` for custom code compiled for too new a Java. The new string pair extends the
same policy to custom code that fails while running.

### The trade-off, stated plainly

If a VASSAL change makes previously working module code throw — VASSAL starts returning null
somewhere, say — this blames the module, and that report no longer arrives automatically. Against
it: the throwable is still written to the error log in full, the dialog names the failing class
precisely, and a maintainer who finds VASSAL at fault can escalate with better information than
today's ABR carries. The PR says so rather than burying it, and the first commit alone (label the
frames, keep filing the reports) is a coherent position if the maintainer prefers it.

### Tests

`DataArchiveTest` builds a zip holding a compiled class, has a real `DataArchive` define it from
there, and checks that what it throws carries the archive's name — in `getClassLoaderName()`, in
the printed trace, and in the attribution. `CustomCodeTest` drives the classifier through a loader
named the way `DataArchive` names its own: a direct throw, a throw via a platform frame, a throw
wrapped in another exception, a failure in engine code called *from* module code (not blamed), the
same failure from a loader that is not a module's (not blamed), and null and self-referential
causes.

Checked red: with the loader naming reverted, `DataArchiveTest` fails with
`expected: <module:Great Battles.vmod> but was: <null>` — which is the state of affairs the PR
changes. Full `vassal-app` suite 804 tests, 0 failures, Checkstyle clean, all five CI jobs green.

## A latent defect found along the way

`GameState.loadFastForward` sets a flag and clears it at the end, with nothing in between:

```java
  private void loadFastForward(boolean append) {
    fastForwarding = true;
    ...
    while (bl.isReplaying()) {
      final Command c = bl.logInput.get(bl.nextInput++);
      c.execute();
      g.sendAndLog(c);
    }
    ...
    fastForwarding = false;
  }
```

There is no `try`/`finally`. It did **not** bite in #15225, because `Command.execute` swallows
exceptions — but `handleFailure` *rethrows* `Error`s, and a `NoSuchMethodError` from stale module
code is exactly that, and is the single most common shape in the family above (`rsr.RsrMapShader`
and `foobarius.HoldableDecorator`, 30 reports between them). One of those during a fast-forward
leaves `fastForwarding` stuck true for the rest of the session, and the flag is consulted in three
places:

- `PlaySound.myKeyEvent` returns **no command at all** while it is set, so a Play Sound trait is
  silent for every player, not merely for the local one;
- `PlayAudioClipCommand.executeCommand` likewise plays nothing;
- `GameState.setup` takes the fast-forward branch, which omits the *"start a new logfile?"* prompt
  — so every game started afterwards in that session is silently unlogged.

A `try`/`finally` fixes it, which is
[#15349](https://github.com/vassalengine/vassal/pull/15349) — kept separate from #15348 rather than
widening that change, since it is a different concern. Its regression test asserts both halves: the
flag *was* set while the load ran, and is clear after the load throws.

There is a second way in which does not involve module code at all. `GameState.loadGame` catches
`IllegalStateException`, handles the `Decorator.no_state_for_trait` case and **rethrows anything
else** (`GameState.java:858`) — which is what a log saved by a different module version can
produce. Cancelling the file chooser is *not* one of these: `loadGame` returns false and the clear
is still reached, which the second test pins.

## The third way an ABR misleads

The two earlier notes in this folder are about the four frames an automated report quotes.
[#15300](vassal-chatter-html-nesting.md) quotes the *deepest* four, which for a recursive overflow
are the least characteristic ones. [#15262](vassal-record-module-load-crash.md) quotes the
*outermost* four, which for an `ExecutionException` wrapper are identical for every failure
anywhere in module loading.

[#15209](vassal-apply-on-move-recursion.md) is a fourth, where five reports of one bug quote five
disjoint sets of BeanShell frames, none of them the fault.

This one is the third case and the most quietly misleading of the four: the quoted frames are
**complete, correct, and the whole of the trace** — and still read as a VASSAL bug, because nothing
in them says the classes are not VASSAL's. `gboa.StateDelta` looks exactly like
`VASSAL.counters.Decorator` to anyone who does not already know the module. The first commit of
#15348 is the answer to precisely that: make the frame say where its class came from, and the
report classifies itself.

## What this means for the utility

Nothing directly: the extension utility does not report bugs on VASSAL's behalf, and it reads a
module's `buildFile.xml`, images and saved games rather than its custom classes.

It matters when reading an ABR before reaching for the engine source. **Check whose code the top
frame belongs to first.** A package that is neither `VASSAL.*` nor one of the bundled libraries
listed above almost certainly came out of the `.vmod`, and the only fix available is the module
maintainer's. On 1,532 traced reports that is true 7% of the time — often enough to be worth the
ten seconds it takes to look.
