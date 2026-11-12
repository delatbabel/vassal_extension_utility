# Why loading and joining take so long, measured

Measured 2026-11-11 against **WiF CE Official Combo 2.2.0** (4,372 PieceSlots, 11 extensions) and a real 8,192-piece scenario, on VASSAL **3.8.0 as released** and on **master** (`3.8.0-115-g74fb77416`, which carries changes 3.8.0 does not).

**Short version:** 80% of the CPU in a load is `SequenceEncoder`, and the cause is the *nested* trait-chain framing — reading 97 MB of trait type text makes the engine scan **3.9 GB** of characters. [#15116](https://github.com/vassalengine/vassal/pull/15116), merged into master four days ago but not in 3.8.0, removes that: the same load goes from **32.8 s to 5.3 s**. A second, still-unfixed cost is that every `GameState.setup()` re-encodes the entire game into a `String` purely to drive the "unsaved changes?" flag.

## 1. Where the time goes

Loading is split cleanly by `loadGameInForeground`: `decodeSavedGame(in)` then `loadCommand.execute()`. Timed outside the GUI, with the module already open:

| Phase | 3.8.0 released | master, old save | master, re-saved |
|---|---|---|---|
| `GameModule.init` (buildFile.xml) | 1.7–2.4 s | — | — |
| `decodeSavedGame` | **20.0 s** | 20.2 s | **2.6 s** |
| `Command.execute` | **12.8 s** | 8.4 s | **2.7 s** |
| **load total** | **32.8 s** | 28.6 s | **5.3 s** |
| `savedGame` command log | 172.7 MB | — | 86.4 MB |

The 3.8.0 column reproduces the reported 29 s + 8 s. The last column is what a 3.9 user sees **once the save has been written by the new code** — the framing is a write-side change, so an existing save still decodes the old way (middle column).

Nothing was lost in the re-save: both files hold 9,361 commands and 8,192 `AddPiece`, and the result is reproducible (2.60 s / 3.04 s on a repeat run).

## 2. The dominant cost: nested trait-chain framing

A JFR profile of the 3.8.0 load (`settings=profile`, `stackdepth=1024`) attributes **79.5% of all CPU samples to `SequenceEncoder`** — 59.2% in `Decoder.nextToken` and 20.3% in `appendEscapedString`.

The reason is the shape of the encoding. In the nested framing a trait's type is two top-level tokens: its own segment, and *the entire rest of the chain as one escaped token*. So `BasicCommandEncoder.createPiece` recurses, and at every level it re-scans, unescapes, allocates and interns almost the whole remaining string.

Walking this scenario's 8,192 piece types with the engine's own `SequenceEncoder.Decoder`, exactly as `createPiece` does:

| | |
|---|---|
| trait instances | 625,120 (76 per piece) |
| nesting depth | **113 max, 39 mean** |
| type text in the save | 97.2 MB |
| **characters actually scanned** | **3,932.6 MB — 40.4× amplification** |
| distinct trait type strings | 9,621 (**65× reuse**) |
| distinct trait text | 0.91 MB (4,344× less than is scanned) |

Tokenising alone — no objects constructed — takes **7.2 s**.

This is what [#15116 "Flatten trait chain encoding"](https://github.com/vassalengine/vassal/pull/15116) fixes, by writing one top-level token per trait so each is scanned once. It was **merged on 2026-11-07**, after 3.8.0 shipped. It is the single biggest item available, it is already done, and the measurements above are the whole case for getting it into users' hands: **6.2× on load, and the command log halves**.

### A smaller one inside the same method

`Decoder.nextToken` ends with `.intern()` on every token. On this data that costs **2.9 s of the 7.2 s** (1.7×). Interning was added for memory, but the nested *rest-of-chain* token is very nearly unique per piece, so those interns can never dedupe — they hash ~3.9 GB and add ~320k single-use entries to the JVM string table. #15116 removes most of this incidentally (the long tokens stop existing); what remains is worth a second look, because short trait segments are exactly the ones that *do* dedupe.

## 3. The engine re-encodes the whole game, to set a boolean

19.4% of the 3.8.0 load profile sits under one stack:

```
GameState$SetupCommand.executeCommand -> GameState.setup -> GameState.saveString
  -> GameModule.encode -> ... -> Decorator.getType()
```

`GameState.setup()` ends with:

```java
lastSave = gameStarting ? saveString() : null;     // GameState.java:541
```

and `saveString()` is `GameModule.encode(getRestoreCommand())` — a **complete serialisation of every piece in the game**. Its only consumer is:

```java
public boolean isModified() {                      // GameState.java:293
  final String s = saveString();
  return s != null && !s.equals(lastSave);
}
```

So the engine builds, and then retains for the whole session, a full copy of the game as text in order to answer "are there unsaved changes?". Measured on this scenario:

| | 3.8.0 | master |
|---|---|---|
| `getRestoreCommand()` | 1.2 s | 0.1 s |
| `GameModule.encode()` | 4.2 s | 1.1 s |
| resulting `String` | **172.7 MB** | **86.4 MB** |
| `isModified()` (does it all again) | 5.4 s | 1.1 s |

It is paid on every `setup(true)`: loading a game, starting one, **and finishing a network sync**. On 3.8.0 it is 67.7% of the whole `execute` phase. #15116 reduces it but does not remove it — on master it is still ~1.1 s and an 86 MB retained `String`, about a fifth of the remaining load time.

Reported as [#15303](https://github.com/vassalengine/vassal/issues/15303) and fixed in [#15306](https://github.com/vassalengine/vassal/pull/15306). Three options were put in the issue; measuring them changed which one was worth doing, and showed one of them does not work at all.

1. **Compare a digest, not the text.** Streaming the encode through a `MessageDigest` keeps 32 bytes instead of 86 MB. But profiling the encode shows only **18.4%** of it is StringBuilder growth and array copying, which is all a digest avoids: **53.8% is the trait `getType()`/`getState()` traversal**, which it still pays in full. Roughly a 20% CPU saving. It is a memory fix, not a CPU one.
2. ~~**Make it lazy.**~~ **Withdrawn — it cannot work.** By the time `isModified()` is called the game has already changed, so the baseline can no longer be captured; it has to be taken at `setup()` time or not at all. A "clean" flag that something later invalidates is option 3, not a cheaper alternative to it.
3. **Track modification from the command stream.** Removes **100%** of the cost — about 5x what the digest saves. This is what [#15306](https://github.com/vassalengine/vassal/pull/15306) does: every change passes through `BasicLogger.log()` (local via `GameModule.sendAndLog`, remote via `CommandDecoder`, replayed via `step()`), while a file load does not, since `loadGameInForeground` executes the restore command directly — so a freshly loaded game is correctly clean with no suppression logic. Measured on this scenario: `execute` 2.77 s → **1.76 s**, `isModified()` 1.1 s → **0 s**, live heap after load 541 MB → **372 MB**.

## 4. Why joining is slower than loading from disk

A join does everything a disk load does, plus work on the other machine, and the reported "10 s to synchronise, then 35 s" matches that.

- **The sender** answers a `SynchCommand` with `getGameState().getRestoreCommand()` and `client.sendTo(recipient, synch)` (`SynchCommand.java`), so it pays the same full O(N²) encode measured above — 5.4 s on 3.8.0.
- **The receiver** decodes that into pieces (the 20 s `createPiece` path), executes it (12.8 s), and the resulting `setup(true)` runs `saveString()` yet again.

So the 35 s after "synchronisation complete" is not the network — it is the same decode-and-build the disk load pays, with the *same* fix. The message is emitted when the bytes have arrived, before the pieces exist; that it appears 25 s before the map does is itself misleading, and worth moving (or adding a progress indication) regardless of the performance work.

This also explains the save/load asymmetry: **a save pays one encode; a load pays a decode, a full object construction, and then an encode of its own.**

## 5. The other PRs in flight

| PR | State | Effect on these numbers |
|---|---|---|
| [#15116](https://github.com/vassalengine/vassal/pull/15116) flat trait chain | **merged, not in 3.8.0** | **32.8 s → 5.3 s**; log 172.7 → 86.4 MB. The whole story above. |
| [#15119](https://github.com/vassalengine/vassal/pull/15119) share trait type data | open, up to date with master, CI green | 9,621 distinct types back 625,120 instances (65×). Re-measured on master **with #15116 already in it**, so this is what it adds on top: `decodeSavedGame` 2.54 s → **1.73 s** (−32%), live heap after load 541 MB → **465 MB**. Still worth having after the flat framing took the larger share. |
| [#15117](https://github.com/vassalengine/vassal/pull/15117) stream the command log | open | Removes the 86 MB single `String` on save/load. Mostly memory and GC, not the scan cost. |
| [#15283](https://github.com/vassalengine/vassal/pull/15283) keystroke snapshot | merged, not in 3.8.0 | **None on load or join** — it is the per-keystroke path. It makes play smoother on big pieces; it does not touch these numbers. |

## 6. Recommendations

1. **Ship #15116.** It is merged, it is measured at 6.2× on this module, and nothing else on this list comes close. Note the caveat that users only get it once saves are rewritten — worth saying in the release notes, since a player loading an old save will see no change and conclude it did nothing.
2. ~~**Stop materialising `saveString()`**~~ (§3) — **done**, [#15306](https://github.com/vassalengine/vassal/pull/15306). Removes ~1.1 s and 86 MB from every load and sync on master, and ~5.4 s on 3.8.0.
3. **Merge #15119** for the heap: 541 MB for one scenario is what makes large games feel bad long after loading, and GC was the busiest JFR event in the 3.8.0 run (89,465 `GCPhaseParallel`). It takes a further 76 MB off and a third off the decode, on top of #15116.
4. **Revisit the `intern()` in `nextToken`** (§2) once #15116 is in, measuring rather than assuming — its value changes completely when the long unique tokens disappear.
5. **Move the "synchronisation complete" message**, or add progress, so it reports the state being built rather than the bytes being received.

## What came of this

| | |
|---|---|
| [#15303](https://github.com/vassalengine/vassal/issues/15303) | issue — `GameState.setup()` serialises the whole game to set the unsaved-changes flag (§3) |
| [#15306](https://github.com/vassalengine/vassal/pull/15306) | pull request fixing it, from `perf/track-game-modified-without-reserialising` |
| [#15305](https://github.com/vassalengine/vassal/issues/15305) | issue — the #15116 measurements, and the caveat that the gain is write-side (§2) |
| [#15119](https://github.com/vassalengine/vassal/pull/15119) | brought up to date with master and re-measured against it (§5) |

## Reproducing

The harnesses used are small programs against `Vengine.jar` that bootstrap the engine headlessly the way `refresh/RefreshRunner` does (`Info.setConfig`, a `MenuManager`, `GameModule.init`, `ExtensionsLoader`, `Localization.translate`), then time `decodeSavedGame` and `execute` separately. Profiles were taken with `-XX:FlightRecorderOptions=stackdepth=1024 -XX:StartFlightRecording=settings=profile` — the default stack depth of 64 truncates the recursive `createPiece` chain and misattributes most of the cost.
