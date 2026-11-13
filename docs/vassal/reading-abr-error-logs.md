# Reading the error log attached to a VASSAL bug report

An automated bug report (an issue labelled **ABR** on [vassalengine/vassal](https://github.com/vassalengine/vassal/issues)) quotes a stack trace in its body and links to the reporter's full error log. The quoted stack is frequently useless on its own — it is truncated, and the `Caused by` that says what actually went wrong is only in the attachment.

Fetching that attachment trips people up, because it is stored **brotli-compressed** behind a redirect. Get the fetch wrong and you see either nothing or binary noise, neither of which looks like "you asked for it the wrong way".

## The command

```bash
curl -sL --compressed "https://vassalengine.org/files/gh/<sha>" -o abr.log
```

Both flags are needed, and leaving either out fails differently:

| | result |
|---|---|
| `curl -sL --compressed` (front URL) | **the log** |
| `curl -s --compressed`, no `-L` | **0 bytes** — the front URL is a 301 with an empty body |
| `curl -sL`, no `--compressed` | **raw brotli** — binary noise starting `1b 62 10 60` |
| `curl -s --compressed` on `https://obj.vassalengine.org/tracker/gh/<sha>` | the log (the redirect target, if you would rather skip the hop) |

`--compressed` only asks for brotli if your curl was built with it; check with `curl -V | grep brotli`. Without it, fetch the bytes and decompress separately (Python's `brotli` module, or the `brotli` CLI).

A quick way to tell you have it right: the issue body states the log's size, e.g. "Error Log | 7924 bytes". That is the **decompressed** size, so it is a direct check that the decode worked. The compressed object is several times smaller — 1,386 bytes in that example.

## Why it is worth the trouble

Two recent reports had no cause at all in the issue body.

**[#15297](https://github.com/vassalengine/vassal/issues/15297)** quoted four frames ending at `ModuleManager.launch`. The log had the rest:

```
Caused by: java.lang.NullPointerException: Cannot invoke
"sun.awt.shell.Win32ShellFolder2.getIShellFolder()" because the return value of
"sun.awt.shell.Win32ShellFolderManager2.getNetwork()" is null
```

— an unreachable Windows shell folder, reached through a `JFileChooser` the preferences built during start-up. Nothing in the issue body pointed anywhere near it.

**[#15298](https://github.com/vassalengine/vassal/issues/15298)** quoted an `ExecutionException` wrapping an `ArrayIndexOutOfBoundsException` with no location. The log gave the frame that mattered, `FixedScaleOpTiledBitmapImpl$TileOp.eval`, which is the whole bug.

## What else the log gives you

Beyond the full stack, the surrounding lines usually settle questions the report does not answer:

- **OS, Java and VASSAL versions**, and whether the bundled JRE is in use (`Java home C:\Program Files\VASSAL-3.8.0\jre`).
- **Which module and version**, and the path it was loaded from — often a different module from the one named in the issue title, because the log covers the whole session.
- **`TilingHandler` lines** — `No images to tile` versus a tiling run, which matters for anything touching the image pipeline.
- **Earlier warnings** that did not crash anything, such as `Source: (No Image) Error: Image not found`.
- **Timestamps.** The gap between loading the module and the failure tells you whether it happened during load or minutes later in play. In #15298 it was 43 seconds after load, during a repaint, which ruled out the load path.

Treat everything in the log as the reporter's own data: it carries their file paths and Windows user name.
