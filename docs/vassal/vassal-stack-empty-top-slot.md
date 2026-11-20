# VASSAL crash: Reposition Stack on a slot with no piece

This note documents a crash in the VASSAL engine (not in this utility), reported as
[#14405](https://github.com/vassalengine/vassal/issues/14405) and
[#14161](https://github.com/vassalengine/vassal/issues/14161) and fixed by
[#15379](https://github.com/vassalengine/vassal/pull/15379). The fault is small; what makes it
worth a note is that one of the two logs contains the engine diagnosing the crash seventeen
seconds before it happened.

## Symptom

Clicking **Reposition Stack** on an At-Start Stack, in the Editor:

```
java.lang.NullPointerException: Cannot invoke "VASSAL.counters.GamePiece.getState()" because "p" is null
  at VASSAL.command.AddPiece.<init>(AddPiece.java:39)
  at VASSAL.counters.PieceCloner.clonePiece(PieceCloner.java:97)
  at VASSAL.counters.PieceCloner.clonePiece(PieceCloner.java:48)
  at VASSAL.build.module.map.SetupStack$StackConfigurer.<init>(SetupStack.java:814)
  at VASSAL.build.module.map.SetupStack.configureStack(SetupStack.java:706)
```

Both reports are `--edit` sessions on modules under development — `SpaceEmpire4x_newwip.vmod` and
`7Empires0.2.0.vmod` — which is where a half-built slot lives.

## Root cause

```java
  protected PieceSlot getTopPiece() {
    final Iterator<PieceSlot> i =
      getAllDescendantComponentsOf(PieceSlot.class).iterator();
    return i.hasNext() ? i.next() : null;
  }
```

The first slot, whether or not that slot holds a piece. Both callers guard against there being no
slot and neither against the slot being empty:

```java
      mySlot = stack.getTopPiece();
      if (mySlot != null) {
        myPiece = PieceCloner.getInstance().clonePiece(mySlot.getPiece());
      }
```

`clonePiece(null)` matches none of its `instanceof` branches and falls to the last one, which
builds `new AddPiece(piece)` — and that constructor is `this(p, p.getState())`.

The guard is one level too shallow: the slot is checked, the piece in it is not. This is the third
instance of that shape in a fortnight of this tracker —
[the Deck with no Map](vassal-deck-with-no-map.md) had the null-map test in the branch that did not
need it, and [the animated dice button](vassal-animated-dice-missing-image.md) had a null check
that could only ever throw.

Notably, `View` — the other caller, which draws the stack — is already written for a null piece:
`myPiece == null` is tested at four separate places in it. Only `StackConfigurer` cloned.

## The log that diagnosed itself

`PieceSlot.getPiece()` returns null in two cases: nobody has defined a piece yet, or the
definition cannot be decoded. The second case it reports itself:

```java
      if ((comm == null) || comm.isNull()) {
        ErrorDialog.dataWarning(new BadDataReport("GamePiece - couldn't build piece -", pieceDefinition));
        pieceDefinition = null;
      }
```

And [#14161](https://github.com/vassalengine/vassal/issues/14161)'s attached log has exactly that,
seventeen seconds before the crash:

```
08:15:38,988  WARN  VASSAL.tools.ErrorDialog -  Source:  Error: GamePiece - couldn't build piece -.
08:15:55,989  ERROR VASSAL.tools.ErrorDialog -
java.lang.NullPointerException: Cannot invoke "VASSAL.counters.GamePiece.getState()" because "p" is null
```

So the engine detected the unreadable slot, said so, nulled its definition — and then crashed on
that same slot when the user opened the dialog. The two lines are seventeen seconds and one user
action apart, and the first is the cause of the second.

This is the strongest example yet of the rule in
[reading-abr-error-logs.md](reading-abr-error-logs.md): **the useful line is often above the
trace.** Here it is not merely context, it is the diagnosis, and the issue body — which quotes
only the four stack frames — does not contain it.

## The fix

```java
    for (final PieceSlot slot : getAllDescendantComponentsOf(PieceSlot.class)) {
      if (slot.getPiece() != null) {
        return slot;
      }
    }
    return null;
```

Fixing `getTopPiece()` rather than guarding the clone means both callers get what their existing
null check already implied, with no change to either, and a Stack whose slots are all empty
answers null — a case both already handle. It also makes the dialog draw the first piece there
*is*, rather than nothing, when an earlier slot is empty. Guarding the clone instead would have
left `getTopPiece()` returning something that is not a top piece, for the next caller to discover.

### Testing a dialog you cannot construct

`StackConfigurer extends JFrame`, so the crashing constructor cannot be built in a headless test,
and `vassal-app`'s surefire runs headless. That was reason enough to defer this fix once, because
a guard without a red check is a guess.

The answer was to stop trying to test the dialog. The test takes the top piece and clones it —
which is the first thing the dialog does with it — and against unmodified `main` that produces the
reported exception with the reported frames:

```
java.lang.NullPointerException: Cannot invoke "VASSAL.counters.GamePiece.getState()" because "p" is null
	at VASSAL.command.AddPiece.<init>(AddPiece.java:39)
	at VASSAL.counters.PieceCloner.clonePiece(PieceCloner.java:97)
	at VASSAL.counters.PieceCloner.clonePiece(PieceCloner.java:48)
```

Three of the five cases fail before the fix; the two controls — a stack whose first slot has a
piece, and a stack with no slots at all — pass both ways. The general point: when the crashing
frame is unreachable from a test, the frame *below* it usually is, and reproducing the exception
there is worth more than asserting the guard exists.

## What this means for the utility

Little directly — the utility never instantiates a `PieceSlot`'s piece; it reads the slot's
definition text as a string when collecting
[image references](../../CLAUDE.md#move--copy-operation-the-core-feature), and an empty or
unreadable definition simply contributes no image names.

It is worth knowing when moving an At-Start Stack between a module and an extension, though. The
utility will copy a slot with an empty or corrupt definition as happily as any other, because to
it the definition is just text. On a VASSAL without this fix, the destination archive then has a
stack whose Reposition Stack button crashes the Editor. **Tools → Remove Unused Images** will not
flag it and nothing else here will either, so a stack that came from a work-in-progress module is
worth opening in the VASSAL Editor once.
