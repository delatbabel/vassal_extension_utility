# Flat trait-chain framing — removing the O(N²) backslashes from VASSAL pieces

This is the follow-up to [wif-save-bloat-analysis.md §3](wif-save-bloat-analysis.md#3-cause-2--sequenceencoder-escaping-is-otraits-per-piece)
("Cause #2 — SequenceEncoder escaping is O(traits²) per piece") and to item **C1** of
[wif-engine-optimizations.md](wif-engine-optimizations.md#c1-eliminate-the-on-sequenceencoder-escaping).
It records a second, exhaustive pass through the engine source in `../vassal` to find every
piece of code that produces or consumes the escaped form of a game piece, and describes the
change implemented on branch **`feature/flat-trait-chain-encoding`** in `../vassal` ([PR #15116](https://github.com/vassalengine/vassal/pull/15116)).

The short version: **the quadratic growth is not a property of `SequenceEncoder` at all.**
`SequenceEncoder` adds exactly one backslash per delimiter per level, and always has. The
growth comes from *one* caller that nests it recursively — `Decorator.getType()`/`getState()`
wrapping the entire inner piece as a single escaped token, one level per trait. Change the
framing in that one place (and its three decoders) and the problem is gone, with
`SequenceEncoder` untouched, every trait's own `;`/`,` encoding untouched, and every custom
trait that follows the documented pattern (override `myGetType`/`myGetState`/`mySetType`/`mySetState`
only) untouched.

---

## 1. Where the backslashes actually come from

### 1.1 `SequenceEncoder` itself is linear

`VASSAL/tools/SequenceEncoder.java`:

- `appendEscapedString()` (`:198-209`) inserts **one** `\` before each occurrence of the
  delimiter in the token. It does *not* escape backslashes.
- `append(String)` (`:95-113`) additionally wraps the token in single quotes if it *starts*
  with `\` (or is already `'…'`-wrapped), so the decoder can tell a leading backslash from an
  escape.
- `Decoder.nextToken()` (`:247-302`) treats a delimiter as escaped iff the character
  immediately before it is `\`, and drops exactly that one backslash.

So encoding a token that already contains `\<delim>` (because it was itself the output of a
`SequenceEncoder` at the same delimiter) yields `\\<delim>`: **each level of re-encoding adds
one backslash to every delimiter of the level below.** That is the whole mechanism.

### 1.2 The one recursive caller: `Decorator`

`VASSAL/counters/Decorator.java` on `master` (before the change):

```java
public String getType() {                                    // :526
  final SequenceEncoder se = new SequenceEncoder(myGetType(), '\t');
  se.append(piece.getType());      // <- the ENTIRE inner piece, as ONE token
  return se.getValue();
}
public String getState()  { …same with myGetState()/piece.getState()… }   // :497
public void setState(String newState) {                      // :437
  final SequenceEncoder.Decoder st = new SequenceEncoder.Decoder(newState, '\t');
  mySetState(st.nextToken());
  piece.setState(st.nextToken());  // <- the rest, unescaped one level, to the inner piece
}
public void mergeState(String newState, String oldState) { …same split, twice… }   // :461
```

`piece` is the next trait inward, whose `getType()` is the same method, so a piece with traits
A, B, C over a BasicPiece is written as

```
esc(A) TAB esc( esc(B) TAB esc( esc(C) TAB esc(basic) ) )
```

The tab after the *i*-th trait passes through *i − 1* outer `append()` calls and so carries
*i − 1* backslashes. For N traits that is N(N−1)/2 backslashes in the type and the same again
in the state — the `0.25 × (tabs)²` fit measured in the analysis, and the reason the *state*
half of a piece (a few characters of real data per trait) is 93 % backslashes.

The decoder mirrors it: `BasicCommandEncoder.createPiece()` (`:269-286`) takes the first
tab-token as this trait's type and the second (unescaped one level) as the inner piece's
whole type, and recurses.

### 1.3 Everything else nests at constant depth

The other `SequenceEncoder` layers a piece passes through are each one level and do not
multiply with the trait count:

| Layer | Delimiter | Depth | Where |
|---|---|---|---|
| `AddPiece` command `+/id/type/state` | `/` | 1 | `BasicCommandEncoder.encode` `:424-430` |
| Command tree (a piece is a sub-command of the restore command) | `ESC` | 2–3 | `GameModule.encode` |
| Each trait's own fields | `;` (sub-lists `,`) | 1–2 | every `myGetType()` |
| An embedded marker definition (`PlaceMarker` "Define Marker", `Replace`) | `;` around a whole `+/…` | +1 per embedding | `PlaceMarker.myGetType` `:167-184` |

Only the trait chain is recursive in the number of traits.

---

## 2. The census: what touches the chain string

The maintainers' concern was that `SequenceEncoder` "appears in every component that
contributes to a save file", that some components "encode themselves the way SequenceEncoder
would", and that custom module code does the same and cannot be changed. All true — and none
of it matters for *this* fix, because the fix does not change `SequenceEncoder`'s syntax, only
how `Decorator` composes the chain. What matters is the much smaller set of code that
produces or parses **the tab-joined chain**. Two exhaustive sweeps of
`vassal-app/src/main/java` (every `'\t'`/`"\t"` literal, every `getType`/`getState`/`setState`/
`mergeState` declaration, every `extends Decorator`) found:

### 2.1 Producers and consumers of the chain — five methods

| Method | Role |
|---|---|
| `Decorator.getType()` | encodes: own segment + inner chain |
| `Decorator.getState()` | encodes: own segment + inner chain |
| `Decorator.setState(String)` | decodes: own segment, rest to inner |
| `Decorator.mergeState(String, String)` | decodes both strings the same way (the path every `ChangePiece` takes, `ChangePiece.java:59-74`) |
| `BasicCommandEncoder.createPiece(String)` | decodes the type chain into a piece |

### 2.2 Decorator subclasses that override the framing — none

All 42 direct subclasses (`ActionButton` … `UsePrototype`) and 6 indirect ones (`Footprint`,
`Replace`, `SetGlobalProperty`, `SetPieceProperty`, `MassPieceLoader.Emb`,
`SavedGameUpdater.ReplaceTrait`) override only `myGetType`/`myGetState`/`mySetType`/`mySetState`.
Every `getType()`/`getState()` declaration found inside a trait file belongs to its nested
`Ed`/`Editor` class (a `PieceEditor`, not a `GamePiece`). `UsePrototype` serialises its
*unexpanded* inner (`piece`), never the expanded prototype chain; expansion for a placed piece
is done by `PieceCloner.clonePiece` (`:72-73`), which drops the `UsePrototype` wrapper and
clones the expanded traits — which is why saves contain no `prototype;` traits.

The non-Decorator `GamePiece`s (`BasicPiece`, `Stack`, `Deck`, `PrototypeDefinition.Plain`)
encode a single `;`-sequence and have no chain.

### 2.3 Code that parses the chain by hand — none

Every other `'\t'` in the engine belongs to a *command* encoding (`EXT`, `DECK`, `LOG`,
`PLAY`, dice, notes, `BoardPicker`, chat protocol, preferences…), not to a piece. Code that
holds whole chain strings treats them as opaque: `PieceSlot.pieceDefinition`,
`PrototypeDefinition.pieceDefinition`, `PlaceMarker.markerSpec`, `Deck` save/load,
`PieceCloner`, `GameState.getRestorePiecesCommand()`. Innermost access is always
`Decorator.getInnermost(piece)` on objects. Two places pass a *whole chain* into a single
trait's `mySetType` (`FreeRotator.java:1022`, `MassPieceLoader.java:336`) — a pre-existing
sloppiness that mis-reads the trait's last field either way and is not made worse.

`GameRefresher` copies state **per trait** (`GpIdChecker.copyState` `:293-314`, matching by
class and `myGetType()`), never by whole-chain `setState`, so it is framing-agnostic.

### 2.4 Code that compares or stores chain strings

| Site | What is compared | Effect of the change |
|---|---|---|
| `UsePrototype.buildPrototype` `:157-159` | fresh `getType()` vs a cached fresh `getType()` | none — same engine, same framing |
| `PieceDefiner` `:1149-1160` | fresh vs fresh | none |
| `ChangeTracker` / `ChangePiece` | fresh `getState()` before and after | none; the strings travel together and are decoded by the unified `mergeState` |
| `GpIdChecker.findState`, `Replace.matchTraits` | per-trait `myGetType()` | none (segments are unchanged) |
| `SavedGameUpdater` `:76-123` + `SavedGameUpdaterDialog` `:195-216` | a **`.properties` file of full chain strings** exported earlier vs fresh `getType()` | a file exported by a 3.7 engine will not match pieces under 3.8; re-export it. Both strings must come from the same engine version, which was already the practical requirement |
| `SequenceEncoder.Decoder.nextToken()` `:301` | interns every token | with the flat framing the interned tokens are single trait segments shared by thousands of pieces, rather than one 25 KB nested string per level |

### 2.5 Outside the engine

- **This utility** (`model/SavedGame.innermostTrait`) and the Python tools
  (`tools/*.py`, `rpartition('\t')`) locate the innermost `BasicPiece` as *the text after the
  last tab*. That is the same text in both framings (only the backslashes *before* the tabs
  differ), so nothing here needs changing. The command-level structure (ESC nesting, `EXT`
  lines, `BoardPicker` commands) is untouched.
- **Custom module code** — see §4.

---

## 3. The fix

### 3.1 The flat framing

Written by `Decorator.getType()`/`getState()` since this change:

```
esc(A) TAB esc(B) TAB esc(C) TAB esc(basic)
```

Each segment is escaped **exactly once** (still by `SequenceEncoder` with the tab delimiter, so
a literal tab inside a segment is `\<TAB>` and a segment starting with `\` is quote-wrapped,
exactly as before). The size is linear in the trait count. For the WiF pieces, whose segments
contain no tabs, the tab-level backslashes disappear entirely.

### 3.2 Telling the two framings apart

The nested framing always has **exactly one top-level tab** after the first segment (the rest of
the chain is one escaped token); the flat framing has one per remaining segment. So:

- **2 top-level tokens** → nested: the second token, unescaped, is the inner chain. (A single
  trait over a basic piece is byte-identical in both framings, so this case is also correct for
  flat input.)
- **3 or more** → flat: the rest of the string, *as it is*, is the inner chain.

A chain may **mix** the two framings (see §4), and the decoders handle that too:

- `BasicCommandEncoder.createPiece()` collects all top-level tokens, decodes the **last one
  recursively** (which unwraps a nested remainder, and is a no-op for a plain basic type), then
  wraps the decorators around it from the inside out. For nested input this degenerates to
  exactly the old algorithm (first token = trait, recurse on the second).
- `Decorator.splitChain()` applies the 2-vs-3+ rule — it asks the `Decoder` whether a third
  top-level token exists — and hands the inner piece either the decoded second token or the raw
  remainder; `setState` and `mergeState` both use it. Each trait
  decides only for its own link, so a legacy-framed link deeper in the chain is decoded by the
  trait that owns it.

### 3.3 The guard for traits that frame the chain themselves

Nothing in the engine overrides `getType`/`getState`/`setState`/`mergeState`, but custom traits
copied from an old `Decorator` might. Such a trait *produces* a nested string for its own link
and *expects* one when decoding. `Decorator.inheritsFraming(Class)` checks (once per class, by
reflection, cached) whether a class leaves all four methods to `Decorator`. `joinChain()` writes
the flat form only when **both** the current trait and its inner trait inherit the framing;
otherwise it escapes the inner chain as one token — the old form, which the unified decoders
read. This makes the change safe for every combination of engine and custom traits, and the
guard is also what the tests use to simulate a legacy trait.

### 3.4 Small robustness gain in `createPiece`

Because a basic piece has nothing inside it, a top-level token whose prefix is a registered
basic type (`piece;`, `stack`, `deck;`) is passed whole to `createBasic()`. Previously a tab
inside a piece's name (possible by pasting into the editor) was split as if it were a trait
boundary, in both framings.

### 3.5 What is on the branch

`../vassal`, branch `feature/flat-trait-chain-encoding` (from `master` at `f9e9dc9bc`; the
branch has since had upstream `master` merged in, and a second commit that makes `splitChain`
independent of the escaping scheme — see [vassal-sequence-fix-comparison.md](vassal-sequence-fix-comparison.md)):

| File | Change |
|---|---|
| `vassal-app/src/main/java/VASSAL/counters/Decorator.java` | `getType`/`getState` → `joinChain()`; `setState`/`mergeState` → `splitChain()` (which finds token boundaries only through `SequenceEncoder.Decoder`, so it does not depend on how a delimiter inside a token is marked); `inheritsFraming()`; a block comment documenting both framings |
| `vassal-app/src/main/java/VASSAL/build/module/BasicCommandEncoder.java` | `createPiece()` reads all top-level tokens, recurses on the last, wraps from the inside out |
| `vassal-app/src/test/java/VASSAL/counters/TraitChainFramingTest.java` | new — see §6 |

`SequenceEncoder.java` is not modified. No trait is modified. No new dependency.

---

## 4. Compatibility

| Reader ↓ / Data → | Nested (3.7 saves, modules, logs) | Flat (written by 3.8+) |
|---|---|---|
| **3.8+ engine** | reads (unchanged algorithm path) | reads |
| **3.7 and earlier** | reads | **cannot read** — `createPiece` takes the first two tokens and fails on the second; the player gets the existing *"saved game was created with a later version"* chat warning (`GameState.java:689-693`) plus per-piece bad-data reports |

This is the same one-way compatibility VASSAL accepted for `!VOBS` in 3.8
([wif-engine-optimizations.md A1](wif-engine-optimizations.md#a1-stop-writing-the-obfuscated-data-in-hex--2-disk---merged-vobs)):
new engines read everything, old engines cannot read new data. Additionally:

- **Modules.** A module *saved by the 3.8 editor* stores flat chains in its `PieceSlot`/
  `PrototypeDefinition` text, so it becomes 3.8-only — exactly the situation the Module
  Manager already reports with `Info.isModuleTooNew()` (`ModuleManagerWindow.java:1559`) and
  the editor with `GameModule.java:1040-1042`. A module merely *played* with 3.8 is unchanged.
- **Online play and logs.** `AddPiece`/`ChangePiece` commands carry chain strings on the wire,
  so players must run the same minor version, which `NodeClient.checkCompatibility()`
  (`:733-757`) already warns about ("out by a whole minor version"). `.vlog` files written by
  3.8 replay only on 3.8.
- **Embedded definitions in module files** (`PlaceMarker.markerSpec`, `Replace`) are stored
  strings; they keep their nested form until the trait is re-edited, and decode fine either way.
  They are a per-*trait* constant, not a per-*piece* one, so they are not where the bytes are.
- **Custom module code**, by category:
  1. Custom traits overriding only `my*` — unaffected (the overwhelming majority, and the
     documented pattern in `BasicCommandEncoder`'s Javadoc).
  2. Custom traits overriding `getType`/`getState`/`setState`/`mergeState` — detected by the
     guard; their links stay nested; the rest of the chain is still flat.
  3. Custom `BasicCommandEncoder` subclasses overriding `createDecorator`/`createBasic` — the
     documented pattern; unaffected, since the chain split happens in `createPiece`.
  4. Custom encoders that *override `createPiece` with a copy of the old two-token code* and
     never call `super.createPiece` — would fail on flat input. The Javadoc says this "should
     generally not need to be overridden"; the only remedy is to update such a module.
  5. Code that parses `piece.getType()` by hand with the old two-token assumption — same.
  6. Anything that *builds* a nested string by hand — still decodes.

---

## 5. Measured effect on the WiF saves

Measured by re-framing every `AddPiece` in the deobfuscated command log from nested to flat with
an exact port of the encoder (scratch script; both the flat output and the nested input were
decoded with the unified rule and compared segment-by-segment, so this is the byte stream the
new engine writes for the same pieces):

| | `002-presetup-aif-everything` (34.1 MB `.vsav`, 8 855 pieces) | `094-42-ND-allies-9-v213` (26.0 MB `.vsav`, 7 430 pieces) |
|---|---:|---:|
| Command log, nested (today) | 222.9 MB | 171.5 MB |
| Command log, flat | **104.8 MB** (2.13× smaller) | **80.0 MB** (2.14× smaller) |
| Backslashes, nested | 121.5 MB (54.5 %) | 93.4 MB (54.5 %) |
| Backslashes, flat | 3.3 MB (almost all literal backslashes inside trait fields, e.g. in expressions; the `/` escapes of the `AddPiece` layer are ~0.3 MB) | 1.9 MB |
| gzip −9 of the log, nested | 18.8 MB | 15.1 MB |
| gzip −9 of the log, flat | **6.9 MB** (2.75× smaller) | **6.7 MB** (2.24× smaller) |

The 121.5 MB of backslashes is exactly the figure in the original analysis, and 118 MB of it
goes away. The compressed size falls by more than the byte count alone would give, because
the backslash runs were incompressible noise between otherwise repetitive trait segments.

The `.vsav` on disk is the ZIP's DEFLATE of the (`!VOBS`, XOR-only) command log, so the gzip
column is the on-disk effect. Memory follows the plaintext column: `GameState.lastSave`,
`saveString()` and the decode-side `IOUtils.toString` all hold the whole log as one `String`.

---

## 6. Tests

`TraitChainFramingTest` (JUnit 5, `MockModuleTest`) covers:

- flat framing has one top-level token per trait and **zero** tab-level backslashes for a
  300-trait chain, against N(N−1)/2 in the nested form of the same piece;
- round trips: flat → `createPiece` + `setState` → identical `getType`/`getState`, properties and
  position; the same from nested input; a single trait is byte-identical in both framings; a
  bare basic piece unchanged;
- delimiters inside segments (a tab in a Marker value and in the piece name, a value starting
  with a backslash, a quoted value) escaped once and recovered from either framing;
- `mergeState` applied from flat and from nested `ChangePiece`-style pairs;
- a legacy-framed trait (overrides all four methods the old way, with its own
  `BasicCommandEncoder` subclass as a module would ship) in the middle of a chain and outermost:
  the link to it is nested, the rest flat, everything round-trips including `mergeState`;
- `splitChain` on the boundary cases (no inner, empty segments, escaped own segment, quoted
  segment, nested vs flat remainder).

The full `vassal-app` suite: 771 tests, 0 failures attributable to the change (the one failure,
`ProcessCallableTest.testNormal`, also fails on unmodified `master` in this environment — a
`_JAVA_OPTIONS` banner on the child process's output). Checkstyle (validate phase) passes;
PMD reports no violations; SpotBugs reports only its 28 pre-existing findings, none in the changed classes.

Run just the new tests with:

```bash
cd ../vassal && ./mvnw -q -pl vassal-app test -Dtest=TraitChainFramingTest -Dsurefire.failIfNoSpecifiedTests=false
```

---

## 7. What this does *not* do, and what is next

- It removes the quadratic term only. The linear content (200 expanded traits per piece,
  [analysis §2](wif-save-bloat-analysis.md#2-cause-1--every-piece-inlines-a-fully-expanded-prototype-chain))
  and the duplication across identical pieces (§6, engine item C2) remain; the module-side
  fixes in [wif-module-optimizations.md](wif-module-optimizations.md) are still the largest
  lever on the linear part.
- It does not touch `SequenceEncoder`, so the 2006-era ambiguities of that class (a token
  *ending* in a backslash before a delimiter is still mis-read; only a *leading* backslash is
  quote-protected) are exactly as they were.
- Decoding a *nested* chain is still O(N·L) per piece (each level copies the remainder), as it
  always was; decoding a flat chain is O(L) for the type and O(N·L) for the state only because
  `setState(String)` must hand each inner trait a substring. Encoding a flat chain is O(N·L)
  too (each level concatenates the inner string) — the same order as before but on a string
  half the size; a `StringBuilder`-threading API would make it O(L) and is a possible follow-up.
