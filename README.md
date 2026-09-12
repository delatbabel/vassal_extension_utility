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
10. Download a module and its extensions from the VASSAL game library (**File → Download Module from Library…**) — paste the library page URL, choose a folder, and the newest copy of each extension is fetched into a correctly-named `<module>_ext` folder. Optionally point it at a saved game to fetch only the extensions that game needs.
11. Run VASSAL's own **Refresh Counters** over any number of external saved games (**Tools → Refresh Counters in Saved Games…**), updating each piece to the module's current definitions. The engine does the work in a subprocess; each save is backed up first.
12. Find and remove "excess" game pieces from a saved game (`.vsav`) — pieces missing from the module's active extensions that otherwise cause *"Image not found"* / *"No such map"* on load and *"Unable to match piece … by name"* on Refresh Counters (**Excess Units…**). The tidied game is written to a new file, leaving the original unchanged.
13. Swap the maps of one saved game for another's (**Tools → Swap Maps Between Saved Games…**) — every board layout is taken from a second saved game, leaving the first game's pieces and everything else exactly as they were. The result is written to a new file; neither original is changed.

## Developing

Building from source, packaging installable releases for Linux/Windows/macOS,
the file-format documentation, and the rest of the technical documentation are
covered in the **[Developer's Guide](DEVELOPERS-GUIDE.md)**.

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

## Downloading a module from the library

**File → Download Module from Library…** fetches a module and its extensions from the [VASSAL game library](https://vassalengine.org/library/projects), placing everything where this utility and VASSAL expect to find it. The flow is a short sequence of prompts:

1. **Name the project.** Paste the module's library page URL (`https://vassalengine.org/library/projects/Project_Name`) or type just the project name. If you do not know it, find the module with the search dialog on the [library page](https://vassalengine.org/library/projects) and copy the address of its project page.
2. **Choose the module**, if the project publishes more than one `.vmod` file (some projects also publish placeholders or older editions). The list is sorted newest first.
3. **Choose the folder to download into.** The folder is tested for writability *before* anything is downloaded — a folder under **Program Files** (or any other protected location) needs administrator rights, which this application does not run with, so choose a folder you own, such as somewhere under **Documents**. If the test fails you are told why and asked for another folder.
4. **Choose which extensions to fetch**, if the project publishes any:
   - **All of them** — the usual choice for a first download; or
   - **Only what one saved game needs** — point the dialog at a `.vsav` file and only the extensions that game lists are fetched. Extensions the game needs but the library does not publish are listed for confirmation rather than silently skipped, so you know what to obtain elsewhere.
5. **Confirm.** The dialog shows what will be downloaded and the total size, then fetches everything with a progress display.

Where things land: the module goes into the chosen folder, and the extensions into a sibling `<module>_ext/` folder named after the module file (e.g. `MyGame.vmod` → `MyGame_ext/`) — the same convention VASSAL itself loads extensions from, so the module is ready to play or open in this utility immediately. When the same extension appears in several library releases, the newest copy of each file is chosen. Every download is verified against the library's SHA-256 checksum and written via a temporary file, so a failed or cancelled download never leaves a truncated module behind.

Afterwards, open the module with **File → Open Module (left)** and manage its extensions with **Show Extensions**. If a download fails, the closing dialog names the first few reasons, and the full detail is in `~/.vassal-extension-utility/extension-utility.log`.

## Swapping the maps of a saved game

**Tools → Swap Maps Between Saved Games…** gives a scenario a different set of boards: it copies **every** board layout from a second saved game into the first one, and writes the result to a new file. Both originals are left exactly as they are, and no module needs to be open.

This is what you want when a scenario was set up on one edition of the maps and you would rather play it on another — the deluxe boards instead of the classic ones, say, or a map an extension adds. Choose three files:

1. **Scenario to keep** — the saved game whose pieces, extensions, decks and everything else you want.
2. **Take maps from** — the saved game whose board layout you want instead.
3. **Save result as** — the new file to write. It is proposed for you as `<scenario> (swapped maps).vsav`, and it cannot be either of the other two.

A map's whole layout — which boards, where, and which way round — is a single entry in a saved game, so the swap is an exact substitution: every other byte of the scenario you are keeping is copied unchanged, and the file stays in whichever format VASSAL wrote it.

One thing to know: **pieces do not move with their board.** A piece's position is recorded in map coordinates, not relative to the board it stands on, so if the new layout puts a board somewhere else, the pieces that were on it stay at the old coordinates — nothing is lost, but they may end up off the board or off the map. This matters when the two layouts differ in how the boards are arranged, not merely in which images they use.

Before anything is written you are shown what will happen. Maps whose layout actually changes are listed in normal text; maps that are already identical, and maps only one of the two games has, are greyed and explained. Maps are matched **by name** — if the two saved games have no map names in common they are almost certainly from different modules, and you are told so rather than given a nonsensical result.

## Logs

Both live in `~/.vassal-extension-utility/`:

- `extension-utility.log` — the application log (rolling, 4 MB × 4).
- `refresh-counters.log` — the full transcript of the last **Refresh Counters**
  run, including everything the VASSAL engine itself printed. This is the file to
  look at if a refresh does not do what you expected; the dialog that closes each
  run names its path.

## Changelog

- [CHANGES.md](CHANGES.md) — release-by-release history of changes
