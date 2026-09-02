#!/usr/bin/env python3
"""Migrate a WiF 1.5.93 scenario to the 2.1.3 deluxe module.

The 1.5.93 module ("WiF CE Maps and Units Combo") kept every map and counter in
the main module; 2.1.3 ("WiF CE Official Combo") splits them across extensions
and reworked several charts. This rewrites a 1.5.93 saved game so it loads under
2.1.3 with the deluxe extension set (10-SiF, 11-CoiF, 12-CVPs, 13-CLs, 14-DiF,
15-TiF, 16-PiF, 18-Production-FiF) plus the map extensions the donor layout
needs (01/02/03). GPIDs were preserved between the two modules (verified: 5225
of 6955 pieces in the reference scenario match by GPID and name), so a piece is
kept when its GPID or name exists in the deluxe set and a later Refresh Counters
run rebuilds every kept piece from the 2.1.3 definitions.

    tools/migrate_15_to_21.py OLD.vsav DONOR.vsav MODULE.vmod OUT.vsav
                              [--jobs OUT.job] [--csv OUT.csv] [--dry-run]

- OLD is the 1.5.93 scenario (read only).
- DONOR is an empty 2.1.3 scenario supplying the board layouts (every
  BoardPicker command) and the moduledata entry. Its World Maps layout has
  `ASIA Main Insert` doubled in at column 4 row 1; the layout this writes uses
  `PACIFIC Main Insert` there.
- MODULE is the 2.1.3 module; its `<module>_ext/` siblings are scanned for the
  allowed piece definitions.
- OUT must be a new path; the original is never touched.

## What it does, in one pass over the command log

- **Board layouts**: every old BoardPicker command is dropped and the donor's
  full set (with the Pacific fix) is inserted before `end_save`, along with
  `EXT` registrations for the deluxe extension list.
- **Map renames** (pieces, stacks and decks): `Allied TFs` -> `CW TFs & Ports`,
  `Axis TFs` -> `Japan TFs & Ports`, `Game MGT` -> `Impulse and Weather`,
  `Minors FiF Chart` -> `CW FiF Chart`. Coordinates are kept ("same position");
  the TF charts' artwork moved, so players tidy those.
- **Removals**: pieces defined only in the non-deluxe extensions (09, 19-27);
  everything on `Resourcesetc` and `Trade Agreements` (replaced by the empty
  Build Points chart); everything on maps that no longer exist (Africa,
  Scandinavia, the DoD/ULDivs/MajP/Prod-Circle charts...); the dropped counter
  families (`* Multi TF Mkr`, `Multi TF Name N`, and a short explicit list);
  and the old module's 655 empty off-map decks.
- **Counter renames**: the convoy points, oilers and tankers 2.1.3 renamed with
  a ` Mod` suffix (`CW S CPs` -> `CW S CPs Mod`, 16 pairs) and the BP display
  counters (`usaBPs` -> `USABPsDisplay`, 8 pairs) — the innermost BasicPiece
  name and gpid are repointed at the new slot, so Refresh Counters rebuilds the
  full definition.
- **Control markers**: the old per-nation `X Control` counters became one
  layered `Hex Control Marker`. Byte surgery cannot expand its prototypes, so
  the old pieces are removed here and `--jobs` writes an AddCountersRunner job
  (`add=<gpid>\\t<map>\\t<x>\\t<y>\\tlayer:majorhexcontroller=<level>`) that has
  the engine place one marker per removed piece, nation layer set.
- **Relocations**: pieces on `FF Display` (chart redrawn) and the one piece from
  `Minors FiF Chart` are laid out in a grid at the top-left of their new map.
- **Decks**: none of the old module's decks survive — a deck's identity (its
  internal key, position, piece id) belongs to the module that defined it, and
  the old force-pool boxes sit where the OLD chart art had them. The donor's
  own deck commands are copied in verbatim instead (ids, keys, positions,
  face-down flags, member lists — dangling member ids are skipped harmlessly
  on load), minus the excluded Prod Circle ones. On the charts both modules
  share (US Entry, the FiF charts, Game MGT/Impulse and Weather) the old and
  new deck positions are identical, so kept counters still sit on their pools.
- **moduledata** is taken from the donor so the save identifies as 2.1.3.

Everything not edited is copied verbatim, delimiters and nesting untouched, and
the output is re-obfuscated whole and written atomically (see tools/README.md).

## Afterwards

1. `java -cp "$VENGINE:$UTILJAR" org...refresh.AddCountersRunner OUT.job`
   places the control markers (engine-built, stacking handled by placeOrMerge).
2. Run **Refresh Counters** against the 2.1.3 module with extensions
   01/02/03 and 10-16/18 active, so every piece is rebuilt to its 2.1.3
   definition, stacks are reknit and the save metadata is restamped.
   **Options: RefreshPieces, UseName, UseLayerName, UseRotateName,
   UseLabelerName.** The layer/rotate/labeler name options are load-bearing:
   the refresher copies a trait's state only when the old and new trait *type
   strings* match exactly, and 2.1.3 changed keystrokes inside nearly every
   Layer definition — without UseLayerName no Flip state transfers, and since
   the 2.1.3 palette pieces are saved face-DOWN, every migrated counter comes
   out face down. With it, layers match by name (`rev`, `facedown`, `OOS`, …)
   and the game's real facing survives.
"""
import collections, csv, os, re, sys, zipfile
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from swap_maps import (read_vsav, split_commands, board_picker_tokens,
                       obfuscate, SAVED_GAME, SAVE_DATA, MODULE_DATA)
from remove_placemark_carriers import add_piece_fields, basic_piece, piece_name

ESC = b'\x1b'
ALLOWED_EXT_NUMS = ('10', '11', '12', '13', '14', '15', '16', '18')
EXT_KEEP = {'01-EURO-Maps', '02-APAC-Maps', '03-Americas-AmiF-Map',
            '10-SiF', '11-CoiF', '12-CVPs', '13-CLs', '14-DiF', '15-TiF',
            '16-PiF', '18-Production-FiF'}

MAP_RENAMES = {
    'Allied TFs': 'CW TFs & Ports',
    'Axis TFs': 'Japan TFs & Ports',
    'Game MGT': 'Impulse and Weather',
    'Minors FiF Chart': 'CW FiF Chart',
}
# Donor maps supplied by extensions the deluxe game does not load: the donor
# was saved with 17-ProductionCircles active, so it carries layouts for its two
# charts — copying them over would make VASSAL complain about unknown maps, and
# anything on them would be orphaned.
DONOR_MAP_EXCLUDES = {'Allied Prod Circle', 'Axis Prod Circle'}
# Charts discarded outright with everything on them (replaced by the empty
# Build Points chart); other vanished maps are dropped by the donor-map test.
DISCARD_MAPS = {'Resourcesetc', 'Trade Agreements'}

# Counters that no longer exist in 2.1.3 and are dropped by name wherever they
# sit (per-map junk would otherwise survive on surviving maps). Note *not*
# 'Impulse Manager Mirror': it still exists in 2.1.3, and only its Africa/
# Scandinavia copies go (with those maps); the six on World Maps stay.
DROP_NAMES = {
    'End of Turn Roll', 'Alternative 2D10 CRT Overlay',
    'count1a', 'count1b', 'count2a', 'count2b',
    'Set Manual String Start',
}
DROP_NAME_RES = [re.compile(r'.* Multi TF Mkr$'), re.compile(r'^Multi TF Name \d+$')]

# 2.1.3 renamed these counters with a " Mod" suffix.
MOD_RENAMES = {n: n + ' Mod' for n in (
    'CH CP', 'CH Tanker', 'CW Co Oilers', 'CW S CPs', 'FR Co Oilers',
    'FR S CPs', 'GE Co Oiler', 'GE S CPs', 'IT Co CP', 'IT Co Oiler',
    'JA Co Oilers', 'JA S CP', 'RU Co Oilers', 'RU Co CPs', 'US Co Oiler',
    'US S CPs')}
BPS_RENAMES = {
    'CWBPs': 'CWBPsDisplay', 'ChinaBPs': 'ChinaBPsDisplay',
    'franceBPs': 'FranceBPsDisplay', 'germanyBPs': 'GermanBPsDisplay',
    'italyBPs': 'ItalyBPsDisplay', 'japanBPs': 'JapanBPsDisplay',
    'usaBPs': 'USABPsDisplay', 'ussrBPs': 'USSRBPsDisplay'}
# One SiF sub whose name lost an underscore in 2.1.3 while its old gpid (4158)
# was not carried over, so neither key matches without this.
MISC_RENAMES = {'GE S SUB TypeVIIC_41_S': 'GE S SUB TypeVIIC41_S'}
NAME_RENAMES = {**MOD_RENAMES, **BPS_RENAMES, **MISC_RENAMES}

# Old per-nation control markers -> Hex Control Marker layer (1-based level in
# the majorhexcontroller Embellishment: CHICOM, CHINAT, CW, France, Germany,
# Italy, Japan, USA, USSR).
HEXCTL_NAME = 'Hex Control Marker'
HEXCTL_LAYER = 'majorhexcontroller'
CONTROL_LEVELS = {
    'CHCOM Control': 1, 'CHICOM Control': 1, 'CHNAT Control': 2,
    'CW Control': 3, 'France Control': 4, 'German Control': 5,
    'Italy Control': 6, 'Japan Control': 7, 'USA Control': 8,
    'USSR Control': 9}

# Pieces on these (original) maps are re-laid in a grid at the top-left of
# their destination map: the FF chart was completely redrawn, and the Minors
# FiF piece lands on a chart it never had a place on.
RELOCATE_MAPS = {'FF Display', 'Minors FiF Chart'}
GRID_ORIGIN, GRID_PITCH, GRID_COLS = (100, 100), 60, 6

SLOT_TAGS = ('PieceSlot', 'CardSlot')


def collect_slots(module_path):
    """-> (allowed gpids, allowed names, name->gpid, all names seen anywhere)."""
    ext_dir = os.path.join(os.path.dirname(os.path.abspath(module_path)),
                           os.path.basename(module_path)[:-5] + '_ext')
    archives = [('MODULE', module_path)]
    if os.path.isdir(ext_dir):
        archives += [(f[:-5], os.path.join(ext_dir, f))
                     for f in sorted(os.listdir(ext_dir)) if f.endswith('.vmdx')]
    gpids, names, name_gpid, everywhere = set(), set(), {}, {}
    for label, path in archives:
        allowed = label == 'MODULE' or label.split('-')[0] in ALLOWED_EXT_NUMS
        with zipfile.ZipFile(path) as z:
            root = ET.fromstring(z.read('buildFile.xml'))
        for el in root.iter():
            if el.tag.split('.')[-1] not in SLOT_TAGS:
                continue
            gpid = (el.get('gpid') or '').strip()
            nm = ''
            f = add_piece_fields(el.text or '')
            if f:
                bp = basic_piece(f[1], f[2])
                if bp:
                    nm = piece_name(bp[0])
            everywhere.setdefault(nm, label)
            if not allowed:
                continue
            if gpid:
                gpids.add(gpid)
            if nm:
                names.add(nm)
                name_gpid.setdefault(nm, gpid)
    return gpids, names, name_gpid, everywhere


def donor_tail(donor_state, donor_toks):
    """The donor's BoardPicker + (filtered) EXT commands, in donor order, with
    the World Maps column-4 fix applied."""
    out = []
    for ds, cs, end in donor_toks:
        content = donor_state[cs:end]
        text = content.decode('utf-8', 'replace')
        if text.startswith('EXT\t'):
            if text.split('\t')[1] in EXT_KEEP:
                out.append(content)
            continue
        pos = text.find('BoardPicker\t')
        if pos < 0 or '\t' in text[:pos] or text[:2] in ('+/', '-/', 'D/', 'M/'):
            continue
        if text[:pos] in DONOR_MAP_EXCLUDES:
            continue
        if cs - ds > 1:
            raise SystemExit(f'donor BoardPicker {text[:40]!r} is nested; refusing')
        if text.startswith('World MapsBoardPicker\t'):
            fixed = text.replace('\tASIA Main Insert\t4\t1',
                                 '\tPACIFIC Main Insert\t4\t1')
            if fixed == text and '\tPACIFIC Main Insert\t4\t1' not in text:
                raise SystemExit('donor World Maps layout has no column-4 main '
                                 'board to fix and no PACIFIC Main Insert')
            content = fixed.encode('utf-8')
        out.append(content)
    if not out:
        raise SystemExit('no BoardPicker commands found in donor')
    return out


def donor_map_names(donor_state, donor_toks):
    return set(board_picker_tokens(donor_state, donor_toks)) - DONOR_MAP_EXCLUDES


def donor_decks(donor_state, donor_toks):
    """The donor's deck AddPiece commands (2.1.3's own decks), verbatim —
    piece id, deck key, position, face-down flag and member list untouched —
    minus decks on excluded maps. Member ids reference donor pieces that are
    not imported; Deck.setState (via Stack) silently skips ids it cannot
    resolve, so the decks come up empty. Verified: no donor piece or member id
    collides with any id in the reference 1.5.93 scenario."""
    out = []
    for ds, cs, end in donor_toks:
        content = donor_state[cs:end]
        text = content.decode('utf-8', 'replace')
        f = add_piece_fields(text)
        if not f:
            continue
        if not f[1].rpartition('\t')[2].startswith('deck'):
            continue
        if f[2].rpartition('\t')[2].split(';')[0] in DONOR_MAP_EXCLUDES:
            continue
        if ESC in content:
            raise SystemExit(f'donor deck {f[0]} contains ESC; refusing')
        out.append(content)
    return out


def split_state(pstate):
    """-> (head incl. trailing tab or '', innermost segment)."""
    head, sep, inner = pstate.rpartition('\t')
    return (head + sep, inner) if sep else ('', pstate)


def rebuild(pid, ptype, pstate):
    return f'+/{pid}/{ptype}/{pstate}'


def grid_position(counters, mp):
    n = counters[mp]
    counters[mp] += 1
    return (GRID_ORIGIN[0] + (n % GRID_COLS) * GRID_PITCH,
            GRID_ORIGIN[1] + (n // GRID_COLS) * GRID_PITCH)


def main(argv):
    args = [a for a in argv if not a.startswith('--')]
    opts = {a.split('=')[0]: (a.split('=', 1)[1] if '=' in a else True)
            for a in argv if a.startswith('--')}
    if len(args) != 4:
        raise SystemExit('usage: migrate_15_to_21.py OLD.vsav DONOR.vsav '
                         'MODULE.vmod OUT.vsav [--jobs=OUT.job] [--csv=OUT.csv] '
                         '[--dry-run]')
    old_path, donor_path, module_path, out_path = args
    if os.path.exists(out_path):
        raise SystemExit(f'{out_path} already exists; refusing to overwrite')
    dry = '--dry-run' in opts

    gpids, names, name_gpid, everywhere = collect_slots(module_path)
    hexctl_gpid = name_gpid.get(HEXCTL_NAME)
    if not hexctl_gpid:
        raise SystemExit(f'{HEXCTL_NAME!r} not found in {module_path}')
    for old, new in NAME_RENAMES.items():
        if new not in name_gpid:
            raise SystemExit(f'rename target {new!r} not in the deluxe set')

    old_state, old_entries, old_deflated = read_vsav(old_path)
    donor_state, donor_entries, _ = read_vsav(donor_path)
    old_toks = split_commands(old_state)
    donor_toks = split_commands(donor_state)
    tail = donor_tail(donor_state, donor_toks)
    decks = donor_decks(donor_state, donor_toks)
    new_maps = donor_map_names(donor_state, donor_toks)

    tally = collections.Counter()
    drops = collections.Counter()          # (reason, map, name) -> count
    job_adds = []                          # (gpid, map, x, y, level)
    grid = collections.Counter()
    rows = []                              # csv: action, map, name, gpid, x, y
    parts = []
    seen_pids = set()                      # every old AddPiece id, kept or not
    deck_ids = set()                       # donor deck ids + their member refs
    for t in decks:
        deck_ids.update(re.findall(rb'\d{10,}', t))

    def keep(delim, content):
        parts.append(delim)
        parts.append(content)

    def drop(reason, mp, nm, gpid='', x='', y=''):
        tally['dropped: ' + reason] += 1
        drops[(reason, mp, nm)] += 1
        rows.append(('drop:' + reason, mp, nm, gpid, x, y))

    end_save_seen = False
    for ds, cs, end in old_toks:
        delim, content = old_state[ds:cs], old_state[cs:end]
        text = content.decode('utf-8', 'replace')

        if text == 'end_save':
            clash = deck_ids & seen_pids
            if clash:
                raise SystemExit('donor deck ids collide with old piece ids: '
                                 + ', '.join(sorted(c.decode() for c in clash)))
            for t in decks:
                keep(ESC, t)
                tally['inserted: donor deck'] += 1
            for t in tail:
                keep(ESC, t)
                tally['inserted: donor layout/EXT'] += 1
            keep(delim, content)
            end_save_seen = True
            continue

        # every old board layout goes; the donor set replaces them
        pos = text.find('BoardPicker\t')
        if (pos < 0 and text.endswith('BoardPicker')):
            pos = len(text) - len('BoardPicker')
        if pos >= 0 and '\t' not in text[:pos] and text[:2] not in ('+/', '-/', 'D/', 'M/'):
            tally['dropped: old BoardPicker'] += 1
            continue
        if text.startswith('EXT\t'):
            tally['dropped: old EXT'] += 1
            continue

        f = add_piece_fields(text)
        if not f:
            keep(delim, content)
            tally['kept: other command'] += 1
            continue
        pid, ptype, pstate = f
        seen_pids.add(pid.encode('ascii', 'replace'))
        inner_type = ptype.rpartition('\t')[2]

        if inner_type.startswith('deck'):
            # No 1.5.93 deck survives: a deck's identity (its internal key,
            # position, id) belongs to the module that defined it, and the old
            # module's force-pool boxes sit where the OLD chart art had them.
            # The donor's decks — the 2.1.3 module's own — are inserted instead
            # (see donor_decks), locations, names and ids preserved verbatim.
            drop('old-module deck', pstate.rpartition('\t')[2].split(';')[0], '')
            continue
        if inner_type.startswith('stack'):
            head, inner = split_state(pstate)
            fields = inner.split(';')
            mp = fields[0]
            new_mp = MAP_RENAMES.get(mp, mp)
            if mp in DISCARD_MAPS or new_mp not in new_maps:
                drop('stack on removed map', mp, '')
                continue
            if mp in RELOCATE_MAPS:
                drop('stack on relaid map', mp, '')
                continue
            if new_mp != mp:
                fields[0] = new_mp
                pstate = head + ';'.join(fields)
                keep(delim, rebuild(pid, ptype, pstate).encode('utf-8'))
                tally['kept: stack (map renamed)'] += 1
            else:
                keep(delim, content)
                tally['kept: stack'] += 1
            continue

        bp = basic_piece(ptype, pstate)
        if bp is None:
            keep(delim, content)
            tally['kept: non-basic piece'] += 1
            continue
        bt, bs = bp
        nm = piece_name(bt)
        sfields = bs.split(';')
        mp = sfields[0]
        x, y = sfields[1], sfields[2]
        new_mp = MAP_RENAMES.get(mp, mp)

        if nm in DROP_NAMES or any(r.match(nm) for r in DROP_NAME_RES):
            drop('discontinued counter', mp, nm, sfields[3], x, y)
            continue
        if mp in DISCARD_MAPS:
            drop('BPs-chart rule', mp, nm, sfields[3], x, y)
            continue
        if new_mp not in new_maps:
            drop('map removed', mp, nm, sfields[3], x, y)
            continue
        if nm in CONTROL_LEVELS:
            job_adds.append((hexctl_gpid, new_mp, x, y, CONTROL_LEVELS[nm]))
            tally['control marker -> job'] += 1
            rows.append(('hex-control-job', new_mp, nm, hexctl_gpid, x, y))
            continue

        renamed = NAME_RENAMES.get(nm)
        if renamed is None and sfields[3] not in gpids and nm not in names:
            owner = everywhere.get(nm)
            drop('only in ' + owner if owner else 'not in 2.1.3', mp, nm,
                 sfields[3], x, y)
            continue

        changed = False
        if renamed is not None:
            tparts = bt.split(';')
            if len(tparts) < 5:
                drop('unsplittable rename', mp, nm, sfields[3], x, y)
                continue
            tparts[4] = renamed
            new_bt = ';'.join(tparts)
            ptype = ptype[:len(ptype) - len(bt)] + new_bt
            sfields[3] = name_gpid[renamed]
            changed = True
            tally['renamed counter'] += 1
        if mp in RELOCATE_MAPS:
            gx, gy = grid_position(grid, new_mp)
            sfields[1], sfields[2] = str(gx), str(gy)
            changed = True
            tally['relocated to grid'] += 1
        if new_mp != mp:
            sfields[0] = new_mp
            changed = True
        if changed:
            head, _ = split_state(pstate)
            pstate = head + ';'.join(sfields)
            keep(delim, rebuild(pid, ptype, pstate).encode('utf-8'))
        else:
            keep(delim, content)
        tally['kept: piece'] += 1

    if not end_save_seen:
        raise SystemExit('no end_save command found; refusing')

    plain = b''.join(parts)
    print(f'{old_path}:')
    for k in sorted(tally):
        print(f'  {tally[k]:6d}  {k}')
    print(f'  plaintext {len(old_state)} -> {len(plain)} bytes; '
          f'{len(job_adds)} control markers to add via --jobs')

    top = collections.Counter()
    for (reason, mp, nm), n in drops.items():
        top[(reason, nm or '(container)')] += n
    print('\n  dropped, by reason and name (top 30):')
    for (reason, nm), n in top.most_common(30):
        print(f'    {n:5d}  {reason}: {nm}')

    if opts.get('--csv') and not dry:
        with open(opts['--csv'], 'w', newline='') as fh:
            w = csv.writer(fh)
            w.writerow(('action', 'map', 'name', 'gpid', 'x', 'y'))
            w.writerows(rows)
        print(f'\n  manifest -> {opts["--csv"]}')

    if dry:
        print('\n  dry run: nothing written')
        return

    jobs = opts.get('--jobs')
    if jobs is True or (jobs is None and job_adds):
        jobs = out_path[:-5] + '.hexctl.job' if out_path.endswith('.vsav') \
            else out_path + '.hexctl.job'
    if job_adds and jobs:
        with open(jobs, 'w', encoding='utf-8') as fh:
            fh.write(f'module={os.path.abspath(module_path)}\n')
            fh.write(f'save={os.path.abspath(out_path)}\n')
            for gpid, mp, x, y, level in job_adds:
                fh.write(f'add={gpid}\t{mp}\t{x}\t{y}\t'
                         f'layer:{HEXCTL_LAYER}={level}\n')
        print(f'  control-marker job -> {jobs}')

    entries = dict(old_entries)
    entries[MODULE_DATA] = donor_entries[MODULE_DATA]
    import random
    key = random.randrange(256)
    tmp = out_path + '.tmp'
    with zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as z:
        for name in (SAVED_GAME, SAVE_DATA, MODULE_DATA):
            data, when = entries[name]
            if name == SAVED_GAME:
                data = obfuscate(plain, key, old_deflated)
            z.writestr(zipfile.ZipInfo(name, date_time=when), data,
                       zipfile.ZIP_DEFLATED)
    os.replace(tmp, out_path)
    print(f'  wrote {out_path}')


if __name__ == '__main__':
    main(sys.argv[1:])
