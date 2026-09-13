# remove_ext_counters.py — delete every counter from given extensions

For a scenario that has picked up counters from an extension it was never meant
to be played with: the extension was active when the scenario was built, so its
pieces went into the force pools, but the scenario's own extension list never
included it.

**Refresh Counters cannot fix this.** The pieces match their definitions
perfectly, so they are not "excess" in the Excess-Units sense; they simply should
not be there.

## Synopsis

```
tools/remove_ext_counters.py MODULE.vmod EXT_NAMES SAVE.vsav [SAVE.vsav...]
                             [--drop-listing] [--dry-run] [--no-backup]
```

| Argument / option | Meaning |
|---|---|
| `MODULE.vmod` | Module whose `_ext/*.vmdx` siblings supply the GPID attribution. |
| `EXT_NAMES` | Comma-separated extension names, e.g. `09-ClassicShips,21-PatiF-AmiF-HWs`. An unknown name aborts the run and prints the known set. |
| `--drop-listing` | Also remove the `EXT` command that names the extension in the save. |
| `--dry-run` | Report and write nothing. |
| `--no-backup` | Do not keep a backup of each edited save. |

## How it works

View [diagrams/remove_ext_counters.md](diagrams/remove_ext_counters.md) on GitHub, or open [diagrams/remove_ext_counters.html](diagrams/remove_ext_counters.html) in a browser for the interactive version.

Every `PieceSlot` in the module and in each `<module>_ext/*.vmdx` contributes a
`gpid → archive` entry. A piece is attributed to an extension by the GPID in the
4th `;`-field of its innermost BasicPiece state. Since GPIDs are unique across
the module and its extensions — check that first with
[renumber_gpids.py](renumber_gpids.md) — that attribution is exact.

Matching `AddPiece` commands and their preceding delimiters are dropped; every
other token is copied verbatim.

## Stacks are left alone deliberately

Force-pool pieces are almost always inside stacks, and a stack's state lists its
members by piece id. Removing a piece leaves those ids dangling, which is safe:
`Stack.setState()` looks each one up and silently skips what it cannot resolve,
so a stack simply comes up with fewer members. A later Refresh Counters rebuilds
the stacking.

## Dropping the dependency too

`--drop-listing` also removes the `EXT` command registering the extension, so the
scenario stops declaring a dependency it no longer has.

Use it deliberately. A scenario's true dependencies **cannot** be derived from
its counters alone: an extension supplying only boards or charts — `01-EURO-Maps`,
say — contributes no piece definitions at all, so anything that pruned
"extensions with no counters present" would throw away exactly the entries a
scenario needs to draw its maps. For the same reason the Java application's own
rule only ever *adds* extension entries.

## Example

```bash
tools/remove_ext_counters.py "data/…2_1_2.vmod" \
    "09-ClassicShips,21-PatiF-AmiF-HWs" data/scenarios/*.vsav --dry-run
```

Drop `--dry-run` to apply.

## See also

- [remove_offmap_pieces.py](remove_offmap_pieces.md) — for debris identified by position rather than by owner
- [docs/refresh-counters.md](../refresh-counters.md) — how the application maintains a save's extension list
- [tools/README.md](../../tools/README.md#remove_ext_counterspy--delete-every-counter-from-given-extensions)
