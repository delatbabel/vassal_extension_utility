# Developer's Guide

How to build, package and release the VASSAL Extension Utility, and where the
technical documentation lives. For what the application does and how to use it,
see the [README](README.md); for the architecture, see [AGENTS.md](AGENTS.md).

## Building

Requires Java 11+ and Maven 3.x.

```bash
make jar     # build executable fat JAR
make run     # build and run
make clean   # remove build artefacts
make help    # show all targets
```

The fat JAR is produced at
`target/extension-utility-<version>-jar-with-dependencies.jar`.

You can also invoke Maven directly:

```bash
./mvnw package
java -jar target/extension-utility-*-jar-with-dependencies.jar
```

## Packaging / releases

The `Makefile` builds installable packages for each platform, matching the types on the [VASSAL download page](https://vassalengine.org/download.html):

```bash
make release-linux-deb       # Linux .deb            (jpackage)
make release-linux-rpm       # Linux .rpm            (jpackage; needs rpmbuild)
make bootstrap               # fetch Windows/macOS cross-build tools + JDKs (once)
make release-windows         # Windows installer .exe x86_64 / aarch64 / x86_32
                             #                       (Launch4j + makensis)
make release-macos           # macOS .dmg x86_64 / aarch64 (libdmg-hfsplus)
make release                 # all of the above
make release-sha256          # checksums
```

Packages are written to `tmp/` and bundle their own Java runtime. The Linux `.deb`/`.rpm` install to `/opt/vassal-extension-utility/` and put a `vassal_extension_utility` command on the user's `PATH` (via a `/usr/bin` symlink). Version numbering is managed via `VNUM` in the `Makefile` (`make version-print` / `version-bump` / `version-set`); `make version-bump` bumps the patch version by 0.0.1 (e.g. 1.0.0 → 1.0.1). See **[docs/packaging.md](docs/packaging.md)** for prerequisites (including how to install the required tools) and full details.

## File Format Documentation

- [vmod format](docs/vmod-format.md) — VASSAL module file structure
- [vmdx format](docs/vmdx-format.md) — VASSAL extension file structure
- [vsav format](docs/vsav-format.md) — VASSAL saved-game file structure (obfuscated command log + metadata)
- [vsav excess units](docs/vsav-excess-units.md) — finding and removing pieces missing from a module's active extensions (detection algorithm & file rewrite)
- [Excess Units guide](docs/excess-units-guide.md) — step-by-step how-to for the **Excess Units …** tool, e.g. dropping unwanted extensions (Convoys in Flames, Light Cruisers) from a saved game

## Additional Documentation

- [packaging.md](docs/packaging.md) — building installable packages (`.deb`/`.rpm`/`.exe`/`.dmg`) and the versioning scheme
- [image-display-and-tiling.md](docs/image-display-and-tiling.md) — how VASSAL tiles large board images and why moved images must preserve their modification times
- [vassal-empty-extensionelement-crash.md](docs/vassal-empty-extensionelement-crash.md) — why an empty `ExtensionElement` crashes VASSAL, and how the utility avoids leaving one behind
- [wif-save-bloat-analysis.md](docs/wif-save-bloat-analysis.md) — why the WiF module uses so much memory and produces huge `.vsav` saves (measured root causes: baked-in prototype expansion, O(traits²) escaping, embedded Place Marker, obfuscation-before-compression)
- [wif-module-optimizations.md](docs/wif-module-optimizations.md) — module-side changes to shrink memory/save size with no engine change and no loss of game features
- [wif-engine-optimizations.md](docs/wif-engine-optimizations.md) — proposed VASSAL engine changes to reduce saved-game memory and disk usage, tiered by effort, with `file:line` citations
- [docs/wif-fix2-trait-reduction.md](docs/wif-fix2-trait-reduction.md) — deep dive into reducing traits per WiF piece: which automation can move to map-level Global Key Commands, what prototype consolidation really buys, where the Marker constants come from, and the dead traits
- [docs/refresh-counters.md](docs/refresh-counters.md) — running VASSAL's own Refresh Counters over external saved games: why the engine runs in a subprocess, how it is bootstrapped, what must be preserved in each save, and why "module was saved with older vassal version" really means duplicate Piece Ids
- [tools/README.md](tools/README.md) — command-line scripts for editing VASSAL files outside the GUI: swapping a map layout between saves, translating pieces off a board that moved, swapping or adding mis-named counters' correct twins, clearing an extension's duplicate Piece Ids, deleting slots or whole extensions' worth of counters, reducing duplicated counters, auditing pieces that are on no map, and migrating a WiF 1.5.93 scenario to the 2.1.3 deluxe module
- [AGENTS.md](AGENTS.md) — architecture and developer guide (also symlinked as `CLAUDE.md`)
