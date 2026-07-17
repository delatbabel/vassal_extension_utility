# Changes

## 1.0.9

Gives the application a real icon on every platform, and makes desktops actually pick it up.

### Added

- **VASSAL-gear application icon across all platforms.** The window/taskbar/Dock icon and the installed launcher previously showed a "no icon" placeholder. A master SVG (with sized 16–256 PNGs bundled into the JAR) now feeds `setIconImages()`/the taskbar, the Linux `.deb`/`.rpm` ship it via `jpackage --icon`, the Windows `.exe` embeds a multi-res `.ico`, and the macOS `.app`/`.dmg` carry an `.icns`.

### Fixed

- **KDE (and other desktops) now pick up the app icon on install/upgrade.** The Linux package shipped the icon only as an absolute-path file under `/opt` referenced directly by the `.desktop` `Icon=` field, and the `postinst` never refreshed any icon cache — so on an upgrade over a version that had no icon, KDE Plasma's cached "no icon" was never invalidated and the menu entry kept showing the placeholder. The package now registers the icon in the freedesktop **hicolor theme** via `xdg-icon-resource` (which runs `gtk-update-icon-cache`, the signal desktops use to invalidate their caches) and references it **by name** in the `.desktop` file, matching how the sibling `vassal` package does it. Icon changes now show up automatically on future installs and upgrades.

## 1.0.8

Adds an extension-properties editor and closes the last way a Move/Copy could produce an illegal module.

### Added

- **Tools → Edit Extension Properties (left/right)…** — mirrors VASSAL's `ModuleExtension` editor. Edit an extension's **Version**, **Description**, and **Allow loading with any module** (`anyModule`) flag; the **Extension ID** is shown read-only (changing it would invalidate existing saved games). Available only for an extension. The values are written **the way VASSAL stores them — in both places**: the `version`/`description`/`anyModule` attributes on the `ModuleExtension` root of `buildFile.xml`, and the matching `<version>`/`<description>`/`<universal>` values in the regenerated `extensiondata` metadata entry. The `extensionId`, module name/version, and recorded `vassalVersion` are left untouched. (Verified: the written `buildFile.xml` root and `extensiondata` are byte-format identical to VASSAL's, XML escaping included, and the edited values round-trip on reopen.)

### Fixed

- **Move/Copy now refuses to inject an `ExtensionElement` into a module.** Copying or moving a component that lives inside an extension's `ExtensionElement` into a module *with no destination parent selected* took the recreate-parents path, which shallow-cloned that wrapper into the module root. The result loaded in VASSAL but broke **Tools → Refresh Counters**, failing with a misleading "saved with older VASSAL version" error that re-saving could not clear. The operation is now refused up front with a dialog telling the user to select the intended parent component in the module tree first. The guard is scoped to that path only, so module→module recreate-parents and grafting into an extension are unaffected.

## 1.0.7

Extensions are now displayed as the module hierarchy they graft into, instead of as flat `ExtensionElement` rows.

### Changed

- **An extension panel reconstructs the parent module's tree.** Previously each grafted component appeared as a flat `Extension Element → target/path/…` row. The panel now decodes each `ExtensionElement`'s `target` into a chain of **greyed "inherited" nodes** (shared prefixes merged), with the wrapper's real component subtree hanging beneath its target in the normal colour — matching how the VASSAL module editor shows the extension. Even a doubly-wrapped (damaged) extension re-grafts from the root and displays correctly.
  - Inherited (grey) nodes are display-only stand-ins for the module's own components: they **cannot be Moved, Copied, or Deleted**, but they *can* be chosen as a Move/Copy **destination** to graft into that specific module location.
  - Tree-state preservation (expansion/selection/scroll) was generalised to handle the synthetic inherited nodes, which have no DOM element.

## 1.0.6

Fixes double-wrapped `ExtensionElement`s and adds a repair tool for extensions already damaged by the bug.

### Fixed

- **Copying/moving an `ExtensionElement` between extensions no longer double-wraps it.** Selecting an `ExtensionElement` wrapper as the source and grafting it into another extension re-wrapped it in a spurious outer `ExtensionElement` with an empty `target`. VASSAL loaded the result, but the module editor could not edit such a doubly-wrapped component. When the source is itself an `ExtensionElement`, it is now grafted directly onto the destination extension root instead of being re-wrapped.

### Added

- **Tools → Repair Double-Wrapped Extension Elements (left/right)…** — collapses existing empty-`target` outer wrappers into their real inner wrapper so the VASSAL editor can edit them again. (Verified against the damaged `E-TiF.vmdx`: 294 wrappers collapsed, 0 remaining.) Running it on an undamaged archive (or a module) reports nothing to fix and changes nothing.

## 1.0.5

### Changed

- **Trees now open with only the root expanded and every folder closed**, instead of fully expanded, so a newly opened archive is easier to scan and the user can open just the branches they need.

## 1.0.4

Fixes a crash that a Move out of an extension could bake into the extension file, and adds a Delete command.

### Added

- **Delete** — remove a component (or a whole subtree) from either panel, via the tree's right-click "Delete" menu item or the toolbar's **Delete (left)** / **Delete (right)** buttons. A confirm dialog names the component (or count) and warns that referenced images and Pre-defined setup files are left in the archive untouched. The archive root and inherited (module) nodes cannot be deleted; deleting a grafted component in an extension also drops any `ExtensionElement` wrapper it leaves empty.

### Fixed

- **Moving a component out of an extension no longer leaves an empty `ExtensionElement`.** Each component grafted into an extension lives inside its own `ExtensionElement` wrapper. Moving that component back out to the module removed the component but left the wrapper behind with nothing inside it. VASSAL **crashes on load** when it hits an empty `ExtensionElement` (it reads no component, then dereferences it — `NullPointerException` in `ExtensionElement.addTo`), aborting the entire module load. A Move out of an extension now drops any wrapper it empties, and the status line reports how many were removed.
- **Linux `.deb`/`.rpm` packages now appear in the KDE/GNOME application menus.**

### Documentation

- Added **`docs/vassal-empty-extensionelement-crash.md`** documenting the VASSAL engine bug (with source line references and a suggested null-guard fix) so it can be addressed upstream.
- Noted the empty-wrapper cleanup in `AGENTS.md`.

### ⚠️ Upgrade note

An extension edited with an earlier build by **moving a grafted component back out to the module** may contain empty `ExtensionElement`s and will crash VASSAL when enabled. Open such an extension in this build and perform any Move out of it, or otherwise re-save it, to strip the empty wrappers. (The sample `SiF.vmdx` had 24 such wrappers, now repaired.)

## 1.0.3

### Fixed

- **Copy now carries the whole component subtree**, exactly like Move — previously a Copy could omit descendants of the copied component.

### Added

- **A new extension inherits the parent module's version** (both the root `version` attribute and the `extensiondata`), falling back to `0.0` only when the module has none.

### Packaging

- Linux packages now use a **space-free launcher name** and install a `/usr/bin` symlink.
- Fixed Windows 32-bit packaging by matching the `jlink` version to the target JDK.

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
</content>
</invoke>
