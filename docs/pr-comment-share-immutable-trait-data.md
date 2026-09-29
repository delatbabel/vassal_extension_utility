## Share a trait's parsed type data between every instance of that type (B2)

### What this does

A trait's `mySetType` parses its type string into key strokes, `PropertyExpression`s, `FormattedString`s, arrays of names and images, and every instance kept its own copy of all of it. Pieces expanded from the same prototype have identical traits, so a large game duplicated the same parsed objects thousands of times: a World in Flames game with 7 072 pieces of 113 traits held **8.9 million objects, 308 MB**, for its pieces alone. Only 7 % of that was strings — `SequenceEncoder.Decoder` has interned every token since 2021 — the rest was the objects parsed from them, plus a Swing `DynamicKeyCommandListConfigurer` per Dynamic Property per piece.

`TraitTypeCache` keeps the parsed, immutable part of a type per distinct type string. A converted trait's `mySetType` takes its parsed values from the cache, so **every instance built from the same string points at the same objects** and holds only its own state. Nothing moves out of the traits' fields: they stay `protected` and are read exactly as before by subclasses, the `Ed` editors and the tests; only the objects they point at are shared. A per-class `ConcurrentHashMap` (pieces are built on the load worker too) with one record per distinct string.

Converted, after auditing each field for in-place mutation: `TriggerAction`, `RestrictCommands`, `Marker` (keys, not values), `Embellishment` (names, key strokes, `resetLevel`, painters and lazily computed bounds; not the level or the `KeyCommand`s), `Labeler` (its `Font`). Anything that names the piece (a `KeyCommand`) or is per-call scratch (a `FormattedString` given properties before evaluation) stays per instance. `TriggerAction.setPropertyMatch` now replaces its expression rather than altering the shared one.

`DynamicProperty`, `SetGlobalProperty` and `SetPieceProperty` no longer build a configurer per instance: `decodeKeyCommands`/`encodeKeyCommands` parse and write the key-command list directly (with static `PropertyChangerConfigurer.encode`/`decode` for the changer part), producing exactly the string the configurer writes; the `keyCommandListConfig` field is deprecated and no longer set (the editor has its own). Incidentally `DynamicProperty.testEquals` compared a trait's commands with itself; it now compares with the other trait's.

### Measured

Pieces built with `createPiece`/`setState` from the saved game, heap by class histogram against the loaded module (harness described in the linked notes):

| | WiF `17-40-JA` (7 072 pieces, legacy style: every counter fully defined) | Europa `FallOfFrance2004` (1 169 pieces, modern style: property sheets over shared images) |
|---|---:|---:|
| `master` | 308 MB, 8.9 M objects | 13.0 MB, 313 k objects |
| this branch | **164 MB, 4.0 M objects** | **9.5 MB, 207 k objects** |

What remains in the WiF game is per instance by design: the trait objects themselves (`TriggerAction` 23 MB, `Embellishment` 11 MB, `Marker` 7 MB), the `DynamicKeyCommand`s and their `PropertyChanger`s (~25 MB, each naming its piece) and their Swing `Action` tables. Making `KeyCommand`s lazy is the natural next step. Europa-style games gain less because their piece heap is already small and mostly per-piece state (labels, property sheets). Piece construction time moves only from 59 s to 55 s on the WiF game: parsing was a small part of it; the object construction is the rest.

Two things noticed on the way, not changed here: `Expression.CACHE` fills with per-piece *values* used as formats (~384 000 soft entries on this game, same on `master`); and the type-derived `FormattedString`/`PropertyExpression` objects were already cached one level down (`Expression.CACHE`, `FormattedString.CACHE`), which is why this change shares their shells rather than their parse trees.

### Compatibility

- Type strings written by every converted trait are byte-identical to before (the existing serialisation tests pass unchanged; `TraitTypeCacheTest` checks the key-command encoding against the configurer's).
- The only behaviour that could differ is code mutating a parsed field's object *in place* (writing into `watchKeys[0]`, say): within the engine nothing does; a custom trait doing so would now affect every piece of that type. Assigning a new object, the normal pattern, is unaffected.
- `keyCommandListConfig` is null; two in-tree tests that set commands through it now set `keyCommands` directly.

### Tests

`TraitTypeCacheTest` (instances of one type share their parsed data and not their state; what they write back is unchanged; the key-command encoding matches the configurer's). Full suite 767 tests; the one failure, `ProcessCallableTest`, is environmental and fails on `master` too. Checkstyle, PMD, SpotBugs clean.

### Merging with the other open branches

Trial-merged against `feature/flat-trait-chain-encoding`, `feature/stream-save-and-load` and `fix_sequences`, singly and all four together: **no conflicts** (disjoint files), the union compiles, and this branch's tests plus the trait, `TraitChainFraming` and `SequenceEncoder` tests pass on it. This branch only ever goes through `SequenceEncoder.Decoder`, so the token form and the chain framing are transparent to it; it can be merged first, last or alone.

One collision in the union is between the *other* two: `CommandSerializer` (streaming) writes backslash escaping and reads only that form, while `fix_sequences` writes length-prefixed tokens. In the union, 6 `CommandSerializerTest` assertions requiring byte-identity with `GameModule.encode` fail, and a file written by a `fix_sequences`-only engine would not be read by the streaming reader. If both land, the streaming branch needs its `TokenReader` taught the length-prefixed form, its byte-identity tests relaxed to decoded-tree equality, and its writer kept on the backslash form (a length prefix needs the whole sub-tree's length before its first character, which for the pieces node is the whole game).
