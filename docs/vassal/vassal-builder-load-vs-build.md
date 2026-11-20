# VASSAL: "failed to load class" names the one class that loaded

This note documents a reporting defect in the VASSAL engine (not in this utility), and the
largest real fault hiding behind it. Twenty-two open automated bug reports carry the message
`VASSAL.build.IllegalBuildException: failed to load class <X>`; in most of them `<X>` loaded
without trouble. Reported as [#14560](https://github.com/vassalengine/vassal/issues/14560),
[#14879](https://github.com/vassalengine/vassal/issues/14879),
[#14880](https://github.com/vassalengine/vassal/issues/14880),
[#14385](https://github.com/vassalengine/vassal/issues/14385) and
[#13207](https://github.com/vassalengine/vassal/issues/13207) (the real fault) and
[#14339](https://github.com/vassalengine/vassal/issues/14339),
[#14596](https://github.com/vassalengine/vassal/issues/14596),
[#14597](https://github.com/vassalengine/vassal/issues/14597),
[#12147](https://github.com/vassalengine/vassal/issues/12147),
[#14515](https://github.com/vassalengine/vassal/issues/14515) and
[#15226](https://github.com/vassalengine/vassal/issues/15226) (the misattribution), fixed by
[#15375](https://github.com/vassalengine/vassal/pull/15375).

## Symptom

A module fails to load, or loads with a component missing, and the automated report says:

```
VASSAL.build.IllegalBuildException: failed to load class VASSAL.build.module.Map
 at VASSAL.build.Builder.create(Builder.java:148)
 at VASSAL.build.Builder.build(Builder.java:80)
 at VASSAL.build.AbstractBuildable.build(AbstractBuildable.java:104)
 at VASSAL.build.GameModule.build(GameModule.java:790)
```

`VASSAL.build.module.Map` is a core engine class. It is on the stack. It cannot have failed to
load, and it did not.

## Root cause

`vassal-app/src/main/java/VASSAL/build/Builder.java`:

```java
    try {
      final Buildable b = (Buildable) (mod == null ?  Class.forName(name) :
        mod.getDataArchive().loadClass(name)).getConstructor().newInstance();
      ...
      b.build(e);
      return b;
    }
    catch (Throwable t) {
      ...
        throw new IllegalBuildException("failed to load class " + name, t);
```

One `try` covers three distinct things:

1. **loading** the class named by the XML tag,
2. **constructing** an instance of it,
3. **building** the component — which is `AbstractBuildable.build()`, so it is the component's
   attributes, its entire recursive subtree, *and* every descendant's `addTo()`.

Step 3 is unbounded. Anything that goes wrong anywhere under a component is reported as that
component having failed to load. The deeper the module's structure, the further the real fault
is from the class the message names.

### What the twenty-two reports actually are

The `Caused by` that would say is in the attached error log, which the issue body drops — see
[reading-abr-error-logs.md](reading-abr-error-logs.md). Reading all twenty-two:

| What the log shows | Reports | Step |
|---|---|---|
| A buildFile attribute that is not a number | 5 | 3 |
| A class the module ships but does not contain | 7 | 1 |
| A module class compiled against an older VASSAL API | 5 | 1 or 3 |
| Malformed XML in the module's own buildFile | 2 | 3 |
| A `ClassCastException` inside a subtree | 1 | 3 |
| A static initialiser failing during board tiling | 1 | 3 |
| `GameModule` nested where it cannot be constructed | 1 | 2 |

Three of them are worth quoting, because they show how far step 3 reaches:

- [#14596](https://github.com/vassalengine/vassal/issues/14596) and
  [#14597](https://github.com/vassalengine/vassal/issues/14597) say *failed to load class
  VASSAL.build.module.Map*. The cause is `NoSuchMethodError` raised by the module's own
  `ASLTilingHandler` constructor, called from `ASLBoardPicker.addTo()` — two levels below the
  Map, in code VASSAL did not write.
- [#12147](https://github.com/vassalengine/vassal/issues/12147) says *failed to load class
  GamePieceLayout*. The cause is `ShapeItem cannot be cast to TextItem`, inside that layout's
  subtree.
- [#15226](https://github.com/vassalengine/vassal/issues/15226) says *failed to load class
  VASSAL.build.GameModule*, with `GameModule.build()` three frames further up the same stack.
  The cause is `NoSuchMethodException: GameModule.<init>()` — step 2, not step 1.

### Why the message is all a reader has

An automated report quotes four frames and no `Caused by`, so for this family the message is the
whole of the public diagnosis. A tracker search on `failed to load class` returns twenty-two
reports of at least eight unrelated faults, grouped by the one thing none of them is. This is the
mirror image of the problem in
[vassal-trailing-backslash-state.md](vassal-trailing-backslash-state.md), where sixteen reports of
*one* fault never grouped because each message embedded a different trait's type string: there the
message was too specific to group, here it is too vague to separate.

## The real fault behind five of them

The largest group has one cause — a buildFile attribute whose value the component cannot parse as
a number:

| Issue | Attribute | Value |
|---|---|---|
| [#14879](https://github.com/vassalengine/vassal/issues/14879), [#14880](https://github.com/vassalengine/vassal/issues/14880) | `Zoomer.zoomStart` | `0.25` |
| [#14560](https://github.com/vassalengine/vassal/issues/14560) | `DiceButton.keepCount` | `false` |
| [#14385](https://github.com/vassalengine/vassal/issues/14385) | `Region.x` | `181.5` |
| [#13207](https://github.com/vassalengine/vassal/issues/13207) | `Region.y` | `708s` |

`AbstractBuildable.build()` walks the element's attributes and hands each to `setAttribute()`:

```java
    final NamedNodeMap n = e.getAttributes();
    for (int i = 0; i < n.getLength(); ++i) {
      final Attr att = (Attr) n.item(i);
      setAttribute(att.getName(), att.getValue().intern());
```

`Zoomer.setAttribute()` does `Integer.valueOf((String) val)`, `DiceButton` and `Region` do
`Integer.parseInt()`. The `NumberFormatException` escapes, is relabelled by `Builder.create()`,
and `Builder.build()` turns it into an automated bug report — so **VASSAL files a bug against
itself for a value in someone's module**, and the component is dropped whole: the map loses its
zoom controls, the toolbar loses its dice button, the board loses a region.

The attribute arrays of all three components were checked at the reporting versions and are
correctly aligned, so the Editor is not writing these values; they come from hand editing, from
module-building tools, or from conversions. That does not make it less VASSAL's problem to
*report* properly.

## The fix

Two parts, in [#15375](https://github.com/vassalengine/vassal/pull/15375).

**Split the three steps** so the message says which one failed — `failed to load class X`,
`failed to construct X`, `failed to build X`. The classification of which throwables are wrapped
and which are rethrown moves into a helper and is otherwise untouched, so the control flow is
identical: each still raises `IllegalBuildException`, which `Builder.build()` catches and reports
exactly as before. Only the wording moves. That matters because the forgiving behaviour is
load-bearing — stop wrapping a build failure in `IllegalBuildException` and `Builder.build()`'s
*other* catch rethrows it, which aborts the whole module load. A more honest-looking change would
have made every one of these reports fatal.

**Treat a bad attribute value as bad data.** VASSAL already has the mechanism —
`ErrorDialog.dataWarning(new BadDataReport(...))`, which logs to the error log and writes to the
chat window without raising a bug report. `AbstractBuildable.build()` now catches
`NumberFormatException` per attribute, reports it, and goes on to the next one:

```
Bad Data in Module: Component VASSAL.build.module.map.Zoomer has a bad value for attribute zoomStart [0.25]
```

So one unreadable value costs that value rather than the whole component, and the module's
maintainer is told the component, the attribute and the value — none of which the bug report it
replaces carried. The catch is `NumberFormatException` and nothing wider: a `ClassCastException`
or a general `IllegalArgumentException` out of `setAttribute()` may well be a real bug, and still
is one. Only the buildFile path is affected; the Editor's own `setAttribute()` calls are untouched.

### The red check

`BuilderTest` is new and covers all three steps; `AbstractBuildableTest` gains three cases for the
attribute handling. Against unmodified `main`, five of the fourteen fail, and in the reported way
— the two bad-attribute cases with

```
java.lang.NumberFormatException: For input string: "false"
	at VASSAL.build.AbstractBuildable.build(AbstractBuildable.java:92)
```

which is the frame [#14560](https://github.com/vassalengine/vassal/issues/14560)'s own log
records, and the three `Builder` cases with `failed to load class` for classes that loaded. Two
control cases — a class that genuinely is not there, and a well-formed numeric value — pass both
ways, so the suite is not merely asserting the new strings.

One thing the test harness forced into the open: a component is loaded by the name in its XML tag,
and `Class.forName` finds a nested class only under a name containing `$`, which is not a legal
XML element name. Test components therefore cannot be nested classes if the element is parsed from
XML. Mocking the `Element` sidesteps it — `Builder.create()` reads nothing from it but
`getTagName()` — and is the reason `BuilderTest` builds no XML at all.

## What this means for the utility

Indirectly, and in two ways.

The utility reads `buildFile.xml` with a plain DOM walk and never instantiates a VASSAL component,
so it is immune to all of this: a module with `zoomStart="0.25"` opens, displays and edits here
exactly as any other does. That cuts both ways — **the utility will not warn about a value that
makes VASSAL drop the component**, and will carry it through a Move, a Copy or a Combine
unchanged. If a component is present in the utility's tree but missing in VASSAL, an unparseable
numeric attribute is worth checking before anything else.

It is also a second instance of a pattern already recorded here: VASSAL reporting a precise-looking
message that is about the wrong thing. [Refresh Counters](../../CLAUDE.md#refresh-counters) says
*"Module was saved with older vassal version"* when what `GpIdChecker` actually found was a
duplicate or non-numeric GPID, and the version is never consulted. Both are cases where the
engine's error text sends the reader somewhere the fault is not, and in both the useful question
is what the *log* says rather than what the dialog says.
