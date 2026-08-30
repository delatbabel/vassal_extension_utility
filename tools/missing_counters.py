#!/usr/bin/env python3
"""Report counters defined by an extension that a saved game does not contain.

Some WiF scenarios are meant to be complete: every counter of certain extensions
should be somewhere in them — on a map or in a force pool — so that a player can
reach any unit the extension adds. Whether that actually holds is not something
the module or VASSAL can answer, and a missing counter is silent: the unit simply
cannot be found in play.

## What counts as present

A counter is identified by its **Piece Id (GPID)**, which is what VASSAL itself
matches on. Every `AddPiece` command in the save contributes the GPID in the 4th
`;`-field of its innermost BasicPiece state, wherever the piece sits — a map, a
force pool, a deck or a stack — so presence is not restricted to a particular
map. A counter with no copy anywhere is `missing`.

**Off-map copies are called out separately.** A piece whose innermost state has
`map == "null"` is on no map at all: an orphan, typically left behind when a
scenario's map layout was swapped. It cannot be reached in play and Refresh
Counters cannot rebuild it (see remove_offmap_pieces.py), so a counter whose only
copies are off-map is reported as `off-map-only` rather than counted as present.

The counter's **name** is reported as found-elsewhere when some other piece in the
save carries the same name. That means the unit is in the scenario under a
different Piece Id — a renumbered slot, or a copy from another extension — so it
is likely a bookkeeping mismatch rather than a genuinely absent unit.

## Usage

    tools/missing_counters.py --ext-dir DIR --extensions 10,11,12 \\
                              [--csv OUT.csv] SAVE.vsav [SAVE.vsav...]

`--extensions` takes the leading numbers of the extension filenames (so `10`
selects `10-SiF.vmdx`). With no `--csv` the report is written to stdout.

`--exclude N:WORD[,WORD...]` drops counters of extension N whose name contains
any of the words, case-insensitively, from the set that has to be accounted for.
Not every counter an extension defines is meant to sit in a scenario: markers and
map furniture are placed during play, so counting them as missing buries the real
gaps. Excluded counters are reported once as a tally, never as rows.

`--dup-csv OUT.csv` writes the opposite report from the same scan: counters held
more than once. The copies are already counted to decide presence, so this costs
nothing beyond the file, and the two reports stay consistent — a counter cannot
appear in both.
"""
import csv, os, re, sys, zipfile
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from swap_maps import read_vsav, split_commands
from remove_placemark_carriers import add_piece_fields, basic_piece, piece_map, piece_name

SLOT_TAGS = ('PieceSlot', 'CardSlot')


def slot_gpid_and_name(elem):
    """(gpid, name) for a PieceSlot element, or None when it has no usable id.

    The name comes from the slot's own definition text, which is itself an
    AddPiece command (`+/null/<type>/<state>`) — the same parse the saved game
    gets, so the two sides are named identically.
    """
    gpid = (elem.get('gpid') or '').strip()
    if not gpid:
        return None
    name = (elem.get('entryName') or '').strip()
    fields = add_piece_fields((elem.text or '').strip())
    if fields:
        basic = basic_piece(fields[1], fields[2])
        if basic:
            name = piece_name(basic[0]) or name
    return gpid, name


def read_extension_slots(path):
    """[(gpid, name)] for every counter the extension defines."""
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        entry = 'buildFile.xml' if 'buildFile.xml' in names else 'buildFile'
        root = ET.fromstring(z.read(entry))
    out = []
    for elem in root.iter():
        if elem.tag.rpartition('.')[2] in SLOT_TAGS:
            got = slot_gpid_and_name(elem)
            if got:
                out.append(got)
    return out


def read_save_pieces(path):
    """(gpid -> [on_map, off_map] counts, {names present}, {extensions listed}).

    The extension list matters for reading the result: a save records the
    extensions loaded when it was written as `EXT\t<name>\t<version>`, and a
    counter from an extension the scenario never loaded is absent for a quite
    different reason than one whose extension is loaded but which was never
    placed. The first needs the extension adding to the scenario; the second
    needs the counter putting somewhere.
    """
    state, _ = read_vsav(path)
    counts, names, listed = {}, set(), set()
    for _, content_start, end in split_commands(state):
        content = state[content_start:end].decode('utf-8', 'replace')
        if content.startswith('EXT\t'):
            listed.add(content.split('\t')[1])
            continue
        fields = add_piece_fields(content)
        if not fields:
            continue
        basic = basic_piece(fields[1], fields[2])
        if not basic:
            continue
        btype, bstate = basic
        parts = bstate.split(';')
        gpid = parts[3].strip() if len(parts) > 3 else ''
        if not gpid:
            continue
        tally = counts.setdefault(gpid, [0, 0])
        tally[0 if piece_map(bstate) != 'null' else 1] += 1
        names.add(piece_name(btype))
    return counts, names, listed


def selected_extensions(ext_dir, wanted):
    """[(label, path)] for the requested leading numbers, in the order given."""
    found = {}
    for fn in sorted(os.listdir(ext_dir)):
        if not fn.lower().endswith('.vmdx'):
            continue
        m = re.match(r'0*(\d+)', fn)
        if m:
            found.setdefault(m.group(1).lstrip('0') or '0', []).append(fn)
    out, missing = [], []
    for w in wanted:
        key = w.lstrip('0') or '0'
        if key not in found:
            missing.append(w)
            continue
        for fn in found[key]:
            out.append((fn[:-len('.vmdx')], os.path.join(ext_dir, fn)))
    if missing:
        raise SystemExit('no extension in %s begins with: %s'
                         % (ext_dir, ', '.join(missing)))
    return out


def main(argv):
    ext_dir, wanted, out_csv, saves = None, None, None, []
    dup_csv = None
    excludes = {}                      # extension number -> [lowercased words]
    i = 0
    while i < len(argv):
        a = argv[i]
        if a == '--ext-dir':
            i += 1; ext_dir = argv[i]
        elif a == '--extensions':
            i += 1; wanted = [x.strip() for x in argv[i].split(',') if x.strip()]
        elif a == '--exclude':
            i += 1
            num, _, words = argv[i].partition(':')
            excludes.setdefault(num.strip().lstrip('0') or '0', []).extend(
                w.strip().lower() for w in words.split(',') if w.strip())
        elif a == '--csv':
            i += 1; out_csv = argv[i]
        elif a == '--dup-csv':
            i += 1; dup_csv = argv[i]
        elif a.startswith('--'):
            raise SystemExit('unknown option ' + a)
        else:
            saves.append(a)
        i += 1
    if not ext_dir or not wanted or not saves:
        raise SystemExit('usage: missing_counters.py --ext-dir DIR --extensions 10,11 '
                         '[--csv OUT.csv] SAVE.vsav [...]')

    extensions = selected_extensions(ext_dir, wanted)
    slots = []
    for label, path in extensions:
        got = read_extension_slots(path)
        num = re.match(r'0*(\d+)', label)
        words = excludes.get(num.group(1).lstrip('0') or '0' if num else '', [])
        kept = [(g, n) for g, n in got
                if not any(w in n.lower() for w in words)]
        slots.extend((label, g, n) for g, n in kept)
        print('%-32s %4d counters%s' % (label, len(kept),
              '  (%d excluded: %s)' % (len(got) - len(kept), ', '.join(words))
              if len(kept) != len(got) else ''), file=sys.stderr)
    print('%-32s %4d counters total\n' % ('', len(slots)), file=sys.stderr)

    rows, dups = [], []
    for save in sorted(saves):
        counts, names, listed = read_save_pieces(save)
        for label, gpid, name in slots:
            tally = counts.get(gpid)
            if tally and tally[0] + tally[1] > 1:
                dups.append({
                    'scenario': os.path.basename(save),
                    'extension': label,
                    'counter_name': name,
                    'gpid': gpid,
                    'copies': tally[0] + tally[1],
                    'copies_on_map': tally[0],
                    'copies_off_map': tally[1],
                })
        missing = offmap = 0
        absent_ext = sorted({lbl for lbl, _ in extensions} - listed)
        for label, gpid, name in slots:
            tally = counts.get(gpid)
            if tally and tally[0]:
                continue
            status = 'off-map-only' if tally else 'missing'
            missing += status == 'missing'
            offmap += status == 'off-map-only'
            rows.append({
                'scenario': os.path.basename(save),
                'extension': label,
                'counter_name': name,
                'gpid': gpid,
                'status': status,
                'copies_off_map': tally[1] if tally else 0,
                'name_found_elsewhere': 'yes' if name in names else 'no',
                'extension_listed_in_save': 'no' if label in absent_ext else 'yes',
            })
        print('%-52s %4d missing, %3d off-map-only%s'
              % (os.path.basename(save), missing, offmap,
                 '   [not loaded: %s]' % ', '.join(absent_ext) if absent_ext else ''),
              file=sys.stderr)

    fields = ['scenario', 'extension', 'counter_name', 'gpid', 'status',
              'copies_off_map', 'name_found_elsewhere', 'extension_listed_in_save']
    stream = open(out_csv, 'w', newline='', encoding='utf-8') if out_csv else sys.stdout
    try:
        w = csv.DictWriter(stream, fieldnames=fields)
        w.writeheader()
        w.writerows(rows)
    finally:
        if out_csv:
            stream.close()
            print('\nwrote %s (%d rows)' % (out_csv, len(rows)), file=sys.stderr)

    if dup_csv:
        dup_fields = ['scenario', 'extension', 'counter_name', 'gpid',
                      'copies', 'copies_on_map', 'copies_off_map']
        with open(dup_csv, 'w', newline='', encoding='utf-8') as f:
            w = csv.DictWriter(f, fieldnames=dup_fields)
            w.writeheader()
            w.writerows(dups)
        print('wrote %s (%d rows)' % (dup_csv, len(dups)), file=sys.stderr)
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
