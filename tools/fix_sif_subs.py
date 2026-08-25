#!/usr/bin/env python3
"""Replace the mis-named SUB counters in a .vsav with their correct twins.

The 10-SiF extension holds two copies of some submarine counters: the correct
SiF ones, named `<nation> S SUB <name>`, and incorrect leftovers named
`<nation> SUB <name>` (no " S "). Saved games built before the fix contain the
incorrect pieces. This script rewrites each such piece into its correct twin.

A piece in a save is an AddPiece command, `+/<id>/<type>/<state>`, whose type is
the *expanded* trait list (prototypes inlined) — so it can never be compared to
the PieceSlot definition as a whole. But the two slot definitions of a pair
differ in exactly two traits, neither of which contains a '/' or a tab, so both
appear verbatim inside the expanded type at any nesting depth:

  * the Embellishment (`emb2;Flip;…`) — the incorrect counter flips to
    `<image>b.png`, the correct one to `<image>bsif.png`;
  * the innermost `piece;;;<image>;<name>` — the name gains its " S ".

Those two substrings are spliced, and the innermost BasicPiece state's 4th
`;`-field (the gpid) is repointed at the correct slot. Everything else — the
piece id, its map, position, layer and properties — is copied verbatim, as is
every command that is not being edited. Pairs whose two definitions differ in
any other trait are refused and reported, never guessed at.

Rewriting follows SavedGame.saveWithout(): tokens copied byte-for-byte with
their own ESC delimiters, fresh obfuscation key, savedata/moduledata copied
whole, output via temp file + atomic replace.
"""
import argparse, os, re, sys, zipfile, xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from swap_maps import read_vsav, split_commands, write_vsav

CMD_DELIM = 0x1b        # ESC — the top-level command separator

SLOT_TAGS = ('PieceSlot', 'CardSlot')
BAD, GOOD = ' SUB ', ' S SUB '


def seq_split(s, delim='\t'):
    """Split a SequenceEncoder string at its delimiters.

    Nested encoding escapes each level's delimiter again, so a boundary is the
    delimiter preceded by any number of backslashes. Trait content here holds
    no tabs, so the split is exact.
    """
    return re.split(r'\\*' + re.escape(delim), s)


def command_fields(cmd):
    """`+/<id>/<type>/<state>` -> (id, type, state), or None if not an AddPiece.

    Splits on unescaped '/' — the delimiter SequenceEncoder used to build it.
    """
    if not cmd.startswith('+/'):
        return None
    cut = [m.start() for m in re.finditer(r'(?<!\\)/', cmd)]
    if len(cut) != 3:
        return None
    return cmd[cut[0] + 1:cut[1]], cmd[cut[1] + 1:cut[2]], cmd[cut[2] + 1:]


def sif_twin(name):
    """The SiF (" S ") name for a plain counter name, or None if it has none.

    Two shapes occur. Where the type is a word in the middle, the " S " is
    inserted before it: `CW SUB Amphion` -> `CW S SUB Amphion`. Where the name
    ends in the type, it goes before that final token: `CW T CA SUB1` ->
    `CW T CA S SUB1`, `CH T SUB` -> `CH T S SUB`. A name that already carries the
    " S " yields None, so an S counter is never treated as needing one.
    """
    if BAD in name and GOOD not in name:
        return name.replace(BAD, GOOD, 1)
    head, sep, last = name.rpartition(' ')
    if sep and last.startswith('SUB') and head.rsplit(' ', 1)[-1] != 'S':
        return head + ' S ' + last
    return None


def load_pairs(ext_paths, overrides=None):
    """-> ({incorrect name: pair}, [(incorrect name, why refused)])

    Slots are pooled across every archive in `ext_paths`, so a pair may span two
    of them — which is what happens when the S counters are split out into an
    extension of their own. Each pair records where both halves came from.

    `overrides` maps an incorrect name to the twin to use instead of the derived
    one — for a counter whose derived name is taken by an unrelated component. An
    override is validated exactly like a derived pair.
    """
    overrides = overrides or {}
    if isinstance(ext_paths, str):
        ext_paths = [ext_paths]

    slots = {}
    origin = {}
    for ext_path in ext_paths:
        with zipfile.ZipFile(ext_path) as z:
            root = ET.fromstring(z.read('buildFile.xml'))
        for el in root.iter():
            if el.tag.split('.')[-1] in SLOT_TAGS and el.get('entryName'):
                slots[el.get('entryName')] = (el.get('gpid'), el.text or '')
                origin[el.get('entryName')] = os.path.basename(ext_path)

    unknown = [n for n in overrides if n not in slots]
    if unknown:
        raise SystemExit('no such counter: %s' % ', '.join(unknown))

    pairs, refused = {}, []
    for name, (gpid, defn) in sorted(slots.items()):
        derived = sif_twin(name)
        if name not in overrides and derived is None:
            continue
        good_name = overrides.get(name) or derived
        if good_name not in slots:
            refused.append((name, 'no "%s" twin' % good_name.strip()))
            continue
        good_gpid, good_defn = slots[good_name]

        old, new = command_fields(defn), command_fields(good_defn)
        if not old or not new:
            refused.append((name, 'definition is not an AddPiece command'))
            continue
        old_traits, new_traits = seq_split(old[1]), seq_split(new[1])
        if len(old_traits) != len(new_traits):
            refused.append((name, 'twin has %d traits, not %d'
                            % (len(new_traits), len(old_traits))))
            continue
        differ = [i for i in range(len(old_traits))
                  if old_traits[i] != new_traits[i]]
        if differ != [len(old_traits) - 2, len(old_traits) - 1]:
            refused.append((name, 'twin differs in traits %s, not just the '
                            'flip image and name' % differ))
            continue

        pairs[name] = {
            'name': name, 'good_name': good_name,
            'from': origin.get(name, '?'), 'to': origin.get(good_name, '?'),
            'gpid': gpid, 'good_gpid': good_gpid,
            'emb2': old_traits[-2], 'good_emb2': new_traits[-2],
            'basic': old_traits[-1], 'good_basic': new_traits[-1],
        }
    return pairs, refused


def patch_command(cmd, pairs):
    """-> (rewritten command, pair) if it is an incorrect counter, else None."""
    fields = command_fields(cmd)
    if not fields:
        return None
    pid, ptype, pstate = fields
    basic = ptype.rpartition('\t')[2]
    if not basic.startswith('piece;'):
        return None
    name = basic.split(';')[4] if basic.count(';') >= 4 else ''
    pair = pairs.get(name)
    if pair is None:
        return None

    state = pstate.rpartition('\t')[2].split(';')
    # Both keys must match the incorrect slot: a same-named piece carrying a
    # different gpid is something else and is left alone.
    if basic != pair['basic'] or len(state) < 4 or state[3] != pair['gpid']:
        return None
    if ptype.count(pair['emb2']) != 1 or ptype.count(pair['basic']) != 1:
        raise SystemExit('%s: cannot locate a unique flip/basic trait to '
                         'splice; refusing' % name)

    ptype = ptype.replace(pair['emb2'], pair['good_emb2'])
    ptype = ptype.replace(pair['basic'], pair['good_basic'])
    state[3] = pair['good_gpid']
    pstate = pstate[:pstate.rindex('\t') + 1] + ';'.join(state) \
        if '\t' in pstate else ';'.join(state)
    return '+/%s/%s/%s' % (pid, ptype, pstate), pair


def fix(path, pairs):
    """-> (rewritten command log, entries, {incorrect name: count})"""
    state, entries = read_vsav(path)
    parts, fixed = [], {}
    for ds, cs, end in split_commands(state):
        parts.append(state[ds:cs])                      # delimiter, unchanged
        content = state[cs:end]
        result = None
        if content[:2] == b'+/':
            result = patch_command(content.decode('utf-8'), pairs)
        if result is None:
            parts.append(content)
            continue
        new, pair = result
        parts.append(new.encode('utf-8'))
        fixed[pair['name']] = fixed.get(pair['name'], 0) + 1

    return b''.join(parts), entries, fixed


def add_twins(path, pairs):
    """Add each matched counter's twin *alongside* it, in the same stack.

    Used for the "everything" scenarios, whose force pools are meant to hold one
    copy of every counter: there the plain counter must stay and its SiF twin be
    added next to it, rather than replacing it.

    For every piece matching an incorrect slot, a new `AddPiece` command is
    emitted immediately after it, carrying:

    - a fresh piece id, taken above the highest id already in the file;
    - the twin's type — the original's type with the two differing traits
      substituted, which is exactly what the twin's own definition expands to
      (the same substitution the replace path makes);
    - the original's state with the innermost gpid repointed at the twin's slot,
      and the `UniqueID` property reset to the new piece id, which is the
      invariant VASSAL maintains (a piece's UniqueID equals its own id).

    The new id is then inserted into the state of whichever stack listed the
    original, immediately after it, so the twin lands in the same force-pool
    stack directly above the counter it accompanies. Insertion is positional
    among the id tokens, which keeps it ahead of the trailing `@@<layer>` marker
    that `Stack` appends after the ids.

    -> (rewritten command log, entries, {incorrect name: count})
    """
    state, entries = read_vsav(path)
    toks = split_commands(state)

    # Pass 1: find the pieces to twin and allocate ids above everything in use.
    next_id = 0
    plan = {}                      # token index -> (new command bytes, old id, new id)
    for ds, cs, end in toks:
        content = state[cs:end].decode('utf-8', 'replace')
        f = command_fields(content)
        if f and f[0].isdigit():
            next_id = max(next_id, int(f[0]))
    next_id += 1

    fixed = {}
    for idx, (ds, cs, end) in enumerate(toks):
        content = state[cs:end].decode('utf-8', 'replace')
        f = command_fields(content)
        if not f:
            continue
        pid, ptype, pstate = f
        basic = ptype.rpartition('\t')[2]
        if not basic.startswith('piece;'):
            continue
        name = basic.split(';')[4] if basic.count(';') >= 4 else ''
        pair = pairs.get(name)
        if pair is None:
            continue
        bs = pstate.rpartition('\t')[2].split(';')
        if basic != pair['basic'] or len(bs) < 4 or bs[3] != pair['gpid']:
            continue
        if ptype.count(pair['emb2']) != 1 or ptype.count(pair['basic']) != 1:
            raise SystemExit('%s: cannot locate a unique flip/basic trait to '
                             'splice; refusing' % name)

        new_id = str(next_id)
        next_id += 1
        new_type = ptype.replace(pair['emb2'], pair['good_emb2']) \
                        .replace(pair['basic'], pair['good_basic'])
        bs[3] = pair['good_gpid']
        new_basic_state = ';'.join(bs)
        new_state = pstate[:pstate.rindex('\t') + 1] + new_basic_state \
            if '\t' in pstate else new_basic_state
        # A piece's UniqueID property mirrors its own id; keep that true.
        marker = 'UniqueID;' + pid
        if marker in new_state:
            new_state = new_state.replace(marker, 'UniqueID;' + new_id, 1)
        cmd = '+/%s/%s/%s' % (new_id, new_type, new_state)
        plan[idx] = (cmd.encode('utf-8'), pid, new_id)
        fixed[name] = fixed.get(name, 0) + 1

    added = {old: new for _, old, new in plan.values()}

    # Pass 2: rebuild, emitting each twin after its original and threading the
    # new ids into the stacks that hold them.
    parts, first = [], True
    for idx, (ds, cs, end) in enumerate(toks):
        content = state[cs:end]
        text = content.decode('utf-8', 'replace')
        f = command_fields(text)
        if f and added:
            inner = f[1].rpartition('\t')[2]
            if inner.startswith('stack'):
                content = _thread_into_stack(text, added).encode('utf-8')
        parts.append(content if first else state[ds:cs] + content)
        first = False
        if idx in plan:
            parts.append(bytes([CMD_DELIM]) + plan[idx][0])
    return b''.join(parts), entries, fixed


def _thread_into_stack(text, added):
    """Insert each new id directly after the id it accompanies, in a stack state."""
    pid, ptype, pstate = command_fields(text)
    head, sep, tail = pstate.rpartition('\t')
    body = tail if sep else pstate
    tokens = body.split(';')
    out = []
    for t in tokens:
        out.append(t)
        if t in added:
            out.append(added[t])
    rebuilt = ';'.join(out)
    return '+/%s/%s/%s' % (pid, ptype, (head + sep + rebuilt) if sep else rebuilt)


def main(argv):
    ap = argparse.ArgumentParser(
        description='Replace mis-named counters in a .vsav with their twins.')
    ap.add_argument('extension', metavar='EXTENSION.vmdx')
    ap.add_argument('--slots', metavar='EXT.vmdx', action='append', default=[],
                    help='additional archive to take counter definitions from, '
                         'for when the S counters live in a different extension; '
                         'repeatable')
    ap.add_argument('saves', metavar='SAVE.vsav', nargs='+')
    ap.add_argument('--in-place', action='store_true',
                    help='overwrite each save, keeping the original as .bak')
    ap.add_argument('--keep-bak', action='store_true',
                    help='with --in-place, allow an existing .bak to stand '
                         '(use on a second pass, so the .bak stays the '
                         'pristine original rather than the first pass output)')
    ap.add_argument('--pair', metavar='OLD=NEW', action='append', default=[],
                    help='use NEW as the twin of OLD instead of the derived '
                         '" S SUB " name; repeatable')
    ap.add_argument('--add', action='store_true',
                    help='ADD each twin alongside the original in the same stack, '
                         'instead of replacing the original (for the "everything" '
                         'scenarios, whose force pools hold one of every counter)')
    ap.add_argument('--dry-run', action='store_true',
                    help='report what would change, write nothing')
    args = ap.parse_args(argv)

    overrides = {}
    for spec in args.pair:
        old, sep, new = spec.partition('=')
        if not sep or not old or not new:
            ap.error('--pair wants OLD=NEW, got %r' % spec)
        overrides[old] = new

    archives = [args.extension] + args.slots
    pairs, refused = load_pairs(archives, overrides)
    print('%s: %d correctable counter pair(s)'
          % (', '.join(os.path.basename(a) for a in archives), len(pairs)))
    for name in sorted(overrides):
        if name in pairs:
            print('  pair given — %s -> %s' % (name, pairs[name]['good_name']))
    for name, why in refused:
        print('  NOT correctable — %s: %s' % (name, why))

    total = 0
    for path in args.saves:
        plain, entries, fixed = (add_twins if args.add else fix)(path, pairs)
        n = sum(fixed.values())
        total += n
        print('\n%s: %d piece(s) %s' % (os.path.basename(path), n,
                                        'to add' if args.add else 'to rewrite'))
        for name in sorted(fixed):
            p = pairs[name]
            cross = '' if p['from'] == p['to'] else '  [%s -> %s]' % (p['from'], p['to'])
            arrow = '+' if args.add else '->'
            print('  %-22s %s %-24s x%d%s'
                  % (name, arrow, p['good_name'], fixed[name], cross))
        if args.dry_run or not n:
            continue

        if args.in_place:
            # Move the original aside *before* writing, so the .bak is always
            # the untouched file and a failed write cannot destroy both.
            backup = path + '.bak'
            if os.path.exists(backup):
                if not args.keep_bak:
                    raise SystemExit('%s already exists; pass --keep-bak to '
                                     'leave it as it is' % backup)
                print('  keeping existing %s' % os.path.basename(backup))
            else:
                os.replace(path, backup)
            write_vsav(path, plain, entries)
            print('  wrote %s (original kept as %s)'
                  % (os.path.basename(path), os.path.basename(backup)))
        else:
            stem, _, suffix = path.rpartition('.')
            out = '%s (subs fixed).%s' % (stem, suffix)
            write_vsav(out, plain, entries)
            print('  wrote %s' % os.path.basename(out))

    print('\n%d piece(s) rewritten across %d file(s)%s'
          % (total, len(args.saves),
             ' (dry run — nothing written)' if args.dry_run else ''))


if __name__ == '__main__':
    main(sys.argv[1:])
