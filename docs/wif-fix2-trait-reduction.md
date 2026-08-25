# Fix 2 deep dive — reducing traits per piece

Detailed follow-up to **[Fix 2](wif-module-optimizations.md#fix-2--reduce-traits-per-piece-the-big-one)**
of [wif-module-optimizations.md](wif-module-optimizations.md), answering four questions it
left open: which automation can actually move off the pieces, what consolidating
near-duplicate prototypes really buys, where the `mark` constants come from, and which traits
are dead.

Measured against **`WiF CE Official Combo ver 2_1_2.vmod` + 27 extensions** (6182 piece slots,
207 prototypes), i.e. *after* the embedded Place Marker removal
([Fix 1](wif-module-optimizations.md#fix-1-status), confirmed complete: zero `placemark`
traits remain module-wide).

---

## 0. Trait vocabulary — why you cannot find "macro"

Trait codes in a piece definition are VASSAL's internal `ID` constants, not the names the editor
shows. `macro` is **Trigger Action**. That single mismatch is why the trait named as the biggest
contributor could not be found in the module.

| code in definition | editor calls it | code | editor calls it |
|---|---|---|---|
| `macro` | **Trigger Action** | `hideCmd` | **Restrict Commands** |
| `mark` | **Marker** | `submenu` | **Sub-Menu** |
| `emb2` | **Layer** | `setprop` | **Set Global Property** |
| `PROP` | **Dynamic Property** | `globalkey` | **Global Key Command** |
| `calcProp` | **Calculated Property** | `globalhotkey` | **Global Hotkey** |
| `label` | **Text Label** | `sendto` | **Send to Location** |
| `piece` | **Basic Piece** | `prototype` | **Prototype** |
| `obs` | **Mask** | `hide` | **Invisible** |
| `report` | **Report Action** | `AreaOfEffect` | **Area of Effect** |
| `markmoved` | **Mark When Moved** | `footprint` | **Movement Trail** |
| `matPiece` | **Mat Cargo** | `nonRect2` | **Non-Rectangular** |

The full list is in the engine: each class in `VASSAL/counters/` declares
`public static final String ID`.

## 1. Corrected baseline

The earlier figure of "median 201 traits" over-counted: a prototype definition's own text ends
with a terminal `piece;;;;` (its Basic Piece), and that is **not** inherited by pieces using the
prototype — only the decorators are. Excluding it:

| | |
|---|---|
| expanded traits, all pieces | **757 347** |
| traits per piece | min 4, **median 102**, mean 123, p90 208, max 235 |

| code | editor name | authored | expanded | ×  |
|---|---|---:|---:|---:|
| `macro` | Trigger Action | 744 | **221 539** | 298 |
| `mark` | Marker | 28 600 | **162 668** | 6 |
| `PROP` | Dynamic Property | 561 | 75 020 | 134 |
| `hideCmd` | Restrict Commands | 102 | 61 850 | **606** |
| `setprop` | Set Global Property | 425 | 57 165 | 135 |
| `emb2` | Layer | 6 974 | 54 913 | 8 |
| `sendto` | Send to Location | 51 | 15 197 | 298 |
| `globalkey` | Global Key Command | 46 | 13 162 | 286 |
| `calcProp` | Calculated Property | 574 | 12 905 | 22 |
| `submenu` | Sub-Menu | 19 | 12 103 | **637** |
| `AreaOfEffect` | Area of Effect | 10 | 8 381 | **838** |

The `×` column is the leverage: one trait edited in a widely-inherited prototype moves that many
copies. Escaping is quadratic (`≈0.251 × traits²`), so cutting a piece's trait count by 10% cuts
its escaping overhead by about 19%.

---

## 2. Which automation can move to the map or module

**The shape that can move.** A Trigger Action of the form *"when key K arrives, if property P
holds, send key A"* is exactly what a **Global Key Command** does — except the GKC lives once on
a Map Window (or the module) and finds its targets by property match, instead of one copy riding
on every piece.

**The shape that cannot.** A Trigger Action with a **menu command** string is a right-click menu
item on that piece. Moving it would remove the menu entry, so it stays.

Splitting the 744 authored Trigger Actions on that line:

| shape | authored | expanded copies |
|---|---:|---:|
| **no menu command** — pure automation, relocatable | 474 | **155 635** (70%) |
| has a menu command — piece menu item, keep | 270 | 65 904 |
| …of the relocatable ones, those firing exactly one key | 112 | 74 379 |

### Cluster A — the four layer-reset triggers (21 040 copies)

Authored **identically in seven prototypes**: `ACFT8`, `Land8`, `Naval8`, `SUB8`, `CVCVLCVE8`,
`trsamph8`, `cpdeasw` — 5260 pieces between them, so 4 × 5260 = **21 040 expanded copies**.

| trait name | watches (named key) | condition | sends |
|---|---|---|---|
| `Isolated Turn Off` | `turnoffisolated` | `{isoreorganisation_Active=="true"}` | `79,130` |
| `OOS Turn Off` | `turnoffoos` | `{OOS_Active=="true"}` | `80,130` |
| `Unflip if Flipped` | `unflip` | `{rev_Active=="true"}` | `70,520` |
| *(face-down reset)* | — | `{facedown_Active=="true"}` | `70,130` |

Each says "if this Layer is showing, send the key that turns it off". **Replacement:** four Global
Key Commands, once each, on the map (or module). Give each the same named key as its trigger
(`turnoffisolated`, …), the same property match, and the same key to send. Delete the trigger from
all seven prototypes.

Where to make the edit — all in the module, not an extension:

```
PrototypesContainer / Counters / ACFTTraits          → ACFT8
PrototypesContainer / Counters / Land Traits         → Land8
PrototypesContainer / Counters / Naval Traits        → Naval8
PrototypesContainer / Counters / SUB Traits          → SUB8
PrototypesContainer / Counters / CV_CVE_CVL Traits   → CVCVLCVE8
PrototypesContainer / Counters / TRS AMPH Traits     → trsamph8
PrototypesContainer / Counters                       → cpdeasw
```

### Cluster B — the `resetrebaseicon` fan-out (21 144 copies)

17 authored Trigger Actions, all watching the named key `resetrebaseicon`, each sending one key
under one condition. Concentrated in `ACFT8` (16 511 copies) and `Land8` (4228), with a few in
`Headquarters` (357) and `Paradropping` (48). Named members include `Turn Off Rebase Icon`,
`Turn Off Super Rebase`, `Turn Off Railed Icon`, `For AoE Turn Off`, `For Movement Trail Turn
Off`, `Reset OP x2 Flag`, plus unnamed ones sending `flynightstrat`, `gndinterception`,
`althighfly`, `flyasabmbr`, `extendedrange`.

This is a pure fan-out: one incoming key, seventeen conditional outgoing keys, none of them a
menu item. **Replacement:** seventeen GKCs on the map, all keyed to `resetrebaseicon`, each with
its own property match. Nothing on the piece is needed.

### Total from the two clusters

**~42 000 expanded traits (5.5% of all)** removed by relocating 21 authored traits, with no
change to menus or behaviour. Across all 53 duplicated trigger shapes the reachable figure is
**60 138 copies**.

### Also worth relocating

`globalkey` (Global Key Command **as a piece trait**, 46 authored → 13 162 copies) and
`setprop` (Set Global Property, 425 → 57 165). A piece-level GKC sends keys to *other* pieces —
usually the same fan-out pattern, and usually expressible once at map level. Audit these next.

**Not relocatable** — intrinsic per-piece behaviour, despite tempting multipliers:
`AreaOfEffect` (×838), `footprint`/Movement Trail (×623), `rotate`/Can Rotate (×493),
`markmoved` (×486), `matPiece`/Mat Cargo (×535), `nonRect2` (2114 copies from one trait),
`submenu` and `hideCmd` (menu structure).

---

## 3. Consolidating near-duplicate prototypes — what it actually buys

**Measured: almost nothing, directly.** Traits inherited *twice* by the same piece through two
prototype paths total **446 copies — 0.1%** of all expanded traits:

| redundant copies | trait |
|---:|---|
| 263 | `mark;aa` |
| 129 | `immob;A;I;R;` |
| 17 | `macro;Delete Text;…` |
| 17 | `delete;;127,195;` |
| 10 | `mark;nav` |

So consolidation is **not** a memory fix. VASSAL expands each prototype's traits into the piece
regardless of how tidily they are factored; the saving only appears when a trait is genuinely
*eliminated*.

**What it is good for:** making the Fix-2 work above a single edit instead of seven. The naval
family overlaps heavily — identical trait sets, copy-pasted:

| prototypes | identical traits shared | pieces |
|---|---:|---|
| `Naval8` ↔ `SUB8` ↔ `CVCVLCVE8` ↔ `trsamph8` | **13** each pair | 835 / 263 / 301 / 211 |
| `Naval12` ↔ `SUB12` ↔ `CVCVLCVE12` ↔ `trsamph12` | 10–12 | same |
| `SUB3` ↔ `CVCVLCVE3` ↔ `trsamph3` | 10 | same |
| `japan` ↔ `ussr` ↔ `italy` | 9 | 609 / 619 / 390 |
| the seven-member `…8` family | 5 (4 Trigger Actions + terminal Basic Piece) | 5260 total |

### Worked example

1. Create `NavalCommon8` under `PrototypesContainer / Counters / Naval Traits`.
2. Move into it the **13 traits** common to `Naval8`, `SUB8`, `CVCVLCVE8` and `trsamph8`.
3. Replace those 13 in each of the four with a single `Prototype → NavalCommon8`.

Expanded trait count afterwards: **unchanged** (13 traits still expand, plus a `prototype` trait
that resolves away). What changes is that Cluster A's four triggers now exist in *one* place, so
the relocation in §2 becomes one deletion rather than seven — and the next such change is cheap.

Do the consolidation **because** it makes elimination tractable, not as a saving in itself.

---

## 4. Where the `mark` constants come from

Markers are the #2 contributor. A Marker's *type* holds only property **names**
(`mark;<name1>,<name2>`); the values live in the piece state. Counting names:

| authored where | marker names | expanded |
|---|---:|---:|
| **directly on piece slots** | 58 429 | 58 429 (1:1, no multiplication) |
| in prototypes | 273 | **134 341** |

Two very different populations:

**Per-counter unit factors, authored on each PieceSlot.** These are the numbers printed on the
counter — no prototype involved, one copy per counter, so they do not multiply. The largest single
block is 11 names repeated on ~2108 slots each:

```
corp, colour, hq, div, type, arty1, arty2, hwmodbp, future1, future2, future3
```

spread across `21-PatiF-AmiF-HWs` (757 slots), the module itself (742), `14-DiF` (385) and
`15-TiF` (198). `FPDeck` accounts for a further 5205. Reducing these means changing how unit
factors are stored (see Fix 2 technique 4 — one delimited Marker instead of many), and touches
thousands of slots for a 1:1 return. **Low leverage.**

**Prototype markers, multiplied by usage.** Only 273 authored, but ~6000 copies each for the top
names — these are the high-leverage ones:

```
nosubs 6127   noships 6075   noacft 6075   numcvs 6074   numcls 6074
numcas 6074   nocps 6063     baseasw 6016   subsurf 5864  OilUse 5341
BaseType 5301  shbomb 5292
```

**Where to look first:** the prototype markers, and specifically the ones nothing reads (§5).

---

## 5. Dead and never-referenced traits

Method: for each marker property name, search **every** `buildFile.xml` in the module and all 27
extensions (7.3 M characters) for the name as a whole word, then subtract its own `mark;`
declarations. A verdict of *never referenced* means the token appears nowhere but its own
declaration, so it is safe; the reverse can be a false positive (e.g. `range` matches a map grid's
`range="Metric"` attribute), which errs toward keeping things.

Validation: `aa` is correctly classed as **live** — it appears in `{aa}` expressions and an
`incaa` command.

### 5a. Markers nothing reads — 15 of 75 names, ~19 500 expanded copies

| marker | authored | expanded | where |
|---|---:|---:|---|
| `shbomb` | 841 | **5292** | prototypes `cpdeasw`, `taskforces`, `CVCVLCVE12`, `NavalDefaults12`, `trsamph12`, `OwnedBase` + 835 slots in `10-SiF`/`13-CLs`/`09-ClassicShips` |
| `hexmkrinplace` | 1 | **2114** | prototype `Land12` — one trait, 2114 pieces |
| `future1`,`future2`,`future3` | 2106 each | 0 | slots in `21-PatiF-AmiF-HWs`, module, `14-DiF`, `15-TiF` — **6318 keys, pure placeholders** |
| `hwmodbp` | 2106 | 0 | same slots as above |
| `move` | 1635 | 0 | slots in `10-SiF` (728), `09-ClassicShips` (299), `13-CLs` (249), `20-PatiF-AmiF-Ships` (99) |
| `tptval` | 7 | 1686 | prototypes `cpdeasw`, `oilcps`, `rescps`, `Naval12`, `SUB12`, `CVCVLCVE12`, `trsamph12` |
| `BuildTime` | 2 | 282 | prototypes `cpcvasw`, `SUB12` |
| `flakcf` | 40 | 0 | `14-DiF`, under `ARTY/FLAK` (29) and `ARTY/SAM` (11) |
| `cvaircomponent_Name` | 22 | 0 | a Layer's auto-generated `_Name` re-declared as a Marker |
| `jungle3d`, `winter`, `surf5`, `cycle2cost` | 1–3 | 0 | scattered |

`move` deserves a second look before deleting — 1635 counters declare a movement factor that
nothing in the module reads. Either the value is only ever shown on the counter art, or something
was meant to read it and never did.

Best single edits here: **`hexmkrinplace`** (delete one trait in `Land12`, remove 2114 copies) and
**`shbomb`** (six prototypes, 5292 copies).

### 5b. Trigger Actions that fire nothing — 79 authored, 16 747 copies

Trigger Actions with an **empty action-key list**: invoking them does nothing. They exist only to
put text in the right-click menu. Two kinds:

**Deliberate menu labels** — e.g. in `cpdeasw` and `oilcps`:

```
macro;Delete Text;DELETE - Use CTRL SHIFT DELETE;57506,0,thisdoesnothing;;;;false;…
```

The named key is literally `thisdoesnothing`. This is an instruction to the player rendered as a
menu row. Costly but intentional; leave unless the instruction can move elsewhere.

**Genuinely broken** — the Mat commands in `cpdeasw` (and siblings):

```
macro;Force Attach to Mat;Force Attach to Mat;74,585;;;;false;…      ← fires nothing
macro;Force Detach from Mat;Force Detach from Mat;76,585;;;;false;…  ← fires nothing
matPiece;As Cargo;true;0;0;74,520;75,520                             ← the real commands
```

The Mat Cargo trait binds `74,520`/`75,520`; the menu triggers bind `74,585`/`76,585`. **Nothing
listens on those keystrokes**, so both menu items do nothing when clicked — the modifier changed
at some point and the menu triggers were not updated. Either repoint them at the Mat Cargo keys or
delete them.

---

## 6. Priority

| # | action | expanded traits removed | risk |
|---|---|---:|---|
| 1 | Delete `hexmkrinplace` from `Land12` | 2 114 | trivial |
| 2 | Delete `shbomb` from its 6 prototypes | ~5 300 | trivial |
| 3 | Delete `tptval`, `BuildTime` from their prototypes | ~2 000 | trivial |
| 4 | Fix or delete the broken "Force Attach/Detach to Mat" triggers | ~2 000 | low |
| 5 | Relocate Cluster A (4 triggers × 7 prototypes) to map GKCs | **21 040** | medium |
| 6 | Relocate Cluster B (`resetrebaseicon`, 17 triggers) to map GKCs | **21 144** | medium |
| 7 | Drop `future1-3`, `hwmodbp`, `move`, `flakcf` from slots | ~14 700 keys | low, but thousands of slots |
| 8 | Audit piece-level `globalkey` and `setprop` for the same pattern | up to 70 000 | medium |

Items 1–6 alone remove **~54 000 expanded traits (7%)**, and because escaping is quadratic the
byte saving on the affected pieces is roughly double that in proportion. Items 1–4 are deletions
of things that provably do nothing.

Steps 5, 6 and 8 need a load + **Tools → Refresh Counters** + play-test loop per prototype, as
Fix 2 warns.

---

## Appendix — reproducing these numbers

Parse every archive's `buildFile.xml`, collect `PieceSlot`/`CardSlot` and `PrototypeDefinition`
definitions (each is a `+/null/<type>/<state>` command), split the type on `\\*\t`, and take each
trait's code as the text before its first `;`. Then:

- **expansion**: replace each `prototype;<name>` trait with that prototype's traits, recursively,
  **dropping the prototype's own terminal `piece;` trait**;
- **usage weight**: count how many piece slots reach each prototype, so an authored trait's cost is
  `1 × (pieces reaching it)`;
- **liveness**: for a marker name, count whole-word matches across all `buildFile.xml` text and
  subtract matches inside `mark;` traits.

`macro` fields are `name;command;key;propertyMatch;watchKeys;actionKeys;…`
(`TriggerAction.myGetType()`); a trait is relocatable when `command` is empty and `actionKeys` is
not.
