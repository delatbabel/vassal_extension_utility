#!/usr/bin/env python3
"""Delete piece slots from an extension by GPID, and optionally bump its version.

For clearing a *duplicated* counter — the same component left behind in two
archives — which is the case renumbering cannot fix: giving the two copies
distinct GPIDs would leave two identical counters in the palette. One copy has to
go. When both copies share the same GPID *and* the same definition, deleting
either is safe for existing saved games, because every piece pointing at that
GPID still matches the survivor.

## The empty-wrapper trap

An extension never holds a component directly: each sits inside a
`VASSAL.build.module.ExtensionElement` naming where in the module's tree it
grafts. Deleting the component and leaving the wrapper produces an
`ExtensionElement` with nothing in it — which is XML-valid but makes VASSAL
**abort the whole module launch** with a NullPointerException, because
`ExtensionElement.build()` leaves its `extension` field null and `addTo()` then
dereferences it (see docs/vassal-empty-extensionelement-crash.md). So a wrapper
left empty by a deletion is removed along with the slot. A wrapper holding other
components is kept.

## What is preserved

Only `buildFile.xml` is rewritten (plus `extensiondata` if the version is
bumped). Every other ZIP entry is copied byte-for-byte **with its original
modification time**, because VASSAL judges whether a cached image tile is stale
purely by comparing mtimes — restamping them forces a needless re-tile of every
board image (see docs/image-display-and-tiling.md).

A version bump updates the version in **both** places VASSAL keeps it: the
`version` attribute on the `ModuleExtension` root and `<version>` in the separate
`extensiondata` metadata entry.

## Usage

    tools/drop_slots.py EXT.vmdx GPID [GPID...] [--version=X.Y.Z]
                        [--dry-run] [--no-backup]

Backups go in `<module>_ext/backups/`, never beside the extension: VASSAL loads
*every* file in the extensions folder whose metadata parses, whatever it is
called, so a copy left there would be loaded as a second extension.
"""
import os, re, shutil, sys, zipfile

BUILD_FILE = 'buildFile.xml'
EXT_DATA = 'extensiondata'
SLOT_TAGS = ('VASSAL.build.widget.PieceSlot', 'VASSAL.build.widget.CardSlot')
WRAPPER = 'VASSAL.build.module.ExtensionElement'


def element_span(xml, start):
    """The [start, end) span of the element whose start tag begins at `start`."""
    tag_end = xml.index('>', start)
    name = re.match(r'<([\w.]+)', xml[start:]).group(1)
    if xml[tag_end - 1] == '/':                     # self-closing
        return start, tag_end + 1
    close = '</%s>' % name
    depth, i = 1, tag_end + 1
    while depth:
        nxt_open = xml.find('<%s' % name, i)
        nxt_close = xml.find(close, i)
        if nxt_close < 0:
            raise SystemExit('unterminated <%s> at offset %d' % (name, start))
        if 0 <= nxt_open < nxt_close:
            depth += 1
            i = xml.index('>', nxt_open) + 1
        else:
            depth -= 1
            i = nxt_close + len(close)
    return start, i


def find_slot(xml, gpid):
    """-> (start, end) of the single slot element carrying this gpid."""
    hits = []
    for m in re.finditer(r'<(%s)\b[^>]*?\bgpid="%s"' % ('|'.join(
            t.replace('.', r'\.') for t in SLOT_TAGS), re.escape(gpid)), xml):
        hits.append(m.start())
    if len(hits) != 1:
        raise SystemExit('gpid %s matches %d slot(s) in this extension; expected 1'
                         % (gpid, len(hits)))
    return element_span(xml, hits[0])


def enclosing_wrapper(xml, pos):
    """-> (start, end) of the ExtensionElement containing `pos`, or None."""
    start = xml.rfind('<%s' % WRAPPER, 0, pos)
    if start < 0:
        return None
    span = element_span(xml, start)
    return span if span[0] < pos < span[1] else None


def wrapper_emptied_by(xml, wrapper, slot):
    """True if removing `slot` leaves `wrapper` with no element children at all.

    Such a wrapper must go too: VASSAL aborts the module launch on an
    ExtensionElement containing no component.
    """
    ws, we = wrapper
    content_start = xml.index('>', ws) + 1
    content_end = xml.rfind('</', ws, we)
    content = xml[content_start:content_end]
    rel_s, rel_e = slot[0] - content_start, slot[1] - content_start
    remaining = content[:rel_s] + content[rel_e:]
    return re.search(r'<[\w.]', remaining) is None


def strip(xml, start, end):
    """Remove [start, end) plus the blank line it leaves behind."""
    line_start = xml.rfind('\n', 0, start) + 1
    if xml[line_start:start].strip() == '':
        start = line_start
    if xml[end:].startswith('\n'):
        end += 1
    return xml[:start] + xml[end:]


def main(argv):
    flags = {a for a in argv if a.startswith('--')}
    args = [a for a in argv if not a.startswith('--')]
    new_version = None
    for a in argv:
        if a.startswith('--version='):
            new_version = a.split('=', 1)[1]
    if len(args) < 2:
        raise SystemExit('usage: drop_slots.py EXT.vmdx GPID [GPID...] '
                         '[--version=X.Y.Z] [--dry-run] [--no-backup]')
    ext, gpids = args[0], args[1:]

    with zipfile.ZipFile(ext) as z:
        xml = z.read(BUILD_FILE).decode('utf-8')
        extdata = z.read(EXT_DATA).decode('utf-8') if EXT_DATA in z.namelist() else None
        infos = z.infolist()

    # Resolve every span first, so a bad gpid aborts before anything is edited.
    plan = []
    for gpid in gpids:
        s, e = find_slot(xml, gpid)
        name = re.search(r'entryName="([^"]*)"', xml[s:e])
        wrapper = enclosing_wrapper(xml, s)
        drop_wrapper = bool(wrapper) and wrapper_emptied_by(xml, wrapper, (s, e))
        plan.append((gpid, name.group(1) if name else '?', s, e, wrapper, drop_wrapper))

    print('%s: dropping %d slot(s)\n' % (os.path.basename(ext), len(plan)))
    for gpid, name, s, e, wrapper, drop_wrapper in plan:
        note = 'slot + its now-empty ExtensionElement' if drop_wrapper else \
               ('slot only (wrapper keeps other components)' if wrapper else 'slot only')
        print('  gpid %-6s %-28s %s' % (gpid, name, note))
    if new_version:
        root = re.search(r'<VASSAL\.build\.module\.ModuleExtension\b[^>]*?'
                         r'\bversion="([^"]*)"', xml)
        print('\n  version %s -> %s (ModuleExtension root + %s)'
              % (root.group(1) if root else '?', new_version, EXT_DATA))

    # Apply from the end backwards so earlier offsets stay valid.
    for gpid, name, s, e, wrapper, drop_wrapper in sorted(plan, key=lambda p: -p[2]):
        xml = strip(xml, *(wrapper if drop_wrapper else (s, e)))

    if new_version:
        xml = re.sub(r'(<VASSAL\.build\.module\.ModuleExtension\b[^>]*?)version="[^"]*"',
                     r'\1version="%s"' % new_version, xml, count=1)
        if extdata:
            extdata = re.sub(r'<version>[^<]*</version>',
                             '<version>%s</version>' % new_version, extdata, count=1)

    if '--dry-run' in flags:
        print('\ndry run — nothing written')
        return

    if '--no-backup' not in flags:
        backup_dir = os.path.join(os.path.dirname(os.path.abspath(ext)), 'backups')
        os.makedirs(backup_dir, exist_ok=True)
        backup = os.path.join(backup_dir, os.path.basename(ext))
        if os.path.exists(backup):
            raise SystemExit('%s already exists; move it aside first' % backup)
        shutil.copy2(ext, backup)
        print('\nbacked up as backups/%s' % os.path.basename(backup))

    tmp = ext + '.tmp'
    with zipfile.ZipFile(ext) as z, zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as out:
        for info in infos:
            if info.filename == BUILD_FILE:
                data = xml.encode('utf-8')
            elif info.filename == EXT_DATA and extdata is not None:
                data = extdata.encode('utf-8')
            else:
                data = z.read(info.filename)
            keep = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            keep.compress_type = info.compress_type
            keep.external_attr = info.external_attr
            out.writestr(keep, data)
    os.replace(tmp, ext)
    print('wrote %s' % os.path.basename(ext))


if __name__ == '__main__':
    main(sys.argv[1:])
