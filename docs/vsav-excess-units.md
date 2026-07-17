# Removing Excess Units from a Saved Game

When a VASSAL game is set up, the saved game (`.vsav`) records every game piece on
the board. If the game is later opened against a module whose **active** extensions
no longer provide some of those pieces — because a piece came from an extension that
has since been deactivated, or from an older version of the module — VASSAL cannot
resolve them. On load it logs, for each affected piece:

```
Bad Data in Module:  Source: PoHW T MOT Narew.png  Error: Image not found
Bad Data in Module:  Source: Allied Prod Circle    Error: No such map
```

and on **Tools → Refresh Counters** it logs:

```
Unable to match piece "germanyBPs" (GPID 10990) by name
```

The Extension Utility's **Excess Units…** tool (toolbar, or **Tools → Find Excess
Units in Saved Game…**) finds these orphaned pieces and can permanently remove them,
writing the tidied game to a **new** file and leaving the original in place.

> This is a companion to the file-format reference in
> [vsav-format.md](vsav-format.md); read that first for the ZIP container, the
> `!VCSK` obfuscation, and the command-log grammar. The logic below is implemented
> in `model/SavedGame` and `gui/MainWindow` (see **[../AGENTS.md](../AGENTS.md)** →
> *Excess units in a saved game*).

## What counts as "excess"

A saved piece is an `AddPiece` command `+/<id>/<type>/<state>` whose innermost trait
is a **BasicPiece**:

- **type** `piece;<cloneKey>;<deleteKey>;<image>;<name>` — the piece's image file and
  its (static) name;
- **state** `<map>;<x>;<y>;<gpid>;<nProps>;…` — its map, position and **GPID** (game
  piece id).

VASSAL matches a saved piece to a definition in the loaded module + active extensions
the same way **Refresh Counters** does (`GpIdChecker`):

1. **by GPID** — the piece's GPID against the `gpid` of every active `PieceSlot`;
2. **by name** (fallback) — the piece's innermost name against the innermost name of
   every active `PieceSlot`.

A piece is flagged as **excess only when *both* keys fail**: its GPID matches no
active slot **and** its name matches no active slot. That is precisely the condition
under which VASSAL prints *"Unable to match piece … by name"*.

Requiring both keys to fail is what makes deletion safe:

- A marker placed at run time gets a fresh GPID that is in no slot, but it keeps the
  **name** of the definition that created it — so it still matches by name and is
  **not** removed.
- A piece whose GPID still matches an active slot can be repaired by Refresh Counters
  — so it is **not** removed.

The piece's **image is not a detection key.** An orphaned piece is removed even if its
image happens to still exist in the module (as with the sample's `germanyBPs`, GPID
10990, whose image `GE BPs.png` is still present but whose definition is gone). Basing
the decision on the image alone would miss exactly the pieces that Refresh Counters
complains about. The image is shown in the report only as an identifier.

### The inactive-extension hint

If a flagged piece's GPID or name is found in one of the module's **inactive**
extensions (under `<module>_ext/inactive/`), that extension's name is shown in braces,
greyed:

```
Guards Armor  [guardsarmor.png]   {21-ULDIVs.vmdx (Inactive)}
```

This means the piece is not truly lost — **activating** that extension (see the *Show
Extensions* tool) is an alternative to deleting the piece. "Delete Excess Units"
removes every listed piece regardless; the hint just lets you decide to activate
instead for some of them.

### Scope

The tool removes **units** (`AddPiece` pieces with a BasicPiece). It does not remove
decks, stacks, global properties or map/board state. In practice removing the
orphaned units eliminates the large majority of the load-time and refresh-time errors;
a stray *"No such map"* from, say, an orphaned deck is out of scope.

## How the rewrite stays byte-exact

The deobfuscated command log is a **two-level** `SequenceEncoder` structure:

- top-level commands are separated by a bare **ESC** (`0x1B`);
- the pieces are grafted one level deeper and separated by an **escaped** `\<ESC>`.

VASSAL's decoder treats an ESC as a real delimiter unless the byte immediately before
it is a backslash. Because piece data never contains an ESC, the tool can split the
log at **every** ESC into command tokens, classify each delimiter by that one
preceding byte, and — crucially — **re-emit the identical delimiter bytes** to
reconstruct the nesting exactly.

To remove a piece, its token **and its preceding delimiter** are dropped; every
surviving token is copied **verbatim** (never decoded and re-encoded, so no escaping
or quoting can drift), and the whole stream is re-obfuscated with a fresh key. The
`moduledata` and `savedata` entries are copied byte-for-byte.

The result is the original saved game minus exactly the removed pieces. (Verified on
the sample files: the tidied game reopens with exactly *original − removed* commands,
re-scans to zero excess, keeps `begin_save … end_save` intact, and has byte-identical
metadata.)

### Safe, atomic writes

The tidied game is written to a **temporary file first and then moved into place**
(an atomic rename where the filesystem supports it). The destination therefore only
ever contains a complete file — if the save is interrupted (for example the
application is closed mid-write), the target is left untouched rather than truncated.
This matters because a large saved game takes a few seconds to rewrite; a truncated
file would fail to open in VASSAL with *"… is not a VASSAL saved game or log."* The
metadata entries are copied verbatim and the obfuscated stream is written in large
buffered chunks, so even a several-hundred-megabyte game is rewritten quickly.

## Using it

1. Open the module in the **left** panel.
2. Click **Excess Units…** (or **Tools → Find Excess Units in Saved Game…**) and
   choose the `.vsav`.
3. Review the list of excess units (greyed entries are recoverable by activating the
   named inactive extension).
4. Click **Delete Excess Units**, confirm, and choose a **new** file name (defaults to
   `<name> (tidied).vsav`). The original file is kept unchanged.

Because a saved game can be very large (hundreds of MB uncompressed), loading,
analysis and writing all run on a background thread behind a modal progress dialog;
the tidied file is written atomically (see above), so closing the window mid-operation
never leaves a corrupt file.
