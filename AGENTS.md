# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

A Java Swing desktop utility for moving components between VASSAL boardgame module files (`.vmod`) and their extensions (`.vmdx`). The sibling repo `../vassal` is the open-source VASSAL engine (Maven/Java 11+); code may be borrowed from it under LGPL 2.1.

## Build & Run

```bash
./mvnw package                       # compile + produce fat JAR in target/
./mvnw exec:java                     # run without packaging
java -jar target/extension-utility-1.0.0-jar-with-dependencies.jar
```

Also: `make jar` / `make run` / `make clean` (see Makefile). No test suite yet. Single-module Maven project; no lint plugins configured.

### Packaging & versioning

The `Makefile` also builds installable packages (modelled on `../vassal/Makefile`): Linux `.deb`/`.rpm` via `jpackage`, Windows `.exe` (x86_64/aarch64/x86_32) via Launch4j + `jlink`, and macOS `.dmg` (x86_64/aarch64) via `genisoimage` + libdmg-hfsplus. Version numbering follows VASSAL: `VNUM` is the single source of truth, `MAVEN_VERSION` is the pom version, and a git-derived `VERSION` names the artifacts (`version-set`/`version-print`/`post-release`). Windows/macOS cross-builds need `make bootstrap` first (fetches Launch4j, libdmg-hfsplus, and per-platform JDKs into the git-ignored `dist/tools` and `dist/jdks`). Full instructions and tool prerequisites: **[docs/packaging.md](docs/packaging.md)**.

## Architecture

### Key Classes

| Class | Role |
|---|---|
| `Main` | `SwingUtilities.invokeLater` entry point |
| `model/VassalArchive` | Opens/saves a `.vmod` or `.vmdx` (ZIP + DOM). Tracks pending images/files/deletions. `createExtension(module)` builds a new empty extension; `save()`/`saveAs(file)` write atomically (see [New extension & Save As](#new-extension--save-as)); `findUnusedImages()`/`removeImage(name)` back the unused-image tool (see [Remove unused images](#remove-unused-images)). |
| `model/RecentFilesStore` | Persists the 5 most-recently-opened files per panel to `~/.vassal-extension-utility/recent-files.properties` (see [Recent files](#recent-files)) |
| `model/ComponentNode` | Wraps a `org.w3c.dom.Element`; computes the editor-style display label (see [Display names](#display-names)) and collects image references from subtree |
| `gui/ArchivePanel` | `JPanel` containing a `JTree` built from `VassalArchive.getRootElement()`; `refresh()` preserves expansion/selection/scroll across rebuilds (see [Tree state preservation](#tree-state-preservation)) |
| `gui/MainWindow` | Top-level `JFrame` — split pane of two `ArchivePanel`s, toolbar, status bar |

### Display names

`ComponentNode.getDisplayName()` mirrors VASSAL's editor tree, which renders each node as `configureName [Component Type]` (`ConfigureTree.ConfigureTreeNode.toString()` in the engine) — the component's editable name first, then its type in brackets. When a component has no configure name, only `[Component Type]` is shown.

- **Component Type** comes from `DISPLAY_NAMES` (simple class name → editor label, sourced from VASSAL's `Editor.*.component_type` properties). Note the editor class for a deck is `DrawPile` ("Deck"), not the runtime `Deck` class.
- **Configure name** is read from a *class-specific* XML attribute, because VASSAL routes a different attribute to `setConfigureName()` per class. `NAME_ATTRIBUTES` holds the exceptions (e.g. `Map`/`PrivateMap`/`PlayerHand` → `mapName`, widgets and `PieceSlot`/`CardSlot` → `entryName`, charts → `chartName`, `AboutScreen`/`HelpFile` → `title`, deck key commands → `menuText`, `Flare` → `flareName`, `ChessClock` → `side`). Classes not listed default to `name`, falling back to `FALLBACK_NAME_ATTRS` only when that is empty. This is what lets the utility distinguish, e.g., multiple Map Windows ("World Maps [Map Window]" vs "Impulse and Weather [Map Window]").

### Recent files

`RecentFilesStore` keeps a separate most-recent-first list (capped at `MAX_RECENT` = 5) for the left and right panels, persisted as a Java properties file at `~/.vassal-extension-utility/recent-files.properties` (keys `left.0..left.4`, `right.0..right.4`). All disk I/O fails soft — a missing/unreadable store yields empty lists and save errors are only logged, so recent-file bookkeeping never blocks opening or saving.

`MainWindow` owns one store and records every successful open via `recordRecent(panel, file)` (which dispatches to `addLeft`/`addRight` by identity comparison against `leftPanel`/`rightPanel`). The **File → Open Recent …** submenu is rebuilt by `rebuildRecentMenu()` after each open: it shows a disabled "Left panel" / "Right panel" header above each panel's entries, and selecting an entry calls `openArchive(panel, file)` to reopen it into that panel. `openArchive()` is the single open path shared by the file chooser and the recent menu; if a recent file no longer exists it is pruned from the store.

### New extension & Save As

**File → New Extension (right)** (`MainWindow.newExtension`) requires a module in the left panel and calls `VassalArchive.createExtension(module)` to build a new, empty extension in the right panel. The created archive:
- has a `ModuleExtension` root referencing the module's `name`/`version`/`VassalVersion`, with `version="0.0"`, `anyModule="false"`, `nextPieceSlotId="0"`, and an `extensionId` generated like VASSAL does (last 3 chars of a random UUID);
- carries a freshly built `extensiondata` and a **copy of the module's `moduledata`** (VASSAL stores the parent module's moduledata inside the extension), queued as pending files;
- has **no backing file** (`getFile() == null`) and is marked modified — it must be saved with Save As.

**File → Save Extension As… (right)** (`saveArchiveAs(rightPanel)`) prompts for a destination, defaulting to `moduleExtensionDir()` — the conventional sibling directory named like the module file with `.vmod` replaced by `_ext` (e.g. `EuropaNewMapV090.vmod` → `EuropaNewMapV090_ext/`) — **creating that directory** if absent. It forces a `.vmdx` extension and confirms overwrite.

Saving is unified in `VassalArchive.writeArchive(source, target)`: it copies surviving entries from `source` (the current file, or **null** for a brand-new archive — then nothing is copied), then writes pending images, pending files, and the rewritten `buildFile.xml`, and atomically moves the temp file into place. `save()` writes back to the existing file (throws if there is none); `saveAs(target)` writes to a new file (creating parent dirs) and adopts it. `saveAll()` routes any modified archive with no file through Save As.

### Remove unused images

**Tools → Remove Unused Images (left/right)…** (`MainWindow.removeUnusedImages`) duplicates VASSAL's tool of the same name. `VassalArchive.findUnusedImages()` returns the archive's image entries minus everything `ComponentNode.collectReferencedImages()` finds referenced across the whole build tree (attributes **and** piece-definition text — the same scan used by Move/Copy — plus the legacy suffix-less `.gif` fall-back VASSAL applies, which errs toward keeping an image). For an extension this naturally operates on the extension's own images and components.

The dialog lists the unreferenced images in a multi-select `JList`, all pre-selected; the user may deselect any to keep (detection is heuristic — an image could be used by custom code, hence the confirmation). Confirmed images are dropped via `VassalArchive.removeImage(name)`, which queues the `images/<name>` entry in `pendingDeletions` and drops it from the live image set — **applied on the next save**, like every other edit. Nothing is deleted from disk until the user saves.

### Tree state preservation

`ArchivePanel.refresh()` rebuilds the `JTree` from the (mutated) DOM, but first captures and then re-applies the user's view state so a Move/Copy doesn't reset the panel:

- **Identity key** — state is keyed by the DOM `Element` object, which is stable across a rebuild of the *same* archive (`buildTreeNode` makes fresh `DefaultMutableTreeNode`/`ComponentNode` wrappers but reuses the underlying elements). After rebuild, the new tree is indexed `Element → node` to translate captured state onto the new nodes.
- **Captured** — expanded elements (`tree.getExpandedDescendants`), selected elements (`getSelectionPaths`), and the element of the row nearest the viewport top (`getClosestRowForLocation`).
- **Restored** — `expandPath` for each previously-expanded element (also re-expands ancestors, so order is irrelevant); collapsed nodes stay collapsed; selection is re-applied (entries whose element is gone — e.g. moved-away nodes — are dropped); the captured top row is scrolled back to the top via `scrollRectToVisible`, falling back to revealing the selection.
- **First load vs edit** — `setArchive()` calls the private `refresh(false)` so a newly opened archive opens at the default depth (`expandToDepth(..., 1)`); the public `refresh()` calls `refresh(true)` to preserve state. Cross-archive carry-over can't happen because a different document's elements won't be found in the index.

Newly added subtrees (e.g. just-moved components) have no prior state and therefore appear collapsed under their (preserved-open) destination parent.

### Selection model

`ArchivePanel` uses `DISCONTIGUOUS_TREE_SELECTION`:
- Click / Shift-click / Ctrl-click work as standard multi-select.
- Right-click → "Search and select…" opens an input dialog; `ArchivePanel.selectMatching()` walks the subtree of the **currently selected node** (falling back to the root when nothing is selected) with `depthFirstEnumeration()`, skips the scope node itself, and calls `tree.setSelectionPaths()` with all **case-sensitive** partial matches. Scoping means a search under "Counters" never selects matches under another branch such as "Game Maps", and "HW" does not match "hw". The query is used **verbatim — never trimmed** — so spaces are significant: searching `" T "` matches "CHCOM T MiG7" but not "CHCOM MOT".
- `getSelectedNodes()` → `List<DefaultMutableTreeNode>` (all selected; for source).
- `getSelectedNode()` → first selected path (for destination parent).

### Move / Copy Operation (the core feature)

Both the Move and Copy toolbar buttons share `MainWindow.performTransfer(src, dst, copy)`. `copy == false` is a Move; `copy == true` is a Copy:
1. All selected source nodes are collected via `src.getSelectedNodes()`.
2. `filterDescendants()` removes any node whose tree ancestor is also selected (transferring the ancestor already accounts for the descendant).
3. The destination parent is resolved:
   - **Grafting into an extension** (`graftIntoExtension`) — when the target archive is an extension *and* the destination would be its top level (nothing selected, or the `ModuleExtension` root selected): each component is wrapped in a `VASSAL.build.module.ExtensionElement`. An extension never holds components directly — they must sit inside an `ExtensionElement` whose `target` names the path, in the *parent module's* tree, to graft into. Appending a raw component to the extension root instead (the old behaviour) made VASSAL silently ignore it, so the component never appeared in the editor. `moduleTargetPath()` builds the target from the source element's parent chain (`className:configureName` tokens, encoded exactly like VASSAL's `ComponentPathBuilder`/`SequenceEncoder` — '/'-joined, ':'-joined, delimiters backslash-escaped; an empty target means graft at the module root). `findOrCreateExtensionElement()` reuses one wrapper per distinct target. (Verified: the generated target string is byte-for-byte identical to the ones VASSAL itself writes into the sample Europa extensions.)
   - Else if a node **is** selected in the target panel, every source component is transferred into it (this covers adding into an existing grafted subtree inside an extension).
   - Else (nothing selected, module target), an OK/Cancel dialog offers to recreate parent paths. On OK, `ensureAncestorPath()` shallow-clones each source component's ancestor chain (every parent from just below the source root down to the immediate parent) under the destination root, and the component is transferred into the recreated parent. `findMatchingChild()`/`sameElement()` (tag name + identical attributes) reuse equivalent existing destination elements so shared paths are consolidated and the source root maps onto the destination root. Cancel aborts.
4. Two kinds of referenced assets are gathered from each source node in one pass and unioned, with `recurse = !copy` (Move scans the full subtree it carries; Copy scans only the element itself):
   - `ComponentNode.collectImageReferences(imageNames, recurse)` — image filenames found in the source's image set. Scans both element **attributes** (e.g. `image`, `icon`, `Board image`) **and element text content**, because game pieces (`PieceSlot`/`CardSlot`) embed their image filenames inside the serialised piece definition held as the element's text, not in attributes. The text is tokenised on the piece-definition delimiters `[;,/\t\r\n\\]` (so comma-separated layer images are split out) and each token is matched against the archive's image set.
   - `ComponentNode.collectSetupFileReferences(recurse)` — the save-file entry named by each `PredefinedSetup`'s `file` attribute when `useFile="true"` (a `.vsav` entry at the archive root). Menu-only setups (`useFile="false"`) contribute nothing.
   When recreating parents, the recreated ancestors' own image refs and setup files are added too.
5. Missing images are queued with `VassalArchive.addPendingImage()`; missing setup files (checked via `VassalArchive.hasEntry()`) are queued with `VassalArchive.addPendingFile()`, which writes the entry under its exact root-level name. Both are in-memory until save. Each carries the **source entry's modification time** (`VassalArchive.getEntryTime()`) so the copied asset keeps its original timestamp — see [Image modification times & tiling](#image-modification-times--tiling).
6. For each source element: `Document.importNode(srcElem, !copy)` clones it into the destination document (deep for Move so children are carried; **shallow for Copy so child components are excluded**) and appends to its resolved destination parent. For Move only, the original is then removed; for Copy the original is retained (creating a duplicate).
7. **Move only:** setup files now orphaned in the source are pruned. The candidate set is the *moved* components' setup files (captured before recreated-ancestor files are added); each is removed via `VassalArchive.removeEntry()` only when a fresh scan of the post-removal source tree (`new ComponentNode(srcRoot).collectSetupFileReferences(true)`) shows no remaining `PredefinedSetup` references it.
8. The destination archive is marked modified (and the source too on a Move); the destination tree is rebuilt via `ArchivePanel.refresh()`, which preserves expansion/selection/scroll (see [Tree state preservation](#tree-state-preservation)) — the source tree too on a Move; Copy leaves the source unchanged.
9. File > Save All / Ctrl+S calls `VassalArchive.save()` which rewrites the ZIP atomically via a temp file (dropping any `pendingDeletions`).

Recreated ancestors are always shallow clones (no children), regardless of Move vs Copy — only the selected components carry children (and only on a Move).

**Images** are **always copied, never deleted** from the source — the same image may be referenced by components that remain. A **Pre-defined setup's `.vsav`**, by contrast, is a *true move*: copied to the destination, then removed from the source when (and only when) no remaining `PredefinedSetup` still references it (it may be shared with components that were not moved). `VassalArchive.removeEntry()` queues the drop in `pendingDeletions`, applied on the next `save()`.

### Image modification times & tiling

VASSAL renders large board images through a **disk tile cache** (`~/.VASSAL`/cache `tiles/<sha1(moduleName_moduleVersion)>`), and decides whether a cached tile is still valid purely by comparing **modification times**: `TilingHandler.isFresh()` re-tiles an image whenever its ZIP-entry mtime is newer-or-equal to the cached tile. Both the module and its active extensions tile into the same cache (keyed by the *module's* name+version).

Therefore `VassalArchive.writeArchive()` **preserves every entry's modification time**:
- surviving entries keep the source ZIP entry's `getTime()` (rather than being stamped "now" by `new ZipEntry(name)`);
- copied images/setup files keep their source entry's time, captured via `getEntryTime()` and passed to `addPendingImage()/addPendingFile()`.

Without this, every save reset all image mtimes to the current time, forcing VASSAL to re-tile large images on the next load. For a very large board image (tens of megapixels) that needless re-tile could fail or leave the map unrenderable even though the image bytes are perfectly valid (verified: identical CRC, `ImageIO`-readable, byte-for-byte the same as a VASSAL-authored entry). If an archive edited by an **older** build still misbehaves, deleting VASSAL's `tiles/` cache forces a clean re-tile.

### File Format

- `.vmod`: ZIP with `buildFile.xml` (root `VASSAL.build.GameModule`) + `moduledata` + `images/`
- `.vmdx`: ZIP with `buildFile.xml` (root `VASSAL.build.module.ModuleExtension`) + `moduledata` + `extensiondata` + `images/`
- Extension elements use `<VASSAL.build.module.ExtensionElement target="ClassName:name/...">` to graft components into the parent module's tree.
- `PredefinedSetup` components (`useFile="true"`) store a saved game as a root-level entry named by their `file` attribute, usually with a `.vsav` extension (but not required to — the attribute value is the literal entry name).
- Full format docs: [docs/vmod-format.md](docs/vmod-format.md), [docs/vmdx-format.md](docs/vmdx-format.md)

## Sample Data

`/home/del/Games/VassalModules/EuropaNewMapV090.vmod` and its 8 extensions in `EuropaNewMapV090_ext/` are the primary test files.
