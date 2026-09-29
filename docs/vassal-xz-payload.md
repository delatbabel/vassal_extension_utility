# Compressing the saved-game command log with XZ — `feature/switch-compressor-to-xz`

The outcome of [vassal-type-table-analysis.md](vassal-type-table-analysis.md): the repetition
that a type table would remove from a saved game by hand is what a long-window compressor
removes by itself, so instead of a new command grammar (C2) the `savedGame` payload is now
LZMA2-compressed. Branch `feature/switch-compressor-to-xz` in `../vassal`, from `master` at
`97e231720`.

---

## 1. The change

`ObfuscatingOutputStream` writes a new five-byte header, **`!VOXZ`**, then the one-byte key,
then the data **XZ-compressed and XORed with the key**: the XZ stream sits between the caller
and the XOR, so the obfuscation applies to the compressed bytes. `DeobfuscatingInputStream`
recognises the header and wraps the XOR-undoing stream in an `XZInputStream`; the three earlier
payloads (`!VCSK` hex, `!VCSZ` deflate-then-hex, `!VOBS` raw) are still read. Every writer of a
save or log goes through `ObfuscatingOutputStream` (`GameState.saveGame`, `saveGameRefresh`,
`BasicLogger.write`), so nothing else changes; the `.vsav` remains an ordinary ZIP whose
`savedGame` entry now holds a few hundred kilobytes of already-compressed bytes that the ZIP's
own deflate leaves as they are.

The preset is **3**: a 4 MB dictionary, which spans the repeated text of dozens of pieces, with
LZMA2's fast match finder. `LZMA2Options` presets 6 and 9 gain a further 20–30 % for ten times
the compression time ([vassal-type-table-analysis.md §5](vassal-type-table-analysis.md#5-what-the-compressor-would-cost)).

**Dependency.** `org.tukaani:xz:1.12` — *XZ for Java*, pure Java, 0BSD-licensed, 275 KB,
sealed, with a `module-info`; the library `commons-compress` delegates to. No native code, so
the `jlink`ed runtimes need no new module. See §3 on its provenance.

## 2. Measured

Engine measurements with the headless harness on the WiF `17-40-JA` game (the module loaded,
the save loaded with `loadGameInForeground`, saved with `saveGame`; this branch is from
`master`, so the save still builds its String):

| | `master` (deflate‑9 of `!VOBS`) | this branch (`!VOXZ`) |
|---|---:|---:|
| `.vsav` written | 17.2 MB (the original, in hex `!VCSK`, is 34 MB) | **829 KB** |
| `savedGame` entry | 17.2 MB | 828 KB (the ZIP's deflate then gains 0 %) |
| Save (encode + compress + write) | 35 s | **20.6 s** |
| Load from the file just written | – | 81 s (from the hex original: 144 s) |

Round trip: the game loaded from the 829 KB file and saved again produced a byte-identical
223.7 MB command log, and a second identical file. Compression of the full log in the
harness's Python equivalent, for the preset choice: deflate‑9 12.0 s, xz preset 3 2.7 s,
preset 6 34 s; decompression 0.4 s at every preset
([vassal-type-table-analysis.md §5](vassal-type-table-analysis.md#5-what-the-compressor-would-cost)).
With the flat chain ([PR #15116](https://github.com/vassalengine/vassal/pull/15116)) the same
game's log is 110 MB and its `!VOXZ` payload about 510 KB.

## 3. The 2024 xz backdoor, and why this dependency is not it

In March 2024 a backdoor was found in **XZ Utils** — the C implementation, `liblzma`, that
Linux distributions ship — tracked as **CVE-2024-3094**. It had been inserted by "Jia Tan", a
co-maintainer who had spent about two years earning commit and release rights to the project.
The malicious code was not in the readable source: it was hidden in obfuscated files in the
test-data directory and activated by a modification to the `configure` build scripts that
existed only in the release tarballs for versions **5.6.0 and 5.6.1**, which Jia Tan created and
signed. When `liblzma` was built that way on an x86-64 Debian- or Fedora-style system and loaded
into `sshd` (through systemd's notification library), it hooked the RSA signature check so that
a holder of a particular private key could execute commands on the machine. It was caught within
weeks by a Postgres developer investigating why `sshd` had become slow.

**XZ for Java is a different codebase**, and this change uses it, not XZ Utils:

- Tukaani's own incident page states of the project's other repositories that "*xz-embedded and
  xz-java are fine and never had malicious or suspicious content*."
- The backdoor's mechanism does not exist here: XZ for Java is pure Java source built with Ant
  and published to Maven Central; there is no `configure`, no native build step, and no binary
  test blob feeding the build.
- Jia Tan's entire footprint in the xz-java repository is four commits between December 2022 and
  January 2024: two README/metadata edits, one removal of unused imports, and one implementation
  of the **ARM64 BCJ filter** (`ARM64Options`, `simple/ARM64.java`, ~175 lines). This change
  uses plain LZMA2 with no BCJ filter, so that code is never on the path — and it was in any
  case reviewed with the rest of the repository after the incident.
- Every commit since (98, from March 2024 through the 1.12 release on 1 March 2026) is by Lasse
  Collin, the project's founder and sole maintainer since the incident, and the `v1.12` tag is
  signed with his key (GitHub reports the signature as verified).
- The version pinned here, **1.12**, post-dates the incident by two years; the jar in the local
  Maven cache matches Maven Central's published checksums (SHA-1
  `bb9703ba3753ab8665f65e6a25b3ddc7b09b1caf`, SHA-256
  `3e158a87bd73d8afb4b6e8239c013b7d049c48563f45860ce99cd2e448cf4a6b`) and contains only class
  files, a manifest and a `module-info`.

## 4. Compatibility

- New engines read every format; older engines cannot read `!VOXZ` saves and logs, exactly as
  they cannot read `!VOBS` — the existing version-mismatch warnings apply.
- **This utility and its scripts** read and re-emit `!VOXZ` alongside the other three
  (`SavedGame.Obfuscation.XZ`, with the same `org.tukaani:xz` dependency and preset;
  `tools/swap_maps.py` and the scripts built on it with Python's `lzma`). Verified both ways:
  the utility rewrites the engine's file to a byte-identical command log in 3.7 s, and the
  engine loads the utility's file and saves it to a byte-identical log again.
- Memory: the decoder needs about the dictionary size, 4 MB; the encoder about 30 MB while a save
  is written.

**Packaging.** The Maven-built packages collect the jar with the other dependencies into
`release-prepare/target/lib`; the Debian package compiles against the system jars listed in
`debian/rules`, so `libxz-java` (`/usr/share/java/xz.jar`) is declared there and in
`debian/control` — the first CI build of the branch failed on exactly that (`package
org.tukaani.xz does not exist` in the `.deb` step) before it was added.

## 5. Tests

`ObfuscatingOutputStreamTest` now expects the XZ layout (computed with `XZOutputStream` at the
same preset) and checks that repetitive text shrinks by more than 20×; `DeobfuscatingInputStreamTest`
gains a case for the uncompressed `!VOBS` payload and one for the `!VOXZ` header, alongside the
existing `!VCSK` cases. Full `vassal-app` suite: 764 tests, the one failure the environmental
`ProcessCallableTest` that fails on `master` too; Checkstyle, PMD and SpotBugs report nothing new.

## 6. Merging with the other branches

Trial merges of each open branch into this one, then a four-way union, compiled and tested:

| Merge into `feature/switch-compressor-to-xz` | Conflicts | Resolution |
|---|---|---|
| `feature/flat-trait-chain-encoding` (PR #15116) | none | — |
| `feature/stream-save-and-load` (PR #15117) | **one hunk**, `ObfuscatingOutputStream.write(byte[], int, int)`: that branch made the method XOR a block at a time; this branch moves the XOR into the inner `XorOutputStream` and has the method delegate to `out.write` | keep this branch's version (the block XOR is already inside `XorOutputStream`) |
| `feature/share-immutable-trait-data` (PR #15119) | none | — |
| `fix_sequences` | none | — |

With that one hunk resolved, the union of all five compiles and passes this branch's stream
tests, `TraitChainFramingTest`, `TraitTypeCacheTest`, `SequenceEncoderTest` and the trait tests.
The only failures on the union are the six `CommandSerializerTest` byte-identity assertions
already noted in [vassal-share-immutable-trait-data.md §6](vassal-share-immutable-trait-data.md#6-merging-with-the-other-branches):
the interaction between the streaming writer and `fix_sequences`' token form, which this branch
neither causes nor touches.

The streaming branch composes with this one exactly as intended: its `writeGameFile` puts the
`DigestOutputStream` *between* the writer and the obfuscating stream, so the digest that
`isModified()` compares is of the plaintext, before compression, and the compression sits in the
same streamed pass as the encoding.
