# global_properties.py — the values no counter accounts for

A module's Global Properties are **game state**.
`GlobalProperty.getRestoreCommand()` writes one
`GlobalProperty\t;<name>;<value>;<container>` command per property into the saved
game, and loading the save restores that value.

Nothing ties a property to the counter that set it. Delete that counter, move it
to a map the module no longer has, or let a migration rebuild it, and the value
stays exactly where the last change left it. No other tool here can see the
problem: every piece is fine, so [remove_offmap_pieces.py](remove_offmap_pieces.md)
and [missing_counters.py](missing_counters.md) report nothing, and Refresh
Counters rebuilds pieces, never properties.

## Synopsis

```
tools/global_properties.py SAVE.vsav [SAVE.vsav...]
                           [--grep=SUBSTR]... [--changed] [--module=MODULE.vmod]
                           [--set=NAME=VALUE]... [--reset=SUBSTR]...
                           [--apply] [--no-backup] [--csv=OUT.csv]
```

| Option | Effect |
|---|---|
| `--grep=SUBSTR` | Restrict the report to properties whose name contains `SUBSTR`. Repeatable, case-insensitive. |
| `--module=MODULE.vmod` | Read each property's `initialValue` from the module and its active extensions, and show the default beside the saved value. |
| `--changed` | With `--module`, list only the properties that differ from their default. |
| `--set=NAME=VALUE` | Set one property by **exact** name. Repeatable. |
| `--reset=SUBSTR` | Set every property whose name contains `SUBSTR` back to the module default. Repeatable. |
| `--apply` | Actually write. **Without it the tool only reports.** |
| `--no-backup` | Do not keep a `-backup.vsav`. |
| `--csv=OUT.csv` | Write the report as CSV. |

## The symptom

A chart or overlay keeps displaying a number no counter on the table accounts
for. The WiF `German BP Overlay` reads its BUILD POINTS / TRADE figure from a
Calculated Property over `Germantradebps`, which the `MajP Lending Strip`
counters set through `Set Global Property` traits. Zero every strip and the
figure stays where the last loan left it, because the loan lives in the property,
not in the strip.

## Finding it

Open [diagrams/global_properties.html](diagrams/global_properties.html).

`--module` plus `--changed` shows the whole of a save's altered global state —
usually a short list:

```bash
tools/global_properties.py data/scenarios/094-*.vsav \
    --module="data/WiF CE Official Combo ver 2_1_3.vmod" --grep=trade
```

```
Germantradebps       '-24'   (default '0')
Italytradebps        '24'    (default '0')
```

The pairing is the tell: a loan is recorded twice, once negative on the lender
and once positive on the borrower, and **both must be cleared together** —
zeroing only the lender's side leaves the borrower spending BPs it is no longer
owed.

## Clearing it

```bash
tools/global_properties.py data/scenarios/094-*.vsav \
    --module="data/WiF CE Official Combo ver 2_1_3.vmod" \
    --reset=germantrade --reset=italytrade --apply
```

Only the changed `GlobalProperty` commands are re-encoded — with
`SequenceEncoder`'s own escaping, so an untouched value re-encodes to exactly the
bytes it was read as — and every other command in the log is copied verbatim.

## See also

- [tools/README.md](../../tools/README.md#global_propertiespy--the-values-no-counter-accounts-for)
