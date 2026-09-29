> It might be worth checking zstd and brotli for size comparison.

Done, in Java (what the engine would run: `XZ for Java` 1.12, `zstd-jni` 1.5.7 native, `aircompressor` 2.0.3 as the only pure-Java zstd encoder, `brotli4j` 1.23 native), on the same 7 072-piece World in Flames log, each codec compressing into memory and verified on decompression, single-threaded, with the window settings that matter for this data.

**Today's text (223.7 MB):**

| Codec | Size | Compress | Decompress | Ships as |
|---|---:|---:|---:|---|
| deflate 9 (today) | 17.18 MB | 12.0 s | 0.8 s | JDK |
| **XZ for Java preset 3 (this PR)** | **0.83 MB** | **3.6 s** | 0.8 s | pure Java, 275 KB, in Debian |
| XZ for Java preset 6 | 0.66 MB | 49.0 s | 0.5 s | |
| zstd-jni −9 | 1.01 MB | 0.5 s | 0.1 s | native, one jar for 18 platforms, in Debian |
| zstd-jni −19 `--long=27` | 0.53 MB | 10.2 s | 0.2 s | |
| aircompressor zstd (pure Java) | 2.63 MB | 0.5 s | 0.3 s | pure Java, one level, small window |
| brotli4j q9, window 24 | **0.54 MB** | **1.5 s** | 0.2 s | native, one artifact per platform, not in Debian |
| brotli4j q11, window 24 | 0.47 MB | 82.2 s | 0.2 s | |

**With the flat chain (#15116), 109.7 MB:** deflate 7.01 MB / 2.3 s; xz 3 0.51 MB / 1.6 s; zstd −9 0.54 MB / 0.3 s; zstd −19 long 0.41 MB / 6.7 s; brotli q9 0.42 MB / 0.9 s; brotli q11 0.37 MB / 34 s.

**Load and save time.** The codec's share of a save is its compression time after the encoding; of a load, its decompression time before the decoding. Every codec here decompresses this log in under a second against two minutes of piece construction, so **load time is the same whichever is chosen**. On save, against the measured 35 s on `master` (12 s of it deflate) and 20.6 s on this PR: zstd −9 would be ≈ 17.5 s, brotli q9 ≈ 18.5 s, zstd −19 long ≈ 27 s, brotli q11 ≈ 99 s. A few seconds between the sensible settings, a lot for the slow ones.

**So:** brotli q9 is the best codec on this data — 35 % smaller than xz preset 3 and twice as fast — but its encoder is native (`brotli4j`: a native artifact per platform, twelve of them, extracted at run time; no Debian package, so the `.deb` could not be built). zstd −9 is fastest but 22 % larger than xz; zstd −19 with long matching is 36 % smaller at 10 s; both native (`zstd-jni` does ship one jar for every platform and is in Debian). The one pure-Java zstd, `aircompressor`, is three times xz's size. XZ for Java is the only pure-Java codec with a long window, and at preset 3 it is within 1.5× of brotli on both size and time, for 275 KB, no native code and a Debian package — which is why I'd keep it here. If the project is willing to carry native code, brotli q9 or zstd −19 long is the upgrade, and the payload header makes that an independent later change. Full tables in the linked note.
