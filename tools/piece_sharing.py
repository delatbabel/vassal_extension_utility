#!/usr/bin/env python3
"""How much of a saved game's piece data is duplicated across pieces.

For every AddPiece in a .vsav this splits the piece's type and state chains
into their per-trait segments (either the nested 3.7 framing or the flat 3.8
one) and reports, for the whole game:

  * pieces, traits, and bytes of type text and state text;
  * how many distinct whole type chains there are (a flyweight that shares
    one type-data object per identical piece definition can save no more
    than this allows);
  * how many distinct trait segments there are (a flyweight that shares
    type data per trait, so that pieces built from the same prototype share
    its expanded traits even when their own traits differ, is bounded by
    this);
  * the same for state text, which no flyweight can share;
  * the largest repeated runs of consecutive trait segments (the baked-in
    prototypes) and how many pieces carry each.

Usage: piece_sharing.py <file.vsav> [more.vsav ...]
"""
import re
import sys
import zipfile
import lzma
import zlib
from collections import Counter

ESC = '\x1b'


def deobfuscate(data):
    if data[:6] == b'\xfd7zXZ\x00':
        return lzma.decompress(data)
    if data[:5] == b'!VOBS':
        key = data[5]
        return bytes.translate(data[6:], bytes(i ^ key for i in range(256)))
    if data[:5] == b'!VCSK':
        key = int(data[5:7], 16)
        return bytes.translate(bytes.fromhex(data[7:].decode('ascii')), bytes(i ^ key for i in range(256)))
    if data[:5] == b'!VCSZ':
        key = int(data[5:7], 16)
        hx = data[7:]
        return zlib.decompress(bytes(int(hx[i:i + 2], 16) ^ key for i in range(0, len(hx), 2)))
    return data


def unquote(t):
    return t[1:-1] if len(t) > 1 and t[0] == "'" and t[-1] == "'" else t


def split_top(s, delim):
    """SequenceEncoder.Decoder over one level."""
    toks = re.split(r'(?<!\\)' + re.escape(delim), s)
    return [unquote(t.replace('\\' + delim, delim)) for t in toks]


def segments(chain):
    """The per-trait segments of a type or state chain, outermost first,
    whichever framing it is in (nested: exactly two top-level tokens, the
    rest escaped as one; flat: one token per trait)."""
    toks = split_top(chain, '\t')
    if len(toks) == 1:
        return toks
    return toks[:-1] + segments(toks[-1])


def trait_id(seg):
    return seg.split(';', 1)[0] if ';' in seg else seg


def analyse(path):
    with zipfile.ZipFile(path) as z:
        plain = deobfuscate(z.read('savedGame')).decode('utf-8')
    pieces = []
    for cmd in plain.split(ESC):
        if not cmd.startswith('+/'):
            continue
        parts = split_top(cmd[2:], '/')
        if len(parts) != 3 or parts[1] == 'stack' or parts[1].startswith('deck;'):
            continue  # stacks and decks are containers, not trait chains
        pieces.append((segments(parts[1]), segments(parts[2])))

    n = len(pieces)
    traits = sum(len(t) for t, _ in pieces)
    type_bytes = sum(len(s) for t, _ in pieces for s in t)
    state_bytes = sum(len(s) for _, st in pieces for s in st)

    whole_types = Counter('\t'.join(t) for t, _ in pieces)
    distinct_whole_bytes = sum(len(k) for k in whole_types)
    seg_counter = Counter(s for t, _ in pieces for s in t)
    distinct_seg_bytes = sum(len(k) for k in seg_counter)
    state_whole = Counter('\t'.join(st) for _, st in pieces)
    distinct_state_bytes = sum(len(k) for k in state_whole)

    # Which traits carry the state: per trait id, total state bytes.
    state_by_trait = Counter()
    for t, st in pieces:
        for seg, sseg in zip(t, st):
            state_by_trait[trait_id(seg)] += len(sseg)
    trait_counts = Counter(trait_id(s) for t, _ in pieces for s in t)

    print(f'== {path}')
    print(f'pieces {n:,}   traits {traits:,}   mean traits/piece {traits / max(n, 1):.1f}')
    print(f'type text  {type_bytes:>12,} bytes   distinct whole chains {len(whole_types):>6,} = {distinct_whole_bytes:>12,} bytes '
          f'({distinct_whole_bytes / max(type_bytes, 1):.1%})   distinct trait segments {len(seg_counter):>7,} = {distinct_seg_bytes:>11,} bytes ({distinct_seg_bytes / max(type_bytes, 1):.1%})')
    print(f'state text {state_bytes:>12,} bytes   distinct whole chains {len(state_whole):>6,} = {distinct_state_bytes:>12,} bytes ({distinct_state_bytes / max(state_bytes, 1):.1%})')
    dup = whole_types.most_common(5)
    print('most repeated whole piece types (copies, traits, bytes each):')
    for k, c in dup:
        segs = k.split('\t')
        print(f'   {c:>5} x {len(segs):>3} traits {len(k):>7,} bytes  {trait_id(segs[-1])[:6]}…{segs[-1].split(";")[-1][:40]!r}')
    print('pieces by number of identical copies of their whole type: ' +
          ', '.join(f'{k}x:{v}' for k, v in sorted(Counter(min(c, 10) for c in whole_types.values() for _ in range(c)).items())))
    print('trait ids by count (top 12): ' + ', '.join(f'{k} {v:,}' for k, v in trait_counts.most_common(12)))
    print('state bytes by trait id (top 8): ' + ', '.join(f'{k} {v:,}' for k, v in state_by_trait.most_common(8)))

    # Repeated runs: longest common runs of consecutive segments shared by many pieces.
    # Approximate the baked prototypes by hashing every run of >= 8 segments that
    # begins at a segment boundary, and keeping the longest runs shared by >= 2 pieces.
    run_pieces = {}
    for idx, (t, _) in enumerate(pieces):
        for i in range(len(t)):
            for L in (8, 16, 32, 64):
                if i + L <= len(t):
                    run_pieces.setdefault((L, '\t'.join(t[i:i + L])), set()).add(idx)
    best = sorted(((L, len(p), len(k)) for (L, k), p in run_pieces.items() if len(p) >= 2), key=lambda x: (-x[0], -x[1]))
    shown = 0
    print('shared runs of consecutive traits (run length, pieces sharing it, bytes):')
    for L, p, b in best:
        if shown >= 6:
            break
        print(f'   {L:>3} traits shared by {p:>5} pieces, {b:>7,} bytes')
        shown += 1
    print()


if __name__ == '__main__':
    for p in sys.argv[1:]:
        analyse(p)
