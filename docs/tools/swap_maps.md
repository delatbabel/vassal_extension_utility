# swap_maps.py — copy a map layout from one save into another

A map's entire layout — which boards, in what grid, which way up — lives in a
single command in the saved game: `<mapName>BoardPicker\t<board>[/rev]\t<col>\t<row>\t…`
(`BoardPicker.encode()` in the engine). Replacing that one token replaces the
whole table. This script lifts it out of a donor save and splices it into a
target, copying every other command byte-for-byte.

It is also the suite's I/O library: `read_vsav`, `split_commands`, `write_vsav`
and `obfuscate` live here and are imported by ten of the other thirteen tools.

## Synopsis

```
tools/swap_maps.py TARGET DONOR OUT MAP [MAP...]
tools/swap_maps.py TARGET DONOR OUT ALL
```

| Argument | Meaning |
|---|---|
| `TARGET` | The save whose layout is being replaced. Read only. |
| `DONOR` | The save supplying the layout. Read only. |
| `OUT` | Output path. |
| `MAP...` | Map names to swap, e.g. `"World Maps"`. `ALL` does every map the two saves have in common. |

There are no options. It writes unconditionally to `OUT`, so give it a new path.

## How it works

View [diagrams/swap_maps.md](diagrams/swap_maps.md) on GitHub, or open [diagrams/swap_maps.html](diagrams/swap_maps.html) in a browser for the interactive version.

Both saves are deobfuscated and split into ESC-delimited command tokens. The
`BoardPicker` tokens are matched by their first tab-token ending in
`BoardPicker`, the donor's bytes are spliced over the target's, and the stream is
re-obfuscated with a fresh key. The before/after layout of each replaced map is
printed.

The splice is refused — rather than risking a corrupt log — when the token to be
replaced is **nested** (its ESC delimiter was escaped) or when the replacement
itself contains an ESC.

## Examples

Give a full-world pre-setup the three-board Eastern-Front layout of an empty
Barbarossa save:

```bash
tools/swap_maps.py \
    data/112-presetup-ce-maps-deluxe-nonfif.vsav \
    data/025-barbarossa-empty.vsav \
    data/113-presetup-barbarossa.vsav ALL
```

Swap one map only, leaving the other maps' layouts alone:

```bash
tools/swap_maps.py before.vsav donor.vsav after.vsav "World Maps"
```

## Pieces do not move with their board

Piece coordinates are absolute in map space. `Map.setBoardBoundaries()` places
the board at grid `(column, row)` at `dx = Σ boardWidths[0..column-1][row]`,
`dy = Σ boardHeights[column][0..row-1]` — so when a board changes column its
pixel origin changes, and any piece standing on it is left behind at the old
coordinates, possibly outside the new map bounds entirely.

Nothing is lost (the `AddPiece` commands are untouched), but such pieces become
unreachable by dragging. Bring them across with
[shift_pieces.py](shift_pieces.md).

## See also

- [shift_pieces.py](shift_pieces.md) — the companion that moves the pieces
- [docs/vsav-format.md](../vsav-format.md) — the container and its obfuscation formats
- [tools/README.md](../../tools/README.md#swap_mapspy--copy-a-map-layout-from-one-save-into-another)
