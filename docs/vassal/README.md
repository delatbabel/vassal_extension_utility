# VASSAL engine documentation

Notes about the **VASSAL engine itself** — its behaviour, its bugs, its build, and the branches
and pull requests this project has contributed to it. The engine lives in the sibling repository
`../vassal` ([vassalengine/vassal](https://github.com/vassalengine/vassal)).

Documentation about the extension utility — its features, the file formats it reads and writes,
its own packaging — stays one level up in [`docs/`](../).

These pages are kept here rather than upstream so that the investigation, the measurements and
the reasoning survive in one place even when only the patch lands in the engine.

## Behaviour and bugs

| Document | What it covers |
|---|---|
| [vassal-deb-missing-xz.md](vassal-deb-missing-xz.md) | `NoClassDefFoundError: org/tukaani/xz/FilterOptions` on the 3.8.0 `.deb` — the new `libxz-java` dependency, why a dangling `Class-Path` symlink fails silently, and how to install the `.deb` so it cannot happen ([#15256](https://github.com/vassalengine/vassal/issues/15256)) |
| [vassal-load-and-sync-performance.md](vassal-load-and-sync-performance.md) | Why a large module takes 30+ seconds to load and longer to join: 80% of the CPU is `SequenceEncoder`, the nested trait framing scans 3.9 GB to read 97 MB, and every `setup()` re-encodes the whole game to set a boolean. Measured on WiF 2.2.0, before and after [#15116](https://github.com/vassalengine/vassal/pull/15116) |
| [vassal-editor-extensions.md](vassal-editor-extensions.md) | Why a module opened with **Edit Module** shows none of its extensions' maps or counters, and the change that loads them without the editor then saving them into the module |
| [vassal-empty-extensionelement-crash.md](vassal-empty-extensionelement-crash.md) | Why an empty `ExtensionElement` throws a `NullPointerException` on load, and how the utility avoids leaving one behind |
| [image-display-and-tiling.md](image-display-and-tiling.md) | How VASSAL tiles large board images, why a moved image can look broken, and why entry modification times must be preserved |

## Building the engine

| Document | What it covers |
|---|---|
| [vassal-release-build-gs.md](vassal-release-build-gs.md) | Why `make release` dies with `(EBADF) Bad file descriptor - /usr/bin/gs` when Ghostscript is installed and working, the two `--add-opens` that fix it, and the jlink/jmods version trap when bootstrapping the Windows build |

## Saved-game size and memory

Measured analyses and the branches that act on them. Start with
[wif-engine-optimizations.md](wif-engine-optimizations.md), which tiers the work and cites the
others; the underlying measurements are in
[wif-save-bloat-analysis.md](../wif-save-bloat-analysis.md) one level up.

| Document | What it covers |
|---|---|
| [wif-engine-optimizations.md](wif-engine-optimizations.md) | Proposed engine changes to reduce saved-game memory and disk usage, tiered by effort, with `file:line` citations |
| [vassal-stream-save-and-load.md](vassal-stream-save-and-load.md) | **B1** — streaming the command log instead of building it as one 223 MB `String` (`feature/stream-save-and-load`, PR #15117) |
| [vassal-flat-trait-chain.md](vassal-flat-trait-chain.md) | Flat trait-chain framing — removing the O(N²) backslashes from the piece encoding (`feature/flat-trait-chain-encoding`, PR #15116) |
| [vassal-sequence-fix-comparison.md](vassal-sequence-fix-comparison.md) | The two candidate fixes for that O(N²) encoding, compared |
| [vassal-share-immutable-trait-data.md](vassal-share-immutable-trait-data.md) | **B2** — sharing a trait's parsed type data between instances (`feature/share-immutable-trait-data`, PR #15119) |
| [vassal-xz-payload.md](vassal-xz-payload.md) | Compressing the command log with XZ (`feature/switch-compressor-to-xz`) — the change behind the dependency in the first row of this page |
| [vassal-type-table-analysis.md](vassal-type-table-analysis.md) | **C2** — a type table in the save: value, complexity and payoff |

## Pull-request correspondence

Review comments written for, and replies sent to, the pull requests above, plus reviews of
other people's engine pull requests. Kept because they carry measurements and reasoning that
did not fit in a commit message.

| Document | Pull request |
|---|---|
| [pr-comment-fix-sequences.md](pr-comment-fix-sequences.md) | Review of `fix_sequences` and how it relates to flat trait-chain framing |
| [pr-comment-share-immutable-trait-data.md](pr-comment-share-immutable-trait-data.md) | Proposal for sharing parsed trait type data |
| [pr-comment-switch-compressor-to-xz.md](pr-comment-switch-compressor-to-xz.md) | Proposal for XZ-compressing the command log |
| [pr-reply-flat-chain-review.md](pr-reply-flat-chain-review.md) | #15116 — flat trait-chain framing |
| [pr-reply-stream-review.md](pr-reply-stream-review.md) | #15117 — streaming save/load |
| [pr-reply-stream-drop-xor.md](pr-reply-stream-drop-xor.md) | #15117 — leaving `ObfuscatingOutputStream` alone |
| [pr-reply-share-trait-data-review.md](pr-reply-share-trait-data-review.md) | #15119 — shared trait type data |
| [pr-reply-xz-alternatives.md](pr-reply-xz-alternatives.md) | XZ vs zstd and brotli, measured |
| [pr-reply-xz-drop-xor.md](pr-reply-xz-drop-xor.md) | Dispensing with the XOR obfuscation entirely |
| [pr-review-trait-keyevent-snapshot.md](pr-review-trait-keyevent-snapshot.md) | [#15257](https://github.com/vassalengine/vassal/pull/15257) — review of riverwanderer's change to snapshot piece state only when a Dynamic Property or Layer handles the keystroke |
