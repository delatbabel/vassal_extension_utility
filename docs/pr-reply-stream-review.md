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
