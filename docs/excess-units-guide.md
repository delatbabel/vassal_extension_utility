# How to Use "Excess Units …"

This is a step-by-step guide to the **Excess Units …** tool, which cleans up a
VASSAL saved game (`.vsav`) by removing game pieces that the module's currently
**active** extensions can no longer provide.

- For *why* a piece is flagged and *how* the file is rewritten safely, see the
  companion reference [vsav-excess-units.md](vsav-excess-units.md).
- For the saved-game file format, see [vsav-format.md](vsav-format.md).

## When to use it

Use **Excess Units …** whenever a saved game contains pieces that are not part of
the module + active-extension set it will actually be loaded with. The two common
situations are:

1. **A game was set up with more extensions than you now want.** You are building a
   new starting position (a scenario, a teaching setup, a house-rules variant) from
   an existing saved game, but you want to *drop* one or more optional extensions —
   for example **Convoys in Flames** (`10-CoiF`) or **Light Cruisers** (`12-CLs`).
   Once those extensions are deactivated, every counter that came from them becomes
   "excess": it has no definition in the remaining active set. This tool finds and
   removes exactly those counters so the new game loads cleanly with the reduced
   extension set.

2. **A save is from an older module or a different extension mix.** Pieces left over
   from an extension that has since been removed, renamed, or deactivated cannot be
   matched to any current definition.

In both cases, loading the untidied game produces errors such as:

```
Bad Data in Module:  Source: PoHW T MOT Narew.png  Error: Image not found
Bad Data in Module:  Source: Allied Prod Circle    Error: No such map
```

and running **Tools → Refresh Counters** inside VASSAL reports many:

```
Unable to match piece "germanyBPs" (GPID 10990) by name
```

Removing the excess units eliminates these messages.

## The workflow: dropping an unwanted extension

Suppose you have a game set up with the full extension set and you want a new game
**without Convoys in Flames and Light Cruisers**.

### 1. Deactivate the extensions you no longer want

The tool's notion of "excess" is relative to which extensions are **active** — the
`.vmdx` files directly inside the module's `_ext` directory. Extensions in the
`_ext/inactive/` sub-directory are ignored by VASSAL and by this tool.

1. Open the module in the **left** panel (**Open Module (left)**).
2. Click **Show Extensions**.
3. Select **Convoys in Flames** (`10-CoiF.vmdx`) and click **Deactivate**; do the same
   for **Light Cruisers** (`12-CLs.vmdx`). Each moves into `_ext/inactive/`.

> You can also deactivate extensions outside the utility (VASSAL's own extension
> manager, or by moving the `.vmdx` files into `_ext/inactive/` yourself). The tool
> only cares which extensions are active *at the moment you run it*.

Now the pieces that came from those two extensions are no longer backed by any active
definition — they are the units you want to remove.

### 2. Find the excess units

1. With the module still loaded in the **left** panel, click **Excess Units …** on
   the toolbar (or **Tools → Find Excess Units in Saved Game …**).
2. Choose the saved game (`.vsav`) you are converting.
3. Wait while the game is analysed (a progress dialog is shown — large games take a
   few seconds).

A dialog lists every excess unit, one per line, as `name  [image]`:

```
CoiF Convoy Escort   [convoyescort.png]   {10-CoiF.vmdx (Inactive)}
Light Cruiser CA     [cl_ca.png]          {12-CLs.vmdx (Inactive)}
germanyBPs           [GE BPs.png]
…
```

Reading the list:

- Entries shown in **grey with a `{… (Inactive)}` note** are available in an
  extension you deactivated. That is expected here — those are precisely the Convoys
  in Flames / Light Cruisers counters you set out to remove. The note tells you which
  inactive extension would restore them, in case you change your mind (re-activate it
  with **Show Extensions** instead of deleting).
- Entries shown **normally (no note)** are pieces that no active *or* inactive
  extension provides — genuinely orphaned counters (e.g. from an older module
  version).

### 3. Remove them and save under a new name

1. Click **Delete Excess Units**.
2. Confirm the prompt.
3. Choose a **new** file name when prompted. It defaults to
   `<original> (tidied).vsav`. Pick a name that reflects the new game, e.g.
   `Barbarossa (no CoiF, no CLs).vsav`.

The tidied game is written to the new file; **the original saved game is left
untouched**, so you always keep the full-extension version. The tool refuses to
overwrite the original.

### 4. Load the new game

Open the new `.vsav` in VASSAL **with only the intended extensions active**. It should
load without the "Image not found" / "No such map" errors, and **Tools → Refresh
Counters** should no longer report "Unable to match piece …".

## Notes and limitations

- **Match your extensions to the intended game before running the tool.** "Excess" is
  defined against the set of extensions active *when you run the analysis*. If you
  leave Convoys in Flames active, its counters will not be listed as excess. Deactivate
  first, then analyse.
- **A piece is removed only when it matches no active definition by GPID *and* by
  name.** A counter placed at run time whose definition is still active (same name) is
  never removed, and a counter still matchable by GPID (repairable by Refresh Counters)
  is never removed. See [vsav-excess-units.md](vsav-excess-units.md) for the exact
  rule.
- **Scope: units only.** The tool removes game pieces (counters). It does not remove
  decks, stacks, maps, or global properties. In practice removing the orphaned units
  clears the great majority of load-time and refresh-time errors.
- **Re-run as needed.** If you later decide to drop *another* extension, deactivate it
  and run the tool again on the already-tidied file, saving to yet another new name.
- **The original is always preserved**, and the tidied file is written atomically, so
  an interrupted save never corrupts anything.
