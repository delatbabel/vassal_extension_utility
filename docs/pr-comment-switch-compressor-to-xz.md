## Compress the saved-game command log with XZ before obfuscating it

### Why

A saved game's command log is thousands of pieces whose text repeats the same expanded prototype traits, but each piece is longer than the 32 KB window of the ZIP entry's deflate, so the ZIP stores the repeat again for every piece. On a large World in Flames game (7 072 pieces of 113 traits, a 224 MB log) the `savedGame` entry deflates to 17 MB. LZMA2 with a 4 MB dictionary sees dozens of pieces at once and stores the repeat once.

I measured the alternative first — a type table in the save that defines each distinct piece type, or each distinct trait, once and references it — and it is worth having only at trait granularity, where it gives the same 10× as a long-window compressor on the *unchanged* text. The compressor needs no new command grammar, no decode-session state, and no change to any external reader of saved games beyond the codec, so it is the better way to get the same result. (Details in the linked note.)

### What changes

`ObfuscatingOutputStream` writes a new five-byte header, `!VOXZ`, the one-byte key, then the data **XZ-compressed (LZMA2 preset 3) and XORed with the key** — the XZ stream sits between the caller and the XOR, so the obfuscation applies to the compressed bytes. `DeobfuscatingInputStream` recognises the header and wraps the XOR-undoing stream in an `XZInputStream`; `!VOBS`, `!VCSK` and `!VCSZ` are still read. Every writer of a save or log goes through these two classes, so nothing else changes; the `.vsav` is still an ordinary ZIP.

Preset 3 (4 MB dictionary, the fast match finder) is the point of diminishing returns: presets 6 and 9 gain a further 20–30 % for ten times the compression time.

### Measured

On the game above, in the engine (this branch is from `master`, so the save still builds its String):

| | `master` | this branch |
|---|---:|---:|
| `.vsav` written | 17.2 MB | **829 KB** |
| Save | 35 s | **20.6 s** |
| Load from the file just written | – | 81 s |

A game loaded from the 829 KB file and saved again produces a byte-identical 223.7 MB command log. On the whole log, xz preset 3 compresses in 2.7 s where deflate level 9 takes 12 s; decompression is 0.4 s.

### Compatibility

New engines read every format. Older engines cannot read `!VOXZ` saves and logs, exactly as they cannot read `!VOBS`; the existing version-mismatch warnings apply. The decoder needs about 4 MB (the dictionary), the encoder about 30 MB while a save is being written.

### The dependency, and the 2024 xz backdoor

This adds `org.tukaani:xz:1.12` — **XZ for Java**, pure Java, 0BSD-licensed, 275 KB, sealed, with a `module-info`; the library that `commons-compress` delegates to. No native code, so the linked runtimes need no new module.

The name deserves a direct answer, because in March 2024 a backdoor was found in **XZ Utils** — the C implementation, `liblzma`, that Linux distributions ship — tracked as **CVE-2024-3094**. It had been inserted by "Jia Tan", a co-maintainer who spent about two years earning commit and release rights. The malicious code was not in readable source: it hid in obfuscated files in the test-data directory and was activated by a modification to the `configure` build scripts that existed only in the release tarballs of versions **5.6.0 and 5.6.1**, which Jia Tan built and signed. When `liblzma` built that way was loaded into `sshd` on an x86-64 Debian- or Fedora-style system (via systemd's notification library), it hooked the RSA signature check so that a holder of one private key could run commands on the machine. It was caught within weeks by a developer investigating why `sshd` had become slow.

**XZ for Java is a separate codebase, and this change uses it, not XZ Utils.** Specifically:

- Tukaani's own incident page states of the project's other repositories: "*xz-embedded and xz-java are fine and never had malicious or suspicious content*."
- The backdoor's mechanism does not exist here. XZ for Java is Java source built with Ant and published to Maven Central; there is no `configure`, no native build step, and no binary test blob that feeds the build.
- Jia Tan's entire footprint in the `xz-java` repository is four commits between December 2022 and January 2024: two README/metadata edits, one removal of unused imports, and one implementation of the ARM64 BCJ filter (`ARM64Options`, `simple/ARM64.java`, ~175 lines). This change uses plain LZMA2 with no BCJ filter, so that code is never on the path — and it was in any case reviewed with the rest of the repository after the incident.
- Every commit since (98, from March 2024 through the 1.12 release on 1 March 2026) is by Lasse Collin, the project's founder and its sole maintainer since the incident, and the `v1.12` tag is signed with his key (GitHub reports the signature as verified).
- The version pinned, **1.12**, post-dates the incident by two years. The jar matches Maven Central's published checksums (SHA-1 `bb9703ba3753ab8665f65e6a25b3ddc7b09b1caf`, SHA-256 `3e158a87bd73d8afb4b6e8239c013b7d049c48563f45860ce99cd2e448cf4a6b`) and contains only class files, a manifest and a `module-info`.

### Packaging

The Maven-built packages pick the jar up from `release-prepare/target/lib` as they do every other dependency (`jdeps` sees it needs only `java.base`). The Debian package builds against the system jars listed in `debian/rules`, so `libxz-java` (`/usr/share/java/xz.jar`, 1.9 on Ubuntu 24.04, which has everything used here) is added there and to `debian/control`'s build and runtime dependencies.

### Tests

`ObfuscatingOutputStreamTest` now expects the XZ layout (computed with `XZOutputStream` at the same preset) and checks that repetitive text shrinks by more than 20×; `DeobfuscatingInputStreamTest` gains a case for the uncompressed `!VOBS` payload and one for the `!VOXZ` header, beside the existing `!VCSK` cases. Full suite: 764 tests, the one failure the environmental `ProcessCallableTest` that fails on `master` too. Checkstyle, PMD, SpotBugs clean.

### Merging with the other open PRs

Trial-merged against #15116, #15117, #15119 and `fix_sequences`. One conflict, with #15117 (streaming), in `ObfuscatingOutputStream.write(byte[], int, int)`: that branch XORs a block there, this branch moves the XOR into an inner `XorOutputStream` and has the method delegate — resolve by keeping this branch's version. With that, the union of all five compiles and passes the stream, chain-framing, trait-sharing and `SequenceEncoder` tests; the only failures on the union are the six `CommandSerializerTest` byte-identity assertions already noted on #15119, which are the streaming writer versus `fix_sequences`' token form and independent of this change. The streaming branch composes as intended: its digest is taken from the plaintext before compression, and the compression sits in the same streamed pass as the encoding.
