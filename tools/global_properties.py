#!/usr/bin/env python3
"""Report — and optionally rewrite — the Global Property values a save carries.

A module's Global Properties are game state: `GlobalProperty.getRestoreCommand()`
writes one `GlobalProperty\\t;<name>;<value>;<container>` command per property
into the saved game, and loading the save restores that value. They are **not**
tied to any piece, so a counter that set a property leaves its value behind when
it is deleted, moved to a map that no longer exists, or reset by a migration.

That makes a stale property invisible to every other tool here — nothing is
wrong with any piece, so `remove_offmap_pieces.py` and `missing_counters.py`
have nothing to report, and Refresh Counters rebuilds pieces, never properties.
The symptom is a chart or overlay that keeps displaying a number no counter on
the table accounts for: the WiF `German BP Overlay` reads its BUILD POINTS /
TRADE figure from a Calculated Property over `Germantradebps`, which the
`MajP Lending Strip` counters set through `Set Global Property` traits. Zero
every strip and the figure stays where the last loan left it, because the loan
lives in the property, not in the strip.

## Usage

    tools/global_properties.py SAVE.vsav [SAVE.vsav...]
                               [--grep=SUBSTR]... [--changed] [--module=MODULE.vmod]
                               [--set=NAME=VALUE]... [--reset=SUBSTR]...
                               [--apply] [--no-backup] [--csv=OUT.csv]

Reports by default and writes only with `--apply`, like the other tools here.

`--grep` restricts the report to properties whose name contains SUBSTR
(repeatable, case-insensitive). `--module` reads each property's `initialValue`
from the module and its active extensions, so the report shows the default
beside the saved value; `--changed` then lists only the properties that differ
from it — the ones a game actually altered, which is where stale state hides.

`--set=NAME=VALUE` sets one property by **exact** name. `--reset=SUBSTR` sets
every property whose name contains SUBSTR back to the module's `initialValue`
(so it needs `--module`), which is the way to undo a ledger a departed counter
left behind. Both are repeatable, and a property the save does not record is
reported rather than added — VASSAL would restore the module default for it
anyway.

`--csv=OUT.csv` writes the reported rows: `scenario, property, value, default,
container`.

Every other command in the log is copied verbatim, exactly as in
`swap_maps.py`; only the changed `GlobalProperty` commands are re-encoded, with
`SequenceEncoder`'s own escaping.
"""
import csv, glob, os, sys, zipfile
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from swap_maps import read_vsav, split_commands, write_vsav
from remove_placemark_carriers import backup_path

PREFIX = b'GlobalProperty\t'


def seq_decode(value, delim=';'):
    """Faithful port of SequenceEncoder.Decoder: split on unescaped `delim`.

    A `\\` before the delimiter escapes it, and a token wholly enclosed in single
    quotes is unquoted (how the encoder protects a value starting with `\\`).
    """
    toks, buf, i, start = [], [], 0, 0
    while i < len(value):
        if value[i] == delim:
            if i > start and value[i - 1] == '\\':
                buf.append(value[start:i - 1])
                start = i                      # keep the delimiter itself
            else:
                buf.append(value[start:i])
                toks.append(''.join(buf))
                buf, start = [], i + 1
        i += 1
    buf.append(value[start:])
    toks.append(''.join(buf))
    return [t[1:-1] if len(t) > 1 and t[0] == "'" and t[-1] == "'" else t
            for t in toks]


def seq_encode(toks, delim=';'):
    """Faithful port of SequenceEncoder.append() over a whole token list."""
    out = []
    for t in toks:
        if not t:
            out.append('')
            continue
        escaped = t.replace(delim, '\\' + delim)
        if t[0] == '\\' or (len(t) > 1 and t[0] == "'" and t[-1] == "'"):
            escaped = "'" + escaped + "'"
        out.append(escaped)
    return delim.join(out)


def module_defaults(module_path):
    """-> {property name: initialValue} over the module and its active extensions."""
    defaults = {}
    ext_dir = os.path.join(os.path.dirname(os.path.abspath(module_path)),
                           os.path.basename(module_path)[:-5] + '_ext')
    archives = [module_path]
    archives += sorted(glob.glob(os.path.join(ext_dir, '*.vmdx')))
    for path in archives:
        with zipfile.ZipFile(path) as z:
            root = ET.fromstring(z.read('buildFile.xml'))
        for el in root.iter():
            if el.tag.split('.')[-1] == 'GlobalProperty' and el.get('name'):
                defaults.setdefault(el.get('name'), el.get('initialValue') or '')
    return defaults


def properties(state, toks):
    """-> [(token index, name, value, container)] for every GlobalProperty command."""
    out = []
    for idx, (ds, cs, end) in enumerate(toks):
        if not state[cs:end].startswith(PREFIX):
            continue
        f = seq_decode(state[cs:end].decode('utf-8', 'replace'))
        out.append((idx, f[1] if len(f) > 1 else '',
                    f[2] if len(f) > 2 else '',
                    f[3] if len(f) > 3 else ''))
    return out


def main(argv):
    flags = {a for a in argv if a.startswith('--') and '=' not in a}
    grep, resets, sets, module, csv_out = [], [], {}, None, None
    for a in argv:
        if a.startswith('--grep='): grep.append(a.split('=', 1)[1].lower())
        elif a.startswith('--reset='): resets.append(a.split('=', 1)[1].lower())
        elif a.startswith('--module='): module = a.split('=', 1)[1]
        elif a.startswith('--csv='): csv_out = a.split('=', 1)[1]
        elif a.startswith('--set='):
            name, _, value = a.split('=', 1)[1].partition('=')
            sets[name] = value
    saves = [a for a in argv if not a.startswith('--')]
    if not saves:
        raise SystemExit(__doc__.strip().split('## Usage')[1].strip())
    if resets and not module:
        raise SystemExit('--reset needs --module=MODULE.vmod to know the default value')
    if '--changed' in flags and not module:
        raise SystemExit('--changed needs --module=MODULE.vmod to compare against')

    defaults = module_defaults(module) if module else {}
    rows, grand = [], 0
    for path in saves:
        state, entries, fmt = read_vsav(path)
        toks = split_commands(state)
        props = properties(state, toks)
        by_name = {name: (idx, value) for idx, name, value, _ in props}

        edits = {}          # token index -> (name, old, new)
        for name, new in sets.items():
            if name not in by_name:
                print('%s: no GlobalProperty command for %r — skipped'
                      % (os.path.basename(path), name))
                continue
            idx, old = by_name[name]
            if old != new:
                edits[idx] = (name, old, new)
        for idx, name, value, _ in props:
            low = name.lower()
            if not any(r in low for r in resets):
                continue
            if name not in defaults:
                print('%s: %s is not defined by the module — skipped'
                      % (os.path.basename(path), name))
                continue
            if value != defaults[name] and idx not in edits:
                edits[idx] = (name, value, defaults[name])

        shown = 0
        print('\n%s: %d Global Property value(s)' % (os.path.basename(path), len(props)))
        for idx, name, value, container in props:
            low = name.lower()
            if grep and not any(g in low for g in grep):
                continue
            default = defaults.get(name)
            if '--changed' in flags and (default is None or value == default):
                continue
            mark = ''
            if idx in edits:
                mark = '  -> %r' % (edits[idx][2],)
            elif default is not None and value != default:
                mark = '  (default %r)' % (default,)
            print('    %-36s %-12r%s' % (name, value, mark))
            shown += 1
            rows.append({'scenario': os.path.basename(path), 'property': name,
                         'value': value, 'default': '' if default is None else default,
                         'container': container})
        if grep or '--changed' in flags:
            print('    %d shown' % shown)
        grand += len(edits)
        if not edits:
            continue
        print('    %d change(s)%s' % (len(edits),
                                      '' if '--apply' in flags else ' — pass --apply to write'))
        if '--apply' not in flags:
            continue

        if '--no-backup' not in flags:
            backup = backup_path(path)
            with open(path, 'rb') as src, open(backup, 'wb') as dst:
                dst.write(src.read())
            print('    backed up as %s' % os.path.basename(backup))

        parts = []
        for idx, (ds, cs, end) in enumerate(toks):
            parts.append(state[ds:cs])                     # delimiter, unchanged
            if idx not in edits:
                parts.append(state[cs:end])                # content, verbatim
                continue
            f = seq_decode(state[cs:end].decode('utf-8', 'replace'))
            while len(f) < 3:
                f.append('')
            f[2] = edits[idx][2]
            parts.append(seq_encode(f).encode('utf-8'))
        write_vsav(path, b''.join(parts), entries, fmt)
        print('    wrote %s' % os.path.basename(path))

    if csv_out:
        cols = ['scenario', 'property', 'value', 'default', 'container']
        with open(csv_out, 'w', newline='', encoding='utf-8') as fh:
            w = csv.DictWriter(fh, fieldnames=cols)
            w.writeheader()
            for row in rows:
                w.writerow(row)
        print('\nwrote %s: %d row(s)' % (csv_out, len(rows)))

    print('\n%d property change(s) across %d file(s)%s'
          % (grand, len(saves),
             '' if '--apply' in flags else ' — REPORT ONLY, pass --apply to write'))


if __name__ == '__main__':
    main(sys.argv[1:])
