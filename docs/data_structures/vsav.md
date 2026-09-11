# `.vsav` — the saved game

A saved game is a ZIP of three entries. Two are small XML metadata documents;
the third, `savedGame`, is an **obfuscated command log** — the entire game state
serialised as a flat list of commands.

**Diagram:** [diagrams/vsav-structure.html](diagrams/vsav-structure.html)

```
save.vsav  (ZIP)
├── savedGame     obfuscated command log          ← everything is here
├── savedata      this save's own metadata
└── moduledata    the module it was saved from
```

A `.vlog` (logfile) uses this **exact same container and obfuscation**. The
difference is in the log's content, not its structure.

## moduledata versus savedata

Both are the `AbstractMetaData` XML shape described in
[zip-container.md](zip-container.md#metadata-entries). They answer different
questions, and the sample save shows why both are needed:

```xml
<!-- moduledata: which module, as it was then -->
<version>0.86</version>
<name>Europa Series New Map</name>
<description>Fix Gamma</description>
<dateSaved>1737867127665</dateSaved>
```

The module on disk is now at version `0.90` with a different description. The
save still records `0.86` and *"Fix Gamma"*, because `moduledata` is a snapshot
of the module **taken at save time** — it is what lets VASSAL warn that a save
was made with a different version than the one now loaded, without opening the
module.

`savedata` carries the same fields minus `<name>`, describing the save itself.

## The obfuscation envelope

`savedGame` is not encrypted — it is XOR-ed with a single random byte, whose
only purpose is to stop a player from editing their own saved game in a text
editor. Three formats exist, told apart by the entry's leading magic bytes.

| Format | Magic | Key | Payload | Written by |
|---|---|---|---|---|
| `RAW` | `VOBS` | 1 raw byte | plaintext XOR key, raw | VASSAL 3.8+ |
| `HEX` | `!VCSK` | 2 hex digits | 2 hex digits per XOR-ed byte | through VASSAL 3.7.x |
| `HEX_DEFLATED` | `!VCSZ` | 2 hex digits | as `HEX`, but the plaintext is deflated first | a pre-release 3.8 branch; never shipped |

The layout of a `!VCSK` entry, from the sample save:

```
2156 4353 4b66 3539 3739 3039 3239 6339   !VCSKf59790929c9
 └─ header ─┘└k┘ └────── payload ──────
```

`!VCSK`, then the key `f5`, then two hex characters per plaintext byte.

**Why the format changed.** The hex encoding doubles the payload into a
16-symbol alphabet, which the ZIP's own deflate can do very little with. `VOBS`
XORs raw bytes and lets the ZIP layer compress normally. In the sample save:
7362 plaintext bytes become 14731 hex bytes, which deflate to 1693 — the same
plaintext written as `VOBS` would deflate far better because the compressor sees
the real byte distribution.

**A rewrite must preserve the format it read.** This project tracks the format
per file (`SavedGame.getObfuscation()`, and `read_vsav`'s third return value)
and re-emits the same one. Converting a save between formats would work — VASSAL
reads all three — but it would make a "nothing changed but the piece I removed"
claim untestable.

The key is **regenerated randomly on every write**, so two byte-identical
rewrites of the same file are not byte-identical on disk. Compare deobfuscated
plaintext, never raw entries.

## The command log

The plaintext is `GameModule.encode(GameState.getRestoreCommand())`. A VASSAL
`Command` is a linked list; `encode` serialises each node with its registered
`CommandEncoder` and joins the results with a `SequenceEncoder` whose delimiter
is **ESC (`0x1B`)**.

> The log is one long line. Its record boundaries are `0x1B` bytes, not newlines.

`GameState.getRestoreCommand()` builds the list in this order:

1. `begin_save` — a `SetupCommand(false)` marking the start;
2. version-check commands;
3. **one `AddPiece` per game piece**, ordered by map then visual layer;
4. each `GameComponent`'s own restore command — board layouts, zones, global
   properties, turn tracker, decks, registered extensions;
5. `end_save`.

### A real census

The sample `BlankScenario.vsav` deobfuscates to 7362 bytes in **31 commands**:

| Count | Command |
|---|---|
| 17 | `<map>BoardPicker` — one per map that exists |
| 4 | `+/` AddPiece |
| 2 | `NOTES` |
| 2 | `PNOTES` |
| 2 | (empty) |
| 1 | `TURNTurnTracker0` |
| 1 | `SETUP_STACK` |
| 1 | `begin_save` |
| 1 | `end_save` |

Note the ratio. In an empty scenario the board layouts outnumber the pieces four
to one — and there is **one `BoardPicker` per map that exists in the module**,
not per map in use. That is the structural reason a refresh run has to prune
surplus layouts: the engine rewrites the set from whatever is loaded, so a
scenario that listed 14 extensions comes back with layouts for all 27.

### The command catalogue

Piece commands come from `BasicCommandEncoder`, using `/` as the field
separator:

| Command | Prefix | Grammar |
|---|---|---|
| Add piece | `+/` | `+/<pieceId>/<type>/<state>` |
| Remove piece | `-/` | `-/<pieceId>` |
| Change piece | `D/` | `D/<pieceId>/<newState>[/<oldState>]` |
| Move piece | `M/` | `M/<pieceId>/<newMapId>/<x>/<y>/…` |

Component commands are tab-delimited and identified by their **first tab-token**:

| Command | First token | Payload |
|---|---|---|
| Board layout | `<mapIdentifier>BoardPicker` | `<board>[/rev]\t<col>\t<row>\t…` |
| Registered extension | `EXT` | `<name>\t<version>` |
| Global property | `GlobalProperty` | `;<name>;<value>;<container>` |
| Turn tracker | `TURN<name>` | tracker state |
| Notes | `NOTES` / `PNOTES` | shared and private notes |

Real examples from the sample:

```
WitDAxisGameChartBoardPicker	WitDAxisGameChart	0	0
+/1740988011519/stack/Weather;52;517;1740988011518;@@-1
```

The second is a **stack**: a piece in its own right whose state lists its members
by piece id, ending in a `@@<layer>` marker. This is why removing a piece never
requires stack surgery — the id left behind is dangling, and `Stack.setState()`
looks each member up and silently skips what it cannot resolve.

### Matching a `BoardPicker` correctly

A map identifier can itself contain a `/` (`China TRS/AMPH`), and piece data can
mention the word "BoardPicker" in passing. So the match is: take the command's
**first tab-token**, require it to end with `BoardPicker`, and exclude anything
starting with a piece-command prefix. Searching the whole command for the
substring gives false positives on real files.

### `EXT` — the recorded extension list

```
EXT	01-EURO-Maps	2.1
EXT	10-CoiF	2.1
```

`<name>` is the extension's file name without its `.vmdx` suffix — exactly
`ModuleExtension.getName()`, and exactly the active `.vmdx` files in the
module's `_ext` directory. Extensions under `_ext/inactive/` are not loaded and
do not appear.

The list is **rebuilt by the engine from whatever is currently loaded** on every
save. That is a hazard for batch processing: a run that needs every extension
active at once will widen a scenario's list from its own 14 to all 27 unless the
original is captured and restored. See
[docs/refresh-counters.md](../refresh-counters.md).

## Identity: how a save refers to a definition

A saved piece carries **no reference to the `PieceSlot` it came from** except a
number: the GPID, the 4th `;`-field of its innermost `BasicPiece` state. Its
name — the 5th field of the innermost type — is the only fallback.

That is the whole join between a `.vsav` and a `.vmod`/`.vmdx`, and it is why so
much of this project is about keeping GPIDs unique and resolvable. See
[game-piece.md](game-piece.md#identity).

## Parsing one yourself

```python
import sys; sys.path.insert(0, 'tools')
from swap_maps import read_vsav, split_commands
state, entries, fmt = read_vsav('SAVE.vsav')
print(fmt, len(state), 'bytes')
for i, (delim_start, content_start, end) in enumerate(split_commands(state)):
    print(i, state[content_start:end].decode('utf-8', 'replace')[:120])
```

Each token is returned as three offsets: where its **preceding delimiter** starts,
where its **content** starts, and where it ends. Keeping the delimiter range
separate is what lets a rewrite drop a token *and its delimiter* and re-emit
everything else unchanged.

## See also

- [game-piece.md](game-piece.md) — the structure of an `AddPiece`
- [sequence-encoder.md](sequence-encoder.md) — the encoding underneath
- [docs/vsav-excess-units.md](../vsav-excess-units.md) — the detection algorithm built on this
- [docs/tools/](../tools/README.md) — the tools that rewrite these files
