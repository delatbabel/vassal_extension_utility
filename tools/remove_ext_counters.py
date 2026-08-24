#!/usr/bin/env python3
"""Remove every counter belonging to given extensions from saved games.

For a scenario that has picked up counters from an extension it was never meant
to be played with — the extension was active when the scenario was built, so its
pieces went into the force pools, but the scenario's own extension list never
included it. Refresh Counters cannot fix that: the pieces match their definitions
perfectly, so they are not "excess" in the Excess-Units sense; they simply should
not be there.

A piece is attributed to an extension by its **GPID**: the 4th `;`-field of the
innermost BasicPiece state, looked up against the `gpid` attributes of every
PieceSlot in the module and each `<module>_ext/*.vmdx`. Since GPIDs are unique
across the module and its extensions (check that first — see
tools/renumber_gpids.py), that attribution is exact.

## Stacks are left alone deliberately

Force-pool pieces are almost always inside stacks, and a stack's state lists its
members by piece id. Removing a piece leaves those ids dangling — which is safe:
`Stack.setState()` looks each one up and silently skips what it cannot resolve
(`if (child != null)`), so a stack simply comes up with fewer members and a stack
that loses everything comes up empty. Run Refresh Counters afterwards and its
StackRefresher rebuilds the stacking from scratch, tidying both cases.

## Everything else is byte-for-byte

Only the targeted `AddPiece` commands are dropped, each with its preceding ESC
delimiter; every surviving command is copied verbatim, never decoded and
re-encoded. `savedata`/`moduledata` are copied whole and the output goes via a
temp file, exactly as model/SavedGame.saveWithout does.

## Dropping the dependency as well

`--drop-listing` additionally removes the saved game's `EXT<TAB><name><TAB><version>`
registration for each named extension, so the scenario stops declaring a
dependency it no longer has.

This is opt-in rather than automatic, and it is deliberately tied to the
extensions you are stripping: a scenario's true dependencies **cannot** be
derived from its counters alone. An extension that supplies only boards or charts
— `01-EURO-Maps`, say — contributes no piece definitions at all, so anything that
pruned "extensions with no counters present" would throw away exactly the entries
a scenario needs to render its maps.

## Usage

    tools/remove_ext_counters.py MODULE.vmod EXT_NAMES SAVE.vsav [SAVE.vsav...]
                                 [--drop-listing] [--dry-run] [--no-backup]

EXT_NAMES is a comma-separated list of extension names — the `.vmdx` file name
without its suffix, which is also what appears in the save's `EXT` commands, e.g.
`09-ClassicShips,21-PatiF-AmiF-HWs`.
"""
import os, re, sys, glob, zipfile
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from swap_maps import read_vsav, split_commands, write_vsav

SLOT_TAGS = ('PieceSlot', 'CardSlot')
EXT_PREFIX = 'EXT\t'


def extensions_dir(module_path):
    name = os.path.basename(module_path)
    stem = name[:-5] if name.lower().endswith('.vmod') else name
    return os.path.join(os.path.dirname(os.path.abspath(module_path)), stem + '_ext')


def gpid_owners(module_path):
    """-> {gpid: 'MODULE' | extension name} across the module and its extensions."""
    owner = {}
    archives = [('MODULE', module_path)]
    ext_dir = extensions_dir(module_path)
    for f in sorted(glob.glob(os.path.join(ext_dir, '*.vmdx'))):
        archives.append((os.path.basename(f)[:-len('.vmdx')], f))
    for label, path in archives:
        with zipfile.ZipFile(path) as z:
            root = ET.fromstring(z.read('buildFile.xml'))
        for el in root.iter():
            if el.tag.split('.')[-1] in SLOT_TAGS:
                gpid = (el.get('gpid') or '').strip()
                if gpid:
                    owner[gpid] = label
    return owner


def piece_gpid(content):
    """The innermost BasicPiece's gpid for an AddPiece command, else None."""
    if not content.startswith('+/'):
        return None
    cut = [m.start() for m in re.finditer(r'(?<!\\)/', content)]
    if len(cut) != 3:
        return None
    basic_type = content[cut[1] + 1:cut[2]].rpartition('\t')[2]
    if not basic_type.startswith('piece;'):
        return None
    fields = content[cut[2] + 1:].rpartition('\t')[2].split(';')
    return fields[3] if len(fields) > 3 and fields[3] else None


def piece_name(content):
    cut = [m.start() for m in re.finditer(r'(?<!\\)/', content)]
    basic_type = content[cut[1] + 1:cut[2]].rpartition('\t')[2]
    parts = basic_type.split(';')
    return parts[4] if len(parts) > 4 else '?'


def main(argv):
    flags = {a for a in argv if a.startswith('--')}
    args = [a for a in argv if not a.startswith('--')]
    if len(args) < 3:
        raise SystemExit('usage: remove_ext_counters.py MODULE.vmod EXT,EXT,... '
                         'SAVE.vsav [SAVE.vsav...] [--dry-run] [--no-backup]')
    module, names, saves = args[0], args[1], args[2:]
    targets = {n.strip() for n in names.split(',') if n.strip()}

    owner = gpid_owners(module)
    known = set(owner.values()) - {'MODULE'}
    unknown = targets - known
    if unknown:
        raise SystemExit('no such extension(s): %s\nknown: %s'
                         % (', '.join(sorted(unknown)), ', '.join(sorted(known))))
    print('attributing %d gpid(s) across the module and %d extension(s)'
          % (len(owner), len(known)))
    print('removing counters from: %s\n' % ', '.join(sorted(targets)))

    grand = 0
    for path in saves:
        state, entries = read_vsav(path)
        toks = split_commands(state)
        drop, per_ext, examples, listings = set(), {}, {}, []
        for idx, (ds, cs, end) in enumerate(toks):
            content = state[cs:end].decode('utf-8', 'replace')
            if content.startswith(EXT_PREFIX):
                name = content.split('\t')[1] if '\t' in content[len(EXT_PREFIX):] \
                    or content.count('\t') >= 1 else ''
                if '--drop-listing' in flags and name in targets:
                    drop.add(idx)
                    listings.append(name)
                continue
            gpid = piece_gpid(content)
            if gpid is None:
                continue
            ext = owner.get(gpid)
            if ext in targets:
                drop.add(idx)
                per_ext[ext] = per_ext.get(ext, 0) + 1
                examples.setdefault(ext, []).append(piece_name(content))

        print('%s: %d piece(s)' % (os.path.basename(path), sum(per_ext.values())))
        for ext in sorted(per_ext):
            print('    %-26s %5d   e.g. %s' % (ext, per_ext[ext],
                                               ', '.join(examples[ext][:3])))
        if listings:
            print('    dropping extension listing(s): %s' % ', '.join(sorted(listings)))
        grand += sum(per_ext.values())
        if not drop or '--dry-run' in flags:
            continue

        if '--no-backup' not in flags:
            stem, dot, suffix = path.rpartition('.')
            backup = '%s-backup.%s' % (stem, suffix)
            n = 2
            while os.path.exists(backup):
                backup = '%s-backup-%d.%s' % (stem, n, suffix)
                n += 1
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

    print('\n%d piece(s) removed across %d file(s)%s'
          % (grand, len(saves), ' (dry run — nothing written)' if '--dry-run' in flags else ''))


if __name__ == '__main__':
    main(sys.argv[1:])
