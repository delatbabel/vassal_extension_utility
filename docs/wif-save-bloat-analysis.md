# Why WiF saves are huge — memory & disk analysis

This document explains *why* the **WiF CE Official Combo** module consumes so much memory in
the VASSAL engine and produces such large saved‑game (`.vsav`) files, backed by measurements
taken from the two sample saves in `data/`. It is the shared evidence base for the two
companion documents that propose concrete changes:

- **[wif-module-optimizations.md](wif-module-optimizations.md)** — changes to the *module*
  (no engine code change, no loss of game features).
- **[wif-engine-optimizations.md](wif-engine-optimizations.md)** — changes to the *VASSAL
  engine* (`../vassal`).

Nothing in this investigation modified any code or any module feature. All engine references
are to the sibling checkout at `../vassal` (`vassal-app/src/main/java/…`). Background on the
container format is in **[vsav-format.md](vsav-format.md)**.

---

## 1. Headline numbers

Measured from **`002-presetup-aif-everything.vsav`** (a fully set‑up AIF game), by unzipping
the `savedGame` entry, de‑obfuscating it (see [vsav-format.md](vsav-format.md) §Obfuscation),
and parsing the command log:

| Quantity | Value |
|---|---|
| `.vsav` file on disk | **34.1 MB** |
| `savedGame` entry (obfuscated) | 445.9 MB |
| De‑obfuscated command log (plaintext) | **222.9 MB** |
| `AddPiece` (`+/id/type/state`) commands | **8 855** (99.9 % of the log) |
| Average bytes per piece | **≈ 25 200** |
| Largest single piece | 89 KB |
| **Median traits per piece** | **201** (mean 183, max 469) |
| Backslash *escape* characters in the plaintext | **121.5 MB = 54.5 %** |

For comparison, **`003-prepopulate-ce-maps-everything.vsav`** (a partially set‑up game): 10.0 MB
on disk, 2 859 pieces, 65 MB plaintext. Going from 2 859 → 8 855 pieces (3.1×) grew the log
65 → 223 MB (3.4×): **per‑piece cost is roughly constant (~25 KB) and total size grows linearly
with the number of pieces on the map.** The problem is not the number of pieces per se — it is
that *each piece is enormous*.

A normal VASSAL game piece is a few hundred bytes with 10–20 traits. A WiF piece is **~25 KB
with ~200 traits**. That 100× inflation is the whole story, and it has three compounding causes.

---

## 2. Cause #1 — every piece inlines a fully‑expanded prototype chain

The module is built almost entirely from **prototypes**: 207 `PrototypeDefinition`s, 1 505
`PieceSlot`s, 100 `CardSlot`s, and **4 155 `prototype;` references** in `buildFile.xml`.

But the *saved game contains zero `prototype;` traits*. VASSAL **bakes** prototypes into each
piece at placement time: when a piece is dragged from the palette, `PieceSlot.getExpandedPiece()`
→ `PieceCloner.clonePiece()` replaces every `UsePrototype` trait with a **deep clone of the
prototype's entire trait chain** (`PieceCloner.java:74‑75`, `UsePrototype.java:193‑196`). From
that moment the piece "has no record that those traits came from a prototype"
(`UsePrototype.java:54‑64`). This is **deliberate** — it guarantees that later edits to a
prototype cannot corrupt existing saves — but it means:

> Every placed piece stores a *complete, independent copy* of all of its prototypes' traits,
> in memory **and** in the save file. There is no sharing and no back‑reference.

The multiplier is stark. Counting a few trait type‑codes in the module definition vs. in the
saved game:

| Trait code | In module `buildFile.xml` | In the save (002) | Expansion factor |
|---|---:|---:|---:|
| `macro` (Global Key / Trigger) | 655 | **232 075** | ~354× |
| `mark` (Marker) | — | 191 484 | — |
| `emb2` (Layer) | 1 389 | 55 092 | ~40× |
| `placemark` (Place Marker) | **1** | **2 066** | ~2066× |
| `prototype` (reference) | 4 155 | **0** | baked away |

So one `macro` trait authored once in a shared "defaults" prototype becomes a *fully‑duplicated
copy on every one of the hundreds of pieces that use that prototype*. This is the community
member's "X + Y bytes" observation (piece + unrolled prototype) — confirmed and quantified.

---

## 3. Cause #2 — SequenceEncoder escaping is **O(traits²)** per piece

A piece's `type` (and `state`) is a nested `SequenceEncoder` structure. `Decorator.getType()`
(`Decorator.java:525‑530`) serialises a trait as:

```
myGetType()  '\t'  escaped( innerPiece.getType() )
```

Every trait *escapes the entire serialised form of the trait below it*. `appendEscapedString()`
(`SequenceEncoder.java:198‑209`) inserts a `\` before each delimiter, and quote‑wraps tokens
that already begin with `\` (`:95‑113`). Because each level re‑escapes the delimiters *and the
escape characters* produced by the level beneath it, the innermost trait's delimiters accumulate
**one extra backslash per trait it is nested under**.

The empirical signature is unmistakable. Successive trait states in a real piece read:

```
1\    2\\    3\\\    7\\\\    9\\\\\    cvp11\\\\\\ …
```

— a staircase of growing backslash runs, up to **233 backslashes** in a single run. Fitting
backslash‑count against trait‑count across all 8 855 pieces:

```
backslashes ≈ 0.251 × traits²        R² = 0.999   (quadratic)
backslashes ≈ 86.4  × traits         R² = 0.849   (linear — poor fit)
```

The quadratic fit is essentially perfect. Bucketed:

| Traits / piece | # pieces | Avg bytes | Avg backslashes | Escape % |
|---:|---:|---:|---:|---:|
| 0–49 | 2 105 | 192 | 3 | **1.5 %** |
| 200–249 | 2 279 | 26 302 | 12 876 | **49 %** |
| 450–499 | 413 | 86 636 | 53 759 | **62 %** |

A lean piece is 1.5 % escaping; a 469‑trait piece is **62 % escaping**. Because the cost is
quadratic, **trait count is doubly punishing**: doubling the traits on a piece roughly *doubles*
its genuine content but *quadruples* its escaping overhead.

Splitting each piece into its `type` vs `state` halves:

| Portion | Bytes | Backslashes |
|---|---:|---:|
| `type` (73 % of body) | 163 MB | 40 % |
| `state` (27 % of body) | 60 MB | **93 %** |

The `state` portion is almost pure escaping — the mutable one‑value‑per‑trait state of a
200‑trait piece, each value escaped ~200 deep.

---

## 4. Cause #3 — embedded "Define Marker" Place Markers

The module contains **exactly one** `placemark;` (Place Marker) trait, inside prototype
**`Land6`**, and it uses an **embedded** marker definition rather than a reference. Its
`markerSpec` field is a whole serialised piece — `+\/null\/mark\;numcvs… prototype\;SUBDefaults12…`
— i.e. an entire `AddPiece` command stored inline (and the embedded marker even references
*further* prototypes).

This is precisely the anti‑pattern the VASSAL community member described: the editor's
**"Define Marker"** button stores the marker inline (`PlaceMarker.Ed.getType()`,
`PlaceMarker.java:808` → `GameModule.encode(new AddPiece(…))`), whereas the **"Select"** button
stores a short component path (`:805`). The embedded definition:

- rides along on **every** parent piece, in memory and on disk, **whether or not the marker is
  ever placed** — it is part of the parent's `type` (`PlaceMarker.myGetType()`,
  `PlaceMarker.java:167‑184`; the field lives on every instance, `:104`, `:536`);
- adds its own traits to the parent's trait count, feeding straight into the O(N²) escaping of §3;
- nests another whole level of `SequenceEncoder` escaping inside the parent.

Because `Land6` is expanded into **2 066 pieces**, this single authoring choice is replicated
2 066 times. Isolating pieces that carry an (unescaped, top‑level) `placemark`: 2 060 pieces,
avg 30.7 KB, 49 % escaping — noticeably heavier than the average piece.

**Note:** the module does **not** use the `replace;` (Replace with Other) trait at all (0
occurrences). The community member's suggestion to remove Replace‑with‑Other is moot here —
there is nothing to remove — which matches the user's own hunt through the module.

---

## 5. Cause #4 — obfuscation hex‑doubles the bytes (and defeats compression)

VASSAL writes the command log through `ObfuscatingOutputStream` (anti‑cheat only), which XORs
each byte with a key and emits it as **two ASCII hex characters** (`ObfuscatingOutputStream.java:82‑88`)
— a flat **2×** size blow‑up (222 MB → 446 MB) *before* the ZIP compresses it. Crucially, the
hex‑over‑16‑symbol stream compresses far worse than the original text. A compression experiment
on the 002 plaintext:

| What is compressed | gzip‑9 result |
|---|---:|
| Obfuscated form (what VASSAL zips today) | **33.1 MB** ≈ the real 34.1 MB `.vsav` |
| Raw plaintext (if VASSAL zipped *that*) | **18.8 MB** |
| Raw plaintext with all backslashes removed | **6.3 MB** |

So **obfuscating before compressing costs ~1.8× on disk** (34 vs 18.8 MB), and the O(N²)
escaping of §3 costs a further ~3× on top of that (18.8 vs 6.3 MB). Neither is a module‑content
problem — both are addressed in [wif-engine-optimizations.md](wif-engine-optimizations.md).

---

## 6. Cause #5 — no deduplication across identical pieces

`GameState.getRestorePiecesCommand()` emits **one `AddPiece` per piece with no dedup**
(`GameState.java:1609`, `new AddPiece(p)` in a bare loop; the `pieces` field is a plain `Map`,
`:119`). A stack of 100 identical counters writes its ~15 KB definition 100 times. In 002 the
single most‑duplicated piece shape (a "Return to Chit Pool" chit) appears **100 times at ~15.6 KB
each** = 1.5 MB for one kind of chit. After digit‑normalising positions/ids, the 8 855 pieces
collapse to 5 601 distinct shapes — i.e. a large fraction of the bytes are re‑serialised
copies of a smaller set of definitions.

---

## 7. What this means for memory (in the running engine)

The disk numbers mirror the heap cost, because the save is essentially a serialisation of the
live piece objects:

- **Per‑piece trait objects.** Each of the 8 855 pieces holds its full expanded decorator
  chain — median **201 linked `Decorator` objects**, each with its own parsed fields
  (strings, `NamedKeyStroke`s, expression objects, cached `KeyCommand[]`). That is on the order
  of **1.7 million live trait objects** for one game, none shared between pieces
  (`PieceCloner.clonePiece` deep‑copies; `UsePrototype`'s `intern()` only caches a *change‑detection
  string*, not the object chain — `UsePrototype.java:159`).
- **Embedded marker strings.** The `Land6` `markerSpec` is held verbatim as a field on all
  2 066 parent instances, never interned to a shared canonical copy (`PlaceMarker.java:104`).
- **The retained save string.** After a save, `GameState.lastSave` keeps the *entire* ~223 MB
  command log as a Java `String` (~446 MB of UTF‑16 `char[]`) for the whole session, used only
  by `isModified()` (`GameState.java:1379`, `:294`). Loading builds the same giant `String`
  (`IOUtils.toString`, `:1635`) — the developers already catch `OutOfMemoryError` here
  (`:1493‑1500`).

What *is* already shared and does **not** dominate: decoded **images**. All pieces sharing an
image share one raster via a single static `OpCache` (`AbstractOpImpl.java:65`); each piece
holds only a lightweight image handle. So the heap pressure is **trait objects and strings**,
not pixels.

---

## 8. Summary of leverage

| Cause | Drives | Fixable in… | Rough leverage |
|---|---|---|---|
| §2 Prototype expansion (200 traits/piece) | memory **and** disk | module design | **highest** — attacks the base content and (via §3) the escaping |
| §3 O(N²) escaping | memory (222 MB string), CPU, disk (~3×) | engine format | high (memory/CPU); high (disk, pre‑ZIP) |
| §4 Embedded Place Marker (`Land6`) | memory **and** disk | **module — one trait** | high value, near‑zero risk |
| §5 Obfuscate‑before‑zip | disk (~1.8×), memory, CPU | engine | ~2× disk, easy |
| §6 No dedup | disk | engine format | high, but format change |

The single highest‑value / lowest‑risk change is the **module‑side** fix in §4 (one Place
Marker: "Define" → "Select"). The largest *overall* win is reducing **traits per piece** (§2),
because escaping (§3) makes that cost quadratic. See the two companion documents for concrete,
prioritised steps.

---

### Appendix — how to reproduce these measurements

The extension‑utility already knows how to de‑obfuscate a `savedGame` (see `model/SavedGame`
and [vsav-format.md](vsav-format.md)). For ad‑hoc analysis, a throwaway decoder:

```python
data = open("savedGame", "rb").read()      # the raw ZIP entry
key  = int(data[5:7], 16)                   # after the '!VCSK' header
hx   = data[7:]
plain = bytes(int(hx[i:i+2],16) ^ key for i in range(0, len(hx), 2))
cmds  = plain.split(b"\x1b")                # ESC-delimited commands
adds  = [c for c in cmds if c[:2] == b"+/"] # AddPiece commands
# traits/piece ≈ c.count(b"\t")+1 ; escaping ≈ c.count(b"\\")
```

All figures above come from this method applied to `data/002-presetup-aif-everything.vsav` and
`data/003-prepopulate-ce-maps-everything.vsav`.
