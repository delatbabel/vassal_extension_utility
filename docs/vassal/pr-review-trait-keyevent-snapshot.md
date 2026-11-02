# Review — #15257, "Only snapshot piece state when Dynamic Property / Layer handle the keystroke"

Review of [vassalengine/vassal#15257](https://github.com/vassalengine/vassal/pull/15257) (`perf/trait-keyevent-snapshot-3.8` → master, riverwanderer, +188/−2 across 4 files).

Static review only: the PR branch was read and cross-checked against the engine source, but its new tests were not executed here, so nothing below rests on a test run.

---

## What the change does

Both `DynamicProperty.myKeyEvent` and `Embellishment.myKeyEvent` previously opened with an unconditional `new ChangeTracker(this)`. A `ChangeTracker` serialises the state of the **whole outermost piece** — once on construction and again on `isChanged()`/`getChangeCommand()` — and every keystroke delivered to a piece is offered to **every** trait in its chain. So a counter with a hundred traits paid up to a hundred pairs of whole-piece serialisations per keypress, for the sake of the one or two traits that actually acted.

The PR defers the snapshot to the strokes the trait really handles:

- **`DynamicProperty`** creates the tracker lazily, inside the `dkc.matches(stroke)` branch, and returns `tracker == null ? null : tracker.getChangeCommand()`.
- **`Embellishment`** gains a private `handlesKeyStroke(KeyStroke)` predicate and early-returns `null` from `myKeyEvent` when it is false.

This is the right fix, and the right shape for it.

## Why it is safe

Each of these was checked against the engine source on the PR branch:

| Concern | Finding |
|---|---|
| Can `myKeyEvent` return null? | Yes. `Decorator.keyEvent` (`Decorator.java:610`) already does `final Command c = myKeyEvent(stroke); return c == null ? piece.keyEvent(stroke) : c.append(piece.keyEvent(stroke));` |
| Does anything call `myKeyEvent` directly? | No. `grep -rn "\.myKeyEvent("` over `vassal-app/src/main/java` returns nothing — the only caller is the unqualified call in `Decorator.keyEvent`. |
| Does any subclass chain to it? | No. `grep -rn "super.myKeyEvent"` returns nothing. `SetGlobalProperty` and `SetPieceProperty` extend `DynamicProperty` but override `myKeyEvent` outright; `MassPieceLoader$Emb` extends `Embellishment` and inherits it unchanged; `Embellishment0` extends `Decorator`, not `Embellishment`. |
| Is hoisting the match before the snapshot safe? | Yes. `KeyCommand.matches()` is `isEnabled() && key != null && key.equals(stroke)` — pure. The tracker created at the first match captures exactly the state a tracker created before the loop would have, because nothing before the first match mutates the piece. |
| Did the removed tracker have a side effect? | No. `Embellishment.myGetState()` does no lazy refresh; `checkPropertyLevel()` is reached only from `draw()` and `getShape()`. |
| New null dereference? | No. `handlesKeyStroke` returns on `followProperty` *before* touching `increaseKeyStroke`/`decreaseKeyStroke`, mirroring the guard order in `myKeyEvent`, and keeps the existing null checks on `resetKey`/`rndKey`. |
| Keystroke comparison | `NamedKeyStroke.equals` deliberately supports comparison against a raw `KeyStroke`, so `activateKeyStroke.equals(stroke)` behaves in the predicate exactly as it does in the method body. |

## Findings

### 1. `handlesKeyStroke` is looser than `myKeyEvent` for the activate stroke

`Embellishment.java:679`

```java
private boolean handlesKeyStroke(KeyStroke stroke) {
  if (activateKeyStroke.equals(stroke)) {
    return true;                                    // ← line 679
  }
  ...
```

but the branch it is standing in for, at line 703, is

```java
if (activateKeyStroke.equals(stroke) && nValues > 0 && !alwaysActive) {
  value = -value;
}
```

So a Layer with `alwaysActive = true` and a populated activate key still returns `true` from the predicate, still builds the `ChangeTracker` at line 701, is then suppressed by `!alwaysActive` at line 703, and finally serialises the piece a **second** time at line 736 to discover that nothing changed and return `null`. Two full `getState()` passes, no effect.

That combination is not exotic. `alwaysActive` and `activateKeyStroke` are read independently from the type string (`Embellishment.java:250–251`), and both the `flycap` and `flyintercept` layers on World in Flames air units are `alwaysActive=true` with `activateKey="A"` — a bare letter keystroke, so it fires whenever the key is pressed with the piece selected. These are exactly the hundred-trait pieces the PR is written for.

Tightening line 679 to

```java
if (activateKeyStroke.equals(stroke) && nValues > 0 && !alwaysActive) {
  return true;
}
```

is behaviour-preserving — any stroke that reaches line 703 and fails those conditions cannot change state through the activate branch — and closes the gap.

The case is untested: `EmbellishmentTest.createLayer` hard-codes `layer.alwaysActive = false`, so no test in the PR exercises an always-active layer. That is why it slipped through.

### 2. `DynamicProperty` returns a no-op `ChangePiece` when nothing changed

`DynamicProperty.java:285`

```java
return tracker == null ? null : tracker.getChangeCommand();
```

The sibling file got the stricter form in the same commit — `Embellishment.java:736` is `return tracker.isChanged() ? tracker.getChangeCommand() : null;` — but `DynamicProperty` still emits a command for any *matched* keystroke, whether or not the state moved.

The clearest case is a Prompt property changer the user cancels: `getNewValue` returns null (line 277), the `if (newValue != null)` guard skips `setValue`, the piece is untouched — and `getChangeCommand()` builds a `ChangePiece` with identical old and new state anyway, which `Decorator.keyEvent` appends, the logfile records and the engine broadcasts to every other player. Any changer that computes the value already held does the same.

This is pre-existing rather than introduced by the PR, but the PR rewrites precisely this return statement and establishes the `isChanged()` discipline next door, so it is the natural place:

```java
return tracker == null || !tracker.isChanged() ? null : tracker.getChangeCommand();
```

## Scope note for the author

Eleven other traits build a `ChangeTracker` unconditionally in `myKeyEvent` and would benefit from the same treatment:

`AreaOfEffect`, `Embellishment0`, `Footprint`, `FreeRotator`, `Hideable`, `Labeler`, `MovementMarkable`, `Obscurable`, `ReportState`, `SendToLocation`

`Hideable`, `Obscurable` and `MovementMarkable` are single-keystroke traits, where the guard is a one-liner. Not a defect in this PR — just where the remaining wins are.

## Why this matters here

The utility's own interest in this PR is the World in Flames module, whose counters carry enough traits for the per-keystroke serialisation cost to be measurable in play. The same whole-piece serialisation sits behind the saved-game size analysis in [wif-save-bloat-analysis.md](../wif-save-bloat-analysis.md) and the engine changes proposed in [wif-engine-optimizations.md](wif-engine-optimizations.md); this PR attacks the runtime half of it rather than the on-disk half.
