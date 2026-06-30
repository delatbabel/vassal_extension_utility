# VASSAL Extension Utility

A desktop utility for working with [VASSAL](https://vassalengine.org/) game module files (`.vmod`) and their extensions (`.vmdx`).

Licensed under the GNU Lesser General Public License v2.1, the same as the VASSAL engine.

## Purpose

VASSAL's built-in module editor lets you edit a module or extension in isolation, but it has no facility for moving components between a module and its extensions (or between two extensions of the same module). This utility fills that gap.

**Current capabilities:**

1. Open a VASSAL module (`.vmod`) and one or more of its extensions (`.vmdx`) simultaneously.
2. View the component hierarchy of each file as a tree, labelled like the VASSAL module editor — `configure name [Component Type]` (e.g. `World Maps [Map Window]`), so components of the same type can be told apart.
3. Select one or more components using click, Shift-click (range), or Ctrl-click (discontiguous); or right-click to search by name.
4. Move all selected components from one file's hierarchy into another, automatically copying any image assets they reference — and the saved-game (`.vsav`) file of any Pre-defined setup that is moved.
5. Copy selected components into another file's hierarchy, creating duplicates without removing the originals. A copy duplicates only the selected components themselves — not their child components.
6. Reopen recent files from **File → Open Recent …** — the 5 most-recently-opened files for each panel are remembered between sessions.

## Building

Requires Java 11+ and Maven 3.x.

```bash
make jar     # build executable fat JAR
make run     # build and run
make clean   # remove build artefacts
make help    # show all targets
```

The fat JAR is produced at `target/extension-utility-1.0-jar-with-dependencies.jar`.

You can also invoke Maven directly:

```bash
./mvnw package
java -jar target/extension-utility-1.0-jar-with-dependencies.jar
```

## Usage

1. **File → Open Module** — open a `.vmod` file (appears in the left panel).
2. **File → Open Extension** — open a `.vmdx` file (appears in the right panel).
   - **File → Open Recent …** lists the 5 most-recently-opened files for each panel (grouped under "Left panel" / "Right panel"); selecting one reopens it into that panel. The lists persist between sessions in `~/.vassal-extension-utility/recent-files.properties`.
3. **Select source components** in one panel using any combination of:
   - Click — select a single component.
   - **Shift-click** — extend the selection as a contiguous range.
   - **Ctrl-click** — add or remove individual components from the selection.
   - **Right-click → Search and select…** — type a string; all components whose name contains that string (case-insensitive) are selected automatically.
4. **Single-click one component** in the other panel to mark it as the **destination parent**.
   - If you leave the target panel with **nothing selected**, transferring will instead ask whether to **recreate the parent path**: every ancestor of each selected component — all the way up to the root — is copied into the target panel (without their other children), and the components are then transferred into that recreated location. Choose **Cancel** to abort.
5. Transfer the selection into the destination parent:
   - Click **Move →** (or **← Move**) to move all selected source components, together with their children. The originals are removed from the source.
   - Click **Copy →** (or **← Copy**) to duplicate the selected components into the destination parent. Only the components themselves are copied — **their child components are not** — and the originals are retained.
6. Images referenced by any transferred component are automatically copied to the destination archive. So are the saved-game files (`.vsav`) of any **Pre-defined setup** that is transferred. Copied assets are **not** removed from the source — the same image may be used by other components that remain, and a moved setup's `.vsav` is left behind as an (now unused) copy.
7. If both a parent and its child are selected, only the parent is transferred (on a move the child is carried with it automatically; on a copy the child is excluded along with all other children).
8. **File → Save All** (or Ctrl+S) writes all modified archives back to disk.

## File Format Documentation

- [vmod format](docs/vmod-format.md) — VASSAL module file structure
- [vmdx format](docs/vmdx-format.md) — VASSAL extension file structure
