# VASSAL crash: the apply-on-move key re-enters the trait that moved

This note documents a crash in the VASSAL engine (not in this utility), reported as
[#15209](https://github.com/vassalengine/vassal/issues/15209),
[#13685](https://github.com/vassalengine/vassal/issues/13685) and
[#13063](https://github.com/vassalengine/vassal/issues/13063) — three issues, two of them open
since 2024, with one line of cause between them — and fixed by
[#15350](https://github.com/vassalengine/vassal/pull/15350).

## Symptom

A `StackOverflowError`, with four frames of BeanShell quoted in the issue body:

```
java.lang.StackOverflowError
 at bsh.Parser.PostfixExpression(Parser.java:1988)
 at bsh.Parser.UnaryExpressionNotPlusMinus(Parser.java:1921)
 at bsh.Parser.UnaryExpression(Parser.java:1813)
 at bsh.Parser.MultiplicativeExpression(Parser.java:1707)
```

#15209 was filed three times in two hours (#15210 and #15214 were closed as duplicates), and each
of the three quotes four **different** BeanShell frames:

| report | quoted frames |
|---|---|
| #15209 | `PostfixExpression` 1988, `UnaryExpressionNotPlusMinus` 1921, `UnaryExpression` 1813, `MultiplicativeExpression` 1707 |
| #15210 | `jj_3R_101` 4738, `jj_3R_60` 4718, `jj_3R_34` 4801, `jj_3_12` 4848 |
| #15214 | `RelationalExpression` 1538, `InstanceOfExpression` 1510, `EqualityExpression` 1459, `AndExpression` 1416 |
| #13063 | `jj_3_9` 4994, `jj_2_9` 3755, `UnaryExpressionNotPlusMinus` 1897, `UnaryExpression` 1813 |
| #13685 | `jj_3R_164` 5510, `jj_3R_159` 5564, `jj_3R_153` 5605, `jj_3R_139` 5647 |

Five reports, five disjoint sets of frames, one cause. A tracker search for any of those method
names finds one report and misses the other four.

## None of it is BeanShell

The printed trace is 1,024 frames — Java's cap. In #15209's log **26 of them are BeanShell and
998 are VASSAL**; the issue body quotes four of the 26. BeanShell is not the fault. It is simply
what happened to be executing when the stack finally ran out, because evaluating an expression is
the deepest thing the innermost turn of the loop does.

Counting the VASSAL frames instead says what the loop is:

| frame | #15209 | #13685 | #13063 |
|---|---|---|---|
| `EditablePiece.finishMove(EditablePiece.java:167)` | **71** | **323** | **42** |
| `FreeRotator.myKeyEvent(643)` | 70 | 321 | — |
| `FreeRotator.rotateCargo(552)` | 70 | 321 | — |
| `FreeRotator.movePiece(521)` | 70 | 321 | — |
| `SendToLocation.myKeyEvent(546)` | — | — | 42 |
| `TriggerAction.keyEvent(164)` | — | — | 129 |
| `Decorator.keyEvent(610/611)` | 497 | 646 | 387 |
| BeanShell / `ExpressionInterpreter` | 1 | 2 | 1 |

One line is in all three, as often as the cycle turns.

## Root cause

`EditablePiece.finishMove` is what a trait calls after it has moved a piece. Its last job is the
map's *apply on move* key (3.7.30 line numbering):

```java
163:    // Apply "afterburner" apply-on-move key command
164:    if (afterburner) {
165:      final Map map = outer.getMap();
166:      if (map != null && map.getMoveKey() != null) {
167:        c = c.append(outer.keyEvent(map.getMoveKey()));
168:      }
169:    }
```

Line 167 dispatches that key **back into the trait chain of the piece which has just moved**. A
trait which moves a piece in response to that key therefore arrives back where it started, and
nothing bounded the round trip.

### The Mat/Cargo form — #15209 and #13685

```
FreeRotator.myKeyEvent(643)      rotate the Mat
  rotateCargo(552)               ... so move each Cargo piece with it
    movePiece(521)               ... which is finishMove(c, true, !cargoFollowup, true)
      finishMove(167)            ... whose afterburner fires the apply-on-move key
        Decorator.keyEvent       ... which travels the piece's traits
          FreeRotator.myKeyEvent(643)   ... and rotates again
```

70 turns in #15209, 321 in #13685. Both reports are of modules using Mat and Mat Cargo: #15209's
log shows the editor failing to open `MatCargo.html` a few minutes before the crash, and #13685's
reporter described the mechanism exactly, two years ago, without knowing it —

> train has mat property, cargo has "mat cargo" property. When I move train with cargo that is the
> moment, when it hapens.

### The Send to Location form — #13063

```
SendToLocation.myKeyEvent(546)   = finishMove(c, true, true, ...)
  finishMove(167)                the afterburner fires the apply-on-move key
    Decorator.keyEvent
      TriggerAction.keyEvent(164)     a Trigger Action responds
        SendToLocation.myKeyEvent(546)  by sending the piece again
```

42 turns. A different pair of traits, the same line, the same shape. The reporter's note — *"I
moved a piece (N. African boat) from the Removed Pieces tray to the Forces tray"* — is a move, and
the move is what starts it.

Every line number above was checked against the 3.7.30 source, since that is the version all three
reports ran.

## The fix

[#15350](https://github.com/vassalengine/vassal/pull/15350) limits the dispatch the way every other
trait-to-trait dispatch in VASSAL already is. `RecursionLimiter` has existed for exactly this since
long before these reports — eleven traits use it, and `TriggerAction.executeKey` is the same four
lines — so a module which closes the loop now gets VASSAL's **infinite loop** report, naming the
trait and the piece, rather than a `StackOverflowError` naming BeanShell.

**Bounded at `finishMove`, not at `FreeRotator`.** That one line is where all three reports
re-enter, so fixing `FreeRotator` would have left #13063 and every trait nobody has reported yet.
`Decorator` gains the `RecursionLimiter.Loopable` marker so the report can name the trait; it
already defined both `Auditable` methods (`getComponentTypeName`, `getComponentName`), so there is
nothing to implement and no behaviour change.

**Nothing is lost on a path which was not already looping.** The limit is 50 levels of *nesting*,
and a module which exceeded that previously exhausted the stack instead.

### Tests

`ApplyOnMoveRecursionTest` builds the loop directly — a trait which calls `finishMove` with the
afterburner on, and a map whose apply-on-move key is the one that reaches it. It asserts that the
key *does* re-enter the trait (so the fix cannot degenerate into never dispatching), that it stops
at the recursion limit rather than at the end of the stack, and that the loop is reported. A second
test covers a map with no apply-on-move key.

Checked red: without the limit it fails with `StackOverflowError` — the reported bug, reproduced in
a unit test. Full `vassal-app` suite 798 tests, 0 failures, Checkstyle clean.

## The fourth reading of the same lesson

This is the same failure of automated bug reports that
[vassal-chatter-html-nesting.md](vassal-chatter-html-nesting.md) describes, in its purest form:
**the four quoted frames are the deepest four, and for a runaway recursion the deepest frames are
the least characteristic ones.** There they were Swing's HTML styling; here they are BeanShell's
expression parser. In both cases the engine code at fault was hundreds of frames further up, and
in both cases the quoted frames sent anyone searching the tracker to the wrong place — which is why
#13063 and #13685 sat open for one and two years with a one-line cause.

The companion notes are [vassal-record-module-load-crash.md](vassal-record-module-load-crash.md),
where the quoted frames are the *outermost* and are identical for every module-load failure, and
[vassal-custom-code-misattribution.md](vassal-custom-code-misattribution.md), where they are the
complete trace and are still misread, and
[vassal-room-list-concurrency.md](vassal-room-list-concurrency.md), where all four are the JDK's
own bounds checking and name neither the fault nor the subsystem. Together: **an ABR's quoted
frames are whichever four sit at the top of the printed trace, which is an accident of the
exception's shape. Fetch the log.**

For this family in particular there is a quick test. A `StackOverflowError` whose log holds a
thousand VASSAL frames and a few dozen library ones is a VASSAL recursion, whatever the library at
the bottom is called; count the frames by class before believing the heading.

## What this means for the utility

Nothing directly: the extension utility does not execute traits.

For module designers it is worth knowing while 3.8.2 is unreleased, because the loop is easy to
build by accident and the crash says nothing about where it came from. **A map's *apply on move*
key reaches every trait of the piece that moved, including traits which move it again.** Rotating
a Mat moves its Cargo; sending a piece to a location moves it; either will fire that key, and if
anything downstream moves the piece once more the loop is closed. Until a build carrying #15350 is
in general use, the symptom is a hard crash rather than a diagnosable error.
