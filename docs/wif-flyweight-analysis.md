# Flyweight prototype expansion (B2) — how much would it actually save?

Item **B2** of [wif-engine-optimizations.md](wif-engine-optimizations.md#b2-flyweight-prototype-expansion--share-immutable-trait-data-across-instances)
proposes sharing the immutable "type" part of each trait across the pieces built from the same
definition, so that N pieces made from one prototype hold one copy of its ~200 parsed traits
instead of N. This note measures what that would buy, on two modules built the two ways VASSAL
modules are built:

- **WiF CE Official Combo 2.1.4** — the legacy style: every counter is fully defined, front and
  back image and all, so a game has at most one copy of most counters; the prototypes are in the
  main module and are baked into every placed piece. Game: `17-40-JA-EOT-Production-etctodo`
  (34 MB `.vsav`, 447 MB obfuscated log).
- **EuropaNewMapV091** — the modern style: a handful of prototypes carry a property sheet whose
  values are overlaid on one shared base image per nationality, so a game holds many copies of
  the same piece definition differing only in their values. Game: `FallOfFrance2004-May-I-1940`
  (301 KB `.vsav`).

Two things were measured, because the file and the heap tell different stories:

1. **Text sharing** in the saved game (`tools/piece_sharing.py`): every `AddPiece` split into its
   per-trait type and state segments; how much of the type text is distinct at *whole-piece*
   granularity (what a flyweight keyed on the whole definition can share) and at *trait*
   granularity (what a flyweight keyed on the trait, i.e. on the prototype run, can share).
2. **Live heap** in the engine (scratch harness): the module loaded, then the game's pieces built
   with the module's own `createPiece`/`setState` in three populations — none, one piece per
   distinct whole type, all pieces — with a class histogram of each, so the piece heap is the
   histogram difference and can be attributed by class.

---

## 1. The two games' shapes

| | WiF `17-40-JA` | Europa `FallOfFrance2004` |
|---|---:|---:|
| Pieces (excluding stacks and decks) | 7 072 | 1 169 |
| Traits per piece (mean) | 112.6 | 25.2 |
| Type text | 98.6 MB | 3.35 MB |
| Distinct **whole** type chains | 5 349 = **92.2 %** of the type text | 133 = **11.6 %** |
| Distinct **trait segments** | 11 255 = **0.9 %** of the type text | 214 = **0.7 %** |
| Pieces whose whole type occurs once | 5 247 (74 %) | 39 (3 %) |
| Pieces whose whole type occurs ≥ 10 times | 1 562 | 865 (74 %) |
| State text | 8.4 MB (`attach` 5.4 MB, `piece` 1.8 MB) | 0.7 MB (`label` 0.37 MB, `piece` 0.22 MB) |
| Longest shared prototype run found | 64 traits, shared by 2 089 pieces | 16 traits, shared by 581 pieces |

The two modules are exactly the two cases the proposal has to serve:

- In **WiF**, three quarters of the pieces have a type nobody else has — each counter's own
  Layer trait names its own images — so a flyweight that shares only *identical whole
  definitions* has almost nothing to share (8 %). But 99 % of the type text is repeated at
  trait level: the 202 prototypes, expanded into every piece, account for it, and a single
  64-trait run recurs in 2 089 pieces. Only a flyweight at trait (prototype-run) granularity
  reaches that.
- In **Europa**, the whole definition is shared by ten or more pieces for three quarters of the
  pieces, so even the simple whole-piece flyweight captures 88 % of the type text, and the trait
  level adds little more. The state is genuinely per piece: the property sheet values, the
  movement trail, the basic piece's persistent properties. (Half of Europa's "state" bytes are
  the current text of each `label` trait, which is the label's *formula* and identical for every
  piece of a type — a per-instance copy of a per-definition constant, worth noting separately.)

## 2. What the pieces cost in the heap

| | WiF | Europa |
|---|---:|---:|
| All pieces | **308 MB**, 8.9 M objects | **13.0 MB**, 313 k objects |
| Per piece / per trait | 43.6 KB / 390 B | 11.1 KB / 460 B |
| One piece per distinct whole type | 266 MB (5 349 pieces) | 1.8 MB (133 pieces) |
| Strings and their arrays (`String`, `byte[]`, `String[]`) | 22 MB (7 %) | 1.3 MB (10 %) |

Where the 308 MB goes (WiF; Europa in the same proportions bar images):

| Kind of object | WiF | share |
|---|---:|---:|
| Trait objects themselves (`VASSAL.counters.*`, shallow) | 81 MB | 26 % |
| Swing configurers and their bean plumbing (`*Configurer`, `PropertyChangeSupport`, `ArrayTable`) | 52 MB | 17 % |
| Key strokes and key commands (`NamedKeyStroke[]`, `DynamicKeyCommand`, `KeyCommand`) | 49 MB | 16 % |
| Parsed expressions (`FormattedString` ×1.3 M, `PropertyExpression` ×812 k) | 47 MB | 15 % |
| `HashMap`s, `ArrayList`s and their node arrays | 40 MB | 13 % |
| Strings | 22 MB | 7 % |
| Image ops and painters | 12 MB | 4 % |

Two findings follow directly from the histogram:

- **The "smaller increment" of B2 — interning the duplicated strings — was already in the
  engine when it was proposed.** `SequenceEncoder.Decoder.nextToken()` has interned every token
  since VASSAL commit `9c3b4ce2b` of 10 March 2021 ("Intern some highly-duplicated strings",
  VASSAL 3.5), so every field of every trait, the `markerSpec` of a `PlaceMarker` and the raw
  type of a `UsePrototype` included, is one shared `String` across all pieces. The bloat
  analysis did not notice this and proposed it as new work; nothing was changed by this
  project. It is why strings are 7 % of the piece heap rather than the majority, and the item
  is marked complete in [wif-engine-optimizations.md](wif-engine-optimizations.md#b2-flyweight-prototype-expansion--share-immutable-trait-data-across-instances--measured).
- **The cost is objects built from those strings**, and it is dominated by parsing artefacts:
  a `FormattedString` for every message and property format, a `PropertyExpression` for every
  match expression, a `NamedKeyStroke[]` and a `KeyCommand` per menu entry, a `HashMap` per
  trait for its properties — and, remarkably, a Swing `Configurer` per instance of several
  traits: `DynamicProperty` constructs a `DynamicKeyCommandListConfigurer` in its constructor
  (`DynamicProperty.java:98`) and uses it as its parser and serialiser for the key-command list
  (`:108`, `:255`), so a WiF game carries 131 000 of them, each with its `PropertyChangeSupport`
  and listener tables. That alone is 52 MB, 17 % of the piece heap, in a running game where no
  editor is open.

## 3. What each flavour of B2 would save

The type-derived objects of a trait are everything above except the trait's state: the state is
a few fields — a layer's current level, a marker's value, a property's value, the attachment
list, the footprint — that `mySetState` fills in. So the shared part of a flyweight is bounded
by the *distinct* population's heap, and the per-instance residue is what cannot be shared.

**(a) String interning** — nothing to gain; it has been in the engine since 2021 (§2).

**(b) Whole-piece flyweight**, keyed on the complete type chain, the simplest to build:

| | WiF | Europa |
|---|---:|---:|
| Shared type data (measured: the distinct population) | 266 MB | 1.8 MB |
| Saving | **42 MB (14 %)** | **~11 MB (85 %)** |

Right for the modern module style, nearly useless for the legacy one — which is the one with
the problem.

**(c) Trait-level flyweight**, keyed on the trait's type segment (equivalently, on the prototype
run), which is what the proposal actually describes:

| | WiF | Europa |
|---|---:|---:|
| Distinct trait segments | 11 255 | 214 |
| Shared type data (at the measured 390–460 B per trait, plus the parsed objects that hang off it) | ≈ 5 MB | ≈ 0.1 MB |
| Per-instance residue: a trait shell per piece per trait (the chain itself is per piece — `getInner()`, `Properties.OUTER` — so an object of ~32–48 B per trait remains) | 796 k × ~40 B ≈ 32 MB | 28 k × ~40 B ≈ 1 MB |
| Per-instance state (attachments, persistent properties, values; the text is 8.4 MB / 0.7 MB) | ≈ 15–25 MB | ≈ 2 MB |
| **Estimated total** | **≈ 50–60 MB, a saving of ~250 MB (80–85 %)** | **≈ 3 MB, a saving of ~10 MB (75–80 %)** |

The WiF residue is dominated not by state but by the 796 000 per-trait shells the chain
structure forces; a design that also collapsed the chain into a per-piece array of state slots
over a shared definition would take the residue down to the state alone (≈ 20 MB) but is a
deeper rewrite of `Decorator` than the proposal contemplates.

**Construction time.** The same sharing applies to *time*: building a trait's type data is the
parsing above, done once per trait instance today. Measured with the harness:

| | WiF: all 7 072 pieces | WiF: 5 349 distinct | Europa: all 1 169 | Europa: 133 distinct |
|---|---:|---:|---:|---:|
| `createPiece` (parse the type chain, build the traits) | **59.1 s** | 53.1 s | 0.5 s | 0.1 s |
| `setState` (parse and apply the state chain) | 19.0 s | 16.3 s | 0.2 s | 0.0 s |
| Per trait | 75 µs + 24 µs | | 18 µs + 7 µs | |

So of the 133 s it takes to load the 8 855-piece game, about 60 s is constructing traits from
their type text and about 20 s applying state; the remainder is decoding the text and
executing the commands (placing pieces on maps, building stacks). A trait-level flyweight would
do the 60 s of type construction once per distinct segment — 11 255 times instead of 789 461,
under a second — and keep the 20 s of state, so the load would fall by roughly **55–60 s
(40–45 %)**; the whole-piece flyweight, sharing only 14 % of the traits, would save about 8 s.
The Europa load is 0.7 s of piece construction and would not change perceptibly.

## 4. Is it worth it?

Put the numbers against what a player experiences:

- **Resident heap is not what stops a large game from loading.** VASSAL's default maximum heap
  is 1 024 MB (`AbstractLaunchAction.DEFAULT_MAXIMUM_HEAP`). The WiF game's pieces resident are
  308 MB; the game as loaded (maps, stacks, module) settles at ~350 MB. What exceeded the heap
  was the *transient* of building the command log as a `String` — 3.2 GB peak on load — and
  that is removed by B1 ([vassal-stream-save-and-load.md](vassal-stream-save-and-load.md)),
  which loads the game inside 1.5 GB (665 MB peak). B2 would bring the resident part from
  ~350 MB to ~100 MB: welcome, but no longer the difference between loading and not loading.
- **Load time is where B2 would be felt.** Loading the 8 855-piece game takes 133 s, of which
  the text handling is a few seconds (B1 measured that) and the rest is constructing 8.9 million
  objects for 796 000 traits. A trait-level flyweight does that parsing once per distinct
  segment — 11 255 times instead of 796 000 — which removes about 60 s of the 133 s (§3):
  the load would be dominated by applying per-instance state and placing the pieces. For Europa-style games the load is
  already fast.
- **For modern (Europa-style) modules B2 is not needed**: 11 KB per piece, 13 MB for a full
  game, a saving of ~10 MB. Their memory goes on images, not pieces.
- **For legacy (WiF-style) modules B2 is a large win in load time and a 250 MB win in heap**,
  but only in its trait-level form, which is the expensive one: every trait's parsed fields
  must be split from its state and the shared part made immutable and reference-shared,
  across 48 trait classes and any custom traits in modules — and custom traits that do not
  follow the pattern would simply stay unshared, which is a graceful fallback.

**Cheaper things the histogram points at first**, with no format change and no refactor of the
trait model:

1. Stop building a Swing `Configurer` per trait instance. `DynamicProperty` (and, per the
   histogram, the traits using `NamedHotKeyConfigurer`, `PropertyChangerConfigurer` and
   `DynamicKeyCommandConfigurer` at runtime: 52 000 each) should parse and serialise their
   key-command lists with a plain parser and create the configurer only in the editor.
   **17 % of the piece heap** and a corresponding share of construction time, in a change of a
   few classes.
2. Share the parsed `FormattedString` / `PropertyExpression` for identical source text. They
   are immutable-in-practice and keyed by an already-interned string; a cache from source text
   to parsed object in their factories would share them across all pieces. **~15 %** of the piece
   heap (47 MB), and it removes the parse from construction.
3. Likewise `NamedKeyStroke` and `KeyCommand` arrays for identical definitions (~16 %).

Those three are the flyweight applied to the objects that dominate, without touching the trait
chain; together they approach half of the B2 saving for a fraction of its effort, and they are
the natural first steps on the way to it.

## 5. Reproducing

```bash
python3 tools/piece_sharing.py data/scenarios/FallOfFrance2004-May-I-1940.vsav \
                               data/scenarios/17-40-JA-EOT-Production-etctodo.vsav
```

The heap harness bootstraps the engine headlessly (as `refresh/RefreshRunner` does), reads the
`AddPiece` commands from the save, builds each population with `GameModule.createPiece` and
`setState` (the innermost piece set to no map), settles the heap with `System.gc()`, and writes
`GC.class_histogram` through the `DiagnosticCommand` MBean; the tables in §2 are differences
between the histograms of the populations.
