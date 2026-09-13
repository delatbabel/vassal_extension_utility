# drop_slots.py — delete piece slots from an extension

For clearing a *duplicated* counter — the same component left behind in two
archives — which is the case [renumbering](renumber_gpids.md) cannot fix: giving
the two copies distinct Piece Ids would leave two identical counters in the
palette. One copy has to go.

When both copies share the same GPID **and** the same definition, deleting either
is safe for existing saved games: every piece pointing at that GPID still matches
the survivor. Check that the definitions really do match before choosing a side.

## Synopsis

```
tools/drop_slots.py EXT.vmdx GPID [GPID...] [--version=X.Y.Z]
                    [--dry-run] [--no-backup]
```

| Option | Effect |
|---|---|
| `--version=X.Y.Z` | Also bump the extension's version. |
| `--dry-run` | Report what would be deleted and write nothing. |
| `--no-backup` | Do not keep a copy in `<module>_ext/backups/`. |

Every GPID is resolved before anything is edited, so a bad id aborts the run
rather than leaving a half-edited archive.

## The empty-wrapper trap

An extension never holds a component directly: each sits inside a
`VASSAL.build.module.ExtensionElement` naming where in the module's tree it
grafts. Deleting the component and leaving the wrapper produces an
`ExtensionElement` with nothing in it — XML-valid, but it makes VASSAL **abort
the whole module launch** with a NullPointerException, because
`ExtensionElement.build()` leaves its `extension` field null and `addTo()` then
dereferences it (see
[docs/vassal-empty-extensionelement-crash.md](../vassal-empty-extensionelement-crash.md)).

So a wrapper left empty by a deletion is removed along with the slot; a wrapper
still holding other components is kept. The run says which of the two happened
for each slot. Verify afterwards that the extension has no empty wrappers.

## How it works

View [diagrams/drop_slots.md](diagrams/drop_slots.md) on GitHub, or open [diagrams/drop_slots.html](diagrams/drop_slots.html) in a browser for the interactive version.

`buildFile.xml` is read as text — not parsed into a DOM — and each GPID is
resolved to the byte span of its slot element and of its enclosing
`ExtensionElement`. The spans are cut, the XML is rewritten, and the archive is
rebuilt.

## What is preserved

Only `buildFile.xml` is rewritten, plus `extensiondata` when `--version` bumps
the version. Every other ZIP entry is copied byte-for-byte **with its original
modification time**, because VASSAL judges whether a cached image tile is stale
purely by comparing mtimes (see
[docs/image-display-and-tiling.md](../image-display-and-tiling.md)).

A version bump updates the version in **both** places VASSAL keeps it: the
`version` attribute on the `ModuleExtension` root, and `<version>` in the
separate `extensiondata` metadata entry.

## Example

```bash
tools/drop_slots.py "…_ext/19-PatiF-AmiF-ACFT.vmdx" \
    2634 2635 2654 2655 7149 --version=2.1.2
```

## See also

- [renumber_gpids.py](renumber_gpids.md) — for clashes between *different* components
- [tools/README.md](../../tools/README.md#drop_slotspy--delete-piece-slots-from-an-extension)
