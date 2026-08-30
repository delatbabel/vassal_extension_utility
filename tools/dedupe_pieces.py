#!/usr/bin/env python3
"""Reduce duplicated counters in a saved game to one copy each.

For a scenario whose force pools are meant to hold exactly one of every counter
but accumulated a second copy of some — typically because an earlier edit added a
counter that was already present under a different force-pool column.

A "duplicate" here means two or more `AddPiece` commands whose innermost
BasicPiece state carries the **same GPID**, i.e. two pieces built from the same
palette slot. The first in log order is kept and the rest dropped.

## Restrict it, always

Plenty of counters are legitimately present many times — in the WiF scenarios
`US Entry Option` appears 17 times (one per entry slot) and `Naval Units In Port
Details` 6 times (one per TF/port map). Deduplicating blindly would destroy them.

`--only-gpid` names the counters exactly, which is what a hand-checked list of
duplicates calls for — `--extension` reduces *every* duplicated counter of that
extension, including ones deliberately held in multiples. The two can be combined;
either alone is enough to select.

So `--extension` is **required**: only counters defined by the named extensions
are considered. Pass `--list` first to see what is duplicated and by whom.

## Stacks are left alone deliberately

Removing a piece leaves its id dangling in the stack that listed it, which is
safe: `Stack.setState()` skips ids it cannot resolve. A later Refresh Counters
rebuilds the stacking.

## Usage

    tools/dedupe_pieces.py MODULE.vmod SAVE.vsav [SAVE.vsav...]
                           (--list | --extension=NAME[,NAME...]
                                   | --only-gpid=GPID[,GPID...])
                           [--dry-run] [--no-backup]
"""
import os, re, sys, glob, zipfile
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from swap_maps import read_vsav, split_commands, write_vsav
from remove_placemark_carriers import (add_piece_fields, basic_piece, piece_name,
                                       backup_path)

SLOT_TAGS = ('PieceSlot', 'CardSlot')


def slot_index(module_path):
    """-> {gpid: (archive, counter name)} across the module and its extensions."""
    out = {}
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
                    out[gpid] = (label, el.get('entryName'))
    return out


def main(argv):
    flags = {a for a in argv if a.startswith('--') and '=' not in a}
    wanted = None
    only_gpid = None
    for a in argv:
        if a.startswith('--extension='):
            wanted = {x.strip() for x in a.split('=', 1)[1].split(',') if x.strip()}
        elif a.startswith('--only-gpid='):
            only_gpid = {g.strip() for g in a.split('=', 1)[1].split(',') if g.strip()}
    args = [a for a in argv if not a.startswith('--')]
    if len(args) < 2 or (wanted is None and only_gpid is None and '--list' not in flags):
        raise SystemExit('usage: dedupe_pieces.py MODULE.vmod SAVE.vsav [SAVE.vsav...] '
                         '(--list | --extension=NAME[,NAME...] | --only-gpid=GPID[,GPID...]) '
                         '[--dry-run] [--no-backup]')
    module, saves = args[0], args[1:]
    index = slot_index(module)

    for path in saves:
        state, entries = read_vsav(path)
        toks = split_commands(state)
        seen, drop, per_gpid = {}, set(), {}
        for idx, (ds, cs, end) in enumerate(toks):
            content = state[cs:end].decode('utf-8', 'replace')
            f = add_piece_fields(content)
            if not f:
                continue
            bp = basic_piece(f[1], f[2])
            if bp is None:
                continue
            bt, bs = bp
            fields = bs.split(';')
            gpid = fields[3] if len(fields) > 3 else ''
            if not gpid:
                continue
            arch, name = index.get(gpid, ('(unmatchable)', piece_name(bt)))
            per_gpid.setdefault(gpid, []).append(
                (idx, arch, name, fields[0], fields[1] if len(fields) > 1 else '',
                 fields[2] if len(fields) > 2 else ''))

        dups = {g: v for g, v in per_gpid.items() if len(v) > 1}
        if '--list' in flags:
            print('\n%s: %d gpid(s) present more than once' % (os.path.basename(path), len(dups)))
            by_arch = {}
            for g, v in dups.items():
                by_arch.setdefault(v[0][1], []).append((g, v))
            for arch in sorted(by_arch):
                rows = by_arch[arch]
                print('  %-26s %d duplicated counter(s)' % (arch, len(rows)))
                for g, v in sorted(rows, key=lambda kv: kv[1][0][2] or '')[:6]:
                    print('      %-26s gpid=%-6s x%d  at %s'
                          % (v[0][2], g, len(v),
                             '; '.join('%s (%s,%s)' % (r[3], r[4], r[5]) for r in v)))
            continue

        kept = []
        for g, v in dups.items():
            if only_gpid is not None and g not in only_gpid:
                continue
            if wanted is not None and v[0][1] not in wanted:
                continue
            for extra in v[1:]:
                drop.add(extra[0])
            kept.append((v[0][2], g, len(v), v[0], v[1:]))

        print('\n%s: %d counter(s) reduced to one copy, %d piece(s) dropped'
              % (os.path.basename(path), len(kept), len(drop)))
        for name, g, n, first, rest in sorted(kept):
            print('    %-26s gpid=%-6s kept (%s,%s)  dropped %s'
                  % (name, g, first[4], first[5],
                     ', '.join('(%s,%s)' % (r[4], r[5]) for r in rest)))
        if not drop or '--dry-run' in flags:
            continue

        if '--no-backup' not in flags:
            backup = backup_path(path)
            with open(path, 'rb') as src, open(backup, 'wb') as dst:
                dst.write(src.read())
            print('    backed up as %s' % os.path.basename(backup))

        parts, first_tok = [], True
        for idx, (ds, cs, end) in enumerate(toks):
            if idx in drop:
                continue
            parts.append(state[cs:end] if first_tok else state[ds:end])
            first_tok = False
        write_vsav(path, b''.join(parts), entries)
        print('    wrote %s: %d of %d commands kept'
              % (os.path.basename(path), len(toks) - len(drop), len(toks)))


if __name__ == '__main__':
    main(sys.argv[1:])
