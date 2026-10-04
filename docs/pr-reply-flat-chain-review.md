# Reply to the review of PR #15116 (flat trait-chain framing)

**`Decorator.java:637` — "Use `VASSAL.tools.lang.Pair` instead of returning an array. A `Pair` cannot have the wrong number of elements."**

Done (`bb1dea599`). `splitChain` returns `Pair<String, String>`: `first` is the trait's own decoded segment, `second` the inner chain, or `null` when the chain has no inner part. `setState`/`mergeState` and the framing test read the fields. I also merged current `master` into the branch (`7f95d564b`), which it had fallen behind by the XZ change and the 3.7.29 release; no conflicts.

**"This breaks something in VASL 6.7.4-beta3. When I try to drag a piece onto a map, the piece disappears. This works correctly with a315f5b."**

I have not been able to reproduce this, so I need more from you. Everything below ran against the branch as it is now (`bb1dea599`, `master` merged), with the `vasl-6.7.4-beta3.vmod` from the VASL GitHub release and a real board set, headless (no Xvfb on this machine, so the AWT drag itself is the one thing I could not drive):

1. **Every palette piece.** All 5,911 `PieceSlot`s were built from the module, cloned with `PieceCloner.clonePiece` exactly as `PieceSlot.startDrag` does, then each clone was rebuilt from its type string and given its state string (the path saves, logs and the non-`EditablePiece` branch of `PieceCloner` take). Type, state and trait chain came back identical for all 5,911; no exception, nothing in the error log.
2. **Dropping onto a started game.** New game on board `r`, all setup steps finished; 60 palette pieces dropped through `ASLPieceMover.movePieces` (VASL's own mover, with Main Map's move key `77,650` applied after the move, auto-report on, `sendAndLog`, `refreshVisibleMaps`), a third of them onto occupied hexes so 21 stacks formed. Each piece was afterwards on the map, registered in `GameState` under its id, neither `INVISIBLE_TO_ME` nor `OBSCURED_TO_ME`, and drew without error; the game's restore command re-decoded to 81 `AddPiece`s (60 pieces, 21 stacks).
3. **A game from master.** The same scenario saved by upstream `a315f5b` (nested framing) loaded on the branch; its 60 pieces were dragged to new hexes, 40 more dropped, and the game saved again. Nothing lost.

VASL's own code frames nothing itself: none of its traits overrides `getType`/`getState`/`setState`/`mergeState`, and `ASLCommandEncoder` overrides only `createDecorator` and `decode` (checked against the beta3 class files).

What would narrow it down:

- Was the piece dragged from the counter palette, or from another map window, and which counter?
- Linux or something else? On Linux `ASLMap` installs a combined drop-target listener (enter via `PieceMover.DragHandler`, drop via `ASLPieceMover.DragHandler`), which is a path I cannot exercise headless.
- `~/.VASSAL/errorLog-3.8.0-SNAPSHOT` from right after the drop. A piece that vanishes during a move would be an exception between its removal and its placement, and it would be in there.
- Does the piece show after saving and reloading, or after a window resize? That would separate a painting problem from a missing piece.
- Was the build the branch head or the PR merge ref? Until today the head lacked #15121, so a game saved by master would not have loaded in it at all, which is a different symptom but worth excluding.
