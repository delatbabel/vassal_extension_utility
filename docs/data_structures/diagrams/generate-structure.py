#!/usr/bin/env python3
"""Generate the three container-structure diagrams for docs/data_structures.

Three horizontal bands, each a boundary: the ZIP entries, the decoded structure
of the main entry, and the record level inside that.  Column 1 is the spine --
the entry that decodes into the next band -- so both band arrows are vertical.
"""
import json, os, sys

COLS = [40, 290, 540, 790]     # 220-wide nodes, 30px apart
ROWS = [110, 270, 430]         # 76-high nodes, 84px apart
W, H = 220, 76

T = {}


def diagram(stem, title, bands, arrows, cards):
    T[stem] = dict(title=title, bands=bands, arrows=arrows, cards=cards)


diagram('vsav-structure', '.vsav — Container Structure',
        [('1 · ZIP container — .vsav',
          [('savedGame', 'obfuscated command log', 'database'),
           ('savedata', "this save's own metadata", 'database'),
           ('moduledata', 'the module it came from', 'database')]),
         ('2 · savedGame — the obfuscation envelope',
          [('plaintext log', 'GameState.saveString()', 'backend'),
           ('magic header', 'VOBS · !VCSK · !VCSZ', 'external'),
           ('XOR key', '1 raw byte, or 2 hex digits', 'security'),
           ('deflate', '!VCSZ only', 'backend')]),
         ('3 · the command log — ESC-delimited records',
          [('command token', 'one record per 0x1B', 'backend'),
           ('+/ AddPiece', 'id / type / state', 'backend'),
           ('EXT · BoardPicker', 'tab-delimited commands', 'backend'),
           ('begin_save … end_save', 'brackets every log', 'backend')])],
        [(0, 0, 1, 0, 'XOR with the key'),
         (1, 0, 2, 0, 'split at every ESC')],
        [{'dot': 'cyan', 'title': 'Three levels, three delimiters',
          'items': ['The ZIP layer is ordinary: three stored entries, no nesting',
                    'The envelope is a header, a key and the plaintext XOR-ed with it',
                    'The log is one long line; its record boundaries are 0x1B bytes, not newlines']},
         {'dot': 'emerald', 'title': 'Why the format is preserved',
          'items': ['VOBS (3.8+) XORs raw so the ZIP deflate can still compress it',
                    '!VCSK (through 3.7.x) hex-encodes, doubling the payload into 16 symbols',
                    '!VCSZ deflates before the hex — a pre-release form that never shipped but is still read']}])

diagram('vmod-structure', '.vmod — Container Structure',
        [('1 · ZIP container — .vmod',
          [('buildFile.xml', 'the component tree', 'database'),
           ('moduledata', 'name, version, VassalVersion', 'database'),
           ('images/', 'every referenced asset', 'database'),
           ('*.vsav at the root', 'PredefinedSetup files', 'database')]),
         ('2 · buildFile.xml — the component tree',
          [('GameModule', 'the root, one per module', 'backend'),
           ('Map · PieceWindow', 'top-level components', 'backend'),
           ('PrototypesContainer', 'shared trait sets', 'backend'),
           ('PredefinedSetup', 'file= names a root entry', 'backend')]),
         ('3 · PieceSlot — one counter definition',
          [('PieceSlot · CardSlot', 'at any depth in the tree', 'backend'),
           ('gpid attribute', 'the definition identity', 'backend'),
           ('element text', '+/null/<type>/<state>', 'backend'),
           ('images in that text', 'not in any attribute', 'backend')])],
        [(0, 0, 1, 0, 'parses to'),
         (1, 0, 2, 0, 'contains, at any depth')],
        [{'dot': 'cyan', 'title': 'The tree is the class hierarchy',
          'items': ['Every XML tag is a fully-qualified VASSAL class name',
                    'The nesting mirrors the Java object tree built at run time',
                    'A module must never contain a ModuleExtension.ExtensionElement']},
         {'dot': 'amber', 'title': 'Two things live outside the XML',
          'items': ['A piece definition is element text, so image names are found by scanning it',
                    "A PredefinedSetup's file= is the literal name of a root-level ZIP entry",
                    'Image entry mtimes are the tile cache key — never restamp them']}])

diagram('vmdx-structure', '.vmdx — Container Structure',
        [('1 · ZIP container — .vmdx',
          [('buildFile.xml', 'the graft list', 'database'),
           ('extensiondata', 'version, universal, dateSaved', 'database'),
           ('moduledata', "a copy of the parent module's", 'database'),
           ('images/', "the extension's own assets", 'database')]),
         ('2 · buildFile.xml — ModuleExtension',
          [('ModuleExtension', 'the root, eight attributes', 'backend'),
           ('ExtensionElement', 'one wrapper per component', 'backend'),
           ('the component', "the wrapper's only child", 'backend'),
           ('nextPieceSlotId', 'the id allocator', 'backend')]),
         ('3 · target= — a path into the module',
          [('target attribute', 'one per ExtensionElement', 'backend'),
           ('className:name', 'one path segment', 'backend'),
           ("joined with '/'", 'ComponentPathBuilder', 'backend'),
           ('empty target', 'grafts at the module root', 'backend')])],
        [(0, 0, 1, 0, 'parses to'),
         (1, 0, 2, 0, 'each wrapper carries one')],
        [{'dot': 'cyan', 'title': 'An extension holds nothing directly',
          'items': ['Every component sits inside an ExtensionElement naming where it grafts',
                    'One wrapper per component — build() reads only its first child',
                    'A wrapper left empty crashes the module launch, not just the extension']},
         {'dot': 'emerald', 'title': 'Identity',
          'items': ['extensionId is the last 3 characters of a UUID, generated once',
                    'Slots created in an extension get ids of the form <extensionId>:<n>',
                    'A slot copied in from elsewhere keeps its plain numeric id — the source of GPID clashes']}])


def build(spec):
    comps, bounds, conns = [], [], []
    for bi, (blabel, nodes) in enumerate(spec['bands']):
        ids = []
        for ci, (label, sub, typ) in enumerate(nodes):
            nid = 'b%dc%d' % (bi, ci)
            ids.append(nid)
            comps.append({'id': nid, 'type': typ, 'label': label, 'sublabel': sub,
                          'pos': [COLS[ci], ROWS[bi]], 'size': [W, H]})
        bounds.append({'kind': 'region', 'label': blabel, 'wraps': ids})
    for i, (b1, c1, b2, c2, label) in enumerate(spec['arrows']):
        conns.append({'id': 'a%d' % i, 'from': 'b%dc%d' % (b1, c1), 'to': 'b%dc%d' % (b2, c2),
                      'label': label, 'variant': 'emphasis', 'labelDy': 62})
    return {'schema_version': 1, 'diagram_type': 'architecture',
            'meta': {'title': spec['title'], 'locale': 'en', 'quality_profile': 'showcase'},
            'components': comps, 'boundaries': bounds, 'connections': conns,
            'cards': spec['cards']}


out = sys.argv[1]
os.makedirs(out, exist_ok=True)
for stem, spec in T.items():
    p = os.path.join(out, stem + '.architecture.json')
    json.dump(build(spec), open(p, 'w'), indent=2, ensure_ascii=False)
    print(p)
