# `.vmod` — the module

A module is a ZIP whose entire structure lives in one XML document,
`buildFile.xml`, surrounded by the images it references.

**Diagram:** [diagrams/vmod-structure.md](diagrams/vmod-structure.md) (viewable here) · [diagrams/vmod-structure.html](diagrams/vmod-structure.html) (interactive, download and open in a browser)

```
Module.vmod  (ZIP)
├── buildFile.xml       the component tree              ← all structure is here
├── moduledata          name, version, VassalVersion
├── images/…            1297 entries in the sample module
├── BlankScenario.vsav  a PredefinedSetup's saved game
└── Help.txt, Intro.txt …
```

## The component tree

`buildFile.xml`'s root is always `VASSAL.build.GameModule`. **Every XML tag is a
fully-qualified Java class name**, and the nesting mirrors the object tree VASSAL
builds at run time. There is no schema; the tree is valid exactly when each class
accepts its parent and children.

The root of the sample module:

```xml
<VASSAL.build.GameModule
    ModuleOther1="" ModuleOther2=""
    VassalVersion="3.7.15"
    description="Add more charts, extensions, update scenarios"
    name="Europa Series New Map"
    nextPieceSlotId="37019"
    version="0.90">
```

| Attribute | Meaning |
|---|---|
| `name` | The module's display name; must match `moduledata`'s `<name>` |
| `version` | The module's own version |
| `VassalVersion` | The engine version that last saved it |
| `nextPieceSlotId` | The id allocator — see [below](#nextpieceslotid) |
| `description`, `ModuleOther1/2` | Free text |

### Common top-level components

The **editor label** column is what VASSAL's own module editor displays, sourced
from `Editor.{Key}.component_type` in `Editor.properties`. This project
reproduces 114 of them in `ComponentNode.DISPLAY_NAMES` so its tree reads like
the editor's.

| Class (suffix) | Editor label | Role |
|---|---|---|
| `GameModule` | Module | Root |
| `BasicCommandEncoder` | Basic Command Encoder | Serialises game commands |
| `Documentation` | Help Menu | Groups help/about documents |
| `GlobalOptions` | Global Options | Module-wide preferences |
| `PlayerRoster` | Definition of Player Sides | Player sides |
| `Map` | Map Window | A map window: boards, grids, stacks |
| `ChartWindow` | Chart Window Menu | Charts and tables |
| `PieceWindow` | Game Piece Palette | The counter tray |
| `PrototypesContainer` | Game Piece Prototype Definitions | Shared trait sets |
| `PredefinedSetup` | Pre-defined setup | Scenarios and setup menus |
| `GamePieceImageDefinitions` | Game Piece Image Definitions | Image-based counters |

### Where a component's name lives

There is no single `name` attribute. VASSAL routes a *different* attribute to
`setConfigureName()` per class, so reading a component's name means knowing which
one. The exceptions this project tracks (`ComponentNode.NAME_ATTRIBUTES`):

| Attribute | Classes |
|---|---|
| `mapName` | `Map`, `PrivateMap`, `PlayerHand` |
| `entryName` | `PieceSlot`, `CardSlot`, and the widgets — `TabWidget`, `BoxWidget`, `PanelWidget`, `ListWidget`, `MapWidget` |
| `chartName` | `Chart`, `HtmlChart`, `HTMLChart` |
| `title` | `AboutScreen`, `HelpFile` |
| `menuText` | `DeckGlobalKeyCommand`, `DeckSendKeyCommand`, `DeckSortKeyCommand` |
| `text` | `ChangePropertyButton`, `SpecialDieFace` |
| `side` | `ChessClock` |
| `flareName` | `Flare` |
| `name` | everything else |

When the class-specific attribute is empty, the fallback order is `name`,
`entryName`, `mapName`, `chartName`, `title`, `description`, `fileName`.

This is what lets a tool tell two map windows apart — *"World Maps [Map Window]"*
versus *"Impulse and Weather [Map Window]"* — where a naive `getAttribute("name")`
sees two anonymous components.

## Piece definitions

A counter is a `PieceSlot` (or `CardSlot`) whose **element text** — not its
attributes — holds the serialised piece:

```xml
<VASSAL.build.widget.PieceSlot entryName="AirUnit" gpid="17877" height="64" width="75"
>+/null/prototype;BlackAir→piece;;;;AirUnit/→null;0;0;17877;0</VASSAL.build.widget.PieceSlot>
```

The text is the same `+/<id>/<type>/<state>` record a saved game uses, with
`null` for the id — see [game-piece.md](game-piece.md).

Two consequences shape every tool that touches modules:

- **The GPID appears twice**: as the `gpid` attribute *and* as field 3 of the
  piece definition's innermost state. Renumbering a slot must change both, or
  the definition disagrees with itself.
- **Image names are inside the text.** A piece's images are `;`-, `,`-, `/`-,
  tab- and backslash-delimited fields of the definition, not attributes. Finding
  every image a module references means tokenising that text on
  `[;,/\t\r\n\\]` and matching the pieces against the archive's image set — which
  is what `ComponentNode.collectImageReferences` does, and what
  **Remove Unused Images** depends on.

`PrototypeDefinition` elements hold a piece definition in the same form, and are
what `prototype;<name>` references resolve to.

## Image references

Images live under the `images/` prefix and are referenced by **bare filename**.
Attributes that carry one:

| Attribute | Example |
|---|---|
| `image` | `image="MainMap.png"` |
| `icon` | `icon="/images/dice.gif"` |
| `fileName` | `fileName="cover.png"` |

A path beginning `/images/` is an **engine** image bundled with VASSAL, not a
module image — `icon="/images/dice.gif"` resolves inside `Vengine.jar`.

Entry modification times are the tile-cache key; see
[zip-container.md](zip-container.md#modification-times-are-a-cache-key).

## Pre-defined setups

```xml
<VASSAL.build.module.PredefinedSetup file="Barbarossa Classic.vsav"
    isMenu="false" name="Barbarossa: One Kick (Classic)" useFile="true"/>
```

`file` is the **literal ZIP entry name** of a saved game stored at the archive
root. It usually ends `.vsav` but is not required to. `useFile="false"` makes the
component a menu container that references no file.

The referenced entry is a complete `.vsav` — [vsav.md](vsav.md) applies to it in
full. Moving or copying a `PredefinedSetup` between archives must carry that
entry along; the Extension Utility does so automatically, and on a **Move**
removes it from the source only when no remaining `PredefinedSetup` still
references it.

## nextPieceSlotId

The root's `nextPieceSlotId` is the module's id allocator: the next GPID the
editor will hand out. It is a high-water mark, not a count, and it only ever
increases.

Its purpose is to make GPIDs unique *within* the module. It says nothing about
extensions, which allocate from their own counter — and that gap is where GPID
clashes come from. See [vmdx.md](vmdx.md#gpid-allocation).

## The invariant a module must not break

**A module must never contain a `VASSAL.build.module.ExtensionElement`.** That
wrapper class belongs only in an extension.

A module carrying one loads and mostly works, which is what makes it dangerous:
the failure surfaces later and misleadingly. VASSAL's *Tools → Refresh Counters*
rejects such a module with *"module was saved with older vassal version"*, an
error that editing and re-saving does not clear.

The Extension Utility enforces this: it refuses any Move/Copy into a module that
would introduce one — for instance recreating the parent chain of a component
taken from inside an extension — and directs the user to select a real parent
component in the module tree instead.

## See also

- [vmdx.md](vmdx.md) — how an extension grafts into this tree
- [game-piece.md](game-piece.md) — the record inside a `PieceSlot`
- [zip-container.md](zip-container.md)
