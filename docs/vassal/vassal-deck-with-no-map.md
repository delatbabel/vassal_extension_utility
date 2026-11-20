# VASSAL crash: a Deck that is not under a Map

This note documents a crash in the VASSAL engine (not in this utility), reported as
[#13610](https://github.com/vassalengine/vassal/issues/13610),
[#12869](https://github.com/vassalengine/vassal/issues/12869),
[#14185](https://github.com/vassalengine/vassal/issues/14185) and
[#13667](https://github.com/vassalengine/vassal/issues/13667), and fixed by
[#15377](https://github.com/vassalengine/vassal/pull/15377). Four reports, two user actions, one
null.

## Symptom

Two shapes, which is why they never grouped:

```
java.lang.NullPointerException: Cannot invoke "VASSAL.build.module.Map.getEdgeBuffer()" because "map" is null
  at VASSAL.build.module.map.SetupStack.setup(SetupStack.java:274)
  at VASSAL.build.module.map.DrawPile.setup(DrawPile.java:1227)
  at VASSAL.build.module.GameState.setup(GameState.java:568)      <- File > New Game
```

```
java.lang.NullPointerException: Cannot invoke "VASSAL.build.module.Map.placeOrMerge(...)"
  because the return value of "VASSAL.build.module.map.DrawPile.getMap()" is null
  at VASSAL.build.module.map.DrawPile.addToContents(DrawPile.java:1028)
  at VASSAL.counters.Deck.sendToDeck(Deck.java:1837)              <- Send to Deck
```

Every frame in all four is VASSAL's own — no module code anywhere, which is rarer in this tracker
than it sounds ([vassal-custom-code-misattribution.md](vassal-custom-code-misattribution.md)).

## The logs say Editor, and two of them say extension

The attached logs matter more than usual here, because the issue bodies say nothing about context.
All four crashes happen inside the **Editor**, after a long run of saves, and two of them —
[#13610](https://github.com/vassalengine/vassal/issues/13610) and
[#12869](https://github.com/vassalengine/vassal/issues/12869) — are `--edit-extension` sessions:

```
VASSAL.launch.Editor --edit-extension -- …\AH-lcg.vmod …\AH-lcg_ext\…\0900 - LA Festa di Hemlock Vale.vmdx
```

That is the diagnosis, and it is only in the attachment.

## Root cause

A Deck (`DrawPile`) or At-Start Stack (`SetupStack`) finds its Map by walking the build tree:

```java
public Map getMap() {
  return (Map) getNonFolderAncestor();
}
```

`getNonFolderAncestor()` returns the `ancestor` field, so the Map is null whenever the ancestor is.
Three places took it on faith.

### The guard is in the branch that does not need it

```java
  protected boolean isOwningBoardActive() {
    boolean active = false;
    if (owningBoardName == null) {
      active = true;                      // no map check
    }
    else {
      final Map map = getMap();
      if ((map != null) && (map.getBoardByName(owningBoardName) != null)) {
        active = true;
      }
    }
    return active;
  }
```

and `setup()`, the one caller:

```java
    if (gameStarting && indicator.isNewGame() && isOwningBoardActive()) {
      ...
      final Map map = getMap();
      if (owningBoardName == null) {
        p.translate(map.getEdgeBuffer().width, map.getEdgeBuffer().height);   // <- NPE
```

The `map != null` test exists. It sits in the `else` branch — the one whose code path never
dereferences the map — while the branch that returns `true` unconditionally is the one that goes
straight to `map.getEdgeBuffer()`. An inverted guard rather than a missing one, which is why it
survived: the null check is right there in the method, and reads as though it covers both cases.

`DrawPile.addToContents()` has no check at all (`return getMap().placeOrMerge(...)`), and
`DrawPile.getPosition()`, which `addToContents()` calls when the Deck has not been built yet,
dereferences `getMap()` twice.

### Why the ancestor is null

`ExtensionElement.build()`:

```java
    extension = Builder.create((Element) n);        // the no-parent form
```

`ExtensionElement implements Buildable` — it is **not** an `AbstractBuildable`, so it has no
ancestor of its own to pass on, and its `add(child)` override assigns the field without calling
`super.add()`, which is what would otherwise set the child's ancestor. The component, and
everything under it, therefore has a null ancestor until `addTo()` grafts it:

```java
  public void addTo(Buildable parent) {
    final Configurable target = targetPath.length == 0 ?
      GameModule.getGameModule() : targetPath[targetPath.length - 1];
    extension.addTo(target);
    target.add(extension);                          // <- the ancestor is set here, and only here
  }
```

In the player that graft happens and nobody notices. In the **extension Editor** the tree being
edited is the extension's own, the Map the Deck names lives in the parent module, and the graft is
not what is on screen — so the Deck has no Map at all, and pressing New Game in the Editor's
preview walks straight into the inverted guard.

Upstream has been closing this class of bug for years without reaching this one:
`c89fb2223` (2023-02-24) "Fix bug when copying Decks and At-Start Stacks to other maps" and
`cdee34bfd` (2026-11-08) "Set the ancestor before building pasted, cloned and copy-dragged
components" are both the same shape, for the Editor's paste and drag paths.

## The fix

Three guards and one cause.

```java
  protected boolean isOwningBoardActive() {
    final Map map = getMap();
    if (map == null) {
      return false;
    }
    return owningBoardName == null ||
           map.getBoardByName(owningBoardName) != null;
  }
```

`getPosition()` returns the Deck's own position when there is no map to offset against.
`addToContents()` reports through `BadDataReport` — naming the Deck — and returns a `NullCommand`,
because a Send to Deck that cannot be honoured should say which Deck rather than crash. And
`ExtensionElement.build()` passes the target as the parent, with the target expression lifted into
one `getTarget()` that `build()` and `addTo()` share so they cannot drift apart.

In the player the last of those changes nothing: `addTo()` set that same ancestor a moment later
anyway. It is the extension Editor that gains a Deck which knows where it lives.

### The red check

Ten new cases across `DeckWithNoMapTest` and `ExtensionElementTest`. Four fail against unmodified
`main`, and one of them fails with the reported message word for word:

```
NullPointer Cannot invoke "VASSAL.build.module.Map.placeOrMerge(VASSAL.counters.GamePiece, java.awt.Point)"
  because the return value of "VASSAL.build.module.map.DrawPile.getMap()" is null
```

The other six cover what was already right — a Stack whose owning board is or is not in play, a
Deck that does have a map, the edge-buffer offset — and pass both ways, so the suite is not merely
asserting its own new guards.

Two things the harness forced into the open, both worth remembering. `DrawPile`'s field
initialiser is `new Deck(GameModule.getGameModule())`, so the class cannot be constructed in a test
without a module; `mock(DrawPile.class, CALLS_REAL_METHODS)` builds one through Objenesis, skipping
the constructor and the field initialisers, and the few fields the test needs go in by reflection.
And an XML element name may not contain `$`, so a nested test class cannot be named by a buildFile
tag — but `DataArchive.loadClass()` is the indirection a module uses to resolve a tag to a class,
so mocking it to answer `FakeComponent.class` for the tag `test.Component` names the nested class
honestly rather than working around the parser.

## What this means for the utility

Directly, for anyone using this utility's **Move / Copy** into an extension. The utility writes a
component into an `ExtensionElement` whose `target` names the module path to graft onto
(see [Move / Copy](../../CLAUDE.md#move--copy-operation-the-core-feature)), and a Deck moved that
way is exactly the configuration above: correct on disk, correct at play time, and — on a VASSAL
without this fix — crashing in the extension Editor the moment anyone presses New Game or sends a
card to it. So a Deck grafted into an extension is worth testing in the Editor as well as in the
player, and a crash there is the engine's, not the utility's output.

It is also a reminder of why the utility's
[extension tree display](../../CLAUDE.md#extension-tree-display) reconstructs the module hierarchy
around each `ExtensionElement` rather than showing the wrapper: in the utility a grafted Deck is
drawn under its module Map, which is where it really belongs. VASSAL's own extension Editor shows
it under the extension root, which is the view in which it has no Map.
