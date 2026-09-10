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
# The three obfuscation formats of the savedGame entry, identified by header.
# The header bytes double as the format token that read_vsav returns and
# obfuscate()/write_vsav() take, so a rewrite re-emits the format it read.
RAW = b'VOBS'            # 1 raw key byte, then XOR(plaintext, key) raw (VASSAL 3.8+)
HEX = b'!VCSK'           # 2-hex key, then hex(XOR(plaintext, key)) (through 3.7.x)
HEX_DEFLATED = b'!VCSZ'  # as HEX, but the plaintext is deflated first (abandoned)
FORMATS = (RAW, HEX, HEX_DEFLATED)
SAVED_GAME, SAVE_DATA, MODULE_DATA = 'savedGame', 'savedata', 'moduledata'


def read_vsav(path):
    """-> (plaintext command log, {entry: (bytes, date_time)}, format token)

    The format token is one of RAW / HEX / HEX_DEFLATED; pass it back to
    write_vsav() so the save is rewritten in the format it was read in.
    """
    entries = {}
    with zipfile.ZipFile(path) as z:
        for name in (SAVED_GAME, SAVE_DATA, MODULE_DATA):
            info = z.getinfo(name)
            entries[name] = (z.read(name), info.date_time)
    raw = entries[SAVED_GAME][0]
    fmt = next((f for f in FORMATS if raw[:len(f)] == f), None)
    if fmt is None:
        raise SystemExit(
            f'{path}: savedGame is not obfuscated (VOBS/!VCSK/!VCSZ missing)')
    if fmt == RAW:
        key, body = raw[4], raw[5:]
    else:
        key, body = int(raw[5:7], 16), bytes.fromhex(raw[7:].decode('ascii'))
    body = body.translate(bytes(i ^ key for i in range(256)))
    if fmt == HEX_DEFLATED:
        body = zlib.decompress(body)
    return body, entries, fmt


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


def obfuscate(plain, key, fmt=RAW):
    """Encodes the command log in one of RAW / HEX / HEX_DEFLATED."""
    payload = zlib.compress(plain, 9) if fmt == HEX_DEFLATED else plain
    payload = payload.translate(bytes(i ^ key for i in range(256)))
    out = bytearray(fmt)
    if fmt == RAW:
        out.append(key)
        out += payload
    else:
        out += b'%02x' % key
        out += payload.hex().encode('ascii')
    return bytes(out)


def write_vsav(path, plain, entries, fmt=RAW, key=None):
    tmp = path + '.tmp'
    # Keys are in 1-255, as in VASSAL's ObfuscatingOutputStream: XORing with 0
    # would leave the data in plain text.
    key = random.randrange(1, 256) if key is None else key
    with zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as z:
        for name in (SAVED_GAME, SAVE_DATA, MODULE_DATA):
            data, when = entries[name]
            if name == SAVED_GAME:
                data = obfuscate(plain, key, fmt)
            z.writestr(zipfile.ZipInfo(name, date_time=when), data,
                       zipfile.ZIP_DEFLATED)
    os.replace(tmp, path)


def main(target, donor, out, maps):
    tgt, tgt_entries, tgt_fmt = read_vsav(target)
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

    write_vsav(out, plain, tgt_entries, tgt_fmt)
    print(f'\nwrote {out}: {len(tgt_toks)} commands, '
          f'{len(plain)} plaintext bytes ({len(tgt)} before)')


if __name__ == '__main__':
    if len(sys.argv) < 5:
        raise SystemExit('usage: swap_maps.py TARGET DONOR OUT MAP [MAP...] | ALL')
    main(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4:])
