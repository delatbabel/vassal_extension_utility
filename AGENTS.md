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
| `model/ComponentNode` | Wraps a `org.w3c.dom.Element`; computes display label and collects image references from subtree |
| `gui/ArchivePanel` | `JPanel` containing a `JTree` built from `VassalArchive.getRootElement()` |
| `gui/MainWindow` | Top-level `JFrame` — split pane of two `ArchivePanel`s, toolbar, status bar |

### Selection model

`ArchivePanel` uses `DISCONTIGUOUS_TREE_SELECTION`:
- Click / Shift-click / Ctrl-click work as standard multi-select.
- Right-click → "Search and select…" opens an input dialog; `ArchivePanel.selectMatching()` walks the full tree with `depthFirstEnumeration()` and calls `tree.setSelectionPaths()` with all case-insensitive partial matches.
- `getSelectedNodes()` → `List<DefaultMutableTreeNode>` (all selected; for source).
- `getSelectedNode()` → first selected path (for destination parent).

### Move Operation (the core feature)

In `MainWindow.performMove(src, dst)`:
1. All selected source nodes are collected via `src.getSelectedNodes()`.
2. `filterDescendants()` removes any node whose tree ancestor is also selected (moving the ancestor already carries descendants).
3. `ComponentNode.collectImageReferences()` is called on each source node in one pass; results are unioned.
4. Missing images are queued with `VassalArchive.addPendingImage()` (in-memory; written on save).
5. For each source element: `Document.importNode(srcElem, true)` deep-clones it into the destination document, appends to the destination parent, then removes the original.
6. Both archives are marked modified; both `ArchivePanel.refresh()` rebuild the JTree from the updated DOM.
7. File > Save All / Ctrl+S calls `VassalArchive.save()` which rewrites the ZIP atomically via a temp file.

Images are **always copied, never deleted** from the source archive (same image may be shared).

### File Format

- `.vmod`: ZIP with `buildFile.xml` (root `VASSAL.build.GameModule`) + `moduledata` + `images/`
- `.vmdx`: ZIP with `buildFile.xml` (root `VASSAL.build.module.ModuleExtension`) + `moduledata` + `extensiondata` + `images/`
- Extension elements use `<VASSAL.build.module.ExtensionElement target="ClassName:name/...">` to graft components into the parent module's tree.
- Full format docs: [docs/vmod-format.md](docs/vmod-format.md), [docs/vmdx-format.md](docs/vmdx-format.md)

## Sample Data

`/home/del/Games/VassalModules/EuropaNewMapV090.vmod` and its 8 extensions in `EuropaNewMapV090_ext/` are the primary test files.
