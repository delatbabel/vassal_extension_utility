# Command-line tools

Standalone Python 3 scripts for editing VASSAL files outside the GUI — useful for
building a series of pre-setup scenario files from one another, and for repairs
the application does not (yet) offer. They are **not** part of the Java
application and have no dependencies beyond the Python standard library.

| script | edits | what it does |
|---|---|---|
| `swap_maps.py` | `.vsav` | copy a map layout from one save into another |
| `shift_pieces.py` | `.vsav` | translate the pieces on one map |
| `fix_sif_subs.py` | `.vsav` | swap mis-named counters for their correct twins |
| `remove_ext_counters.py` | `.vsav` | delete every counter belonging to given extensions |
| `remove_placemark_carriers.py` | `.vsav` | delete off-map pieces carrying a stale embedded Place Marker |
| `remove_offmap_pieces.py` | `.vsav` | report (and optionally delete) every piece that is on no map |
| `dedupe_pieces.py` | `.vsav` | reduce duplicated counters to one copy each |
| `copy_counter_positions.py` | `.vsav` | give counters the positions they hold in a reference save |
| `missing_counters.py` | — (read-only) | report an extension's counters that a save does not contain |
| `renumber_gpids.py` | `.vmdx` | clear duplicate Piece Ids |
| `drop_slots.py` | `.vmdx` | delete piece slots by Piece Id |

The saved-game scripts work the way `model/SavedGame` does (see
[docs/vsav-format.md](../docs/vsav-format.md) and
[docs/vsav-excess-units.md](../docs/vsav-excess-units.md)):

- the `savedGame` entry is deobfuscated whole (`!VCSK` + 2-hex key + XOR-hex; the
  `!VCSZ` variant additionally inflates the result, and a rewrite re-emits the
  same format it read — see docs/vsav-format.md);
- the command log is split at **every** ESC (`0x1B`), recording each token's
  preceding-delimiter bytes separately from its content. Because piece data
  contains no ESC, re-emitting the same delimiter bytes reconstructs the
  two-level `SequenceEncoder` nesting exactly;
- every token not being edited is copied **verbatim** — never decoded and
  re-encoded — then the whole stream is re-obfuscated with a fresh random key;
- `savedata` and `moduledata` are copied byte-for-byte, and the output is
  written to a temp file and moved into place, so a failed run never leaves a
  truncated `.vsav` (which is what makes VASSAL report *"… is not a VASSAL saved
  game or log"*).

`swap_maps.py` and `shift_pieces.py` always write a **new** file; pass the same
path for input and output only if you deliberately want to edit in place.
`fix_sif_subs.py` takes a list of saves and writes new `… (subs fixed).vsav`
files unless `--in-place` is given.

## swap_maps.py — copy a map layout from one save into another

```
tools/swap_maps.py TARGET DONOR OUT MAP [MAP...]
tools/swap_maps.py TARGET DONOR OUT ALL
```

Replaces the `<mapName>BoardPicker` command(s) in `TARGET` with the corresponding
command(s) from `DONOR`, writing `OUT`. A map's whole layout lives in that one
command — `<mapName>BoardPicker\t<board>[/rev]\t<col>\t<row>\t…` (see
`BoardPicker.encode()` in the engine) — so this is the entire "which boards, in
what grid" change. `ALL` does every map the two saves have in common; naming
individual maps (e.g. `"World Maps"`) restricts it to those.

It refuses to splice a token that is nested (its ESC delimiter was escaped) or
whose replacement contains an ESC, so a bad match can't corrupt the log. The
before/after layout of each replaced map is printed.

Example — give a full-world pre-setup the three-board Eastern-Front layout of an
empty Barbarossa save:

```bash
tools/swap_maps.py \
    data/112-presetup-ce-maps-deluxe-nonfif.vsav \
    data/025-barbarossa-empty.vsav \
    data/113-presetup-barbarossa.vsav ALL
```

### Pieces do not move with their board

Piece coordinates are absolute in map space. `Map.setBoardBoundaries()` places
the board at grid `(column, row)` at `dx = Σ boardWidths[0..column-1][row]`,
`dy = Σ boardHeights[column][0..row-1]` — so if a board changes column, its
pixel origin changes and any piece standing on it is left behind at the old
coordinates, possibly outside the new map bounds entirely. Nothing is lost (the
`AddPiece` commands are untouched), but such pieces become unreachable by
dragging. Use `shift_pieces.py` to bring them across.

## shift_pieces.py — translate the pieces on one map

```
tools/shift_pieces.py TARGET OUT MAP X_LO X_HI DX DY
```

Adds `(DX, DY)` to the innermost `BasicPiece` state coordinates
(`<map>;<x>;<y>;<gpid>;…`) of every `AddPiece` command on map `MAP` whose `x`
falls in `[X_LO, X_HI)` — the x-range of the board that moved, which is what
scopes the edit to the pieces that were standing on it. Only those two fields
change; the rest of every command is copied verbatim. Each moved piece's gpid
and old/new position is printed.

Example — the board that was at column 2 (origin x = 3507 + 6779 = 10286, width
6779) is now at column 0, so its pieces shift left by 10286; row heights are
unchanged above it, so `DY` is 0:

```bash
tools/shift_pieces.py \
    data/113-presetup-barbarossa.vsav \
    data/113-presetup-barbarossa.vsav \
    "World Maps" 10286 17065 -10286 0
```

Get the board widths/heights from the board images themselves — the `Board`
elements in the module/extension `buildFile.xml` name them, and the image
dimensions are the board dimensions.

## fix_sif_subs.py — swap mis-named counters for their correct twins

```
tools/fix_sif_subs.py [--in-place] [--keep-bak] [--dry-run] [--add]
                      [--pair OLD=NEW]... [--slots EXT.vmdx]...
                      EXTENSION.vmdx SAVE.vsav [SAVE.vsav...]
```

Written for the WiF `10-SiF.vmdx` extension, which holds two copies of some
submarine counters: the correct SiF ones named `<nation> S SUB <name>`, and
incorrect leftovers named `<nation> SUB <name>` (no `" S "`). Saved games built
before the fix contain the incorrect pieces; this rewrites each into its twin.

The pairs are **derived from the extension**, not hard-coded, and used only if the
two definitions differ in exactly two traits — the Embellishment (`emb2;Flip;…`,
whose flip image gains the `sif` suffix) and the innermost
`piece;;;<image>;<name>`. Any other difference means the two are different
counters, not a duplicate, so the pair is **refused and reported** rather than
guessed at.

### Two naming shapes

The `" S "` marking the SiF counter sits in one of two places, and both are
recognised:

| plain name | SiF twin | rule |
|---|---|---|
| `CW SUB Amphion` | `CW S SUB Amphion` | type is a word in the middle |
| `CW T CA SUB1` | `CW T CA S SUB1` | name ends in the type |
| `CH T SUB` | `CH T S SUB` | ditto, no trailing number |

A name that already carries the `" S "` yields no twin, so the script can never
double-apply.

### When the twins live in another extension

`--slots EXT.vmdx` (repeatable) pools counter definitions from further archives,
so the two halves of a pair may come from different extensions. That is the case
once the S counters are split out into an extension of their own — the plain
counters stay in `20-PatiF-AmiF-Ships` while their twins live in
`25-PatiF-AmiF-SiF-SUBs`:

```bash
tools/fix_sif_subs.py --in-place \
    "…_ext/20-PatiF-AmiF-Ships.vmdx" \
    --slots="…_ext/25-PatiF-AmiF-SiF-SUBs.vmdx" \
    data/scenarios/*.vsav
```

A pair that crosses archives is reported as such:
`CW T CA SUB1 -> CW T CA S SUB1  [20-PatiF-AmiF-Ships.vmdx -> 25-PatiF-AmiF-SiF-SUBs.vmdx]`.

Note that swapping counters into a different extension makes the scenario depend
on that extension, which its recorded extension list will not mention. Running
**Refresh Counters** afterwards adds the entry (see
[docs/refresh-counters.md](../docs/refresh-counters.md)).

### `--add`: keep the original and add the twin beside it

Default behaviour **replaces** the counter. `--add` instead **keeps it and adds
its twin into the same stack**, which is what the "everything" scenarios need:
their force pools are meant to hold one copy of every counter, so both the plain
and the SiF version belong there.

For each match a new `AddPiece` command is emitted directly after the original,
carrying:

- **a fresh piece id**, allocated above the highest id already in the file;
- **the twin's type** — the original's type with the same two traits substituted
  that the replace path uses, which is exactly what the twin's own definition
  expands to;
- **the original's state**, with the innermost gpid repointed at the twin's slot
  and the `UniqueID` property reset to the new id. That last one matters: VASSAL
  keeps a piece's `UniqueID` equal to its own piece id, and two pieces sharing one
  is asking for trouble.

The new id is then threaded into the state of whichever stack listed the original,
**immediately after it**, so the twin lands in the same force-pool stack directly
alongside the counter it accompanies.

Insertion is positional among the id tokens rather than appended, because a
stack's state ends with a `@@<layer>` marker after the ids
(`Stack.HAS_LAYER_MARKER`) — an id appended after that marker would be misread.

```bash
tools/fix_sif_subs.py --add --in-place \
    "…_ext/20-PatiF-AmiF-Ships.vmdx" \
    --slots="…_ext/25-PatiF-AmiF-SiF-SUBs.vmdx" \
    data/scenarios/*everything*.vsav
```

Verify afterwards that piece ids are still unique, that every twin appears in a
stack, and that each twin's `UniqueID` matches its own id. Only counters whose
original is actually present get a twin — if the original is missing from the
scenario there is nothing to add beside.

A piece in a save is an `AddPiece` command whose type is the *expanded* trait
list (prototypes inlined), so it can never be compared to the slot definition as
a whole. But neither of the two differing traits contains a `/` or a tab, so
both appear verbatim in the expanded type at any nesting depth and can be
spliced directly. The innermost `BasicPiece` state's 4th `;`-field (the gpid) is
repointed at the correct slot — the value VASSAL itself stamps on a piece
dragged from that palette slot (`PieceSlot.getPiece()` sets `PIECE_ID` from the
slot's `gpid` **attribute**). The piece id, map, position, layer and properties
are all copied verbatim, as is every command not being edited.

A piece is only rewritten when **both** its name and its gpid match the
incorrect slot, so a same-named piece carrying some other gpid is left alone.

`--pair OLD=NEW` (repeatable) overrides the derived twin for one counter, for
when the `" S SUB "` name is already taken by an unrelated component. That is
the case for `GE SUB TypeVIIC`: the existing `GE S SUB TypeVIIC` is an original
SiF counter with its own trait layout, image and movement factor — refused by
the trait check above — while the converted twin is named
`GE S SUB TypeVIIC_S`. An override is validated exactly like a derived pair, so
naming the wrong counter is still refused rather than applied.

```bash
tools/fix_sif_subs.py --dry-run "…_ext/10-SiF.vmdx" data/scenarios/*.vsav
tools/fix_sif_subs.py --in-place "…_ext/10-SiF.vmdx" data/scenarios/*.vsav
tools/fix_sif_subs.py --in-place --keep-bak \
    --pair "GE SUB TypeVIIC=GE S SUB TypeVIIC_S" \
    "…_ext/10-SiF.vmdx" data/scenarios/*.vsav
```

`--in-place` moves each original to `<name>.vsav.bak` **before** writing, so the
backup is always the untouched file; it refuses to run if a `.bak` already
exists. On a second pass over already-rewritten saves, `--keep-bak` leaves that
existing `.bak` alone, so it stays the pristine original instead of becoming the
first pass's output. The tool is idempotent — a counter already rewritten no
longer matches an incorrect slot — so re-running it only picks up what is new.

## missing_counters.py — report counters a save does not contain

```
tools/missing_counters.py --ext-dir DIR --extensions 10,11,12 \
                          [--exclude N:WORD[,WORD...]]... \
                          [--csv OUT.csv] SAVE.vsav [SAVE.vsav...]
```

Some scenarios are meant to be complete: every counter of certain extensions
should be somewhere in them, on a map or in a force pool, so a player can reach
any unit those extensions add. Nothing in VASSAL answers that question, and the
failure is silent — the unit simply cannot be found in play. This reads only; it
never writes to a save.

`--extensions` takes the leading numbers of the extension filenames, so `10`
selects `10-SiF.vmdx`. Every `PieceSlot`/`CardSlot` in each named extension is one
counter to account for.

**Presence is by Piece Id**, which is what VASSAL itself matches on: each
`AddPiece` command in the save contributes the GPID in the 4th `;`-field of its
innermost BasicPiece state, wherever the piece sits — map, force pool, deck or
stack — so nothing is restricted to a particular map.

| status | meaning |
|---|---|
| `missing` | no copy of that Piece Id anywhere in the save |
| `off-map-only` | every copy has `map == "null"` — on no map at all, so unreachable in play and beyond the reach of Refresh Counters (see [remove_offmap_pieces.py](#remove_offmap_piecespy--audit-pieces-that-are-on-no-map)) |

Two columns say *why* a counter is absent, which decides what to do about it:

| column | meaning |
|---|---|
| `extension_listed_in_save` | whether the save loads that extension at all (its `EXT` commands). `no` means the scenario was built without it — every one of its counters is absent for that single reason, and the fix is to add the extension to the scenario, not to place counters |
| `name_found_elsewhere` | the unit is in the save under a *different* Piece Id — a renumbered slot or a copy from another extension. A bookkeeping mismatch rather than an absent unit |

```bash
tools/missing_counters.py --ext-dir "data/…2_1_3_ext" \
    --extensions 10,11,12,13,14,15,16,19,20,21,25,26,27 \
    --csv data/scenarios/missing-counters-sdx.csv \
    $(ls data/scenarios/*.vsav | grep -iE "superdeluxe|sdx")
```

### Counters that are not meant to be placed

Not everything an extension defines belongs in a scenario. `15-TiF` carries road,
rail, resource and oil markers and `21-PatiF-AmiF-HWs` carries the `T POLMkr`
political markers — all placed during play, none of them a unit anyone needs to
find in a force pool. Counted as missing they bury the real gaps, so
`--exclude N:WORD[,WORD...]` drops them from the set to account for, matching the
words against the counter name case-insensitively:

```bash
tools/missing_counters.py --ext-dir "data/…2_1_3_ext" \
    --extensions 10,11,12,13,14,15,16,19,21,25,26,27 \
    --exclude 15:road,rail,res,oil --exclude 21:POLMkr \
    --csv data/scenarios/missing-counters-sdx.csv \
    $(ls data/scenarios/*.vsav | grep -iE "superdeluxe|sdx")
```

Each exclusion is reported as a count against its extension (`15-TiF  242
counters  (49 excluded: road, rail, res, oil)`), so what was set aside stays
visible rather than silently shrinking the total.

### The opposite report

`--dup-csv OUT.csv` writes the counters a scenario holds **more than once**, from
the same scan — the copies are already counted to decide presence, so the two
reports cost one pass together and cannot contradict each other. Columns are
`scenario, extension, counter_name, gpid, copies, copies_on_map, copies_off_map`.

Per-extension and per-scenario tallies go to stderr as it runs, so a whole
extension that is absent stands out immediately; the CSV holds the detail.

## copy_counter_positions.py — copy counter positions from a reference save

```
tools/copy_counter_positions.py --reference REF.vsav --module MODULE.vmod
                                --anchor "Map Name;X;Y" --jobs DIR
                                [--gpid-file FILE] [--dry-run] [--no-backup]
                                SAVE.vsav [SAVE.vsav...]
```

Counters added in bulk land in one arbitrary spot and then have to be
distributed. Doing that by hand once, in a reference scenario, and copying the
result to the rest of the family is far less work than repeating it — and keeps
the family consistent.

**Two steps, because stacking belongs to the engine.** A counter's position is
three fields of its BasicPiece state, which this could rewrite directly. Its
*stacking* is not: a stack is a separate piece whose state lists members by id,
so re-homing a counter by hand means editing two stacks, getting the `@@<layer>`
suffix right, and hoping the destination stack exists. Instead each counter is
**removed** here and **re-placed by VASSAL** at the reference position, where
`Map.placeOrMerge` merges it into whatever stack is there — exactly what dragging
it would do. Removal needs no stack surgery either: the id left behind is
dangling, and `Stack.setState()` silently skips what it cannot resolve.

So the script writes the pruned saves plus one job file per save, and
[`AddCountersRunner`](../docs/refresh-counters.md#adding-counters--addcountersrunner)
completes the move:

```bash
tools/copy_counter_positions.py --reference data/scenarios/101-….vsav \
    --module "data/…2_1_3.vmod" --anchor "World Maps;207;60" \
    --gpid-file /tmp/gpids.txt --jobs /tmp/movejobs \
    data/scenarios/1*.vsav data/scenarios/2*.vsav

for job in /tmp/movejobs/*.job; do
  java -cp "$VENGINE:$UTILJAR" \
       org.vassalengine.extutil.refresh.AddCountersRunner "$job"
done
```

**What gets moved.** Only counters **at the anchor** with **exactly one copy in
the reference**. The anchor is what makes this safe against a scenario holding
other copies of the same counter: one already in its right place is never
touched. `--gpid-file` narrows it to a named set of Piece Ids and is usually what
you want — without it, *any* counter at the anchor that the reference also holds
is moved to the reference's position for it, including ones that were never part
of the exercise. A counter the reference does not hold stays where it is, which
is how a target may keep extras the reference never had.

## renumber_gpids.py — clear duplicate Piece Ids in an extension

```
tools/renumber_gpids.py EXT.vmdx [--start=N] [--dry-run] [--no-backup]
```

VASSAL refuses to run **Refresh Counters** while any two components share a Piece
Id (GPID). `GameRefresher.execute()` builds a `GpIdChecker` over every
`PieceSlot`, and if `hasErrors()` it logs *"Unable to run Refresh, module was
saved with older vassal version. Edit and save module with latest vassal version
first."* and returns **without refreshing anything**. That message is misleading:
`GpIdChecker.testGpId()` flags a GPID that is empty, non-numeric, or **already
seen**, and never looks at the VASSAL version.

Duplicates across extensions are easy to create. Extensions generate fresh ids as
`<extensionId>:<n>`, but a slot copied from the module or from another extension
keeps its plain numeric id, and `GpIdChecker` keys on the raw value when
extensions are loaded — so two extensions can claim the same number.

This script finds the target extension's slots whose GPID is also used by the
module or by any sibling extension (`<module>_ext/` and `<module>_ext/inactive/`,
located from the target's own path) and gives *those* slots fresh numbers,
allocated consecutively from `--start` (default 16000), skipping anything already
in use. The extension's `nextPieceSlotId` is advanced past the block.

Both places the id appears are updated: the `gpid="..."` attribute and the same
value inside that slot's own piece definition (`…;0;0;<gpid>;…`). Each id must
occur exactly twice in `buildFile.xml` — once as each — or the script refuses,
rather than risk rewriting a number it does not understand.

### Which side to renumber

A GPID is how a saved game refers to a piece definition, so renumbering a slot
orphans any piece in any save that points at it. Renumber the side of a clash
that **no saved game references**. Check before committing to it:

```bash
tools/renumber_gpids.py "…_ext/23-DoD-III.vmdx" --dry-run     # what would change
```

then look for the old numbers in your saves — for each `AddPiece`, the GPID is the
4th `;`-field of the innermost `BasicPiece` state, and the piece name the 5th
field of the innermost type, so you can see *which* of the two clashing
components a save actually holds. Pieces whose GPID no longer resolves can still
be matched by name (Refresh Counters' "Use counter names" option), but by GPID
they are lost.

### Never leave a spare copy in the extensions folder

`ExtensionsManager`'s file filter is only `!isHidden() && !isDirectory()`: VASSAL
loads **every** file in `<module>_ext/` whose metadata parses as an extension,
whatever it is called. A `foo.vmdx.bak` or `Copy of foo.vmdx` left there is loaded
as a real extension — which after a renumbering re-creates every duplicate GPID
it just removed, and is easy to miss (the giveaway is the extension count going
up by one). Backups therefore go in `<module>_ext/backups/`; directories are
skipped by that filter, and only `inactive` is also scanned. Use `--no-backup` if
you keep your own copies somewhere outside the extensions folder.

### Modification times are preserved

Only `buildFile.xml` is rewritten. Every other ZIP entry is copied byte-for-byte
**with its original modification time**, because VASSAL decides whether a cached
image tile is stale purely by comparing mtimes — restamping them forces a needless
re-tile of every board image (see
[docs/image-display-and-tiling.md](../docs/image-display-and-tiling.md)).

## drop_slots.py — delete piece slots from an extension

```
tools/drop_slots.py EXT.vmdx GPID [GPID...] [--version=X.Y.Z]
                    [--dry-run] [--no-backup]
```

For clearing a *duplicated* counter — the same component left behind in two
archives — which is the case renumbering cannot fix: giving the two copies
distinct Piece Ids would leave two identical counters in the palette. One copy
has to go.

When both copies share the same GPID **and** the same definition, deleting either
is safe for existing saved games: every piece pointing at that GPID still matches
the survivor. Check that the definitions really do match before choosing a side.

### The empty-wrapper trap

An extension never holds a component directly — each sits inside a
`VASSAL.build.module.ExtensionElement` naming where in the module's tree it
grafts. Deleting the component and leaving the wrapper produces an
`ExtensionElement` with nothing in it, which is XML-valid but makes VASSAL
**abort the whole module launch**: `ExtensionElement.build()` leaves its
`extension` field null and `addTo()` then dereferences it (see
[docs/vassal-empty-extensionelement-crash.md](../docs/vassal-empty-extensionelement-crash.md)).

So a wrapper left empty by a deletion is removed along with the slot; a wrapper
still holding other components is kept. The run says which of the two happened
for each slot. Verify afterwards that the extension has no empty wrappers.

`--version=X.Y.Z` also bumps the extension's version, in **both** places VASSAL
keeps it: the `version` attribute on the `ModuleExtension` root and `<version>`
in the separate `extensiondata` entry.

```bash
tools/drop_slots.py "…_ext/19-PatiF-AmiF-ACFT.vmdx" \
    2634 2635 2654 2655 7149 --version=2.1.2
```

## remove_ext_counters.py — delete every counter from given extensions

```
tools/remove_ext_counters.py MODULE.vmod EXT_NAMES SAVE.vsav [SAVE.vsav...]
                             [--drop-listing] [--dry-run] [--no-backup]
```

For a scenario that has picked up counters from an extension it was never meant
to be played with: the extension was active when the scenario was built, so its
pieces went into the force pools, but the scenario's own extension list never
included it. **Refresh Counters cannot fix this** — the pieces match their
definitions perfectly, so they are not "excess" in the Excess-Units sense
([docs/vsav-excess-units.md](../docs/vsav-excess-units.md)); they simply should
not be there.

`EXT_NAMES` is comma-separated, each the `.vmdx` file name without its suffix —
which is also what appears in the save's `EXT` commands, e.g.
`09-ClassicShips,21-PatiF-AmiF-HWs`.

A piece is attributed to an extension by its **GPID**: the 4th `;`-field of the
innermost BasicPiece state, looked up against the `gpid` attributes of every
PieceSlot in the module and each `<module>_ext/*.vmdx`. That is exact only while
GPIDs are unique across the module and its extensions, so check that first with
`renumber_gpids.py`.

### Stacks are left alone deliberately

Force-pool pieces are almost always inside stacks, and a stack's state lists its
members by piece id, so removing a piece leaves those ids dangling. That is safe:
`Stack.setState()` looks each one up and silently skips what it cannot resolve
(`if (child != null)`), so a stack comes up with fewer members, and one that loses
everything comes up empty. Run **Refresh Counters** afterwards and its
`StackRefresher` rebuilds the stacking from scratch, tidying both cases. (In one
real run, 284 of the 390 affected stacks were emptied outright and the refresh
cleared them all.)

### Dropping the dependency too

`--drop-listing` additionally removes the save's `EXT<TAB><name><TAB><version>`
registration for each named extension, so the scenario stops declaring a
dependency it no longer has.

This is opt-in, and deliberately tied to the extensions you are stripping: a
scenario's true dependencies **cannot** be derived from its counters alone. An
extension supplying only boards or charts — `01-EURO-Maps`, say — contributes no
piece definitions at all, so anything that pruned "extensions with no counters
present" would throw away exactly the entries a scenario needs to draw its maps.
For the same reason the application's own rule only ever *adds* extension entries.

```bash
tools/remove_ext_counters.py "data/…2_1_2.vmod" \
    "09-ClassicShips,21-PatiF-AmiF-HWs" data/scenarios/*.vsav --dry-run
```

## remove_placemark_carriers.py — clear stale embedded Place Markers

```
tools/remove_placemark_carriers.py SAVE.vsav [SAVE.vsav...] [--dry-run] [--no-backup]
```

The WiF module once had a Place Marker defined with an **embedded** marker
("Define Marker") instead of a reference: its `markerSpec` held an entire
serialised piece inline, with further prototypes expanded inside it. That trait
was removed from the module in 2.1.2, but pieces already in a saved game keep
whatever they were baked with — and each carrier's type is ~21 KB.

**Refresh Counters cannot clear them,** which is the whole reason this tool
exists. Every carrier has `map = null`, and `GameRefresher.getRefreshables()`
builds its work list by walking *map contents*, so an off-map piece is never
collected and never rebuilt. Tested directly: refreshing such a save reports every
counter refreshed with no warnings and leaves the command log the same byte
length. (`DeleteNoMap` does not help either — it only applies to pieces the
refresher collected, and VASSAL has it disabled in its own dialog over issue
12902.)

A piece is deleted only when **both** hold: its BasicPiece state has
`map == "null"`, and its type contains a `placemark` trait. A carrier that *is* on
a map is **reported and kept** — that one is not an orphan, and Refresh Counters
will rebuild it. (In practice the one such case, `US BB Alaska`, is also
unmatchable by GPID, so it needs the Excess Units tool instead.)

## remove_offmap_pieces.py — audit pieces that are on no map

```
tools/remove_offmap_pieces.py SAVE.vsav [SAVE.vsav...] [--apply] [--no-backup]
                              [--csv=OUT.csv]
                              [--keep-name=SUBSTR]... [--only-name=SUBSTR]...
                              [--module=MODULE.vmod]
```

Off-map pieces accumulate: a scenario built by swapping another's map layout can
be left holding counters that belonged to the old layout and now belong nowhere.
They are invisible in play, immune to Refresh Counters, and still cost memory and
bytes in every save.

**This reports by default and writes only with `--apply`** — deliberately, because
off-map does *not* by itself mean unwanted. In the WiF scenarios the largest group
is ownership markers (`US Owned`, `CW Owned`, `MajP Lending Strip`, ~529 per save,
identical across the fif and nonfif variants of the same scenario), which look
like a deliberate off-map pool rather than debris; deleting those could break
ownership marking. Yet `105` and `107` carry none at all, so the population is not
structural either. Decide per piece name, not per save:

```bash
# what is there, attributed to the archive that defines each piece
tools/remove_offmap_pieces.py data/scenarios/*.vsav --module="data/…2_1_2.vmod"

# everything except the ownership pool
tools/remove_offmap_pieces.py data/scenarios/103-*.vsav \
    --keep-name=owned --keep-name="lending strip" --apply
```

`--module` attributes each piece to the archive defining its GPID, which shows at
a glance whether a group comes from an extension the scenario no longer uses —
the signature of map-swap debris.

`--only-gpid=GPID[,GPID...]` restricts a run to exactly those Piece Ids. Use it
when the pieces to remove come from a report rather than from a name pattern —
`--only-name=oil` would also catch anything else off-map with "oil" in its name,
whereas the id list cannot over-reach. It still only ever deletes pieces that are
on no map, so the two conditions compound:

```bash
# the 13 TiF oil/resource markers left behind by a map swap
tools/remove_offmap_pieces.py data/scenarios/101-*.vsav \
    --only-gpid=2027,2028,2029,2030,2031,2032,2033,2034,2035,2036,2037,2038,7919 --apply
```

### The CSV manifest

`--csv=OUT.csv` writes **one row per piece that a run with `--apply` would
delete** — the same selection, so the file is an exact manifest of the pending
deletion rather than a separate report that might drift from it. It honours the
name filters, and can be combined with `--apply` to record what was removed.

| column | meaning |
|---|---|
| `scenario` | save file the piece is in |
| `piece_name` | counter name (innermost BasicPiece name) |
| `gpid` | Piece Id, i.e. which definition it came from |
| `defining_archive` | archive defining that GPID, or `(unmatchable)`; needs `--module` |
| `container` | `stack`, `deck` or `loose` |
| `x`, `y` | stored position — off-map pieces keep the coordinates they last had |
| `piece_id` | the save's own id for the piece, for tracing one row back |

```bash
tools/remove_offmap_pieces.py $(ls data/scenarios/*.vsav | grep -v -- -backup) \
    --module="data/…2_1_2.vmod" --csv=data/scenarios/offmap-pieces.csv
```

Open it in a spreadsheet and pivot on `piece_name` or `defining_archive` to decide
what is a deliberate off-map pool and what is debris. Feed the conclusion back as
`--keep-name` / `--only-name` filters, then re-run with `--apply`.

`dedupe_pieces.py` takes the same idea of an exact list: `--only-gpid` reduces
just the counters you name, where `--extension` reduces every duplicated counter
of an extension — including ones deliberately held in multiples, such as the WiF
CoiF Oilers and Tankers.

Note the `x`/`y` columns: an off-map piece retains its last coordinates, so a
cluster sharing a position is a good sign of a group that came off the same board
— which is what map-swap debris looks like.

Decks are never touched: their contents always carry a real map id (verified —
279 of 279 deck members in a sample scenario).

## dedupe_pieces.py — reduce duplicated counters to one copy

```
tools/dedupe_pieces.py MODULE.vmod SAVE.vsav [SAVE.vsav...]
                       (--list | --extension=NAME[,NAME...])
                       [--dry-run] [--no-backup]
```

For a scenario whose force pools are meant to hold exactly one of every counter
but picked up a second copy of some — typically an earlier edit adding a counter
that was already present under a different force-pool column.

A duplicate here means two or more `AddPiece` commands whose innermost BasicPiece
state carries the **same GPID**, i.e. two pieces built from the same palette slot.
The first in log order is kept and the rest dropped; the run prints the
coordinates of both so you can see which survived.

### `--extension` is required, and that is the point

Plenty of counters are legitimately present many times. In the WiF "everything"
scenarios `US Entry Option` appears **17** times (one per entry slot),
`MajP Lending Strip` and `Impulse Weather` 10 each, and `Naval Units In Port
Details` 6 (one per TF-and-port map). Deduplicating blindly would destroy them.

So the tool refuses to run without being told which extensions to consider. Use
`--list` first to see what is duplicated and which archive defines it, thenname the
extensions you actually mean:

```bash
tools/dedupe_pieces.py "data/…2_1_2.vmod" data/scenarios/003-*.vsav --list
tools/dedupe_pieces.py "data/…2_1_2.vmod" data/scenarios/003-*.vsav \
    --extension=09-ClassicShips
```

Stacks are left with dangling member ids, which `Stack.setState()` skips; a later
Refresh Counters rebuilds the stacking.

## Checking the result

```bash
unzip -t out.vsav                 # container intact
tools/swap_maps.py … ALL          # prints the layout it wrote
```

To confirm only what you intended changed, deobfuscate both files and compare
the token lists — a correct run differs in exactly the tokens you targeted:

```python
from swap_maps import read_vsav, split_commands
a, _ = read_vsav('before.vsav'); b, _ = read_vsav('after.vsav')
ta, tb = split_commands(a), split_commands(b)
assert len(ta) == len(tb)
print([i for i in range(len(ta)) if a[ta[i][0]:ta[i][2]] != b[tb[i][0]:tb[i][2]]])
```
