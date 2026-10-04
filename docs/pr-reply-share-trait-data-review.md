# Reply to the review of PR #15119 (shared trait type data)

**`TraitTypeCache.java` — "We should not introduce more singletons. Better would be to provide the cache via a getter on `GameModule`."**

Done. `TraitTypeCache` is now an ordinary instance held by `GameModule` and reached through `getTraitTypeCache()`; the static maps are gone. The traits go through `TraitTypeCache.lookup(...)`, which asks the current module for its cache and, when there is no module or the module has no cache (the mocked module in `DecoratorTest`-based tests returns `null`), simply parses the type — so a trait built outside a game is correct and just shares nothing. `TraitTypeCacheTest` gives the mocked module a cache of its own in a `@BeforeEach`, and has a case for the no-cache path. The existing trait tests run unchanged.

---

# Second review (3 October)

**`Embellishment.java:226` — "Arrays aren't immutable. What happens if a subclass modifies the shared `imageName` array?"**

Every other Layer built from that type string would have seen the change, and one subclass does exactly that: `MassPieceLoader.Emb.buildLayers` writes the substituted image name into `imageName[i]`, so with a shared array the second piece generated from a template would have read the first piece's image names instead of the template's.

Fixed in `d933fa7ce`: a trait never puts a shared array into one of its fields. `Embellishment` (`imageName`, `commonName`, `imagePainter`), `TriggerAction` (`watchKeys`, `actionKeys`) and `RestrictCommands` (`watchKeys`) assign a `clone()` of the shared array, so the elements (strings, key strokes, painters) stay shared and a write stays with the instance. A Layer's `size` bounds, which `getCurrentImageBounds()` hands out to callers, are computed per instance again and are no longer in the shared data. `TraitTypeCache`'s Javadoc now states the rule, and `TraitTypeCacheTest` asserts distinct arrays with identical elements.

Cost, measured on the WiF game (7,072 pieces, 789 k traits): piece heap 164 MB → 183 MB. Of the 19 MB, 12.8 MB is the 558 k `NamedKeyStroke[]` of Trigger Action and Restrict Commands, arrays of one to three entries where the header is as large as the contents. That is still down from 308 MB on master. If you would rather keep those key arrays shared under a documented contract, that is the 12.8 MB; I have taken the safe default.
