# migrate_15_to_21.py — migrate a 1.5.93 scenario to the 2.1.3 deluxe module

The 1.5.93 module ("WiF CE Maps and Units Combo") kept every map and counter in
the main module; 2.1.3 ("WiF CE Official Combo") splits them across extensions
and reworked several charts. This rewrites a 1.5.93 saved game so it loads under
2.1.3 with the deluxe extension set — `10-SiF` through `16-PiF` and
`18-Production-FiF`, plus the map extensions `01`/`02`/`03`.

It is the most specific tool here: a one-off migration encoded as a script, kept
because the rules it embeds were expensive to establish.

## Synopsis

```
tools/migrate_15_to_21.py OLD.vsav DONOR.vsav MODULE.vmod OUT.vsav
                          [--jobs=OUT.job] [--csv=OUT.csv] [--dry-run]
```

| Argument / option | Meaning |
|---|---|
| `OLD.vsav` | The 1.5.93 scenario. Never touched. |
| `DONOR.vsav` | An empty 2.1.3 scenario supplying every board layout and the `moduledata` entry. |
| `MODULE.vmod` | The 2.1.3 module, read for the deluxe set's piece definitions. |
| `OUT.vsav` | Output. Must not already exist — the run refuses to overwrite. |
| `--jobs=OUT.job` | Hex-control job file. Defaults to `OUT.hexctl.job`. |
| `--csv=OUT.csv` | Manifest row for every removed, renamed or relocated piece. |
| `--dry-run` | Report and write nothing. |

## Why matching works at all

GPIDs were preserved between the two modules — 5225 of 6955 pieces in the
reference scenario match by GPID **and** name — which is what makes the keep test
reliable: a piece is kept when its GPID *or* its name exists in the deluxe set,
and a later Refresh Counters rebuilds every kept piece from the 2.1.3
definitions.

## What it does, in one pass

View [diagrams/migrate_15_to_21.md](diagrams/migrate_15_to_21.md) on GitHub, or open [diagrams/migrate_15_to_21.html](diagrams/migrate_15_to_21.html) in a browser for the interactive version.

- Board layouts and `EXT` registrations are replaced wholesale with the donor's.
- Maps are renamed on every piece, stack and deck — `Allied TFs` → `CW TFs & Ports`,
  `Axis TFs` → `Japan TFs & Ports`, `Game MGT` → `Impulse and Weather` —
  coordinates kept, because the World Maps insert boards reassemble the old
  single-board columns exactly (verified: identical column widths and heights).
- Counters defined only in the non-deluxe extensions (09, 19–27) are removed, as
  is everything on the discarded `Resourcesetc` / `Trade Agreements` charts and
  on maps 2.1.3 no longer has.
- Renamed counters are repointed at their new slots (16 ` Mod` convoy/oiler
  pairs, 8 `…BPs` → `…BPsDisplay` pairs) by innermost name **and** GPID.
- The old per-nation `X Control` markers became one layered `Hex Control Marker`.
  Byte surgery cannot expand its prototypes, so they are removed here and written
  to an `AddCountersRunner` job whose `layer:majorhexcontroller=<n>` fields have
  the engine place each marker with the right nation showing.
- Pieces on the redrawn `FF Display` (and the one counter from the vanished
  `Minors FiF Chart`, sent to `CW FiF Chart`) are laid out in a grid at the
  top-left of their map, since their old coordinates mean nothing on the new art.
- **No old deck survives.** A deck's identity — internal key, position, piece id
  — belongs to the module that defined it, and the old force-pool boxes sit where
  the *old* chart art had them. The donor's own deck commands are copied in
  verbatim instead, so the boxes align with the 2.1.3 art and `Return to Deck`
  traits find the keys they name.

The donor's World Maps layout has `ASIA Main Insert` doubled in at column 4 row
1; the layout this writes uses `PACIFIC Main Insert` there.

## Example

```bash
tools/migrate_15_to_21.py \
    data/old/094-fascist-tide.vsav \
    data/025-barbarossa-empty.vsav \
    "data/WiF CE Official Combo ver 2_1_3.vmod" \
    data/scenarios/094-fascist-tide-21.vsav \
    --csv /tmp/094-migration.csv
```

## Afterwards

1. Run the `.hexctl.job` through `AddCountersRunner`.
2. Run **Refresh Counters** against the 2.1.3 module with options **pieces +
   counter names + layer names + rotate names + labeler names**.

The name-matching options are load-bearing: the refresher copies a trait's state
only when the old and new trait type strings match exactly, and 2.1.3 changed the
keystrokes inside nearly every Layer. Without `UseLayerName` no Flip state
transfers — and since the 2.1.3 palette pieces are saved face-*down*, every
migrated counter comes out face down.

## See also

- [docs/refresh-counters.md](../refresh-counters.md) — the refresh run this depends on
- [tools/README.md](../../tools/README.md#migrate_15_to_21py--migrate-a-1593-scenario-to-the-213-deluxe-module)
