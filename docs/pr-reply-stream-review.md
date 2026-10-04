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
