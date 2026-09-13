# SequenceEncoder

Every delimited structure in every VASSAL format is a `SequenceEncoder` string.
The command log, an `AddPiece`'s fields, a piece's trait chain, an
`ExtensionElement`'s target path — all the same encoding with different
delimiters. It is one page of rules, and it makes all three formats readable.

**Diagram:** [diagrams/sequence-encoder.md](diagrams/sequence-encoder.md) (viewable here) · [diagrams/sequence-encoder.html](diagrams/sequence-encoder.html) (interactive, download and open in a browser)

## The rules

`SequenceEncoder` joins a list of strings with a single-character delimiter.
Two rules make the join reversible:

1. **A literal delimiter inside a token is prefixed with a backslash.**
2. **A token that begins with a backslash — or is already wrapped in single
   quotes — is wrapped in single quotes**, so the decoder knows to unquote it
   rather than treat the leading backslash as an escape.

Encoding `{A, {B, C}}` with delimiter `,` gives:

```
A,B\,C
```

The encoder, as this project implements it
([`MainWindow.seqAppend`](../../src/main/java/org/vassalengine/extutil/gui/MainWindow.java)):

```java
if (addDelimiter) buf.append(delim);
if (s == null || s.isEmpty()) return;
boolean quote = s.charAt(0) == '\\'
        || (s.charAt(0) == '\'' && s.charAt(s.length() - 1) == '\'');
if (quote) buf.append('\'');
for (int i = 0; i < s.length(); i++) {
    char c = s.charAt(i);
    if (c == delim) buf.append('\\');
    buf.append(c);
}
if (quote) buf.append('\'');
```

An **empty token contributes nothing but its delimiter**, which is why
`piece;;;;AirUnit` has three empty fields in the middle and why runs of
consecutive delimiters are normal rather than a sign of damage.

## The decoder

The inverse walks the string looking for a delimiter whose immediately preceding
byte is **not** a backslash. That single-byte lookbehind is the whole
classification rule, and it is what makes the format streamable:

```
for each character:
    if it is the delimiter:
        if the previous character was '\' -> it is escaped; keep scanning
        else                              -> token ends here
```

Then the token is unquoted if it starts and ends with `'`. This project ports it
faithfully in two places — `SavedGame.seqDecode` and `ArchivePanel.seqDecode` —
because getting it subtly wrong silently mis-splits piece data.

## The nesting levels

| Level | Structure | Delimiter | Example |
|---|---|---|---|
| 0 | The saved-game command log | `ESC` (`0x1B`) | `begin_save` `<ESC>` `+/…` `<ESC>` `end_save` |
| 1 | An `AddPiece` command's fields | `/` | `+/<id>/<type>/<state>` |
| 1 | A `BoardPicker` or `EXT` command | tab | `EXT\t10-SiF\t2.1` |
| 2 | A piece's trait chain | tab | `prototype;BlackAir\tpiece;;;;AirUnit` |
| 3 | One trait's own fields | `;` | `piece;;;;AirUnit` |
| 4 | A field holding a list | `,` | `Black _Corps.png,Black _CorpsBack.png` |
| — | An `ExtensionElement` target path | `/` then `:` | `ChartWindow:Charts/TabWidget:tabs` |

The saved-game log is the only level that uses a control character. That choice
is deliberate: piece data can contain every printable character, but never an
ESC, so splitting the log at ESC bytes is unambiguous without decoding anything
below it.

### Why splitting at *every* ESC is safe

The log is nested one level deep in places — pieces grafted into a command are
separated by an **escaped** ESC (`\` + `0x1B`) rather than a bare one. A tool
that only wanted top-level records would have to track depth.

Splitting at every ESC instead, and recording each token's *preceding delimiter
bytes* alongside its content, reconstructs the nesting exactly: re-emit the same
delimiter bytes and the two-level structure comes back byte-for-byte. This is
what `SavedGame.splitCommands` and `tools/swap_maps.py::split_commands` do, and
it is why a piece nested one level deep can be removed as independently as a
top-level one.

## Why escapes pile up

Each nesting level escapes the delimiters **and the backslashes** the level
below already wrote. Encode a value that contains a tab, then embed the result in
another tab-joined chain, and the backslash count doubles.

This is visible in any real save. Here is the beginning of the `state` chain of a
marker from the sample scenario, a piece with a dozen traits:

```
-1	-1\	-1\\	-1\\\	-1\\\\	true;;0;false\\\\\	false\\\\\\	0\\\\\\\ …
```

Every field is the literal `-1`, and every one is longer than the last. The run
of backslashes grows by one per trait, so a piece with *n* traits carries
**O(n²)** backslashes in its state.

That is not a curiosity — it is a measured cause of saved-game bloat in
large modules; see
[docs/wif-save-bloat-analysis.md](../wif-save-bloat-analysis.md).

## The practical consequence: copy verbatim

Because the encoding is lossy in the sense that matters — a value can have more
than one valid encoding, and re-encoding a decoded token is not guaranteed to
reproduce its input bytes — **no tool in this project decodes a token it does not
intend to change.**

Every rewrite works the same way:

1. split the stream into token byte ranges;
2. edit only the bytes of the tokens being targeted;
3. copy every other token through, byte for byte, with its original delimiter.

The check that this actually held is short, and worth running after any rewrite:

```python
import sys; sys.path.insert(0, 'tools')
from swap_maps import read_vsav, split_commands
a, _, _ = read_vsav('before.vsav'); b, _, _ = read_vsav('after.vsav')
ta, tb = split_commands(a), split_commands(b)
assert len(ta) == len(tb)
print([i for i in range(len(ta)) if a[ta[i][0]:ta[i][2]] != b[tb[i][0]:tb[i][2]]])
```

A correct run prints exactly the indices it meant to touch.

## See also

- [game-piece.md](game-piece.md) — the structure this encoding carries
- [vsav.md](vsav.md#the-command-log) · [vmdx.md](vmdx.md#the-target-path)
