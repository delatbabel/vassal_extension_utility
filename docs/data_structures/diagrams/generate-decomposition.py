#!/usr/bin/env python3
"""Generate the two decomposition diagrams for docs/data_structures.

Same 8-node grid as docs/tools/diagrams: row 0 is the thing being decomposed,
row 1 the parallel structure beside it, joining at stages 3 and 4.
"""
import json, os, sys

T = {}


def tool(stem, title, stages, a, b, fa, fb, j2, j3, cards):
    T[stem] = dict(title=title, stages=stages, a=a, b=b, fa=fa, fb=fb, j2=j2, j3=j3, cards=cards)


tool('piece-record', 'A game piece in a saved game — Record Layout',
     ['The command', 'Two chains', 'Trait nesting', 'The leaf'],
     [('+/ AddPiece', 'one ESC-delimited token', 'database'),
      ('type', 'the static definition', 'backend'),
      ('outer decorator', 'a trait, tab-joined', 'backend'),
      ('piece;;;img;name', 'the BasicPiece type', 'backend')],
     [('piece id', 'field 1, a timestamp', 'database'),
      ('state', 'the mutable half', 'backend'),
      ('outer trait state', 'same shape, same order', 'backend'),
      ('map;x;y;gpid;n;…', 'the BasicPiece state', 'backend')],
     ['split on / ', 'read left to right', 'after the last tab'],
     ['split on /', 'read left to right'],
     'field for field', 'gpid is field 4',
     [{'dot': 'cyan', 'title': 'Two parallel chains, one shape',
       'items': ['type is GamePiece.getType() — what the piece is; state is getState() — where it is and how it looks',
                 'Both are tab-joined trait chains in the same order, so trait n of one lines up with trait n of the other',
                 'Prototypes are expanded inline, so a saved type can never be compared to a PieceSlot definition whole']},
      {'dot': 'emerald', 'title': 'Reaching the leaf',
       'items': ['The innermost trait is the substring after the LAST tab — it carries no escaping',
                 'Identity lives there: name is field 5 of the type, gpid field 4 of the state',
                 'Field 5 of the state is a property count, followed by that many key;value pairs']}])

tool('sequence-encoder', 'SequenceEncoder — Nesting and Escaping',
     ['Level 0', 'Level 1', 'Level 2', 'Reading back'],
     [('command log', 'delimiter ESC 0x1B', 'database'),
      ('AddPiece fields', 'delimiter /', 'backend'),
      ('trait chain', 'delimiter tab', 'backend'),
      ('seqDecode()', 'unescape, then unquote', 'backend')],
     [('escape rule', 'delimiter gets a \\\\', 'external'),
      ('quote rule', "leading \\\\ wraps in '…'", 'external'),
      ('compounding', 'levels re-escape', 'backend'),
      ('original values', 'byte-for-byte', 'database')],
     ['tokens joined with', 'tokens joined with', 'is the exact inverse of'],
     ['applies at every level', 'applies at every level'],
     'why runs of \\\\ appear', 'one level per call',
     [{'dot': 'cyan', 'title': 'One encoder, three delimiters',
       'items': ['A literal delimiter inside a token is prefixed with a backslash',
                 "A token starting with \\\\ (or already quoted) is wrapped in single quotes",
                 'Encoding {A, {B, C}} with delimiter , gives A,B\\,C']},
      {'dot': 'amber', 'title': 'Why escapes pile up',
       'items': ['Each nesting level escapes the backslashes the level below already wrote',
                 'A real trait state shows -1\\t-1\\\\\\t-1\\\\\\\\\\t… — the run doubling at every level',
                 'This is the O(traits squared) growth measured in the WiF save-bloat analysis']},
      {'dot': 'emerald', 'title': 'Which is why tools copy verbatim',
       'items': ['Decoding and re-encoding an untouched token is not guaranteed to reproduce its bytes',
                 'Every tool here edits the bytes it targets and copies the rest through unchanged']}])


def build(spec):
    n, f = [], []
    for i in range(4):
        for row, lane in ((0, 'a'), (1, 'b')):
            label, sub, typ = spec[lane][i]
            n.append({'id': '%s%d' % (lane, i), 'type': typ, 'label': label,
                      'sublabel': sub, 'stage': i, 'row': row})
    for i in range(3):
        f.append({'id': 'a%d%d' % (i, i + 1), 'from': 'a%d' % i, 'to': 'a%d' % (i + 1),
                  'label': spec['fa'][i], 'variant': 'emphasis', 'labelDy': -30})
    for i in range(2):
        f.append({'id': 'b%d%d' % (i, i + 1), 'from': 'b%d' % i, 'to': 'b%d' % (i + 1),
                  'label': spec['fb'][i], 'labelDy': -52})
    f.append({'id': 'j2', 'from': 'b2', 'to': 'a2', 'label': spec['j2']})
    f.append({'id': 'j3', 'from': 'a3', 'to': 'b3', 'label': spec['j3'],
              'variant': 'emphasis', 'labelDy': 40})
    return {'schema_version': 1, 'diagram_type': 'dataflow',
            'meta': {'title': spec['title'], 'locale': 'en',
                     'quality_profile': 'showcase', 'viewBox': [860, 420]},
            'stages': [{'label': s} for s in spec['stages']],
            'nodes': n, 'flows': f, 'cards': spec['cards']}


out = sys.argv[1]
os.makedirs(out, exist_ok=True)
for stem, spec in T.items():
    p = os.path.join(out, stem + '.dataflow.json')
    json.dump(build(spec), open(p, 'w'), indent=2, ensure_ascii=False)
    print(p)
