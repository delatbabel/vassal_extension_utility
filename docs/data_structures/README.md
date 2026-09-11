# VASSAL data structures

Reference documentation for the three file formats this project reads and
writes, described as **data structures**: what the bytes are, how the levels
nest, which field carries identity, and which invariants a tool must not break.

Each page is written from the code that parses these files
([`model/SavedGame`](../../src/main/java/org/vassalengine/extutil/model/SavedGame.java),
[`model/VassalArchive`](../../src/main/java/org/vassalengine/extutil/model/VassalArchive.java),
[`tools/swap_maps.py`](../../tools/swap_maps.py)) and every example is taken
from a real file, not invented.

| Page | Covers |
|---|---|
| [zip-container.md](zip-container.md) | The ZIP layer all three formats share, and why entry modification times are load-bearing |
| [sequence-encoder.md](sequence-encoder.md) | `SequenceEncoder` — the one encoding every level of every format is built from |
| [vsav.md](vsav.md) | `.vsav` / `.vlog` — the saved game: obfuscation envelope and command log |
| [game-piece.md](game-piece.md) | The game-piece record: the `type` and `state` chains, and where identity lives |
| [vmod.md](vmod.md) | `.vmod` — the module: `buildFile.xml`, the component tree, piece definitions |
| [vmdx.md](vmdx.md) | `.vmdx` — the extension: `ExtensionElement` wrappers and target paths |

## The three formats at a glance

All three are ZIP archives whose payload is one XML or text document plus assets.

| | `.vmod` | `.vmdx` | `.vsav` |
|---|---|---|---|
| Main entry | `buildFile.xml` | `buildFile.xml` | `savedGame` |
| Root / envelope | `<VASSAL.build.GameModule>` | `<VASSAL.build.module.ModuleExtension>` | `VOBS` / `!VCSK` / `!VCSZ` obfuscation |
| Metadata entries | `moduledata` | `moduledata`, `extensiondata` | `moduledata`, `savedata` |
| Assets | `images/`, root-level `*.vsav` | `images/`, root-level `*.vsav` | none |
| Payload encoding | XML | XML | `SequenceEncoder` text, ESC-delimited |
| Identity of a counter | `gpid` attribute on a `PieceSlot` | same | 4th `;`-field of a piece's innermost state |

The two XML formats describe **definitions**; the saved game describes
**instances**. `gpid` is the join between them, and it is the only join: a saved
piece carries no other reference back to the slot it came from.

## What they share

### One encoding, at every level

Every delimited structure in every format — the command log, an `AddPiece`'s
fields, a trait chain, an `ExtensionElement`'s target path — is a
`SequenceEncoder` string. Learn it once and all three formats become readable.
See [sequence-encoder.md](sequence-encoder.md).

### Entry modification times are a cache key

VASSAL tiles large board images to a disk cache and decides whether a cached
tile is stale by comparing the image ZIP entry's mtime against the tile's. A
tool that rewrites an archive must copy every untouched entry **with its
original mtime**; stamping "now" forces a re-tile of every board image. See
[zip-container.md](zip-container.md#modification-times-are-a-cache-key).

### Writes must be atomic

A truncated `.vsav` is what makes VASSAL report *"… is not a VASSAL saved game
or log."* Every writer in this project builds a temp file and moves it into
place.

### Verbatim beats round-tripping

Because escaping compounds with nesting depth (see
[sequence-encoder.md](sequence-encoder.md#why-escapes-pile-up)), decoding and
re-encoding an untouched token is not guaranteed to reproduce its bytes. Every
tool here edits the bytes it targets and copies the rest through unchanged.

## Diagrams

Standalone interactive pages under [`diagrams/`](diagrams/) — open the `.html`
in a browser; no server, no network.

| Diagram | Shows |
|---|---|
| [vsav-structure.html](diagrams/vsav-structure.html) | `.vsav`: ZIP entries → obfuscation envelope → command log |
| [vmod-structure.html](diagrams/vmod-structure.html) | `.vmod`: ZIP entries → component tree → a `PieceSlot` |
| [vmdx-structure.html](diagrams/vmdx-structure.html) | `.vmdx`: ZIP entries → `ModuleExtension` → a target path |
| [piece-record.html](diagrams/piece-record.html) | The `AddPiece` record: the two parallel chains and the leaf |
| [sequence-encoder.html](diagrams/sequence-encoder.html) | The three nesting levels, their delimiters and the escaping |

The three `*-structure` diagrams share one grid: three bands, each a container
level, with column 1 the spine — the entry that decodes into the band below.
Diagram sources are the `.json` specs kept beside the HTML; they were rendered
with the `archify` skill at its `showcase` quality profile.

## Reading a file yourself

Everything below runs against a real file with no build step. From the
repository root:

```bash
python3 - <<'PY'
import sys; sys.path.insert(0, 'tools')
from swap_maps import read_vsav, split_commands
state, entries, fmt = read_vsav('SAVE.vsav')
print('obfuscation', fmt, '| plaintext', len(state), 'bytes')
for i, (ds, cs, end) in enumerate(split_commands(state)):
    print(i, state[cs:end].decode('utf-8', 'replace')[:120])
PY
```

For the XML formats, `unzip -p` is enough:

```bash
unzip -l  Module.vmod                    # the entry inventory
unzip -p  Module.vmod moduledata         # the metadata
unzip -p  Module.vmod buildFile.xml | head -c 2000
unzip -p  Extension.vmdx buildFile.xml | grep -o 'target="[^"]*"' | sort -u
```

## Related documentation

- [docs/vsav-format.md](../vsav-format.md), [docs/vmod-format.md](../vmod-format.md),
  [docs/vmdx-format.md](../vmdx-format.md) — the original format notes these pages expand on
- [docs/vsav-excess-units.md](../vsav-excess-units.md) — the detection algorithm built on these structures
- [docs/image-display-and-tiling.md](../image-display-and-tiling.md) — the tiling pipeline behind the mtime rule
- [docs/wif-save-bloat-analysis.md](../wif-save-bloat-analysis.md) — what these structures cost at scale
- [docs/tools/](../tools/README.md) — the command-line tools that manipulate them
