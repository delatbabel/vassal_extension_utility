# VASSAL crash: a module whose custom code contains a record will not load

This note documents a crash in the VASSAL engine (not in this utility), reported as
[#15262](https://github.com/vassalengine/vassal/issues/15262) and fixed by
[#15345](https://github.com/vassalengine/vassal/pull/15345). It is triggered by a single Java
`record` anywhere in a module's custom classes.

## Symptom

The module does not load at all. The launcher puts up a bug dialog, and the automated report
quotes this:

```
java.util.concurrent.ExecutionException: java.lang.StringIndexOutOfBoundsException: Index 18 out of bounds for length 18
  at java.base/java.util.concurrent.FutureTask.report(FutureTask.java:124)
  at java.base/java.util.concurrent.FutureTask.get(FutureTask.java:193)
  at java.desktop/javax.swing.SwingWorker.get(SwingWorker.java:610)
  at VASSAL.launch.AbstractLaunchAction$LaunchTask.done(AbstractLaunchAction.java:510)
```

Four frames of `SwingWorker` plumbing, saying only that *something* threw on the launch worker.
Nothing about what, or where.

## The cause is in the log, not the report

The attached error log — fetched as [reading-abr-error-logs.md](reading-abr-error-logs.md)
describes — carries the `Caused by` the issue body drops, and it is the whole diagnosis:

```
Caused by: java.lang.StringIndexOutOfBoundsException: Index 18 out of bounds for length 18
  at java.base/java.lang.String.charAt(String.java:1785)
  at org.objectweb.asm.Type.getReturnTypeOffset(Type.java:381)
  at org.objectweb.asm.Type.getReturnType(Type.java:358)
  at VASSAL.tools.deprecation.DependencyWalker.addMethodDesc(DependencyWalker.java:429)
  at VASSAL.tools.deprecation.DependencyWalker.addConstant(DependencyWalker.java:464)
  at VASSAL.tools.deprecation.DependencyWalker.addConstants(DependencyWalker.java:470)
  at VASSAL.tools.deprecation.DependencyWalker$MethodDependencyVisitor.visitInvokeDynamicInsn(DependencyWalker.java:322)
  ...
  at VASSAL.launch.AbstractLaunchAction.checkRemovedAndDeprecated(AbstractLaunchAction.java:196)
```

It is the removed-and-deprecated check, which walks the bytecode of a module's custom classes
looking for VASSAL API that has been deprecated or removed. It runs *before* the module is
opened, so when it throws, nothing loads.

The frame that matters is the fourth from the bottom. `visitInvokeDynamicInsn` is handing the
bootstrap arguments of an `invokedynamic` instruction to `addConstants`, and one of them is
reaching `addMethodDesc`, which is giving ASM something that is not a method descriptor.

## Root cause

`vassal-app/src/main/java/VASSAL/tools/deprecation/DependencyWalker.java`:

```java
  private void addConstant(Object cst) {
    if (cst instanceof Type) {
      addType((Type) cst);
    }
    else if (cst instanceof Handle) {
      final Handle h = (Handle) cst;
      addInternalName(h.getOwner());
      addMethodDesc(h.getDesc());      // line 464
    }
  }

  private void addMethodDesc(String desc) {
    addType(Type.getReturnType(desc));  // line 429
    ...
```

A method handle's descriptor is a **method** descriptor only when the handle refers to a method or
a constructor. The JVM has nine method-handle kinds, and four of them — `H_GETFIELD`,
`H_GETSTATIC`, `H_PUTFIELD`, `H_PUTSTATIC` — refer to a *field*, and carry a **field** descriptor
instead. `addConstant` did not look at the tag.

`Type.getReturnType` works by finding the `')'` that closes the argument list. A field descriptor
has no `')'`, so the scan runs past the end of the string.

### Why a record

Nothing in a module has to mention method handles for this to fire. Since Java 16 the compiler
builds a record's `equals`, `hashCode` and `toString` out of a single `invokedynamic` to
`java.lang.runtime.ObjectMethods.bootstrap`, whose trailing bootstrap arguments are **one
field-access handle per component**. The author writes:

```java
public record Hex(String name, int row) { }
```

and the class file carries two `H_GETFIELD` handles as `invokedynamic` constants. The walker meets
the first one and dies.

So the condition for the crash is just "the module's custom code was compiled as Java 16 or later
and contains a record" — which is to say, ordinary modern Java, and increasingly likely as module
authors move to current JDKs.

### Where the 18 comes from

`Ljava/lang/String;` is exactly eighteen characters:

```
L j a v a / l a n g / S t r i n g ;
1 2 3 4 5 6 7 8 9 ...            18
```

so *"Index 18 out of bounds for length 18"* names the component type precisely: the reporter's
record has a `String` in it.

The scan always falls off the end exactly one past the last character, so the index and the length
in the message are always equal and always the length of the descriptor it choked on:

| field descriptor | length | message |
|---|---|---|
| `Ljava/lang/String;` | 18 | Index 18 out of bounds for length 18 |
| `I` (or `J`) | 1 | Index 1 out of bounds for length 1 |
| `[Ljava/lang/String;` | 19 | Index 19 out of bounds for length 19 |
| `Lfoo/Bar;` | 9 | Index 9 out of bounds for length 9 |

So the exception message is a descriptor-length readout, and a future report of this fault can be
narrowed to the component type before the module is ever opened: a record of `(int, int)` would
have said "Index 1 out of bounds for length 1".

## Reproducing it

A two-component record is enough. Walking the compiled `record Sample(String name, int count) { }`
with `DependencyWalker` throws:

```
java.lang.StringIndexOutOfBoundsException: Index 18 out of bounds for length 18
	at VASSAL.tools.deprecation.DependencyWalker.addMethodDesc(DependencyWalker.java:429)
	at VASSAL.tools.deprecation.DependencyWalker.addConstant(DependencyWalker.java:464)
	at VASSAL.tools.deprecation.DependencyWalker.addConstants(DependencyWalker.java:470)
	at VASSAL.tools.deprecation.DependencyWalker$MethodDependencyVisitor.visitInvokeDynamicInsn(DependencyWalker.java:322)
```

— the reported exception, with the reported message, at the reported line numbers.

## The fix

Dispatch on the handle's tag, and read a field-access handle's descriptor with the `addDesc` that
already exists for field descriptors:

```java
  private void addHandleDesc(Handle h) {
    switch (h.getTag()) {
    case Opcodes.H_GETFIELD:
    case Opcodes.H_GETSTATIC:
    case Opcodes.H_PUTFIELD:
    case Opcodes.H_PUTSTATIC:
      addDesc(h.getDesc());
      break;
    default:
      addMethodDesc(h.getDesc());
      break;
    }
  }
```

The class named by the descriptor is still recorded as a dependency either way, so the check keeps
its coverage: a record component's type is examined exactly as a method's parameter type is. That
matters enough to be its own test — a fix that simply swallowed the descriptor would also stop the
crash, and would silently blind the checker to every record component in every module.

`H_NEWINVOKESPECIAL`, the constructor kind, has a method descriptor ending in `)V` and goes through
the default branch unchanged.

### A second gap in the same method

`addConstant` matched `Type` and `Handle` and ignored everything else, so a `ConstantDynamic`
bootstrap argument was skipped along with its type and its own bootstrap arguments. That is not the
crash — an ignored constant throws nothing — but it is the same oversight, and it means a module
using a condy quietly escapes the check the class exists to perform. The fix walks it.

### The other call sites

`addMethodDesc` has four other callers, and all four were checked: `visitMethod`,
`visitMethodInsn`, `visitInvokeDynamicInsn` and the `Type.METHOD` case of `addType`. Each is
reached only with a genuine method descriptor, so the handle branch was the only way in.

## Searching the tracker, and why it proved little

No other issue reports this cause — and that result is close to meaningless, which is the point
worth recording.

The [#15300 note](vassal-chatter-html-nesting.md) ends on the observation that an automated report
quotes the *deepest* four frames, which for a recursive overflow are the least characteristic ones.
This report is the opposite failure and just as blinding: the exception is an `ExecutionException`
wrapping the real one, so the four quoted frames are the *outermost* — `FutureTask.report`,
`FutureTask.get`, `SwingWorker.get`, `LaunchTask.done` — which are the same four for **every**
failure anywhere in module loading. A tracker search for `DependencyWalker`, `objectweb` or
`getReturnType` returns nothing at all, because none of those words appears in any issue body.

So the two notes between them give the rule: **an ABR's quoted frames are whichever four sit at the
top of the printed trace, and that is an accident of the exception's shape, not a summary of the
fault.** ([vassal-custom-code-misattribution.md](vassal-custom-code-misattribution.md) adds a third
case, where the quoted frames are the complete trace and are still misread, because they belong to
the module's own compiled code rather than to VASSAL, and
[vassal-apply-on-move-recursion.md](vassal-apply-on-move-recursion.md) a fourth, where five reports
of one bug quote five disjoint sets of frames and no two of them can be found by the same search;
[vassal-room-list-concurrency.md](vassal-room-list-concurrency.md) a fifth, where all four quoted
frames are the JDK's own bounds checking and name neither the fault nor the subsystem it is in;
and [vassal-module-manager-edt.md](vassal-module-manager-edt.md) a sixth and most extreme, where
all four belong to a third-party widget and none to VASSAL at all — five maintainers closed
reports on that basis, when the absence was itself the signature of the fault, a threading
violation whose offending thread had returned long before the trace was taken; and
[vassal-trailing-backslash-state.md](vassal-trailing-backslash-state.md) a seventh, which inverts
the lesson -- there all four frames are accurate, VASSAL's own, and name the fault exactly, and
sixteen reports of it still went ungrouped for two and a half years, because the *message* embeds
the whole type string of a trait and so no two of them share a title; and
[vassal-multi-piece-editor-drift.md](vassal-multi-piece-editor-drift.md) an eighth, where the
first VASSAL frame is accurate but is `SequenceEncoder`, which is called from everywhere, so the
seven reports it groups are three unrelated faults and the *second* frame is what tells them
apart.)
Only the attached log distinguishes one report from another. The two other open
`StringIndexOutOfBoundsException` reports whose logs could be checked, #12471 and #13411, have no
`DependencyWalker` frame in them and are something else; beyond that, any duplicate of this bug
would be invisible in the issue list.

[#15330](https://github.com/vassalengine/vassal/pull/15330) is adjacent rather than related: it
reclassifies failures on this same `LaunchTask.done()` path that are problems with the user's
environment rather than with VASSAL. This one is the counter-case on that path — a genuine defect,
to be fixed rather than reported more kindly — so the two do not overlap, but anyone working
through that path wants both.

## What this means for the utility

Nothing directly: the extension utility does not run the removed-and-deprecated check, and it reads
a module's `buildFile.xml` and images rather than its custom classes.

It matters to anyone shipping a module with custom code. Until a build carrying #15345 is in
general use, **a record in a module's custom classes makes that module unloadable** on 3.8.0 — not
degraded, not warned about, but refused at launch with a bug dialog that names nothing useful. The
workaround is to avoid records in module code, or to rewrite the record as a plain class.
