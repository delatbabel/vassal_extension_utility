# VASSAL crash: one missing die face image stops the module loading

This note documents a crash in the VASSAL engine (not in this utility), reported as
[#15368](https://github.com/vassalengine/vassal/issues/15368),
[#15367](https://github.com/vassalengine/vassal/issues/15367) and
[#15366](https://github.com/vassalengine/vassal/issues/15366), and fixed by
[#15369](https://github.com/vassalengine/vassal/pull/15369). It is triggered by an Animated Dice
Button one of whose die face images is not in the module.

## Symptom

The module does not load. The launcher puts up a bug dialog, and all three reports quote the same
four frames:

```
java.lang.NullPointerException: Cannot invoke "VASSAL.tools.imageop.ImageOp.getImage()" because "img" is null
  at VASSAL.build.module.AnimatedDiceButton$BaseResultsIcon.getResultIcon(AnimatedDiceButton.java:579)
  at VASSAL.build.module.AnimatedDiceButton$AnimResultsIcon.doResults(AnimatedDiceButton.java:815)
  at VASSAL.build.module.AnimatedDiceButton$BaseResultsIcon.setResults(AnimatedDiceButton.java:614)
  at VASSAL.build.module.AnimatedDiceButton.addTo(AnimatedDiceButton.java:455)
```

Two modules between them, both on 3.8.2, three reports in a single day.

## What the log adds, and it is not the fault

Unlike most of the reports in this directory, the four quoted frames name the fault precisely: the
class, the method and the line that threw. What the attached log
([reading-abr-error-logs.md](reading-abr-error-logs.md)) supplies is the *trigger*, in the two lines
immediately above the trace:

```
WARN  VASSAL.tools.ErrorDialog -  Source: <a die face image> Error: Image not found.
ERROR VASSAL.build.Builder - Error building VASSAL.build.module.AnimatedDiceButton <name>
```

The first says which of the module's images could not be loaded. The second is why this is not a
dice button that fails to draw:

```java
        catch (RuntimeException | Error ex) {
          final Node attr = child.getAttributes().getNamedItem("name");
          logger.error("Error building " + child.getNodeName() + ...);
          throw ex;
        }
```

`Builder.build()` logs *Error building …* and **rethrows**. The icons are built from
`AnimatedDiceButton.addTo()`, which runs during module construction, so the throw comes out through
the launch worker and nothing opens. One image missing from a ZIP, and the module is refused.

`AnimatedDiceButton` is new in 3.8.0 (added 2026-09-14), which is why there is no history of this.

## Root cause

`BaseResultsIcon.getFaceImage()` is documented *"May return null"* and has three ways of doing it:

```java
    protected ImageOp getFaceImage(int diceNo, int face) {
      if (diceNo >= dice.size()) return null;          // no such die
      final String imageName = dice.get(diceNo).getImageName(face);
      if (imageName.length() <= 0) return null;        // no image name
      final ImageOp sop = Op.load(imageName);
      if (sop.getImage() == null) return null;         // not in the module  <-- the reported case
      return sop;
    }
```

Its one caller for the rolled face then dereferenced the result:

```java
    protected ImageOp getResultIcon(int diceNo, int result) {
      final ImageOp img = getFaceImage(diceNo, result);

      if (img.getImage() == null) return null;         // line 579
      return img;
    }
```

The intent is not in doubt. `getResultIcon`'s own javadoc says *"May return null"*;
`ButtonResultsIcon.doResults()` guards its result with `if (icon != null)`;
`AnimResultsIcon.doResults()` guards the final frame the same way. One method forgot, and it is the
one that every path goes through.

The check it forgot to guard is also **redundant**. `getFaceImage()` has already returned null for
an image it could not load, so `img.getImage()` can never be null when `img` is not — the only
thing that line could ever do was throw, in precisely the case the method below it exists to
report.

## Why the one-line fix is not the fix

Delete line 579 and the crash moves three lines on. `AnimResultsIcon.doResults()` builds the
animation out of the same null-returning calls and assumes none of them does:

```java
        for (final int j : frames) {
          final ImageOp curr;
          if (j == results[i])
            curr = icons[k++][i] = icon;                             // may be null
          else
            curr = icons[k++][i] = rotateImage(getFaceImage(i, j));  // Op.rotate(null, a) throws
          widths[i]   = Math.max(widths[i],  curr.getWidth());       // NPE
          heights[i]  = Math.max(heights[i], curr.getHeight());
        }
```

`Op.rotate` dispatches to `OrthoRotateOpBitmapImpl` or `RotateScaleOpBitmapImpl`, and both open with
`if (sop == null) throw new IllegalArgumentException()`. So an *intermediate* face whose image is
missing throws before the result face is ever measured, and a result face that is missing throws at
`curr.getWidth()`. Three faults, one loop.

Nulls are safe from there on, which is what makes "leave the frame empty" the right answer rather
than a fudge: `doPaint()` skips a null icon, and both `getIconWidth()` and `getIconHeight()` skip
null entries in the grid. The grid was always allowed to have holes; only the code that fills it
was not.

### Three more on the same path

All reached from the same `addTo()`, none of them in any of the three reports:

- **A face that was never given an icon.** `getFaceImage()` reads `.length()` on the image name, but
  `SpecialDieFace.imageName` is declared with no initial value, and that class's own
  `addLocalImageNames()` tests it for null. So a face with no icon answers `null`, not `""`, and
  the guard meant to catch exactly that case is what throws.

- **A die with no faces, or with one.** `getFrameNos()` picks the intermediate frames from the faces
  other than the one rolled:

  ```java
      final List<Integer> possible = new ArrayList<>(nfaces - 1);
      ...
      while (cur < steps) {
        Collections.shuffle(possible, ran);
        for (final Integer st : possible) { ...; cur++; ... }
      }
  ```

  With no faces that is `new ArrayList<>(-1)` — *Illegal Capacity: -1*. With exactly one face the
  pool is empty, the inner loop never runs, `cur` never advances, and the `while` **spins for
  ever**: the module load hangs, with no bug dialog and no error report of any kind. A die with no
  faces is not hypothetical — `SpecialDie.getImageName()` carries the comment *"No Faces may be
  defined"*, and `AnimatedDiceButton.DR()` already tests `faceCount == 0` before rolling.

- **More results than dice.** `getFrameNos()` indexes `dice` directly, while `setResults()`
  explicitly warns that it may have been handed more results than there are dice — and then calls it
  anyway. `decode()` builds that array from a `ShowAnimResults` command, which carries as many
  results as the *sender's* copy of the module rolled, so this one is reachable in play and not only
  at load.

## The fix

[#15369](https://github.com/vassalengine/vassal/pull/15369) covers the whole path:
`getResultIcon()` becomes the delegation it always was; `doResults()` lets any frame be null;
`getFaceImage()` accepts a null image name; `getFrameNos()` tolerates a short `dice` list and holds
the rolled face for every step when there is nothing to animate through.

Deliberately not changed: `AnimResultsIcon.getIconWidth()` returns 0 when nothing could be drawn,
where its sibling `ButtonResultsIcon` floors the same calculation at the default 25. That is a
sizing question rather than a crash, and changing it would move the layout of modules that work.

## The red check

`AnimatedDiceButtonTest` is the class's first test. Two things made it awkward, and both are worth
recording:

- **The icons are private inner classes with no accessor**, so they are reached by reflection —
  `Class.forName(AnimatedDiceButton.class.getName() + "$AnimResultsIcon")` and its implicit
  outer-instance constructor.
- **The button's own constructor opens a `JDialog`**, which throws `HeadlessException` in a build
  with no display, so the instance is made without running it (`mock(…, CALLS_REAL_METHODS)` leaves
  the constructor and the field initialisers unrun) and the four fields it would have set are filled
  in by reflection. This is the same headless trap that cost a red CI run in
  [vassal-multi-piece-editor-drift.md](vassal-multi-piece-editor-drift.md); here it was designed
  around from the start.

The tests use a face with an *empty* image name rather than an absent archive entry: both converge
on the identical null inside `getFaceImage()`, and the first needs no `GameModule` and no
`DataArchive`.

Against the unfixed code all six fail:

| test | failure |
|---|---|
| `aFaceWithNoImageDoesNotCrashTheAnimationBug15368` | `NullPointerException: Cannot invoke "…ImageOp.getImage()" because "img" is null` |
| `aFaceWithNoImageDoesNotCrashTheButtonIcon` | same |
| `moreResultsThanDiceDoesNotCrashTheAnimation` | same |
| `aFaceWithNoImageNameDoesNotCrashTheAnimationBug15368` | `NullPointerException: Cannot invoke "String.length()" because "imageName" is null` |
| `aDieWithNoFacesDoesNotCrashTheAnimation` | `IllegalArgumentException: Illegal Capacity: -1` |
| `aDieWithOneFaceDoesNotHangTheAnimation` | times out after 10 s |

— the first reproducing the reported message verbatim. With the fix, 6 of 6 pass; the full suite is
825 tests, 0 failures, headless, with 0 Checkstyle violations.

### A dead constant found while writing them

The first red run came back with the *wrong* red: every case failed on the null image name, and
none on the reported NPE. The reason is that `SpecialDieFace` declares two constants for the image,

```java
  public static final String ICON = "icon";
  public static final String IMAGE = "image";
```

and only `ICON` is wired into `setAttribute()`, `getAttributeValueString()` and
`getAttributeNames()`. `IMAGE` is read by nothing. Setting it is silently ignored — which is how the
test that meant to configure a *blank* image name was in fact configuring *no* image name, and
exercising the fault next door to the one it was named after. A constant that no code reads is not
documentation; it is a trap with a plausible name on it.

## What this means for the utility

Nothing directly: the utility does not instantiate module components, and an Animated Dice Button is
just another element of the build tree to it.

It matters when moving one between a module and an extension. The button's dice are
`SpecialDie`/`SpecialDieFace` children whose face images are ordinary image references, collected by
`ComponentNode.collectImageReferences()` from the `icon` attribute like any other — so a Move or
Copy carries them. But the three reports here are modules that simply **shipped without** one of
those images, which is the same condition a hand-edited archive can be left in, and until a build
carrying [#15369](https://github.com/vassalengine/vassal/pull/15369) is in general use the
consequence is that the module will not open at all. **Tools → Remove Unused Images** is the one
operation to be careful with: it is deliberately conservative, but an image referenced only from a
die face is referenced by exactly one attribute, and if it were ever dropped the module would not
degrade — it would refuse to load.
