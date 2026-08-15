# Saved-game command-line tools

Standalone Python 3 scripts for editing a VASSAL saved game (`.vsav`) outside the
GUI — useful for building a series of pre-setup scenario files from one another.
They are **not** part of the Java application and have no dependencies beyond the
Python standard library.

All three scripts work the way `model/SavedGame` does (see
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
tools/fix_sif_subs.py [--in-place] [--dry-run] EXTENSION.vmdx SAVE.vsav [SAVE.vsav...]
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

```bash
tools/fix_sif_subs.py --dry-run "…_ext/10-SiF.vmdx" data/scenarios/*.vsav
tools/fix_sif_subs.py --in-place "…_ext/10-SiF.vmdx" data/scenarios/*.vsav
```

`--in-place` moves each original to `<name>.vsav.bak` **before** writing, so the
backup is always the untouched file; it refuses to run if a `.bak` already
exists.

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
