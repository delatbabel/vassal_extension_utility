# VASSAL Extension Utility

A desktop utility for working with [VASSAL](https://vassalengine.org/) game module files (`.vmod`) and their extensions (`.vmdx`).

Licensed under the GNU Lesser General Public License v2.1, the same as the VASSAL engine.

## Purpose

VASSAL's built-in module editor lets you edit a module or extension in isolation, but it has no facility for moving components between a module and its extensions (or between two extensions of the same module). This utility fills that gap.

**Current capabilities:**

1. Open a VASSAL module (`.vmod`) and one or more of its extensions (`.vmdx`) simultaneously.
2. View the component hierarchy of each file as a tree, with component type names matching the VASSAL module editor.
3. Select one or more components using click, Shift-click (range), or Ctrl-click (discontiguous); or right-click to search by name.
4. Move all selected components from one file's hierarchy into another, automatically copying any image assets they reference.
5. Copy selected components into another file's hierarchy, creating duplicates without removing the originals. A copy duplicates only the selected components themselves — not their child components.

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
3. **Select source components** in one panel using any combination of:
   - Click — select a single component.
   - **Shift-click** — extend the selection as a contiguous range.
   - **Ctrl-click** — add or remove individual components from the selection.
   - **Right-click → Search and select…** — type a string; all components whose name contains that string (case-insensitive) are selected automatically.
4. **Single-click one component** in the other panel to mark it as the **destination parent**.
5. Transfer the selection into the destination parent:
   - Click **Move →** (or **← Move**) to move all selected source components, together with their children. The originals are removed from the source.
   - Click **Copy →** (or **← Copy**) to duplicate the selected components into the destination parent. Only the components themselves are copied — **their child components are not** — and the originals are retained.
6. Images referenced by any transferred component are automatically copied to the destination archive. They are **not** removed from the source — the same image may be used by other components that remain.
7. If both a parent and its child are selected, only the parent is transferred (on a move the child is carried with it automatically; on a copy the child is excluded along with all other children).
8. **File → Save All** (or Ctrl+S) writes all modified archives back to disk.

## File Format Documentation

- [vmod format](docs/vmod-format.md) — VASSAL module file structure
- [vmdx format](docs/vmdx-format.md) — VASSAL extension file structure
