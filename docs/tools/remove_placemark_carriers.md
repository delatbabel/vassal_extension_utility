# remove_placemark_carriers.py — clear stale embedded Place Markers

The WiF module once had a Place Marker trait defined with an **embedded** marker
("Define Marker") rather than a reference: its `markerSpec` field held an entire
serialised piece inline, with further prototypes expanded inside it. That trait
was removed from the module in 2.1.2 — no `placemark` remains anywhere — but
pieces already stored in a saved game keep whatever traits they were baked with,
and each carrier's type is about **21 KB**.

## Synopsis

```
tools/remove_placemark_carriers.py SAVE.vsav [SAVE.vsav...] [--dry-run] [--no-backup]
```

| Option | Effect |
|---|---|
| `--dry-run` | Report and write nothing. |
| `--no-backup` | Do not keep a backup of each edited save. |

## Why Refresh Counters cannot clear them

Every carrier has `map = null`: it is on no map, sitting in an off-map stack.
`GameRefresher.getRefreshables()` builds its work list by walking *map contents*,
so an off-map piece is never collected and never rebuilt. `DeleteNoMap` does not
help either — it only applies to pieces the refresher collected, and VASSAL has it
disabled in its own dialog over issue 12902.

Refreshing such a save reports every counter refreshed, with no warnings, and
leaves the carriers byte-for-byte intact. They are orphans: invisible in play,
immune to refresh, and carried in memory and in every save indefinitely.

## What it selects

Open [diagrams/remove_placemark_carriers.html](diagrams/remove_placemark_carriers.html).

An `AddPiece` command is deleted when **both** hold:

1. its innermost BasicPiece state has `map == "null"` — the piece is on no map; and
2. its type contains a `placemark` trait.

A carrier that **is** on a map is reported and kept. That one is not an orphan,
and the right treatment is Refresh Counters, which will rebuild it from the
current definition and drop the trait.

## Stacks are left alone deliberately

Carriers sit inside stacks, whose state lists members by piece id, so removing a
piece leaves those ids dangling. That is safe: `Stack.setState()` looks each one
up and silently skips what it cannot resolve, so a stack comes up with fewer
members and one that loses everything comes up empty.

## Example

```bash
tools/remove_placemark_carriers.py data/scenarios/*.vsav --dry-run
tools/remove_placemark_carriers.py data/scenarios/*.vsav
```

The run prints the bytes freed per save, which is the point of running it: the
saving is measured in megabytes, not counters.

## See also

- [remove_offmap_pieces.py](remove_offmap_pieces.md) — the general case of the same off-map problem
- [docs/wif-save-bloat-analysis.md](../wif-save-bloat-analysis.md) — where the 21 KB figure comes from
- [tools/README.md](../../tools/README.md#remove_placemark_carrierspy--clear-stale-embedded-place-markers)
