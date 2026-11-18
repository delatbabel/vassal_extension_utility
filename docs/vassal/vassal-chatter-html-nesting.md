# VASSAL crash: an `<html>` tag in a chat message overflows the stack

This note documents a crash in the VASSAL engine (not in this utility), reported as
[#15300](https://github.com/vassalengine/vassal/issues/15300) and fixed by
[#15344](https://github.com/vassalengine/vassal/pull/15344). It is triggered by a module that
wraps its report messages in `<html>...</html>` — a habit, not a mistake, and a common one.

## Symptom

The game dies after an hour or two of play with a `StackOverflowError`, and the automated bug
report quotes this:

```
java.lang.StackOverflowError
  at java.desktop/javax.swing.text.html.MuxingAttributeSet.getAttribute(MuxingAttributeSet.java:210)
  at java.desktop/javax.swing.text.html.MuxingAttributeSet.getAttribute(MuxingAttributeSet.java:210)
  at java.desktop/javax.swing.text.html.StyleSheet$ViewAttributeSet.doGetAttribute(StyleSheet.java:2873)
  at java.desktop/javax.swing.text.html.StyleSheet$ViewAttributeSet.getAttribute(StyleSheet.java:2853)
```

The full error log (fetched as [reading-abr-error-logs.md](reading-abr-error-logs.md) describes)
adds nothing in the way of a culprit. The trace is 1,024 frames and 1,021 of them are
`ViewAttributeSet.getAttribute` (511 times) alternating with `doGetAttribute` (510 times).
**There is no VASSAL frame anywhere in the trace.** Java truncates a printed stack at 1,024
frames, so everything of ours was cut off the bottom.

What the log does give is the timing — the module loaded at 21:43 and the crash came at 23:06,
an hour and twenty-three minutes into play. Nothing happened in between worth logging.

## Reading the trace

The useful move is to stop treating the repeating frames as noise and go and read them. From
`java.desktop/share/classes/javax/swing/text/html/StyleSheet.java`:

```java
class ViewAttributeSet extends MuxingAttributeSet {
    ...
    Object doGetAttribute(Object key) {
        Object retValue = super.getAttribute(key);
        if (retValue != null) {
            return retValue;
        }
        ...
        AttributeSet parent = getResolveParent();
        if (parent != null) {
            return parent.getAttribute(key);      // the recursive frame
        }
        ...
    }

    public AttributeSet getResolveParent() {
        if (host == null) {
            return null;
        }
        View parent = host.getParent();
        return (parent != null) ? parent.getAttributes() : null;
    }
```

So the recursion is Swing resolving an inherited CSS attribute by walking from a view up to the
root of the **view tree**, one stack frame per level. It cannot run away on its own; it is bounded
by the depth of the document. 510 frames means an HTML document about 500 levels deep.

That is the whole diagnosis, and it narrows the search to one question: which HTML document in
VASSAL gets hundreds of levels deep?

## Root cause

The chat window is the one HTML document in VASSAL that grows for the length of a session.
`vassal-app/src/main/java/VASSAL/build/module/Chatter.java`:

```java
  private void doShow(String s) {
    ...
    kit.insertHTML(doc, doc.getLength(), "\n<div class=\"" + style + "\">" + s + "</div>", 0, 0, null);
```

`HTMLEditorKit.insertHTML` treats its string as a **fragment** inserted into the document that is
already there, not as a document of its own. An `<html>` tag in a fragment therefore does not
start anything — Swing's HTML reader opens two more levels of nesting which are never closed, and
everything appended afterwards sits inside them.

So the conversation document gets **two levels deeper for every message that contains an `<html>`
tag**, permanently, for as long as the session lasts.

Module designers wrap text in `<html>...</html>` all the time, because that is what a Swing label
needs and the habit carries over. A module that does it for its report messages deepens the chat
document steadily through the game until a repaint walks a chain too long for the stack. Hence an
hour and twenty-three minutes, and hence a trace with nothing of ours in it: the overflow happens
inside Swing's own painting, hundreds of frames below anything VASSAL called.

The message has to reach the HTML path to do this. `doShow` escapes `<` and `>` to entities unless
HTML is allowed, which it is for a message starting `-`, and for one starting `*` when the message
carries a quick-colour code or the module has opted in through the `chatterHTMLSupport` global
option. Anything else is escaped and displays its `<html>` literally, harmlessly.

### What does and does not trigger it

Measured by inserting 10 and then 60 copies of each payload into a pane driven exactly as
`doShow` drives it, and taking the depth of the resulting document:

| message content | 10 messages | 60 messages | |
|---|---|---|---|
| `hello` | 5 | 5 | |
| `<html>hi</html>` | 24 | 124 | **grows, +2 per message** |
| `<html>hi` | 24 | 124 | **grows** — the closing tag is irrelevant |
| `<HTML>hi</HTML>` | 24 | 124 | **grows** — case does not matter |
| `<html lang="en">hi` | 24 | 124 | **grows** — attributes do not matter |
| `</html>hi` | 5 | 5 | a stray closing tag alone is harmless |
| `<body>hi</body>` | 5 | 5 | harmless |
| `<head><title>x</title></head>hi` | 5 | 5 | harmless |
| `< html>hi` | 5 | 5 | not a tag to Swing's parser |
| `<div>hi` (unclosed) | 6 | 6 | one level, then stable |
| `<table><tr><td>hi` (unclosed) | 8 | 8 | three levels, then stable |

Only the **opening `<html>` tag** accumulates. Every other malformed fragment tried — unclosed
`<div>`, `<span>`, `<b>`, `<table>`, stray closing tags — costs a fixed number of levels once and
then stops, because the HTML reader closes what it opened at the end of each fragment.

Growth is exactly two levels per `<html>` message — `2n + 4` for n of them — and it is the count
of *those* messages that matters, not the traffic: one `<html>` message followed by forty plain
ones leaves the document at depth 7, not 85. (The plain messages that follow sit inside the
nesting the first one opened, which is the extra level over `2n + 4`.)

## Reproducing the crash

Driving a `JTextPane` the way `doShow` does and then painting it, on a thread with a 512 KB stack
(the paint path itself uses hundreds of frames before it ever resolves an attribute):

| messages, each `<html>`-wrapped | document depth | paint |
|---|---|---|
| 50 | 104 | ok |
| 100 | 204 | ok |
| 200 | 404 | ok |
| 300 | 604 | ok |
| 500 | 1004 | **StackOverflowError** |

(Depths at 300 and 500 follow the `2n + 4` law measured above; the others were read off directly.)

The overflow lands on `StyleSheet$ViewAttributeSet.doGetAttribute`, matching the reported trace
frame for frame — **including the JDK line numbers 210, 2873 and 2853**, which happen to be the
same on the JDK 25 used to reproduce and the JDK 26 the reporter ran.

Where exactly it falls over depends on the stack size and on how much of the stack the rest of the
paint has already used, so the number of messages a given player survives varies. The mechanism
does not.

## The fix

Strip `<html>`, `<head>`, `<body>` and their closing forms from a message before inserting it.
They have nothing to delimit inside a fragment. Everything else — `<span>`, `<img>`, `<b>`,
colours, the message text itself — is left exactly as the module wrote it, so nothing that
displays correctly today changes.

The engine already did this elsewhere, which is the strongest argument that it is the right shape
of fix. `vassal-app/src/main/java/VASSAL/tools/swing/DataArchiveTextPane.java`, the pane behind
piece text labels, has carried it since 2023:

```java
    // Remove original HTML markers if present and replace wrapping a styled div, inserting into document
    final String fixed = text.replaceFirst("<html>", "").replaceFirst("</html>", "");
```

It has been there since the class was written, in March 2023. That pane inserts once per instance,
so its nesting is bounded at two levels and the wrapper would have been harmless there anyway —
yet it was still stripped, because module text was known to carry it. The Chatter, where it is not harmless, never got the same treatment.

`#15344` adds the equivalent to `Chatter`, as a case-insensitive regex rather than two literal
`replaceFirst` calls, plus a `ChatterTest` covering both halves: that the document-structure tags
go and other markup stays, and the regression itself — that feeding a pane N messages the way the
Chatter does leaves the depth unchanged as N grows. The test first asserts that an *unstripped*
wrapper does deepen the document, so it cannot quietly stop meaning anything if Swing's fragment
handling ever changes.

### Review: the head element has to go whole

Stripping `<head>` and `</head>` and leaving what sat between them was wrong, as uckelman pointed
out in review, and not merely untidy. The head's contents are not all invisible. Swing's
`HTMLDocument.HTMLReader` discards the text of a `<title>`, but the body of a `<style>` is
inserted as ordinary text, so

```html
<html><head><style>b{color:red}</style></head><body>* A unit moved to 1204</body></html>
```

printed `b{color:red}* A unit moved to 1204` in the chat window. Verified by inserting the
fragment into a real `JTextPane` and reading back `doc.getText`.

So the head element is matched whole, contents and all, before the remaining structure tags are
stripped:

```java
  private static final Pattern HEAD_ELEMENT =
    Pattern.compile("<head\\b[^>]*>.*?</head\\b[^>]*>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
```

Non-greedy and `DOTALL`, so a head spanning several lines goes; `\b` keeps `<header>` out of it;
and the second pass still catches an unpaired `<head>` or `</head>`, which has no contents to take
with it but must not reach the document either.

One behaviour change falls out of this and is worth stating rather than discovering later: a
`<style>` in a message's head is now **dropped**, where Swing would previously have registered it
into the pane's shared stylesheet — affecting every later message, not just the one carrying it.
Dropping it is the better of the two, but it is not a no-op.

## A second issue, same cause

[#13117](https://github.com/vassalengine/vassal/issues/13117) (*[Star Trek The Dice Game]
StackOverflowError*, open since 3.7.7) is the same fault, and it is a good illustration of why
these reports are worth opening the log for.

Its issue body quotes only the four deepest frames:

```
  at java.desktop/javax.swing.text.StyleContext$NamedStyle.getAttribute(StyleContext.java:1496)
  at java.desktop/javax.swing.text.html.MuxingAttributeSet.getAttribute(MuxingAttributeSet.java:202)
  at java.desktop/javax.swing.text.html.MuxingAttributeSet.getAttribute(MuxingAttributeSet.java:202)
  at java.desktop/javax.swing.text.html.MuxingAttributeSet.getAttribute(MuxingAttributeSet.java:202)
```

Different classes, different line numbers, no obvious connection to #15300 — a GitHub search for
`ViewAttributeSet` does not even return it. The attached log holds two crashes, at 18:26:58 and
18:33:47, and each is **510 frames of `StyleSheet$ViewAttributeSet.getAttribute` /
`doGetAttribute`** at lines 2817 and 2840, the JDK 21 numbering for the same two lines. Identical
recursion, identical depth. The quoted four frames are just the deepest point of the same chain,
where it bottoms out in the style the walk finally resolves against.

Both crashes came from an `Editor` process that was being played in — the log is full of
`SendToLocation` traits firing and `Return To Deck` commands — so report messages were flowing
into the chat window throughout.

The lesson generalises: **an ABR's four quoted frames are the deepest four, which for a recursive
overflow are the least characteristic ones.** Two reports of the same bug can look unrelated in
the issue list and be identical in the log.

## Searching the tracker for the rest

The tracker returns about fifty issues for `StackOverflowError`. These two are the only ones with
this signature: the open ones were checked individually, the rest by searching issue bodies for
the frame classes. The others fall into clear families, none of them HTML:

| family | top frames | examples |
|---|---|---|
| trait chain recursion | `VASSAL.counters.Decorator.getState` / `getProperty` | #12502, #12351 |
| BeanShell expression parsing | `bsh.Parser.*` | #15209, #13063, #13685 |
| regex backtracking | `java.util.regex.Pattern$*.match` | #13621 |
| piece decoding | `SequenceEncoder$Decoder.nextToken` | #11249 |

Searching issue bodies for a class name only finds reports whose quoted four frames happen to
contain it, which — per the previous section — is a lottery. Searching for the *second* and later
distinct frames in a recursive trace is more reliable than searching for the first.

[vassal-record-module-load-crash.md](vassal-record-module-load-crash.md) is the same lesson from
the other end: there the thrown exception is an `ExecutionException` wrapping the real one, so the
four quoted frames are the *outermost* and are identical for every failure anywhere in module
loading. Between them the two notes give the rule — an ABR's quoted frames are whichever four sit
at the top of the printed trace, which is an accident of the exception's shape, not a summary of
the fault. Only the attached log tells one report from another.

[vassal-custom-code-misattribution.md](vassal-custom-code-misattribution.md) is the third case, and
the one that does not even need the log: there the four frames are the *whole* trace, complete and
correct, and still misread — because they are a module's own compiled classes and nothing in a
VASSAL stack trace said which frames were not VASSAL's.

[vassal-apply-on-move-recursion.md](vassal-apply-on-move-recursion.md) is this same case again, and
the sharpest instance of it: five reports of one bug, quoting five *disjoint* sets of four
BeanShell frames, none of which is the fault. Twenty-six of those 1,024 frames are BeanShell and
998 are VASSAL.

[vassal-room-list-concurrency.md](vassal-room-list-concurrency.md) is the limiting case: there all
four quoted frames are the JDK's own bounds checking, so the report names neither the fault nor
the subsystem it is in. The log holds twelve more frames and the first of them is the bug.

## What this means for the utility

Nothing directly: the extension utility does not render chat, and none of its features touch
`Chatter`. It is filed here because the diagnosis belongs with the other engine notes, and because
the failure mode — module-authored HTML reaching Swing's HTML reader with nothing sanitising it in
between — is the same exposure as
[#13497](https://github.com/vassalengine/vassal/issues/13497), where malformed HTML in a module
chart raised `Unexpected top level block close` from the same reader.

For module designers the practical note is simpler: **do not wrap chat and report messages in
`<html>...</html>`.** Until a build carrying #15344 is in general use, every such message costs the
session two levels of nesting it never gets back.
