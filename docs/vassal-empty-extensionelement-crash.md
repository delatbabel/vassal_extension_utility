# VASSAL crash: an empty `ExtensionElement` throws a `NullPointerException`

This note documents a crash in the VASSAL engine (not in this utility) so it can
be fixed upstream later. It is triggered by a structurally-invalid — but
XML-valid — extension: an `ExtensionElement` that contains **no component**.

## Symptom

Loading a module with such an extension enabled aborts the launch with:

```
ERROR VASSAL.build.Builder - Error building VASSAL.build.module.ExtensionElement
java.lang.NullPointerException: Cannot invoke
  "VASSAL.build.Buildable.addTo(VASSAL.build.Buildable)" because "this.extension" is null
    at VASSAL.build.module.ExtensionElement.addTo(ExtensionElement.java:101)
    at VASSAL.build.Builder.build(Builder.java:82)
    at VASSAL.build.module.ModuleExtension.build(ModuleExtension.java:140)
    at VASSAL.build.module.ExtensionsLoader.addExtension(ExtensionsLoader.java:104)
    ...
```

## Root cause (VASSAL source)

`vassal-app/src/main/java/VASSAL/build/module/ExtensionElement.java`:

```java
45:  private Buildable extension;               // the single wrapped component

62:  public void build(Element e) {
        // ... parse the target path ...
71:    // find and build first child which is an element
72:    for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
73:      if (n.getNodeType() == Node.ELEMENT_NODE) {
74:        extension = Builder.create((Element) n);
75:        break;
76:      }
77:    }
      }                                          // <-- if there is NO child element,
                                                 //     `extension` stays null

98:  public void addTo(Buildable parent) {
99:    final Configurable target = targetPath.length == 0 ?
100:      GameModule.getGameModule() : targetPath[targetPath.length - 1];
101:    extension.addTo(target);                 // <-- NPE when extension == null
102:    target.add(extension);
103:  }
```

An `ExtensionElement` is designed to hold **exactly one** component: `build()`
picks up only the first child element and stops, and `addTo()` grafts that one
component into the parent module's tree. If the `<ExtensionElement …>` has a
`target` but **no child element at all**, `build()` never assigns `extension`,
and the subsequent `addTo()` dereferences null and crashes the whole load.
`getBuildElement()` (line 93) and `getAllImageNames()` (lines 106+) would fail
the same way.

There is no validation that an `ExtensionElement` actually contains a component.

## How an empty `ExtensionElement` comes to exist

The wrapper is valid XML, so it can arise from any editing path that leaves a
wrapper without its component, for example:

- A component is grafted into an extension (wrapped in its own `ExtensionElement`)
  and then later **moved back out** to the module — if the tool removes the
  component but not the now-childless wrapper.
- Hand-editing the `buildFile.xml`.

In the case that prompted this note, `SiF.vmdx` had **24** such empty wrappers,
all targeting `…/BoxWidget:Naval/BoxWidget:SiF`, left behind after components
were moved from the extension back into the module.

### ⚠️ VASSAL loads *every* file in the `_ext` directory

`ExtensionsManager` activates extensions by presence in the module's `_ext`
directory — **not** by a `.vmdx` suffix. Any file left there is loaded: a
backup such as `SiF.vmdx.bak`, a stray temp file, an old renamed copy. So
repairing a damaged extension in place is not enough — a backup of the *broken*
version sitting alongside it in the same directory will still be loaded and will
still crash the module. Backups must be moved **out of the `_ext` directory**
entirely (the `inactive/` subdirectory is not scanned). This is exactly what
bit the SiF repair: the fixed `SiF.vmdx` loaded fine, but a `SiF.vmdx.bak-emptyEE`
backup left beside it was loaded too and reproduced the crash.

## Suggested engine fix

Make `ExtensionElement` tolerate (and ideally warn about) a missing component
rather than crashing the entire module load. Options, in `ExtensionElement.java`:

1. **Guard the graft** — in `addTo()` (and `getBuildElement()` /
   `getAllImageNames()`), do nothing when `extension == null`:
   ```java
   public void addTo(Buildable parent) {
     if (extension == null) {
       // Empty ExtensionElement — nothing to graft. Log and skip.
       return;
     }
     final Configurable target = targetPath.length == 0
       ? GameModule.getGameModule() : targetPath[targetPath.length - 1];
     extension.addTo(target);
     target.add(extension);
   }
   ```
2. **Fail soft at build time** — in `build()`, if no child element is found,
   log a data warning (`ErrorDialog.dataWarning`) naming the extension and the
   `target`, and leave the element inert.

Either keeps one malformed wrapper from taking down the entire module load, and
lets the editor surface/repair it instead of crashing.

## What this utility does about it

The VASSAL Extension Utility never *creates* an empty `ExtensionElement` (it
writes one wrapper per component, always with its component inside — see
[AGENTS.md → Move / Copy Operation](../AGENTS.md)). It previously could *leave*
one behind when a grafted component was moved back out of an extension; it now
drops any `ExtensionElement` that a Move empties (`MainWindow.removeEmptyExtensionElements`),
so freshly edited extensions are safe. Extensions damaged by an earlier build
can be repaired by opening them and removing the empty wrappers (which is what
was done to fix `SiF.vmdx`).
