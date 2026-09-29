## Review of `fix_sequences`, and how it relates to `feature/flat-trait-chain-encoding`

I built this branch in a worktree, ran the full `vassal-app` suite, wrote a few probe tests against it, re-encoded a real 8 855-piece WiF save with an exact port of the new encoder, and merged it with `feature/flat-trait-chain-encoding` to see whether the two conflict. Everything below is reproduced, not inferred. Short version: the length-prefix idea works and is linear as the commit says, but the branch has two blocking defects, compresses much worse than the flat framing on the actual problem, and the two changes are orthogonal once one line of the flat branch was fixed (already done).

### What the branch does

- `d1dcdf353`: `new SequenceEncoder(delim)` throws for `-.0123456789EINaefilnrstuy`, so length digits can never be mistaken for a delimiter. Every delimiter the engine uses (`; \t | + , = / ~ : $ # \n`, `ESC`) is legal; custom module code using a rejected character would now throw at construction.
- `5bb084eb9`: `append(String)` writes any token containing the delimiter or `U+001E` as `RS<len>RS<token>` verbatim; the `'…'` quote-wrap survives; `appendEscapedString` and the "token starts with `\`" quote rule are gone, so the encoder never writes a backslash escape again. `Decoder.nextToken()` takes the length path when the token starts with `RS`, else the old backslash loop (so old data still reads).
- Probe: a 300-trait chain built the old nested way with this encoder is 5 236 chars, 0 backslashes, 598 `RS`, and decodes. Linear, as claimed. The existing suite passes (761 tests; the one failure, `ProcessCallableTest`, is environmental and fails on `master` too).

### Blocking defects (both reproduced)

**1. It cannot write `buildFile.xml`.** Every `PieceSlot`/`PrototypeDefinition` stores the encoded `AddPiece` of its piece. With this encoder, any piece with two or more traits has a tab in its inner chain, so the chain is length-prefixed and contains `U+001E`, which is not a legal XML 1.0 character. `Builder.writeDocument` throws:

```
javax.xml.transform.TransformerException: org.xml.sax.SAXException:
  An invalid XML character (Unicode: 0x1e) was found in the node's character data content.
```

and `Builder.toString(Document)` (`Builder.java:244-256`) catches that and returns `""`, which `GameModule.save()` (`GameModule.java:2199-2200`) writes as the build file. So the editor saves an empty `buildFile.xml`. Reproduce:

```java
final String enc = new SequenceEncoder(';').append("a;b").append("c").getValue();
final Document doc = Builder.createNewDocument();
final Element e = doc.createElement("PieceSlot");
e.appendChild(doc.createTextNode(enc));
doc.appendChild(e);
Builder.writeDocument(doc, new StringWriter());   // throws
```

**2. A trailing empty token after a length-prefixed token is lost.**

```java
final SequenceEncoder.Decoder d =
  new SequenceEncoder.Decoder(new SequenceEncoder(',').append("a,b").append("").getValue(), ',');
d.nextToken();        // "a,b"
d.hasMoreTokens();    // false — should be true, next token ""
```

The length path sets `start = Math.min(lend + 1 + len + 1, stop)` and nulls `val` when that reaches `stop`, which is exactly the case where a trailing delimiter announces one more empty token. The backslash path (`a\,b,`) gets it right. In a trait that is "last field empty, previous field contains `;`", `nextToken(default)` callers get their default and bare `nextToken()` callers get `NoSuchElementException`.

Also: two `System.err.println` in the length path (on the WiF save that is ~1.5 million lines to stderr per load), no new tests, and the token-ending-in-backslash ambiguity (`append("ab\\").append("c")` → decodes as one token `ab,c`) is unchanged, because a plain token ending in `\` still takes the legacy path.

### Measured on a real save (WiF, 8 855 pieces, 87.6 traits each)

Command log re-encoded piece by piece under each scheme; gzip −9 is what the `.vsav` stores (post-`!VOBS` the ZIP deflates the XOR-ed plaintext).

| Encoding | Plaintext | gzip −9 |
|---|---:|---:|
| `master` (nested, backslash-escaped) | 222.9 MB | 18.8 MB |
| `fix_sequences` (nested, length-prefixed) | 113.5 MB | 12.3 MB |
| `feature/flat-trait-chain-encoding` (flat) | 104.8 MB | 6.9 MB |
| both | ≈ 104.5 MB | ≈ 6.9 MB |

Plaintext is similar (both linear; a prefix costs ~6 bytes per level). Compressed is not: each length prefix is a number that depends on everything nested below it, inserted at ~175 points per piece and different from piece to piece, so it breaks the long repeats deflate lives on. The flat framing inserts nothing, so identical traits stay byte-identical across pieces.

---

## (1) If `fix_sequences` is to be merged on its own

Required before merge:

- [ ] **Pick an XML-legal marker** (any char ≥ `U+0020` that is not `<`/`&`/`"`; a private-use code point such as `U+E000` is a reasonable choice, since the encoder already length-prefixes any token containing the marker itself) — or escape at the XML layer in `Builder`. Either way, add a test that round-trips a length-prefixed value through `Builder.writeDocument`/`createDocument`.
- [ ] Independently of that, make `Builder.toString`/`GameModule.save` **fail loudly** instead of writing an empty build file; this branch just exposed a silent data-loss path that already existed.
- [ ] **Fix the trailing-empty-token loss.** After taking the token, if `lend + 1 + len == stop` set `val = null` (no delimiter follows, nothing more); otherwise set `start = lend + 1 + len + 1` and leave `val` alone even when `start == stop`, so the next call returns `""` exactly as the legacy path does.
- [ ] Remove the two `System.err.println`.
- [ ] Add tests to `SequenceEncoderTest`: length-prefixed round trip (token containing the delimiter, containing the marker, quoted, empty), trailing empty token after a prefixed token, legacy escaped input still decoding, a deeply nested sequence decoding at every level with linear size, and the XML round trip above.

Worth deciding, not strictly blocking:

- [ ] Also length-prefix a token that **ends** with `\` (and, since the old quote rule is gone, one that **starts** with `\`); that finally closes the 2006 ambiguity for newly written data, at no cost.
- [ ] Consider **logging rather than throwing** for an ugly delimiter, or at least document it in the change log: it turns a working custom class into a crash at construction.
- [ ] Document the format change explicitly. This alters the byte grammar of *every* `SequenceEncoder` output wherever a token contains its delimiter — module and extension files, saves, logs, chat/server protocol, preferences — so every external reader (module tools, save-file scripts, the server if it ever parses fields) must learn `RS<len>RS`. That is a larger compatibility footprint than the flat framing, whose grammar is unchanged. As with `!VOBS`, older engines cannot read the new files; the existing version-mismatch warnings apply.
- [ ] Note the size result honestly: ~1.5× smaller on disk for the large-piece case, vs ~2.75× for the flat framing, because the prefixes are incompressible per-piece noise. Decoding is also still N-deep recursion with an O(N·L) remainder copy per level; the length prefix does not change that.

## (2) If `feature/flat-trait-chain-encoding` is merged alongside `fix_sequences`

They compose. Tested by merging the two branches in a worktree and running the framing and trait tests (95 tests, 0 failures).

- **No further change is needed on the flat branch.** Its one point of contact was `Decorator.splitChain`, which originally found the boundary between a trait's own segment and the rest by scanning for a tab not preceded by a backslash — a private copy of the `Decoder`'s escape rule, which a length-prefixed token (unescaped tab inside `RS<len>RS…`) defeats. That is fixed in `7d240ec1d`: `splitChain` now uses only `SequenceEncoder.Decoder` to find boundaries (it asks whether a *third* top-level token exists), `createPiece` already used only the `Decoder`, and `joinChain` uses `SequenceEncoder` for the segment and raw concatenation for the already-encoded inner chain, which is valid under either marking. The test that counted backslashes in the nested framing is conditional on the encoder producing any, so it passes under both.
- **Merge order does not matter.** Both decoders are unified over old and new forms, and a chain may mix them.
- **What each contributes once both are in:** the flat framing removes the per-trait nesting (all of the quadratic bytes); `fix_sequences` then only changes the remaining constant-depth layers — the `/` escapes of `AddPiece` (~0.3 MB on the WiF save), the `ESC` command tree, embedded marker definitions (`PlaceMarker`/`Replace`), and any deep nesting custom code does. On the WiF save the combined output is the flat form with those few escapes turned into prefixes: ≈ 104.5 MB / ≈ 6.9 MB, indistinguishable from the flat branch alone.
- **The combination inherits all of (1)'s costs** — the XML fix, the grammar change for external readers, the ugly-delimiter behaviour — and gains only that generality. So the decision for (2) is really: is the constant-depth generality worth changing the grammar everyone parses? My reading of the numbers is no, and that the flat branch should go in on its own; but if `fix_sequences` is fixed up per (1) it can follow without touching the flat branch.
- **Two-token contract.** One thing (1) preserves that the flat framing does not: a custom `BasicCommandEncoder` that overrides `createPiece` with a copy of the old two-token loop, or a hand parser assuming two top-level tokens, keeps working under `fix_sequences` and fails on flat data. `BasicCommandEncoder`'s Javadoc steers custom code to `createDecorator`, which is unaffected, and I have not found a module that overrides `createPiece`; if one turns up, a fallback in `createPiece` for subclasses that override it is possible without changing `SequenceEncoder`.
- Change-log entries for both, and a single note in the developers' guide describing the chain framing (`Decorator` carries a block comment with the exact grammar) and, if (1) lands, the `RS<len>RS` token form.

Reproduction for all of the above: `docs/vassal-flat-trait-chain.md` and `docs/vassal-sequence-fix-comparison.md` in the extension-utility repo have the census, the probes and the measurement script description.
