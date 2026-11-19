# VASSAL crash: a Stack that leaves the map without leaving the map

This note documents a crash in the VASSAL engine (not in this utility), reported as
[#12033](https://github.com/vassalengine/vassal/issues/12033) and five others, and fixed by
[#15370](https://github.com/vassalengine/vassal/pull/15370). It is reached by clicking on a counter
after an undo.

## Symptom

Six reports, six modules, versions 3.6.12 to 3.7.18, all quoting the same four frames:

```
java.lang.IllegalArgumentException: Piece is not on this map
  at VASSAL.build.module.Map.positionOf(Map.java:2577)
  at VASSAL.build.module.map.MenuDisplayer.maybePopup(MenuDisplayer.java:276)
  at VASSAL.build.module.map.MenuDisplayer.mouseReleased(MenuDisplayer.java:244)
  at java.desktop/java.awt.AWTEventMulticaster.mouseReleased(AWTEventMulticaster.java:297)
```

The reporters' own notes are unusually useful, and all point the same way: *"I thought it had
exceeded MA and undid movement to look at it again. Starting through again this error came up"*;
*"Undid a couple of steps, started logging. Redid two steps and closed log. Tried drag a piece"*;
*"clicked on a piece that was near (but not in) its deck"*.

## Root cause

A Stack's state records which map it is on, or that it is on none, and an undo replays recorded
states. `Stack.setState()` ends:

```java
    if (m != getMap()) {
      if (m != null) {
        m.addPiece(this);
      }
      else {
        setMap(null);
      }
    }
```

`BasicPiece.setState()` has the identical branch, and does one thing more in it:

```java
      else { // oldMap can't possibly be null if we get to here
        oldMap.removePiece(outer);
        setMap(null);
      }
```

Nothing else takes the Stack out of `Map.pieces`. So a Stack told it is on no map **stays in the
map's piece collection** while reporting no map: still drawn, still hit-tested, still returned by
`Map.findPiece()`. `Stack.setMap(null)` also sets every piece in it to no map, and

```java
  public Map getMap() {
    return getParent() == null ? map : getParent().getMap();
  }
```

means each of those pieces answers from the Stack. The map then hands a click a piece, and
`Map.positionOf()` refuses to locate it:

```java
  public Point positionOf(GamePiece p) {
    if (p.getMap() != this) {
      ...
      throw new IllegalArgumentException(message);
    }
```

`Deck.setState()` is a copy of `Stack.setState()`, with the same hole. A Deck is what the
"near (but not in) its deck" report hit.

## The workaround that could never have worked

This is the part worth remembering. `MenuDisplayer.maybePopup()` has carried a repair for exactly
this since [e80e31bf4](https://github.com/vassalengine/vassal/commit/e80e31bf4) (March 2025,
released in 3.7.16):

```java
      // FIXME: workaround for https://github.com/vassalengine/vassal/issues/12033
      // Undo sometimes corrupts a pieces Map.
      if (!map.equals(p.getMap())) {
        p.setMap(map);
      }
```

`BasicPiece.setMap()` sets the piece's **own** `map` field — the one `getMap()` ignores for as long
as the piece has a parent. And the piece being repaired came from

```java
    final GamePiece p = map.findPiece(e.getPoint(), targetSelector);
```

whose `targetSelector` is a `PieceFinder.PieceInStack`: a piece *with* a parent is the only thing it
returns, apart from a Deck. So the repair is a no-op in precisely the case it was written for.

The line numbers settle it rather than leaving it as an argument. In 3.7.16 and 3.7.18,
`MenuDisplayer.java:276` is the `map.positionOf(p)` call **three lines after** that repair — so four
of the six reports are from versions that ran it and crashed anyway. Only #13730 (3.7.15, crashing
at line 274) and #12033 (3.6.12) predate it.

A repair that cannot work is worse than none, because it closes the question. The issue stayed open
with a plausible-looking mitigation against it for twenty months.

## The fix

[#15370](https://github.com/vassalengine/vassal/pull/15370):

- `Stack.setState()` and `Deck.setState()` leave the map before forgetting it, as
  `BasicPiece.setState()` always has.
- The `MenuDisplayer` repair sets the **Stack's** map, which sets every piece in it, and falls back
  to the piece's own only when there is no parent (the Deck case, where it always did work).
- If the piece still does not belong to the map, the click is declined rather than thrown out of.
  With the root cause fixed that should be unreachable; `positionOf()` throws rather than returning,
  so two lines seemed a fair price.

**It does not explain how the state comes to say "no map" at a moment when the Stack is still on
one.** That is the undo bug #12033 is named for and it is untouched, which is why that issue was
deliberately left out of the PR's closing list: the crash it reports will stop, but the
inconsistency it describes has only been made survivable.

### The red check

`StackOffMapStateTest` reuses the harness `StackMergeUndoTest` established upstream in October 2026
(a mocked `GameModule`/`GameState`/`IndexManager` and a `Map` registered with the id manager), which
is the first time one of these notes has had a test fixture waiting for it. Three tests, all red
against the current code — two on *"the Stack has no map but the map still holds it"*, one on the
reported `IllegalArgumentException`. A fourth was written and **deleted**: it asserted that
`map.findPiece()` no longer returns the adrift piece, and it passed against the broken code too,
because a `BasicPiece` with no image has an empty shape and the finder's hit test can never match
it. A test that passes before the fix is not a check, it is decoration.

### One thing noticed and left alone

`Stack.getState()` always writes the position, but `Stack.setState()` skips reading it when the map
id is `"null"`:

```java
    if ("null".equals(mapId)) { //BR// Looooks like if we encode null in getState then there's no position in the file to be read
      setPosition(new Point(0, 0));
    }
```

The comment is simply wrong — `getState()` appends `getPosition().x` and `.y` unconditionally. The
two tokens are then read by the child-id loop, found to be neither a known piece nor a layer marker,
and silently dropped, so an off-map Stack round-trips to (0,0). Harmless today, because a Stack
returning to a map arrives with a state that carries a position; but it is a second disagreement
between the two halves of the same encoding, in the same method as the first.

## What this means for the utility

Nothing directly: the utility reads `buildFile.xml` and images, and never instantiates a `Stack` or
runs a game.

It is worth knowing when reading a `.vsav` command log. The utility's
[excess-unit removal](../vsav-excess-units.md) and its `tools/` scripts treat every top-level command token as
opaque bytes and copy what they do not remove, which is exactly the right posture here: a save
written after one of these undos can contain a Stack whose recorded state says one thing and whose
position in the log says another, and nothing the utility does will make that worse. A tool that
decoded and re-encoded those states would have to decide what such a Stack means — which is the
question the engine itself got wrong.
