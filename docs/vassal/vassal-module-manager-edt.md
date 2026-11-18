# VASSAL bug: the Module Manager's tree is changed from the wrong thread

This note documents a bug in the VASSAL engine (not in this utility), reported **thirteen
times** between 2021 and 2026 and fixed by
[#15362](https://github.com/vassalengine/vassal/pull/15362). It is this folder's clearest case
of a trace whose **absent** frames were the evidence: there is no VASSAL frame in it at all,
and that is precisely what the fault predicts.

## Symptom

An internal-error dialog while clicking about in the Module Manager, usually while deleting a
module from the library. Every report carries the same message and, in twelve of the thirteen,
the same four frames:

```
java.lang.IllegalArgumentException: node must be a TreeTableNode managed by this model
 at org.jdesktop.swingx.treetable.DefaultTreeTableModel.isLeaf(DefaultTreeTableModel.java:303)
 at org.jdesktop.swingx.JXTreeTable$TreeTableHackerExt5.getEventForTreeRenderer(JXTreeTable.java:706)
 at org.jdesktop.swingx.JXTreeTable$TreeTableHackerExt4.hitHandleDetectionFromProcessMouse(JXTreeTable.java:613)
 at org.jdesktop.swingx.JXTreeTable.processMouseEvent(JXTreeTable.java:435)
```

Four frames of SwingX, nothing else. The line numbers drift with the SwingX version; the
methods do not.

## The family

| issue | version | opened | | issue | version | opened |
|---|---|---|---|---|---|---|
| [#15104](https://github.com/vassalengine/vassal/issues/15104) | 3.7.27 | 2026-09-23 | | [#13436](https://github.com/vassalengine/vassal/issues/13436) | 3.7.12 | 2024-07-01 |
| [#14565](https://github.com/vassalengine/vassal/issues/14565) | 3.7.20 | 2026-03-21 | | [#13160](https://github.com/vassalengine/vassal/issues/13160) | 3.7.8 | 2024-02-17 |
| [#14384](https://github.com/vassalengine/vassal/issues/14384) | 3.7.18 | 2025-12-06 | | [#12465](https://github.com/vassalengine/vassal/issues/12465) | 3.6.19 | 2023-07-12 |
| [#14325](https://github.com/vassalengine/vassal/issues/14325) | 3.7.18 | 2025-10-27 | | [#12438](https://github.com/vassalengine/vassal/issues/12438) | 3.6.19 | 2023-07-04 |
| [#14135](https://github.com/vassalengine/vassal/issues/14135) | 3.7.16 | 2025-07-01 | | [#11393](https://github.com/vassalengine/vassal/issues/11393) | 3.6.6 | 2022-04-23 |
| [#14092](https://github.com/vassalengine/vassal/issues/14092) | 3.7.16 | 2025-05-31 | | [#10457](https://github.com/vassalengine/vassal/issues/10457) | 3.5.8 | 2021-09-24 |
| | | | | [#9867](https://github.com/vassalengine/vassal/issues/9867) | 3.5.3 | 2021-06-28 |

The six on the left were open; the seven on the right were closed without a fix. Five of those
closures say some version of *there is nothing of ours in the stack*:

> none of our code in the stack - not enough data to investigate — #10457
>
> No vassal code to look at — #11393

Which was a fair reading. #9867 went further and got most of the way there:

> This appears to be some kind of not-quite-threadsafe behavior in JXTreeTable -- we've removed
> a node just at the very moment it decided to try to display some tooltip text for it. Not
> worth further concern unless it starts showing up a bunch.

A node removed, and the view still holding a row for it: that is the shape of it exactly. The
part not visible from the trace is that the removal was not racing a tooltip. It was racing
VASSAL. And it did start showing up a bunch — twelve more reports over the following five years.

## What the exception requires

Read from the SwingX sources (`swingx-all` 2.2, `com.github.wumpz.swingx`):

```java
public boolean isLeaf(Object node) {
    if (!isValidTreeTableNode(node)) {
        throw new IllegalArgumentException("node must be a TreeTableNode managed by this model");
    }
    return ((TreeTableNode) node).isLeaf();
}

private boolean isValidTreeTableNode(Object node) {
    boolean result = false;
    if (node instanceof TreeTableNode ttn) {
        while (!result && ttn != null) {
            result = ttn == root;
            ttn = ttn.getParent();
        }
    }
    return result;
}
```

Validation is a walk up the parent chain to the model root. So the message means one thing
only: **the node is detached from the root**. Nothing detaches a node but `remove`, which calls
`setParent(null)`.

And the caller got that node from the view:

```java
int row = rowAtPoint(pt);
if (row >= 0) {
    TreePath path = getPathForRow(row);
    Object node = path.getLastPathComponent();
    if (!getTreeTableModel().isLeaf(node) ...
```

So the tree's row→path layout cache still had a row for a node the model no longer held. The
view and the model had diverged — the whole fault in one sentence.

### The window that makes it reachable

`DefaultTreeTableModel.removeNodeFromParent` detaches *before* it announces:

```java
int index = parent.getIndex(node);
node.removeFromParent();              // detached here
modelSupport.fireChildRemoved(...);   // the layout cache hears about it here
```

Between those two statements the model will already refuse to answer for a node that every
view still has a row for. On a single thread that window closes before anything can look into
it. Two threads is what opens it.

## Root cause

`vassal-app/src/main/java/VASSAL/launch/AbstractLaunchAction.java:468`, inside
`LaunchTask.doInBackground()` — a `SwingWorker` background method:

```java
final ModuleManagerWindow mmw = ModuleManagerWindow.getInstance();
if (lr.module != null) {
  mmw.addModule(lr.module);
}
mmw.setWaitCursor(false);
```

`addModule` (`ModuleManagerWindow.java:990`) calls `treeModel.insertNodeInto` twice and
`updateModuleList()`, which also drives a `CardLayout`. All Swing, none of it on the Event
Dispatch Thread. `AbstractLaunchAction` contained no `invokeLater` or `invokeAndWait` anywhere.

The class had every reason to know better:

- `TilingHandler`, constructed and run by **the same method a few lines earlier**, already uses
  `EDT.execute` for its dialog work.
- `ModuleManagerWindow.updateRequest` already exists for exactly this purpose —
  `SwingUtilities.invokeLater(() -> update(f))` — and the socket handler goes through it.

Every *removal* path was already EDT-driven: `removeModule`, `ModuleInfo.refresh`, the popup's
Remove and Refresh actions, and `update` via `updateRequest`. So the EDT supplies the
detachment and the launch worker supplies the concurrent structural change that leaves the
layout cache behind.

### What is proven, and what is inferred

Worth separating, because the temptation to overclaim here is strong.

**Proven**, by reading the sources and by the test that ships with the fix: that the message
means a detached node; that the caller takes that node from the view's row mapping; and that
`removeNodeFromParent` exposes exactly that state to anything listening.

**Proven**, by reading VASSAL: that `doInBackground` mutates the tree model off the EDT, and
that it is the only such path.

**Inferred**: the precise interleaving in any one reporter's run. A data race leaves no record
of which thread was where. What can be said is that the Swing contract was broken on a path the
reports repeatedly implicate, and that the documented consequence of breaking it is a view and
model that disagree — which is what the exception reports.

## The reporters supplied the rest

Unusually, the issue bodies corroborate both halves without needing the logs at all.

**Repeat launches**, the off-EDT insert:

> I may have hit the module open button two or three times by mistake. — #14092

**Duplicate rows for one module**, which is what an unserialised test-then-insert produces:

> module library showed two modules labeled NLB Campaign (SPI) and one unlabeled module -
> delete one of the labeled modules & got an internal error — #14384
>
> clicked yes and saw I had two copies, one with a name. Removed the one without a name, but
> the named one disappeared. — #14135
>
> trying to delete a module from the library that loaded with no name — #14325

**Removal**, the EDT half:

> Failed to remove module from Module Manager — #9867's title

So the user-visible symptom was never only the dialog. It was **duplicate and unnamed module
rows in the library that then would not delete cleanly** — and nobody had connected the two,
because the duplicate rows never produced a stack trace to file.

`addModule`'s guard is `if (!rootNode.contains(f))`. Off the EDT that test is not atomic with
the insert that follows it, so two launches of one module can both pass it and both add a row.
On the EDT they are serialised, and this half of the bug disappears with the same fix.

### The log on #15104

The attached log shows the setup directly: four `SwingWorker-pool-1-thread-N` threads logged
*Loading module file* for the same module inside 1.5 seconds, four `ZipFileImageTiler`
subprocesses then raced on one tile directory and one died with `NoSuchFileException` writing a
tile, and about sixteen seconds later the EDT threw. The tiler failure is a second concurrency
fault with the same first cause — four launches of one module — and is not addressed here.

## The fix

Hand both calls to the EDT, in the idiom the window already uses for `update`:

```java
final ModuleManagerWindow mmw = ModuleManagerWindow.getInstance();
SwingUtilities.invokeLater(() -> {
  if (lr.module != null) {
    mmw.addModule(lr.module);
  }
  mmw.setWaitCursor(false);
});
```

`addModule`, `removeModule` and `update` are documented as EDT-only, so the next caller has
something to read.

`SwingUtilities.invokeLater` rather than VASSAL's own `EDT.execute`, on two grounds: it is what
`ModuleManagerWindow.updateRequest` already does for this same window, and `EDT.execute`
throws `RejectedExecutionException` once its executor is shut down, which would be a new
failure mode inside `doInBackground` for no gain. (Nothing currently shuts it down, so this is
caution rather than a bug.)

## Testing a race you cannot run

The shipped test, `ModuleManagerTreeModelTest`, pins the **mechanism** with the real SwingX
classes: that a node in the tree is accepted, that a detached one is rejected *with the
reported message verbatim*, and that `removeNodeFromParent` detaches before it notifies, so a
listener — and a tree's row cache is a listener — is handed a node the model already refuses.

It does not red-check the fix, and the PR says so rather than implying otherwise. The direct
assertion — *`addModule` called off the EDT runs its body on the EDT* — needs a
`ModuleManagerWindow`, and that class cannot be constructed **or even mocked** in a headless
test:

```
Mockito cannot mock this class: class VASSAL.launch.ModuleManagerWindow
Underlying exception: Cannot instrument class VASSAL.launch.ModuleManagerWindow
  because it or one of its supertypes could not be initialized
```

The reason is a single line, `ModuleManagerWindow.java:189`:

```java
private static final ModuleManagerWindow instance = new ModuleManagerWindow();
```

The singleton is eager, so merely *loading* the class builds the whole window, and
`MenuManager.getInstance()` throws because no menu manager is installed. CI is headless and the
project's one GUI test is commented out in its entirety, so there is no way around it.

[#15312](https://github.com/vassalengine/vassal/pull/15312) — an unrelated robustness change,
open at the same time — makes that singleton lazy. Checked on its branch: the mock then works,
and the assertion fails there exactly as a red check should, with `addModule` seen once on the
worker thread and never re-dispatched. The test becomes available as soon as that lands, which
is noted on the PR.

The general lesson is worth keeping: **an untestable fix is sometimes untestable for a reason
that is itself a defect**, and saying which line blocks the test is more useful than asserting
that races cannot be tested.

## What an ABR's four quoted frames tell you — the sixth case

[vassal-record-module-load-crash.md](vassal-record-module-load-crash.md) collects the running
list; this is the next entry, and the most extreme so far.

Here **all four frames belong to a third-party library and none to VASSAL**, and five
maintainers' closures took that at face value. But the fault is a threading violation, and the
defining property of a threading violation is that the offending thread is *not in the trace* —
it did its damage and returned long before the EDT noticed. The absence of VASSAL frames was
not evidence that VASSAL was uninvolved. It was consistent with the one explanation that fits.

#14092 makes the same point from the other side. Its four frames are different —

```
 at org.jdesktop.swingx.treetable.DefaultTreeTableModel.isLeaf(DefaultTreeTableModel.java:312)
 at java.desktop/javax.swing.plaf.basic.BasicTreeUI$NodeDimensionsHandler.getNodeDimensions(BasicTreeUI.java:3280)
 at java.desktop/javax.swing.tree.AbstractLayoutCache.getNodeDimensions(AbstractLayoutCache.java:497)
 at java.desktop/javax.swing.tree.VariableHeightLayoutCache$TreeStateNode.updatePreferredSize(VariableHeightLayoutCache.java:1344)
```

— and name the layout cache itself, asking the model about a node it is still holding. Same
divergence, a second way of noticing it, and a set of frames that no search for the other
twelve would ever have matched.

## What this means for the utility

Directly, a rule the extension utility has to keep, because it does the same kind of thing.
Several operations run on a `SwingWorker` and then touch Swing:

- `MainWindow.checkExcessUnits` (`MainWindow.java:1937`) and `swapMaps` (`:1780`, `:1829`) do
  their file work on a worker behind a modal progress dialog and then rebuild trees and lists;
- `optimiseImages` (`:736`) runs the scan on a worker and updates a determinate progress dialog;
- `RefreshWorker` (`:3409`) drives the Refresh Counters subprocess;
- `ArchivePanel.refresh()` rebuilds a `JTree` from a mutated DOM.

Checked, and they are right: every one of those `doInBackground()` bodies is pure file and
archive work, with the dialogs disposed and the models rebuilt in `done()`, which runs on the
EDT. That is the arrangement VASSAL's launch worker lacked.

The rule to keep is the one VASSAL broke: **`SwingWorker.doInBackground()` is not the EDT**.
Work goes there; the model and the view are touched in `done()`, in `process()`, or through
`SwingUtilities.invokeLater`. A `JTree` is the same trap as a `JXTreeTable` — the tree's layout
cache and its model are two structures kept in step only by events delivered on one thread, and
changing the model from a worker leaves rows pointing at nodes that are no longer there. The
utility's tree-state preservation does its capture-and-restore around `refresh()` on the EDT
for the same reason.

The second lesson is about diagnosis rather than code, and it is the one worth remembering:
**a stack trace records where the symptom surfaced, not where the fault was committed.** For
anything concurrent those are routinely different threads, and the trace cannot show the one
that mattered.
