# VASSAL Module File Format (.vmod)

A `.vmod` file is a standard ZIP archive containing the complete data for a VASSAL game module.

## Archive Contents

| Entry | Description |
|---|---|
| `buildFile.xml` | XML document describing the module's component hierarchy |
| `moduledata` | XML document with module metadata (name, version, timestamps) |
| `images/` | Directory containing all image assets referenced in the buildFile |
| `*.vsav` | Optional pre-built saved game files (scenarios) |
| `*.txt`, `*.html`, etc. | Optional documentation or help files |

## moduledata

```xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<data version="1">
  <version>0.90</version>
  <extra1/>
  <extra2/>
  <VassalVersion>3.7.15</VassalVersion>
  <dateSaved>1745847514547</dateSaved>
  <description>Human-readable description</description>
  <name>Module Display Name</name>
</data>
```

| Field | Description |
|---|---|
| `version` | The module's own version string |
| `VassalVersion` | The VASSAL engine version that last saved this file |
| `dateSaved` | Unix timestamp in milliseconds |
| `name` | Module display name |

## buildFile.xml Structure

The root element is always `VASSAL.build.GameModule`. Every child element corresponds to a Java class in the VASSAL engine (the XML tag is the fully-qualified class name). The hierarchy mirrors the Java object tree built at runtime.

```xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<VASSAL.build.GameModule
    VassalVersion="3.7.15"
    description="..."
    name="Europa Series New Map"
    nextPieceSlotId="37019"
    version="0.90">

  <VASSAL.build.module.BasicCommandEncoder/>
  <VASSAL.build.module.Documentation>
    <VASSAL.build.module.documentation.AboutScreen fileName="cover.png" title="..."/>
    <VASSAL.build.module.documentation.HelpFile fileName="Help.txt" fileType="archive" title="..."/>
  </VASSAL.build.module.Documentation>
  <VASSAL.build.module.GlobalOptions .../>
  <VASSAL.build.module.Map ...>
    <VASSAL.build.module.map.BoardPicker ...>
      <VASSAL.build.module.map.boardPicker.Board image="map.png" name="Main Map"/>
    </VASSAL.build.module.map.BoardPicker>
    ...
  </VASSAL.build.module.Map>
  <VASSAL.build.module.ChartWindow name="Charts">
    <VASSAL.build.widget.TabWidget entryName="tabs">
      ...
    </VASSAL.build.widget.TabWidget>
  </VASSAL.build.module.ChartWindow>
  <VASSAL.build.module.PieceWindow ...>
    ...
  </VASSAL.build.module.PieceWindow>
  <VASSAL.build.module.PrototypesContainer>
    <VASSAL.build.module.PrototypeDefinition .../>
    ...
  </VASSAL.build.module.PrototypesContainer>
  <VASSAL.build.module.PredefinedSetup isMenu="true" name="Scenarios" useFile="true">
    ...
  </VASSAL.build.module.PredefinedSetup>
  ...
</VASSAL.build.GameModule>
```

## Common Top-Level Component Types

The table below lists the most common top-level children of `VASSAL.build.GameModule`. The **Editor label** column shows the name VASSAL's module editor displays for each type in its component tree; this is the authoritative human-readable name sourced from `Editor.properties` (`Editor.{Key}.component_type`).

| XML Tag (class suffix) | Editor label | Purpose |
|---|---|---|
| `GameModule` | Module | Root element |
| `BasicCommandEncoder` | Basic Command Encoder | Handles game command serialisation |
| `Documentation` | Help Menu | Groups help/about documents |
| `GlobalOptions` | Global Options | Module-wide preferences and options |
| `PlayerRoster` | Definition of Player Sides | Manages player sides |
| `Map` | Map Window | A game map window (boards, grids, piece stacks) |
| `ChartWindow` | Chart Window Menu | A window containing charts/tables/images |
| `PieceWindow` | Game Piece Palette | The counter tray |
| `PrototypesContainer` | Game Piece Prototype Definitions | Stores piece prototypes (shared trait sets) |
| `PredefinedSetup` | Pre-defined setup | Saved scenarios and setup menus |
| `DiceButton` | Dice Button | A dice-rolling toolbar button |
| `TurnTracker` | Turn Counter | A turn-counter component |
| `GamePieceImageDefinitions` | Game Piece Image Definitions | Image-based counter definitions |
| `NotesWindow` | Notes Window | Shared notes panel |

## Image References

Image files are stored under the `images/` prefix inside the ZIP. The XML references them by filename only (without the `images/` prefix) in attributes such as:

| Attribute | Example |
|---|---|
| `image` | `image="MainMap.png"` |
| `icon` | `icon="/images/dice.gif"` (absolute path) |
| `fileName` | `fileName="cover.png"` |

Absolute paths beginning with `/images/` reference bundled VASSAL engine images rather than module images.

## Pre-defined Setup Files

A `PredefinedSetup` component with `useFile="true"` references a bundled saved game via its `file` attribute, e.g.:

```xml
<VASSAL.build.module.PredefinedSetup file="Barbarossa Classic.vsav" isMenu="false"
    name="Barbarossa: One Kick (Classic)" useFile="true"/>
```

The `file` value is the **literal ZIP entry name** of the saved game, stored at the archive root (not under `images/`). It usually ends in `.vsav` but is not required to. Setups with `useFile="false"` are menu containers only and reference no file.

Because the saved game lives in the same archive as the component, moving or copying a `PredefinedSetup` between a module and an extension must carry its `.vsav` entry along with it. The Extension Utility copies the referenced entry into the destination archive automatically. On a **Move** it also removes the entry from the source — but only when no other `PredefinedSetup` remaining in the source still references that file, so a save file shared between setups is never lost.

## nextPieceSlotId

The `GameModule` root element carries a `nextPieceSlotId` integer. This is a monotonically increasing counter used to assign unique IDs to `PieceSlot` elements. When adding new piece slots to a module, this counter must be incremented and the new slot must receive the old counter value as its `gpid` attribute.
