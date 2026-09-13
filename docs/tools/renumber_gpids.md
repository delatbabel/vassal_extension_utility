# renumber_gpids.py — clear duplicate Piece Ids in an extension

VASSAL refuses to run Refresh Counters while any two components share a GPID.
`GameRefresher.execute()` builds a `GpIdChecker` over every `PieceSlot`, and if
`hasErrors()` it logs **"Unable to run Refresh, module was saved with older
vassal version"** and returns without refreshing anything.

That message is misleading: `GpIdChecker.testGpId()` flags a GPID that is empty,
non-numeric, or **already seen**, and never looks at the VASSAL version at all.
This tool finds the clashes and renumbers one side of them.

## Synopsis

```
tools/renumber_gpids.py EXT.vmdx [--start=N] [--dry-run] [--no-backup]
```

| Option | Effect |
|---|---|
| `--start=N` | Allocate replacement ids from `N` upward. Default: above the highest id in use. |
| `--dry-run` | Report what would change and write nothing. |
| `--no-backup` | Do not keep a copy in `<module>_ext/backups/`. |

Only the *target* extension's clashing slots are renumbered; every other archive
is left alone.

## How it works

View [diagrams/renumber_gpids.md](diagrams/renumber_gpids.md) on GitHub, or open [diagrams/renumber_gpids.html](diagrams/renumber_gpids.html) in a browser for the interactive version.

The target's `PieceSlot` GPIDs are indexed, then every sibling archive — the
module and the other `_ext/*` files — is indexed the same way. Any id in both
sets is a clash. Each clashing slot gets a fresh unused number written into
**two** places: the `gpid="..."` attribute, and the matching id embedded in that
slot's own piece definition text.

Only `buildFile.xml` is rewritten. Every other ZIP entry is copied byte-for-byte
**with its original modification time**, because VASSAL decides whether a cached
image tile is stale purely by comparing mtimes — restamping them forces a
needless re-tile of every board image (see
[docs/image-display-and-tiling.md](../image-display-and-tiling.md)).

## Why duplicates happen

Extensions generate fresh ids as `<extensionId>:<n>`, but a slot copied from the
module or from another extension keeps its plain numeric id, and `GpIdChecker`
keys on the raw value when extensions are loaded. Two extensions can then claim
the same number without either doing anything unusual.

## Which side to renumber

A GPID is how a saved game refers to a piece definition, so renumbering a slot
orphans any piece in any save that points at it. **Renumber the side of a clash
that no saved game references.** Check first:

```bash
tools/renumber_gpids.py "…_ext/23-DoD-III.vmdx" --dry-run     # what would change
```

then look for the old numbers in your saves: for each `AddPiece`, the GPID is the
4th `;`-field of the innermost `BasicPiece` state and the piece name the 5th field
of the innermost type, so you can see *which* of the two clashing components a
save actually holds. Pieces whose GPID no longer resolves can still be matched by
name (Refresh Counters' "Use counter names" option), but by GPID they are lost.

If the two copies are genuinely the same component, renumbering is the wrong fix
— it would leave two identical counters in the palette. Delete one instead, with
[drop_slots.py](drop_slots.md).

## Never leave a spare copy in the extensions folder

`ExtensionsManager`'s file filter is only `!isHidden() && !isDirectory()`: VASSAL
loads **every** file in `<module>_ext/` whose metadata parses as an extension,
whatever it is called. A `foo.vmdx.bak` or `Copy of foo.vmdx` left there is loaded
as a real extension — which after a renumbering re-creates every duplicate GPID it
just removed, and is easy to miss (the giveaway is the extension count going up by
one).

Backups therefore go in **`<module>_ext/backups/`**; directories are skipped by
that filter, and only `inactive` is also scanned. Use `--no-backup` if you keep
your own copies somewhere outside the extensions folder.

## See also

- [drop_slots.py](drop_slots.md) — for the clashes renumbering cannot fix
- [docs/refresh-counters.md](../refresh-counters.md) — the application makes the same check before a batch run
- [tools/README.md](../../tools/README.md#renumber_gpidspy--clear-duplicate-piece-ids-in-an-extension)
