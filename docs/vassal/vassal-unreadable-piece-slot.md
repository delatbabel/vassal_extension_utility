# VASSAL crash: one unreadable piece definition stops the whole module

This note documents a crash in the VASSAL engine (not in this utility), reported six times —
[#14939](https://github.com/vassalengine/vassal/issues/14939),
[#14916](https://github.com/vassalengine/vassal/issues/14916),
[#14897](https://github.com/vassalengine/vassal/issues/14897),
[#14896](https://github.com/vassalengine/vassal/issues/14896),
[#14677](https://github.com/vassalengine/vassal/issues/14677) and
[#14491](https://github.com/vassalengine/vassal/issues/14491) — and fixed by
[#15380](https://github.com/vassalengine/vassal/pull/15380). It is the second half of
[the trailing-backslash story](vassal-trailing-backslash-state.md): that fix stopped new damage
being written, this one stops existing damage being fatal.

## Symptom

The module does not open:

```
java.lang.RuntimeException: java.util.concurrent.ExecutionException:
  java.lang.IllegalStateException: No state for trait=prototype;Army
  at VASSAL.launch.Launcher.<init>(Launcher.java:164)
  at VASSAL.launch.Player.main(Player.java:56)
```

Three frames of launcher plumbing, which is all the issue body carries. The six reports group
under `Player.main` with forty other unrelated ones, and their messages all differ because each
quotes a different trait's whole type string — the same double blindness as the backslash family.

## What the logs show

Identical in all six:

```
Caused by: java.lang.IllegalStateException: No state for trait=prototype;Army
  at VASSAL.counters.Decorator.setState(Decorator.java:444)
  at VASSAL.build.widget.PieceSlot.getPiece(PieceSlot.java:240)
  at VASSAL.build.widget.PieceSlot.getI18nData(PieceSlot.java:680)
  at VASSAL.build.AbstractConfigurable.add(AbstractConfigurable.java:173)
  at VASSAL.build.Builder.build(Builder.java:83)
```

Note where this is: `AbstractConfigurable.add()` builds a component's translation data, which for
a `PieceSlot` means instantiating the piece. So **every piece in the module is built during the
buildFile walk**, and one that will not build takes the launch with it.

## Root cause

`PieceSlot.getPiece()` already knows a definition may be unreadable:

```java
      final AddPiece comm = (raw instanceof AddPiece) ? (AddPiece) raw : null;
      if ((comm == null) || comm.isNull()) {
        ErrorDialog.dataWarning(new BadDataReport("GamePiece - couldn't build piece -", pieceDefinition));
        pieceDefinition = null;
      }
      else {
        c = comm.getTarget();
        c.setState(comm.getState());      // <- can throw, and nothing catches it
```

A definition that decodes to nothing is bad module data: report it, drop it, return null. Every
caller copes, because null is a value this method has always been able to return — that is the
same property [the At-Start Stack fix](vassal-stack-empty-top-slot.md) leaned on.

A definition that decodes and *then* throws had no such route. The two outcomes mean the same
thing — this definition will not build — and only one of them was handled.

The usual failure is a state with fewer tab-separated fields than the type has traits.
`Decorator.setState()` takes one token for itself and hands the rest inward; the innermost trait
finds nothing left:

```java
    mySetState(st.nextToken());
    try {
      piece.setState(st.nextToken());
    }
    catch (NoSuchElementException e) {
      throw new IllegalStateException(Resources.getString("Decorator.no_state_for_trait") + myGetType());
    }
```

## Why these modules exist, and why they will keep existing

[#15363](https://github.com/vassalengine/vassal/pull/15363) fixed one way of producing such a
definition — a trait value ending in a backslash, which escapes the delimiter after it and merges
two fields. That fix is **on the encoding side only, deliberately**: the ambiguity is inherent, so
the decoder was left untouched and nothing already written reads differently.

Which means every module already carrying such a definition stays unreadable, permanently. The
encoder fix protects the future; nothing protected the modules that already exist. That is the
gap this fills, and it is worth noticing as a general shape: *a fix that corrects what gets
written leaves everything already written needing a second, separate decision.*

They also arrive by other routes. Two of these six are different versions of one project whose
`.vmod` is produced by its own export tooling — and the same project produced
[#14879](https://github.com/vassalengine/vassal/issues/14879)'s unparseable `zoomStart="0.25"`,
fixed in [#15375](https://github.com/vassalengine/vassal/pull/15375) as part of
[a different family](vassal-builder-load-vs-build.md). One generator writing a buildFile VASSAL
cannot quite read produces several kinds of damage, and the engine meets each in a different place
and crashes differently. Three of this month's PRs are the engine learning to survive one module's
exporter.

## The fix

Wrap the build and route a failure to the report the branch above already uses:

```java
      catch (RuntimeException e) {
        ErrorDialog.dataWarning(new BadDataReport("GamePiece - couldn't build piece -", pieceDefinition, e));
        c = null;
        pieceDefinition = null;
      }
```

The slot ends in exactly the state the sibling branch leaves it in — empty, definition dropped, no
retry — so the module opens with one piece missing and a Bad Data message naming it.

Two choices worth recording:

- **The exception becomes the report's cause.** The aim is to stop it killing the launch, not to
  hide it; the error log still names what happened.
- **`RuntimeException`, not `IllegalStateException`.** The narrow catch fixes these six and leaves
  the identical hole one trait over — `mySetState()` implementations parse numbers, so a
  `NumberFormatException` there means the same thing and would still cost the module. The branch
  this joins is about *this definition will not build*, which is what all of them mean. Catching
  broadly is defensible here precisely because the handler it joins already exists and already
  decides what that means.

### The red check

Two of four new cases fail against unmodified `main`, with #14939's own message at the line its
log records:

```
java.lang.IllegalStateException: No state for trait=prototype;Army
	at VASSAL.build.widget.PieceSlot.getPiece(PieceSlot.java:240)
```

The controls — a definition that decodes to nothing, which was already handled, and one that reads
correctly — pass both ways.

## What this means for the utility

Quite a lot, and it is the reason this note is here rather than in the engine's own tracker alone.

The utility moves `PieceSlot` elements between archives by copying their DOM subtree, and a slot's
piece definition is the element's **text content** — an opaque string it never parses (see
[Move / Copy](../../CLAUDE.md#move--copy-operation-the-core-feature); the only thing it reads out of
that text is image filenames, by tokenising on the piece-definition delimiters). So:

- **The utility will copy an unreadable piece definition without complaint**, because to it there
  is nothing wrong — it is text, and text copies fine.
- **The destination module then will not open** on a VASSAL without this fix. The failure appears
  at launch, nowhere near the Move that caused it, and the message names a trait type rather than
  the piece or the module.
- Nothing in the utility flags it. **Tools → Remove Unused Images** does not look at definitions,
  and the tree shows the slot by its `entryName` whether or not its definition is sound.

So a module assembled here from parts of another one is worth launching once in VASSAL before it
is shipped. If it refuses to open with *No state for trait=*, the fault travelled with a piece
slot and was already there in the source archive — this utility did not create it, and on a
VASSAL carrying #15380 it will no longer stop the module, only the piece.
