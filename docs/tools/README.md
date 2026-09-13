# Command-line tools

`tools/` holds fourteen standalone Python scripts that edit VASSAL saved games
(`.vsav`) and extension archives (`.vmdx`) from the shell. They are **separate
from the Java application** — no build step, no dependencies beyond the standard
library, and nothing in `src/` imports them. They exist because a batch of forty
scenarios cannot be fixed through a GUI one file at a time.

These pages are the reference guide: one page per tool with a synopsis, an option
table, worked examples and a data-flow diagram. [`tools/README.md`](../../tools/README.md)
is the companion narrative — longer prose on *why* each tool exists and what the
engine does that makes it necessary.

| Tool | Edits | One line |
|---|---|---|
| [swap_maps.py](swap_maps.md) | `.vsav` | Copy a map's board layout from one save into another |
| [shift_pieces.py](shift_pieces.md) | `.vsav` | Translate the pieces on one map by (dx, dy) |
| [fix_sif_subs.py](fix_sif_subs.md) | `.vsav` | Swap mis-named counters for their correct twins |
| [missing_counters.py](missing_counters.md) | — (report) | Report counters an extension defines that a save does not hold |
| [copy_counter_positions.py](copy_counter_positions.md) | `.vsav` + jobs | Give counters the positions they hold in a reference save |
| [migrate_15_to_21.py](migrate_15_to_21.md) | `.vsav` | Migrate a WiF 1.5.93 scenario to the 2.1.3 deluxe module |
| [renumber_gpids.py](renumber_gpids.md) | `.vmdx` | Give an extension's duplicated Piece Ids fresh numbers |
| [drop_slots.py](drop_slots.md) | `.vmdx` | Delete piece slots from an extension by GPID |
| [remove_ext_counters.py](remove_ext_counters.md) | `.vsav` | Remove every counter belonging to named extensions |
| [remove_placemark_carriers.py](remove_placemark_carriers.md) | `.vsav` | Delete off-map pieces carrying a stale embedded Place Marker |
| [remove_offmap_pieces.py](remove_offmap_pieces.md) | `.vsav` | Report — and optionally delete — every piece on no map |
| [dedupe_pieces.py](dedupe_pieces.md) | `.vsav` | Reduce duplicated counters to one copy each |
| [global_properties.py](global_properties.md) | `.vsav` | Report and rewrite the Global Property values a save carries |
| [upload_scenarios.py](upload_scenarios.md) | — (network) | Publish a directory of scenarios to the VASSAL game library |

## Running them

Python 3, standard library only — no third-party packages and no build step.
Most scripts are executable; all of them work when invoked through the
interpreter. Only `fix_sif_subs.py` and `upload_scenarios.py` take `--help`; the
rest print their usage line when run with the wrong arguments:

```bash
python3 tools/swap_maps.py
usage: swap_maps.py TARGET DONOR OUT MAP [MAP...] | ALL
```

Ten of the fourteen `import` helpers from `tools/swap_maps.py` and
`tools/remove_placemark_carriers.py`, so **run them from the repository root** (or
with `tools/` on `PYTHONPATH`) — otherwise the import fails.

## The shared `.vsav` pipeline

`swap_maps.py` is not only a tool; it is the I/O library the rest of the suite is
built on. Everything that touches a saved game goes through the same four steps,
which is why the data-flow diagrams all have the same shape.

| Function | Lives in | Does |
|---|---|---|
| `read_vsav(path)` | `swap_maps.py` | Opens the ZIP, deobfuscates the `savedGame` entry, returns `(plaintext, entries, format)` |
| `split_commands(state)` | `swap_maps.py` | Splits the command log at **every** ESC (`0x1B`), returning each token's delimiter and content byte ranges |
| `board_picker_tokens()` | `swap_maps.py` | Finds the `<mapName>BoardPicker` commands |
| `write_vsav(path, ...)` | `swap_maps.py` | Re-obfuscates with a fresh key and writes via a temp file + `os.replace` |
| `add_piece_fields()`, `basic_piece()`, `piece_map()`, `piece_name()` | `remove_placemark_carriers.py` | Pull `+/<id>/<type>/<state>` apart and reach the innermost BasicPiece |
| `backup_path(path)` | `remove_placemark_carriers.py` | Names the `-backup` / `.bak` copy |

Three properties fall out of that design and hold for every tool here:

- **Tokens are copied verbatim, never re-encoded.** A tool edits the bytes of the
  tokens it targets and copies every other token through untouched, so a run can
  only differ from its input in the places it meant to change. The
  [check recipe](#checking-the-result) below turns that into an assertion.
- **The obfuscation format round-trips.** A save is re-emitted in whichever of
  VASSAL's three formats it was read in (`VOBS`, `!VCSK`, `!VCSZ` — see
  [docs/vsav-format.md](../vsav-format.md)).
- **Writes are atomic.** Output goes to a temp file that is then `os.replace`d
  into position, so an interrupted write never leaves the truncated `.vsav` that
  makes VASSAL report *"… is not a VASSAL saved game or log."*

## Conventions shared by every tool

- **Report first, write second.** Newer tools gate writing behind `--apply`;
  older ones write by default and preview with `--dry-run`. Each page says which.
- **Backups.** Saves get a `-backup.vsav` / `.bak` copy unless `--no-backup`.
  Extension backups go in **`<module>_ext/backups/`**, never beside the `.vmdx`:
  VASSAL's `ExtensionsManager` loads *every* non-hidden file in `_ext/` whose
  metadata parses as an extension, so a stray `foo.vmdx.bak` is loaded as a real
  extension. Directories are skipped by that filter.
- **Modification times are preserved.** Tools that rewrite a `.vmdx` copy every
  untouched ZIP entry with its original mtime, because VASSAL decides whether a
  cached image tile is stale purely by comparing mtimes (see
  [docs/image-display-and-tiling.md](../image-display-and-tiling.md)).
- **Stacks are left alone deliberately.** Removing a piece leaves its id dangling
  in any stack that listed it, which is safe: `Stack.setState()` looks each member
  up and silently skips what it cannot resolve. A later Refresh Counters reknits
  the stacking.
- **GPID is the identity.** A piece is attributed to its definition by the Piece
  Id in the 4th `;`-field of its innermost BasicPiece state — exactly what VASSAL
  matches on. That attribution is only exact while GPIDs are unique across the
  module and its extensions; [renumber_gpids.py](renumber_gpids.md) is what makes
  sure they are.

## Reading the diagrams

Each page links a diagram under [`diagrams/`](diagrams/), in two forms: a
**`.md`** page that renders here on GitHub, and the **`.html`** viewer to
download and open in a browser — it needs no server and no network, and adds
pan, zoom, search and relationship tracing. All fourteen share one grid:

- the **top lane** is the artefact being transformed (the save, or the extension XML);
- the **bottom lane** is the reference or option side — the module, the extension
  index, the CLI filters, the credentials;
- the bottom lane **joins upward** at the *Select* stage: that arrow is what the
  selection is made against;
- the top lane **joins downward** at the *Write* stage: that arrow is what is
  actually written.

Diagram sources are the `.dataflow.json` specs kept beside the HTML; they were
rendered with the `archify` skill at its `showcase` quality profile. The `.md`
pages and the SVGs they embed are exported from that same HTML by
[`docs/export-diagrams.py`](../export-diagrams.py), so the three formats cannot
drift apart — see [`docs/diagrams.md`](../diagrams.md) for every diagram in the
repository and how to regenerate them.

## Checking the result

```bash
unzip -t out.vsav                 # container intact
tools/swap_maps.py … ALL          # prints the layout it wrote
```

To confirm that only what you intended changed, deobfuscate both files and
compare the token lists. A correct run differs in exactly the tokens it targeted:

```python
from swap_maps import read_vsav, split_commands
a, _, _ = read_vsav('before.vsav'); b, _, _ = read_vsav('after.vsav')
ta, tb = split_commands(a), split_commands(b)
assert len(ta) == len(tb)
print([i for i in range(len(ta)) if a[ta[i][0]:ta[i][2]] != b[tb[i][0]:tb[i][2]]])
```

## See also

- [tools/README.md](../../tools/README.md) — the narrative companion to these pages
- [docs/data_structures/](../data_structures/README.md) — the formats these tools parse, as data structures
- [docs/vsav-format.md](../vsav-format.md) — the saved-game container and its three obfuscation formats
- [docs/vmdx-format.md](../vmdx-format.md) — the extension archive
- [docs/refresh-counters.md](../refresh-counters.md) — `AddCountersRunner`, which consumes the job files two tools write
- [docs/architecture/architecture.md](../architecture/architecture.md) — runtime architecture of the Java application ([interactive](../architecture/architecture.html))
