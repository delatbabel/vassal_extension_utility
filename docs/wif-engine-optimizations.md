# Engine‑side memory & disk optimizations (VASSAL)

Changes to the **VASSAL engine** (`../vassal`, `vassal-app/src/main/java/…`) that would reduce
memory usage and saved‑game (`.vsav`) size for large modules like *WiF CE Official Combo*.
Evidence and measurements are in **[wif-save-bloat-analysis.md](wif-save-bloat-analysis.md)**;
module‑side changes (no engine change needed) are in
**[wif-module-optimizations.md](wif-module-optimizations.md)**.

These are proposals for the VASSAL engine maintainers. They are ordered by **effort vs. payoff**:
the first two are small (A2 is fully format‑compatible; A1 adds a new, backward‑readable
`savedGame` encoding); the rest are larger and (mostly) require a save‑format version bump.
**A1 (option 1) and A2 are now implemented** on feature branches in `../vassal` — see each
section. All line numbers are from the current `../vassal` checkout and should be re‑confirmed
before editing.

---

## Tier A — small, format‑preserving, ship‑anytime

### A1. Compress *then* obfuscate — **~1.8× disk** — ✅ IMPLEMENTED (option 1)

**Problem.** The save path obfuscates the command log **before** the ZIP compresses it.
`ObfuscatingOutputStream` emits **two ASCII hex chars per byte** (`ObfuscatingOutputStream.java:82‑88`),
so the ZIP is asked to compress a 2×‑inflated, 16‑symbol hex stream, which it does far less
effectively than the original text.

**Evidence** ([analysis §5](wif-save-bloat-analysis.md#5-cause-4--obfuscation-hex-doubles-the-bytes-and-defeats-compression)),
gzip‑9 of the 002 sample:

| Compressed input | Result |
|---|---:|
| Obfuscated form (today) | 33.1 MB ≈ real 34.1 MB `.vsav` |
| Raw plaintext | **18.8 MB** |

**What was implemented — option 1, reorder to compress‑then‑obfuscate.** Deflate the plaintext
first (`Deflater.BEST_COMPRESSION`), then run the small compressed result through the
obfuscator. The hex‑doubling then applies to ~18 MB instead of ~223 MB, and the ZIP sees
compressible text. The read path reverses the order.

Implemented on branch `feature/compress-then-obfuscate-vsav-file` in `../vassal` (pushed to
the `delatbabel/vassal` fork; PR against the main VASSAL repository to be raised manually):

- `ObfuscatingOutputStream` now writes plaintext → deflate → XOR‑hex, marked with a new
  **`!VCSZ`** header (the key byte in hex follows, exactly as before). The old `!VCSK`
  constant remains for reading.
- `DeobfuscatingInputStream` reads **both** formats (`!VCSK` → deobfuscate only; `!VCSZ` →
  deobfuscate then inflate) and still passes plain text through unchanged. The `.vsav`
  remains an ordinary ZIP — the change is confined to the `savedGame` entry's payload, so
  WinZip‑style tools are unaffected.
- Compatibility: new engines read old and new saves; **old engines cannot read `!VCSZ`
  saves**. Whether to gate the new writer behind a version check is left to upstream review.

**Options considered and discarded** — judged unacceptable to the upstream maintainers
(both remove the obfuscation itself rather than reordering it, abandoning the anti‑cheat
intent):

2. ~~**Drop obfuscation for the ZIP entry**~~ — writing the plaintext directly (the
   `DeobfuscatingInputStream` passthrough would have made old readers tolerate it).
3. ~~**Make it a preference**~~ — a "compact save" option that skips obfuscation.

**This utility** has been updated to match: `model/SavedGame` recognises both headers when
opening a `.vsav`, and every rewrite (Excess Units, `PreservedState.restore`) re‑emits the
**same format the file was opened with** — `!VCSK` in, `!VCSK` out; `!VCSZ` in, `!VCSZ` out.
`tools/swap_maps.py` does the same. (The Refresh Counters feature saves through whatever
engine is installed, so its output format follows that engine's version.)

**Where (engine).** Save: `GameState.saveGame(File)` wraps the entry in `ObfuscatingOutputStream`
unconditionally (`GameState.java:1372‑1377`, esp. `:1373`); the refresh path likewise
(`:1348`). Read: `GameState.decodeSavedGame(InputStream)` (`:1627‑1644`). All three call
sites compose the two stream classes, so the whole change lives in `tools/io/`.

**Payoff.** ~1.8× smaller `.vsav` on disk with no module change. Also reduces the memory
high‑water mark.

---

### A2. Raise the interactive‑save deflate level 6 → 9 — small, trivial — ✅ IMPLEMENTED

**Problem.** Two different ZIP writers are used, at different compression levels:

- Interactive **Save** uses `ZipWriter`, which never calls `setLevel()`, so entries use
  `Deflater.DEFAULT_COMPRESSION` = **level 6** (`ZipWriter.java:62`, `makeEntry` `:120‑124`).
- The editor **refresh** save uses `ZipArchive`, which sets **level 9** (`ZipArchive.java:466`).

**Change.** Set the `ZipWriter` `ZipOutputStream` to level 9 to match. On the 002 sample this
alone is only ~3 % on the obfuscated stream (33.1 vs 34.1 MB) — small, but free and consistent.
Larger once combined with A1 (level 9 matters more on compressible plaintext).

**Where.** `ZipWriter.java:62` (add `setLevel(Deflater.BEST_COMPRESSION)`).

**Payoff.** Small. **Effort:** trivial, fully format‑compatible.

Implemented on branch `feature/raise-interactive-save-level` in `../vassal` (pushed to the
`delatbabel/vassal` fork; PR to be raised manually).

---

## Tier B — memory / robustness (no disk‑format change)

### B1. Stream the save/load instead of building a 222 MB `String`

**Problem.** Both save and load materialise the *entire* command log as one Java `String`:

- **Save:** `saveString()` builds the whole log via recursive `GameModule.encode()`
  (`GameState.java:1020‑1022`), then `save.getBytes(UTF_8)` allocates a second full copy
  (`:1374`), and `lastSave = save` **retains** the ~223 MB `String` (~446 MB UTF‑16) for the
  whole session, used only by `isModified()` (`:1379`, `:294`).
- **Load:** `decodeSavedGame()` does `IOUtils.toString(din, UTF_8)` — the full log in one
  `String` before parsing (`:1635`). Background loads already catch `OutOfMemoryError` right
  here (`:1493‑1500`).

The developers left standing FIXMEs acknowledging this: *"It is extremely inefficient to produce
the save string. It would be faster to write directly to the output stream"* (`GameState.java:1361‑1362`)
and *"toString() is very inefficient, make decode() use the stream directly"* (`:1633`).

**Change.** Stream commands directly to the (obfuscating/compressing) output during save, and
parse the deobfuscated stream incrementally during load, so peak memory is O(one piece) rather
than O(whole game). Replace the retained `lastSave` String with a cheaper dirty‑flag/hash.

**Payoff.** Removes the practical OOM ceiling on how large a game can be saved/loaded, and cuts
hundreds of MB of transient + retained heap. **Does not** change the file format or its size.
**Effort:** medium–high (touches encode/decode plumbing). High value for exactly this class of
module.

---

### B2. Flyweight prototype expansion — share immutable trait data across instances

**Problem.** Each placed piece holds its **own deep clone** of its expanded prototype chain.
`UsePrototype.buildPrototype()` calls `PieceCloner.clonePiece()` per instance
(`UsePrototype.java:161`; `PieceCloner.java:74‑75`), so N pieces built from one prototype hold
N independent copies of the same ~200 trait objects — on the order of **1.7 M live trait objects**
for a full WiF game. The only sharing today is the decoded‑image `OpCache` (`AbstractOpImpl.java:65`)
and a per‑instance change‑detection string (`UsePrototype.lastCachedPrototype = type.intern()`,
`:159`) — neither shares the trait *objects*.

**Change (larger).** Split each trait into immutable **type data** (identical across all
instances of a definition) and mutable **per‑instance state**, so instances share one canonical
type‑data object (flyweight) and carry only their own state. The type portion is provably
identical across pieces sharing a prototype (it's the deterministic, prototype‑expanded
`getType()`), so this is a pure heap win with no behaviour change — but it is a substantial
refactor of the `Decorator` hierarchy and `PieceCloner`.

**Change (smaller, incremental).** Intern/​canonicalise the obviously‑duplicated large strings:
`PlaceMarker.markerSpec` (`PlaceMarker.java:104`) and `UsePrototype`'s raw type are byte‑identical
across all instances of a definition but never shared — route them through a canonical‑string
cache so all instances point at one copy.

**Payoff.** Large heap reduction (the dominant cost per [analysis §7](wif-save-bloat-analysis.md#7-what-this-means-for-memory-in-the-running-engine)).
**Effort:** high for the full flyweight; low for the string‑interning increment.

---

## Tier C — disk‑format changes (require a save‑format version bump)

These attack the 222 MB itself. Each needs a new command opcode / container version, and must
remain compatible with **live network play** and **log replay**, which use the same
`BasicCommandEncoder` wire form — the central obstacle. Recommend gating behind a
`saveFormatVersion` and keeping the old reader.

### C1. Eliminate the O(N²) `SequenceEncoder` escaping

**Problem.** `Decorator.getType()`/`getState()` nest one `SequenceEncoder` per trait
(`Decorator.java:525‑530`), and `appendEscapedString()` escapes the delimiters **and** escape
characters of every inner level (`SequenceEncoder.java:198‑209`, quote‑wrap `:95‑113`). The
innermost trait accumulates one extra backslash per enclosing trait → **54.5 % of the plaintext
is backslashes**, `backslashes ≈ 0.251 × traits²`
([analysis §3](wif-save-bloat-analysis.md#3-cause-2--sequenceencoder-escaping-is-otraits-per-piece)).

**Change.** Serialise a piece's trait chain with a **length‑prefixed / non‑escaping container**
(e.g. write each trait's `myGetType()`/`myGetState()` with an explicit length or a
non‑recursive framing) so nesting depth no longer multiplies escape characters. Every
`getType()`/`setState()` pair still round‑trips; only the framing changes.

**Payoff.** Cuts the plaintext ~54 % → memory/CPU win (222 → ~101 MB string), and compressed
disk from ~18.8 → ~6.3 MB in the de‑obfuscated case (a further ~3×). **Effort:** high, invasive
(touches the encoder + every trait's expectations of the delimiter contract) and format‑breaking.

### C2. Type‑table / definition dedup in the save

**Problem.** One `AddPiece` per piece with **no dedup** (`GameState.java:1609`); identical
counters re‑emit identical multi‑KB type strings. 8 855 pieces collapse to 5 601 distinct shapes
([analysis §6](wif-save-bloat-analysis.md#6-cause-5--no-deduplication-across-identical-pieces)).

**Change.** Emit each distinct `<type>` once into a table at the head of the save and reference
it by index from each piece (piece then carries only `id + typeRef + state`). `getType()` is
deterministic, so equal pieces share a table entry for free. Decode resolves references in
`createPiece` (`BasicCommandEncoder.java:269‑286`).

**Payoff.** Removes most of the duplicated `type` bytes (73 % of each piece body). **Effort:**
high; new opcode + decoder support + version gate; must not break replay/network.

### C3. Re‑reference prototypes in saves (conditional) — *note the trade‑off*

Storing `prototype;<name>` instead of the expanded chain would shrink each piece's type to a
few bytes, but VASSAL **intentionally** bakes prototypes at placement so later prototype edits
can't corrupt old saves (`UsePrototype.java:54‑64`, `PieceCloner.java:74‑75`). Re‑introducing
references reverses that forward‑compatibility guarantee. If ever pursued, it would need the
save to **also** embed a snapshot of the prototype definitions it references (so the save is
self‑contained) — which recovers correctness but gives back much of the space. Listed for
completeness; **C1 + C2 are the better targets.**

---

## Recommended sequence

1. **A2** (level 9) — ✅ done (`feature/raise-interactive-save-level`), PR pending.
2. **A1** (compress‑then‑obfuscate) — ✅ done, option 1 only
   (`feature/compress-then-obfuscate-vsav-file`), PR pending; options 2 and 3 discarded.
3. **B1** (streaming) — removes the OOM ceiling; enables even‑larger games regardless of the above.
4. **B2 string‑interning increment** — cheap heap win now; full flyweight later.
5. **C1**, then **C2** — the deep format changes that shrink the 222 MB itself; do together
   behind one `saveFormatVersion` bump with a compatible legacy reader.

Tiers A–B deliver real wins with no disk‑format break and pair well with the module‑side fixes
in [wif-module-optimizations.md](wif-module-optimizations.md); Tier C is where the order‑of‑magnitude
disk reduction lives, at the cost of a versioned format change.
