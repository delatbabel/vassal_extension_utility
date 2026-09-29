# Streaming the saved-game command log — `feature/stream-save-and-load`

Implements item **B1** of [wif-engine-optimizations.md](wif-engine-optimizations.md#b1-stream-the-saveload-instead-of-building-a-222-mb-string)
on branch `feature/stream-save-and-load` in `../vassal`. The file format is unchanged; what
changes is that the command log of a saved game or log file is never assembled as one Java
`String` on the way out or on the way in, and is no longer retained for the session.

---

## 1. What was wrong

For the fully set-up WiF game (8 855 pieces) the command log is 223 MB of text, which as a
Java `String` is ~446 MB of UTF-16. On `master`:

- **Save.** `GameState.saveString()` built it by recursive `GameModule.encode()` — each
  level building the whole text of the levels beneath it and re-escaping it — then
  `save.getBytes(UTF_8)` made a second full copy to write, and `lastSave = save` kept the
  `String` for the rest of the session so that `isModified()` could `equals()` it against a
  freshly built one (`GameState.java:1364-1377`, `:292-294`).
- **Load.** `decodeSavedGame()` read the whole entry with `IOUtils.toString()` and handed the
  `String` to `GameModule.decode()`, which split it level by level, each level a fresh
  substring of most of the text, every token interned (`:1627-1644`). The background loader
  already caught `OutOfMemoryError` at exactly this point.
- **Log files.** `BasicLogger.write()` did the same for a `.vlog`, whose text begins with the
  whole game state.

Both FIXMEs the developers left there say what to do: write straight to the stream, decode
straight from it.

## 2. The text form, and why streaming it needs care

`GameModule.encode(Command)` writes a command tree as a `SequenceEncoder` sequence with the
`ESC` delimiter: the command's own text (from whichever `CommandEncoder` recognises it) as
the first token, then the text form of each sub-command as a further token. A sub-command's
text is itself such a sequence, escaped as *one* token, so a delimiter inside a command
nested *d* levels deep carries *d* backslashes, and `SequenceEncoder.append()`'s quoting rule
(wrap in `'…'` if the token starts with `\` or both starts and ends with `'`) applies to the
*whole* text of a sub-command as seen by its parent. A saved game is the tree

```
begin_save  ESC  <version check ESC alert>  ESC  ""\ESC+/piece 1\ESC+/piece 2 …  ESC  <components>  ESC  end_save
```

with the pieces two levels down under a `NullCommand` whose own text is empty. Two
consequences shape the implementation:

- Writing a node requires knowing, before its first character, whether its *entire* text
  will need quoting — which depends on its last character, i.e. on the last leaf of its
  rightmost line of descent. The writer finds that by walking that line (never building
  text) and caches the own texts it had to compute so nothing is encoded twice.
- Reading has to reproduce `decode(String)`'s rule that the first token is the command's own
  text *unless* it was the whole text untouched by unescaping or unquoting, in which case it
  is the command; and its rule that a quoted token can only be recognised at its end.

## 3. What the branch does

| File | Change |
|---|---|
| `VASSAL/command/CommandSerializer.java` (new) | `write(Command, ownEncoder, delim, Writer)` and `read(Reader, stringDecoder, ownDecoder, delim)`: the streaming forms of `GameModule.encode`/`decode`, byte-for-byte and tree-for-tree equivalent to them. The reader is a stack of per-level token filters over one buffered character source; only one command's own text is ever held. |
| `VASSAL/build/GameModule.java` | `encode(Command, Writer)` and `decode(Reader)`, delegating to the serializer with the module's private `encodeSubCommand`/`decodeSubCommand`. The String forms are unchanged and still used for network traffic and single commands. |
| `VASSAL/build/module/GameState.java` | `saveGame(File)` and `saveGameRefresh()` encode straight into the obfuscating stream. `decodeSavedGame()` decodes straight from the deobfuscating stream. `isModified()` compares a **SHA-256 digest** of the streamed encoding with the one recorded at the last save (`saveDigest()`), instead of building and retaining the text; `lastSave` is deprecated and always null. New `writeGameFile(File, Command, SaveMetaData)` writes the log entry and metadata **through a temporary file** that replaces the target only when complete, and returns the digest. |
| `VASSAL/build/module/BasicLogger.java` | `write()` uses `writeGameFile` for the `.vlog`. |
| `VASSAL/tools/io/ObfuscatingOutputStream.java` | `write(byte[],off,len)` XORs a block and writes it once, instead of one `write(int)` per byte. |
| `VASSAL/command/CommandSerializerTest.java` (new) | See §5. |

**Why the temporary file.** `ZipWriter` opens its target with `TRUNCATE_EXISTING`. On
`master` the whole text was built *before* the file was opened, so a `CommandEncoder`
failing part-way (a `RuntimeException` in module code) left the old save intact. With
streaming the file would already be open, so the write goes to `<name>.<hex>.tmp` beside it
and is moved into place (atomically where the filesystem allows) only after the metadata
entries are written too. This also protects against a full disk, which `master` did not.

**Why a digest.** `isModified()` needs "is the state different from what was last saved".
Comparing SHA-256 of the encoded text is as exact as comparing the text for any practical
purpose and costs nothing to keep. It is computed in the same pass as the save (a
`DigestOutputStream` sits between the writer and the obfuscator, so it hashes the plaintext).

**What is unchanged.** The bytes on disk: two engines given the same loaded game wrote
byte-identical 223 MB command logs (§4). The `String` API: `encode(Command)`,
`decode(String)`, `saveString()` all remain. The obfuscation, the ZIP layout, the metadata.

## 4. Measured

Harness: bootstrap the engine headlessly (as this utility's Refresh Counters runner does),
load `002-presetup-aif-everything` (34 MB `.vsav`, 8 855 pieces, 223 MB log) with
`loadGameInForeground`, then `saveGame`, then `isModified()`; three iterations; a sampler
thread records peak used heap. Same JVM (OpenJDK 25), same module and extensions, unmodified
`master` built from the same source.

| 8 GB heap | `master` | branch |
|---|---:|---:|
| Load (decode and execute) | 133 s | **136 s** |
| Peak heap during load | 3 220 MB | **1 200 MB** |
| Heap after load, settled | 344 MB | 344 MB |
| Save | 35 s | **28 s** |
| Peak heap during save | 2 670 MB | **1 250 MB** |
| `isModified()` | 15.7 s | **14.7 s** |

- Load time is dominated by constructing 8 855 pieces of ~200 traits each (BeanShell
  expressions, key strokes, images), not by the text; streaming only removes the text's share
  and the per-level substring/intern work, and adds a per-character filter per nesting level.
  The first cut was 7 s slower; a block-buffered character source brought it to within ~2 %.
- Save is 20 % faster: one pass, no 446 MB `String`, no second `getBytes` copy, and the
  obfuscator's per-byte `write(int)` is gone.
- Peak heap during load and save falls by roughly two thirds. What remains during load is the
  `Command` tree itself (an `AddPiece` per piece, each holding its piece and its state text)
  until it executes; that is inherent to "decode, then execute".
- The two engines' output logs are **byte-identical** (`cmp` of the deobfuscated entries).

| 1.5 GB heap | `master` | branch |
|---|---:|---:|
| Load | **`OutOfMemoryError: Java heap space`** in `decodeSavedGame` | 136 s, peak 665 MB |
| Save | – | 29 s, peak 666 MB |

With the heap capped at 1.5 GB — less than the 3.2 GB `master` needs — the unmodified engine
cannot open the game at all, while the branch opens and saves it at the same speed as with
8 GB, the collector simply keeping the transient garbage within the smaller budget. That is
the practical ceiling B1 set out to remove.

## 5. Tests

`CommandSerializerTest` reproduces the two private `GameModule` algorithms as references and
checks every case four ways: stream-written text equals String-written text; stream-read tree
equals String-read tree; each reader reads the other writer's output. Cases: a lone command;
a null root; a saved-game-shaped tree; sub-commands that encode to null (skipped, no
delimiter) beside empty texts (kept as empty tokens); delimiters inside texts at three depths;
every branch of the quoting rule (leading backslash, quote at both ends, quoted own text with
an unquoted whole, quoting at the root, nested quoting); a `LOG` command whose own text embeds
another tree's encoding; and 3 000 random trees over the alphabet `a b ESC \ '` with null and
empty texts, four levels deep. Full `vassal-app` suite: 770 tests, the only failure being `ProcessCallableTest.testNormal`,
which fails identically on unmodified `master` in this environment (a `_JAVA_OPTIONS` banner on a
child process's output). Checkstyle, PMD and SpotBugs report nothing new.

## 6. Review question: why not a "changed" flag instead of the digest?

A reviewer of the branch asked whether `isModified()` could keep a boolean marking a changed
state rather than the old `String` or the new digest, recalling having concluded around 2010
that it would not work but not why. The answer given (posted on the PR):

> A "changed" boolean was the first thing I considered too, and I think the reason it does not
> work is the same in 2026 as in 2010: `isModified()` has to answer "would the file I write now
> differ from the one I wrote last?", and only the encoded state itself can answer that without
> false negatives.
>
> **What a flag would have to observe.** The restore command is assembled from the pieces plus
> 36 `GameComponent`s that implement `getRestoreCommand()` (maps and their `BoardPicker`, decks,
> global properties, turn tracker, player roster, notes, chess clocks, scenario options, …).
> Commands reach the state through three entry points (`GameModule.sendAndLog`, `CommandDecoder`
> for the server, `BasicLogger` for step and undo), so `Command.execute()` would be the natural
> hook — but not every change to that state is a `Command`. `BoardPicker.getRestoreCommand()`
> encodes `currentBoards`, which the setup dialog sets directly; several components keep their
> restore state in their own fields and mutate them locally, and custom module classes do the
> same. Each such path is a way for the flag to stay `false` while the state has changed, and the
> consequence of a false negative here is that the close-game prompt is skipped and the player
> loses the game. The string comparison, and now the digest, cannot be wrong in that direction:
> they compare what would actually be written.
>
> **A flag is also wrong in the other direction.** Undo, a log stepped forward and back, or a
> command whose effect is nil all leave the state equal to the saved one; the comparison says
> "unchanged", a flag set in `execute()` says "changed" and prompts for a save that isn't needed.
> Harmless, but it is a regression from today.
>
> **Cost is not the reason to change it.** `isModified()` is called from exactly two places,
> `maybeSaveGame()` (closing or exiting) and `BugDialog`, never per command. The digest costs one
> streamed encode, the same work as a save and no memory: 14 s on the 8,855-piece World in Flames
> game that motivated this branch, well under a second on a normal game, and always paid at the
> moment the player is about to save anyway. What the branch removes is not that cost but the
> 446 MB `String` that was retained for the whole session to make the comparison possible.
>
> **Where a flag would fit.** As an optimisation on top of the digest, not instead of it: set in
> `Command.execute()` and cleared on save, so that when no command has run since the last save the
> digest is skipped. That is safe only if we accept that a non-command mutation would then go
> undetected, which is the same risk as the flag alone — so I would only add it if that fast path
> measurably mattered, and I don't think it does.
>
> So the recommendation is to keep the digest: it is exact by construction, its cost sits where a
> save already costs the same, and it keeps nothing between calls but 32 bytes.

## 7. Not done here

- The load's remaining transient, the `Command` tree, could be removed by executing each
  `AddPiece` as it is decoded instead of after the whole tree is built. That changes
  `loadGameInBackground`'s contract (decode on a worker, execute on the EDT) and error
  handling, and is a separate change.
- `GameModule.encode(Command)` is still used for network traffic and `SavedGameUpdater`; those
  are per-command and small.
- Piece text strings are still interned by `SequenceEncoder.Decoder` per token; with the flat
  trait chain ([vassal-flat-trait-chain.md](vassal-flat-trait-chain.md)) that becomes a
  benefit rather than a cost.
