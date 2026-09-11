# shift_pieces.py — translate the pieces on one map

After [swap_maps.py](swap_maps.md) moves a board to a different grid column, the
pieces that were standing on it are left at their old absolute coordinates. This
adds `(DX, DY)` to those coordinates, scoped to one map and one x-range, so the
pieces land back on the board they belong to.

At 51 lines it is the smallest tool here, and the clearest illustration of what
the whole suite does: it touches two fields of one command type and copies every
other byte verbatim.

## Synopsis

```
tools/shift_pieces.py TARGET OUT MAP X_LO X_HI DX DY
```

| Argument | Meaning |
|---|---|
| `TARGET` | Save to adjust. |
| `OUT` | Output path — may be the same as `TARGET`. |
| `MAP` | Map name whose pieces are considered, e.g. `"World Maps"`. |
| `X_LO` `X_HI` | Half-open x-range `[X_LO, X_HI)`: the span of the board that moved. |
| `DX` `DY` | Offsets added to each selected piece's x and y. |

All seven arguments are required; there are no options.

## How it works

Open [diagrams/shift_pieces.html](diagrams/shift_pieces.html).

Every `AddPiece` command is examined. If the innermost `BasicPiece` state's map
matches `MAP` and its `x` falls inside `[X_LO, X_HI)`, the `x` and `y` fields of
that state are rewritten. Nothing else in the command changes, and commands that
do not match are copied unchanged. Each moved piece's GPID and its old and new
position are printed.

The x-range is what scopes the edit to the pieces that were standing on the board
that moved — there is no other record in the save of which board a piece was on.

## Example

The board that was at column 2 (origin `x = 3507 + 6779 = 10286`, width 6779) is
now at column 0, so its pieces shift left by 10286. Row heights above it are
unchanged, so `DY` is 0:

```bash
tools/shift_pieces.py \
    data/113-presetup-barbarossa.vsav \
    data/113-presetup-barbarossa.vsav \
    "World Maps" 10286 17065 -10286 0
```

## Finding the numbers

Get the board widths and heights from the board images themselves: the `Board`
elements in the module's or extension's `buildFile.xml` name the image, and the
image dimensions **are** the board dimensions. The origin of a board at column
`c`, row `r` is the sum of the widths of the boards to its left in that row, and
the sum of the heights of the boards above it in that column.

## See also

- [swap_maps.py](swap_maps.md) — what usually creates the need for this
- [tools/README.md](../../tools/README.md#shift_piecespy--translate-the-pieces-on-one-map)
