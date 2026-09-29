# A type table in the save (C2) — value, complexity and payoff

Item **C2** of [wif-engine-optimizations.md](wif-engine-optimizations.md#c2-typetable--definition-dedup-in-the-save)
proposed emitting each distinct piece type once into a table at the head of the save and
referencing it by index from each `AddPiece`, on the strength of the original analysis's
finding that the 8 855 pieces of the WiF game collapse to 5 601 distinct shapes. This note
measures what that would buy, against the two module styles, after the changes already made
on the other branches ([PR #15116](https://github.com/vassalengine/vassal/pull/15116) flat
chain, [PR #15117](https://github.com/vassalengine/vassal/pull/15117) streaming,
[PR #15119](https://github.com/vassalengine/vassal/pull/15119) shared trait data; A1 `VOBS`
and A2 level-9 deflate merged) — and against the alternative the measurement itself brought
up.

**Short version.** The value of a type table on disk depends entirely on its granularity: a
*whole-piece* table (C2 as written) is worth 4 % on the WiF game because three quarters of its
pieces have a type no other piece has; a *trait-segment* table is worth 10×. But the repetition
a trait table removes is exactly what a long-window compressor finds on its own: **xz on the
unchanged text gives the same 10× as the trait table with deflate**, on today's format as well
as the flat one, with no change to the command grammar. Since the compressor is the cheaper,
safer and more general change, and a table adds nothing measurable on top of it, C2 is not worth
building as a format change.

---

## 1. Method

`tools/piece_sharing.py` splits every piece of a save into its per-trait type and state
segments. A scratch script built four encodings of each save's command log from that:

| Encoding | What it is |
|---|---|
| nested | as VASSAL 3.7 writes it (measured from the file) |
| flat | `feature/flat-trait-chain-encoding` (each trait segment escaped once) |
| flat + whole-piece table | C2 as proposed: the first piece of each distinct type chain defines it (`T/<n>/<chain>`), later pieces carry `#<n>`; defined on first use so it streams |
| flat + trait-segment table | the same at trait granularity: each distinct segment defined once (`S/<n>/<segment>`), a piece's type is a tab-joined list of `#<n>` |

Each was sized raw, with deflate level 9 (what the ZIP entry does), and with xz (LZMA2, the
long-window compressor the JDK lacks but `XZ for Java` provides in pure Java). The games are
the two examined before: WiF `17-40-JA` (7 072 pieces, 113 traits each; legacy style, every
counter fully defined) and Europa `FallOfFrance2004` (1 169 pieces, 25 traits; modern style,
property sheets over shared images).

## 2. Results

### WiF `17-40-JA` — 7 072 pieces, 5 349 distinct whole types, 11 255 distinct trait segments

| Encoding | raw | deflate‑9 | xz‑6 | xz‑9e |
|---|---:|---:|---:|---:|
| nested (today) | 223.7 MB | 17.18 MB | **0.66 MB** | 0.47 MB |
| flat | 109.7 MB | 7.01 MB | 0.44 MB | 0.38 MB |
| flat + whole‑piece type table (C2) | 101.3 MB | 6.72 MB | 0.45 MB | 0.40 MB |
| flat + trait‑segment table | 14.8 MB | **0.68 MB** | 0.40 MB | 0.36 MB |

### Europa `FallOfFrance2004` — 1 169 pieces, 133 distinct whole types, 214 distinct segments

| Encoding | raw | deflate‑9 | xz‑6 | xz‑9e |
|---|---:|---:|---:|---:|
| nested (today) | 4.83 MB | 187 KB | 63 KB | 57 KB |
| flat | 4.15 MB | 161 KB | 59 KB | 56 KB |
| flat + whole‑piece type table (C2) | 1.16 MB | 96 KB | 48 KB | 47 KB |
| flat + trait‑segment table | 0.90 MB | 85 KB | 48 KB | 45 KB |

(The `.vsav` on disk is the deflate column plus a few hundred bytes of metadata; the WiF file
is 34 MB today only because it was written in the hex `!VCSK` form that `VOBS` has replaced.)

## 3. What the numbers say

**C2 as written does little for the module that has the problem.** A whole-piece table can only
remove types that recur, and in the legacy style each counter's own Layer trait names its own
images: 5 247 of 7 072 pieces are the only piece of their type. The analysis's "5 601 distinct
shapes out of 8 855" was after normalising digits, which a table cannot do. Result: 7.01 →
6.72 MB, 4 %. On the modern-style module it does what it promised, 161 → 96 KB, but that is a
40 % saving on a file that is already small.

**A trait-segment table is the version with real leverage**, because the repetition is at
trait level — the baked prototypes — where only 0.9 % of the type text is distinct: 7.01 →
0.68 MB with deflate, 10×. That is the table the original C2 should have described.

**But deflate is the reason the repetition costs anything.** Deflate looks back 32 KB; a WiF
piece is 15–90 KB, so every piece's prototype run is out of the window by the time the next
piece repeats it, and the ZIP stores it again and again. A compressor with a longer window —
xz's dictionary is 8 MB at preset 6, 64 MB at 9 — sees the repeat and stores a reference, which
is precisely what a trait table would store by hand. On the flat text xz reaches 0.44 MB with no
table at all; on today's nested text 0.66 MB; and putting the trait table under xz gains only
0.44 → 0.40 MB, because there is nothing left for it to remove. Europa says the same in
miniature: 59 KB with xz alone against 85 KB for table-plus-deflate.

So the disk payoff attributed to C2 is available three ways, and they are not additive:

| Way to get it | WiF result | Format change |
|---|---:|---|
| trait-segment table + deflate | 0.68 MB | new commands, `#n` references in `AddPiece`, decode-session table, version gate, every external reader updated |
| xz payload, no table | 0.44 MB (0.66 MB even on the nested text) | a new payload header, as `VOBS` was; the command grammar untouched |
| both | 0.40 MB | both |

## 4. What a table would cost

For completeness, what C2 at trait granularity would involve, since that is the only form worth
considering:

- **Encoding.** `BasicCommandEncoder.encode(AddPiece)` writes `+/id/type/state` with no context.
  A table needs one: the encoder must know, per output stream, which segments have been defined
  so far, and emit an `S/<n>/<segment>` command before the first piece that uses one. That is a
  per-save (thread-confined) table threaded through `GameModule.encode(Command, Writer)`, and it
  must *not* apply to the String `encode` used for network traffic, where the other clients have
  no table. Defining on first use rather than in a head table is what lets it stream (the
  streaming writer never sees the whole game) and lets a `.vlog` keep adding pieces after the
  restore block.
- **Decoding.** `BasicCommandEncoder.decode` must recognise `S/` (store, return a null command)
  and resolve `#n` in `+/` — again per decode session, which for `loadGameInBackground` is a
  worker thread while the EDT may be decoding network commands, so the table cannot simply be a
  field on the encoder. `createPiece` receives the resolved chain, so the trait code is untouched.
- **Everything else that reads or writes `AddPiece` text**: `Deck` save/load files,
  `SavedGameUpdater`, `PredefinedSetup`, `PieceCloner` (encode/decode round trip within a
  session), and outside the engine this utility's `SavedGame` (GPID and name extraction, Excess
  Units, the Refresh Counters bookkeeping), every script in `tools/`, and any third-party tool.
  Unlike the flat chain and streaming changes, which left the grammar alone, this one would break
  all of them until updated.
- **Compatibility.** Old engines cannot read the new files (as with `VOBS`); the version-mismatch
  warnings apply. Same one-way story as the other branches, but for a smaller gain.
- **Memory and time.** None. Peak memory on load is already streamed (B1); the parsed trait
  objects are already shared per segment (B2, keyed by the same segment strings a table would
  reference); and the text parsing a table would skip is a few seconds of a load dominated by
  constructing pieces (B1 measured it).

## 5. What the compressor would cost

The alternative is what `!VCSZ` briefly did with deflate, done with a compressor whose window
matches the data: the `savedGame` payload becomes `<header> + key + XOR(xz(text))`, read back
by the header, with the legacy headers kept readable. Compression sits inside the streaming
writer (B1) as one more filter stream.

Timed on the WiF log (Python's `lzma`/`zlib`, the same liblzma and zlib the JDK-side libraries
wrap; single-threaded; decompression is the load-time cost):

| Text | Compressor | Size | Compress | Decompress |
|---|---|---:|---:|---:|
| nested, 223.7 MB | deflate‑9 (today) | 17.18 MB | 12.0 s | 0.6 s |
| | xz preset 1 (1 MB dictionary) | 1.41 MB | 2.2 s | 0.4 s |
| | **xz preset 3 (4 MB dictionary)** | **0.83 MB** | **2.7 s** | 0.4 s |
| | xz preset 6 (8 MB) | 0.66 MB | 34.4 s | 0.4 s |
| | xz preset 9 (64 MB) | 0.57 MB | 36.0 s | 0.4 s |
| flat, 109.7 MB | deflate‑9 (today + flat chain) | 7.01 MB | 2.3 s | 0.3 s |
| | xz preset 1 | 0.66 MB | 1.1 s | 0.2 s |
| | **xz preset 3** | **0.51 MB** | **1.4 s** | 0.2 s |
| | xz preset 6 | 0.44 MB | 12.5 s | 0.1 s |
| | xz preset 9 | 0.42 MB | 13.3 s | 0.2 s |

Preset 3 is the point: a 4 MB dictionary already spans dozens of pieces, so it captures the
cross-piece repetition, and its match finder is the fast one — it compresses today's text in
2.7 s where deflate level 9 takes 12 s, and yields a file 20× smaller. Presets 6 and 9 buy a
further 20–30 % for ten times the compression time; not worth it for a save. Decompression is
under half a second at every preset, and a 4 MB dictionary costs the decoder about that much
memory.

- **Dependency.** The JDK has no LZMA. `XZ for Java` (Tukaani, public domain, ~120 KB, pure
  Java, no native code, the library `commons-compress` delegates to) is the natural choice. A
  pure-Java dependency does not disturb the `jlink`ed runtimes.
- **Compatibility.** The same one-way story as `VOBS`: new engines read old and new, old cannot
  read new. External readers need the codec, not a new grammar — this utility already switches
  on the payload header and would gain a fourth case.
- **Generality.** It helps every module, every log, and every save regardless of style, and it
  is indifferent to whether the text is nested or flat.

## 6. Recommendation

1. **Do not build C2 as a format change.** The whole-piece table is worth 4 % on the game that
   matters; the trait-segment table is worth 10× but no more than a compressor with a window
   large enough to see one piece's prototype run from the next, and it costs a new grammar that
   every reader of saved games would have to learn.
2. **Get C2's payoff by compression instead**: an LZMA (xz) payload for the `savedGame` entry,
   header-versioned like `VOBS`. Measured: today's 17 MB deflated log becomes 0.66 MB on the
   nested text and 0.44 MB on the flat text, against 0.68 MB for the best table. Preset by the
   timing table above; preset 6 unless its compression time on the largest games is
   unacceptable, in which case preset 3 still captures the sharing.
3. If, after that, load-time text parsing were ever the bottleneck, a trait-segment table would
   be the next step — but B1 and B2 have already moved that cost elsewhere.

The C2 row in the optimisation plan is updated to point here.
