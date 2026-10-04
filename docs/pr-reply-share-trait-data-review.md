# Reply to the review of PR #15119 (shared trait type data)

**`TraitTypeCache.java` — "We should not introduce more singletons. Better would be to provide the cache via a getter on `GameModule`."**

Done. `TraitTypeCache` is now an ordinary instance held by `GameModule` and reached through `getTraitTypeCache()`; the static maps are gone. The traits go through `TraitTypeCache.lookup(...)`, which asks the current module for its cache and, when there is no module or the module has no cache (the mocked module in `DecoratorTest`-based tests returns `null`), simply parses the type — so a trait built outside a game is correct and just shares nothing. `TraitTypeCacheTest` gives the mocked module a cache of its own in a `@BeforeEach`, and has a case for the no-cache path. The existing trait tests run unchanged.

---

# Second review (3 October)

**`Embellishment.java:226` — "Arrays aren't immutable. What happens if a subclass modifies the shared `imageName` array?"**

Every other Layer built from that type string would have seen the change, and one subclass does exactly that: `MassPieceLoader.Emb.buildLayers` writes the substituted image name into `imageName[i]`, so with a shared array the second piece generated from a template would have read the first piece's image names instead of the template's.

Fixed in `d933fa7ce`: a trait never puts a shared array into one of its fields. `Embellishment` (`imageName`, `commonName`, `imagePainter`), `TriggerAction` (`watchKeys`, `actionKeys`) and `RestrictCommands` (`watchKeys`) assign a `clone()` of the shared array, so the elements (strings, key strokes, painters) stay shared and a write stays with the instance. A Layer's `size` bounds, which `getCurrentImageBounds()` hands out to callers, are computed per instance again and are no longer in the shared data. `TraitTypeCache`'s Javadoc now states the rule, and `TraitTypeCacheTest` asserts distinct arrays with identical elements.

Cost, measured on the WiF game (7,072 pieces, 789 k traits): piece heap 164 MB → 183 MB. Of the 19 MB, 12.8 MB is the 558 k `NamedKeyStroke[]` of Trigger Action and Restrict Commands, arrays of one to three entries where the header is as large as the contents. That is still down from 308 MB on master. If you would rather keep those key arrays shared under a documented contract, that is the 12.8 MB; I have taken the safe default.

---

# Third review (4 October)

**`TriggerAction.java:455/460`, `RestrictCommands.java:210`, `Embellishment.java:281` — "`PropertyExpression` / `FormattedString` isn't immutable", and the summary: "they can't be cached in type objects as-is … making these copy-on-write would solve the problem, that's also not obvious how to do it."**

Agreed on both counts. Both classes have public setters (`setExpression`; `setFormat`, `setProperty`, `setDefaultProperties`), so one instance shared between every trait of a type would let a write through any of them reach all the others. Copy-on-write cannot rescue a shared *reference*: `setFormat` on it has to stay with the caller's instance, and no amount of copying inside the object can make a change visible to one holder and not the rest.

So the shared type data no longer holds either class (`5bd588696`). It holds their source text, and every Trigger Action (`propertyMatch`, `whileExpression`, `untilExpression`, `loopCount`, `indexStart`, `indexStep`), Restrict Commands (`propertyMatch`) and Layer (`resetLevel`) builds its own instance from it, exactly as on master. The expensive part stays shared anyway: `FormattedString` is a 24-byte handle whose parsed `Expression` lives in that class's own `FSData` cache, keyed by format text, so building an instance is a cache lookup, not a parse; its property map is only created by `setProperty`. `PropertyExpression` is 16 bytes of text reference. `TraitTypeCache`'s Javadoc now says this, and `TraitTypeCacheTest` asserts per-instance handles with equal text.

Cost on the WiF game (7,072 pieces, 789 k traits), piece heap: 164 MB with everything shared → 183 MB with the arrays per instance → **213 MB** with these handles per instance (813 k `PropertyExpression`, 1.3 M `FormattedString`). Master is 308 MB, so the branch now saves 95 MB and 2.6 M objects rather than 144 MB. What remains shared is what has no setter: strings, `NamedKeyStroke`s, the Layer painters, Marker keys, and the Dynamic Property's configurer-free key commands, which were the largest single item.

**`DynamicProperty.java:167` — "There should be tests to ensure that this code stays in sync with what `DynamicKeyCommandListConfigurer` reads and writes."**

Added, in the same commit: `TraitTypeCacheTest.keyCommandCodecMatchesTheConfigurer` builds a Dynamic Property with six key commands covering every property-changer kind (set directly, increment, prompt, prompt from a list with escaped commas, a named key stroke, and an entry with no key and the default changer), feeds the same list to a `DynamicKeyCommandListConfigurer`, and asserts that the configurer's `getValueString()`, `encodeKeyCommands(decodeKeyCommands(list))` and the encoding of the trait's own parsed commands are the same string; then decodes the configurer's output through the trait's codec and checks each entry against the configurer's `DynamicKeyCommand` — name, key stroke, changer class and the changer's own encoding.
