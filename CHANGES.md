# Changes

## 1.0.21

The command-line tools run again, and gain one for the game state that no
counter accounts for.

### Added

- **`tools/global_properties.py` — report and rewrite the Global Property values a save carries.** A module's Global Properties are game state, saved as one `GlobalProperty` command each, and nothing ties a value to the counter that set it: delete that counter, move it to a map the module no longer has, or let a migration rebuild it, and the value stays where the last change left it. Nothing else finds this — every piece is intact, so `remove_offmap_pieces.py` reports nothing, and Refresh Counters rebuilds pieces, not properties. The symptom is a chart reading a number no counter accounts for (a WiF `BP Overlay` showing a trade balance of −24 with every `MajP Lending Strip` at zero, because the loan lives in `Germantradebps`/`Italytradebps`). With `--module` the report shows each property's `initialValue` beside the saved value and `--changed` lists only what differs; `--reset=SUBSTR` puts matching properties back to the default and `--set=NAME=VALUE` sets one by name, writing only with `--apply`. See `tools/README.md`.

### Fixed

- **The command-line tools in `tools/` work again on any saved game.** `read_vsav()` grew a third return value (the `!VCSZ`/`!VCSK` format flag) when VASSAL 3.8+ deflated saves were supported, but only `swap_maps.py` and `migrate_15_to_21.py` were updated for it, so every other tool died at startup with `ValueError: too many values to unpack (expected 2, got 3)`. All of them now unpack it — `copy_counter_positions.py`, `dedupe_pieces.py`, `fix_sif_subs.py`, `missing_counters.py`, `remove_ext_counters.py`, `remove_offmap_pieces.py`, `remove_placemark_carriers.py` and `shift_pieces.py` — and each rewrites a save in whichever of the two formats it was read in, instead of silently converting a deflated save back to the older one.

## 1.0.20

Windows and macOS downloads get names short enough to read on the releases page.

### Changed

- **Shorter Windows and macOS package file names.** The installers are now named `VASSAL-Extension-Utility-<version>-<arch>.exe` / `.dmg`, dropping the git commit and branch that the build version appends and the redundant platform tag. The GitHub releases page truncates long file names from the end — cutting off the very `x86_64`/`aarch64` suffix that tells you which download is yours. The full build version is still recorded inside each package (the `.exe` version resource, the installer's product version, its install directory and its Add/Remove Programs entry) and still names the `.sha256` checksum file.
