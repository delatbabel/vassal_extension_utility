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


def load_pairs(ext_path, overrides=None):
    """-> ({incorrect name: pair}, [(incorrect name, why refused)])

    `overrides` maps an incorrect name to the twin to use instead of the
    derived `" S SUB "` one — for a counter whose derived name is taken by an
    unrelated component. An override is validated exactly like a derived pair.
    """
    overrides = overrides or {}
    with zipfile.ZipFile(ext_path) as z:
        root = ET.fromstring(z.read('buildFile.xml'))

    slots = {}
    for el in root.iter():
        if el.tag.split('.')[-1] in SLOT_TAGS and el.get('entryName'):
            slots[el.get('entryName')] = (el.get('gpid'), el.text or '')

    unknown = [n for n in overrides if n not in slots]
    if unknown:
        raise SystemExit('%s: no such counter: %s'
                         % (os.path.basename(ext_path), ', '.join(unknown)))

    pairs, refused = {}, []
    for name, (gpid, defn) in sorted(slots.items()):
        if name not in overrides and (BAD not in name or GOOD in name):
            continue
        good_name = overrides.get(name) or name.replace(BAD, GOOD, 1)
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


def main(argv):
    ap = argparse.ArgumentParser(
        description='Replace mis-named counters in a .vsav with their twins.')
    ap.add_argument('extension', metavar='EXTENSION.vmdx')
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
    ap.add_argument('--dry-run', action='store_true',
                    help='report what would change, write nothing')
    args = ap.parse_args(argv)

    overrides = {}
    for spec in args.pair:
        old, sep, new = spec.partition('=')
        if not sep or not old or not new:
            ap.error('--pair wants OLD=NEW, got %r' % spec)
        overrides[old] = new

    pairs, refused = load_pairs(args.extension, overrides)
    print('%s: %d correctable counter pair(s)'
          % (os.path.basename(args.extension), len(pairs)))
    for name in sorted(overrides):
        if name in pairs:
            print('  pair given — %s -> %s' % (name, pairs[name]['good_name']))
    for name, why in refused:
        print('  NOT correctable — %s: %s' % (name, why))

    total = 0
    for path in args.saves:
        plain, entries, fixed = fix(path, pairs)
        n = sum(fixed.values())
        total += n
        print('\n%s: %d piece(s)' % (os.path.basename(path), n))
        for name in sorted(fixed):
            print('  %-22s -> %-24s x%d'
                  % (name, pairs[name]['good_name'], fixed[name]))
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
