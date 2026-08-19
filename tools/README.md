# Command-line tools

Standalone Python 3 scripts for editing VASSAL files outside the GUI — useful for
building a series of pre-setup scenario files from one another, and for repairs
the application does not (yet) offer. They are **not** part of the Java
application and have no dependencies beyond the Python standard library.

`swap_maps.py`, `shift_pieces.py` and `fix_sif_subs.py` edit a **saved game**
(`.vsav`); `renumber_gpids.py` edits an **extension** (`.vmdx`).

The three saved-game scripts work the way `model/SavedGame` does (see
[docs/vsav-format.md](../docs/vsav-format.md) and
[docs/vsav-excess-units.md](../docs/vsav-excess-units.md)):

- the `savedGame` entry is deobfuscated whole (`!VCSK` + 2-hex key + XOR-hex);
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
tools/fix_sif_subs.py [--in-place] [--keep-bak] [--dry-run]
                      [--pair OLD=NEW]... EXTENSION.vmdx SAVE.vsav [SAVE.vsav...]
```

Written for the WiF `10-SiF.vmdx` extension, which holds two copies of some
submarine counters: the correct SiF ones named `<nation> S SUB <name>`, and
incorrect leftovers named `<nation> SUB <name>` (no `" S "`). Saved games built
before the fix contain the incorrect pieces; this rewrites each into its twin.

The pairs are **derived from the extension**, not hard-coded: every `PieceSlot`
whose name contains `" SUB "` is matched to the one named with `" S SUB "`, and
the pair is used only if the two definitions differ in exactly two traits — the
Embellishment (`emb2;Flip;…`, whose flip image gains the `sif` suffix) and the
innermost `piece;;;<image>;<name>`. Any other difference means the two are
different counters, not a duplicate, so the pair is **refused and reported**
rather than guessed at.

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
