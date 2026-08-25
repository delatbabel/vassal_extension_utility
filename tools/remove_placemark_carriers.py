#!/usr/bin/env python3
"""Delete off-map pieces carrying a stale embedded Place Marker.

The WiF module once had a Place Marker trait defined with an **embedded** marker
("Define Marker") rather than a reference: its `markerSpec` field held an entire
serialised piece inline, with further prototypes expanded inside it. That trait
was removed from the module in 2.1.2 — no `placemark` remains anywhere — but
pieces already stored in a saved game keep whatever traits they were baked with,
and each carrier's type is ~21 KB.

**Refresh Counters cannot clear them.** Every carrier has `map = null`: it is on
no map, sitting in an off-map stack. `GameRefresher.getRefreshables()` builds its
work list by walking *map contents*, so an off-map piece is never collected and
never rebuilt. (`DeleteNoMap` does not help either — it only applies to pieces
the refresher collected, and VASSAL has it disabled in its own dialog over issue
12902.) Refreshing such a save reports every counter refreshed, with no warnings,
and leaves the carriers byte-for-byte intact.

So they are orphans: invisible in play, immune to refresh, carried in memory and
in every save indefinitely. This removes them.

## What it selects

An `AddPiece` command is deleted when **both** hold:

1. its innermost BasicPiece state has `map == "null"` — the piece is on no map; and
2. its type contains a `placemark` trait.

A carrier that *is* on a map is **reported and kept**: that one is not an orphan,
and the right treatment is Refresh Counters, which will rebuild it from the
current definition and drop the trait.

## Stacks are left alone deliberately

Carriers sit inside stacks, whose state lists members by piece id, so removing a
piece leaves those ids dangling. That is safe: `Stack.setState()` looks each one
up and silently skips what it cannot resolve, so a stack comes up with fewer
members and one that loses everything comes up empty. A later Refresh Counters
rebuilds the stacking — though note it will only tidy stacks that are themselves
on a map.

Decks are unaffected: their contents always carry a real map id.

## Usage

    tools/remove_placemark_carriers.py SAVE.vsav [SAVE.vsav...]
                                       [--dry-run] [--no-backup]
"""
import os, re, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from swap_maps import read_vsav, split_commands, write_vsav


def add_piece_fields(content):
    """`+/<id>/<type>/<state>` -> (id, type, state), or None if not an AddPiece."""
    if not content.startswith('+/'):
        return None
    cut = [m.start() for m in re.finditer(r'(?<!\\)/', content)]
    if len(cut) != 3:
        return None
    return content[cut[0] + 1:cut[1]], content[cut[1] + 1:cut[2]], content[cut[2] + 1:]


def basic_piece(ptype, pstate):
    """(innermost type, innermost state) — the BasicPiece, or None if not one."""
    bt = ptype.rpartition('\t')[2]
    if not bt.startswith('piece;'):
        return None
    return bt, pstate.rpartition('\t')[2]


def piece_map(basic_state):
    return basic_state.split(';')[0]


def piece_name(basic_type):
    parts = basic_type.split(';')
    return parts[4] if len(parts) > 4 else '?'


def backup_path(path):
    stem, _, suffix = path.rpartition('.')
    candidate = '%s-backup.%s' % (stem, suffix)
    n = 2
    while os.path.exists(candidate):
        candidate = '%s-backup-%d.%s' % (stem, n, suffix)
        n += 1
    return candidate


def main(argv):
    flags = {a for a in argv if a.startswith('--')}
    saves = [a for a in argv if not a.startswith('--')]
    if not saves:
        raise SystemExit('usage: remove_placemark_carriers.py SAVE.vsav [SAVE.vsav...] '
                         '[--dry-run] [--no-backup]')

    grand = 0
    for path in saves:
        state, entries = read_vsav(path)
        toks = split_commands(state)
        drop, names, onmap, freed = set(), {}, {}, 0
        for idx, (ds, cs, end) in enumerate(toks):
            content = state[cs:end].decode('utf-8', 'replace')
            f = add_piece_fields(content)
            if not f:
                continue
            _, ptype, pstate = f
            if 'placemark' not in ptype:
                continue
            bp = basic_piece(ptype, pstate)
            if bp is None:
                continue
            bt, bs = bp
            name = piece_name(bt)
            if piece_map(bs) == 'null':
                drop.add(idx)
                names[name] = names.get(name, 0) + 1
                freed += end - cs
            else:
                onmap[name] = onmap.get(name, 0) + 1

        print('%s: %d off-map Place Marker carrier(s), %d bytes'
              % (os.path.basename(path), len(drop), freed))
        for n in sorted(names):
            print('    %-28s x%d' % (n, names[n]))
        if onmap:
            print('    KEPT — these carriers are on a map; run Refresh Counters on them instead:')
            for n in sorted(onmap):
                print('      %-28s x%d' % (n, onmap[n]))
        grand += len(drop)
        if not drop or '--dry-run' in flags:
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

    print('\n%d piece(s) removed across %d file(s)%s'
          % (grand, len(saves), ' (dry run — nothing written)' if '--dry-run' in flags else ''))


if __name__ == '__main__':
    main(sys.argv[1:])
