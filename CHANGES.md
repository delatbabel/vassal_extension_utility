# Changes

## 1.0.19

The Windows packages become real installers, and the file choosers remember
where you keep your files.

### Added

- **The Windows packages are now executable installers.** `make release-windows` builds an NSIS installer per architecture (x86_64, aarch64, x86_32) with `makensis`, in place of the previous zip files. The installer offers Standard/Custom setup, removes older versions of the utility (Custom setup lets you choose which), creates optional desktop and Start Menu shortcuts, and registers an uninstaller in Add/Remove Programs. It registers no file associations — VASSAL itself owns `.vmod`/`.vlog`/`.vsav` — and its uninstall registry key is deliberately named so that VASSAL's own installer, which removes every key beginning `VASSAL `, never mistakes the utility for an old VASSAL. See `docs/packaging.md`.

- **A Help menu.** **Help → Users Guide** (F1) displays the user documentation — the same text as the repository README, bundled into the application at build time, with the developer-facing sections omitted. **Help → About** shows the installed version.

- **File choosers remember where you last opened each kind of file.** Opening a module (left panel), an extension (right panel), or a saved game (Excess Units, the Refresh Counters scenario picker, or the Download-from-Library scenario filter) now starts the chooser in the folder that kind of file was last opened from, falling back to the old defaults when nothing is remembered. The locations persist in `~/.vassal-extension-utility/recent-files.properties` alongside the recent-files lists.

- **`tools/migrate_15_to_21.py` — migrate a WiF 1.5.93 scenario to the 2.1.3 deluxe module.** Replaces board layouts and the extension list from a donor 2.1.3 scenario, renames the restructured charts on every piece, stack and deck, removes counters the deluxe extension set does not define, repoints renamed counters at their new slots, and replaces the old module's decks with the 2.1.3 module's own. The old per-nation control markers are recreated as the layered `Hex Control Marker` via an `AddCountersRunner` job: its `add=` lines now take `layer:<name>=<level>` fields, setting a Layer trait to a 1-based level before placing. See `tools/README.md`.

### Changed

- **Saved games written by VASSAL 3.8+ (the deflated `!VCSZ` format) now open**, in the application and the command-line tools alike, and each file is rewritten in whichever format it was read in.

- **The Download-from-Library prompt** now points at the library's search page for finding a module's project name, and when a project publishes more than one module the newest is listed first.

## 1.0.17

Fixes **Download Module from Library** reporting that it downloaded nothing,
without saying why, when the chosen folder cannot be written to.

### Fixed

- **A download into a protected folder failed silently.** A Windows user pointed the download at `C:\Program Files\VASSAL\modules` — where the VASSAL installer had put their module, so it is where the folder chooser opens — and was told *"Downloaded 0 file(s)"*. Nothing said why. Only an elevated process may create files under `Program Files`, so every download failed the moment it tried to write its temp file, and the reason went nowhere: the per-file messages were written into the progress dialog, which is disposed when the run ends, and the download flow had no logger of its own.

  The folder is now tested **before** anything is downloaded, by creating and deleting a file in it, and the user is asked for another one if it fails. The test cannot be done by asking `File.canWrite()`, which on Windows reports the read-only attribute and ignores ACLs — it answers "yes" for `Program Files`. Where the folder does not exist yet, its nearest existing ancestor is tested instead, so a folder the user may not go on to confirm is never created.

- **Download failures now say what went wrong.** The closing dialog names the first few reasons and points at `~/.vassal-extension-utility/extension-utility.log`, and the download flow logs each failure there. The message for an unwritable destination names the folder, where the JDK's own was a bare *"Permission denied"* — neither file nor folder — which is unusable in a report from a user.

- **A failure in the background half of the download was discarded.** As with Refresh Counters in 1.0.14, `SwingWorker.done()` never called `get()`, so anything thrown outside the per-file `catch` was lost and the run simply reported nothing downloaded. It is now collected and reported.

## 1.0.16

Command-line tooling only; the application itself is unchanged from 1.0.15.

### Added

- **`tools/missing_counters.py` — report counters a saved game does not contain.** Some scenarios are meant to be complete: every counter of certain extensions should be somewhere in them, on a map or in a force pool, so a player can reach any unit those extensions add. Nothing in VASSAL answers that, and the failure is silent — the unit simply cannot be found in play. Presence is decided by Piece Id, the key VASSAL itself matches on, over every `AddPiece` command in the save, so a counter is found wherever it sits.

  Two columns say *why* a counter is absent, which is what decides the remedy: `extension_listed_in_save` distinguishes a scenario that never loaded the extension at all — where the fix is to add the extension, not to place counters — and `name_found_elsewhere` flags a unit that is present under a different Piece Id, i.e. a bookkeeping mismatch rather than an absent unit. Counters whose only copies are on no map are reported as `off-map-only` rather than counted as present, since they are unreachable in play and beyond the reach of Refresh Counters.

  `--exclude N:WORD[,WORD...]` drops an extension's markers and map furniture from the set to account for — they are placed during play, and counting them as missing buries the real gaps. `--dup-csv` writes the opposite report, the counters a scenario holds more than once, from the same scan.

### Changed

- **`tools/remove_offmap_pieces.py` gained `--only-gpid`.** Restricting a run by Piece Id is exact, where the existing substring name filters are not: removing thirteen named oil markers with `--only-name=oil` would also catch anything else off-map with "oil" in its name. It compounds with the existing rule that only pieces on no map are ever deleted.

## 1.0.15

Fixes **Download Module from Library** on Windows and macOS, where it could not
connect to the library at all. Linux packages were never affected, and nothing
else changes: this is a packaging fix, with no change to the application's own
code.

**Upgrading.** Windows and macOS users should install 1.0.15 — 1.0.14 and every
earlier package for those platforms cannot reach the library. Linux users gain
nothing over 1.0.14.

### Fixed

- **Download Module from Library failed on Windows and macOS with an SSL handshake error.** Entering a library URL produced `javax.net.ssl.SSLHandshakeException: (handshake_failure) Received fatal alert: handshake_failure` a moment after the progress bar appeared, on every URL and every attempt — reinstalling, or switching between the 64-bit and 32-bit builds, made no difference.

  The Windows and macOS packages carry a runtime built by `jlink` from an explicit module list, and that list was missing `jdk.crypto.ec` — the SunEC provider. It is loaded as a service, so nothing in the application's bytecode refers to it and `jdeps` cannot detect it as a dependency. Without it the runtime has no elliptic-curve key agreement at all: no x25519, no secp256r1, leaving only the FFDHE groups, which the library's server does not accept, so the connection was refused during the handshake. The Linux `.deb`/`.rpm` were never affected because `jpackage` builds their runtime itself and includes every JDK module — which is also why this never appeared in development.

  `jdk.crypto.ec` is now included, at no measurable cost in size, along with `jdk.charsets` (about 1 MB) for the legacy encodings a module's XML declaration may name.

### Changed

- **The build verifies the runtime it produced.** `jlink` will produce an image that cannot open an HTTPS connection without complaint, so each linked runtime is now checked against the module list as it is built and the build fails naming what is absent. The runtime rules also depend on a stamp of that list: a linked runtime is a directory, which Make otherwise treats as up to date forever, and a rebuilt package was found repackaging a runtime linked before this fix. See [docs/packaging.md](docs/packaging.md).

### Verified

The handshake failure was reproduced away from Windows, by linking a runtime from
the module list the packages shipped and fetching the library URL with it: the
same exception, to the character. With `jdk.crypto.ec` added, the same
`GameLibrary` code fetches the project (2 module packages, 24 extensions) and
downloads an extension whose SHA-256 checks out. All three Windows packages were
rebuilt and confirmed to contain the module.

## 1.0.14

A repair release for **Refresh Counters**, which could not run at all in 1.0.13
when the utility had been installed from one of its own packages: it reported
*"No scenarios were refreshed"* without ever starting the VASSAL engine, and the
log it told the user to consult did not exist. Everything else in 1.0.13 is
unaffected, as is running the utility from the jar or from `make run`, where
Refresh Counters worked throughout.

**Upgrading.** If you installed 1.0.13 from a `.deb`, `.rpm`, `.exe` or `.dmg`,
install 1.0.14 over it — the fix is in the application, not in your modules,
extensions or saved games, none of which need changing. Nothing else about the
utility's behaviour has changed.

### Fixed

- **Refresh Counters did nothing in a build installed from a package, reporting only "No scenarios were refreshed".** The engine subprocess was launched with `java.home/bin/java`, and in an installed build that file does not exist: `jpackage` builds the runtime it bundles with `--strip-native-commands`, so the runtime holds only `conf`, `legal`, `lib` and `release` — there is no `bin` directory. `ProcessBuilder.start()` therefore threw before the engine was ever reached.

  A launcher is now resolved by searching, first hit wins: the JVM running the utility (right for `java -jar` and `mvn exec:java`), `JAVA_HOME`, each `PATH` entry — which is how VASSAL's own launcher script finds Java — and finally the conventional JVM directories, macOS's `Contents/Home` layout included. Each candidate must be an executable file. If none is found the run is refused up front with a dialog saying what is needed, instead of failing invisibly.

- **A failure in the background half of Refresh Counters was discarded.** `SwingWorker` holds whatever `doInBackground()` threw until someone calls `get()`, and nothing did, so the exception above left `results`, `blocked` and `fatal` all empty — which is indistinguishable from a run that simply found nothing to do, and produced exactly that message. The outcome is now collected and reported. A run that yields no `OK`, `FAIL`, `BLOCKED` or `FATAL` also reports the subprocess **exit code** and quotes its **last dozen lines of output**, so a launcher that is not there, a JVM too old for the engine's class files, or a process killed outright each say so.

- **"See the log for details" named a log that did not exist.** Logging went only to a console appender, and a utility started from a desktop entry or an installed package has no console attached. There are now two files, both in `~/.vassal-extension-utility/`:

  - `extension-utility.log` — the application log, rolling at 4 MB × 4.
  - `refresh-counters.log` — the full transcript of the last Refresh Counters run: everything the subprocess printed, engine output included, flushed line by line so a run still in progress can be read, with the exact command line and job file at the top.

  Every Refresh Counters dialog — success, blocked, failed, or nothing-refreshed — names the transcript's path.

### Verified

Against the WiF CE Official Combo 2.1.3 module and its 27 extensions: with
`java.home` pointed at a stripped `jpackage` runtime the launcher search resolves
to the system `java`; four scenarios refreshed from copies (5702, 5315, 6743 and
5255 counters), each recording the loaded module's version in place of the one it
was saved with. The packaged build carries the runner classes and the new logging
configuration.

## 1.0.13

Adds batch Refresh Counters for saved games outside the module, downloading a
module and its extensions from the game library, and a set of command-line
repairs for saved games and extensions.

### Added

- **Refresh Counters in Saved Games.** **Tools → Refresh Counters in Saved Games…** runs VASSAL's own *Refresh Counters* over any number of `.vsav` files against the module in the left panel — the batch equivalent of the engine's tool, for saved games that live outside the module. Select individual files or a whole folder; the same options dialog VASSAL shows is presented, and each scenario is copied to `<name>-backup.vsav` (never overwriting an earlier backup) before being rewritten in place.

  The refresh is **not reimplemented**: the engine's own `GameRefresher` does the work, unmodified, in a subprocess — `GameModule.init()` may be called only once per JVM, so it cannot run in the utility's own process. Each save's recorded **module version is updated** to the loaded module's, and two things the engine would otherwise rebuild from whatever is loaded are corrected: its record of **which extensions it was saved with**, and its **board layouts** — the engine writes one for every map that exists, so a scenario would otherwise carry layouts for maps belonging to extensions it never listed, and log "No such map" for anyone loading it with only its own extensions.

  Before starting, the tool checks that every extension any selected scenario names is active, and that the module's Piece Ids are sound — VASSAL refuses to refresh anything when two components share a Piece Id, but reports it only as *"module was saved with older vassal version"*. The utility names the colliding components instead, and stops before touching a file. See [docs/refresh-counters.md](docs/refresh-counters.md).

  The runner links against the VASSAL engine, so it is compiled only when the build is pointed at one (`make jar` finds an installed VASSAL automatically). Built without it, the menu item says so; the engine is never bundled, so the tool always drives the VASSAL the user has installed.

- **Download a module and its extensions from the game library.** **File → Download Module from Library…** takes a library page URL (or bare project name), asks where to put things, and fetches the module plus the newest copy of each extension into a correctly-named `<module>_ext` folder. Point it at a saved game instead and only the extensions that game names are fetched; any it names that the library does not publish are listed rather than silently skipped.

  "Newest" is decided **per file, not per release**, because a release is a batch upload and one extension can appear in several — in the WiF project `23-DoD-III.vmdx` is in releases 2.1.3, 2.1.2 and 2.1.1 while twenty-two others only ever appeared in 2.1.1, so taking the newest release alone would fetch two extensions and miss twenty-two. Where a project publishes more than one `.vmod` the dialog asks which, rather than guessing. Downloads are written to a temporary file, checked against the library's SHA-256 and then moved into place, so a failure or a cancel never leaves a truncated module.

  JSON is read by a small built-in parser, so the application gains no new dependency and still builds offline.

- **Add counters to a saved game** (`refresh/AddCountersRunner`). Byte-level editing can copy an existing piece and patch a trait or two, but an arbitrary counter needs its definition expanded with prototypes inlined exactly as VASSAL inlines them — so the engine builds each piece the way dragging one off the palette does, and drops it at an existing counter's coordinates so it merges into that counter's stack.

- **`tools/remove_placemark_carriers.py`** — deletes off-map pieces still carrying the embedded Place Marker that the WiF module dropped in 2.1.2. Refresh Counters cannot clear these: every carrier is on no map, and the engine builds its refresh list by walking map contents, so an off-map piece is never collected and never rebuilt.

- **`tools/remove_offmap_pieces.py`** — reports, and with `--apply` deletes, every piece that is on no map. Off-map does not by itself mean unwanted, so it reports by default; `--csv` writes an exact manifest of the pending deletion (scenario, counter, Piece Id, defining archive, container, position) for review in a spreadsheet, and `--keep-name` / `--only-name` let the decision be made per counter name.

- **`tools/dedupe_pieces.py`** — reduces counters present more than once to a single copy. `--extension` is required rather than optional: plenty of counters legitimately appear many times, so deduplicating blindly would destroy them.

- **`tools/fix_sif_subs.py --add`** — keeps the original counter and adds its twin into the same stack, rather than replacing it, for scenarios whose force pools are meant to hold one copy of every counter.

### Changed

- **Refresh Counters rebuilds a scenario's extension list instead of merely preserving it.** A saved game records which extensions were loaded when it was written, and that record goes stale: when a counter is moved into a different extension, the scenario ends up depending on an extension it never names, and VASSAL reports those pieces as unmatchable on load with no hint why. Every entry the scenario already had is still kept verbatim, version included, but an entry is now **added** for each extension that supplies a piece the scenario holds and is not already listed. The piece-to-extension mapping is by Piece Id, via the new `model/ExtensionIndex`, read from the module and its `_ext` archives.

  The rule is **additive only**. A scenario's dependencies cannot be inferred from its counters alone — an extension supplying only boards or charts has no piece definitions at all — so nothing is ever pruned; dropping a dependency is a deliberate act (`tools/remove_ext_counters.py --drop-listing`). See [docs/refresh-counters.md](docs/refresh-counters.md).

### Fixed

- **Downloading a module whose filename contains spaces failed with HTTP 400.** The library embeds the filename verbatim in the download URL, so the module's URL carried literal spaces; `HttpURLConnection` passes those into the request line and the object store rejects it. Extensions were unaffected only because their filenames happen to have no spaces, which made the fault look module-specific. Request paths are now percent-encoded, leaving any existing `%XX` escape untouched so an already-encoded URL is not double-encoded.

- **Refresh Counters refuses to run against a module that is not there.** `DataArchive` accepts a path that does not exist, and `GameModule.init()` then builds an empty *"Unnamed module v0.0"* with no piece definitions rather than reporting a problem. Every check downstream passes on such a module — the Piece Id check included — and the refresh then matches each piece against nothing, which does not fail: it strips every scenario it is pointed at. A mistyped or since-deleted module path was enough to reach it.

  The module file is now required to exist and be readable before the engine is started, and the module it builds is required to contain at least one piece definition afterwards (a build that fails partway leaves the same empty shell). Either way out stops the run before a single file is opened, so no backup is written and no scenario is altered. The count of piece definitions is now reported alongside the module version and extension count, so an implausibly small module is obvious in the log.

- **`tools/renumber_gpids.py` no longer refuses a slot whose definition omits its own Piece Id.** Some slots carry an empty field where the id would sit, because `PieceSlot.getPiece()` stamps the attribute onto the piece at creation, so the copy in the definition never mattered. The script required exactly two occurrences and gave up on such a slot; it now accepts the attribute plus *at most* one copy in the definition, still refusing if the number turns up anywhere else, and reports which slots were in that state.

## 1.0.12

Fixes corrupt output from Delete Excess Units and makes it much faster.

### Fixed

- **Delete Excess Units no longer produces an unreadable saved game.** The tidied `.vsav` was written directly to the destination path, so if the write was interrupted — e.g. the window was closed during the several-second save of a large game — a truncated file was left behind, and VASSAL rejected it with *"… is not a VASSAL saved game or log."* The tidied game is now written to a temporary file and atomically moved into place, so the destination only ever holds a complete file (the original is still never touched). The operation also runs behind a modal progress dialog so it is clearly working and cannot be closed mid-write, and deobfuscation/obfuscation now use bulk/chunked I/O — a ~465 MB game is rewritten in a few seconds instead of ~30.

## 1.0.11

Adds a tool for tidying saved games whose pieces no longer match the module's active extensions.

### Added

- **Excess Units.** A new toolbar button (and **Tools → Find Excess Units in Saved Game…**) loads a VASSAL saved game (`.vsav`) and reports the game pieces in it that are missing from the module (left panel) and its **active** extensions — the pieces that make VASSAL log *"Bad Data in Module … Image not found"* / *"No such map"* on load and *"Unable to match piece … by name"* on Refresh Counters. A piece is flagged only when it matches no active PieceSlot by **either** GPID **or** name (VASSAL's own "unmatchable" condition), so run-time markers and refresh-repairable pieces are never touched. Pieces recoverable from an *inactive* extension are shown greyed with that extension's name in braces (activating it is an alternative to deleting). **Delete Excess Units** removes all listed pieces and saves the tidied game under a **new** name, leaving the original file unchanged. The saved game is rewritten byte-exactly (surviving commands copied verbatim, metadata preserved). See [docs/vsav-excess-units.md](docs/vsav-excess-units.md).
- **Saved-game format documentation.** [docs/vsav-format.md](docs/vsav-format.md) documents the `.vsav` container, the `!VCSK` obfuscation, and the command-log grammar.

## 1.0.10

Adds a way to see and manage a module's extensions from within the utility.

### Added

- **Show Extensions.** A new toolbar button lists the extensions available for the module in the left panel — both **active** ones (the `*.vmdx` files in the module's `_ext` directory) and **deactivated** ones (those in the `_ext/inactive/` subdirectory, which VASSAL ignores). Entries are listed alphabetically; inactive ones are shown in grey with an "(Inactive)" marker. Selecting an extension lets you either **Activate/Deactivate** it — moving its `.vmdx` file into or out of the `inactive/` subdirectory — or **Edit Extension**, which opens it into the right panel (double-click does the same).

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
