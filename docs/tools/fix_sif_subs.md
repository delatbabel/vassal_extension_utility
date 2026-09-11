# fix_sif_subs.py — swap mis-named counters for their correct twins

The `10-SiF` extension holds two copies of some submarine counters: the correct
SiF ones, named `<nation> S SUB <name>`, and incorrect leftovers named
`<nation> SUB <name>` with no `" S "`. Saved games built before the fix contain
the wrong pieces. This rewrites each such piece into its correct twin — or, with
`--add`, places the twin beside it.

## Synopsis

```
tools/fix_sif_subs.py [--in-place] [--keep-bak] [--dry-run] [--add]
                      [--pair OLD=NEW]... [--slots EXT.vmdx]...
                      EXTENSION.vmdx SAVE.vsav [SAVE.vsav...]
```

| Option | Effect |
|---|---|
| `--slots EXT.vmdx` | Additional archive to search for twin slots. Repeatable — use it when the two halves of a pair live in different extensions. |
| `--in-place` | Overwrite each save, moving the original to `<name>.vsav.bak` **first**. Refuses to run if a `.bak` already exists. |
| `--keep-bak` | With `--in-place`, leave an existing `.bak` alone, so it stays the pristine original on a second pass. |
| `--pair OLD=NEW` | Override the derived twin for one counter. Repeatable; validated exactly like a derived pair. |
| `--add` | Keep the original and add its twin into the same stack, instead of replacing it. |
| `--dry-run` | Report what would change and write nothing. |

Without `--in-place` each save is written alongside as `<stem> (subs fixed).vsav`.

## How it works

Open [diagrams/fix_sif_subs.html](diagrams/fix_sif_subs.html).

A piece in a save is an `AddPiece` command whose type is the **expanded** trait
list with prototypes inlined, so it can never be compared to a `PieceSlot`
definition as a whole. But the two slot definitions of a pair differ in exactly
two traits, and neither contains a `/` or a tab, so both appear verbatim inside
the expanded type at any nesting depth:

- the Embellishment (`emb2;Flip;…`) — the incorrect counter flips to
  `<image>b.png`, the correct one to `<image>bsif.png`;
- the innermost `piece;;;<image>;<name>` — the name gains its `" S "`.

Those two substrings are spliced, and the innermost BasicPiece state's 4th
`;`-field (the GPID) is repointed at the correct slot — the value VASSAL itself
stamps on a piece dragged from that palette slot. The piece id, map, position,
layer and properties are copied verbatim, as is every command not being edited.

A piece is only rewritten when **both** its name and its GPID match the incorrect
slot, so a same-named piece carrying some other GPID is left alone. The tool is
idempotent: a counter already rewritten no longer matches an incorrect slot.

## Examples

Preview, then rewrite in place:

```bash
tools/fix_sif_subs.py --dry-run "…_ext/10-SiF.vmdx" data/scenarios/*.vsav
tools/fix_sif_subs.py --in-place "…_ext/10-SiF.vmdx" data/scenarios/*.vsav
```

When the twins live in another extension — the plain counters stay in
`20-PatiF-AmiF-Ships` while their twins are in `25-PatiF-AmiF-SiF-SUBs`:

```bash
tools/fix_sif_subs.py --in-place \
    "…_ext/20-PatiF-AmiF-Ships.vmdx" \
    --slots="…_ext/25-PatiF-AmiF-SiF-SUBs.vmdx" \
    data/scenarios/*.vsav
```

A pair that crosses archives is reported as such:
`CW T CA SUB1 -> CW T CA S SUB1  [20-PatiF-AmiF-Ships.vmdx -> 25-PatiF-AmiF-SiF-SUBs.vmdx]`.

With an override, on a second pass that must not clobber the first pass's backup:

```bash
tools/fix_sif_subs.py --in-place --keep-bak \
    --pair "GE SUB TypeVIIC=GE S SUB TypeVIIC_S" \
    "…_ext/10-SiF.vmdx" data/scenarios/*.vsav
```

`GE SUB TypeVIIC` needs the override because `GE S SUB TypeVIIC` is already
taken by an unrelated original SiF counter with its own trait layout, image and
movement factor; the converted twin is `GE S SUB TypeVIIC_S`.

## `--add`: keep the original and add the twin beside it

The default **replaces** the counter. `--add` instead keeps it and adds its twin
into the same stack, which is what the "everything" scenarios need: their force
pools are meant to hold one copy of every counter, so both the plain and the SiF
version belong there.

```bash
tools/fix_sif_subs.py --add --in-place \
    "…_ext/20-PatiF-AmiF-Ships.vmdx" \
    --slots="…_ext/25-PatiF-AmiF-SiF-SUBs.vmdx" \
    data/scenarios/*everything*.vsav
```

For each match a new `AddPiece` command is emitted directly after the original,
carrying a fresh piece id allocated above the highest already in the file, the
twin's type, and the original's state with the GPID repointed and the `UniqueID`
property reset to the new id. The new id is threaded into the state of whichever
stack listed the original, **immediately after it** — positionally among the id
tokens, not appended, because a stack's state ends with a `@@<layer>` marker
(`Stack.HAS_LAYER_MARKER`) after which an id would be misread.

Only counters whose original is actually present get a twin. Afterwards, verify
that piece ids are still unique, that every twin appears in a stack, and that
each twin's `UniqueID` matches its own id.

## Afterwards

Swapping counters into a different extension makes the scenario depend on that
extension, which its recorded extension list will not mention. Run **Refresh
Counters** afterwards to add the entry — see
[docs/refresh-counters.md](../refresh-counters.md).

## See also

- [tools/README.md](../../tools/README.md#fix_sif_subspy--swap-mis-named-counters-for-their-correct-twins)
