# dedupe_pieces.py — reduce duplicated counters to one copy

For a scenario whose force pools are meant to hold exactly one of every counter
but accumulated a second copy of some — typically because an earlier edit added a
counter that was already present under a different force-pool column.

A "duplicate" here means two or more `AddPiece` commands whose innermost
BasicPiece state carries the **same GPID**, i.e. two pieces built from the same
palette slot. The first in log order is kept and the rest dropped; the run prints
the coordinates of both, so you can see which survived.

## Synopsis

```
tools/dedupe_pieces.py MODULE.vmod SAVE.vsav [SAVE.vsav...]
                       (--list | --extension=NAME[,NAME...] | --only-gpid=GPID[,GPID...])
                       [--dry-run] [--no-backup]
```

| Option | Effect |
|---|---|
| `--list` | Report what is duplicated and which archive defines it. Writes nothing. |
| `--extension=NAME,...` | Consider only counters defined by the named extensions. |
| `--only-gpid=GPID,...` | Consider only the named Piece Ids. Can be combined with `--extension`. |
| `--dry-run` | Report and write nothing. |
| `--no-backup` | Do not keep a backup of each edited save. |

One of `--list`, `--extension` or `--only-gpid` is **required**.

## Always restrict it — that is the point

Plenty of counters are legitimately present many times. In the WiF "everything"
scenarios `US Entry Option` appears **17** times (one per entry slot),
`MajP Lending Strip` and `Impulse Weather` 10 each, and `Naval Units In Port
Details` 6 (one per TF-and-port map). Deduplicating blindly would destroy them.

So the tool refuses to run without being told what to consider:

- `--only-gpid` names the counters **exactly**, which is what a hand-checked list
  of duplicates calls for;
- `--extension` reduces *every* duplicated counter of that extension — including
  ones deliberately held in multiples, such as the WiF CoiF Oilers and Tankers.

Use `--list` first.

## How it works

Open [diagrams/dedupe_pieces.html](diagrams/dedupe_pieces.html).

Every `PieceSlot` in the module and its `_ext/*.vmdx` siblings gives a
`gpid → archive` entry. The save's `AddPiece` commands are tallied by GPID; any
GPID seen more than once, and passing the selection, has all but its first
occurrence dropped.

## Examples

```bash
tools/dedupe_pieces.py "data/…2_1_2.vmod" data/scenarios/003-*.vsav --list
tools/dedupe_pieces.py "data/…2_1_2.vmod" data/scenarios/003-*.vsav \
    --extension=09-ClassicShips
```

## Stacks are left alone deliberately

Stacks keep dangling member ids for the dropped copies, which `Stack.setState()`
skips; a later Refresh Counters rebuilds the stacking.

## See also

- [missing_counters.py](missing_counters.md) — its `--dup-csv` produces the list to feed `--only-gpid`
- [tools/README.md](../../tools/README.md#dedupe_piecespy--reduce-duplicated-counters-to-one-copy)
