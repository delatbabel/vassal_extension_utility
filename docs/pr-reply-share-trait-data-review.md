# Reply to the review of PR #15119 (shared trait type data)

**`TraitTypeCache.java` — "We should not introduce more singletons. Better would be to provide the cache via a getter on `GameModule`."**

Done. `TraitTypeCache` is now an ordinary instance held by `GameModule` and reached through `getTraitTypeCache()`; the static maps are gone. The traits go through `TraitTypeCache.lookup(...)`, which asks the current module for its cache and, when there is no module or the module has no cache (the mocked module in `DecoratorTest`-based tests returns `null`), simply parses the type — so a trait built outside a game is correct and just shares nothing. `TraitTypeCacheTest` gives the mocked module a cache of its own in a `@BeforeEach`, and has a case for the no-cache path. The existing trait tests run unchanged.
