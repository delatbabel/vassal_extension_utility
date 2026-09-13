# remove_offmap_pieces.py — report and delete pieces that are on no map

A piece's innermost BasicPiece state begins with the map it is on
(`<map>;<x>;<y>;<gpid>;…`), or the literal `null` when it is on none. Off-map
pieces accumulate: a scenario built by swapping the map layout of another can be
left holding counters that belonged to the old layout and now belong nowhere.
They are invisible in play while still costing memory and bytes in every save.

They are also **immune to Refresh Counters**: `GameRefresher.getRefreshables()`
builds its work list by walking map contents, so an off-map piece is never
collected, never rebuilt, and never reported as a warning.

## Synopsis

```
tools/remove_offmap_pieces.py SAVE.vsav [SAVE.vsav...]
                              [--apply] [--no-backup] [--csv=OUT.csv]
                              [--keep-name=SUBSTR]... [--only-name=SUBSTR]...
                              [--only-gpid=GPID[,GPID...]]
                              [--module=MODULE.vmod]
```

| Option | Effect |
|---|---|
| `--apply` | Actually delete. **Without it the tool only reports.** |
| `--no-backup` | Do not keep a backup of each edited save. |
| `--csv=OUT.csv` | Write one row per piece the run would delete. |
| `--keep-name=SUBSTR` | Exclude any piece whose name contains `SUBSTR`. Repeatable, case-insensitive. |
| `--only-name=SUBSTR` | Restrict the selection to names containing `SUBSTR`. Repeatable, case-insensitive. |
| `--only-gpid=GPID,...` | Restrict to exactly those Piece Ids — exact, unlike the substring filters. |
| `--module=MODULE.vmod` | Attribute each piece to the archive defining its GPID. |

## Read the report before deleting anything

Off-map does **not** by itself mean unwanted. In the WiF scenarios the largest
group by far is ownership markers — `US Owned`, `CW Owned`, `MajP Lending Strip`
and friends, ~529 per save, identical across the fif and nonfif variants of the
same scenario — which look like a deliberate off-map pool that pieces are drawn
from, not debris. Deleting those could break ownership marking. Yet scenarios
`105` and `107` carry none at all, so the population is clearly not structural
either.

That ambiguity is why this reports by default and writes only with `--apply`, and
why `--keep-name` exists: **decide per piece name, not per save.**

## How it works

View [diagrams/remove_offmap_pieces.md](diagrams/remove_offmap_pieces.md) on GitHub, or open [diagrams/remove_offmap_pieces.html](diagrams/remove_offmap_pieces.html) in a browser for the interactive version.

Every `AddPiece` command whose innermost state has `map == "null"` is a
candidate. The name and GPID filters narrow the candidate set; `--module` adds
the defining archive to the report. With `--apply`, the surviving selection's
tokens and their preceding delimiters are dropped and the save is rewritten.

Decks are never affected — their contents always carry a real map id (verified:
279 of 279 deck members in a sample scenario).

## Examples

See what is there, attributed to the archive that defines each piece:

```bash
tools/remove_offmap_pieces.py data/scenarios/*.vsav --module="data/…2_1_2.vmod"
```

Delete everything except the ownership pool:

```bash
tools/remove_offmap_pieces.py data/scenarios/103-*.vsav \
    --keep-name=owned --keep-name="lending strip" --apply
```

Delete exactly the 13 TiF oil/resource markers left behind by a map swap.
`--only-name=oil` would also catch anything else off-map with "oil" in its name;
an id list cannot over-reach:

```bash
tools/remove_offmap_pieces.py data/scenarios/101-*.vsav \
    --only-gpid=2027,2028,2029,2030,2031,2032,2033,2034,2035,2036,2037,2038,7919 --apply
```

## The CSV manifest

`--csv=OUT.csv` writes **one row per piece that a run with `--apply` would
delete** — the same selection, so the file is an exact manifest of the pending
deletion rather than a separate report that might drift from it. It honours the
name filters and can be combined with `--apply` to record what was removed.

| Column | Meaning |
|---|---|
| `scenario` | Save file the piece is in |
| `piece_name` | Counter name (innermost BasicPiece name) |
| `gpid` | Piece Id, i.e. which definition it came from |
| `defining_archive` | Archive defining that GPID, or `(unmatchable)`; needs `--module` |
| `container` | `stack`, `deck` or `loose` |
| `x`, `y` | Stored position — off-map pieces keep the coordinates they last had |
| `piece_id` | The save's own id for the piece, for tracing one row back |

```bash
tools/remove_offmap_pieces.py $(ls data/scenarios/*.vsav | grep -v -- -backup) \
    --module="data/…2_1_2.vmod" --csv=data/scenarios/offmap-pieces.csv
```

Open it in a spreadsheet and pivot on `piece_name` or `defining_archive` to
decide what is a deliberate off-map pool and what is debris. `defining_archive`
showing an extension the scenario no longer uses is the signature of map-swap
debris; a cluster of pieces sharing an `x`/`y` is a good sign of a group that
came off the same board. Feed the conclusion back as `--keep-name` /
`--only-name` filters, then re-run with `--apply`.

## See also

- [remove_placemark_carriers.py](remove_placemark_carriers.md) — the specific off-map case worth the most bytes
- [missing_counters.py](missing_counters.md) — reports `off-map-only` counters from the other direction
- [tools/README.md](../../tools/README.md#remove_offmap_piecespy--audit-pieces-that-are-on-no-map)
