# Refresh Counters over external saved games

**Tools → Refresh Counters in Saved Games…** runs VASSAL's own *Refresh Counters*
over any number of `.vsav` files, against the module loaded in the left panel.
It is the batch equivalent of the engine's **Tools → Refresh Counters**, but for
saved games that live outside the module rather than for the game currently
loaded in the player, or for the module's own predefined setups.

The refresh itself is **not reimplemented here**. The engine's
`GameRefresher`/`GpIdChecker` do the work, unmodified, in a subprocess.

## Why a subprocess

`GameModule.init()` throws `UnsupportedOperationException` if called a second
time, so a JVM can host exactly one module for its entire life. The utility opens
modules freely — and lets you swap the left panel between them — so it cannot
host the engine in its own process without a restart per module. Running the
engine out of process also keeps its player window, its preferences file and any
crash of it away from the utility.

`org.vassalengine.extutil.refresh.RefreshRunner` is the entry point. It is the
only class that links against VASSAL.

### Building it

VASSAL is not published to Maven Central, so the runner is compiled only when the
build is pointed at an engine jar:

```bash
./mvnw package -Dvassal.engine.jar=/usr/share/vassal/lib/Vengine.jar
make jar            # finds an installed engine and passes this for you
```

Without one the project still builds; the runner is simply left out and the menu
item reports that Refresh Counters is unavailable. The engine is a `system`-scope
dependency, so it is **not** bundled into the fat jar — the application drives
whatever VASSAL the user has installed, which is also the version their module
was authored with.

Profile activation keys off the property being *set*, not `<file><exists>`,
because a POM property cannot drive profile activation: Maven interpolates the
POM's default rather than a `-D` override, so the profile switched on even when
pointed at a jar that was not there.

### Only `Vengine.jar` goes on the classpath

Never `lib/*`. The engine jar's manifest `Class-Path` pulls in the other ~40 jars
relative to itself, which is how VASSAL launches itself. With a wildcard, another
jar's `images/` folder can precede the engine's on the classpath, and
`IconFactory` then fails to find VASSAL's icons — the run dies with
`IllegalStateException: Icon Family eye not found`. VASSAL's own source flags this
hazard as bug 9670.

### Engine bootstrap

The minimum that makes a module usable headlessly:

```java
Info.setConfig(new StandardConfig());   // else the save records VassalVersion 1.2.3
new HeadlessMenuManager();              // MenuManager is abstract; one must exist
GameModule.init(new GameModule(new DataArchive(modulePath)));
new ExtensionsLoader().addTo(GameModule.getGameModule());
Localization.getInstance().translate();
GameModule.getGameModule().getPrefs().setValue(SaveMetaData.PROMPT_LOG_COMMENT, false);
```

The player window is built but never shown. `Info.setConfig` is easy to miss and
fails quietly: without it `Info.getVersion()` returns the development placeholder
`1.2.3`, which is then written into every refreshed save's `savedata`. The prefs
line suppresses the "enter save comments" dialog, exactly as the engine's own
batch refresh (`GameState.saveGameRefresh`) does. AWT threads keep the JVM alive,
so the runner must `System.exit()`.

## Refusing to run against a module that is not there

`DataArchive` accepts a path that does not exist, and `GameModule.init()` then
builds an empty *"Unnamed module v0.0"* with no pieces in it rather than throwing.
Every check downstream passes vacuously on such a module, and the refresh matches
each piece against nothing — which does not fail, it strips every scenario. That
is the one way this tool can destroy data quietly, so it is guarded twice: the
module file must exist and be readable before `init()`, and the built module must
have at least one `PieceSlot` afterwards. Either way out is `!!FATAL` before a
single file is touched. The slot count is reported on `!!READY` so an implausibly
small module is visible in the log.

## Per scenario

Mirrors `PredefinedSetup.refreshWithStatus()`, but reads and writes an external
file instead of an entry inside the module:

1. capture the state the engine would otherwise rebuild — the extension
   registrations and the set of maps with board layouts (see below);
2. copy the original to `<name>-backup.vsav`;
3. `gs.setup(false)`, `setRefreshingSemaphore(true)`, `gs.setupRefresh()`;
4. `gs.loadGameInForeground(name, stream)` then `resolvePendingAttachments()`;
5. `new GameRefresher(mod).execute(options, null)`;
6. `gs.saveGame(tmp)`, `updateDone()`, `closeGame()`, then move `tmp` over the original;
7. reapply the captured state.

The save is written to a temp file beside the original and moved into place, so an
interrupted write cannot leave a truncated `.vsav` — which is what makes VASSAL
report *"… is not a VASSAL saved game or log."*

### Backups are never overwritten

`<name>.vsav` → `<name>-backup.vsav`, and if that exists `<name>-backup-2.vsav`,
`-3`, … A second run therefore never destroys the pristine original. When a
*folder* is selected, files matching `*-backup.vsav` / `*-backup-N.vsav` are
skipped, so the tool does not refresh its own backups. A backup named explicitly
as a file is still refreshed — that is taken as deliberate.

## The three things the engine gets wrong for this use

### Module version — wanted, and automatic

Each save records the module version it was made with, in its `savedata` entry.
`GameState.saveGame()` builds a fresh `SaveMetaData`, whose constructor calls
`setVersion(GameModule.getGameVersion())` — so the refreshed save is stamped with
the version of the module that performed the refresh. That is the desired update
and needs no help.

### Extension list — must be put back

A saved game also records which extensions were loaded when it was written, as
`EXT<TAB><name><TAB><version>` commands in the command log (`ExtensionsLoader
.COMMAND_PREFIX`; `name` is the `.vmdx` file name without its suffix). VASSAL
rebuilds that list from the **currently loaded** extensions on every save.

A batch refresh must have every extension active at once (see below), so a plain
re-save would widen every scenario's list to the full set — in the WiF module,
from a scenario's own 14 or 16 to all 24. That record is a statement about what
the scenario needs, so it is captured before the refresh and restored afterwards
by `SavedGame.restoreExtensionRegistrations()`, which drops the file's `EXT`
tokens and re-emits the originals at the same position. Every other command is
copied byte-for-byte; verified on a real refresh, only the `EXT` tokens differ.

### Surplus board layouts — must be stripped

The same cause, a different symptom. A map's whole layout lives in one
`<mapIdentifier>BoardPicker<TAB><board>[/rev]<TAB>…` command
(`BoardPicker.encode()`), and the engine writes one for **every map that exists**
— including the maps of extensions the scenario never listed. Refreshing the WiF
scenarios with all 24 extensions active added ten of them apiece: `DOD III PM`,
`DoD III Status Display`, `USED DODIII`, `ULDivs`, `3D10`, `3D10 Odds Chart`,
`MajP Chart 1`/`2`, `Allied`/`Axis Prod Circle`.

Harmless while every extension is active, but a scenario listing 11 extensions
carrying layouts for maps from extensions 12–24 will make VASSAL log "No such
map" for anyone who loads it with only the extensions it names.

So the set of maps that had a layout is captured beforehand, and afterwards any
`BoardPicker` command for a map outside that set is dropped. Layouts for maps the
scenario already had are left exactly as the refresh wrote them — this only
removes the surplus, it does not restore old layouts.

Detection matches on the command's **first `TAB`-delimited token** ending in
`BoardPicker`, not on searching the whole command: a map identifier may itself
contain a `/` (e.g. `China TRS/AMPH`), and piece data can mention "BoardPicker"
in passing. Commands starting with a piece-command prefix are excluded outright.

### Both are applied in one pass

`SavedGame.PreservedState.capture(game)` reads both before the refresh and
`restore(file)` reapplies them after, in a **single** rewrite — a 30 MB saved game
is deobfuscated and re-obfuscated once, not once per fix. It returns how many
extension registrations were rewritten and which maps' layouts were dropped, both
of which the runner reports as `!!PRESERVED` and the GUI shows per scenario. If
nothing differs, the file is not rewritten at all.

## Checks made before the engine is started

- **A module is loaded in the left panel**, has a file on disk, and has no unsaved
  changes (the engine reads it from disk, so pending edits would not apply).
- **Every extension every selected scenario names is active.** Refresh matches
  each piece against the definitions currently loaded, so a piece from an inactive
  extension is left unmatched — quietly, apart from a warning count. Because one
  run covers many scenarios, the union of everything they need must be active.
  Missing ones are listed, saying which are merely deactivated (in `_ext/inactive/`)
  and which are absent altogether.
- **The module's Piece Ids are sound** — see below.

## "Module was saved with older vassal version" really means duplicate GPIDs

`GameRefresher.execute()` builds a `GpIdChecker` over every `PieceSlot` and
prototype, and if `hasErrors()` it logs
`GameRefresher.gpid_error_message` — *"Unable to run Refresh, module was saved
with older vassal version. Edit and save module with latest vassal version
first."* — and **returns without refreshing anything**.

That message is misleading. `GpIdChecker.testGpId()` records an error slot when a
GPID is **empty**, **non-numeric**, or **already seen** — a duplicate. The VASSAL
version is not consulted anywhere in that decision.

Duplicates across extensions are easy to create: extensions generate fresh ids as
`<extensionId>:<n>`, but a slot copied from the module or another extension keeps
its plain numeric id, and `GpIdChecker` keys on the raw value when extensions are
loaded. Two extensions can then claim the same id.

The runner therefore asks the same question up front, with the same `GpIdChecker`,
and if it objects reports **which** slots collide and stops before touching a
single file. The engine keeps its error list private, so the names are recovered
by re-walking the slots — advisory detail attached to the engine's own verdict.

## Progress protocol

The runner writes `!!`-prefixed, tab-separated lines to stdout; anything else is
the engine's chatter and is echoed into the log pane.

| Line | Meaning |
|---|---|
| `!!READY <moduleVersion> <extensionCount> <slotCount>` | module built, extensions loaded |
| `!!BLOCKED <problem>` | GPID error; nothing will be refreshed |
| `!!FILE <n> <total> <name>` | starting a scenario |
| `!!BACKUP <name> <backupName>` | original copied |
| `!!LOG <text>` | a line the engine wrote to its chatter |
| `!!PRESERVED <name> <extCount> <strippedCount> <maps>` | extension list put back and/or surplus board layouts dropped |
| `!!OK <name> <warnings>` | refreshed; `warnings` is `GameRefresher.warnings()` |
| `!!FAIL <name> <message>` | this scenario failed; the batch continues |
| `!!SUMMARY <refreshed> <failed>` | finished |

`!!LOG` lines come from the chatter, which is where `GameRefresher.log()` and
`GpIdChecker.chat()` report — including the per-piece "unable to match" detail
that never reaches a return value. The runner tails the chatter's `JTextPane`
between files to pick it up.
