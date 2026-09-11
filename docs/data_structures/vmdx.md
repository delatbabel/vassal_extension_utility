# `.vmdx` — the extension

An extension is a `.vmod` with one structural difference: it holds no components
directly. Every component sits inside a wrapper that names **where in the parent
module's tree it grafts**.

**Diagram:** [diagrams/vmdx-structure.html](diagrams/vmdx-structure.html)

```
Extension.vmdx  (ZIP)
├── buildFile.xml    the graft list
├── extensiondata    version, universal, dateSaved
├── moduledata       a copy of the parent module's
└── images/…         the extension's own assets
```

`moduledata` is a **copy of the parent module's**, not the extension's own
metadata — VASSAL stores it so it can tell, without opening the module, which
module an extension belongs to. The extension's own metadata is `extensiondata`.

## extensiondata

```xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<data version="1">
  <version>0.1</version>
  <extra1/>
  <extra2/>
  <VassalVersion>3.7.15</VassalVersion>
  <dateSaved>1745847579505</dateSaved>
  <description>Fire in the East/Scorched Earth Game Charts</description>
  <universal>false</universal>
</data>
```

The shape is `AbstractMetaData`'s, with `<universal>` in place of `moduledata`'s
`<name>`. `<universal>` is the `anyModule` flag: whether the extension may be
loaded against any module rather than only its parent.

**Three values are stored twice**, in `extensiondata` *and* as attributes on the
`buildFile.xml` root: `version`, `description` and `anyModule`/`universal`.
Editing one without the other leaves the extension disagreeing with itself —
VASSAL reads the root attributes when loading and the metadata when listing, so
the two places surface in different UI. The utility's **Edit Extension
Properties** writes both.

## The ModuleExtension root

```xml
<VASSAL.build.module.ModuleExtension
    anyModule="false"
    description="Fire in the East/Scorched Earth Game Charts"
    extensionId="8e6"
    module="Europa Series New Map"
    moduleVersion="0.90"
    nextPieceSlotId="0"
    vassalVersion="3.7.15"
    version="0.1">
```

| Attribute | Meaning |
|---|---|
| `module` | Parent module's `name`. The match VASSAL checks on load |
| `moduleVersion` | Parent module's version at the time the extension was saved |
| `version` | The extension's own version |
| `extensionId` | Three characters identifying this extension for id allocation |
| `anyModule` | `true` to allow loading against any module |
| `nextPieceSlotId` | This extension's own id allocator |
| `vassalVersion` | Engine version that last saved it |
| `description` | Free text |

### extensionId

`extensionId` is generated once, when the extension is created, as the **last
three characters of a random UUID** — `8e6` above. It is not a version, not a
sequence number and not derived from the name.

VASSAL warns against changing it, and the reason is in the next section: it is
half of every GPID the extension allocates, so changing it orphans every saved
piece that came from this extension.

## GPID allocation

This is the single most consequential difference between a module and an
extension, and the source of a whole class of bugs.

| Archive | New slot gets | Example |
|---|---|---|
| Module | `nextPieceSlotId++` | `17877` |
| Extension | `<extensionId>:<nextPieceSlotId++>` | `8e6:14` |

`ModuleExtension.generateGpId()` is one line:

```java
return extensionId + ":" + nextGpId++;
```

The prefix is what keeps two extensions from colliding: each draws from its own
counter, but the `extensionId` makes the results disjoint.

**The scheme only protects ids the extension allocated itself.** A slot *copied
in* from the module or from another extension keeps its plain numeric id — and
`GpIdChecker` keys on the raw attribute value when extensions are loaded, so two
archives can then claim the same number.

In practice that is the common case, not the corner case. Every `PieceSlot` in
the sample extension sets to hand — the eight Europa extensions and the
twenty-seven WiF ones — carries a plain numeric GPID; not one uses the prefixed
form, because their slots were all built by copying counters in rather than
creating them in the extension editor. Uniqueness across an extension set is
therefore something to verify, never something to assume.

When that happens, `GameRefresher.execute()` refuses to run and reports *"Unable
to run Refresh, module was saved with older vassal version"*. The message is
misleading: `GpIdChecker.testGpId()` flags a GPID that is empty, non-numeric or
**already seen**, and never looks at the VASSAL version. See
[renumber_gpids.py](../tools/renumber_gpids.md).

## ExtensionElement

An extension's `buildFile.xml` root has exactly one kind of child:

```xml
<VASSAL.build.module.ExtensionElement
    target="VASSAL.build.module.ChartWindow:Charts/VASSAL.build.widget.TabWidget:tabs">
  <VASSAL.build.widget.TabWidget description="Fire in the East/Scorched Earth"
      entryName="FitE-SE">
    …
  </VASSAL.build.widget.TabWidget>
</VASSAL.build.module.ExtensionElement>
```

Three rules govern these wrappers, and each exists because breaking it fails in a
way that is hard to diagnose.

### One component per wrapper

`ExtensionElement.build()` builds **only its first child element**, and its
`add()` keeps a single `extension` reference. Packing several components into one
wrapper makes VASSAL silently keep the first and drop the rest — and if a dropped
`Board` owned an image, that image then never displays even though its bytes and
tiles are fine.

The editor writes one wrapper per component even when several target the same
location, and so must any tool.

### A wrapper must never be left empty

`ExtensionElement.build()` leaves its `extension` field null when there is no
child, and `addTo()` then dereferences it. The result is a
NullPointerException that **aborts the whole module launch** — not a broken
extension, an unopenable module.

This is why deleting the last component grafted through a wrapper must delete the
wrapper too. See
[docs/vassal-empty-extensionelement-crash.md](../vassal-empty-extensionelement-crash.md).

### A wrapper must not be nested

Wrapping an `ExtensionElement` in another `ExtensionElement` produces a file
VASSAL loads but the module editor cannot edit. The outer wrapper has an empty
`target` (its "parent" is the extension root), and the editor cannot resolve the
component inside. Archives damaged this way can be repaired — the utility's
**Repair Double-Wrapped Extension Elements** collapses each outer wrapper whose
children are all themselves `ExtensionElement`s.

## The target path

`target` names a path in the **parent module's** component tree. Its grammar is
two levels of `SequenceEncoder`:

```
target := segment ( '/' segment )*
segment := className ':' configureName
```

- segments joined with `/`;
- within a segment, the class name and the component's configure name joined
  with `:`;
- both levels backslash-escape their delimiter, and a token beginning with `\`
  is single-quoted (see [sequence-encoder.md](sequence-encoder.md));
- **an empty target means graft at the module root.**

Real targets from the sample extension:

```
VASSAL.build.module.ChartWindow:Charts/VASSAL.build.widget.TabWidget:tabs
VASSAL.build.module.PredefinedSetup:Scenarios
```

Read the first as: *find the `ChartWindow` named "Charts", then the `TabWidget`
named "tabs" inside it, and add my component there.*

The `configureName` in each segment is the **class-specific** name attribute —
`entryName` for a widget, `mapName` for a map, and so on (see
[vmod.md](vmod.md#where-a-components-name-lives)). Building a target from the
wrong attribute produces a path that resolves to nothing, and the component
silently never appears.

VASSAL builds these with `ComponentPathBuilder`; this project's encoder
(`MainWindow.seqToken` / `seqJoin`) produces byte-identical output, verified
against the targets VASSAL itself wrote into the sample extensions.

## What an extension cannot do

An extension can only **add**. There is no removal or override element: a target
path says where to attach, never what to replace. Anything an extension appears
to change, it changes by adding a component that shadows or supplements the
module's.

This is also why a scenario's true extension dependencies cannot be derived from
its counters alone. An extension supplying only boards or charts —
`01-EURO-Maps`, say — defines no piece at all, so a rule that pruned "extensions
with no counters present" would discard exactly the entries needed to draw the
maps. Dependency lists are only ever safe to *add* to.

## Differences from `.vmod` at a glance

| | `.vmod` | `.vmdx` |
|---|---|---|
| Root element | `GameModule` | `ModuleExtension` |
| Metadata | `moduledata` | `extensiondata` + a copy of the module's `moduledata` |
| Component placement | Directly in the tree | Inside an `ExtensionElement` with a `target` |
| GPID form | `17877` | `8e6:14` — or plain, if copied in |
| May contain `ExtensionElement` | **Never** | Only these |

## See also

- [vmod.md](vmod.md) — the tree an extension grafts into
- [sequence-encoder.md](sequence-encoder.md) — the target-path encoding
- [docs/tools/renumber_gpids.md](../tools/renumber_gpids.md) · [drop_slots.md](../tools/drop_slots.md)
