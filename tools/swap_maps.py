#!/usr/bin/env python3
"""Swap the map layout of one .vsav into another.

Replaces the target save's `<mapName>BoardPicker` command token(s) with the
matching token(s) from a donor save, copying every other command byte-for-byte.
Mirrors SavedGame.saveWithout(): verbatim token copy, ESC delimiters re-emitted
unchanged, fresh obfuscation key, savedata/moduledata copied whole, output via
temp file + atomic replace.
"""
import os, random, sys, zipfile, zlib

ESC = 0x1B
HEADER = b'!VCSK'            # payload is the plaintext, XOR-hex encoded
DEFLATED_HEADER = b'!VCSZ'   # payload is deflated (zlib) before XOR-hex (VASSAL 3.8+)
SAVED_GAME, SAVE_DATA, MODULE_DATA = 'savedGame', 'savedata', 'moduledata'


def read_vsav(path):
    """-> (plaintext command log, {entry: (bytes, date_time)}, deflated flag)"""
    entries = {}
    with zipfile.ZipFile(path) as z:
        for name in (SAVED_GAME, SAVE_DATA, MODULE_DATA):
            info = z.getinfo(name)
            entries[name] = (z.read(name), info.date_time)
    raw = entries[SAVED_GAME][0]
    deflated = raw[:5] == DEFLATED_HEADER
    if not deflated and raw[:5] != HEADER:
        raise SystemExit(f'{path}: savedGame is not obfuscated (!VCSK/!VCSZ missing)')
    key = int(raw[5:7], 16)
    body = bytes.fromhex(raw[7:].decode('ascii'))
    body = body.translate(bytes(i ^ key for i in range(256)))
    if deflated:
        body = zlib.decompress(body)
    return body, entries, deflated


def split_commands(state):
    """Split at *every* ESC, recording (delim_start, content_start, end) per token.

    Identical to SavedGame.splitCommands: an ESC whose preceding byte is '\\' is
    a nested (deeper-level) delimiter, but it is still a split point — re-emitting
    the same delimiter bytes reconstructs the nesting exactly.
    """
    toks, delim_start, content_start = [], 0, 0
    for i, b in enumerate(state):
        if b == ESC:
            bs = 1 if (i > 0 and state[i - 1] == 0x5C) else 0
            toks.append((delim_start, content_start, i - bs))
            delim_start, content_start = i - bs, i + 1
    toks.append((delim_start, content_start, len(state)))
    return toks


def board_picker_tokens(state, toks):
    """-> {map identifier: token index} for every top-level BoardPicker command."""
    found = {}
    for idx, (ds, cs, end) in enumerate(toks):
        content = state[cs:end]
        pos = content.find(b'BoardPicker\t')
        if pos < 0 and not content.endswith(b'BoardPicker'):
            continue
        if pos < 0:
            pos = len(content) - len(b'BoardPicker')
        # Must be the command prefix, not text buried inside a piece definition.
        # (Map identifiers may contain '/' — e.g. "China TRS/AMPHBoardPicker".)
        if b'\x09' in content[:pos] or content[:2] in (b'+/', b'-/', b'D/', b'M/'):
            continue
        found[content[:pos].decode('utf-8')] = idx
    return found


def obfuscate(plain, key, deflated=False):
    """Re-encodes in the same format the file was read in (!VCSK or !VCSZ)."""
    payload = zlib.compress(plain, 9) if deflated else plain
    out = bytearray(DEFLATED_HEADER if deflated else HEADER)
    out += b'%02x' % key
    out += payload.translate(bytes(i ^ key for i in range(256))).hex().encode('ascii')
    return bytes(out)


def write_vsav(path, plain, entries, deflated=False, key=None):
    tmp = path + '.tmp'
    key = random.randrange(256) if key is None else key
    with zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as z:
        for name in (SAVED_GAME, SAVE_DATA, MODULE_DATA):
            data, when = entries[name]
            if name == SAVED_GAME:
                data = obfuscate(plain, key, deflated)
            z.writestr(zipfile.ZipInfo(name, date_time=when), data,
                       zipfile.ZIP_DEFLATED)
    os.replace(tmp, path)


def main(target, donor, out, maps):
    tgt, tgt_entries, tgt_deflated = read_vsav(target)
    don, _, _ = read_vsav(donor)
    tgt_toks, don_toks = split_commands(tgt), split_commands(don)
    tgt_bp, don_bp = board_picker_tokens(tgt, tgt_toks), board_picker_tokens(don, don_toks)

    if maps == ['ALL']:
        maps = sorted(set(tgt_bp) & set(don_bp))

    replace = {}
    for m in maps:
        if m not in tgt_bp:
            raise SystemExit(f'no BoardPicker for {m!r} in {target}')
        if m not in don_bp:
            raise SystemExit(f'no BoardPicker for {m!r} in {donor}')
        ti, di = tgt_bp[m], don_bp[m]
        # Both must be top-level tokens (unescaped ESC delimiter) for a raw splice.
        for state, toks, i, who in ((tgt, tgt_toks, ti, target), (don, don_toks, di, donor)):
            ds, cs, _ = toks[i]
            if cs - ds > 1:
                raise SystemExit(f'{who}: {m} BoardPicker is a nested token; refusing')
        old = bytes(tgt[tgt_toks[ti][1]:tgt_toks[ti][2]])
        new = bytes(don[don_toks[di][1]:don_toks[di][2]])
        if ESC in new:
            raise SystemExit(f'{donor}: {m} BoardPicker contains ESC; refusing')
        replace[ti] = new
        print(f'  {m}:')
        print(f'    was: {old.decode("utf-8")}')
        print(f'    now: {new.decode("utf-8")}')

    # Rebuild: every token verbatim with its own delimiter bytes, except the
    # replaced BoardPicker contents.
    parts = []
    for idx, (ds, cs, end) in enumerate(tgt_toks):
        parts.append(tgt[ds:cs])                       # delimiter bytes, unchanged
        parts.append(replace.get(idx) or tgt[cs:end])  # content
    plain = b''.join(parts)

    write_vsav(out, plain, tgt_entries, tgt_deflated)
    print(f'\nwrote {out}: {len(tgt_toks)} commands, '
          f'{len(plain)} plaintext bytes ({len(tgt)} before)')


if __name__ == '__main__':
    if len(sys.argv) < 5:
        raise SystemExit('usage: swap_maps.py TARGET DONOR OUT MAP [MAP...] | ALL')
    main(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4:])
