# Sharing a trait's parsed type data (B2) — `feature/share-immutable-trait-data`

Implements item **B2** of [wif-engine-optimizations.md](wif-engine-optimizations.md#b2-flyweight-prototype-expansion--share-immutable-trait-data-across-instances--measured),
in the form the measurements in [wif-flyweight-analysis.md](wif-flyweight-analysis.md) showed
was worth having, together with the "cheaper fixes" that analysis found on the way. Branch
`feature/share-immutable-trait-data` in `../vassal`, raised as [PR #15119](https://github.com/vassalengine/vassal/pull/15119),, from `master` at `97e231720`.

---

## 1. Design: share the objects, keep the fields

The proposal was a flyweight: split each trait into immutable type data and per-instance
state, and have instances of one definition share one type-data object. Two facts from the
measurement shaped how that was done:

- **Strings were never the problem.** `SequenceEncoder.Decoder` has interned every token since
  2021, and `Expression` and `FormattedString` already cache their parsed data. What each
  instance duplicated was the *objects built from* the strings: `NamedKeyStroke[]`,
  `PropertyExpression`, `FormattedString` shells, arrays of image names, image painters, and a
  Swing configurer per Dynamic Property.
- **Traits expose their parsed fields.** They are `protected`, read directly by subclasses,
  by the `Ed` editor classes and by the test suite (`trait.watchKeys = …`), and by custom traits
  in modules that extend the standard ones. Moving them into a separate type-data object would
  break every such reader at the binary level.

So the flyweight is applied one level down: **every field stays where it is, and `mySetType`
assigns into it objects taken from a per-class cache keyed by the type string**, so that all
instances built from one string point at the same objects. The per-instance residue is the
trait object itself (its field slots) and its state. `VASSAL.counters.TraitTypeCache` is the
cache: `get(dataClass, typeString, parser)` — a `ConcurrentHashMap` per data class (traits are
built on the load worker as well as the EDT), holding one parsed record per distinct string.
It is an instance owned by the `GameModule` (`getTraitTypeCache()`, at the reviewer's request:
no new singletons); traits reach it through the static `TraitTypeCache.lookup(...)`, which
parses without sharing when there is no module or the module has no cache, as in the
mocked-module tests.

What may be shared is anything immutable, or mutated only in a way every sharer would perform
identically. What may not is anything that names the piece, or is used as per-call scratch.
Audited per trait:

| Trait (WiF instances) | Shared through the cache | Kept per instance |
|---|---|---|
| `TriggerAction` (240 k) | `watchKeys`, `actionKeys`, the three `PropertyExpression`s, `loopCount`/`indexStart`/`indexStep` (`FormattedString`s that are only ever evaluated), key strokes, strings | the `KeyCommand` (names the piece); `setPropertyMatch` now *replaces* the expression instead of altering the shared one |
| `Marker` (178 k) | `keys` (never written in place) | `values` (state; `setProperty` writes into it) |
| `DynamicProperty`, `SetGlobalProperty`, `SetPieceProperty` (131 k) | — (see §2) | `keyCommands` (each names the piece and carries a `PropertyChanger` bound to it), `format` (scratch: `setFormat` per call) |
| `RestrictCommands` (63 k) | `propertyMatch`, `watchKeys` | — |
| `Embellishment` (56 k) | `imageName`, `commonName`, key strokes, `resetLevel`, the `ScaledImagePainter[]` (an image op and a scale cache, nothing per piece) and the lazily computed `Rectangle[] size` (deterministic from the image) | the current level, the `KeyCommand`s, the last bounds and shape |
| `Labeler` (Europa: 6.9 k) | the `Font` (immutable) | everything else; its `FormattedString`s are scratch |

The legacy `emb;` Layer format (`originalSetType`) is left unshared: it rewrites `resetLevel`
during parsing and is not what modules write today.

## 2. The cheaper fixes

**Swing configurers per trait instance.** `DynamicProperty`'s constructor built a
`DynamicKeyCommandListConfigurer` for every instance and used it as the parser and serialiser
of its key-command list — 131 000 configurers, each with its bean plumbing, 17 % of the WiF
piece heap in a game with no editor open. The trait now parses and encodes the list itself
(`decodeKeyCommands`/`encodeKeyCommands`, with static `PropertyChangerConfigurer.encode`/`decode`
for the changer part), producing byte-identical type strings; the field `keyCommandListConfig`
is deprecated and no longer set (the editor keeps its own). `DynamicProperty.testEquals`
compared a trait's commands with itself; it now compares with the other trait's.

**Parsed expressions.** Already cached by `Expression.CACHE` and `FormattedString.CACHE`; what
remained per instance were the `FormattedString`/`PropertyExpression` shells, which §1 now
shares for the converted traits (1.3 M `FormattedString`s in the WiF game become 0.5 M).

## 3. Measured

Same harness and games as [wif-flyweight-analysis.md](wif-flyweight-analysis.md) (all pieces
built with `createPiece`/`setState`, heap by class histogram against the loaded module):

| | WiF `17-40-JA` (7 072 pieces) | Europa `FallOfFrance2004` (1 169 pieces) |
|---|---:|---:|
| Piece heap, `master` | 308 MB, 8.9 M objects | 13.0 MB, 313 k objects |
| Piece heap, this branch | **164 MB, 4.0 M objects** | **9.5 MB, 207 k objects** |
| Saving | **144 MB (47 %)** | 3.5 MB (27 %) |
| `createPiece` time | 59.1 s → 54.7 s | 0.5 s |

What remains in WiF, from the histogram, is per instance by design: the trait objects
themselves (`TriggerAction` 23 MB, `Embellishment` 11 MB, `Marker` 7 MB …), the
`DynamicKeyCommand`s and their `PropertyChanger`s (~25 MB, each naming its piece), the
`KeyCommand`s' Swing `Action` tables, and per-piece strings. Two further things the histogram
shows, not changed here:

- `Expression.CACHE` holds ~384 000 entries keyed by per-piece *values* (a Dynamic Property's
  value or a label's text becomes the format of a `FormattedString`, and every distinct one is
  parsed and cached, softly). It is the same on `master`; ~12 MB of soft-referenced cache.
- `createPiece` time barely moves because parsing was a small part of it; the cost is
  constructing the trait chain and its `KeyCommand`s. Making `KeyCommand`s lazy (built when a
  menu is shown or a key is matched) is the next step for both heap and time.

Europa-style modules gain little because their piece heap is already small and mostly
per-piece state (labels, property sheets, property maps).

## 4. Compatibility

- Type strings written by the converted traits are byte-identical to before (the existing
  serialisation tests for each trait pass unchanged; `TraitTypeCacheTest` checks the
  key-command encoding against the configurer's).
- Behaviour is identical unless code mutates a parsed field's object in place. Within the
  engine nothing does (audited above). A custom trait extending one of the converted classes
  and writing into, say, `watchKeys[0]` would now affect every piece of that type; the
  supported pattern (assign a new array or object) is unaffected.
- `keyCommandListConfig` on `DynamicProperty` is null; two in-tree tests that set key commands
  through it were changed to set `keyCommands` directly.
- `TraitTypeCache` grows by one record per distinct type string per class — bounded by the
  module's definitions plus each edit made in the editor; `clear()` exists.

## 5. Tests

`TraitTypeCacheTest` (6 tests) and the full `vassal-app` suite: 767 tests, the only failure the
environmental `ProcessCallableTest` that fails on `master` too. Checkstyle, PMD and SpotBugs
report nothing new.

## 6. Merging with the other branches

Trial merges of each of the other three branches into this one, and of all four together, in
scratch worktrees (`git merge --no-ff`), then the union compiled and its affected tests run:

| Merge into `feature/share-immutable-trait-data` | Conflicts | Notes |
|---|---|---|
| `feature/flat-trait-chain-encoding` | none | disjoint files (`Decorator`, `BasicCommandEncoder`); this branch's parsers use only the `Decoder` API and the segment strings, which are identical in both framings |
| `feature/stream-save-and-load` | none | disjoint files (`GameModule`, `GameState`, `BasicLogger`, `CommandSerializer`, `ObfuscatingOutputStream`) |
| `fix_sequences` | none | disjoint files (`SequenceEncoder`); this branch parses through `SequenceEncoder.Decoder`, so the token form is transparent to it |
| all four | none | compiles; `TraitTypeCacheTest`, `TraitChainFramingTest`, `SequenceEncoderTest` and all trait tests pass |

One semantic collision exists in the union, **between the other two branches, not this one**:
`CommandSerializer` (streaming) writes the backslash escaping and its token reader recognises
only that form, while `fix_sequences` makes `SequenceEncoder` write length-prefixed tokens
(`RS<len>RS…`). In the union, 6 of the 9 `CommandSerializerTest` assertions that require the
streamed text to be *byte-identical* to `GameModule.encode`'s fail; what it writes is still
readable by the union's `Decoder` (the legacy path is kept), but a file written by a
`fix_sequences`-only engine, with length-prefixed tokens at the top level, would not be read by
the streaming reader. If both are merged, the streaming branch needs: the length-prefixed
branch added to its `TokenReader`; its byte-identity tests relaxed to decoded-tree equality; and
a note that a streaming writer must keep the backslash form for nested sub-trees, because a
length prefix needs the whole sub-tree's length before its first character, which for the
pieces node is the whole game. Order of merging is otherwise free; this branch can go first,
last or alone.
