# The game-piece record

A game piece is the one data structure shared by all three formats. In a
`.vmod`/`.vmdx` it is the text content of a `PieceSlot`; in a `.vsav` it is an
`AddPiece` command. Both are the same four-field record:

```
+/<pieceId>/<type>/<state>
```

**Diagram:** [diagrams/piece-record.html](diagrams/piece-record.html)

| Field | In a saved game | In a `PieceSlot` |
|---|---|---|
| `pieceId` | the instance's id — a millisecond timestamp | the literal `null` |
| `type` | `GamePiece.getType()` — what the piece *is* | the same |
| `state` | `GamePiece.getState()` — where it is and how it looks | the same, mostly empty |

The `/` separators are `SequenceEncoder` delimiters, so a `/` **inside** a field
is backslash-escaped and does not count. Finding the three cuts means scanning
for unescaped `/` characters, not calling `split('/')`.

## Two parallel chains

`type` and `state` are both tab-joined chains, one entry per trait
(VASSAL calls them Decorators), **in the same order**. Trait *n* of the type
lines up with trait *n* of the state.

A complete, real `PieceSlot` from the sample module makes the whole structure
visible at once:

```xml
<VASSAL.build.widget.PieceSlot entryName="AirUnit" gpid="17877" height="64" width="75"
>+/null/prototype;BlackAir→piece;;;;AirUnit/→null;0;0;17877;0</VASSAL.build.widget.PieceSlot>
```

(`→` marks a literal tab.) Pulling it apart:

```
pieceId : null
type    : prototype;BlackAir → piece;;;;AirUnit
state   :                    → null;0;0;17877;0
```

Two traits, so two entries in each chain:

| # | type entry | state entry |
|---|---|---|
| 1 | `prototype;BlackAir` | *(empty)* |
| 2 | `piece;;;;AirUnit` | `null;0;0;17877;0` |

The `prototype` trait contributes an empty state, which is why the state chain
begins with a tab. That leading empty field is normal, not damage.

## The leaf: `BasicPiece`

The **innermost** trait is always a `BasicPiece`, and it is where identity
lives. Reaching it is trivial once you know the trick: it is the substring after
the **last** tab in the chain. The innermost leaf carries no escaping — nothing
nests below it — so its content follows the final tab verbatim.

### `BasicPiece` type

```
piece;<clone>;<delete>;<image>;<name>
```

| Field | Index | Example |
|---|---|---|
| `piece` | 0 | the trait tag |
| clone key | 1 | usually empty |
| delete key | 2 | usually empty |
| **image** | 3 | `01_Red.png`, or empty when a prototype supplies it |
| **name** | 4 | `WeatherMarker` |

### `BasicPiece` state

```
<map>;<x>;<y>;<gpid>;<propertyCount>[;<key>;<value>]…
```

| Field | Index | Example |
|---|---|---|
| **map** | 0 | `Weather`, or the literal `null` when the piece is on no map |
| x | 1 | `52` |
| y | 2 | `517` |
| **gpid** | 3 | `34256` |
| property count | 4 | `4` |
| key/value pairs | 5… | `ClickedX;0;UniqueID;1740988011518;ppScale;1.0;ClickedY;0` |

A real one, from the sample scenario:

```
piece;;;01_Red.png;WeatherMarker
Weather;52;517;34256;4;ClickedX;0;UniqueID;1740988011518;ppScale;1.0;ClickedY;0
```

Note field 4: it is a **count**, and exactly that many `key;value` pairs
follow. A parser that treats everything after the gpid as a flat list will
still work for reading identity, but not for rewriting properties.

`UniqueID` is worth knowing about: VASSAL keeps a piece's `UniqueID` property
equal to its own piece id. A tool that clones a piece must allocate a fresh id
**and** reset `UniqueID`, or two pieces will share one.

## Identity

Two fields, and only two, connect a saved piece back to a definition:

| Key | Where | Used by |
|---|---|---|
| **GPID** | state field 3 | `GpIdChecker` — the primary match |
| **name** | type field 4 | the "Use counter names" fallback |

VASSAL's Refresh Counters tries the GPID first and the name second. A piece that
fails **both** is what it reports as *"Unable to match piece … by name"*, and
that two-key failure is exactly the condition this project uses to call a piece
"excess" — see [docs/vsav-excess-units.md](../vsav-excess-units.md).

Requiring both keys to fail is what makes removal safe: a marker placed at run
time has a fresh GPID but a live name, and a piece whose GPID still resolves is
repairable by a refresh. Neither is excess.

## Why a saved type never equals a slot definition

A `PieceSlot`'s type is written with `prototype;<name>` references. A **saved**
piece's type has those prototypes **expanded inline** — the full trait list, with
further prototypes expanded inside them.

So the two can never be compared whole, and any tool matching a saved piece to a
definition must work on individual traits. That constraint shapes several tools:

- [`fix_sif_subs.py`](../tools/fix_sif_subs.md) splices two specific trait
  substrings, chosen because neither contains a `/` or a tab and so both appear
  verbatim inside the expanded type at any nesting depth;
- [`remove_placemark_carriers.py`](../tools/remove_placemark_carriers.md)
  matches on the presence of a `placemark` trait anywhere in the expanded type;
- byte surgery cannot *build* a piece, which is why
  [`AddCountersRunner`](../refresh-counters.md#adding-counters--addcountersrunner)
  exists: only the engine can expand prototypes the way the engine does.

Expansion is also the reason saved games get large: every piece carries the full
text of every prototype it uses, and the escaping compounds with trait depth
(see [sequence-encoder.md](sequence-encoder.md#why-escapes-pile-up)).

## Stacks and decks are pieces too

A stack is itself an `AddPiece` whose type is `stack` and whose state lists its
members by piece id:

```
+/1740988011519/stack/Weather;52;517;1740988011518;@@-1
```

`Weather;52;517` is the map and position; `1740988011518` is a member id; `@@-1`
is the layer marker (`Stack.HAS_LAYER_MARKER`) that terminates the id list.

Two consequences follow, and both are relied on throughout this project:

- **Removing a piece needs no stack surgery.** The id left behind is dangling,
  and `Stack.setState()` looks each member up and silently skips what it cannot
  resolve. The stack simply comes up with fewer members.
- **Inserting a piece into a stack is positional, not appended.** An id added
  after the `@@<layer>` marker would be misread, so it must go among the ids.

A deck is likewise a piece, with a much longer `;`-delimited type carrying its
menu commands, shuffle options and bounds.

## Reading one

```python
import sys; sys.path.insert(0, 'tools')
from swap_maps import read_vsav, split_commands
from remove_placemark_carriers import add_piece_fields, basic_piece, piece_map, piece_name

state, _, _ = read_vsav('SAVE.vsav')
for ds, cs, end in split_commands(state):
    fields = add_piece_fields(state[cs:end].decode('utf-8', 'replace'))
    if not fields:
        continue                      # not an AddPiece
    leaf = basic_piece(fields[1], fields[2])
    if not leaf:
        continue                      # a stack or deck, not a unit
    gpid = leaf[1].split(';')[3]
    print(piece_name(leaf[0]), gpid, piece_map(leaf[1]))
```

## See also

- [vsav.md](vsav.md) · [vmod.md](vmod.md#piece-definitions)
- [sequence-encoder.md](sequence-encoder.md)
