# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

A Java Swing desktop utility for moving components between VASSAL boardgame module files (`.vmod`) and their extensions (`.vmdx`). The sibling repo `../vassal` is the open-source VASSAL engine (Maven/Java 11+); code may be borrowed from it under LGPL 2.1.

## Build & Run

```bash
./mvnw package                       # compile + produce fat JAR in target/
./mvnw exec:java                     # run without packaging
java -jar target/extension-utility-1.0-jar-with-dependencies.jar
```

Also: `make jar` / `make run` / `make clean` (see Makefile). No test suite yet. Single-module Maven project; no lint plugins configured.

## Architecture

### Key Classes

| Class | Role |
|---|---|
| `Main` | `SwingUtilities.invokeLater` entry point |
| `model/VassalArchive` | Opens/saves a `.vmod` or `.vmdx` (ZIP + DOM). Tracks pending images. |
| `model/ComponentNode` | Wraps a `org.w3c.dom.Element`; computes the editor-style display label (see [Display names](#display-names)) and collects image references from subtree |
| `gui/ArchivePanel` | `JPanel` containing a `JTree` built from `VassalArchive.getRootElement()` |
| `gui/MainWindow` | Top-level `JFrame` — split pane of two `ArchivePanel`s, toolbar, status bar |

### Display names

`ComponentNode.getDisplayName()` mirrors VASSAL's editor tree, which renders each node as `configureName [Component Type]` (`ConfigureTree.ConfigureTreeNode.toString()` in the engine) — the component's editable name first, then its type in brackets. When a component has no configure name, only `[Component Type]` is shown.

- **Component Type** comes from `DISPLAY_NAMES` (simple class name → editor label, sourced from VASSAL's `Editor.*.component_type` properties). Note the editor class for a deck is `DrawPile` ("Deck"), not the runtime `Deck` class.
- **Configure name** is read from a *class-specific* XML attribute, because VASSAL routes a different attribute to `setConfigureName()` per class. `NAME_ATTRIBUTES` holds the exceptions (e.g. `Map`/`PrivateMap`/`PlayerHand` → `mapName`, widgets and `PieceSlot`/`CardSlot` → `entryName`, charts → `chartName`, `AboutScreen`/`HelpFile` → `title`, deck key commands → `menuText`, `Flare` → `flareName`, `ChessClock` → `side`). Classes not listed default to `name`, falling back to `FALLBACK_NAME_ATTRS` only when that is empty. This is what lets the utility distinguish, e.g., multiple Map Windows ("World Maps [Map Window]" vs "Impulse and Weather [Map Window]").

### Selection model

`ArchivePanel` uses `DISCONTIGUOUS_TREE_SELECTION`:
- Click / Shift-click / Ctrl-click work as standard multi-select.
- Right-click → "Search and select…" opens an input dialog; `ArchivePanel.selectMatching()` walks the full tree with `depthFirstEnumeration()` and calls `tree.setSelectionPaths()` with all case-insensitive partial matches.
- `getSelectedNodes()` → `List<DefaultMutableTreeNode>` (all selected; for source).
- `getSelectedNode()` → first selected path (for destination parent).

### Move / Copy Operation (the core feature)

Both the Move and Copy toolbar buttons share `MainWindow.performTransfer(src, dst, copy)`. `copy == false` is a Move; `copy == true` is a Copy:
1. All selected source nodes are collected via `src.getSelectedNodes()`.
2. `filterDescendants()` removes any node whose tree ancestor is also selected (transferring the ancestor already accounts for the descendant).
3. The destination parent is resolved:
   - If a node **is** selected in the target panel (`dst.getSelectedNode() != null`), every source component is transferred into it.
   - If **nothing** is selected, an OK/Cancel dialog offers to recreate parent paths. On OK, `ensureAncestorPath()` shallow-clones each source component's ancestor chain (every parent from just below the source root down to the immediate parent) under the destination root, and the component is transferred into the recreated parent. `findMatchingChild()`/`sameElement()` (tag name + identical attributes) reuse equivalent existing destination elements so shared paths are consolidated and the source root maps onto the destination root. Cancel aborts.
4. `ComponentNode.collectImageReferences(imageNames, recurse)` is called on each source node in one pass; results are unioned. `recurse` is `!copy` — Move scans the full subtree it carries, Copy scans only the element's own attributes. When recreating parents, each recreated ancestor's own-attribute image refs are added too.
5. Missing images are queued with `VassalArchive.addPendingImage()` (in-memory; written on save).
6. For each source element: `Document.importNode(srcElem, !copy)` clones it into the destination document (deep for Move so children are carried; **shallow for Copy so child components are excluded**) and appends to its resolved destination parent. For Move only, the original is then removed; for Copy the original is retained (creating a duplicate).
7. The destination archive is marked modified (and the source too on a Move); the destination tree is rebuilt via `ArchivePanel.refresh()` (the source tree too on a Move — Copy leaves the source unchanged).
8. File > Save All / Ctrl+S calls `VassalArchive.save()` which rewrites the ZIP atomically via a temp file.

Recreated ancestors are always shallow clones (no children), regardless of Move vs Copy — only the selected components carry children (and only on a Move).

Images are **always copied, never deleted** from the source archive (same image may be shared).

### File Format

- `.vmod`: ZIP with `buildFile.xml` (root `VASSAL.build.GameModule`) + `moduledata` + `images/`
- `.vmdx`: ZIP with `buildFile.xml` (root `VASSAL.build.module.ModuleExtension`) + `moduledata` + `extensiondata` + `images/`
- Extension elements use `<VASSAL.build.module.ExtensionElement target="ClassName:name/...">` to graft components into the parent module's tree.
- Full format docs: [docs/vmod-format.md](docs/vmod-format.md), [docs/vmdx-format.md](docs/vmdx-format.md)

## Sample Data

`/home/del/Games/VassalModules/EuropaNewMapV090.vmod` and its 8 extensions in `EuropaNewMapV090_ext/` are the primary test files.
