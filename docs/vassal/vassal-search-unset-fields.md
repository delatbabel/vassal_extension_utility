# VASSAL crash: the Editor's search on a component you have not finished

This note documents a crash in the VASSAL engine (not in this utility), reported as
[#14198](https://github.com/vassalengine/vassal/issues/14198) and
[#14149](https://github.com/vassalengine/vassal/issues/14149) and fixed by
[#15378](https://github.com/vassalengine/vassal/pull/15378). It is worth recording less for the
bug, which is three lines, than for the way the rest of the family was found: by running the
engine rather than by reading it.

## Symptom

```
java.lang.NullPointerException
  at java.base/java.util.Objects.requireNonNull(Objects.java:233)
  at java.base/java.util.ImmutableCollections$List12.<init>(ImmutableCollections.java:563)
  at java.base/java.util.List.of(List.java:937)
  at VASSAL.build.module.documentation.AboutScreen.getFormattedStringList(AboutScreen.java:233)
  at VASSAL.configure.ConfigureTree$SearchAction.checkSearchTarget(ConfigureTree.java:2796)
  at VASSAL.configure.ConfigureTree$SearchAction.checkNode(ConfigureTree.java:2934)
```

Four of the six quoted frames are the JDK's own collection code — the shape
[reading-abr-error-logs.md](reading-abr-error-logs.md) warns about — but the fifth names the
method exactly, and the method is three lines long.

## Root cause

```java
  @Override
  public List<String> getFormattedStringList() {
    return List.of(title);
  }
```

`List.of()` throws `NullPointerException` on a null element, by design. `AboutScreen.title` has no
initial value and the `title` attribute need not be in the buildFile, so an About Screen whose
title has not been filled in has a null there, and the Editor's **search** — which asks every
component for its strings — walks into it. The module cannot be searched at all until that
component is given a title.

The same class allows for the null twice within ten lines: `setAttribute()` tests
`title != null` before comparing it, and `addLocalImageNames()` tests `fileName != null`. Only the
search accessor, added later for `SearchTarget`, assumed the field was set.

## Finding the rest of the family, by running it

The interesting part. `return List.of(<field>);` appears 51 times across the `SearchTarget`
methods, and the obvious move is to read them all and guard the ones whose field looks nullable.
Doing that by eye produced a **wrong** answer within minutes: `HelpFile.getFormattedStringList()`
is character-for-character identical to `AboutScreen`'s, and `HelpFile.title` likewise has no
initialiser — so it went into the first draft. Its test then failed, expecting `[]` and getting
`[help]`, because:

```java
  public HelpFile() {
    this("help", (URL) null);
  }
```

The no-argument constructor names it. A declaration without an initialiser is not evidence; what
the constructor does is.

So the survey was done by running it instead. A throwaway test walked `target/classes`, found
every concrete class implementing `SearchTarget` with a public no-argument constructor,
constructed one of each, called all five list methods on it and printed the ones that threw. 131
classes; four results:

| Class | Method | Verdict |
|---|---|---|
| `AboutScreen` | `getFormattedStringList` | real — the reported bug |
| `GlobalProperty` | `getExpressionList` | real, and not reported |
| `ZoneProperty` | `getExpressionList` | real — it extends `GlobalProperty` |
| `GlobalOptions` | `getNamedKeyStrokeList` | **false positive** |

`GlobalOptions` throws only because it reads `GameModule.getGameModule()`, which is null in a test
and never null in the Editor. The probe has to be read, not trusted — but it is still a far better
starting point than reading 51 methods, because it is the engine's own answer.

`GlobalProperty.getExpressionList()` is the find:

```java
    return List.of(initialValue, minValue, maxValue);
```

`initialValue` starts as `""`, but `minValue` and `maxValue` have no initial value and the `min`
and `max` attributes are optional — so **every Global Property without a numeric range set**
crashes the search. That is the default state of the component, and nobody has reported it, which
says something about how much the Editor's search is used.

One site the probe could not reach, because it needs a state rather than a fresh object:
`GlobalTranslatableMessage.getPropertyList()` returns `List.of(property.getName())`, and the
property is named after the component, so an unnamed one has a null name. That is the same fault
as `GlobalProperty.getPropertyList()`, reported as
[#14504](https://github.com/vassalengine/vassal/issues/14504) and fixed in
[#15336](https://github.com/vassalengine/vassal/pull/15336) — found there by a report, found here
by looking for its twin.

### What the probe ruled out

Just as useful: **none of the ~20 trait classes throws**, though many of their fields are declared
without initialisers. Traits are built by `mySetType()`, which reads a `SequenceEncoder.Decoder`,
and `nextToken()` returns `""` for a missing token rather than null:

```java
      if (start == stop) {
        // token for "null" is the empty string
        val = null;
        return "";
      }
```

So a trait's string fields are empty, never null, and guarding them would have been twenty
speculative changes to code that works. The empirical survey is what made it safe to leave them
alone, and to say so.

## The fix

Three guards, in the style [#15336](https://github.com/vassalengine/vassal/pull/15336) already
used for `GlobalProperty.getPropertyList()`:

```java
    return title == null ? List.of() : List.of(title);
```

and, for the three-element case, the explicit form `SetupStack.getExpressionList()` already uses
nearby — add each value only when it is there.

### The red check

Four of the seven new cases fail against unmodified `main`, all with `NullPointerException`; the
three control cases — an About Screen that has a title, a named Global Translatable Message, a
Global Property with a range — pass both ways.

## What this means for the utility

Nothing directly: this utility has its own tree and its own
[search](../../CLAUDE.md#selection-model), which matches on the node's display name and never asks
a VASSAL component for its searchable strings.

It matters as a *method*, though, and the lesson generalises past VASSAL. Three of the four sites
here were found by constructing 131 objects and calling five methods on each — about forty lines of
throwaway test. Reading the same 51 call sites by eye produced a confident wrong answer on the
second one looked at. Where a fault class is mechanical, enumerate it mechanically: the list that
comes back is evidence, and the entries that do *not* appear are evidence too.
