# Changes

## 1.0.1

A maintenance release that fixes data loss and image-display problems when moving components into extensions, plus a small release-tooling addition.

### Fixed

- **Moving multiple components into an extension no longer drops any of them.** When several components were transferred to the same location in an extension (for example three boards under *World Maps → Map Boards*), they were all packed into a single `ExtensionElement`. VASSAL only reads the first component of an `ExtensionElement`, so it silently kept the first and discarded the rest — and permanently deleted them if the extension was later re-saved in the module editor. The utility now writes **one `ExtensionElement` per component**, exactly as the VASSAL editor does, so every moved/copied component is preserved.

  This was also the real cause of the "a moved board image won't display in VASSAL" reports: the board that owned the image was one of the discarded components, so nothing referenced the image. (The image data and generated tiles were always correct.)

- **Image modification times are now preserved when saving.** Previously every save stamped all entries with the current time, which invalidated VASSAL's image-tile cache and forced large board images to be re-tiled on the next load. Moved and copied images now keep their original timestamps.

### Added

- **`make version-bump`** — bumps the patch version by 0.0.1 (e.g. `1.0.0` → `1.0.1`), updating both the `Makefile` and `pom.xml` in one step.

### Documentation

- Added **`docs/image-display-and-tiling.md`**, a detailed explanation of how VASSAL tiles and renders large board images, why a structurally-incorrect extension can leave a board blank, and how to diagnose it from VASSAL's `errorLog`.
- Updated `AGENTS.md` and `docs/vmdx-format.md` to document that an `ExtensionElement` holds exactly one component.

### ⚠️ Upgrade note

Extensions produced with **1.0.0** by moving or copying **more than one component to the same target location** are incomplete — VASSAL discarded all but the first component when it loaded them. **Recreate those extensions** with 1.0.1: start from the original (pre-move) module and redo the transfer. Extensions where only a single component was grafted per location are unaffected.

## 1.0.0

Initial release.

- Open a VASSAL module (`.vmod`) and its extensions (`.vmdx`) side by side; view each component hierarchy as a tree labelled like the VASSAL module editor (`configure name [Component Type]`).
- **Move** and **Copy** components between a module and an extension, automatically carrying referenced images (including the counter images embedded in game-piece definitions) and Pre-defined setup `.vsav` files. Grafting into an extension wraps components in the correct `ExtensionElement`; a Move genuinely moves a setup's `.vsav` (removed from the source when no longer referenced).
- **Search and select** components by a case-sensitive, space-significant substring, scoped to the selected branch.
- Tree expansion, selection, and scroll position are preserved across edits.
- **Create a new empty extension** for the loaded module and **Save Extension As…** into the module's `_ext` directory.
- **Remove Unused Images** tool (mirrors VASSAL's).
- **Open Recent** — remembers the 5 most-recently-opened files per panel.
- `Makefile` targets to build installable packages — Linux `.deb`/`.rpm` (jpackage), Windows `.exe` ×3 architectures (Launch4j), macOS `.dmg` (libdmg-hfsplus) — with a VASSAL-style version-numbering system. See [docs/packaging.md](docs/packaging.md).
