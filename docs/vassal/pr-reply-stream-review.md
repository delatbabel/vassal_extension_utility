# Replies to the review of PR #15117 (streaming save/load)

**`GameState.java` — "Would it make sense to have the message digest as a member and reset it with each use?"**

Yes — done. One `MessageDigest` per `GameState`, created on first use and `reset()` before each, shared by `saveDigest()` and `writeGameFile()`. Both run on the EDT, so there is no contention on it; `writeGameFile` became an instance method for that reason (the logger reaches it through the game state). PMD's `AvoidMessageDigestField` rule objects to any `MessageDigest` field on thread-safety grounds; it is suppressed at the field with that EDT justification in the comment.

**"The temp dir is available from `Info.getTempDir()` / `Config.tempDir()`" and "`Files.createTempFile` is typically what we use"**

Switched to `Files.createTempFile`, but deliberately in the target's own directory rather than the temp dir: the point of the temporary file is that the final step is a *rename* over the old save, which is atomic only within one filesystem. On most systems the temp dir is a different filesystem from wherever the user keeps saves (tmpfs, another drive), and then `Files.move` has to fall back to copy-and-delete, which reopens exactly the window — a truncated file if something dies mid-copy — that the temporary file exists to close. A comment says so at the call. One consequence of `createTempFile` worth knowing: on POSIX it creates the file owner-read/write only, so a save written this way is `0600` where `ZipWriter` used to give the umask default; if that matters for people who share a save directory, the permissions could be copied from the previous file before the move.

**"Should we delete the temp file unconditionally, even on failure?"**

It already is — the `deleteIfExists` is in a `finally`, so it runs whether the move happened (then the temp file is gone and it is a no-op) or anything above threw (then it removes the partial file). I've added a comment there saying exactly that, since it evidently wasn't obvious.

**"Why are we using `Writer` here instead of `OutputStream`?"**

Because what `GameModule.encode(Command, Writer)` produces is text — the command log is characters, exactly as the old `encode(Command)` returned a `String` — and the digest has to be of the *bytes* that end up in the file, i.e. that text encoded as UTF-8. The `OutputStreamWriter` is the UTF-8 encoder sitting between the two; under it the `DigestOutputStream` sees the same bytes `saveGame` writes into the ZIP, so `isModified()` compares like with like. An `OutputStream` at the encoder would have meant encoding characters to bytes inside the serializer instead, which is the writer's job. Added a comment at the site.

---

# Replies to the second review (1 October)

**`ObfuscatingOutputStream.java` — "This change looks like it doesn't belong in this PR."**

Agreed and already dropped (`2ea22da6b`): the branch no longer touches that class. #15121 is removing the XOR altogether, so the block-at-a-time tweak was moot anyway.

**`GameState.java` `saveGameRefresh` — "If you wrap them two deep here you can end up with a leak … However, we could just go to `XZOutputStream` here."**

Named every stream in each try-with-resources — here, in `saveDigest`, in `writeGameFile` (five deep: ZIP entry, buffer, obfuscator, digest, writer) and in `decodeSavedGame` (the `InputStreamReader` under the `BufferedReader`) — so nothing can leak if a later constructor throws. Going straight to `XZOutputStream` is #15121's change; this branch keeps constructing `ObfuscatingOutputStream`, which that PR keeps as a deprecated wrapper that compresses, and once both are in these two sites switch to `GameState.compressSavedGame`.

**"The reset should be in an else."**

Done.

**"This doesn't need to be inside the try block."**

Moved the digest out before the `try`.

**"I'm not sure we should delete the temp file on failure, since it might provide some evidence of what happened."**

Fair — the `finally` is gone. A successful move consumes the temporary file; a failure anywhere before or during the move now leaves it beside the target for inspection, and the Javadoc says so.

**"Make sure each stream is named in the try so all are closed on exit."**

Done, as above.

**`CommandSerializer.java` — "This appears to be duplicating what's in `SequenceEncoder`."**

It restates one rule from `SequenceEncoder.Decoder` — a delimiter is escaped iff the character before it is a backslash, and the backslash is dropped — and does so deliberately. `Decoder` works on a `String` already in memory and hands back each token as a new `String`; for the top level of a saved game that string is the whole command log and the second token is the whole piece list, which is exactly what this PR exists to stop materialising. `TokenReader` applies the same rule to a stream of characters one level at a time, so that only one command's own text is ever held. I kept it inside `CommandSerializer`, private, rather than adding a `Reader`-based mode to `SequenceEncoder`, because I did not want to touch that class in a PR about something else — but if you would rather the streaming tokenizer lived in `SequenceEncoder.Decoder` (say a constructor taking a `Reader`), I can move it there; it is the same forty lines either way, and the test that checks it against the `String` decoder on 3 000 random trees would come along. One thing worth knowing for `fix_sequences`: this is the one place that would need its length-prefixed token form added, since it reads the delimiters itself.

---

# After merging `master` (3 October)

The merge of `master` (with #15121) kept an `IOUtils` import this branch no longer uses, which Checkstyle rejects at `validate`, so the build stopped before compiling anything. Removed it, and since the XZ change is now in, the two writers (`writeGameFile` and `saveGameRefresh`) call `GameState.compressSavedGame()` directly instead of constructing the deprecated `ObfuscatingOutputStream`, as promised above.

---

# Third review (3–4 October)

**`CommandSerializer.java:475` — "Can this replace almost all of the implementation of `SequenceEncoder.Decoder`?"**

They implement the same grammar (a delimiter preceded by a backslash is part of the token with the backslash dropped; a token wrapped in single quotes is unwrapped), but over different representations, and that is where the duplication comes from. `Decoder` indexes into a `String` the caller already holds, returns interned substrings, and offers `getRemaining()`, `copy()` and the typed `nextInt()`/`nextNamedKeyStroke()`/... conveniences on top. `TokenReader` pulls characters from a stream it never holds whole and yields each token *as a stream*, so a nested level is read from its parent's characters without materialising the parent, with one character of pushback for the backslash look-ahead. `getRemaining()` and `copy()` cannot exist on it.

So `Decoder.nextToken()` could be written over a `TokenReader` on a `StringReader`, but not for free: a `Reader`, a `TokenReader` and a `Token` per decoder, and a character-at-a-time loop through three calls where today the common unescaped token is one indexed scan and one `substring`. `Decoder` is the hottest parser in the engine, since every trait's `mySetType` goes through it (789 k traits on the WiF game), and that is the direction I would not take. The reverse, `TokenReader` over `Decoder`, is impossible, since `Decoder` needs the whole string.

What can be shared is the scanning rule itself: a `CharSequence`-based scanner that `Decoder.nextToken()` uses for the string case and `TokenReader` uses block-wise. I would rather do that as its own PR, so that this one stays about streaming and the hot path change gets its own review. Until then the two are held to the same behaviour by `CommandSerializerTest`, which decodes 3,000 random command trees through both paths and asserts equal trees and byte-identical re-encoding.

**`GameState.java:1751` — "Why 64KB for the buffer?"**

No measurement behind it. The serializer's own `ReaderSource` reads its `Reader` in 16 K blocks, so a `BufferedReader` of any size on top only added a copy. Removed (`ecfe500e7`); the entry is read through the `InputStreamReader` alone.

---

# Fourth round (16–22 October): "just append a record", undo past an auto-save, and a save marker

**cholmcc — "Can we agree that the code to encode a single `Command` … is already in the code base? … `Open output in append mode; Write (Encode Command) to end of output; Close output`. That's it."**

Agreed on the first part: `GameModule.encode(Command)` is there, unchanged, and still used for every command that goes over the wire. This branch does not touch it.

But appending is not what a save is. A `.vsav` holds no records of play. It is *one* command, `begin_save ESC <version check> ESC <every piece> ESC <every component's state> ESC end_save`, a snapshot of the whole game taken at the moment of saving (`GameState.getRestoreCommand()`). The piece list is a single `NullCommand` with 8,855 sub-commands, encoded as *one token* two levels down, and that token is where the 223 MB lives. Every save re-encodes every piece as it stands; there is nothing in the file to append to, and no earlier record that could stay valid, because the pieces have moved. A `.vlog` is the same snapshot (`beginningState`, taken when logging begins) followed by the `LOG` records; the records are the part that could be appended, and they are small. What costs memory and time, on load and on save alike, is the snapshot.

So "write `encode(command)` to the end of the output" is exactly what `GameModule.encode(Command, Writer)` on this branch does, and the command written that way is the snapshot. The reason that is 475 lines in `CommandSerializer` rather than a hook is a rule in `SequenceEncoder.append()`: a token is wrapped in quotes if it starts with a backslash, or starts and ends with a quote; a sub-command's *entire* encoding is one token of its parent; so the writer has to know the *last* character of a subtree's text before it may emit the *first*. The `String` form never met that problem, because it built the subtree's text and then looked at it. The streaming form finds the answer by walking the rightmost line of descent without building anything, and the reader has the mirror problem with quoted tokens, which can only be recognised at their end. That is the whole of the extra code, and `CommandSerializerTest` holds it byte-for-byte to what `encode(Command)` produces, on 3,000 random trees and on every branch of the quoting rule.

On appending to the file itself: the command log is an XZ stream inside a ZIP entry. Appending to it means rewriting the entry's length and CRC and the central directory behind it, and only ever for a `.vlog`'s tail.

**cholmcc — "To not make a regression, you will need to facilitate UNDO beyond the last auto-save … I don't think I would merge anything until such a regression is addressed."**

There is no auto-save in this PR, no periodic flush, and nothing is dropped from memory while a game is open. The diff to `BasicLogger` is a dozen lines, and all it does is replace the `String` build in `write()` with a streamed write of the same log; `logOutput`, `nextUndo`, `dontUndoPast` and `undo()` are untouched. Undo works exactly as on `master`, back to the start of the session or the last `blockUndo`, however many times the game has been saved in between. The regression you describe belongs to the periodic-flush design you proposed, in which the in-memory log is trimmed and has to be read back from disk to undo past the trim. This PR does not do that, and I would not want it to for the reasons I gave.

What the branch removes from memory is the *encoded text*: the 446 MB `String` of the restore command that `saveString()` built on each save and that `lastSave` then held for the whole session so that `isModified()` could compare against it (`GameState.java:292-294` on `master`), plus the second full copy `getBytes(UTF_8)` made of it to write. The `Command` objects of the undo history are not that, and the branch keeps them.

> Edit: BTW, to calculate the new checksum to compare to the old checksum, we would need to encode the current log in its entirety first, only to find out that no write is needed and the encoding has been for naught.

Yes, and that is what `master` does today in `isModified()`: `saveString().equals(lastSave)`, a full encoding of the current state into a `String` and a comparison against the retained one. The branch encodes the same text once into a digest and keeps 32 bytes instead of 446 MB. It is called in two places, closing a game and quitting, never per command. And after Joel's suggestion below it is skipped altogether whenever anything has been logged since the last save, which is the usual state of a game being closed.

**uckelman — "Mark the command after which the last save occurred. If the undo or redo stacks are not empty and we are not at the marked command, then we're in a state where a save is required …"**

Done, as the fast path in front of the digest (`fc8bcc236`). `BasicLogger` counts every command added to its output log, undos included; `GameState` records that count at each save, and at the two other points `lastSave` used to be set, game start and `setModified()`; `isModified()` answers *yes* at once if the count has moved. So the "I played, now I'm closing" case costs nothing, and for the WiF game the close-and-save path now encodes the state once, to write it, instead of twice.

What I have kept the digest for is the other answer. When the count has *not* moved the state can still differ from the file, and the cost of saying "unchanged" wrongly is that the game closes without the save prompt:

- **Commands that bypass the log.** `Command.isLoggable()` is overridden to `false` by both chess clock commands (`ChessClock.UpdateTimerCommand`, `ChessClockControl.UpdateClockControlCommand`), while `ChessClock.getRestoreCommand()` *is* an `UpdateTimerCommand` carrying `elapsedTime`: the clocks are in every save and never in `logOutput`. A ticking clock changes that state with no command at all, from its `Timer` (`updateDisplay()`).
- **State set directly.** The 36 `GameComponent.getRestoreCommand()`s encode whatever their fields hold at the time. A field changed without a command, by the component itself or by module code, is invisible to the count. `setModified(true)` exists for callers that know they have done this.

In those cases the count is unchanged and the digest decides, exactly as the `String` comparison did on `master`.

Two notes on the engine as it stands, in case they bear on the design you have in mind. There is no redo stack to be "in": an undo is a new `UndoCommand` appended to `logOutput`, with `nextUndo` walking back (`BasicLogger.undo()`), which is why the counter simply counts it. And the "odd situation" you say we need not detect is the one case the fast path gets wrong, in the harmless direction: a prompt for a save that would write the same bytes.

If you would rather drop the digest outright and accept the first list, that is a one-line change and I will make it. I would just want it to be a decision rather than a side effect of this PR, since `isModified()` on `master` has answered those cases correctly for as long as the method has existed.
