# Replies to the review of PR #15117 (streaming save/load)

**`GameState.java` — "Would it make sense to have the message digest as a member and reset it with each use?"**

Yes — done. One `MessageDigest` per `GameState`, created on first use and `reset()` before each, shared by `saveDigest()` and `writeGameFile()`. Both run on the EDT, so there is no contention on it; `writeGameFile` became an instance method for that reason (the logger reaches it through the game state). PMD's `AvoidMessageDigestField` rule objects to any `MessageDigest` field on thread-safety grounds; it is suppressed at the field with that EDT justification in the comment.

**"The temp dir is available from `Info.getTempDir()` / `Config.tempDir()`" and "`Files.createTempFile` is typically what we use"**

Switched to `Files.createTempFile`, but deliberately in the target's own directory rather than the temp dir: the point of the temporary file is that the final step is a *rename* over the old save, which is atomic only within one filesystem. On most systems the temp dir is a different filesystem from wherever the user keeps saves (tmpfs, another drive), and then `Files.move` has to fall back to copy-and-delete, which reopens exactly the window — a truncated file if something dies mid-copy — that the temporary file exists to close. A comment says so at the call. One consequence of `createTempFile` worth knowing: on POSIX it creates the file owner-read/write only, so a save written this way is `0600` where `ZipWriter` used to give the umask default; if that matters for people who share a save directory, the permissions could be copied from the previous file before the move.

**"Should we delete the temp file unconditionally, even on failure?"**

It already is — the `deleteIfExists` is in a `finally`, so it runs whether the move happened (then the temp file is gone and it is a no-op) or anything above threw (then it removes the partial file). I've added a comment there saying exactly that, since it evidently wasn't obvious.

**"Why are we using `Writer` here instead of `OutputStream`?"**

Because what `GameModule.encode(Command, Writer)` produces is text — the command log is characters, exactly as the old `encode(Command)` returned a `String` — and the digest has to be of the *bytes* that end up in the file, i.e. that text encoded as UTF-8. The `OutputStreamWriter` is the UTF-8 encoder sitting between the two; under it the `DigestOutputStream` sees the same bytes `saveGame` writes into the ZIP, so `isModified()` compares like with like. An `OutputStream` at the encoder would have meant encoding characters to bytes inside the serializer instead, which is the writer's job. Added a comment at the site.
