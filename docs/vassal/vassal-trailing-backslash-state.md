# VASSAL bug: a label ending with a backslash makes the piece unreadable

A defect in `VASSAL.tools.SequenceEncoder`, diagnosed and reported by **FredKors** as
[#15337](https://github.com/vassalengine/vassal/issues/15337) and fixed by
[#15363](https://github.com/vassalengine/vassal/pull/15363), which is the work recorded here.

The diagnosis is not this project's. It was reached here independently, from the opposite
direction — working down the open automated bug reports rather than up from a reproduction —
and the issue was found already filed, with the same mechanism and the same proposed fix.
That is the useful part of the story and [the last section](#checking-the-tracker-first-worked)
returns to it.

## Symptom

```
java.lang.IllegalStateException: No state for trait=label;83,65;Strength;14;;255,0,51;b;0;c;0;b;c;$pieceName$ ($label$);Dialog;1;0;Strength;;false
	at VASSAL.counters.Decorator.setState(Decorator.java:444)
	at VASSAL.counters.Decorator.setState(Decorator.java:441)
	at VASSAL.counters.Decorator.setState(Decorator.java:441)
	… eighteen of these …
	at VASSAL.command.AddPiece.executeCommand(AddPiece.java:55)
	at VASSAL.build.module.GameState$11.done(GameState.java:1528)
```

A saved game will not load, a log will not replay, or a counter cannot be cloned. The message
quotes the whole type of one trait, which makes it look specific and is in fact the one part of
it that carries no information about the cause.

## Root cause

Two halves of `SequenceEncoder` disagree about a backslash.

**The decoder** treats a delimiter as escaped when the character before it is a backslash:

```java
if (val.charAt(i) == delim) {
  if (i > 0 && val.charAt(i - 1) == '\\') {
    // escaped delimiter; piece together the token
```

**The encoder** protects a value by enclosing it in quotes when it *starts* with a backslash —
but not when it *ends* with one:

```java
if (s.charAt(0) == '\\' ||
    (s.charAt(0) == '\'' && s.charAt(s.length() - 1) == '\'')) {
```

So the delimiter following a value that ends with a backslash is read as part of that value, and
the decoder goes on looking for an end that never comes. It does not merely corrupt that one
value: it swallows **every remaining token**.

Run against the real class, with `,` as the delimiter:

```
in=[abc  | innerstate]   encoded=abc,innerstate    out=["abc" | "innerstate"]
in=[abc\ | innerstate]   encoded=abc\,innerstate   out=["abc,innerstate" | *** NoSuchElementException ***]
in=[\abc | innerstate]   encoded='\abc',innerstate out=["\abc" | "innerstate"]
in=[a\bc | innerstate]   encoded=a\bc,innerstate   out=["a\bc" | "innerstate"]
```

The third line is the tell: the leading-backslash case is quoted and survives. The asymmetry is
the bug.

### How that reaches a game

`Decorator.getState()` builds a piece's state by joining each trait's own state to the state of
the traits below it with a TAB:

```java
public String getState() {
  final SequenceEncoder se = new SequenceEncoder(myGetState(), '\t');
  se.append(piece.getState());
  return se.getValue();
}
```

A trait whose state ends with a backslash therefore swallows the states of **every inner trait**.
`Decorator.setState()` reads the chain back, finds nothing left for the piece inside it, and
converts the `NoSuchElementException` into the message above — naming the trait that ran out,
which is the one holding the backslash.

### Why it is nearly always `label`

`Labeler.myGetState()` returns the label text exactly as the user typed it. It is the trait whose
state is free text, so it is the trait whose state can end with a backslash. On a UK keyboard the
`\` key sits beside the left shift, which is how a label acquires one without being meant to.

The piece is **written** correctly. It fails when it is **read back**, which is why the reports
cluster on three moments: loading a save, replaying a log, and cloning a counter. Cloning is the
one that gives the game away, because no file is involved at all —
`Clone.myKeyEvent` does

```java
newPiece.setState(outer.getState());
```

and that round trip is enough on its own. Two reporters describe exactly that: *"I think I was
hitting Ctrl C in order to copy a unit that I had just populated"*, and *"Was trying to clone a
marker. This marker has the Label trait and I set the two instances of that label"*.

## The fix

Quote a value ending with a backslash too, so that the quote rather than the backslash occupies
the position before the delimiter; `unquote()` removes it again on the way back.

```java
if (s.charAt(0) == '\\' ||
    s.charAt(s.length() - 1) == '\\' ||
    (s.charAt(0) == '\'' && s.charAt(s.length() - 1) == '\'')) {
```

Three things make this smaller than it looks:

- **The decoder is untouched.** Nothing already written reads differently; only what is written
  from now on changes. The ambiguity is inherent — `abc\<DELIM>` genuinely cannot be told apart
  from an escaped delimiter — so the fix has to be on the encoding side, and quoting is the
  escape hatch the class already had.
- **The seeding constructor is covered.** `SequenceEncoder(String, char)` delegates to `append`,
  and that constructor is the one `Decorator.getState()` uses.
- **A value ending with the delimiter was never affected.** Escaping prepends a backslash to the
  delimiter, so the encoded form of `abc<DELIM>` ends `\<DELIM>` and the real delimiter that
  follows is preceded by the delimiter, not by a backslash. The hole was only ever a *literal*
  trailing backslash.

### Older versions can still read it

Values quoted this way must stay readable by VASSAL versions that predate the fix, or every save
written by a fixed build would be a one-way door. The `Decoder` class is byte-identical between
3.7.25 and current `main`, and `unquote()` has been in it since 3.4.

Rather than leave that as an argument, it was measured: encode with the patched build, then
decode with the **released** `Vengine.jar` loaded in its own class loader.

```
encoded by fixed build : 'test\'<TAB>inner1\<TAB>inner2
released decoder tok1  : test\
released decoder tok2  : inner1<TAB>inner2
loaded Decoder from    : file:/usr/share/vassal/lib/Vengine.jar
```

Value and inner states intact.

### The red check

Six new tests, all failing before the change and passing after; 826 tests pass in total. The one
that matters is in `LabelerTest`, because it goes end to end rather than through the encoder:

```java
final Labeler saved = new Labeler(Labeler.ID, createBasicPiece());
saved.setLabel("test\\");
final Labeler loaded = new Labeler(Labeler.ID, createBasicPiece());
loaded.setState(saved.getState());
```

Against the unmodified engine that throws

```
java.lang.IllegalStateException: No state for trait=label;;Change label;10;;0,0,0;t;0;c;0;b;c;$pieceName$ ($label$);Dialog;0;0;TextLabel;;false
```

— the reporters' exception verbatim, which is the strongest evidence available that the defect
and the reports are the same thing.

One of the six is a test of something that was never broken: a value of a single backslash
round-trips, because it also *starts* with one and so was already quoted. It is there so the
fix cannot be "simplified" later into something that loses that case.

## The family, and what can honestly be claimed

Grouping the open ABR issues by the **first VASSAL frame** in the quoted trace puts 16 of them on
`Decorator.setState`, and 12 of those name the `label` trait:

| issue | version | trait | | issue | version | trait |
|---|---|---|---|---|---|---|
| [#12579](https://github.com/vassalengine/vassal/issues/12579) | 3.6.19 | PROP | | [#14328](https://github.com/vassalengine/vassal/issues/14328) | 3.7.18 | label |
| [#12628](https://github.com/vassalengine/vassal/issues/12628) | 3.6.19 | label | | [#14336](https://github.com/vassalengine/vassal/issues/14336) | 3.7.18 | label |
| [#13067](https://github.com/vassalengine/vassal/issues/13067) | 3.7.7 | label | | [#14607](https://github.com/vassalengine/vassal/issues/14607) | 3.7.20 | emb2 |
| [#13234](https://github.com/vassalengine/vassal/issues/13234) | 3.7.9 | label | | [#14762](https://github.com/vassalengine/vassal/issues/14762) | 3.7.22 | mark |
| [#13588](https://github.com/vassalengine/vassal/issues/13588) | 3.7.14 | label | | [#14915](https://github.com/vassalengine/vassal/issues/14915) | 3.7.24 | label |
| [#13684](https://github.com/vassalengine/vassal/issues/13684) | 3.7.15 | label | | [#14984](https://github.com/vassalengine/vassal/issues/14984) | 3.7.26 | label |
| [#14221](https://github.com/vassalengine/vassal/issues/14221) | 3.7.16 | label | | [#15010](https://github.com/vassalengine/vassal/issues/15010) | 3.7.26 | — |
| [#14276](https://github.com/vassalengine/vassal/issues/14276) | 3.7.18 | label | | [#15190](https://github.com/vassalengine/vassal/issues/15190) | 3.7.29 | label |

**None of them is claimed as fixed, and the PR deliberately writes `fixes` for the issue only.**
The attached logs record the exception but not the label text, so no individual report can be
confirmed, and there is at least one other route to the same message: a *prototype* that gained a
trait after the game was saved expands to more traits than the state describes, which also leaves
the chain short. `No state for trait=prototype;Army`, elsewhere in the tracker, looks like exactly
that. A concentration of twelve on the one trait whose state is free text is hard to read any
other way, but "hard to read any other way" is not proof and should not be written down as if it
were.

### Why a sixteen-report family went unnoticed

This is the first family in these notes where the four quoted frames are **completely accurate and
completely VASSAL's** — `Decorator.setState` four times over — and the family was still invisible.
The reason is the titles. The message embeds the whole type string of the trait, so every report
gets a title no other report shares:

```
[Gettysburg (1977)] IllegalStateException: No state for trait=label;83,65;Strength;14;…
[Panzer] IllegalStateException: No state for trait=label;73,130;Change ID #;15;…
[Fortress Europa (AH)] IllegalStateException: No state for trait=label;76,130;Label;8;…
```

A tracker search finds none of the others from any of them. Sixteen reports of one fault, each
looking like a module-specific oddity, over two and a half years.

So this note adds a seventh case to the running list in
[vassal-record-module-load-crash.md](vassal-record-module-load-crash.md), and it is the one that
inverts the lesson: there the quoted frames were the problem, here they were the only thing that
*worked*. The method that found it was to stop reading titles and group every open ABR by the
first `VASSAL.` frame in its trace — which is a dozen lines of script and immediately sorted 250
unclaimed reports into families, this one among them.

## Checking the tracker first worked

The [corrupt-prefs note](vassal-corrupt-prefs-startup.md) ends on being overtaken: a fix for the
same defect landed upstream while that PR was open, because the duplicate-hunting had searched the
*open issues* for reports and never the *commits* for a fix.

The check that was missing there was applied here, and paid immediately. Before any code was
written, three questions:

- is the defect still present on `upstream/main`? (yes — diffed)
- does an open PR touch these files? (two, neither related)
- does the tracker know about it? (**yes** — [#15337](https://github.com/vassalengine/vassal/issues/15337), filed three days earlier)

The third answer changed the work from "diagnose and fix" to "implement, test and verify someone
else's diagnosis, and say so". That is a smaller and better job: it took the mechanism as already
established, and spent the effort instead on the things the issue left open — whether the seeding
constructor was covered, whether older versions really can read the new output, and whether the
defect reaches real reports. All three were checkable, and checking them is what the PR actually
contributes.

The cost of asking was about four minutes.

## What this means for the utility

**It had the same bug, in code copied from the same place.** `MainWindow.seqAppend` is a port of
VASSAL's `append`, written to produce `ExtensionElement` `target` paths byte-identical to the
ones VASSAL writes — and it faithfully reproduced the defect:

```java
boolean quote = s.charAt(0) == '\\'
        || (s.charAt(0) == '\'' && s.charAt(s.length() - 1) == '\'');
```

A component whose configure name ends with a backslash produced a target the decoder cannot
split. Measured against the utility's own `ComponentPath.decode`, with the old rule and the new:

```
Maps     old-encoded=VASSAL.build.module.Map:Maps/…BoardPicker    -> 2 segments ✓
Maps\    old-encoded=VASSAL.build.module.Map:Maps\/…BoardPicker   -> 1 segment  ✗  name="Maps/VASSAL.build.module.map.BoardPicker"
Maps\    new-encoded=VASSAL.build.module.Map:'Maps\'/…BoardPicker -> 2 segments ✓
```

The whole path collapses into a single segment whose name is the rest of the path, so the graft
resolves to nothing — a Move or Copy into an extension would silently target a component that
does not exist. `seqAppend` now carries the same three-arm condition as the fixed engine, and the
round trip is verified for a name that ends with a backslash, starts with one, or contains `/`
or `:`.

The general lesson is the one worth keeping: **a faithful port inherits the original's bugs, and
goes on being faithful after the original is fixed.** The decoder here *should* stay a faithful
port, because it has to read what VASSAL wrote; the encoder should track the fixed version. The
two halves of a copied codec do not age the same way, and nothing in the code says which is
which — so the places where this project deliberately mirrors engine behaviour
([`ComponentPath`](../../src/main/java/org/vassalengine/extutil/model/ComponentPath.java),
`MainWindow.seqAppend`, `SavedGame`'s obfuscation readers) are worth re-reading whenever the
engine changes underneath them.
