# Diagrams

Every diagram in this repository exists in three forms, all generated from one
source and kept in step:

| Form | What it is for |
|---|---|
| **`<name>.md`** | Renders on GitHub. Embeds the SVG and restates the diagram as text — stages, labelled flows, conclusion cards. |
| **`<name>-light.svg` / `<name>-dark.svg`** | The drawing on its own. The `.md` picks one with `<picture>`, so it follows your GitHub light/dark setting. |
| **`<name>.html`** | The interactive viewer: pan, zoom, search, relationship tracing, its own theme toggle. Download it and open it in a browser — no server, no network. |

GitHub serves a checked-in `.html` file as plain text rather than rendering it,
which is why the `.md` pages exist; the `.html` files are the ones worth
downloading.

The source of every diagram is the `.json` specification kept beside it, rendered
to HTML with the `archify` skill at its `showcase` quality profile.

## Architecture

| Diagram | View on GitHub | Interactive |
|---|---|---|
| VASSAL Extension Utility — Runtime Architecture | [`architecture.md`](architecture/architecture.md) | [`architecture.html`](architecture/architecture.html) |

## Data structures

The `.vsav`, `.vmod` and `.vmdx` formats as data structures — see
[`data_structures/README.md`](data_structures/README.md) for the prose.

| Diagram | View on GitHub | Interactive |
|---|---|---|
| A game piece in a saved game — Record Layout | [`piece-record.md`](data_structures/diagrams/piece-record.md) | [`piece-record.html`](data_structures/diagrams/piece-record.html) |
| SequenceEncoder — Nesting and Escaping | [`sequence-encoder.md`](data_structures/diagrams/sequence-encoder.md) | [`sequence-encoder.html`](data_structures/diagrams/sequence-encoder.html) |
| .vmdx — Container Structure | [`vmdx-structure.md`](data_structures/diagrams/vmdx-structure.md) | [`vmdx-structure.html`](data_structures/diagrams/vmdx-structure.html) |
| .vmod — Container Structure | [`vmod-structure.md`](data_structures/diagrams/vmod-structure.md) | [`vmod-structure.html`](data_structures/diagrams/vmod-structure.html) |
| .vsav — Container Structure | [`vsav-structure.md`](data_structures/diagrams/vsav-structure.md) | [`vsav-structure.html`](data_structures/diagrams/vsav-structure.html) |

## Command-line tools

One data-flow diagram per script in [`tools/`](../tools/), all on the same grid
so they can be read against each other — see [`tools/README.md`](tools/README.md).

| Diagram | View on GitHub | Interactive |
|---|---|---|
| copy_counter_positions.py — Data Flow | [`copy_counter_positions.md`](tools/diagrams/copy_counter_positions.md) | [`copy_counter_positions.html`](tools/diagrams/copy_counter_positions.html) |
| dedupe_pieces.py — Data Flow | [`dedupe_pieces.md`](tools/diagrams/dedupe_pieces.md) | [`dedupe_pieces.html`](tools/diagrams/dedupe_pieces.html) |
| drop_slots.py — Data Flow | [`drop_slots.md`](tools/diagrams/drop_slots.md) | [`drop_slots.html`](tools/diagrams/drop_slots.html) |
| fix_sif_subs.py — Data Flow | [`fix_sif_subs.md`](tools/diagrams/fix_sif_subs.md) | [`fix_sif_subs.html`](tools/diagrams/fix_sif_subs.html) |
| global_properties.py — Data Flow | [`global_properties.md`](tools/diagrams/global_properties.md) | [`global_properties.html`](tools/diagrams/global_properties.html) |
| migrate_15_to_21.py — Data Flow | [`migrate_15_to_21.md`](tools/diagrams/migrate_15_to_21.md) | [`migrate_15_to_21.html`](tools/diagrams/migrate_15_to_21.html) |
| missing_counters.py — Data Flow | [`missing_counters.md`](tools/diagrams/missing_counters.md) | [`missing_counters.html`](tools/diagrams/missing_counters.html) |
| remove_ext_counters.py — Data Flow | [`remove_ext_counters.md`](tools/diagrams/remove_ext_counters.md) | [`remove_ext_counters.html`](tools/diagrams/remove_ext_counters.html) |
| remove_offmap_pieces.py — Data Flow | [`remove_offmap_pieces.md`](tools/diagrams/remove_offmap_pieces.md) | [`remove_offmap_pieces.html`](tools/diagrams/remove_offmap_pieces.html) |
| remove_placemark_carriers.py — Data Flow | [`remove_placemark_carriers.md`](tools/diagrams/remove_placemark_carriers.md) | [`remove_placemark_carriers.html`](tools/diagrams/remove_placemark_carriers.html) |
| renumber_gpids.py — Data Flow | [`renumber_gpids.md`](tools/diagrams/renumber_gpids.md) | [`renumber_gpids.html`](tools/diagrams/renumber_gpids.html) |
| shift_pieces.py — Data Flow | [`shift_pieces.md`](tools/diagrams/shift_pieces.md) | [`shift_pieces.html`](tools/diagrams/shift_pieces.html) |
| swap_maps.py — Data Flow | [`swap_maps.md`](tools/diagrams/swap_maps.md) | [`swap_maps.html`](tools/diagrams/swap_maps.html) |
| upload_scenarios.py — Data Flow | [`upload_scenarios.md`](tools/diagrams/upload_scenarios.md) | [`upload_scenarios.html`](tools/diagrams/upload_scenarios.html) |

## Regenerating

The `.md` pages and the SVGs are exported from the delivered HTML, so a diagram
is only ever authored once. After changing a `.json` spec, re-render its HTML
with `archify`, then:

```bash
python3 docs/export-diagrams.py                     # every diagram under docs/
python3 docs/export-diagrams.py docs/tools/diagrams/swap_maps.html   # just one
```

The exporter loads each delivered HTML in headless Firefox, resolves the page CSS
that styles the SVG into inline styles, and writes the result out as a standalone
file. Nothing is redrawn — the geometry is the one `archify` produced. It needs
Firefox and `geckodriver` (the Firefox snap ships both, as
`firefox.geckodriver`), starts and stops the driver itself, and is deterministic:
it waits for the embedded webfont before measuring, because the legend's layout
is computed from real text metrics.

> A Firefox installed as a snap cannot read files outside `$HOME`, so the
> repository has to live under your home directory for the exporter's `file://`
> loads to work.
