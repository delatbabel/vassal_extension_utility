# missing_counters.py — report counters a save does not contain

Some scenarios are meant to be complete: every counter of certain extensions
should be somewhere in them — on a map or in a force pool — so a player can
reach any unit the extension adds. Whether that actually holds is not something
VASSAL can answer, and a missing counter is silent: the unit simply cannot be
found in play.

This is a **read-only** report. It writes no save.

## Synopsis

```
tools/missing_counters.py --ext-dir DIR --extensions 10,11,12
                          [--exclude N:WORD[,WORD...]]...
                          [--csv OUT.csv] [--dup-csv OUT.csv]
                          SAVE.vsav [SAVE.vsav...]
```

| Option | Effect |
|---|---|
| `--ext-dir DIR` | The `<module>_ext` directory holding the extensions. Required. |
| `--extensions 10,11` | Leading numbers of the extension filenames to account for — `10` selects `10-SiF.vmdx`. Required. |
| `--exclude N:WORD,...` | Drop counters of extension `N` whose name contains any of the words (case-insensitive). Repeatable. |
| `--csv OUT.csv` | Write the report as CSV instead of stdout. |
| `--dup-csv OUT.csv` | Also write the counters a scenario holds **more than once**, from the same scan. |

## How it works

Open [diagrams/missing_counters.html](diagrams/missing_counters.html).

Every `PieceSlot` of the selected extensions contributes an expected `(gpid, name)`
pair. Every `AddPiece` command in each save contributes the GPID in the 4th
`;`-field of its innermost BasicPiece state — wherever the piece sits, whether a
map, a force pool, a deck or a stack — so presence is not restricted to any one
map.

Because the copies are already counted to decide presence, `--dup-csv` costs
nothing extra and cannot contradict the main report.

## Three verdicts, not two

| Verdict | Meaning |
|---|---|
| `missing` | No copy of that GPID anywhere in the save. |
| `off-map-only` | Every copy has `map == "null"`: on no map at all, unreachable in play, and invisible to Refresh Counters (see [remove_offmap_pieces.py](remove_offmap_pieces.md)). |
| present | At least one copy is on a map or in a pool. |

Two CSV columns qualify a `missing` verdict:

| Column | Meaning |
|---|---|
| `extension_listed_in_save` | Whether the save loads that extension at all (its `EXT` commands). `no` means every one of its counters is absent for that single reason — the fix is to add the extension to the scenario, not to place counters. |
| `name_found_elsewhere` | The unit is in the save under a *different* Piece Id — a renumbered slot, or a copy from another extension. A bookkeeping mismatch rather than an absent unit. |

## Examples

```bash
tools/missing_counters.py --ext-dir "data/…2_1_3_ext" \
    --extensions 10,11,12,13,14,15,16,19,20,21,25,26,27 \
    --csv data/scenarios/missing-counters-sdx.csv \
    $(ls data/scenarios/*.vsav | grep -iE "superdeluxe|sdx")
```

## Counters that are not meant to be placed

Not everything an extension defines belongs in a scenario. `15-TiF` carries road,
rail, resource and oil markers, and `21-PatiF-AmiF-HWs` the `T POLMkr` political
markers — all placed during play, none a unit anyone needs to find in a force
pool. Counted as missing they bury the real gaps:

```bash
tools/missing_counters.py --ext-dir "data/…2_1_3_ext" \
    --extensions 10,11,12,13,14,15,16,19,21,25,26,27 \
    --exclude 15:road,rail,res,oil --exclude 21:POLMkr \
    --csv data/scenarios/missing-counters-sdx.csv \
    $(ls data/scenarios/*.vsav | grep -iE "superdeluxe|sdx")
```

Each exclusion is reported as a count against its extension
(`15-TiF  242 counters  (49 excluded: road, rail, res, oil)`), so what was set
aside stays visible rather than silently shrinking the total.

Per-extension and per-scenario tallies go to stderr as the run proceeds, so a
whole extension that is absent stands out immediately; the CSV holds the detail.

## See also

- [remove_offmap_pieces.py](remove_offmap_pieces.md) — what to do about `off-map-only`
- [dedupe_pieces.py](dedupe_pieces.md) — what to do about the `--dup-csv` output
- [tools/README.md](../../tools/README.md#missing_counterspy--report-counters-a-save-does-not-contain)
