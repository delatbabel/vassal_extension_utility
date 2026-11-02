# Loading extensions with a module in the editor — `feature/load-extensions-with-module-in-editor`

Branch `feature/load-extensions-with-module-in-editor` in `../vassal`. The editor now loads a
module's extensions, as the Player does, so the player window it opens is the whole game — and
the module it saves is still only the module.

---

## 1. What was wrong

Open a module with **Edit Module** — say *WiF CE Official Combo ver 2.1.5* — then in the module
window choose **File → Load Game or Log…** and pick a saved game. A run of *"Image not found"*
errors appears, the window comes up with the boards missing, and only the counters defined in
the module itself are there. Everything the extensions supply is absent, even though every one
of them is active.

The cause is a two-line difference between the two ways a module is opened.
`LoadModuleAction.loadModule` (the Player) does this:

```java
GameModule.init(new GameModule(new DataArchive(f.getPath())));
Localization.getInstance().translate();
final GameModule m = GameModule.getGameModule();
new ExtensionsLoader().addTo(m);          //  <-- extensions
showWizardOrPlayerWindow(m);
```

`EditModuleAction.loadModule` overrides it and has neither the `ExtensionsLoader` call nor the
`translate()`. So in the editor the module is only ever half of itself.

Measured on WiF 2.1.5 with its 27 extensions (16 active, 11 inactive), building the module
exactly as each action does:

| | Maps | BoardPickers | PieceSlots |
|---|---:|---:|---:|
| Editor (before) | 33 | 33 | 1 637 |
| Player | 41 | 41 | 5 560 |

Eight maps and 3 923 counters short, which is precisely the reported symptom.

## 2. Why it is not a one-line fix

`ExtensionElement.addTo` grafts an extension's component in by making it a **real child** of the
module component it targets:

```java
extension.addTo(target);
target.add(extension);
```

That is what makes an extension work at run time — the map, the counters and the images are
simply *there*. It also means `AbstractBuildable.getBuildElement` will serialise them, because
it walks `getBuildables()`; and `ModuleExtension.build` ends with `g.add(this)`, so the
extension object itself is a child of `GameModule` too.

The module's `buildString()` is what the editor writes to `buildFile.xml` on save, and what it
compares against to decide whether the module is dirty. With the extensions loaded it grew from
**3 012 637** to **9 522 589** characters: 16 `ModuleExtension` elements and 2 147
`ExtensionElement`s, with every component inside them. An editor that merely called
`ExtensionsLoader` and then saved would silently absorb all 27 extensions into the module —
turning it monolithic behind the user's back and leaving 27 now-redundant `.vmdx` files that
would double every component on the next load.

So the fix has two halves: load the extensions, and make the module able to tell what it owns
from what an extension lent it.

## 3. What the branch does

**A registry of borrowed components** (`GameModule`). An identity set — two components can be
equal in every attribute and still be different components — with `addExtensionComponent` and
`isExtensionComponent`. It is populated in two places, which between them cover every extension
however it was loaded:

- `ExtensionElement.addTo`, for each grafted component, right after `target.add(extension)`;
- `ModuleExtension.build`, for the extension itself, right after `g.add(this)`.

**Three places stop serialising them.**

- `AbstractBuildable.getBuildElement` skips a child the registry knows — this covers the common
  case and, because the skip takes the whole child, its entire subtree with it.
- `BoardPicker.getBuildElement` iterates its own `possibleBoards` list rather than
  `getBuildables()`, so the skip above never reaches it. A board added by an extension — the
  most common graft there is, `Map:…/BoardPicker` — had to be filtered there as well. It is the
  **only** other class that serialises children itself: of the 22 overrides of
  `getBuildElement`, exactly two append child build elements, and the other is
  `ExtensionElement`, whose own serialisation into the *extension's* file is correct.
- `ConfigureTree.buildTreeNode` leaves them out of the module editor's tree. They are not the
  module's to edit, the module will not save them, and offering them would invite edits that
  silently vanish. This is a new `isHiddenFromTree` hook beside the existing "hide Plug-ins"
  rule.

**The extension editor is unaffected.** `ExtensionTree` deliberately shows the *whole* module
tree and greys out what the extension being edited does not own (`ExtensionTree.isEditable`,
`ExtensionRenderer`), so it overrides `isHiddenFromTree` to hide only Plug-ins. Hiding
extension components there would have emptied it of the very things it exists to edit.

**`EditModuleAction.loadModule`** loads the extensions and translates, and takes
`updateLastSave()` **after** both — so the dirty-check baseline matches what a save would now
write, and opening a module does not look like a change.

## 4. Measured

Same module, after the fix:

| | Maps | BoardPickers | PieceSlots | `buildString()` |
|---|---:|---:|---:|---:|
| Editor, no extensions | 33 | 33 | 1 637 | 3 012 637 |
| Editor, extensions loaded | **41** | **41** | **5 560** | **3 012 589** |
| Player, extensions loaded | 41 | 41 | 5 560 | — |

The editor now sees everything the player sees, and what it would save is the module alone:
**0** `ModuleExtension` and **0** `ExtensionElement` elements in the module's `buildString`, and
exactly the same 1 537 module-owned `PieceSlot`s as before.

### The 48 characters

The two editor figures differ by 48 characters, in **45 of those 1 537** `PieceSlot`s and
nowhere else — no other element or attribute changes. They are module-owned slots whose images
live in an extension: with no extensions loaded the image cannot be found and the slot falls
back to a placeholder size, so the module on disk currently records `width="60" height="60"`
(or `260`/`130`) for pieces that really are `75 × 64`.

This is not introduced by the change — it is what loading extensions has always done, and the
Player has always done it; the Player simply never saves. Saving from the editor will now
correct those 45 cached sizes to the ones a player actually sees. Worth knowing, because it is a
real (if small and beneficial) diff on the first save of a module whose last save was made
without extensions.

## 5. Scope

The branch does not change the file format, the extension format, or how extensions are
activated. It does not make extension components editable from the module editor — they are
hidden there, and the extension editor remains the way to edit them. A module saved from the
editor is byte-comparable with one saved before, apart from the 45 corrected piece sizes above.

## 6. Files changed

| File | Change |
|---|---|
| `VASSAL/build/GameModule.java` | the identity registry, `addExtensionComponent` / `isExtensionComponent` |
| `VASSAL/build/AbstractBuildable.java` | `getBuildElement` skips borrowed children |
| `VASSAL/build/module/ModuleExtension.java` | registers itself after `g.add(this)` |
| `VASSAL/build/module/ExtensionElement.java` | registers each grafted component after `target.add` |
| `VASSAL/build/module/map/BoardPicker.java` | `getBuildElement` skips borrowed boards |
| `VASSAL/configure/ConfigureTree.java` | `isHiddenFromTree` hook; hides borrowed components |
| `VASSAL/configure/ExtensionTree.java` | overrides it — the extension editor shows them |
| `VASSAL/launch/EditModuleAction.java` | loads extensions, translates, then `updateLastSave()` |
