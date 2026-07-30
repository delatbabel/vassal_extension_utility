#!/usr/bin/env python3
"""Translate pieces on one map of a .vsav by (dx, dy), restricted to an x range.

Used after swap_maps.py when a board changes grid column: pieces that sat on
that board keep their position relative to it. Only the innermost BasicPiece
state's x/y fields are touched (`<map>;<x>;<y>;<gpid>;…`); every other byte of
every command is copied verbatim.
"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from swap_maps import read_vsav, split_commands, write_vsav


def main(path, out, map_name, x_lo, x_hi, dx, dy):
    state, entries = read_vsav(path)
    toks = split_commands(state)
    prefix = (map_name + ';').encode('utf-8')

    parts, moved = [], []
    for ds, cs, end in toks:
        parts.append(state[ds:cs])
        content = state[cs:end]
        tab = content.rfind(b'\t')
        inner = content[tab + 1:]
        if content[:2] != b'+/' or not inner.startswith(prefix):
            parts.append(content)
            continue
        f = inner.split(b';')
        try:
            x, y = int(f[1]), int(f[2])
        except (IndexError, ValueError):   # not a positioned BasicPiece
            parts.append(content)
            continue
        if not (x_lo <= x < x_hi):
            parts.append(content)
            continue
        f[1], f[2] = str(x + dx).encode(), str(y + dy).encode()
        parts.append(content[:tab + 1] + b';'.join(f))
        moved.append((f[3].decode(), x, y, x + dx, y + dy))

    write_vsav(out, b''.join(parts), entries)
    for gpid, ox, oy, nx, ny in moved:
        print(f'  gpid {gpid}: ({ox},{oy}) -> ({nx},{ny})')
    print(f'\nwrote {out}: {len(moved)} piece(s) moved')


if __name__ == '__main__':
    if len(sys.argv) != 8:
        raise SystemExit('usage: shift_pieces.py TARGET OUT MAP X_LO X_HI DX DY')
    p, o, m, lo, hi, dx, dy = sys.argv[1:8]
    main(p, o, m, int(lo), int(hi), int(dx), int(dy))
