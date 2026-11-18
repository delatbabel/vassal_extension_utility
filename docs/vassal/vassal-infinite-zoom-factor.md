# VASSAL bug: a zoom-to-fit on a map with no boards sets the zoom to infinity

Diagnosis of six automated bug reports, fixed by
[#15364](https://github.com/vassalengine/vassal/pull/15364), which closes
[#15013](https://github.com/vassalengine/vassal/issues/15013).

## Symptom

A map cannot be drawn. Every repaint throws:

```
java.lang.RuntimeException: Dimensions (width=2147483647 height=2147483647) are too large.
    Image=images/HQ_04-CO.png (75 x 75) scale=Infinity
  at VASSAL.tools.imageop.ScaleOpBitmapImpl.fixSize
  at VASSAL.tools.opcache.OpCache.getFuture
  …
  at VASSAL.tools.imageop.ScaledImagePainter.draw
  at VASSAL.counters.BasicPiece.draw
  …
  at VASSAL.build.module.Map.drawPiecesInRegion
  at VASSAL.build.module.Map$View.paint
```

## The number was the diagnosis

2147483647 is `Integer.MAX_VALUE`, and it appears for **both** width and height, from a 75×75
image. No finite arithmetic on a 75-pixel image lands exactly on `Integer.MAX_VALUE` twice by
accident; a `double` that overflows an `int` cast does, every time. So before reading any VASSAL
source the shape of the fault was already fixed: something multiplied the image's size by a
number that was not finite.

`ScaleOpBitmapImpl.fixSize()`:

```java
final Dimension sd = sop.getSize();
size = new Dimension((int)(sd.width * scale), (int)(sd.height * scale));
```

`(int) (75 * Infinity)` is `Integer.MAX_VALUE`. That is the whole of it.

## Root cause

`Zoomer`'s three fit actions each divide a view extent by a map extent:

```java
else if (FIT_WIDTH.equals(cmd)) {
  final Dimension vd = map.getView().getVisibleRect().getSize();
  final Dimension md = map.mapSize();
  setZoomFactor(vd.getWidth() / md.getWidth());
}
```

and `Map.mapSize()` unions the boards into an empty rectangle:

```java
final Rectangle r = new Rectangle(0, 0);
for (final Board b : boards) r.add(b.bounds());
r.width += edgeBuffer.width;
r.height += edgeBuffer.height;
return r.getSize();
```

**A map with no boards measures (0, 0).** `Dimension.getWidth()` returns a `double`, so this is a
`double` division: a zero denominator gives `Infinity`, quietly, rather than the
`ArithmeticException` integer division would have raised. Both extents zero gives `NaN`.

That is one of those cases where the stricter-looking type is the more dangerous one. An `int`
division by zero fails at the point of the mistake and names it. A `double` division by zero
succeeds, and the mistake surfaces later, somewhere else, as a number nobody can trace back.

### Why nothing downstream caught it

Because the guard that exists covers one end of the invalid range and not the other.
`Zoomer.State.setZoom`:

```java
public void setZoom(double z) {
  if (z <= 0.0) {
    // This should never happen, it's just a kludge to make sure that
    // we continue having valid data even if our caller is wrong.
    z = Double.MIN_VALUE;
  }
```

The comment says exactly what the code is for: *keep the data valid even if the caller is wrong*.
And `Infinity <= 0.0` is false. So is `NaN <= 0.0` — every comparison with NaN is false, which is
how NaN defeats range checks written as inequalities.

The factor is stored, and from then on every repaint of every piece on that map scales by it. The
map is unusable until the zoom is changed by some other route.

Storing it does a second thing: `Arrays.binarySearch(levels, Infinity)` reports an insertion point
past the end of the array, so the level index is left equal to `levels.length` — one past the last
valid index. The red check caught that independently (`level 3 of 3`), which is the sort of thing
a test written from the mechanism finds and a test written from the symptom does not.

`ScaledImagePainter.draw` has the same gap one layer up —

```java
if (srcOp == null || scale <= 0) return;
```

— and was deliberately left alone, for reasons under [the fix](#the-fix).

## The instrumentation is what cracked it

The five oldest reports say only

```
Dimensions (width=2147483647 height=2147483647) are too large
```

The newest says

```
Dimensions (width=2147483647 height=2147483647) are too large. Image=images/HQ_04-CO.png (75 x 75) scale=Infinity
```

The difference is `95e32f6c7`, which added the image name, its real size and the scale to the
message, and shipped in 3.7.19. One later report then carried the answer in its title.

This is worth recording because these notes have otherwise been a catalogue of how little an
automated report tells you. Here a maintainer, unable to reproduce a fault, **widened the
exception message and waited**, and the next report was diagnosable in a single line. That is the
cheapest debugging tool in the file, and the one most easily skipped in favour of guessing.

## The family

| issue | version | | issue | version |
|---|---|---|---|---|
| [#13741](https://github.com/vassalengine/vassal/issues/13741) | 3.7.15 | | [#14186](https://github.com/vassalengine/vassal/issues/14186) | 3.7.16 |
| [#14022](https://github.com/vassalengine/vassal/issues/14022) | 3.7.16 | | [#14525](https://github.com/vassalengine/vassal/issues/14525) | 3.7.20 |
| [#14121](https://github.com/vassalengine/vassal/issues/14121) | 3.7.16 | | [#15013](https://github.com/vassalengine/vassal/issues/15013) | 3.7.26 |

Only #15013 is marked fixed. The other five predate the `scale=` diagnostic, so none of them
*states* that the scale was infinite, and an enormous but finite zoom would clamp to
`Integer.MAX_VALUE` as well — the `Other…` zoom dialog accepts any positive number the user types.
They carry the identical message, the identical value and the identical four frames, which is
strong, and each has been commented with that reasoning so a maintainer can close them. It is not
the same thing as proof and is not written down as if it were.

The four other open reports under `OpCache.getFuture` are unrelated: an `OutOfMemoryError`, a
missing Batik class, an `InterruptedException` and a `URISyntaxException`. Grouping by the first
VASSAL frame gathers a *call site*, not a cause, and the last step of using it is always to read
the messages and throw back what does not belong.

## The fix

**The cause.** The three fit actions no longer divide by a map extent of zero. There is nothing to
fit to, so they do nothing.

**The backstop.** `setZoom`'s guard now covers any factor that is not a positive, finite number,
and keeps the zoom already in force rather than coercing. There is no zoom that an infinite or NaN
factor could sensibly *become*; the previous coercion to `Double.MIN_VALUE` (≈4.9e-324) produced
data that was valid only in the sense that it would not throw — a zoom of effectively zero, which
renders nothing. That reading of the comment's intent over its letter is flagged in the PR as the
one judgement call in the change.

`ScaledImagePainter.draw`'s `scale <= 0` was left as it is, deliberately. Extending it would stop
the bug dialog without fixing anything, and the throw in `fixSize()` was put there on purpose to
catch oversized images — quietly swallowing a bad scale one layer above it would blind the very
diagnostic that made this diagnosable. Noted in the PR as an observation for the maintainers
rather than taken as licence.

### The red check

Five tests, four red before the change:

```
anInfiniteZoomIsNotStored              zoom was Infinity
aNaNZoomIsNotStored                    zoom was NaN
anInfiniteZoomLeavesTheExistingZoom…   expected: <2.0> but was: <Infinity>
anInfiniteZoomLeavesTheLevelInRange    level 3 of 3
```

The fifth is a control: an ordinary factor is still stored. 824 tests pass, Checkstyle clean.

The fit actions themselves are not unit-tested — the division sits inside
`ZoomMenu.actionPerformed`, which needs a live `Map` and a realised view. `setZoom` is the choke
point every path goes through, so that is where the test is aimed, and the PR says so rather than
implying the guard itself is covered.

## A guard that covers one end of the range

This is the second defect in a day with precisely this shape. The other is in
[vassal-trailing-backslash-state.md](vassal-trailing-backslash-state.md): `SequenceEncoder.append`
protects a value that *starts* with a backslash and not one that *ends* with it. Here `setZoom`
rejects a factor that is too small and not one that is too large.

Both are defensive code written by someone who had the right instinct, reached for the case in
front of them, and left the mirror image open. Neither is an oversight a reviewer would catch by
reading the diff, because in both the guard looks complete and *is* complete for the example that
prompted it.

Worth carrying as a review habit: when a guard rejects invalid input, ask what the **other** ways
of being invalid are — the opposite end, the empty case, and for floating point the three values
that make every inequality false or true regardless. A range check written as `x <= 0` admits
`Infinity` and `NaN`; one written as `!(x > 0)` admits `Infinity` but rejects `NaN`; only an
explicit finiteness test admits neither.

## The family this is not

`IllegalArgumentException: Piece is not on this map` was the better-looking candidate when
choosing what to work on next: 31 reports, six open, every trace identical, all of it VASSAL's own
code. It was dropped, which is worth recording as much as the fix is.

[#12033](https://github.com/vassalengine/vassal/issues/12033) is the maintainers' consolidated
parent record for it — five other reports were closed against it by hand — and
[#14347](https://github.com/vassalengine/vassal/pull/14347), merged, does nothing but widen the
exception message so the next report will say which map the piece is actually on. They are running
the same play that cracked the zoom bug, and the data has not arrived yet: every open report is
from 3.7.15–3.7.18, and the diagnostic shipped in 3.7.19.

A plausible guard dropped into `MenuDisplayer.maybePopup` would have stopped the crash and
destroyed the evidence they are waiting for. Knowing that a fault is already being instrumented by
someone else is a reason not to fix it yet.

## What this means for the utility

Nothing to fix, this time, and the check is worth recording anyway since the last two notes both
found the engine's bug mirrored here.

The utility's only ratio of this kind is the download progress callback in
`DownloadModuleDialog`:

```java
if (total > 0) {
    setProgress((int) Math.min(100, done * 100 / total));
}
```

— already guarded, and integer arithmetic besides, so a zero would have thrown at the point of the
mistake rather than travelling. The general point stands for anything added later: **a `double`
division by zero does not fail, it propagates**, and `Infinity` and `NaN` pass straight through
every range check written as an inequality.
