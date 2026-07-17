# VASSAL Extension Utility

A desktop utility for working with [VASSAL](https://vassalengine.org/) game module files (`.vmod`) and their extensions (`.vmdx`).

Licensed under the GNU Lesser General Public License v2.1, the same as the VASSAL engine.

## Purpose

VASSAL's built-in module editor lets you edit a module or extension in isolation, but it has no facility for moving components between a module and its extensions (or between two extensions of the same module). This utility fills that gap.

**Current capabilities:**

1. Open a VASSAL module (`.vmod`) and one or more of its extensions (`.vmdx`) simultaneously, or create a brand-new empty extension for the loaded module.
2. View the component hierarchy of each file as a tree, labelled like the VASSAL module editor — `configure name [Component Type]` (e.g. `World Maps [Map Window]`), so components of the same type can be told apart.
3. Select one or more components using click, Shift-click (range), or Ctrl-click (discontiguous); or right-click to search by name.
4. Move all selected components from one file's hierarchy into another, automatically copying any image assets they reference — and the saved-game (`.vsav`) file of any Pre-defined setup that is moved.
5. Copy selected components (with all their child components) into another file's hierarchy, creating duplicates without removing the originals.
6. Reopen recent files from **File → Open Recent …** — the 5 most-recently-opened files for each panel are remembered between sessions.
7. Create a new empty extension for the loaded module (**File → New Extension**) and save it with **File → Save Extension As…**, which defaults to the module's `_ext` directory (creating it if needed).
8. Remove unused (unreferenced) images from a module or extension (**Tools → Remove Unused Images**), after reviewing and confirming the list — mirroring the VASSAL editor's tool of the same name.
9. List a module's extensions — active and inactive — and activate/deactivate or open them for editing (**Show Extensions**).
10. Find and remove "excess" game pieces from a saved game (`.vsav`) — pieces missing from the module's active extensions that otherwise cause *"Image not found"* / *"No such map"* on load and *"Unable to match piece … by name"* on Refresh Counters (**Excess Units…**). The tidied game is written to a new file, leaving the original unchanged.

## Building

Requires Java 11+ and Maven 3.x.

```bash
make jar     # build executable fat JAR
make run     # build and run
make clean   # remove build artefacts
make help    # show all targets
```

The fat JAR is produced at `target/extension-utility-1.0.0-jar-with-dependencies.jar`.

You can also invoke Maven directly:

```bash
./mvnw package
java -jar target/extension-utility-1.0.0-jar-with-dependencies.jar
```

## Packaging / releases

The `Makefile` builds installable packages for each platform, matching the types on the [VASSAL download page](https://vassalengine.org/download.html):

```bash
make release-linux-deb       # Linux .deb            (jpackage)
make release-linux-rpm       # Linux .rpm            (jpackage; needs rpmbuild)
make bootstrap               # fetch Windows/macOS cross-build tools + JDKs (once)
make release-windows         # Windows .exe x86_64 / aarch64 / x86_32 (Launch4j)
make release-macos           # macOS .dmg x86_64 / aarch64 (libdmg-hfsplus)
make release                 # all of the above
make release-sha256          # checksums
```

Packages are written to `tmp/` and bundle their own Java runtime. The Linux `.deb`/`.rpm` install to `/opt/vassal-extension-utility/` and put a `vassal_extension_utility` command on the user's `PATH` (via a `/usr/bin` symlink). Version numbering is managed via `VNUM` in the `Makefile` (`make version-print` / `version-bump` / `version-set`); `make version-bump` bumps the patch version by 0.0.1 (e.g. 1.0.0 → 1.0.1). See **[docs/packaging.md](docs/packaging.md)** for prerequisites (including how to install the required tools) and full details.

## Usage

1. **File → Open Module** — open a `.vmod` file (appears in the left panel).
2. **File → Open Extension** — open a `.vmdx` file (appears in the right panel).
   - **File → Open Recent …** lists the 5 most-recently-opened files for each panel (grouped under "Left panel" / "Right panel"); selecting one reopens it into that panel. The lists persist between sessions in `~/.vassal-extension-utility/recent-files.properties`.
   - **File → New Extension (right)** — with a module open in the left panel, creates a new empty extension for it in the right panel. The extension references the module's name and version automatically. It is unsaved until you use **Save Extension As…**.
   - **File → Save Extension As… (right)** — writes the right-panel extension to disk. The dialog opens in the module's extension directory — the module file name with `.vmod` replaced by `_ext` (e.g. `EuropaNewMapV090.vmod` → `EuropaNewMapV090_ext/`) — and creates that directory if it does not exist. (`Save All` also prompts for a location the first time a new extension is saved.)
3. **Select source components** in one panel using any combination of:
   - Click — select a single component.
   - **Shift-click** — extend the selection as a contiguous range.
   - **Ctrl-click** — add or remove individual components from the selection.
   - **Right-click → Search and select…** — type a string; all components **under the currently selected component** whose name contains that string are selected automatically. The match is **case-sensitive** ("HW" does not match "hw") and **space-significant** — the text is matched exactly as typed, with no spaces stripped, so searching for `" T "` finds "CHCOM T MiG7" but not "CHCOM MOT". The search is confined to the selected branch (searching under "Counters" never selects matches under "Game Maps"); with nothing selected, the whole tree is searched.
4. **Single-click one component** in the other panel to mark it as the **destination parent**.
   - If the target panel is an **extension** and you transfer to its top level (nothing selected, or the extension root selected), each component is wrapped in an *Extension Element* that targets its original location in the module's tree — so the VASSAL editor grafts it back into the same position. (Appending components to an extension any other way makes VASSAL silently ignore them.)
   - Otherwise, if you leave the target panel with **nothing selected**, transferring asks whether to **recreate the parent path**: every ancestor of each selected component — all the way up to the root — is copied into the target panel (without their other children), and the components are then transferred into that recreated location. Choose **Cancel** to abort.
5. Transfer the selection into the destination parent:
   - Click **Move →** (or **← Move**) to move all selected source components, together with their children. The originals are removed from the source.
   - Click **Copy →** (or **← Copy**) to duplicate the selected components, **together with all their children**, into the destination parent, leaving the originals in place.
6. Images referenced by any transferred component are automatically copied to the destination archive — including the counter images embedded in game pieces (`PieceSlot`/`CardSlot`), whose filenames live in the piece definition rather than in attributes. Images are **never** removed from the source (the same image may be used by other components that remain). The saved-game file (`.vsav`) of any **Pre-defined setup** is also copied — and on a **Move** it is genuinely moved: removed from the source once no remaining Pre-defined setup there still references it (so a `.vsav` shared by another setup is kept).
7. If both a parent and its child are selected, only the parent is transferred — for both Move and Copy the child is carried with it automatically (the whole subtree comes along).
8. After each transfer the trees are redrawn, but your view is kept: branches you had expanded stay expanded, collapsed ones stay collapsed, your selection is retained, and the scroll position stays in approximately the same place.
9. **Tools → Remove Unused Images** (left or right panel) lists the images in that archive that no component references. Review the list — deselect any you want to keep (they could be used by custom code) — and confirm to mark the rest for removal. The images are deleted when you save.
10. **File → Save All** (or Ctrl+S) writes all modified archives back to disk.

## File Format Documentation

- [vmod format](docs/vmod-format.md) — VASSAL module file structure
- [vmdx format](docs/vmdx-format.md) — VASSAL extension file structure
- [vsav format](docs/vsav-format.md) — VASSAL saved-game file structure (obfuscated command log + metadata)
- [vsav excess units](docs/vsav-excess-units.md) — finding and removing pieces missing from a module's active extensions

## Additional Documentation

- [packaging.md](docs/packaging.md) — building installable packages (`.deb`/`.rpm`/`.exe`/`.dmg`) and the versioning scheme
- [image-display-and-tiling.md](docs/image-display-and-tiling.md) — how VASSAL tiles large board images and why moved images must preserve their modification times
- [vassal-empty-extensionelement-crash.md](docs/vassal-empty-extensionelement-crash.md) — why an empty `ExtensionElement` crashes VASSAL, and how the utility avoids leaving one behind
- [AGENTS.md](AGENTS.md) — architecture and developer guide (also symlinked as `CLAUDE.md`)

## Changelog

- [CHANGES.md](CHANGES.md) — release-by-release history of changes
