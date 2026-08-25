# Module‑side memory & disk optimizations (WiF CE Official Combo)

Actionable changes to the **module** (`WiF CE Official Combo ver 2_1_1.vmod` and its
extensions) that reduce VASSAL memory usage and `.vsav` size **without changing any game
feature and without any engine code change**. Evidence and measurements are in
**[wif-save-bloat-analysis.md](wif-save-bloat-analysis.md)**; engine‑side changes are in
**[wif-engine-optimizations.md](wif-engine-optimizations.md)**.

All work here is done in the **VASSAL module editor** (or, for bulk edits, by rewriting
`buildFile.xml` — the same tree this extension‑utility manipulates). Every change is to a
**prototype or piece definition**; because prototypes are baked into pieces at placement time,
a fix to one definition automatically shrinks *every* piece and *every future save* that uses it.

> ⚠️ **Validate against existing saves.** Changing a prototype does **not** retroactively shrink
> pieces already stored in an old `.vsav` (those are baked). New/refreshed games get the benefit.
> Editing a piece's trait *type* can break loading of saves that stored the old type; test each
> change by loading a sample save and running **Tools → Refresh Counters**.

---

<a id="fix-1-status"></a>
## Fix 1 — Convert the embedded Place Marker to a reference — ✅ **DONE (module 2.1.2)**

> **Status: no further module work needed.** Verified against
> `WiF CE Official Combo ver 2_1_2.vmod` + 27 extensions: there are **zero** `placemark` traits
> anywhere — both by parsing every piece/prototype definition and by a raw text search for the
> string `placemark` across all 28 `buildFile.xml` files (0 occurrences). The trait was not
> converted to a reference, it was **removed outright**, which is better than this fix asked for.
> `Land6` still exists but now holds only `sendto` ×2 and `return`; the commands the marker used
> (`dropchicommkr`, `deletechcommkr`) appear nowhere in the module.
>
> ⚠️ **But four saved games still carry baked copies, and Refresh Counters cannot clear them** —
> see [Leftovers in existing saves](#fix-1-leftovers-in-existing-saves) below.

The original analysis, for reference:

**What / where.** There is exactly **one** Place Marker (`placemark`) trait in the whole
module + extensions, inside prototype **`Land6`**. It is defined with an **embedded** marker
("Define Marker"): its definition contains a full inline piece (`+/null/…mark;…prototype;SUBDefaults12…`)
rather than a pointer to a palette piece. That embedded blob is copied onto **every one of the
~2 066 pieces** that expand `Land6`, in memory and in every save, whether or not a marker is
ever placed. (Background: [analysis §4](wif-save-bloat-analysis.md#4-cause-3--embedded-define-marker-place-markers).)

**Change.** Recreate the marker as a real **PieceSlot** in a Game Piece Palette (or reuse the
existing palette entry the marker represents), then in the `Land6` Place Marker trait:

1. Open the Place Marker trait editor.
2. Click **"Select"** (not "Define Marker") and pick the palette PieceSlot for the marker.
3. Save.

This replaces the large inline `markerSpec` with a short component path. In the engine terms:
`PlaceMarker.Ed.getType()` will emit the `markerSlotPath` branch (`PlaceMarker.java:804‑806`)
instead of the `GameModule.encode(new AddPiece(…))` branch (`:807‑810`).

**Behaviour.** Identical — the same marker piece is placed when the command fires (VASSAL
resolves the path lazily via `createBaseMarker()`, `PlaceMarker.java:427‑442`). The only
difference is where the marker's definition is stored: once, in the palette, instead of inline
on thousands of pieces.

**Expected impact.** Removes the embedded blob from ~2 066 pieces. Those pieces average ~30.7 KB
today; the embedded marker (and the extra `SequenceEncoder` nesting level it introduces) is a
large fraction of the *difference* between them and the ~23 KB baseline. Also reduces the
per‑instance memory each of those pieces holds (`markerSpec` field, `PlaceMarker.java:104`).

**Risk.** Very low. One trait, well‑understood, behaviour‑preserving. Verify by placing the
marker in‑game before and after.

<a id="fix-1-leftovers-in-existing-saves"></a>
### Leftovers in existing saves — a refresh will *not* remove them

Changing the module does not retroactively shrink pieces already stored in a `.vsav`, and in this
case **Refresh Counters cannot fix them either**. Four scenarios still carry the embedded blob:

| scenario | pieces carrying it | their bytes | % of save |
|---|---:|---:|---:|
| `103-presetup-aif-superdeluxe-nonfif` | 56 | 1 451 331 | 0.69% |
| `104-presetup-aif-deluxe-nonfif` | 56 | 1 451 331 | 0.84% |
| `106-presetup-ssw-superdeluxe-nonfif` | 63 | 1 638 883 | 0.75% |
| `108-presetup-ssw-deluxe-nonfif` | 63 | 1 638 883 | 0.91% |

The stored trait is the embedded form this fix describes —
`placemark;;58231,0,dropchicommkr;+\/null\/mark\;numcvs…` with `SUBDefaults12`,
`NavalDefaults12` and `ACFTDefaults12` inlined. Each carrier's type is **~21.6 KB**. The carriers
are convoy/oiler counters (`CW S CPs Mod`, `FR Co Oilers Mod`, `CH Tanker Mod`), not land units,
so this is a different Place Marker from the `Land6` one originally found — or the same one after
it moved.

**Why a refresh does not help.** Tested directly: refreshing `103` against 2.1.2 reported
*5962 counters collected, 5962 refreshed, 0 warnings* and left all 56 in place, with the
deobfuscated command log **byte-for-byte the same length** (209 165 110 before and after).

Every one of those pieces has **`map = null`** — it is not on any map, and sits in a stack that is
likewise off-map. `GameRefresher.getRefreshables()` builds its work list by walking **map
contents**, so an off-map piece is never collected and never rebuilt. The `DeleteNoMap` option is
no help either: it only applies to pieces the refresher collected (and VASSAL has it disabled in
its own dialog over issue 12902).

These are therefore **orphaned pieces** — invisible in play, immune to Refresh Counters, and
carried in memory and in every save indefinitely. **`tools/remove_placemark_carriers.py` removes
them** (dangling stack references are harmless — `Stack.setState()` skips ids it cannot resolve).
Applied to the 16 scenarios it cleared 238 pieces: 56 each from `103`/`104` and 63 each from
`106`/`108`. One carrier is deliberately kept — `US BB Alaska` in `106` is *on* a map, so it is
not an orphan; it is also unmatchable by GPID, which is why a refresh never repaired it, making it
a case for the Excess Units tool.

**Wider note.** Off-map pieces are not confined to those four: `101`, `102` and `120` carry **529**
each with no Place Marker among them, while `105` and `107` have **zero** — which suggests the
population is avoidable rather than structural. **`tools/remove_offmap_pieces.py` audits this** —
it reports by default and writes only with `--apply`, and can exclude groups by name. The bulk is
ownership markers (`US Owned`, `CW Owned`, `MajP Lending Strip`), which look like a deliberate
off-map pool rather than debris, so review the report before deleting anything. Its `--csv` option
writes an exact manifest of the pending deletion — one row per piece, with scenario, counter name,
GPID, defining archive, container and stored position — for review in a spreadsheet. Across the 16
scenarios that is **3043 pieces in 14 of them** (`105` and `107` have none), spanning 57 distinct
counters, all of them loose rather than in a stack or deck, and all but 91 defined by the module
itself.

---

## Fix 2 — Reduce traits per piece (the big one)

> 📄 **Detailed follow-up: [wif-fix2-trait-reduction.md](wif-fix2-trait-reduction.md)** — names the
> specific traits that can move to the map, the specific near-duplicate prototypes, where the
> `mark` constants come from, and the dead traits with their locations. It also corrects two
> things below: `macro` is the editor's **Trigger Action** trait, and the median piece carries
> **102** traits, not 201 (the earlier count included each prototype's own terminal Basic Piece,
> which pieces do not inherit).

**Why it matters most.** The median WiF piece carries **201 traits** (normal pieces have 10–20),
almost all from expanded prototypes. Escaping overhead is **quadratic** in trait count
(`backslashes ≈ 0.251 × traits²`, R² = 0.999 — [analysis §3](wif-save-bloat-analysis.md#3-cause-2--sequenceencoder-escaping-is-otraits-per-piece)),
so trait count drives *both* the genuine byte count *and* a quadratic escaping penalty, in memory
and on disk. Halving traits per piece roughly **halves** the base content and **quarters** the
escaping. This is where the largest structural win lives.

This is a **design refactor**, not a mechanical edit — it requires game‑logic judgement to
preserve features. Candidate techniques, all feature‑preserving:

1. **Move shared automation off the pieces and up to the map/module.** Any `macro` (Trigger
   Action / Global Key Command) or `globalkey` trait that is *identical on thousands of pieces*
   and fires the same logic can often be implemented **once** as a map‑level **Global Key
   Command** or a **module‑level Global Hotkey** that targets the pieces by property match,
   instead of being embedded per piece. The single biggest contributor to trait count is
   `macro` (232 075 copies in the save from 655 authored). Each such trait relocated off the
   prototype removes one copy from *every* piece that used it — and shrinks the quadratic
   escaping of the pieces that remain.

2. **Consolidate near‑duplicate prototypes.** *(Measured since: this saves almost nothing on its
   own — traits inherited twice by one piece total 0.1%. Its value is making the eliminations in
   technique 1 and 3 a single edit instead of seven. See the
   [deep dive §3](wif-fix2-trait-reduction.md#3-consolidating-near-duplicate-prototypes--what-it-actually-buys).)* 207 prototype definitions with heavy overlap
   (the `…Defaults…` family: `SUBDefaults12`, `NavalDefaults12`, etc.) tend to accumulate
   copy‑pasted trait stacks. Factor the common traits into a shared base prototype that the
   variants include, so a unit expands *one* copy of the common logic rather than several
   overlapping ones. (This reduces authoring duplication; note it does **not** reduce the final
   *expanded* count unless traits are genuinely eliminated — the win comes from removing traits
   that are redundant once consolidated.)

3. **Delete dead / never‑referenced traits.** Long‑lived modules accumulate leftover traits:
   `mark` (Marker) constants that nothing reads, disabled Layers, obsolete Triggers. `mark`
   is the #2 contributor (191 484 copies in the save). Audit each shared prototype for markers
   and macros whose property/command names appear nowhere else, and remove them. Every trait
   removed from a widely‑used prototype is multiplied by its usage count.

4. **Prefer computed/dynamic values over many static Markers.** Where a cluster of static
   `mark` traits encodes constants that could be derived (e.g. via a Calculated Property or a
   single delimited Marker), collapsing them cuts trait count without losing the values.

**How to prioritise.** Rank shared prototypes by *(trait count) × (number of pieces that use
them)*. The `Land6`/naval/`…Defaults…` prototypes used by thousands of counters give the most
leverage per trait removed. Use the reproduction script in
[analysis §Appendix](wif-save-bloat-analysis.md#appendix--how-to-reproduce-these-measurements)
to count trait codes before/after.

**Risk.** Medium — each relocation/removal must be verified to preserve behaviour (menus,
triggers, reports). Do it prototype‑by‑prototype with a load + Refresh Counters + play‑test loop.

---

## Fix 3 — Replace with Other: nothing to do

The community member suggested removing **Replace with Other** (`replace`) traits. This module
contains **zero** `replace;` traits — confirmed across the module and all extensions. This
matches the user's own search. No action needed; recorded here so the next agent does not
re‑investigate.

---

## Fix 4 — Audit for embedded definitions creeping back in

The embedded‑marker pathology (Fix 1) can reappear whenever an editor uses **"Define …"**
instead of **"Select"**:

- **Place Marker** → "Define Marker" vs "Select" (`PlaceMarker.java:658‑682`).
- **Replace with Other** → "Define Replacement" vs "Select" (same pattern in `Replace`/its
  editor) — not currently used, but guard against it if Replace is ever added.

As a standing rule for module maintainers: **always use "Select"** to point these traits at a
palette PieceSlot. A quick lint is to scan `buildFile.xml` (and each `.vmdx`) for a `placemark`
or `replace` trait whose marker/replacement field begins with the AddPiece prefix (`+/`) after
un‑escaping — that indicates an embedded definition. The extension‑utility is well placed to
offer such a scan as a future tool (it already parses the same trees and piece‑definition text).

---

## Expected combined effect

- **Fix 1** — ✅ done in module 2.1.2; the trait is gone module-wide. What remains is cleaning the
  baked copies out of four saved games, which a refresh cannot do (see above).
- **Fix 2** is the structural lever: because of quadratic escaping, cutting the median trait count
  materially shrinks *every* piece in *both* memory and every future save, and reduces the live
  trait objects the engine holds during play. Detailed follow-up:
  **[wif-fix2-trait-reduction.md](wif-fix2-trait-reduction.md)** (which also corrects the median
  to **102** traits, not 201).
- **Fixes 3–4** keep the module from regressing.

None of these changes any game feature; they change *how* the same behaviour is stored. Combined
with the engine‑side changes in [wif-engine-optimizations.md](wif-engine-optimizations.md)
(compression/obfuscation ordering, escaping format, streaming), the two together attack every
cause identified in the [analysis](wif-save-bloat-analysis.md).
