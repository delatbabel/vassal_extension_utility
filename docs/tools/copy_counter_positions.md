# copy_counter_positions.py — copy counter positions from a reference save

When a family of scenarios is built from the same force pools, counters added to
all of them land in one arbitrary spot and then have to be distributed. Doing
that by hand once, in a reference scenario, and copying the result to the rest is
far less work than repeating it — and guarantees the family stays consistent.

## Synopsis

```
tools/copy_counter_positions.py --reference REF.vsav --module MODULE.vmod
                                --anchor "Map Name;X;Y" --jobs DIR
                                [--gpid-file FILE] [--dry-run] [--no-backup]
                                SAVE.vsav [SAVE.vsav...]
```

| Option | Effect |
|---|---|
| `--reference REF.vsav` | The save whose positions are correct. Read only. Required. |
| `--module MODULE.vmod` | Module the job files will be run against. Required. |
| `--anchor "Map;X;Y"` | The arbitrary spot the counters currently sit at. Only counters **at the anchor** are moved. Required. |
| `--jobs DIR` | Directory to write one `.job` file per save into. Created if absent. Required. |
| `--gpid-file FILE` | Restrict to the Piece Ids listed in the file (one per line, `#` comments allowed). |
| `--dry-run` | Report and write nothing. |
| `--no-backup` | Do not keep a backup of each edited save. |

## Two steps, because stacking belongs to the engine

A counter's position is three fields of its BasicPiece state (`<map>;<x>;<y>`),
which this could rewrite directly. Its **stacking** is not: a stack is a separate
piece whose state lists its members by id, so re-homing a counter by hand means
editing two stacks as well, getting the `@@<layer>` suffix right, and hoping the
destination stack exists.

So each counter is **removed** here and **re-placed by VASSAL** at the reference
position, where `Map.placeOrMerge` merges it into whatever stack is there —
exactly what dragging it would do. Removal needs no stack surgery either: the id
left behind in the old stack is dangling, and `Stack.setState()` looks each
member up and silently skips what it cannot resolve.

## How it works

Open [diagrams/copy_counter_positions.html](diagrams/copy_counter_positions.html).

The reference save is scanned for counters with exactly **one** copy, giving a
`gpid → (name, map, x, y)` table. Each target save is then scanned for pieces at
the anchor whose GPID is in that table; those `AddPiece` commands are dropped and
the pruned save is written. Alongside it, one job file per save records an `add=`
line per counter, naming the GPID and the reference position.

`refresh/AddCountersRunner` then completes the move:

```bash
for job in /tmp/movejobs/*.job; do
  java -cp "$VENGINE:$UTILJAR" \
       org.vassalengine.extutil.refresh.AddCountersRunner "$job"
done
```

## Example

```bash
tools/copy_counter_positions.py --reference data/scenarios/101-….vsav \
    --module "data/…2_1_3.vmod" --anchor "World Maps;207;60" \
    --gpid-file /tmp/gpids.txt --jobs /tmp/movejobs \
    data/scenarios/1*.vsav data/scenarios/2*.vsav
```

## What gets moved

Only counters **at the anchor** with **exactly one copy in the reference**. The
anchor is what makes this safe against a scenario holding other copies of the
same counter: one already in its right place is never touched.

`--gpid-file` narrows it to a named set of Piece Ids and is usually what you
want. Without it, *any* counter at the anchor that the reference also holds is
moved to the reference's position for it, including ones that were never part of
the exercise. A counter the reference does not hold stays where it is — which is
how a target may legitimately keep extras the reference never had.

## See also

- [docs/refresh-counters.md](../refresh-counters.md#adding-counters--addcountersrunner) — the runner that consumes the job files
- [tools/README.md](../../tools/README.md#copy_counter_positionspy--copy-counter-positions-from-a-reference-save)
