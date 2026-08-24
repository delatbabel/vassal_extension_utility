#!/usr/bin/env python3
"""Give an extension's duplicated Piece Ids (GPIDs) fresh, unused numbers.

VASSAL refuses to run Refresh Counters while any two components share a GPID:
`GameRefresher.execute()` builds a `GpIdChecker` over every PieceSlot, and if
`hasErrors()` it logs "Unable to run Refresh, module was saved with older vassal
version" and returns without refreshing anything. That message is misleading —
`GpIdChecker.testGpId()` flags a GPID that is empty, non-numeric, or **already
seen**, and never looks at the VASSAL version.

Duplicates across extensions are easy to create: extensions generate fresh ids as
`<extensionId>:<n>`, but a slot copied from the module or another extension keeps
its plain numeric id, and GpIdChecker keys on the raw value when extensions are
loaded. Two extensions can then claim the same number.

This renumbers the *target* extension's clashing slots, leaving every other
archive alone. Only `buildFile.xml` is rewritten, and only the specific
`gpid="..."` attributes and the matching id embedded in each slot's own piece
definition. Every other ZIP entry is copied byte-for-byte **with its original
modification time**, because VASSAL decides whether a cached image tile is stale
purely by comparing mtimes — restamping them forces a needless re-tile of every
board image (see docs/image-display-and-tiling.md).

## Never leave a spare copy in the extensions folder

`ExtensionsManager`'s file filter is just `!isHidden() && !isDirectory()`: VASSAL
loads **every** file in `<module>_ext/` whose metadata parses as an extension,
whatever it is called. A `foo.vmdx.bak` or `Copy of foo.vmdx` sitting there is
loaded as a real extension — which for a renumbering like this re-creates every
duplicate GPID it just removed. Backups therefore go in `<module>_ext/backups/`;
directories are skipped by that filter, and only `inactive` is also scanned.

## Before you renumber

A GPID is how a saved game refers to a piece definition, so renumbering a slot
orphans any piece in any save that points at it. Check first:

    tools/renumber_gpids.py EXT.vmdx --dry-run          # what would change
    # then grep the saves for the old numbers before committing to it

Pieces whose GPID no longer resolves can still be matched by name (Refresh
Counters' "Use counter names" option), but by GPID they are lost. Renumber the
side of a clash that no saved game references.

## Usage

    tools/renumber_gpids.py EXT.vmdx [--start N] [--dry-run] [--no-backup]

The module and its sibling extensions are found from EXT.vmdx's own location: it
is expected to sit in `<module>_ext/`, alongside `<module>.vmod` in the parent
directory. New ids are allocated consecutively from `--start`, skipping anything
already in use anywhere; `--start` should sit clear of every archive's
`nextPieceSlotId`, or the next piece someone adds in the editor may collide all
over again. The extension's own `nextPieceSlotId` is advanced past the block.
"""
import os, re, shutil, sys, zipfile
import xml.etree.ElementTree as ET

BUILD_FILE = 'buildFile.xml'
SLOT_TAGS = ('PieceSlot', 'CardSlot')


def slot_gpids(path):
    """-> {gpid: [entryName, ...]} for one archive."""
    out = {}
    with zipfile.ZipFile(path) as z:
        root = ET.fromstring(z.read(BUILD_FILE))
    for el in root.iter():
        if el.tag.split('.')[-1] in SLOT_TAGS:
            gpid = (el.get('gpid') or '').strip()
            if gpid:
                out.setdefault(gpid, []).append(el.get('entryName'))
    return out


def sibling_archives(ext_path):
    """The module and every other extension sharing this extension's folder."""
    ext_dir = os.path.dirname(os.path.abspath(ext_path))
    parent = os.path.dirname(ext_dir)
    found = []
    for name in sorted(os.listdir(parent)):
        if name.lower().endswith('.vmod'):
            found.append(os.path.join(parent, name))
    for directory in (ext_dir, os.path.join(ext_dir, 'inactive')):
        if not os.path.isdir(directory):
            continue
        for name in sorted(os.listdir(directory)):
            full = os.path.join(directory, name)
            if name.lower().endswith('.vmdx') and not os.path.samefile(full, ext_path):
                found.append(full)
    return found


def rewrite(ext_path, mapping, next_slot_id, out_path):
    """Copy the archive, substituting the renumbered buildFile.xml."""
    with zipfile.ZipFile(ext_path) as z:
        xml = z.read(BUILD_FILE).decode('utf-8')
        infos = z.infolist()

        stateless = []
        for old, new in mapping.items():
            # The id lives in the slot's gpid attribute, and usually also inside
            # that slot's own BasicPiece state. The state copy is optional: some
            # slots carry an empty field there (`;0;0;;`) because VASSAL stamps
            # the attribute onto the piece at creation time
            # (PieceSlot.getPiece() sets PIECE_ID from getGpId()). What must hold
            # is that the number appears nowhere else — otherwise it is in use
            # somewhere we do not understand, and we refuse rather than corrupt
            # the file.
            bare = len(re.findall(r'(?<![0-9])' + old + r'(?![0-9])', xml))
            attr = xml.count('gpid="%s"' % old)
            state = len(re.findall(r';0;0;' + old + r';', xml))
            if attr != 1 or state > 1 or bare != attr + state:
                raise SystemExit(
                    'gpid %s appears %d time(s) (attribute %d, piece state %d); '
                    'expected attribute 1 plus at most 1 piece state and nothing '
                    'else — refusing' % (old, bare, attr, state))
            if state == 0:
                stateless.append(old)
            xml = xml.replace('gpid="%s"' % old, 'gpid="%s"' % new)
            xml = re.sub(r';0;0;' + old + r';', ';0;0;%s;' % new, xml)
        if stateless:
            print('  note: %d slot(s) carried no gpid inside their own definition '
                  '(harmless — VASSAL stamps it from the attribute): %s'
                  % (len(stateless), ', '.join(stateless)))

        if next_slot_id is not None:
            root_attr = re.search(r'nextPieceSlotId="(\d+)"', xml)
            if root_attr and int(root_attr.group(1)) < next_slot_id:
                xml = xml.replace(root_attr.group(0),
                                  'nextPieceSlotId="%d"' % next_slot_id, 1)

        tmp = out_path + '.tmp'
        with zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as out:
            for info in infos:
                data = xml.encode('utf-8') if info.filename == BUILD_FILE \
                    else z.read(info.filename)
                # Preserve the entry's own mtime; a fresh ZipInfo would stamp
                # "now" and invalidate VASSAL's whole tile cache.
                keep = zipfile.ZipInfo(info.filename, date_time=info.date_time)
                keep.compress_type = info.compress_type
                keep.external_attr = info.external_attr
                out.writestr(keep, data)
    os.replace(tmp, out_path)


def main(argv):
    args = [a for a in argv if not a.startswith('--')]
    flags = {a for a in argv if a.startswith('--')}
    start = 16000
    for a in argv:
        if a.startswith('--start='):
            start = int(a.split('=', 1)[1])
    if len(args) != 1:
        raise SystemExit(__doc__.strip().splitlines()[-0] +
                         '\nusage: renumber_gpids.py EXT.vmdx [--start=N] '
                         '[--dry-run] [--no-backup]')
    ext = args[0]

    mine = slot_gpids(ext)
    others = {}
    for archive in sibling_archives(ext):
        for gpid, names in slot_gpids(archive).items():
            others.setdefault(gpid, []).append(
                (os.path.basename(archive), names))
    used = set(mine) | set(others)

    clashes = sorted((g for g in mine if g in others),
                     key=lambda g: int(g) if g.isdigit() else -1)
    if not clashes:
        print('%s: no GPIDs clash with the module or its other extensions.'
              % os.path.basename(ext))
        return

    # Allocate consecutively from --start, skipping anything already in use.
    mapping, nxt = {}, start
    for old in clashes:
        while str(nxt) in used:
            nxt += 1
        mapping[old] = str(nxt)
        used.add(str(nxt))
        nxt += 1

    print('%s: renumbering %d clashing GPID(s)\n' % (os.path.basename(ext), len(clashes)))
    for old in clashes:
        elsewhere = '; '.join('%s:%s' % (a, ','.join(n)) for a, n in others[old])
        print('  %-6s -> %-6s  %-24s (also %s)'
              % (old, mapping[old], ','.join(mine[old]), elsewhere))

    next_slot_id = max(int(v) for v in mapping.values()) + 1
    print('\n  nextPieceSlotId -> %d' % next_slot_id)

    if '--dry-run' in flags:
        print('\ndry run — nothing written')
        return

    if '--no-backup' not in flags:
        # The backup must go in a SUBDIRECTORY, not beside the extension.
        # ExtensionsManager's filter is only `!isHidden() && !isDirectory()`, so
        # VASSAL loads *every* file in the extensions folder whose metadata
        # parses — the name and suffix are never looked at. A "23-DoD-III.vmdx.bak"
        # left in place is therefore loaded as a second extension, re-creating
        # every duplicate GPID this tool just removed. Directories are skipped,
        # and only `inactive` is scanned as well, so `backups/` is safe.
        backup_dir = os.path.join(os.path.dirname(os.path.abspath(ext)), 'backups')
        os.makedirs(backup_dir, exist_ok=True)
        backup = os.path.join(backup_dir, os.path.basename(ext))
        if os.path.exists(backup):
            raise SystemExit('%s already exists; move it aside first' % backup)
        shutil.copy2(ext, backup)
        print('\nbacked up as backups/%s' % os.path.basename(backup))

    rewrite(ext, mapping, next_slot_id, ext)
    print('wrote %s' % os.path.basename(ext))


if __name__ == '__main__':
    main(sys.argv[1:])
