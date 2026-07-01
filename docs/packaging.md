# Building Installable / Executable Packages

This document describes how to build the distributable, installable packages of
the VASSAL Extension Utility, following the same conventions as the main
[VASSAL engine Makefile](../../vassal/Makefile).

All packaging is driven by the top-level `Makefile` and is intended to run on a
Linux host (Ubuntu 26.04 LTS or later, matching the development machine). The
package types mirror those on the [VASSAL download page](https://vassalengine.org/download.html):

| Package | Target | Tool | Cross-build? |
|---|---|---|---|
| Linux `.deb`   | `release-linux-deb`     | `jpackage`               | native only |
| Linux `.rpm`   | `release-linux-rpm`     | `jpackage` + `rpmbuild`  | native only |
| Windows `.exe` (x86_64, aarch64, x86_32) | `release-windows[-<arch>]` | Launch4j + `jlink` | yes, from Linux |
| macOS `.dmg` (x86_64, aarch64) | `release-macos[-<arch>]` | `genisoimage` + libdmg-hfsplus + `jlink` | yes, from Linux |

Built packages are written to the `tmp/` directory. `make release` builds them
all; `make release-sha256` writes a checksum file over whatever is present.

---

## Prerequisites

The build machine is assumed to be **Ubuntu 26.04 LTS or later**. Two classes of
prerequisite exist: system packages (installed with `apt`, needing root) and
per-project tools (downloaded into `dist/`, needing no root).

### 1. System packages (root / `sudo`)

| Needed for | Package(s) | Provides |
|---|---|---|
| Everything | `openjdk-21-jdk` | `jpackage`, `jlink`, `jmods` (a JDK that can link a runtime) |
| Building the fat JAR | *(none — uses the bundled `./mvnw`)* | |
| `.deb` | `fakeroot`, `dpkg` (usually preinstalled) | Debian package assembly |
| `.rpm` | `rpm` | `rpmbuild` |
| macOS `.dmg` | `genisoimage` | Apple-format ISO creation |
| Building libdmg-hfsplus (see below) | `git`, `gcc`, `make`, `libssl-dev`, `zlib1g-dev` | compile the `dmg` tool |

Install them all in one go:

```bash
sudo apt-get update
sudo apt-get install -y \
    openjdk-21-jdk \
    fakeroot dpkg \
    rpm \
    genisoimage \
    git gcc make libssl-dev zlib1g-dev unzip zip curl
```

Notes:
- A **JDK that ships `jmods`** is required. The system's default JDK may be a
  runtime-only image (no `jmods`), which `jpackage` cannot link from — hence
  `openjdk-21-jdk`. The Makefile auto-detects the newest JDK under
  `/usr/lib/jvm/*` that has both `jmods` and `jpackage`; override with
  `make JPACKAGE_JDK=/path/to/jdk` if needed.
- `rpmbuild` is only needed for the `.rpm` target. If you cannot install `rpm`
  (e.g. no root), every other target still works; `release-linux-rpm` will
  print a clear message and stop.
- If you are an unprivileged user **with `sudo`**, the commands above are the
  same. If you have **no root at all**, you can still build `.deb` (uses
  `fakeroot`/`dpkg`, normally already present), the Windows packages, and the
  macOS packages — only the `.rpm` target and the initial CMake/OpenSSL
  toolchain for libdmg-hfsplus require system packages. See the notes under
  "Cross-build tools" for the no-root path.

### 2. Cross-build tools (no root — fetched into `dist/`)

Windows and macOS packages are cross-built on Linux and need extra tools plus a
JDK for each target platform. These are downloaded and built into `dist/tools/`
and `dist/jdks/` (both git-ignored) by a single command:

```bash
make bootstrap        # or: dist/bootstrap.sh
```

This fetches, with **no root required**:

- **Launch4j 3.50** — wraps the fat JAR into a Windows `.exe`
  (from [SourceForge](https://sourceforge.net/projects/launch4j/files/launch4j-3/3.50/)).
- **CMake** (portable build) — only used to compile the next item.
- **libdmg-hfsplus** (`dmg` tool) — converts an Apple ISO into a compressed
  `.dmg`. Built from the [fanquake fork](https://github.com/fanquake/libdmg-hfsplus),
  which compiles cleanly against OpenSSL 3. Requires `gcc`, `make`, and the
  `libssl-dev`/`zlib1g-dev` headers listed above.
- **Temurin JDKs** for each target platform (Windows x64/aarch64/x86-32, macOS
  x64/aarch64) from [Adoptium](https://adoptium.net/). Their `jmods` feed
  `jlink` to build the bundled runtimes. Windows 32-bit uses a Java 17 JDK (the
  last with a 32-bit Windows build); the rest use Java 21.

`make bootstrap` is idempotent — anything already present is skipped. It is
**not** needed for the Linux `.deb`/`.rpm` targets.

> No-root alternative for libdmg-hfsplus: CMake is already downloaded as a
> portable binary by `bootstrap`, so no `cmake` package is required. Only the
> `libssl-dev` and `zlib1g-dev` **headers** need to be present to compile the
> `dmg` tool; on a machine without them and without root, copy a prebuilt `dmg`
> binary to `dist/tools/libdmg-hfsplus/build/dmg/dmg`.

---

## Version numbering

Versioning mirrors `../vassal/Makefile`.

- **`VNUM`** at the top of the `Makefile` is the numeric version and the single
  source of truth (e.g. `1.0.0`). Bump it for each release — either by editing
  the line directly or with `make version-bump` (see below).
- **`MAVEN_VERSION`** is the pom version — normally `VNUM`, or `VNUM-SNAPSHOT`
  between releases (uncomment the alternative line).
- **`VERSION`** is the full, unique build identifier derived from git and used
  in artifact filenames:
  - on a release tag equal to `MAVEN_VERSION` → just the version (e.g. `1.0.0`);
  - on a `release-*` branch → `VERSION-<commit>`;
  - anywhere else → `VERSION-<commit>-<branch>` (e.g. `1.0.0-6153f9b-main`).

Useful targets:

```bash
make version-print     # print the full build VERSION
make version-bump      # bump the patch version by 0.0.1 (e.g. 1.0.0 -> 1.0.1)
make version-set       # set the pom.xml version to MAVEN_VERSION
make post-release      # re-apply the pom version after a release (alias of version-set)
```

`make version-bump` increments the last (patch) component of `VNUM` — carrying
correctly, e.g. `1.0.9` → `1.0.10` — rewriting the `VNUM` line in the `Makefile`
**and** setting the pom version to match in one step, so the build stays
consistent. For a larger bump (a new minor or major version), edit `VNUM`
directly and run `make version-set`.

Release flow: run `make version-bump` (or edit `VNUM`), commit, tag as
`MAVEN_VERSION`, then `make release`.

---

## Building each package

### Linux `.deb` / `.rpm`

```bash
make release-linux-deb     # -> tmp/vassal-extension-utility_<VNUM>_amd64.deb
make release-linux-rpm     # -> tmp/vassal-extension-utility-<VNUM>-1.<arch>.rpm
make release-linux         # both
```

`jpackage` bundles a minimal Java runtime automatically, so the resulting
package is self-contained (no separate Java install needed by the end user).

### Windows `.exe`

```bash
make bootstrap             # once, to fetch Launch4j + Windows JDKs
make release-windows-x86_64
make release-windows-aarch64
make release-windows-x86_32
make release-windows       # all three
```

Each target produces `tmp/VASSAL-Extension-Utility-<VERSION>-windows-<arch>.zip`
containing `VASSAL-Extension-Utility.exe` (a Launch4j wrapper) alongside a `jre/`
directory — a `jlink` runtime built from that architecture's Windows JDK — plus
the JAR, `README.md` and `LICENSE`. The `.exe` launches the bundled runtime, so
end users need no installed Java.

### macOS `.dmg`

```bash
make bootstrap             # once, to fetch libdmg-hfsplus + macOS JDKs
make release-macos-x86_64
make release-macos-aarch64
make release-macos         # both
```

Each target assembles a `VASSAL Extension Utility.app` bundle (with a `jlink`
runtime under `Contents/MacOS/jre` and the JAR under `Contents/Resources/Java`),
builds an Apple-format ISO with `genisoimage`, and compresses it into
`tmp/VASSAL-Extension-Utility-<VERSION>-macos-<arch>.dmg` with the libdmg-hfsplus
`dmg` tool. Dragging the app to the `/Applications` shortcut installs it.

### Checksums / everything

```bash
make release           # deb + rpm + all windows + all macos
make release-sha256    # SHA-256 sums over all packages in tmp/
make clean-release     # remove tmp/
```

---

## How it maps to the VASSAL engine build

| VASSAL engine (`../vassal/Makefile`) | Here |
|---|---|
| `VNUM`, `MAVEN_VERSION`, git-derived `VERSION`, `version-set`, `version-print`, `post-release` | Same, simplified |
| `bootstrap.sh` downloads JDKs + builds `dmg` | `dist/bootstrap.sh`, `make bootstrap` |
| jlink runtime via `jdeps`/module list | `APP_MODULES` + `jlink` |
| Launch4j `.l4j.xml.in` → `.exe` | `dist/windows/launch4j.xml.in` → `.exe` |
| `genisoimage` + libdmg-hfsplus `dmg` for macOS | Same tools |
| NSIS installer | *not used* — a zip with a bundled JRE is shipped instead |
| Per-arch Windows/macOS builds | Same (`%`-pattern rules per architecture) |

The main simplifications versus the engine: this is a single self-contained fat
JAR (no native components), Linux packages use `jpackage` (which did not exist
when the engine's Makefile was written) to produce real `.deb`/`.rpm` installers
rather than a tarball, and no NSIS installer or release-announcement machinery is
included.
