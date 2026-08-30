#!/usr/bin/env python3
"""Give counters in one saved game the positions they hold in another.

When a family of scenarios is built from the same force pools, counters added to
all of them land in one arbitrary spot and then have to be distributed. Doing
that by hand once, in a reference scenario, and copying the result to the rest is
far less work than repeating it — and guarantees the family stays consistent.

## Two steps, because stacking belongs to the engine

A counter's position is three fields of its BasicPiece state (`<map>;<x>;<y>`),
which this could rewrite directly. Its **stacking** is not: a stack is a separate
piece whose state lists its members by id, so re-homing a counter by hand means
editing two stacks as well, getting the `@@<layer>` suffix right, and hoping the
destination stack exists.

So instead each counter is **removed** here and **re-placed by VASSAL** at the
reference position, where `Map.placeOrMerge` merges it into whatever stack is
there — exactly what dragging it would do. Removal needs no stack surgery
either: the id left behind in the old stack is dangling, and `Stack.setState()`
looks each member up and silently skips what it cannot resolve.

This writes the pruned saves and a job file per save; run the job files through
`AddCountersRunner` to complete the move:

    java -cp Vengine.jar:extension-utility.jar \\
         org.vassalengine.extutil.refresh.AddCountersRunner JOB

## What gets moved

Only counters that are **at the anchor** (`--anchor`) and have **exactly one copy
in the reference**. The anchor is what makes this safe to run against a scenario
holding other copies of the same counter: a counter already in its right place is
never touched.

`--gpid-file` narrows it further to a named set of Piece Ids, and is usually what
you want. Without it, *any* counter sitting at the anchor that the reference also
holds is moved to the reference's position for it — including counters that were
never part of the exercise and that the reference happens to place elsewhere. The
file is one Piece Id per line; blank lines and `#` comments are ignored.

## Usage

    tools/copy_counter_positions.py --reference REF.vsav --module MODULE.vmod
                                    --anchor "Map Name;X;Y" --jobs DIR
                                    [--gpid-file FILE] [--dry-run] [--no-backup]
                                    SAVE.vsav [SAVE.vsav...]
"""
import os, shutil, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from swap_maps import read_vsav, split_commands, write_vsav
from remove_placemark_carriers import (add_piece_fields, basic_piece, piece_name,
                                       backup_path)


def piece_positions(path):
    """gpid -> (name, map, x, y) for every counter with exactly one copy."""
    state, _ = read_vsav(path)
    seen = {}
    for _, cs, end in split_commands(state):
        f = add_piece_fields(state[cs:end].decode('utf-8', 'replace'))
        if not f:
            continue
        b = basic_piece(f[1], f[2])
        if not b:
            continue
        parts = b[1].split(';')
        if len(parts) < 4 or not parts[3]:
            continue
        gpid = parts[3]
        if gpid in seen:
            seen[gpid] = None                     # more than one copy: ambiguous
        else:
            seen[gpid] = (piece_name(b[0]), parts[0], parts[1], parts[2])
    return {g: v for g, v in seen.items() if v}


def main(argv):
    ref = module = anchor = jobs_dir = gpid_file = None
    flags = {a for a in argv if a.startswith('--') and '=' not in a}
    saves, i = [], 0
    while i < len(argv):
        a = argv[i]
        if a == '--reference':
            i += 1; ref = argv[i]
        elif a == '--module':
            i += 1; module = argv[i]
        elif a == '--anchor':
            i += 1; anchor = argv[i]
        elif a == '--jobs':
            i += 1; jobs_dir = argv[i]
        elif a == '--gpid-file':
            i += 1; gpid_file = argv[i]
        elif not a.startswith('--'):
            saves.append(a)
        i += 1
    if not (ref and module and anchor and jobs_dir and saves):
        raise SystemExit(__doc__.strip().split('## Usage')[1].strip())

    a_map, a_x, a_y = anchor.split(';')
    targets = piece_positions(ref)
    print('reference %s: %d counters with a unique copy'
          % (os.path.basename(ref), len(targets)))
    if gpid_file:
        wanted = set()
        with open(gpid_file, encoding='utf-8') as f:
            for line in f:
                line = line.split('#')[0].strip()
                if line:
                    wanted.add(line)
        missing = sorted(wanted - set(targets))
        targets = {g: v for g, v in targets.items() if g in wanted}
        print('  restricted to %d of %d listed Piece Id(s)%s'
              % (len(targets), len(wanted),
                 '; not uniquely present in the reference: ' + ', '.join(missing)
                 if missing else ''))
    os.makedirs(jobs_dir, exist_ok=True)

    grand = 0
    for path in saves:
        if os.path.abspath(path) == os.path.abspath(ref):
            continue
        state, entries = read_vsav(path)
        toks = split_commands(state)
        drop, moves, skipped = set(), [], []
        for idx, (ds, cs, end) in enumerate(toks):
            f = add_piece_fields(state[cs:end].decode('utf-8', 'replace'))
            if not f:
                continue
            b = basic_piece(f[1], f[2])
            if not b:
                continue
            parts = b[1].split(';')
            if len(parts) < 4:
                continue
            gpid = parts[3]
            if (parts[0], parts[1], parts[2]) != (a_map, a_x, a_y):
                continue                          # not at the anchor: leave alone
            if gpid not in targets:
                skipped.append((gpid, piece_name(b[0])))
                continue
            drop.add(idx)
            moves.append((gpid, targets[gpid]))

        name = os.path.basename(path)
        print('%s: %d to move, %d left at the anchor (not in the reference)'
              % (name, len(moves), len(skipped)))
        if not moves:
            continue

        job = os.path.join(jobs_dir, name.replace('.vsav', '.job'))
        with open(job, 'w', encoding='utf-8') as f:
            f.write('module=%s\n' % os.path.abspath(module))
            f.write('save=%s\n' % os.path.abspath(path))
            for gpid, (_, m, x, y) in moves:
                f.write('add=%s\t%s\t%s\t%s\n' % (gpid, m, x, y))
        print('    job: %s' % job)
        grand += len(moves)

        if '--dry-run' in flags:
            continue
        out = bytearray()
        for idx, (ds, cs, end) in enumerate(toks):
            if idx in drop:
                continue
            out += state[ds:end]
        if '--no-backup' not in flags:
            bak = backup_path(path)
            shutil.copy2(path, bak)
            print('    backed up as %s' % os.path.basename(bak))
        write_vsav(path, bytes(out), entries)
        print('    wrote %s: %d of %d commands kept'
              % (name, len(toks) - len(drop), len(toks)))

    print('\n%d counter(s) to re-place across %d file(s)%s'
          % (grand, len(saves) - 1, ' (dry run — nothing written)'
             if '--dry-run' in flags else ''))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
