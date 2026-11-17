# VASSAL crash: the room list is read while the network thread is editing it

This note documents a crash in the VASSAL engine (not in this utility), reported as
[#15107](https://github.com/vassalengine/vassal/issues/15107) and fixed by
[#15351](https://github.com/vassalengine/vassal/pull/15351). It is a data race between a chat
client's network thread and the event dispatch thread, and it needs nothing unusual from the
module — only that someone leaves a room at the moment the room list is being redrawn.

## Symptom

The automated bug report quotes four frames, every one of them JDK bounds-check plumbing:

```
java.lang.IndexOutOfBoundsException: Index 1 out of bounds for length 1
 at java.base/jdk.internal.util.Preconditions.outOfBounds(Preconditions.java:100)
 at java.base/jdk.internal.util.Preconditions.outOfBoundsCheckIndex(Preconditions.java:106)
 at java.base/jdk.internal.util.Preconditions.checkIndex(Preconditions.java:302)
 at java.base/java.util.Objects.checkIndex(Objects.java:365)
```

Nothing there names a class that VASSAL wrote. The full log (fetched as
[reading-abr-error-logs.md](reading-abr-error-logs.md) describes) carries the other twelve
frames, and the first four of *those* are the whole diagnosis:

```
	at java.base/java.util.ArrayList.get(ArrayList.java:428)
	at java.base/java.util.Collections$UnmodifiableList.get(Collections.java:1507)
	at VASSAL.chat.ui.RoomTree.setRooms(RoomTree.java:106)
	at VASSAL.chat.ui.ChatServerControls.lambda$setClient$1(ChatServerControls.java:268)
```

`RoomTree.java:106` is still `p.get(j)` on `release-3.8` — the file has not moved since the
report.

## Root cause

`vassal-app/src/main/java/VASSAL/chat/SimpleRoom.java` handed out a **live view** of its own
collection:

```java
private final List<Player> players = new ArrayList<>();

@Override
public List<Player> getPlayerList() {
  return Collections.unmodifiableList(players);
}
```

Unmodifiable by the caller, but not a copy: everything the room does to `players` afterwards
shows through it. `RoomTree.setRooms` brings the number of player nodes into line with that
list's size and then indexes it:

```java
final List<Player> p = roomAt(i).getPlayerList();
while (p.size() < node.getChildCount()) { ... removeNodeFromParent ... }
while (p.size() > node.getChildCount()) { ... insertNodeInto ... }
for (int j = 0; j < node.getChildCount(); ++j) {
  model.valueForPathChanged(..., p.get(j));          // line 106
}
```

The two `while`s guarantee `node.getChildCount() == p.size()` — at the instant they finish. A
player leaving between them and the `for` makes the last line ask for an element that is no
longer there.

### The two threads

The reporter's session had been in peer-to-peer mode for two and a half hours, with peers
joining throughout (`P2P模式已启动于端口 5050`, then a long run of `同伴 … 已连接`).

| | |
|---|---|
| **writer** | `org.litesoft.p2pchat.ActivePeer`, which names its own thread `"Active Peer Thread"` (`ActivePeer.java:87`). Its `run()` loop calls `UserDialog.showHELO` / `showNAME` / `showDisconnect`, which `P2PClient` implements by calling `RoomManager.update` / `remove` — and those call `SimpleRoom.addPlayer` / `removePlayer`. |
| **reader** | the EDT. `ChatServerControls.setClient` registers a listener that posts `roomTree.setRooms(...)` through `SwingUtilities.invokeLater`. |

`RoomManager.getRooms()` returns `rooms.toArray(new Room[0])` — the live `SimpleRoom` objects
themselves, not copies — so the array handed to the EDT is a set of objects the peer thread is
still editing.

That also accounts for the one oddity in the log's timing: the last *logged* event is a peer
connecting at 19:21:12 and the crash is at 19:24:02, nearly three minutes later. A **dis**connect
is not logged, and a disconnect is exactly what shrinks a player list.

## A second instance of the same race, in the renderer

`RoomTreeRenderer` asked the room for its list three separate times in four lines:

```java
if (!room.getPlayerList().isEmpty() && room.getPlayerList().get(0).getStatus() instanceof SimpleStatus) {
  final SimpleStatus status = (SimpleStatus) room.getPlayerList().get(0).getStatus();
  ...
final List<Player> players = room.getPlayerList();
```

Three calls, three different live views. The emptiness test is made against one list and
`get(0)` is called on another, so a room emptying in between throws `Index 0 out of bounds for
length 0` — from inside a paint. No report of that one has been filed, but it is the same bug in
a worse place.

## The fix

`SimpleRoom` synchronizes every method which touches the player list, and `getPlayerList()`
returns an unmodifiable **snapshot**. A list once handed out does not change under its reader.
`NodeRoom` is the only other `Room` implementation in the tree and extends `SimpleRoom`,
reaching `players` only through these methods, so one change covers both the official server
and peer-to-peer.

Two callers are corrected to match:

- **`RoomTree.setRooms`** copies the list before measuring it. `Room` is a public interface and
  an implementation is free to return a live view; the tree should not depend on it not doing
  so.
- **`RoomTreeRenderer`** reads once.

While in `setRooms`, one more thing was wrong on its face. The placeholder node inserted when a
room gains a player was

```java
model.insertNodeInto(new DefaultMutableTreeNode(room[i]), node, 0);
```

— a `Room` object in a *player's* position, indexed out of the caller's array by the **tree's**
own child index. Both `model.isLeaf` and `RoomTreeRenderer` switch on `instanceof Player`, so a
repaint landing between the insert and the fix-up three lines later saw a room where a player
belonged. Every such node has its user object set from the player list immediately afterwards,
so the placeholder is now empty.

## Reproducing it deterministically

A race can be tested without threads if you can get at the window directly. `setRooms` writes
each player back through `model.valueForPathChanged`, which fires `treeNodesChanged`, so a
`TreeModelListener` is called **between** the loop that matched the node count and the next
`p.get(j)`:

| step | state |
|---|---|
| `setRooms` with a 2-player room | tree node has 2 children |
| `setRooms` again — both `while`s no-op | `p.size() == 2 == childCount` |
| `j = 0` → `valueForPathChanged` → listener removes a player | `p.size()` drops to 1 |
| `j = 1` → `p.get(1)` | **`IndexOutOfBoundsException: Index 1 out of bounds for length 1`** |

That is the reported message, verbatim, against the unfixed code. `RoomTreeTest` does exactly
this; `SimpleRoomTest` asserts the snapshot contract directly and also hammers a room from a
second thread while reading it.

Red check: reverting the three source files fails 3 of the 6 new tests, including the
reproduction above. With the fix all 6 pass and the full `vassal-app` suite is 854 tests, 0
failures.

## The lesson, again

This is the fifth note here about an automated bug report whose quoted frames point nowhere
useful, and a new variant: **all four frames were the JDK's own bounds checking**, so the report
named neither the fault nor even the subsystem. A tracker search for anything in the issue body
would not find it, and the title (`IndexOutOfBoundsException: Index 1 out of bounds for length
1`) could belong to any of a hundred bugs.

The other four, each a different way for the same thing to go wrong:

- [vassal-chatter-html-nesting.md](vassal-chatter-html-nesting.md) — the quoted frames are the
  deepest of a recursion, so they are the least characteristic ones.
- [vassal-apply-on-move-recursion.md](vassal-apply-on-move-recursion.md) — same, five reports
  quoting five *disjoint* sets of four frames.
- [vassal-record-module-load-crash.md](vassal-record-module-load-crash.md) — the quoted frames
  are the outermost wrapper, identical for every failure anywhere in module loading.
- [vassal-custom-code-misattribution.md](vassal-custom-code-misattribution.md) — the quoted
  frames are the complete and correct trace, and still misread, because nothing said which
  frames were not VASSAL's.

Between them the rule is: an ABR's quoted frames are whichever four sit at the top of the
printed trace, which is an accident of the exception's shape. Fetch the log.

## What this means for the utility

Nothing directly — the extension utility does not use VASSAL's chat code. It is filed here
because the diagnosis belongs with the other engine notes, and because the shape of the bug is
one worth recognising: a collection published to another thread as an "unmodifiable" view, which
protects the *caller* from writing to it and does nothing at all about the owner still writing
to it.
