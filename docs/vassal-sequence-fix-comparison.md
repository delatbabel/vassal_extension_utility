# Two fixes for the O(N²) piece encoding: `fix_sequences` vs `feature/flat-trait-chain-encoding`

Two branches in `../vassal` attack the quadratic backslash growth described in
[wif-save-bloat-analysis.md §3](wif-save-bloat-analysis.md#3-cause-2--sequenceencoder-escaping-is-otraits-per-piece):

| | `fix_sequences` (Joel Uckelman, 28 Sep 2026, WIP) | `feature/flat-trait-chain-encoding` (this project, [vassal-flat-trait-chain.md](vassal-flat-trait-chain.md)) |
|---|---|---|
| What changes | `SequenceEncoder` (and only it): a token that contains its delimiter (or `U+001E`) is written **length-prefixed**, `RS<len>RS<token>`, instead of backslash-escaped. Also rejects "ugly" delimiters with an exception. | `Decorator` + `BasicCommandEncoder.createPiece` (and only them): the trait chain is joined **flat**, one singly-escaped segment per trait, instead of each trait escaping the whole inner piece as one token. `SequenceEncoder` untouched. |
| Where the nesting goes | The **nesting stays**; it just stops costing anything per level (a length prefix instead of re-escaping). Applies to every nested `SequenceEncoder` use in the engine. | The **nesting is removed** for the one structure that nests in proportion to the trait count. Other layers (constant depth) are left as they are. |
| Files | `SequenceEncoder.java` (+2 whitespace-only test edits) | `Decorator.java`, `BasicCommandEncoder.java`, new `TraitChainFramingTest.java` |
| Reads old data | yes (legacy backslash path kept in `Decoder.nextToken`) | yes (nested framing is one case of the unified decoders) |
| Old engines read new data | no | no |

Both were examined by reading the diffs, building each in its own worktree, running the full
`vassal-app` suite, writing targeted probes, re-encoding the 8 855-piece WiF save under each
scheme, and finally merging the two branches to test the "orthogonal" hypothesis. Everything
below is measured or reproduced, not inferred.

---

## 1. What `fix_sequences` does, precisely

Diff of `SequenceEncoder.java` against the common base `f9e9dc9bc` (two substantive commits):

1. **`d1dcdf353` — reject ugly delimiters.** `new SequenceEncoder(delim)` now throws
   `IllegalArgumentException` if `delim` is one of `-.0123456789EINaefilnrstuy` (characters
   that `String.valueOf` can produce for numbers and booleans). Previously such delimiters
   were handled by routing primitives through the escaping path (`uglyDelim`). Needed so that
   the *digits of a length prefix* can never be confused with a delimiter. Every delimiter the
   engine itself uses (`; \t | + , = / ~ : $ # \n`, `ESC`) is legal; custom module code using
   one of the rejected characters would now crash at construction.
2. **`5bb084eb9` — length-prefix instead of escape.** `append(String s)`:
   - if `s` contains the delimiter or `U+001E` → `RS` + `s.length()` + `RS` + `s` (verbatim);
   - else if `s` is `'…'`-quoted → wrapped in another pair of quotes (as before);
   - else appended raw. The old rule that quote-wrapped a token *starting* with `\` is gone,
     and `appendEscapedString` is deleted — **the encoder never writes a backslash escape again.**

   `Decoder.nextToken()`: if the token starts with `RS`, read the length up to the next `RS`,
   take that many characters, skip the following delimiter; otherwise the old backslash-aware
   loop. Two `System.err.println` debug lines are left in the length path.

Because a nested sequence is a token that contains the delimiter, every level now costs
`2 + digits(len)` characters instead of one more backslash per delimiter below it, so the
commit's claim holds: nesting is linear. Probe on a 300-trait chain built the old nested way
with this encoder: 5 236 characters, 0 backslashes, 598 record separators, decodes correctly.

---

## 2. Defects found in `fix_sequences` as it stands

These are reproduced with a probe test compiled against the branch (not committed anywhere).

### 2.1 It cannot write a module file — `U+001E` is not an XML character

`buildFile.xml` holds every `PieceSlot` and `PrototypeDefinition` as the encoded `AddPiece`
string of the piece. With this encoder, any piece with **two or more traits** has a tab inside
its inner chain, so the chain is length-prefixed and contains `U+001E`. XML 1.0 forbids that
character anywhere in a document, and the writer the editor uses refuses it:

```
javax.xml.transform.TransformerException: org.xml.sax.SAXException:
  An invalid XML character (Unicode: 0x1e) was found in the node's character data content.
```

(`Builder.writeDocument`, `Builder.java:204-224`). Worse, `Builder.toString(Document)`
(`:244-256`) **catches the exception and returns `""`**, and `GameModule.save()`
(`GameModule.java:2199-2200`) writes that string as `buildFile.xml` without checking — so the
editor would save an empty build file. The same applies to `.vmdx` extensions and to
`moduledata`/`extensiondata` if any encoded value reached them. The fix is not hard (choose an
XML-legal marker character, or escape at the XML layer) but it means the byte format is not
final, and it is the reason this branch cannot be merged as it is.

### 2.2 A trailing empty token after a length-prefixed token is lost

```java
new SequenceEncoder(',').append("a,b").append("").getValue()   // → RS 3 RS a,b,
```

decodes to `"a,b"` and then `hasMoreTokens() == false`: the length path sets
`start = min(lend + 1 + len + 1, stop)` and nulls `val` when that reaches `stop`, so the empty
final token vanishes. The backslash path handles the same input (`a\,b,`) correctly. In practice
this is a trait whose *last* field is empty and whose *previous* field contains the delimiter —
`nextToken(default)` callers get their default, bare `nextToken()` callers get a
`NoSuchElementException`. Not caught by the existing suite (761 tests pass on the branch, the
one failure being the environmental `ProcessCallableTest`).

### 2.3 The old ambiguity is still there

A token *ending* in a backslash, `append("ab\\").append("c")` → `ab\,c`, still decodes as one
token `ab,c`: the encoder no longer writes backslashes, but the legacy decode path still treats
`\` before a delimiter as an escape, and a plain token ending in `\` takes that path. (Unchanged
from `master`; neither branch fixes it. It would take length-prefixing tokens that end in `\`.)

### 2.4 Work-in-progress markers

Debug output in the decoder hot path (`nt:` / `v:` for every length-prefixed token — on the
WiF save that is 1.5 million lines to stderr per load), no tests, no change log, and two test
files touched only by whitespace.

---

## 3. Measured on the WiF save (`002-presetup-aif-everything`, 8 855 pieces, 87.6 traits each)

The command log was re-encoded piece by piece under each scheme with exact ports of both
encoders (the same script as in [vassal-flat-trait-chain.md §5](vassal-flat-trait-chain.md#5-measured-effect-on-the-wif-saves)):

| Encoding of the command log | Plaintext | Backslashes | `RS` | gzip −9 (≈ what the `.vsav` stores) |
|---|---:|---:|---:|---:|
| `master` (nested, backslash-escaped) | 222.9 MB | 121.5 MB | – | 18.8 MB |
| `fix_sequences` (nested, length-prefixed) | **113.5 MB** | 3.1 MB | 3.1 MB | **12.3 MB** |
| `feature/flat-trait-chain-encoding` (flat, backslash-escaped) | **104.8 MB** | 3.3 MB | – | **6.9 MB** |
| both together (flat, length-prefixed) | ≈ 104.5 MB | 3.1 MB | ≈ 0 | ≈ 6.9 MB |

(The ~3 MB of backslashes that remain under every scheme are literal backslashes inside trait
fields — expressions and the like — not escapes.)

The plaintext sizes are close, as expected: both are linear, and the length prefixes cost about
6 bytes per trait. The **compressed** sizes are not close: `fix_sequences` lands at 12.3 MB
against 6.9 MB. The reason is structural. Deflate thrives on the long identical runs that
identical traits produce across thousands of pieces; a length prefix inserts, at every one of
the ~175 nesting points per piece, a number that depends on everything *below* it — and that
differs between pieces whenever their ids, positions or any inner state differ. The flat
framing inserts nothing, so identical traits stay byte-identical wherever they sit. On disk,
then, `fix_sequences` is a 1.5× improvement and the flat framing a 2.75×; combining them adds
nothing measurable over the flat framing alone (the only remaining escapes are the few hundred
kilobytes of `/` in the `AddPiece` layer).

---

## 4. Strengths and weaknesses

### `fix_sequences`

**Strengths**

- *General.* One change fixes nesting cost everywhere `SequenceEncoder` nests: the trait
  chain, embedded marker definitions (`PlaceMarker` "Define Marker", `Replace`), command trees,
  and any deep nesting custom code might do. The flat framing fixes only the trait chain.
- *Transparent to the two-token chain contract.* The chain still has exactly two top-level
  tokens per level, so custom `BasicCommandEncoder`s that override `createPiece` with a copy of
  the old code, and any hand-parser that splits the chain with `SequenceEncoder.Decoder`, keep
  working. (Those that split with `String.split("\t")` were already broken by escapes and stay
  broken.)
- *Verbatim tokens.* A length-prefixed token is copied, not scanned, so it cannot be
  mis-parsed by the escape rule — for *those* tokens the 2006 ambiguity is gone.

**Weaknesses**

- *It is the "SequenceEncoder is everywhere" problem, applied to the fix.* The wire format of
  every `SequenceEncoder` use changes wherever a token contains its delimiter: module and
  extension files, saves, logs, the chat/server protocol, preferences, saved-game metadata.
  Every external reader of VASSAL data must learn the length-prefix grammar — this utility's
  `SavedGame` (`splitAddPiece` counts unescaped `/`; `seqDecode` is a port of the old
  `Decoder`), every script in `tools/`, and third-party tools alike. The flat framing changes
  the layout of one structure and leaves the grammar alone; this utility reads its output today.
- *The XML defect (§2.1)* makes it unshippable as is, and fixing it changes the format again.
- *The lost-trailing-token defect (§2.2)* is a silent data bug.
- *Compression* (§3): a length prefix is per-instance noise between what would otherwise be
  repeated bytes; 12.3 MB vs 6.9 MB on the real save.
- *Still recursive.* Decoding a 469-trait piece still recurses 469 deep through
  `createPiece`/`setState` and copies the remainder at every level, O(N·L) per piece. The flat
  framing decodes a type in one pass and two stack frames.
- *Behaviour change for custom code:* a formerly-working "ugly" delimiter now throws.
- *Per-level overhead* of `2 + digits(len)` bytes, versus none.

### `feature/flat-trait-chain-encoding`

**Strengths**

- *Contained and verifiable.* Two engine files; `SequenceEncoder` untouched; every trait
  untouched; the census in [vassal-flat-trait-chain.md §2](vassal-flat-trait-chain.md#2-the-census-what-touches-the-chain-string)
  shows the chain is produced and consumed in exactly five methods and nowhere else.
- *Best size result* on the actual problem (2.13× plaintext, 2.75× compressed), zero overhead.
- *Old data decodes through the same algorithm path* (nested input degenerates to the old
  two-token loop); mixed chains and legacy-framed custom traits are handled and tested.
- *Cheaper decode:* flat types decode in one pass without deep recursion.
- *Tested:* 10 new tests covering both framings, mixing, `mergeState`, escaping edge cases.

**Weaknesses**

- *Narrow.* Only the trait chain. Embedded marker definitions, the `AddPiece` `/` layer and the
  command tree keep their (constant-depth, linear) escaping; on WiF that is the 0.3 MB of `/`
  escapes.
- *Breaks the two-token assumption.* A custom encoder that overrides `createPiece` with the old
  code and never calls `super`, or a hand-parser assuming two tokens, fails on flat data. The
  Javadoc says `createPiece` "should generally not need to be overridden", but the risk is
  real for old modules with custom classes and cannot be detected from the engine.
- *A reflection guard* (`inheritsFraming`) is a small piece of machinery that exists only for
  custom traits that override the framing methods; no engine trait needs it.

---

## 5. Are they orthogonal? Tested, not assumed

Merged the two branches (`fix_sequences` into `feature/flat-trait-chain-encoding`) in a
worktree and ran the framing tests:

- **First attempt: 5 of 10 failed.** Our `splitChain` found the boundary between "this trait's
  segment" and "the rest" by scanning for a tab not preceded by a backslash — a private copy of
  the `Decoder`'s escape rule. Under the length-prefix encoder a segment containing a tab is
  `RS len RS …<TAB>…` with the tab *unescaped*, so the scan cut inside the token. This is the
  one point of contact between the two changes.
- **Fix (commit `7d240ec1d` on our branch):** `splitChain` now finds boundaries only through
  `SequenceEncoder.Decoder` — it takes the first token, and asks a second `Decoder` on the
  remainder whether there is a token *after* the second one — so it works under any marking of
  delimiters inside tokens. `createPiece` already used only the `Decoder`; `joinChain` uses
  `SequenceEncoder` for the segment and raw concatenation for the (already-encoded) inner chain,
  which is valid under either encoder. The test's one assertion that counted backslashes in the
  nested form is now conditional on the encoder producing any.
- **Second attempt:** all 10 framing tests pass on the merged tree, alongside `SequenceEncoderTest`
  and the Marker/Embellishment/PlaceMarker/Replace/Obscurable/UsePrototype/Deck/Stack tests
  (95 tests, 0 failures). Our branch's own full suite after the fix: 771 tests, the one failure
  being the environmental `ProcessCallableTest` that also fails on `master`.

So: **orthogonal after that one fix**, and the flat branch now carries it. Applied together, the
output for WiF is the flat form with length prefixes replacing the few `/` escapes — i.e. the
flat branch's result. The combination inherits all of `fix_sequences`'s compatibility costs
(§4) and gains only the generality (embedded markers, command tree, custom nesting).

---

## 6. Recommendation

1. **Merge `feature/flat-trait-chain-encoding` on its own.** It solves the measured problem
   (the trait chain is where all the quadratic bytes are), with the smallest blast radius, the
   best compressed result, the same one-way compatibility VASSAL already accepted for `!VOBS`,
   and no change to a class the maintainers have said they will not revisit. External tools,
   including this one, keep working.
2. **Do not merge `fix_sequences` in its current form.** It needs an XML-legal marker (or
   XML-layer escaping), the trailing-empty-token fix, removal of the debug output, and tests;
   and its authors should weigh the cost of changing the byte grammar for every consumer of
   VASSAL data against the gain, which after (1) is confined to structures that nest at constant
   depth. If it is pursued, it composes with (1) without further changes to (1).
3. **If only one of the two is ever taken, take (1).** Under (2) alone the save is 1.5× smaller
   on disk; under (1) alone 2.75×; under both, the same as (1).

One thing (2) offers that (1) does not, and that may be worth doing *without* changing
`SequenceEncoder`: the two-token chain contract for custom `createPiece` overrides. If that
compatibility turns out to matter for real modules, `createPiece` could fall back to the old
two-token interpretation when a subclass overrides it — but no such module has been identified,
and `BasicCommandEncoder`'s own Javadoc steers custom code to `createDecorator`, which is
unaffected.

---

## 7. Reproducing

```bash
cd ../vassal
git worktree add /tmp/wt-fixseq fix_sequences && (cd /tmp/wt-fixseq && ./mvnw -q -pl vassal-app test)
git worktree add -b combo /tmp/wt-combo feature/flat-trait-chain-encoding \
  && (cd /tmp/wt-combo && git merge --no-edit fix_sequences \
      && ./mvnw -q -pl vassal-app test -Dtest=TraitChainFramingTest -Dsurefire.failIfNoSpecifiedTests=false)
```

The probes in §2 are the `SequenceEncoderProbeTest` reproduced in §2's descriptions (six
JUnit tests: trailing empty token after a prefixed and after a plain token, token ending in
backslash, legacy data, the record separator through `Builder.writeDocument`/`createDocument`,
and the 300-level nesting); the size table in §3 comes from re-encoding the deobfuscated
`savedGame` entry with exact ports of both encoders and comparing `gzip -9` output.
