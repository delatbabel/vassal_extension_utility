# VASSAL Saved-Game File Format (.vsav)

A `.vsav` file is a VASSAL **saved game** — a snapshot of a game in progress. Like a
module (`.vmod`) or extension (`.vmdx`), it is a standard ZIP archive, but it holds a
different set of entries and its main payload is an *obfuscated command log* rather
than a `buildFile.xml` component tree.

> This document describes the format as VASSAL 3.7 reads and writes it. Everything
> below was traced from the VASSAL engine source (paths under
> `vassal-app/src/main/java/` in the sibling `../vassal` checkout) and verified
> against a real save produced by the *WiF CE Official Combo* module. The Extension
> Utility itself does not read or write `.vsav` command logs — it only copies the
> bundled `.vsav` of a moved/copied `PredefinedSetup` verbatim (see
> [Relationship to modules and extensions](#relationship-to-modules-and-extensions)) —
> so this is reference material, not a description of code in this repo.

## Archive Contents

A `.vsav` is a ZIP archive (DEFLATE-compressed entries) containing exactly **three**
entries:

| Entry | Kind | Description |
|---|---|---|
| `moduledata` | XML | A copy of the parent module's metadata — used to match the save to the module that created it |
| `savedata` | XML | Metadata about the save itself (save comment, timestamp, versions) |
| `savedGame` | Obfuscated text | The game state, serialized as an obfuscated VASSAL command log |

The ZIP is written by `VASSAL.tools.io.ZipWriter` from `GameState.saveGame(File)`
(`VASSAL/build/module/GameState.java`). The three entry names are engine constants:

| Entry name | Constant | Location |
|---|---|---|
| `savedGame` | `GameState.SAVEFILE_ZIP_ENTRY` | `GameState.java:1264` |
| `savedata` | `SaveMetaData.ZIP_ENTRY_NAME` | `metadata/SaveMetaData.java:66` |
| `moduledata` | `ModuleMetaData.ZIP_ENTRY_NAME` | `metadata/ModuleMetaData.java:51` |

The same three-entry layout is also written by `GameState.saveGameRefresh(ZipArchive)`
(the editor's "refresh" path).

### Example (from a real save)

```
$ unzip -l "004-39-SO-allies-i2-211.vsav"
   Length      Date    Time    Name
---------  ---------- -----   ----
465352947  2026-07-17 17:01   savedGame     <- the game state (obfuscated); can be huge
      212  2026-07-17 17:01   savedata
      350  2026-07-17 17:01   moduledata
```

## moduledata

XML metadata describing the **module** the save belongs to. It is a verbatim copy of
the module's own `moduledata` entry (VASSAL copies it out of the loaded module archive
rather than regenerating it, so translations are preserved —
`AbstractMetaData.copyModuleMetadata`, `metadata/AbstractMetaData.java:296`). Written
by `VASSAL.build.module.metadata.ModuleMetaData`.

```xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<data version="1">
  <version>2.1.1</version>
  <extra1>With 2024 reprint maps and counters</extra1>
  <extra2/>
  <VassalVersion>3.7.24</VassalVersion>
  <dateSaved>1784279779272</dateSaved>
  <description>WiF CE Official 2024 Reprint</description>
  <name>WiF CE Official Combo</name>
</data>
```

| Field | Description |
|---|---|
| `data version` | Structure version of the metadata format (`ModuleMetaData.DATA_VERSION = "1"`) |
| `version` | The **module's** version string (`GameModule.getGameVersion()`) |
| `extra1` / `extra2` | Free-form module fields (`GameModule.getModuleOther1/2()`) |
| `VassalVersion` | VASSAL engine version recorded when the module was last saved |
| `dateSaved` | Epoch milliseconds (`System.currentTimeMillis()`); here `1784279779272` = 2026-07-17 09:16:19 UTC |
| `description` | The module's description |
| `name` | The **module name** — the element unique to `moduledata` (see below) |

## savedata

XML metadata describing the **save** itself. Written by
`VASSAL.build.module.metadata.SaveMetaData`.

```xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<data version="1">
  <version>2.1.1</version>
  <extra1/>
  <extra2/>
  <VassalVersion>3.7.24</VassalVersion>
  <dateSaved>1784282474959</dateSaved>
</data>
```

| Field | Description |
|---|---|
| `data version` | Structure version (`SaveMetaData.DATA_VERSION = "1"`) |
| `version` | The module/game version at the moment of saving (`GameModule.getGameVersion()`) |
| `extra1` / `extra2` | Free-form fields (empty for saves) |
| `VassalVersion` | VASSAL engine version that wrote the save |
| `dateSaved` | Epoch milliseconds when the save was written; here `1784282474959` = 2026-07-17 10:01:14 UTC |
| `description` | *(optional)* The user's save comment. Absent above because the game was saved without a comment; when present it appears as a `<description>` element, with per-language `<description lang="xx">…` variants for translations. |

### moduledata vs savedata

Both share the common `<data version="1">` skeleton (`version`, `extra1`, `extra2`,
`VassalVersion`, `dateSaved`, optional `description`) defined by `AbstractMetaData`.
The differences:

- `moduledata` has an extra **`<name>`** element (the module name); `savedata` does
  not. `<name>` is emitted by `ModuleMetaData.addElements()`
  (`AbstractMetaData.java:98`, `ModuleMetaData.java:99`).
- The fields *mean* different things: in `moduledata` they describe the module
  (module name/version/description); in `savedata` they describe the save event (save
  comment, the versions in effect, the save timestamp).

### Why the module's metadata is embedded

Before loading, `GameState.isSaveMetaDataValid(File)` (`GameState.java:632`) reads the
`savedata`/`moduledata` **without** decoding the game state, and compares the embedded
module name/version/VASSAL-version against the currently-loaded module:

- **Module name mismatch** → a confirm dialog; the user can abort the load.
- **Module version / VASSAL version mismatch** → a non-blocking warning in the chat
  window.

This lets VASSAL confirm a save belongs to the module (and version) that created it
without needing the original module file at hand.

## savedGame

The `savedGame` entry holds the actual game state: a single serialized VASSAL
**command log**, UTF-8 encoded, then passed through VASSAL's obfuscation filter. It is
usually by far the largest entry (hundreds of MB is possible for a large game — the
example above is ~465 MB).

### Obfuscation (`!VCSK`)

The payload is wrapped by `VASSAL.tools.io.ObfuscatingOutputStream` (read back by
`DeobfuscatingInputStream`). This is *obfuscation, not encryption* — its only purpose
is to discourage casual hand-editing (cheating). The scheme:

```
!VCSK <KK> <PP><PP><PP>...
└──┬─┘ └┬─┘ └────┬──────┘
 header key   payload
```

1. The literal ASCII header **`!VCSK`** (5 bytes).
2. A single random **key byte**, written as **two lowercase hex characters** (`<KK>`).
   A fresh random key (0–255) is chosen for each save.
3. The plaintext (the UTF-8 command-log string), byte by byte: each byte is
   **XOR-ed with the key** and written as **two lowercase hex characters**.

So the whole entry is 7-bit ASCII. Total length is `5 + 2 + 2·N` for an `N`-byte
plaintext. `DeobfuscatingInputStream` reverses it (and, for backward compatibility,
passes the stream through unchanged if the first five bytes are not `!VCSK`). Its
`unhex` accepts upper- or lower-case hex on read, though the writer only ever emits
lowercase.

Relevant source: `ObfuscatingOutputStream.java:38` (`HEADER = "!VCSK"`), `:62-88`
(header, key, per-byte XOR + hex).

**Decoding one, by hand.** Take the start of the real sample's `savedGame`:

```
!VCSK 81 e3 e4 e6 e8 ef de f2 e0 f7 e4 ...
      └key=0x81
```

XOR each subsequent byte with `0x81`: `0xe3^0x81 = 0x62 = 'b'`, `0xe4^0x81 = 'e'`,
`0xe6^0x81 = 'g'`, `0xe8^0x81 = 'i'`, `0xef^0x81 = 'n'`, `0xde^0x81 = '_'`,
`0xf2^0x81 = 's'`, `0xe0^0x81 = 'a'`, `0xf7^0x81 = 'v'`, `0xe4^0x81 = 'e'` →
**`begin_save`**.

A throwaway decoder (not part of the utility):

```python
data = open("savedGame", "rb").read()          # the raw ZIP entry
assert data[:5] == b"!VCSK"
key = int(data[5:7], 16)
hexpayload = data[7:]
plaintext = bytes(int(hexpayload[i:i+2], 16) ^ key
                  for i in range(0, len(hexpayload), 2)).decode("utf-8")
```

### The deobfuscated command log

The plaintext is `GameModule.encode(GameState.getRestoreCommand())`
(`GameState.saveString()`). A VASSAL `Command` is a linked list of sub-commands;
`GameModule.encode` serializes each one with its registered `CommandEncoder` and joins
them with a `SequenceEncoder` whose **delimiter is the ESC character `0x1B`**
(`GameModule.COMMAND_SEPARATOR = KeyEvent.VK_ESCAPE`, `GameModule.java:232`).

> **Commands are separated by `0x1B` (ESC), not by newlines.** The log is one long
> line to the naked eye; the record boundaries are ESC bytes.

The restore command list, in the order `GameState.getRestoreCommand()`
(`GameState.java:1273`) builds it:

1. **`begin_save`** — a `SetupCommand(false)` marking the start
   (`GameState.BEGIN_SAVE`, `:1328`).
2. Version-check commands (module/VASSAL version mismatch alerts).
3. **One `AddPiece` command per game piece**, ordered by map then visual layer.
4. Each `GameComponent`'s own restore command (map/board state, zones, global
   properties, turn tracker, decks, registered extensions, …).
5. **`end_save`** — a `SetupCommand(true)` marking the end (`GameState.END_SAVE`,
   `:1329`).

So the log is bracketed `begin_save` … `end_save`.

#### AddPiece and other piece commands

Piece commands are encoded by `VASSAL.build.module.BasicCommandEncoder`
(`BasicCommandEncoder.java:323`), using `/` as the field separator (`PARAM_SEPARATOR`):

| Command | Prefix | Grammar |
|---|---|---|
| Add piece | `+/` | `+/<pieceId>/<type>/<state>` |
| Remove piece | `-/` | `-/<pieceId>` |
| Change piece | `D/` | `D/<pieceId>/<newState>[/<oldState>]` |
| Move piece | `M/` | `M/<pieceId>/<newMapId>/<x>/<y>/…` |

- `<type>` is the piece's static definition (`GamePiece.getType()` — its trait/decorator
  stack), and `<state>` is its mutable state (`GamePiece.getState()`). Both are
  themselves `SequenceEncoder` strings, typically using a **tab (`\t`)** or `;` / `,`
  as their inner delimiter.

An example `AddPiece` from the sample (a Deck), with the leading `0x1B` separator shown
as `<ESC>`:

```
<ESC>+/1767760260803/deck;false;0,0,0;140;140;Never;Never;true;true;false;;;;ULDIV Return;
;;;false;false;;;10;;false;;false;;$BasicName$;;false;;Shuffle;Reverse;;Draw multiple
cards;Draw specific cards;Face up;Face down;;;;;Save Deck;;Deck Saved;Load Deck;;Deck
Loaded;false;/null;526;710;false
```

Here `1767760260803` is the piece id, `deck;false;0,0,0;…;false` is the type
(`;`-delimited deck definition), and after the `/null` the trailing `526;710;false` is
the state.

#### Registered-extension commands (`EXT`)

Near the end of the log (before `end_save`), VASSAL records **each active extension**
that was loaded when the game was saved, so the same extensions can be verified on
load. These come from `ExtensionsLoader` (`ExtensionsLoader.COMMAND_PREFIX = "EXT\t"`,
`ExtensionsLoader.java:44`), tab-delimited:

```
EXT<TAB><extensionName><TAB><extensionVersion>
```

From the sample (each preceded by an ESC separator):

```
EXT	01-EURO-Maps	2.1
EXT	02-APAC-Maps	2.1
EXT	03-Americas-AmiF-Map	2.1
EXT	09-SiF	2.1
EXT	10-CoiF	2.1
EXT	11-CVPs	2.1
EXT	12-CLs	2.1
EXT	13-DiF	2.1
EXT	14-TiF	2.1
EXT	15-PiF	2.1
EXT	17-Production-FiF	2.1
```

These names correspond exactly to the **active** `.vmdx` files in the module's `_ext`
directory (the extensions in the `_ext/inactive/` subdirectory are *not* loaded and do
not appear). This makes the command log a faithful record of the module+extension set a
save requires — relevant to this utility's *Show Extensions* feature, which
activates/deactivates those same `.vmdx` files.

#### SequenceEncoder escaping (why nesting is safe)

Because the same `SequenceEncoder` mechanism is nested at three levels — commands
(delim `0x1B`), piece fields (delim `/`), and type/state internals (delim `\t`/`;`/`,`)
— literal delimiter characters inside a value must be escaped so they are not mistaken
for separators (`VASSAL/tools/SequenceEncoder.java`):

- A literal delimiter inside a token is prefixed with a backslash `\`.
- If a token *starts* with `\` (or is already wrapped in single quotes `'…'`), the
  whole token is wrapped in single quotes so the decoder can unquote it.

For example, encoding `{A, {B, C}}` with delimiter `,` yields `A,B\,C`. This is the same
encoder used for `.vmdx` `ExtensionElement` target paths (see
[docs/vmdx-format.md](vmdx-format.md)).

### `.vsav` vs `.vlog` (saved game vs logfile)

A `.vsav` and a `.vlog` share this exact container and obfuscation. The difference is in
the `savedGame` command log: a plain saved game contains only the restore block
(`begin_save … end_save`). A **logfile** additionally contains a sequence of logged
moves appended after the restore block — `LogCommand`s and `UndoCommand`s from
`BasicLogger`, prefixed `LOG\t` / `UNDO\t` and bracketed by `begin_log` / `end_log`
(`BasicLogger.java:81-84`). A pure save generally has no `LOG` commands.

## Relationship to modules and extensions

A `.vsav` is not standalone: loading it requires the module named in its `moduledata`
(and, in practice, the extensions listed by its `EXT` commands). Within this project a
`.vsav` shows up in two ways:

- **A bundled scenario / Pre-defined setup.** A `PredefinedSetup` component with
  `useFile="true"` names a `.vsav` stored as a root-level entry inside a `.vmod` or
  `.vmdx` (see [docs/vmod-format.md](vmod-format.md) and
  [docs/vmdx-format.md](vmdx-format.md)). When the Extension Utility moves or copies
  such a setup, it copies that `.vsav` entry verbatim into the destination archive
  (and, on a Move, prunes it from the source when no remaining setup references it). The
  utility never parses the command log — it treats the `.vsav` as opaque bytes.

- **A standalone save file** (like the sample here), which lives on disk next to the
  module. The utility's **Excess Units…** tool reads such a save to find and remove
  game pieces whose definitions are missing from the module's active extensions —
  see [vsav-excess-units.md](vsav-excess-units.md) for that feature and the detection
  algorithm.

## Quick reference of constants

| Item | Value | Location |
|---|---|---|
| Container | ZIP (DEFLATE), 3 entries | `ZipWriter.java`; `GameState.saveGame` |
| Entry — game state | `savedGame` | `GameState.java:1264` |
| Entry — save meta | `savedata` | `SaveMetaData.java:66` |
| Entry — module meta | `moduledata` | `ModuleMetaData.java:51` |
| Obfuscation header | `!VCSK` | `ObfuscatingOutputStream.java:38` |
| Obfuscation | header + 2-hex key + (2-hex-per-byte, each byte XOR key), lowercase | `ObfuscatingOutputStream.java:62-88` |
| Payload charset | UTF-8 | `GameState.java:1374, 1635` |
| Command separator | `0x1B` (ESC, `KeyEvent.VK_ESCAPE`) | `GameModule.java:232` |
| Save-block markers | `begin_save` / `end_save` | `GameState.java:1328-1329` |
| Piece field separator | `/` | `BasicCommandEncoder.java:323` |
| Piece commands | `+/` add, `-/` remove, `D/` change, `M/` move | `BasicCommandEncoder.java:324-327` |
| Registered extension | `EXT\t<name>\t<version>` | `ExtensionsLoader.java:44` |
| Log markers (`.vlog`) | `begin_log`/`end_log`, `LOG\t`, `UNDO\t` | `BasicLogger.java:81-84` |
| Metadata root | `<data version="1">` | `AbstractMetaData.java:90, 208` |
| Common meta elements | `version`, `extra1`, `extra2`, `VassalVersion`, `dateSaved`, `description` | `AbstractMetaData.java:91-99` |
| `moduledata`-only element | `name` | `ModuleMetaData.java:99` |
| `dateSaved` format | epoch milliseconds | `AbstractMetaData.java:235` |
