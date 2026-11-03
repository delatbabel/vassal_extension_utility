# Why modules and extensions did not shrink, and what would actually shrink them

Measured on 2026-11-03 against 248 archives (modules plus their extensions) totalling ~8 GB of image data, with the VASSAL engine at `master` (after `feature/compress-then-obfuscate-vsav-file` merged).

**Short version:** a saved game is almost entirely text, so compressing the command log transformed it. A module is almost entirely image bytes — `buildFile.xml` is **0.2–0.6%** of a `.vmod` — so no amount of work on trait encoding or container compression can move it. The one real win measured is lossless PNG re-filtering of large RGB images, worth **1.92×** on the biggest module in the collection, and it is an authoring fix rather than an engine fix.

## 1. Where the bytes actually are

Entries grouped by kind, raw and as stored in the ZIP:

| Module | File | images | `buildFile.xml` raw → zip | buildFile as % of file |
|---|---|---|---|---|
| WiF CE Official Combo 2.1.5 | 32.2 MB | 31.6 MB (99.4%) | 3.02 MB → 0.196 MB | **0.6%** |
| EuropaNewMapV0-92-0 | 252.4 MB | 251.8 MB (99.8%) | 6.45 MB → 0.42 MB | **0.2%** |
| World in Flames 5th Ed 2.1.0 | 88.9 MB | 86.1 MB (97.7%) | 2.89 MB → 0.18 MB | **0.2%** |

Extensions are more extreme still, because they carry almost no structure of their own:

| Extension | File | images | buildFile |
|---|---|---|---|
| `05-SSW-EURO-Maps.vmdx` | 113.2 MB | 113.17 MB (100.0%) | 0.02 MB |
| `06-SSW-APAC-Maps.vmdx` | 103.7 MB | 103.69 MB (100.0%) | 0.01 MB |
| `01-EURO-Maps.vmdx` | 38.3 MB | 38.26 MB (100.0%) | 0.01 MB |
| `23-DoD-III.vmdx` | 28.9 MB | 28.82 MB (99.9%) | 0.02 MB |

The WiF 2.1.5 module plus all 27 of its extensions is 526 MB of compressed image data against roughly 1 MB of compressed XML.

## 2. What the two open PRs would do to module size

Both are worth merging for the reasons they were written, but neither is a module-size change.

**[#15116](https://github.com/vassalengine/vassal/pull/15116), `feature/flat-trait-chain-encoding`** — removes the O(traits²) backslash escaping. Measured on the WiF 2.1.5 `buildFile.xml`: 189,156 backslashes, 6.3% of the raw bytes. Deleting *every* backslash in the file (an upper bound the real change does not reach) takes it from 3.02 MB to 2.83 MB raw, and from **0.196 MB to 0.185 MB** compressed — an 11 KB saving, **0.035% of the 32 MB module**. Its value is runtime cost and `.vsav` size, where the command log is the whole file; on a `.vmod` it is unmeasurable.

**[#15119](https://github.com/vassalengine/vassal/pull/15119), `feature/share-immutable-trait-data`** — shares a trait's parsed type data between instances at runtime. It changes heap occupancy, not serialised bytes: **zero** effect on file size, by construction.

**Applying XZ to `buildFile.xml`** (the analogue of what was done for saved games) takes the WiF buildFile from 0.196 MB to 0.127 MB — a 69 KB saving, **0.2% of the module**, in exchange for breaking every existing reader of the format.

## 3. The container is already doing what it can

`ZipArchive` sets `zout.setLevel(9)` and writes entries `DEFLATED` (`vassal-app/src/main/java/VASSAL/tools/io/ZipArchive.java:290,466`). `ArchiveWriter.addImage` hands straight to `addFile`, so **image bytes are stored exactly as the designer supplied them** — the engine never re-encodes an image.

Deflating already-entropy-coded PNG/GIF/JPEG recovers 0.6–7%, so the container is not leaving anything on the table. (Storing images `STORED` instead would make saving appreciably faster at a cost of a percent or two of size, but that is a speed trade, not a size one.)

## 4. The image corpus, by PNG colour type

All PNGs ≥ 1 MB across 248 archives — 814 images, 8.1 GB:

| Colour type | Count | GB | Share | Lossless gain measured |
|---|---|---|---|---|
| palette8 | 477 | 6.651 | **82.5%** | **1.00× — already optimal** |
| RGB8 | 191 | 0.752 | 9.3% | **1.4× – 3.3×** |
| RGBA8 | 125 | 0.582 | 7.2% | **1.00× — already optimal** |
| palette4 | 21 | 0.078 | 1.0% | — |

Most VASSAL module art is already well authored. Verification of the two "optimal" rows, all pixel-identical round trips with `optipng -o2`:

- 200 WiF counter PNGs (palette8): 2.244 MB → 2.242 MB, **1.00×**
- WiF `EEuropea.png`, 12052×16564 palette8, the single largest image in the collection: 51.24 MB → 51.16 MB, **1.00×**
- 40 largest RGBA8 images from `23-DoD-III.vmdx`: 9.126 MB → 9.126 MB, **1.00×**

## 5. The one real win: PNG filter choice on large RGB images

The Europa modules are the outlier, and the cause is specific and verifiable. A PNG stores a filter byte per scanline; `optipng` searches filter strategies, and on flat-colour map art the right answer is almost always **None**, because any predictive filter turns long runs of identical pixels into non-identical residuals that deflate can no longer match.

Reading the filter bytes of the first 300 scanlines:

| Image | Filters used |
|---|---|
| `EUROPA Map24.png` as shipped | **Paeth 98%**, Sub 2% |
| the same image after `optipng -o2` | **None 100%** |
| WiF `EEuropea.png` as shipped | **None 100%** |

That is the whole story: WiF's maps were exported with the filter that suits them and cannot be improved; Europa's were exported with adaptive filtering that chose Paeth, and pay roughly double for it.

Measured, pixel-identical, on Europa's four largest maps:

| Image | Before | After | Ratio |
|---|---|---|---|
| `EUROPA Map24.png` 6431×9072 | 35.87 MB | 17.84 MB | 2.01× |
| `EUROPA Map23.png` | 33.15 MB | 17.35 MB | 1.91× |
| `EUROPA Map13.png` | 20.18 MB | 10.17 MB | 1.98× |
| `EUROPA Map21.png` | 16.33 MB | 9.55 MB | 1.71× |
| **total** | **105.53 MB** | **54.90 MB** | **1.92×** |

`EuropaNewMapV0-92-0.vmod` holds 231 MB of RGB8 images out of 252 MB. At the measured 1.92× that module would fall to roughly **142 MB — a 44% reduction with no visible change whatsoever.** `optipng -o2` is a conservative setting; `oxipng -o max --zopfli` or `zopflipng` would do better still, so treat 1.92× as a floor.

The gain is not uniform within the class — it tracks how flat the art is. Across a mixed spread of smaller Europa RGB8 images: `EUROPA Map41.png` 3.28×, `ge-weather-table.png` 2.12×, but `air-unit-identification.png` 1.05× and `sf-naval-gun-table.png` 1.05× (both effectively photographic, where Paeth genuinely is the right filter).

## 6. Things that looked promising and are not

- **Deduplicating images across a module and its extensions.** Across WiF 2.1.5 plus all 27 extensions: 355 duplicate-content groups, 319 of them spanning more than one archive — but only **2.0 MB of 526 MB (0.4%)**. The duplicates are small shared markers, not maps. ZIP cannot share entries between archives anyway.
- **Stripping ancillary PNG chunks** (`tEXt`, `iCCP`, colour profiles): 0.18 MB across the whole WiF module, 0.13 MB across Europa. Noise.
- **Reducing palette bit depth.** Every large palette8 map checked uses 255 or 256 entries, so none can drop to 4-bit.
- **Re-encoding with PIL / Java ImageIO defaults.** Re-saving `EUROPA Map24.png` at zlib level 9 gives 98.5% of the original, and PIL's `optimize=True` gives **104.8%** — *worse* than shipped. A naive re-encode is not the same thing as a filter search, which is why this has to be an explicit optimisation pass and not a side effect of saving.

## 7. A lossy option, stated honestly

`EUROPA Map24.png` contains **468 distinct colours** in 58.3 megapixels. PNG palettes cap at 256, so it cannot be palettised losslessly. But the colour distribution is extremely skewed — the 212 rarest colours cover **0.131% of pixels** (76,299 of 58,342,032), which is antialiasing fringe.

Building a palette from the exact 256 most frequent colours and mapping the remainder to their nearest neighbour gives **35.87 MB → 13.09 MB, 2.74×**, with 0.131% of pixels moved and a worst-case per-channel error of 60/255 on the rarest colours. Compare a generic median-cut quantiser, which reaches a similar 13.08 MB but alters **42% of pixels** — the method matters far more than the target palette size.

This is a module-author's judgement call, not something the engine should ever do silently. The lossless 1.92× needs no such judgement and should come first.

## 8. What is worth doing

1. **Run a lossless PNG optimiser over module and extension art before publishing.** This is the entire measurable win and it needs no engine change. The utility now does it in place — **Tools → Optimise Images (left/right)…** narrows the colour type where that loses nothing, searches the filter strategies, and verifies every result against the original pixel by pixel before accepting it; `oxipng -o max --strip safe *.png` or `optipng -o2` do the same job outside it. On Europa it is worth ~110 MB per release; on WiF it is worth nothing, because that art is already right.
2. **Consider an engine-side filter search on image import.** `ArchiveWriter.addImage` copies bytes verbatim; a lossless re-filter pass at import would have prevented the Europa case at source. It is a genuine engine improvement, but it is a convenience — it does nothing that step 1 does not.
3. **Do not pursue XZ for `buildFile.xml`, or expect #15116 or #15119 to shrink modules.** Both PRs are worth merging on their own terms — runtime cost and heap — but the combined upper bound on module size is well under 0.3%.

## See also

- [wif-save-bloat-analysis.md](wif-save-bloat-analysis.md) — the equivalent analysis for `.vsav` saved games, where the command log *is* the file and compression therefore transformed it
- [vassal/wif-engine-optimizations.md](vassal/wif-engine-optimizations.md) — proposed engine changes for saved-game memory and disk usage
- [vassal/image-display-and-tiling.md](vassal/image-display-and-tiling.md) — how VASSAL tiles these large board images, and why entry modification times matter when they are re-saved
