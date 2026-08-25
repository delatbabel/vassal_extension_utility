#!/usr/bin/env python3
"""Report — and optionally delete — every piece that is on no map.

A piece's innermost BasicPiece state begins with the map it is on
(`<map>;<x>;<y>;<gpid>;…`), or the literal `null` when it is on none. Off-map
pieces accumulate: a scenario built by swapping the map layout of another can be
left holding counters that belonged to the old layout and now belong nowhere, and
they are invisible in play while still costing memory and bytes in every save.

They are also **immune to Refresh Counters**: `GameRefresher.getRefreshables()`
builds its work list by walking map contents, so an off-map piece is never
collected, never rebuilt, and never reported as a warning.

## Read the report before deleting anything

Off-map does **not** by itself mean unwanted. In the WiF scenarios the largest
group by far is ownership markers — `US Owned`, `CW Owned`, `MajP Lending Strip`
and friends, ~529 per save, identical across the fif and nonfif variants of the
same scenario — which look like a deliberate off-map pool that pieces are drawn
from, not debris. Deleting those could break ownership marking. Against that,
some scenarios (`105`, `107`) carry **none at all**, so the population is clearly
not structural either.

That ambiguity is why this tool **reports by default and writes only with
`--apply`**, and why `--keep-name` exists: decide per piece name, not per save.

Decks are never affected — their contents always carry a real map id.

## Stacks are left alone deliberately

Removing a piece leaves its id dangling in any stack that listed it. That is
safe: `Stack.setState()` skips ids it cannot resolve. A later Refresh Counters
rebuilds the stacking, though only for stacks that are themselves on a map.

## Usage

    tools/remove_offmap_pieces.py SAVE.vsav [SAVE.vsav...]
                                  [--apply] [--no-backup]
                                  [--keep-name SUBSTR]... [--only-name SUBSTR]...
                                  [--module MODULE.vmod]

`--keep-name` excludes any piece whose name contains SUBSTR; `--only-name`
restricts the selection to names containing SUBSTR. Both are repeatable and
case-insensitive. `--module` additionally attributes each piece to the archive
that defines its GPID, which shows at a glance whether a group comes from an
extension that this scenario no longer uses.
"""
import os, re, sys, glob, zipfile
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from swap_maps import read_vsav, split_commands, write_vsav
from remove_placemark_carriers import (add_piece_fields, basic_piece, piece_map,
                                       piece_name, backup_path)

SLOT_TAGS = ('PieceSlot', 'CardSlot')


def gpid_owners(module_path):
    """-> {gpid: 'MODULE' | extension name}, for attributing pieces to archives."""
    owner = {}
    ext_dir = os.path.join(os.path.dirname(os.path.abspath(module_path)),
                           os.path.basename(module_path)[:-5] + '_ext')
    archives = [('MODULE', module_path)]
    archives += [(os.path.basename(f)[:-5], f)
                 for f in sorted(glob.glob(os.path.join(ext_dir, '*.vmdx')))]
    for label, path in archives:
        with zipfile.ZipFile(path) as z:
            root = ET.fromstring(z.read('buildFile.xml'))
        for el in root.iter():
            if el.tag.split('.')[-1] in SLOT_TAGS:
                gpid = (el.get('gpid') or '').strip()
                if gpid:
                    owner[gpid] = label
    return owner


def containers(state, toks):
    """-> {piece id: 'stack'|'deck'} for pieces listed inside one."""
    out = {}
    for ds, cs, end in toks:
        content = state[cs:end].decode('utf-8', 'replace')
        f = add_piece_fields(content)
        if not f:
            continue
        _, ptype, pstate = f
        inner = ptype.rpartition('\t')[2]
        kind = 'deck' if inner.startswith('deck') else 'stack' if inner.startswith('stack') else None
        if kind:
            for pid in re.findall(r'\d{10,}', pstate):
                out[pid] = kind
    return out


def main(argv):
    flags = {a for a in argv if a.startswith('--') and '=' not in a}
    keep, only, module = [], [], None
    for a in argv:
        if a.startswith('--keep-name='): keep.append(a.split('=', 1)[1].lower())
        elif a.startswith('--only-name='): only.append(a.split('=', 1)[1].lower())
        elif a.startswith('--module='): module = a.split('=', 1)[1]
    saves = [a for a in argv if not a.startswith('--')]
    if not saves:
        raise SystemExit(__doc__.strip().split('## Usage')[1].strip())

    owner = gpid_owners(module) if module else {}
    grand = 0
    for path in saves:
        state, entries = read_vsav(path)
        toks = split_commands(state)
        inside = containers(state, toks)
        drop, rows, freed = set(), {}, 0
        for idx, (ds, cs, end) in enumerate(toks):
            content = state[cs:end].decode('utf-8', 'replace')
            f = add_piece_fields(content)
            if not f:
                continue
            pid, ptype, pstate = f
            bp = basic_piece(ptype, pstate)
            if bp is None:
                continue
            bt, bs = bp
            if piece_map(bs) != 'null':
                continue
            name = piece_name(bt)
            low = name.lower()
            if any(k in low for k in keep):
                continue
            if only and not any(o in low for o in only):
                continue
            gpid = bs.split(';')[3] if bs.count(';') >= 3 else ''
            key = (name, owner.get(gpid, '(unmatchable)') if module else '',
                   inside.get(pid, 'loose'))
            rows[key] = rows.get(key, 0) + 1
            drop.add(idx)
            freed += end - cs

        print('\n%s: %d off-map piece(s), %d bytes (%.2f%% of the command log)'
              % (os.path.basename(path), len(drop), freed,
                 freed / max(len(state), 1) * 100))
        for (name, arch, where), n in sorted(rows.items(), key=lambda kv: -kv[1]):
            print('    %-30s %-22s %-6s x%d' % (name, arch, where, n))
        grand += len(drop)
        if not drop or '--apply' not in flags:
            continue

        if '--no-backup' not in flags:
            backup = backup_path(path)
            with open(path, 'rb') as src, open(backup, 'wb') as dst:
                dst.write(src.read())
            print('    backed up as %s' % os.path.basename(backup))

        parts, first = [], True
        for idx, (ds, cs, end) in enumerate(toks):
            if idx in drop:
                continue
            parts.append(state[cs:end] if first else state[ds:end])
            first = False
        write_vsav(path, b''.join(parts), entries)
        print('    wrote %s: %d of %d commands kept'
              % (os.path.basename(path), len(toks) - len(drop), len(toks)))

    print('\n%d off-map piece(s) across %d file(s)%s'
          % (grand, len(saves),
             '' if '--apply' in flags else ' — REPORT ONLY, pass --apply to delete'))


if __name__ == '__main__':
    main(sys.argv[1:])
