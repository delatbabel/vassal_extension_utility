# VASSAL Extension File Format (.vmdx)

A `.vmdx` file is a standard ZIP archive that overlays additional components onto an existing `.vmod` module. It shares the same container format as a vmod but has a different internal structure.

## Relationship to the Module

Each extension targets a specific parent module (matched by module name and version). Extensions are stored in a subdirectory named `<module-basename>_ext/` alongside the module file:

```
EuropaNewMapV090.vmod
EuropaNewMapV090_ext/
    SE.vmdx
    WitD.vmdx
    FoF.vmdx
    ...
```

## Archive Contents

| Entry | Description |
|---|---|
| `buildFile.xml` | XML document describing the extension's component additions |
| `moduledata` | A copy of the parent module's `moduledata` (used to verify compatibility) |
| `extensiondata` | XML document with extension-specific metadata |
| `images/` | Image assets added or used exclusively by this extension |
| `*.vsav` | Optional scenario files bundled with this extension |

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

| Field | Description |
|---|---|
| `version` | Extension's own version string |
| `VassalVersion` | VASSAL engine version that last saved this file |
| `universal` | If `true`, the extension can load with any module (not just the one it targets) |

## moduledata

An exact copy of the parent module's `moduledata`, used by VASSAL to verify that the extension is compatible with the loaded module version.

## buildFile.xml Structure

The root element is `VASSAL.build.module.ModuleExtension`. Rather than replicating the full module hierarchy, it contains only `ExtensionElement` children. Each `ExtensionElement` specifies exactly where in the parent module's component tree its child component should be inserted.

```xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<VASSAL.build.module.ModuleExtension
    anyModule="false"
    description="Fire in the East/Scorched Earth Game Charts"
    extensionId="8e6"
    module="Europa Series New Map"
    moduleVersion="0.90"
    nextPieceSlotId="0"
    vassalVersion="3.7.15"
    version="0.1">

  <VASSAL.build.module.ExtensionElement
      target="VASSAL.build.module.ChartWindow:Charts/VASSAL.build.widget.TabWidget:tabs">
    <VASSAL.build.widget.TabWidget description="Charts title" entryName="FitE-SE">
      <VASSAL.build.widget.MapWidget entryName="Turn Record Chart">
        ...
      </VASSAL.build.widget.MapWidget>
    </VASSAL.build.widget.TabWidget>
  </VASSAL.build.module.ExtensionElement>

  <VASSAL.build.module.ExtensionElement
      target="VASSAL.build.module.PredefinedSetup:Scenarios">
    <VASSAL.build.module.PredefinedSetup isMenu="true" name="Fire in the East" useFile="true">
      <VASSAL.build.module.PredefinedSetup file="scenario.vsav" isMenu="false" name="..." useFile="true"/>
    </VASSAL.build.module.PredefinedSetup>
  </VASSAL.build.module.ExtensionElement>

</VASSAL.build.module.ModuleExtension>
```

## ModuleExtension Root Attributes

| Attribute | Description |
|---|---|
| `anyModule` | `true` if the extension is universal (works with any module) |
| `description` | Human-readable extension description |
| `extensionId` | Short hex string, unique identifier for this extension |
| `module` | Name of the parent module (must match `moduledata/name`) |
| `moduleVersion` | Version of the parent module this extension targets |
| `nextPieceSlotId` | Counter for assigning unique IDs to new PieceSlot elements added by this extension |
| `vassalVersion` | VASSAL version that last saved this file |
| `version` | Extension version string |

## ExtensionElement and Target Paths

`VASSAL.build.module.ExtensionElement` is the key structural element. Its `target` attribute is a `/`-separated path identifying a container in the parent module's component tree.

Each path segment has the form `ClassName:identifierValue` where:
- `ClassName` is the fully-qualified Java class name of the component
- `identifierValue` is the component's `name`, `entryName`, or other identifying attribute

**Example:** `VASSAL.build.module.ChartWindow:Charts/VASSAL.build.widget.TabWidget:tabs`

This means: find the `ChartWindow` whose `name` is `"Charts"`, then find the `TabWidget` whose `entryName` is `"tabs"` inside it. The `ExtensionElement`'s child component will be appended to that `TabWidget`.

**Common target patterns:**

| Target | Editor label | Inserts into |
|---|---|---|
| `VASSAL.build.module.ChartWindow:Charts/VASSAL.build.widget.TabWidget:tabs` | Chart Window Menu → Tabbed Panel | The Charts window tab strip |
| `VASSAL.build.module.PredefinedSetup:Scenarios` | Pre-defined setup | The Scenarios setup menu |
| `VASSAL.build.module.PieceWindow:pieces/VASSAL.build.widget.TabWidget:...` | Game Piece Palette → Tabbed Panel | A counter tray tab |

> **Note on editor display names:** VASSAL's module editor shows human-readable labels for each component type (e.g., `VASSAL.build.module.gamepieceimage.FontManager` appears as "Font Styles"). These labels come from `Editor.*.component_type` entries in VASSAL's `Editor.properties` i18n file. The Extension Utility uses the same labels in its component tree. See `ComponentNode.java` for the complete mapping.

## Image References

Image files are stored under `images/` inside the vmdx ZIP, exactly as in a vmod. The same attribute naming conventions apply (`image`, `icon`, `fileName`).

Because the same image may be referenced by both the parent module and one or more extensions, images are **never automatically removed** from a source archive when moving a component. They should always be **copied** to the destination archive.

## Differences from .vmod at a Glance

| Aspect | .vmod | .vmdx |
|---|---|---|
| Root element | `VASSAL.build.GameModule` | `VASSAL.build.module.ModuleExtension` |
| Metadata file | `moduledata` only | `moduledata` + `extensiondata` |
| Component structure | Full hierarchy | Flat list of `ExtensionElement` wrappers |
| Standalone | Yes | No — requires the parent module |
