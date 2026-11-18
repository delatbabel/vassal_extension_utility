# VASSAL bug: the Multi-Piece Editor edits by position, and four things moved the positions

This note documents a bug in the VASSAL engine (not in this utility), reported three times
between 2022 and 2026 and fixed by
[#15365](https://github.com/vassalengine/vassal/pull/15365).

It is this folder's clearest case of a fix that was made **once, for one of four methods with
the same shape** — and of a frame group that had to be split by its *second* frame before any
of it made sense.

## Symptom

The editor throws while you are editing a trait, and the trace has no obvious connection to
what you were doing:

```
java.util.NoSuchElementException
 at VASSAL.tools.SequenceEncoder$Decoder.nextToken(SequenceEncoder.java:248)
 at VASSAL.counters.Embellishment.originalSetType(Embellishment.java:337)
 at VASSAL.counters.Embellishment.mySetType(Embellishment.java:206)
 at VASSAL.counters.MassPieceDefiner$Def.edit(MassPieceDefiner.java:181)
```

`nextToken` throws `NoSuchElementException` when a decoder is asked for a field that is not
there. So a Layer trait was handed a type string with too few fields in it — which is a
strange thing for an editor to do, since the editor is where type strings come *from*.

## The family, and why it had to be split

Grouping the open automated reports by their first `VASSAL.` frame put seven under
`SequenceEncoder$Decoder.nextToken`. That group is not one fault. The *second* frame splits it
cleanly into three:

| second frame | reports | what it is |
|---|---|---|
| `MassPieceDefiner$Def.edit` | [#12377](https://github.com/vassalengine/vassal/issues/12377), [#13607](https://github.com/vassalengine/vassal/issues/13607), [#15027](https://github.com/vassalengine/vassal/issues/15027) | this bug |
| `BasicCommandEncoder.decode` | [#12329](https://github.com/vassalengine/vassal/issues/12329), [#12354](https://github.com/vassalengine/vassal/issues/12354) | loading a saved game or log; one reporter says they killed the session mid-save |
| `PlayerRoster.decode` | [#14958](https://github.com/vassalengine/vassal/issues/14958) | one report, loading a save |

Plus one `StackOverflowError` ([#11249](https://github.com/vassalengine/vassal/issues/11249))
that happens to pass through the same method.

The lesson generalises the one in
[vassal-record-module-load-crash.md](vassal-record-module-load-crash.md): the first `VASSAL.`
frame is a good *bucket* because it is the first thing in the trace that is ours, but it is a
bucket, not a diagnosis. `SequenceEncoder` is called from everywhere, so bucketing on it mixes
an editor bug, a file-format problem and a recursion. One frame deeper, the three separate.

The three that are this bug span three VASSAL versions and three unrelated modules, which is
what says the fault is the engine's rather than any module's:

| issue | version | what the reporter was doing |
|---|---|---|
| [#12377](https://github.com/vassalengine/vassal/issues/12377) | 3.6.19 | removed a duplicated Layer, pasted a prototype, renamed it |
| [#13607](https://github.com/vassalengine/vassal/issues/13607) | 3.7.14 | fixing Restricted Access on a card |
| [#15027](https://github.com/vassalengine/vassal/issues/15027) | 3.7.26 | changing the name of a Prototype |

All three are the Multi-Piece Editor — **right-click a Piece Window or folder → Edit all
contained pieces**.

## Root cause

`MassPieceDefiner` shows you one `PieceDefiner` and keeps a hidden one per piece in step with
it. Its whole premise is that the pieces carry *identical trait lists*. The constructor enforces
that at the start, dropping any piece whose trait classes do not match the first one's:

```java
final List<Class<? extends GamePiece>> template = getTemplate();
for (int i = 0; i < definers.size(); ++i) {
  final GamePiece p = definers.get(i).definer.getPiece();
  if (!matchesTemplate(p, template)) {
    definers.remove(i--);
  }
}
```

From then on every edit is applied **by position**. `Def.edit(index)` takes the trait at index
N of the *visible* list, asks it for its editor, and hands that editor's type string to the
trait at index N of *its own* piece:

```java
final Object o = MassPieceDefiner.this.inUseModel.elementAt(index);
final PieceEditor template = ((EditablePiece) o).getEditor();
final EditablePiece myPiece = (EditablePiece) this.inUseModel.elementAt(index);
myPiece.mySetType(template.getType());
```

There is no check that the two are the same kind of trait, because by construction they are.
The correctness of the whole dialog rests on every structural change being applied to every
piece at the same position.

Four of them were not.

### 1. Paste read each piece's own selection

```java
protected void paste() {
  final int selectedIndex = inUseList.getSelectedIndex();
  ...
  moveDecorator(inUseModel.size() - 1, selectedIndex + 1);
}
```

`inUseList` is the definer's own In-Use list widget. Only the visible definer has a selection
the user set; the hidden ones still have whatever `setPiece()` selected when the dialog opened,
which is index 0. So:

```java
protected void paste() {
  super.paste();                    // visible: pastes at the user's selection
  for (final Entry e : definers) {
    e.definer.paste();              // each piece: pastes wherever ITS list is pointing
  }
}
```

With a four-trait piece, copying index 1 and clicking index 3 before pasting:

```
visible : [BasicPiece, Marker, Embellishment, Marker, Marker]
piece 1 : [BasicPiece, Marker, Marker, Embellishment, Marker]
piece 2 : [BasicPiece, Marker, Marker, Embellishment, Marker]
```

From index 2 the lists disagree. At index 3 the editor holds a `Marker` where every piece holds
an `Embellishment` — and a Marker's type string (`mark;Side`) has nothing like the field count
`Embellishment.originalSetType` expects.

### 2 and 3. Move to Top and Move to Bottom were never fanned out at all

`MassPieceDefiner` overrides `moveDecoratorUp(int)` and `moveDecoratorDown(int)`. It does not
override `moveDecoratorTop(int)` or `moveDecoratorBottom(int)`, which `PieceDefiner` grew
later. Using those two buttons moves the trait in the piece you can see and in none of the
others — a one-click way to desynchronise everything, with no error at the time.

### 4. The one-argument addTrait applied the trait twice

```java
// PieceDefiner
protected void addTrait(Decorator c) {
  addTrait(c, inUseList.getSelectedIndex());   // virtual: lands on the override below
}

// MassPieceDefiner
@Override
protected void addTrait(Decorator c) {
  super.addTrait(c);              // already fans out, via that virtual call
  for (final Entry e : definers) {
    e.definer.addTrait(c);        // ... and again
  }
}
```

Every piece got the trait twice, at two different positions. Nothing in the UI reaches the
one-argument form today — `doAdd` calls the two-argument one — so this has never been reported.
It is still wrong, and the override exists only to do it.

## Why it went unnoticed, and the fix that was already there

The two-argument `addTrait` override is not original. It was added in February 2021 by
[eddc197e8](https://github.com/vassalengine/vassal/commit/eddc197e8cbd983baca728a1c0505dcac0faeb82),
"13981 Exception generated when using Edit All Contained Pieces" — the same fault, found the
same way, fixed for one method:

```java
+  @Override
+  protected void addTrait(Decorator c, int insertIndex) {
+    super.addTrait(c, insertIndex);
+    for (final Entry e : definers) {
+      e.definer.addTrait(c, insertIndex);
+    }
+  }
```

That is exactly the shape of the fix the other three needed, sitting in the same file, six lines
above `paste()`. This is the same pattern as
[vassal-corrupt-prefs-startup.md](vassal-corrupt-prefs-startup.md), where the handling existed
one class away from where it was needed — and it is worth stating as a habit rather than a
coincidence: **when a fix is "propagate the caller's index instead of reading the widget",
check every sibling method that takes an index.** There were three.

The reason it stayed open for five years after the first fix is that nothing *says* when the
lists drift. Paste at the wrong depth is silent; Move to Top moving one piece is silent. The
exception comes later, from a different trait, during an unrelated edit — which is why all three
reporters described a step (renaming a prototype, fixing Restricted Access) that has nothing to
do with the operation that actually broke it.

## The fix

- `PieceDefiner.paste()` splits into `paste()` and `paste(int)`, and `MassPieceDefiner` resolves
  the position once, from the list the user can see, and gives that one number to every piece.
  A new `getSelectedTraitIndex()` is the single reader of the selection, since `inUseList` is
  private and a subclass cannot otherwise ask.
- `moveDecoratorTop(int)` and `moveDecoratorBottom(int)` are overridden and fanned out, in the
  same shape as `moveDecoratorUp` / `moveDecoratorDown`.
- The one-argument `addTrait` override is deleted. `PieceDefiner.addTrait(Decorator)` already
  dispatches to the correct two-argument override.
- `Def.edit()` additionally checks that the trait it is about to overwrite is the same class as
  the one being edited, and leaves the piece alone if it is not.

That last one is **containment, not the fix**, and it is worth being explicit about why it is
there at all. The argument against it is real: it turns a loud failure into a silent skip, and
a silent skip is how a bug lives for five years. The argument for it won because of what the
alternative writes to disk. `mySetType` on a mismatched trait does not always throw — a Marker
handed a Layer's type string takes it and stores nonsense. The crash in these three reports is
the *lucky* case. A guard that declines to edit a piece whose trait list has already drifted
costs one unedited piece; no guard costs a corrupted module, saved without complaint.

## The red check

`MassPieceDefinerTest` is the first test this class has ever had: six tests, all six failing
against the unmodified base and passing with the fix.

```
RED   (upstream) : Tests run: 6, Failures: 5, Errors: 1
GREEN (fixed)    : Tests run: 6, Failures: 0, Errors: 0
```

They name no new API, so the ordinary red check works — revert the two source files and rerun.
(Contrast [vassal-corrupt-prefs-startup.md](vassal-corrupt-prefs-startup.md), where the fix
introduced API the tests needed and the red check had to be done with a throwaway written
against the old one.)

Three things made this testable at all, none of them obvious beforehand:

- **The dialog runs headless.** `PieceDefiner extends JPanel`, and a `JPanel` is not a `Window`,
  so nothing in building or driving it needs a display. Only `edit()` on the *visible* definer
  opens a `Dialog`, and these tests never call it. CI has no X display and no `xvfb`, so this is
  the difference between a test and no test.
- **A stubbed `GameModule` is enough**, but it needs five things, each discovered by running
  into it: `getPrefs()` (an `Integer` for the trait-list split), `getResourcePathFinder()` and
  `getDataArchive()` (the available-traits list instantiates every trait, and a few load icons),
  and both `createPiece` overloads answered by a real `BasicCommandEncoder` — without which
  `PieceCloner` returns a chain whose innermost piece is `null`.
- **Assert through `save()` and the slots, not the internals.** `MassPieceDefiner.save()` writes
  each definer's piece back into its `PieceSlot`, so comparing each slot's trait list with the
  visible definer's tests the invariant on the thing that actually reaches the module.

### A Swing trap found while writing them

The "nothing selected" test passed against the broken code, which was wrong, and the reason is
worth keeping:

```java
list.setSelectedIndex(-1);   // does nothing at all
```

`JList.setSelectedIndex` forwards to `ListSelectionModel.setSelectionInterval(-1, -1)`, and
`DefaultListSelectionModel` returns immediately if either index is negative. The selection was
never cleared, so the test had been exercising index 0 — where the visible definer and the
hidden ones happen to agree, which is precisely the one case that was never broken. Clearing it
properly with `clearSelection()` turned the test red, as it should always have been.

The general form: **a test that passes before the fix is not a milder test, it is a test that
is not testing what it says.** Checking why it passed is what found this.

## What this means for the utility

Nothing in the utility edits traits, so this exact bug cannot occur here. The transferable part
is the shape of the invariant, which the utility does rely on in two places:

- **Move/Copy** resolves a destination by walking parallel structures in two documents
  (`findMatchingChild` / `sameElement`), and matches on tag name plus attributes rather than on
  position. That is the right call, and this bug is the reason: positional correspondence
  between two structures is an assumption that nothing re-checks once it stops being true.
- **`ComponentPath.findChild`** already learned the same lesson in the other direction — it
  falls back to "the only child of that class" for an unnamed path segment, because matching on
  a name that `ComponentNode` had to *guess* was silently resolving to the wrong component. See
  [Combine / Uncombine extensions](../../CLAUDE.md#combine--uncombine-extensions).

The rule both encode: **when one structure is used to index into another, check at the point of
use that they still correspond.** Establishing it once at construction, as `matchesTemplate`
does, is not the same thing — it only tells you the assumption was true before any editing
started.
