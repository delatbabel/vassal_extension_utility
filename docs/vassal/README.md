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
| [reading-abr-error-logs.md](reading-abr-error-logs.md) | How to fetch and read the error log attached to an automated bug report: it is brotli-compressed behind a redirect, so `curl -sL --compressed` (both flags) is the whole trick, and the `Caused by` that the issue body omits is usually in there |
| [vassal-deb-missing-xz.md](vassal-deb-missing-xz.md) | `NoClassDefFoundError: org/tukaani/xz/FilterOptions` on the 3.8.0 `.deb` — the new `libxz-java` dependency, why a dangling `Class-Path` symlink fails silently, and how to install the `.deb` so it cannot happen ([#15256](https://github.com/vassalengine/vassal/issues/15256), documented upstream by [#15361](https://github.com/vassalengine/vassal/pull/15361)) |
| [vassal-load-and-sync-performance.md](vassal-load-and-sync-performance.md) | Why a large module takes 30+ seconds to load and longer to join: 80% of the CPU is `SequenceEncoder`, the nested trait framing scans 3.9 GB to read 97 MB, and every `setup()` re-encodes the whole game to set a boolean. Measured on WiF 2.2.0, before and after [#15116](https://github.com/vassalengine/vassal/pull/15116) |
| [vassal-editor-extensions.md](vassal-editor-extensions.md) | Why a module opened with **Edit Module** shows none of its extensions' maps or counters, and the change that loads them without the editor then saving them into the module |
| [vassal-record-module-load-crash.md](vassal-record-module-load-crash.md) | Why a module whose custom code contains a single Java `record` cannot be loaded at all: the removed-and-deprecated check reads a field-access method handle's descriptor as a method descriptor and runs off the end of it, before the module is ever opened ([#15262](https://github.com/vassalengine/vassal/issues/15262), fixed by [#15345](https://github.com/vassalengine/vassal/pull/15345)) |
| [vassal-chatter-html-nesting.md](vassal-chatter-html-nesting.md) | Why a long game dies with a `StackOverflowError` deep inside Swing's HTML styling: an `<html>` tag in a chat message nests the conversation document two levels deeper, permanently, so a module that wraps its report messages in one eventually overflows a repaint ([#15300](https://github.com/vassalengine/vassal/issues/15300), [#13117](https://github.com/vassalengine/vassal/issues/13117), fixed by [#15344](https://github.com/vassalengine/vassal/pull/15344)) |
| [vassal-custom-code-misattribution.md](vassal-custom-code-misattribution.md) | Why a fault in a module's own compiled Java is reported as a VASSAL bug: the class loader which loads custom classes was never named, so nothing in a stack trace told the module's frames from the engine's -- 108 of 1,532 traced automated reports ([#15225](https://github.com/vassalengine/vassal/issues/15225) and 32 other open ones, fixed by [#15348](https://github.com/vassalengine/vassal/pull/15348)) |
| [vassal-apply-on-move-recursion.md](vassal-apply-on-move-recursion.md) | Why three reports of a `StackOverflowError` deep inside BeanShell's parser are really one line of VASSAL: a map's apply-on-move key is dispatched back into the trait chain of the piece that just moved, so a trait which moves it again loops until the stack gives out ([#15209](https://github.com/vassalengine/vassal/issues/15209), [#13685](https://github.com/vassalengine/vassal/issues/13685), [#13063](https://github.com/vassalengine/vassal/issues/13063), fixed by [#15350](https://github.com/vassalengine/vassal/pull/15350)) |
| [vassal-room-list-concurrency.md](vassal-room-list-concurrency.md) | Why the room list throws `IndexOutOfBoundsException` out of a chat session: a room handed out a live view of its player list, so the peer-to-peer network thread could remove a player between the event dispatch thread measuring the list and indexing it ([#15107](https://github.com/vassalengine/vassal/issues/15107), fixed by [#15351](https://github.com/vassalengine/vassal/pull/15351)) |
| [vassal-mapwidget-delete-npe.md](vassal-mapwidget-delete-npe.md) | Why deleting a Map from a Chart Window raises a `NullPointerException` **and then does not delete it**: `MapWidget.remove` strips the panel through its own fields rather than the component it was given, and throws before the component is taken out of the widget -- six reports over three years ([#14667](https://github.com/vassalengine/vassal/issues/14667) and five others, fixed by [#15352](https://github.com/vassalengine/vassal/pull/15352)) |
| [vassal-corrupt-prefs-startup.md](vassal-corrupt-prefs-startup.md) | Why one bad escape in the preferences file stops VASSAL starting: `Properties.load` signals a malformed file with an *unchecked* exception, and of the three places that read preferences only `Prefs.read` guarded against it -- all eleven open reports under that frame are this one fault ([#13820](https://github.com/vassalengine/vassal/issues/13820) and ten others). Also the one case here of being overtaken: the reading half landed upstream in [78a7a04a8](https://github.com/vassalengine/vassal/commit/78a7a04a8d75d56484caafd9b35a16f9d1be031c) under four other issue numbers while the PR was open, leaving [#15353](https://github.com/vassalengine/vassal/pull/15353) scoped to the saving half |
| [vassal-tiling-not-out-of-memory.md](vassal-tiling-not-out-of-memory.md) | Why "Possibly ran out of memory" usually means nothing of the kind: the tiler reports a real OOM as exit code 2, VASSAL retried on any failure and wound the heap up to the machine's whole memory first -- 16 of the 30 open reports are a tiler that could not be loaded ([#13851](https://github.com/vassalengine/vassal/issues/13851) and fifteen others, fixed by [#15354](https://github.com/vassalengine/vassal/pull/15354)) |
| [vassal-ansi-arg-mangling.md](vassal-ansi-arg-mangling.md) | Why a module in a folder named `La Bataille d'Auerstædt` cannot be tiled: a subprocess argument crosses through the system ANSI code page, and the test for which arguments need protecting assumed that code page was always windows-1252 -- the one report in the tiling family with a cause of its own ([#14193](https://github.com/vassalengine/vassal/issues/14193), fixed by [#15360](https://github.com/vassalengine/vassal/pull/15360)) |
| [vassal-module-manager-edt.md](vassal-module-manager-edt.md) | Why clicking in the Module Manager throws out of a tree widget with no VASSAL code in the stack at all: the module list's tree model was changed from a launch worker as well as from the event dispatch thread, so the tree kept rows for nodes the model had already dropped -- and the duplicate module rows several reporters describe are the same fault. Thirteen reports, five of them closed for having nothing of ours in the trace ([#15104](https://github.com/vassalengine/vassal/issues/15104) and twelve others, fixed by [#15362](https://github.com/vassalengine/vassal/pull/15362)) |
| [vassal-trailing-backslash-state.md](vassal-trailing-backslash-state.md) | Why a label ending with a backslash makes its piece unreadable: the encoder quotes a value which *starts* with a backslash but not one which *ends* with it, so the delimiter after it is read as escaped and the states of every inner trait are swallowed -- sixteen reports that no tracker search could group, because the message embeds the trait's whole type and so no two share a title (diagnosed by FredKors in [#15337](https://github.com/vassalengine/vassal/issues/15337), fixed by [#15363](https://github.com/vassalengine/vassal/pull/15363)) |
| [vassal-infinite-zoom-factor.md](vassal-infinite-zoom-factor.md) | Why a map stops drawing with "Dimensions (width=2147483647 height=2147483647) are too large": zoom-to-fit divides a view extent by a map extent, a map with no boards measures (0,0), and a *double* division by zero yields `Infinity` instead of throwing -- which the zoom guard lets through, because it tests `z <= 0` and every comparison with `Infinity` or `NaN` at that end is false ([#15013](https://github.com/vassalengine/vassal/issues/15013) and five others, fixed by [#15364](https://github.com/vassalengine/vassal/pull/15364)) |
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
| [vassal-load-and-sync-performance.md](vassal-load-and-sync-performance.md) | [#15303](https://github.com/vassalengine/vassal/issues/15303) and [#15306](https://github.com/vassalengine/vassal/pull/15306) — the `setup()` re-serialisation and the fix; [#15305](https://github.com/vassalengine/vassal/issues/15305) — the flat trait-chain measurements |
| [pr-review-trait-keyevent-snapshot.md](pr-review-trait-keyevent-snapshot.md) | [#15257](https://github.com/vassalengine/vassal/pull/15257) — review of riverwanderer's change to snapshot piece state only when a Dynamic Property or Layer handles the keystroke |
