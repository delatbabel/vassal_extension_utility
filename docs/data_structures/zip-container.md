# The ZIP container

`.vmod`, `.vmdx` and `.vsav` are all ordinary ZIP archives. Nothing about the
container is unusual — no custom header, no encryption, no self-extracting
prefix — and any ZIP tool can open one:

```bash
unzip -l  Module.vmod
unzip -p  Module.vmod moduledata
unzip -t  save.vsav          # integrity check; a truncated file fails here
```

What matters is the **entry inventory**, the **entry metadata**, and two rules
that a rewriting tool must honour.

## Entry inventory

| Format | Required entries | Optional entries |
|---|---|---|
| `.vmod` | `buildFile.xml`, `moduledata` | `images/…`, root-level `*.vsav`, `*.txt` / `*.html` help files |
| `.vmdx` | `buildFile.xml`, `moduledata`, `extensiondata` | `images/…`, root-level `*.vsav` |
| `.vsav` | `savedGame`, `savedata`, `moduledata` | — |

Real numbers from the sample module (`EuropaNewMapV090.vmod`, 116 MB):

```
1303 entries
1297 under images/
   6 at the root: buildFile.xml, moduledata, BlankScenario.vsav,
                  Help.txt, Intro.txt, This Europa Module includes.txt
```

Note the shape: a module is **99.5% images by entry count**. Every structural
question — the component tree, the piece definitions — lives in a single
`buildFile.xml`, and every tool that edits structure rewrites that one entry and
copies the other 1302 through.

Three properties of the inventory are load-bearing:

- **There are no directories, only prefixes.** `images/Green RailEng.png` is one
  flat entry name; the ZIP holds no directory records. An image is referenced
  from the XML by its bare filename, and the `images/` prefix is added by
  VASSAL's `DataArchive`.
- **A pre-defined setup's save file sits at the root**, not under `images/`, and
  is named by the literal value of the component's `file` attribute. It usually
  ends `.vsav` but is not required to.
- **Every entry is deflated** (ZIP method 8) in files VASSAL writes — including
  images that are already compressed formats.

## Entry metadata

Two fields of the ZIP entry header matter to VASSAL.

### Modification times are a cache key

VASSAL renders a large board image through a **disk tile cache**
(`~/.VASSAL/tiles/<sha1(moduleName_moduleVersion)>`) and decides whether a
cached tile is still valid purely by comparing timestamps:
`TilingHandler.isFresh()` re-tiles an image whenever its ZIP-entry mtime is
newer-or-equal to the cached tile's.

So a rewrite that stamps every entry with the current time invalidates the whole
tile cache, forcing a re-tile of every board image on the next load. For a
tens-of-megapixel board that re-tile can fail or leave the map blank even though
the image bytes are perfectly intact.

**Rule:** copy every surviving entry with its source `getTime()`, and carry an
image's original mtime with it when copying between archives. `new
ZipEntry(name)` without setting the time stamps "now" — that is the bug this
rule exists to prevent.

Both the module and its active extensions tile into the *same* cache, keyed by
the **module's** name and version, which is why an extension's images are
subject to the same rule. Full pipeline analysis:
[docs/image-display-and-tiling.md](../image-display-and-tiling.md).

### Sizes tell you what encoding was used

The sample save `BlankScenario.vsav`:

```
savedGame    14731 bytes raw  ->  1693 deflated
savedata       211            ->   140
moduledata     287            ->   181
```

14731 is not arbitrary. The deobfuscated command log is **7362 bytes**, the
entry is in the legacy `!VCSK` format, and that format writes a 5-byte header, a
2-character key and **two hex characters per plaintext byte**:

```
5 + 2 + (7362 × 2) = 14731
```

That doubling into a 16-symbol alphabet is exactly what the modern `VOBS` format
exists to avoid — see [vsav.md](vsav.md#the-obfuscation-envelope).

## The two writer rules

### Write through a temp file

Every writer in this project — `VassalArchive.writeArchive`,
`SavedGame.saveWithout`, `tools/swap_maps.py::write_vsav` — builds the new
archive at a temporary path and then moves it into place with an atomic replace.

The failure this prevents is specific: a partially written `.vsav` is a
partially written ZIP, and VASSAL reports it as *"… is not a VASSAL saved game
or log."* A user who interrupted a save and now has an unreadable scenario
cannot tell that from a corrupt one.

### Never leave a spare archive in `_ext/`

`ExtensionsManager`'s file filter is only `!isHidden() && !isDirectory()`:
VASSAL loads **every** file in `<module>_ext/` whose metadata parses as an
extension, whatever it is called. A `foo.vmdx.bak` or `Copy of foo.vmdx` left
beside the real one is loaded as a second, real extension — which duplicates
every GPID it defines and breaks Refresh Counters.

Backups therefore belong in `<module>_ext/backups/`. Directories are skipped by
that filter, and only `inactive` is also scanned — which is the same mechanism
the application's **Show Extensions** dialog uses to deactivate an extension:
moving the file into `_ext/inactive/`.

## Metadata entries

`moduledata`, `savedata` and `extensiondata` are all the same small XML shape,
written by VASSAL's `AbstractMetaData`:

```xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<data version="1">
  <version>0.90</version>
  <extra1/>
  <extra2/>
  <VassalVersion>3.7.15</VassalVersion>
  <dateSaved>1745847514547</dateSaved>
  <description>Add more charts, extensions, update scenarios</description>
  <name>Europa Series New Map</name>
</data>
```

| Field | Present in | Meaning |
|---|---|---|
| `version` | all | The archive's own version string |
| `VassalVersion` | all | The engine version that last wrote the file |
| `dateSaved` | all | Unix milliseconds |
| `description` | all | Free text |
| `name` | `moduledata` only | The module's display name |
| `universal` | `extensiondata` only | The `anyModule` flag |

`<data version="1">` is the *metadata schema* version, not the module's — it has
been `1` throughout.

The discriminator is the **entry name**, not the content: `moduledata` and
`savedata` in a `.vsav` have almost the same shape, and only `moduledata`
carries `<name>`. See [vsav.md](vsav.md#moduledata-versus-savedata) for why a
save carries both.

## See also

- [vsav.md](vsav.md) · [vmod.md](vmod.md) · [vmdx.md](vmdx.md)
- [docs/image-display-and-tiling.md](../image-display-and-tiling.md)
