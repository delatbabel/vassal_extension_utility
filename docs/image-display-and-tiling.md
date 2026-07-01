# Image Display & Tiling — why a moved image can look "broken" in VASSAL

This note records a detailed investigation into a report that a large board
image (`CEMOD AMERICAS Centre.png`, 9602×8749) displayed correctly when it lived
in a module but not after it was moved into an extension — even though the
extension shows the image as present and its checksum matches the module's.

## Resolution (the actual root cause)

The image never failed to *load* — its **owning `Board` component was silently
dropped** when VASSAL read the extension, so nothing referenced the image and the
board simply wasn't there to draw. The utility had packed several boards
(`Americas AiF Nth Insert`, `… Main Inset`, `… Sth Insert`) into a **single**
`VASSAL.build.module.ExtensionElement`. But a VASSAL `ExtensionElement` holds
**exactly one** component — `ExtensionElement.build()` builds only the *first*
child element and stops — so VASSAL kept the first board and discarded the rest
(and permanently deleted them if the extension was then re-saved in the editor).
The `CEMOD AMERICAS Centre.png` board was one of the discarded ones.

**Fix:** the utility now writes **one `ExtensionElement` per grafted component**,
never sharing a wrapper even when several components target the same location —
exactly what the VASSAL editor does (see `MainWindow.createExtensionElement` and
[AGENTS.md → Move / Copy Operation](../AGENTS.md)). An extension already damaged by
an older build must be re-created (re-do the move with the fixed utility), since
VASSAL discarded the extra boards on load.

The rest of this note — the tiling analysis below — is retained because it was a
useful (and now ruled-out) line of inquiry: the image bytes and the generated
tiles were verified perfect, which is what pointed the investigation away from
the file and toward the component structure.

**Bottom line:** the image bytes the utility writes are byte-for-byte correct and
tile perfectly; the defect was that extra grafted components sharing one
`ExtensionElement` were dropped by VASSAL.

## What was verified about the moved image

Everything that could differ between the module copy and the extension copy was
checked and found identical / valid:

| Check | Result |
|---|---|
| Decompressed CRC-32 vs the module's copy | **identical** (`b9e42a8f`) |
| Whole-archive integrity (`unzip -t`) | clean |
| ZIP entry framing (method, data descriptor, flags) | identical to VASSAL's own entries (deflate + data descriptor) |
| `ImageIO.read` from the extension zip | **succeeds** — 9602×8749, `TYPE_BYTE_INDEXED` (a palette PNG) |
| Decode + convert to `TYPE_INT_RGB` at a 512 MB heap | succeeds (~414 MB; the source is 1 byte/pixel indexed) |
| The `Board` element referencing it | identical attributes (`image="CEMOD AMERICAS Centre.png" name="Americas AiF Main Inset"`), same `ZonedGrid` children |
| Grafting | inside `<ExtensionElement target="…Map:World Maps/…BoardPicker:Choose Boards">` — a valid graft |

The only physical difference from the original entry is that the utility
re-compresses the image, producing a deflate stream 7 bytes larger — the
decompressed bytes (and thus the PNG) are identical.

> **The "ark can't preview it" symptom is a red herring.** ark (Qt) refuses to
> preview because the image exceeds Qt's default 128 MB `QImageReader`
> allocation limit (9602×8749×4 ≈ 336 MB as ARGB). Extracting and opening it in a
> normal viewer works, and Java's `ImageIO` reads it fine. It is not corrupt.

## How VASSAL displays large board images (the tiler)

VASSAL does not draw huge board images directly; it slices them into a **disk
tile cache** and renders from the tiles. The relevant engine code (paths under
`vassal-app/src/main/java/VASSAL`):

1. **At module launch** (`launch/AbstractLaunchAction`), VASSAL runs an image
   **tiler subprocess** — first for the module, then once **per active
   extension** (`ExtensionsManager.getActiveExtensions()`). Each writes tiles
   into one shared cache dir keyed by the *module's* name+version:
   `<cache>/tiles/<sha1(moduleName_moduleVersion)>`.
2. `launch/TilingHandler` decides what to tile (`findImages`): for each image it
   reads the dimensions (`ImageUtils.getImageSize` → PNG header only) and, if the
   image is bigger than one tile, adds it to the to-tile list. **The tiler's
   image key is the full path `images/<name>`** (`getImageNameSet(true, true)`).
3. The subprocess (`tools/image/tilecache/ZipFileImageTiler`) loads each image
   (forcing `TYPE_INT_RGB`) and writes tiles named `sha1("images/<name>(x,y@1:div")`.
   It exits **2 on `OutOfMemoryError`, 1 on any other exception**; `TilingHandler`
   retries with +50 % heap up to the limit, then gives up and throws.
4. **At render** (`tools/imageop`), a board image becomes a
   `SourceOpTiledBitmapImpl` (via `Op.loadLarge`). It resolves the name with
   `ResourcePathFinder.findImagePath` → `images/<name>`, then reads tiles from
   `GameModule.getImageTileSource()`, whose cache dir is
   `<cache>/tiles/<sha1(getGameName()_getGameVersion())>` — **the same key** the
   tiler used. A tiled op has **no fallback to reading the whole image**: if a
   tile file is missing, that tile is blank.

Because the render-time key (`images/<name>`) and cache dir
(`sha1(moduleName_moduleVersion)`) exactly match what the tiler writes — and are
independent of whether the image came from the module or an extension — a
correctly-tiled extension image renders identically to a module image.

## So why does a moved image fail?

Given the bytes, the `Board` reference, the tile key and the cache dir are all
identical between module and extension, the difference has to be that **the
extension's copy never got tiled** (or its tiles are missing), and — because the
tiled render path has no whole-image fallback — the board then draws blank.

Candidate mechanisms in the VASSAL engine, in order of likelihood:

1. **The extension isn't tiled at the moment tiling runs.** Tiling happens once,
   at module launch, over `getActiveExtensions()`. An extension that is enabled
   *after* launch, or not active when the module is launched, never has its large
   images tiled. Clearing the tile cache does **not** fix this — it only forces a
   re-tile of what is tiled at the *next* launch, which still excludes an
   inactive extension.
2. **A silently-skipped image.** `TilingHandler.findImages` catches per-image read
   failures into a `failed` list and `continue`s — **but that list is never
   logged or surfaced**. An image that fails `getImageSize` is quietly left
   un-tiled and later renders blank with no error. This is a genuine robustness
   gap in the engine (worth reporting upstream): the user gets a blank image and
   no diagnostic.
3. **Tiler give-up.** If the subprocess exits non-zero and retries exhaust the
   heap limit, tiling throws and the launch aborts — so this normally shows as a
   failed launch, not a silent blank. (Memory was ruled out here: the image is
   indexed and tiles in ~414 MB.)

None of these are caused by the utility: the artifact it produces is a valid,
correctly-referenced image, indistinguishable from one VASSAL itself would write.

## What the errorLog + tile cache actually showed

For the reported case (VASSAL 3.7.24), the `errorLog` and cache were inspected
directly, which **narrows the cause to VASSAL's render side and clears both the
utility and the tiler**:

- The tiler subprocess **succeeded**:
  `FileArchiveImageTiler - Tiled images/CEMOD AMERICAS Centre.png` (no OOM; the
  reported tiler heap was 24 GB, and the image is a `TYPE_BYTE_INDEXED` PNG that
  decodes in ~414 MB anyway).
- The cache dir `~/.VASSAL/tiles/700ae41c…` **is** `sha1("WiF CE Official Combo_2.0.r")`
  — the module's name+version, exactly what both the tiler and the renderer key
  on — and it contains **all 1330 1:1 tiles plus every reduced scale (1:2…1:32)**
  for `images/CEMOD AMERICAS Centre.png`, all non-empty. (The bare-name key
  `CEMOD AMERICAS Centre.png` — no `images/` — does *not* exist, confirming the
  tiler used the full-path key the renderer also uses.)

So the tiles the renderer needs are present and correctly named. The image still
not drawing is therefore a **render-time bug in the engine**, not missing/mis-keyed
tiles and not anything in the archive the utility wrote.

## The likely engine bug: the tiled-vs-direct decision is made once, at build time

`Board.setAttribute(IMAGE, …)` decides how the board image will be loaded **at the
moment the board component is built**:

```java
// VASSAL/build/module/map/boardPicker/Board.java
tiled = ts.tileExists("images/" + imageFile, 0, 0, 1.0);
boardImageOp = tiled ? Op.loadLarge(imageFile)   // tiled render path
                     : Op.load(imageFile);        // whole-image render path
```

`tileExists` is just `new File(tilePath).isFile()`. The result is cached on the
board and **never re-evaluated**. Consequences:

- A board built while its tiles already exist → tiled path → renders fine. This is
  the normal case for **module** board images (their tiles persist between runs).
- A board built *before* its tiles exist → whole-image path (`Op.load`). For a
  huge board image the whole-image path can fail to render (blank), and the board
  stays on that path for the whole session no matter that tiles appear moments
  later. This is the fragile case for a board image that was **just moved into an
  extension** and has never been tiled before.

This build-time, one-shot decision — combined with the tiled path having no
whole-image fallback and the whole-image path struggling on a ~84-megapixel image
— is the most likely reason the same image draws from the module but not (yet)
from the extension. It is an engine issue worth reporting upstream.

### Practical remedy

Because the decision is re-made every launch from whether tiles exist *then*, and
the tiles now exist, simply **launch the module / load the game again**: on the
next launch the extension board is built with its tiles already present, so it
takes the tiled path and should draw. (Do **not** clear the tile cache first —
that removes the tiles and reintroduces the "built before tiled" race.) If a
second launch still shows it blank, capture the `errorLog` and report it to the
VASSAL project with this analysis; there is nothing the utility can change to
affect it, since the archive and the generated tiles are already correct.

## Diagnosing it on your machine

The engine records the tiler subprocess output. **Reproduce the problem, then
read VASSAL's `errorLog`** (in the VASSAL configuration directory — e.g.
`~/.VASSAL/errorLog` on Linux, or the folder shown under *Help → Show error log*
/ the Module Manager). Look for lines such as:

- `Tiling <images/CEMOD AMERICAS Centre.png>` — confirms the tiler attempted it;
- `Tiling failed with return value == …` / `ran out of memory` — a tiler failure;
- no mention of the image at all — it was never offered to the tiler (extension
  not tiled), which points at mechanism (1) above.

Then:

- **Ensure the extension is enabled/active before launching the module**, launch
  it once (let tiling run), and only then load the game. If that fixes it, it was
  mechanism (1).
- If the `errorLog` shows a tiler exception or "ran out of memory", raise
  *Global Preferences → Maximum heap for image tiler* and relaunch.
- If the image is offered to the tiler and still fails with no error, that is an
  engine bug (silent skip / no whole-image fallback) — capture the `errorLog`
  and report it to the VASSAL project; it is not something this utility can fix.

## Relationship to the earlier mtime fix

The utility was separately corrected to **preserve image modification times** on
save (see [AGENTS.md → Image modification times & tiling](../AGENTS.md)), because
the tiler's freshness check is mtime-based. That fix is still correct and keeps a
moved image from forcing a needless re-tile, but it is independent of the
rendering behaviour described here.
