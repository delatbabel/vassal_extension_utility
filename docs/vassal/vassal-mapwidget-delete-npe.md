# VASSAL bug: deleting a Map from a Chart Window fails, and leaves it in the module

This note documents a bug in the VASSAL **editor** (not in this utility), reported six times
between 2023 and 2026 and fixed by
[#15352](https://github.com/vassalengine/vassal/pull/15352). The visible half is a
`NullPointerException` dialog; the half that matters is that the deletion then silently does not
happen.

## Symptom

Six automated bug reports, all with the same four frames:

| issue | version | module | opened |
|---|---|---|---|
| [#14667](https://github.com/vassalengine/vassal/issues/14667) | 3.7.21 | Total War | 2026-04-20 |
| [#14057](https://github.com/vassalengine/vassal/issues/14057) | 3.7.16 | ASG Golf | 2025-05-13 |
| [#13847](https://github.com/vassalengine/vassal/issues/13847) | 3.7.15 | Golden Age of Piracy | 2025-02-17 |
| [#13764](https://github.com/vassalengine/vassal/issues/13764) | 3.7.15 | Star Drifter | 2025-01-06 |
| [#13759](https://github.com/vassalengine/vassal/issues/13759) | 3.7.15 | Waterloo 3.0 from scratch | 2024-12-28 |
| [#12290](https://github.com/vassalengine/vassal/issues/12290) | 3.6.17 | Away Team | 2023-05-08 |

```
java.lang.NullPointerException: Cannot read field "parent" because "comp" is null
 at java.desktop/java.awt.Container.remove(Container.java:1290)
 at VASSAL.build.widget.MapWidget.remove(MapWidget.java:104)
 at VASSAL.configure.ConfigureTree.remove(ConfigureTree.java:1376)
 at VASSAL.configure.ConfigureTree.delete(ConfigureTree.java:1368)
```

This family is unusual in being **legible from the issue body alone** — the four quoted frames
are the whole fault, naming the engine class, the method and the line. Contrast every other note
here (see [the list below](#the-tracker-shape)). The only thing the logs add is corroboration,
and in one case the user's own account.

`MapWidget.java:104` is `panel.remove(mapHolder)` in all six versions, checked against the tags.
Only the two frames underneath differ: #13847 came through `ExtensionTree`, the rest through
`ConfigureTree`.

## Root cause

`vassal-app/src/main/java/VASSAL/build/widget/MapWidget.java`:

```java
@Override
public void remove(Buildable b) {
  if (b instanceof WidgetMap) {
    panel.remove(mapHolder);          // line 104
    panel.remove(map.getToolBar());
    mapHolder = null;
  }
  super.remove(b);
}
```

It takes the panel apart through the widget's own `mapHolder` and `map` fields, and never looks
at `b` beyond its type. Reaching it a second time — with the same `WidgetMap`, or with a
different one — finds `mapHolder` already null, and `Container.remove(null)` dereferences it.

A `MapWidget` holding more than one `WidgetMap` is a state the class already contemplates:
`add()` begins by dropping whatever is currently on display.

```java
public void add(Buildable b) {
  if (b instanceof WidgetMap) {
    if (mapHolder != null) { panel.remove(mapHolder); mapHolder = null; }
    map = (WidgetMap) b;
    ...
```

And `ConfigureTree.delete` walks a subtree in postorder, removing *every* node in it:

```java
final Enumeration<?> e = getTreeNode(target).postorderEnumeration();
...
for (final DefaultMutableTreeNode victim : victims) {
  result &= remove((Configurable) ((DefaultMutableTreeNode) victim.getParent()).getUserObject(),
                   (Configurable) victim.getUserObject());
}
```

so two maps under one widget are removed one after the other, through the same `remove`.

## The half that matters

The NPE arrives **before `super.remove(b)`**. The component is therefore never taken out of the
widget's `buildComponents`: the editor shows an error dialog and the delete does not happen.
The reporter of #13759 wrote the diagnosis for us:

> I was trying to delete some duplicate "Map Window" entries that I no longer needed. The error
> message popped up. I had to save the module with errors and when I opened it again, the "Map
> Windows" that I thought were deleted were still there. They had not been deleted. I can make
> it happen again by trying to delete the empty "Map Windows."

Two of the logs corroborate the "duplicate, empty" part directly: both #13759 and #14667 carry a
run of `More than one Map Window named …` / `Must define at least one board in [...]` warnings
immediately before the crash. And #14057 logs the same NPE **twice in one editor session**,
which is what trying again looks like.

A crash dialog is an annoyance. An operation that reports an error and then quietly does nothing
is how a module ends up with components its author believes they removed.

## The fix

`remove()` now leaves the panel alone unless `b` is the map actually on display, and the
detaching is shared with `add()` so the two cannot drift apart:

```java
private void detachMap() {
  if (mapHolder != null) { panel.remove(mapHolder); mapHolder = null; }
  if (map != null)       { panel.remove(map.getToolBar()); map = null; }
}
```

Two smaller things fall out of it:

- **A leaked toolbar.** `add()` dropped the superseded map's layered pane but never its toolbar,
  which stayed in the panel owned by nothing. `BorderLayout` only shows one component per
  position, so it was invisible rather than harmful — but it was still a child of the panel.
- **Both fields are cleared together.** Leaving `map` set while `mapHolder` was null is the
  inconsistency the crash grew out of. `getMap()` returning null once its map has been removed
  is the honest answer, and nothing in `vassal-app` calls it (the only other `getMap()` nearby,
  in `PieceSlot`, is `GamePiece.getMap()`).

## What could not be established

Which editor action produces the second `remove` is in none of the six logs: they record the
module loading, then the exception, with nothing in between. `delete`, `insert`, `nodeUpdated`,
the Move action and the cut/paste paths were each traced and each is balanced on its own; the
reachable combinations all involve a `MapWidget` holding two `WidgetMap`s.

Supporting that reading, `isDeleteAllowed` only offers Delete on a mandatory component when its
parent holds **more than one** of that class — the engine explicitly contemplates deleting one
of several. (A `WidgetMap` is not in fact mandatory, so Delete is always offered; but the shape
of the check says what the author had in mind.)

A scan of 928 `MapWidget`s across the local module collection found every one with exactly one
`WidgetMap` child, so this is not a state modules are saved in — it arises during an editing
session.

Rather than guess at the sequence, the fix makes `remove()` correct for all of them.
`panel.remove(null)` is wrong whatever led there.

## Tests

`MapWidgetTest` covers removing the same map twice, removing a superseded one, removing a
non-map, and what the panel and the widget hold afterwards — including that the component really
does leave the widget, which is the user-visible symptom.

Building a real `WidgetMap` in a test takes two pieces of scaffolding, both worth recording.

**A `GameModule`.** `Map`'s constructor registers key listeners, `Map.build` reads preferences,
makes a launch button (which wants a `DataArchive`) and adds a `BoardPicker` (which wants a
`GameState`). `mock(GameModule.class, RETURNS_DEEP_STUBS)` covers the chain, with two explicit
overrides — `getPrefs().getValue()` has to return something unboxable to `int`, and
`getPlayerWindow()` must **not** be a deep stub, because AWT calls real methods on whatever
window it is handed and a mocked `Window` has none of its internals (`Cannot enter synchronized
block because "this.ownedWindowList" is null`, thrown on the EDT).

**No display.** VASSAL's CI has no X11, and two of the obvious ways to set a map up need one:

```
java.awt.HeadlessException
  at java.desktop/java.awt.dnd.DragSource.getDefaultDragSource(DragSource.java:224)
  at VASSAL.build.module.map.CounterDetailViewer.addTo(CounterDetailViewer.java:319)
  at VASSAL.build.module.Map.addChild(Map.java:793)
  at VASSAL.build.module.Map.build(Map.java:762)
```

`Map.build(null)` takes the branch which adds a `CounterDetailViewer`, and `Map.addTo` asks for
the default `DragSource` itself (`Map.java:979`). Building the map from an *element* takes the
other branch, and handing it straight to `MapWidget.add` skips `addTo`; neither omitted step
touches the panel, which is all this class does. The lesson generalises to any test here that
instantiates a `Map`: **run it with `DISPLAY` unset before pushing**, since a developer machine
will not tell you.

```bash
env -u DISPLAY -u WAYLAND_DISPLAY -u XAUTHORITY ./mvnw -pl vassal-app -am test
```

Red check: against the unfixed class four of the six fail, two with the reported message
`Cannot read field "parent" because "comp" is null`. With the fix the full `vassal-app` suite is
860 tests, 0 failures.

## The tracker shape

Worth recording because it is the opposite of the usual case. The other notes here are all about
reports whose quoted frames mislead —
[chatter HTML nesting](vassal-chatter-html-nesting.md) and
[apply-on-move recursion](vassal-apply-on-move-recursion.md) (deepest frames of a recursion),
[record module load](vassal-record-module-load-crash.md) (outermost wrapper frames),
[custom-code misattribution](vassal-custom-code-misattribution.md) (complete but unattributed),
[room list concurrency](vassal-room-list-concurrency.md) (four frames of JDK bounds checking).

This one is the case where the automated report works exactly as intended: identical frames
across six reports, naming the line. It sat open for three years anyway, which suggests the
limiting factor on this tracker is not usually the quality of the trace.

## What this means for the utility

Nothing directly. The extension utility edits `buildFile.xml` through the DOM and never
instantiates `MapWidget`, so it cannot hit this. It is filed here with the other engine notes,
and as a reminder of a failure mode worth watching for in the utility's own code: an exception
thrown part-way through a mutation leaves the model in neither the old state nor the new one.
