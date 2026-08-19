/*
 * Copyright (c) 2025 VASSAL Extension Utility contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Library General Public
 * License (LGPL) as published by the Free Software Foundation.
 */
package org.vassalengine.extutil.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Reads and rewrites a VASSAL saved-game ({@code .vsav}) file for the purpose of
 * finding and removing "excess" game pieces — pieces whose definitions are not
 * present in the currently-loaded module and its <em>active</em> extensions.
 *
 * <p>A {@code .vsav} is a ZIP archive of three entries: {@code moduledata} and
 * {@code savedata} (small XML metadata) and {@code savedGame} — an obfuscated
 * command log. See <b>docs/vsav-format.md</b> for the full format. The command
 * log is a {@code SequenceEncoder}-joined list of commands separated by the
 * {@code 0x1B} (ESC) character; a game piece is an {@code AddPiece} command of the
 * form {@code +/<id>/<type>/<state>} whose innermost trait is a {@code BasicPiece}
 * ({@code piece;<clone>;<delete>;<image>;<name>} in the type,
 * {@code <map>;<x>;<y>;<gpid>;…} in the state).</p>
 *
 * <p><b>What counts as excess.</b> A piece is flagged only when it cannot be
 * matched to any definition in the module + active extensions by <em>either</em>
 * of the two keys VASSAL uses in Refresh Counters — its GPID does not match any
 * active PieceSlot, <em>and</em> its (innermost BasicPiece) name matches no active
 * PieceSlot name. This is exactly the condition under which VASSAL logs
 * <i>"Unable to match piece &lt;name&gt; (GPID &lt;n&gt;) by name"</i>. Requiring
 * <em>both</em> keys to fail means a piece created at run time from a still-present
 * definition (same name) is never removed, and a piece whose GPID still matches an
 * active slot (repairable by Refresh Counters) is never removed. Such orphaned
 * pieces are also the ones that produce <i>"Bad Data in Module … Image not
 * found"</i> / <i>"No such map"</i> on load when their assets live only in an
 * extension that is no longer active.</p>
 *
 * <p>Rewriting is byte-exact: surviving command tokens are copied verbatim and
 * re-joined with ESC (never decoded and re-encoded), and the two metadata entries
 * are copied unchanged, so the only difference from the original is the removed
 * pieces.</p>
 */
public class SavedGame {

    private static final Logger log = LoggerFactory.getLogger(SavedGame.class);

    public static final String SAVED_GAME_ENTRY = "savedGame";
    public static final String SAVE_DATA_ENTRY  = "savedata";
    public static final String MODULE_DATA_ENTRY = "moduledata";

    /** Obfuscation header written by VASSAL's {@code ObfuscatingOutputStream}. */
    private static final byte[] HEADER = "!VCSK".getBytes(StandardCharsets.US_ASCII);
    /** Top-level command separator in the deobfuscated log (ESC / {@code KeyEvent.VK_ESCAPE}). */
    private static final byte CMD_DELIM = 0x1b;
    private static final byte ESCAPE = '\\';
    private static final String ADD_PIECE_PREFIX = "+/";
    private static final String BASIC_PIECE_PREFIX = "piece;";
    /** Prefix of the "this extension was loaded" command, {@code ExtensionsLoader.COMMAND_PREFIX}. */
    private static final String EXT_PREFIX = "EXT\t";

    private static final byte[] HEX = {
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    };

    private final java.io.File file;
    private final byte[] moduleData;
    private final byte[] saveData;
    private final long moduleDataTime;
    private final long saveDataTime;
    private final long savedGameTime;

    /** Deobfuscated {@code savedGame} plaintext (can be hundreds of MB). */
    private final byte[] state;
    /**
     * One entry {@code {delimStart, contentStart, contentEnd}} per command token in
     * {@link #state}. {@code [delimStart, contentStart)} is the token's preceding
     * ESC delimiter (0, 1 or 2 bytes — empty for the first token, {@code <ESC>} at
     * the top level, {@code \<ESC>} for a nested one); {@code [contentStart,
     * contentEnd)} is the verbatim command content. Splitting on <em>every</em> ESC
     * (both nesting levels) makes each {@code AddPiece} its own token; re-emitting
     * the same delimiter bytes reconstructs the nesting exactly.
     */
    private final List<int[]> commandRanges;

    private SavedGame(java.io.File file, byte[] moduleData, long moduleDataTime,
                      byte[] saveData, long saveDataTime,
                      byte[] state, long savedGameTime) {
        this.file = file;
        this.moduleData = moduleData;
        this.moduleDataTime = moduleDataTime;
        this.saveData = saveData;
        this.saveDataTime = saveDataTime;
        this.state = state;
        this.savedGameTime = savedGameTime;
        this.commandRanges = splitCommands(state);
    }

    public java.io.File getFile() { return file; }

    // -----------------------------------------------------------------------
    // Opening / deobfuscation
    // -----------------------------------------------------------------------

    /**
     * Opens a {@code .vsav}, reading the two metadata entries whole and
     * deobfuscating the {@code savedGame} command log into memory.
     *
     * @throws IOException if the file is not a ZIP with the expected entries, or
     *                     the {@code savedGame} entry lacks the {@code !VCSK} header
     */
    public static SavedGame open(java.io.File f) throws IOException {
        try (ZipFile zf = new ZipFile(f)) {
            final ZipEntry savedGame = requireEntry(zf, SAVED_GAME_ENTRY, f);
            final ZipEntry saveData  = zf.getEntry(SAVE_DATA_ENTRY);
            final ZipEntry moduleData = zf.getEntry(MODULE_DATA_ENTRY);

            final byte[] sd = saveData  == null ? new byte[0] : readAll(zf, saveData);
            final byte[] md = moduleData == null ? new byte[0] : readAll(zf, moduleData);
            final byte[] plain = deobfuscate(zf, savedGame, f);

            return new SavedGame(f,
                    md, moduleData == null ? -1L : moduleData.getTime(),
                    sd, saveData == null ? -1L : saveData.getTime(),
                    plain, savedGame.getTime());
        }
    }

    private static ZipEntry requireEntry(ZipFile zf, String name, java.io.File f) throws IOException {
        final ZipEntry e = zf.getEntry(name);
        if (e == null) {
            throw new IOException("Not a VASSAL saved game (no \"" + name + "\" entry): " + f.getName());
        }
        return e;
    }

    private static byte[] readAll(ZipFile zf, ZipEntry e) throws IOException {
        try (InputStream in = zf.getInputStream(e)) {
            return in.readAllBytes();
        }
    }

    /**
     * Streams the obfuscated {@code savedGame} entry and returns its plaintext.
     * The entry is {@code !VCSK} + a two-hex key byte + two-hex-per-byte payload,
     * each payload byte XOR-ed with the key (see docs/vsav-format.md).
     */
    private static byte[] deobfuscate(ZipFile zf, ZipEntry e, java.io.File f) throws IOException {
        final byte[] raw;
        try (InputStream in = zf.getInputStream(e)) {
            raw = in.readAllBytes();   // the whole (hex-ASCII) entry
        }
        if (raw.length < HEADER.length + 2) {
            throw new IOException("Not an obfuscated VASSAL saved game (too short): " + f.getName());
        }
        for (int i = 0; i < HEADER.length; i++) {
            if (raw[i] != HEADER[i]) {
                throw new IOException("Not an obfuscated VASSAL saved game (missing !VCSK header): "
                        + f.getName());
            }
        }
        final int key = (hexVal(raw[HEADER.length]) << 4) | hexVal(raw[HEADER.length + 1]);
        final int payload = raw.length - HEADER.length - 2;
        final byte[] out = new byte[payload / 2];
        int j = HEADER.length + 2;
        for (int i = 0; i < out.length; i++, j += 2) {
            out[i] = (byte) (((hexVal(raw[j]) << 4) | hexVal(raw[j + 1])) ^ key);
        }
        return out;
    }

    private static int hexVal(int c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        throw new NumberFormatException("Bad hex digit: " + c);
    }

    // -----------------------------------------------------------------------
    // Command splitting
    // -----------------------------------------------------------------------

    /**
     * Splits the plaintext into command tokens at <em>every</em> ESC, recording for
     * each token its preceding-delimiter range and its content range (see
     * {@link #commandRanges}). An ESC preceded by a backslash is a nested (depth-1)
     * delimiter and the backslash is treated as part of the delimiter, not the token
     * — matching VASSAL's {@code SequenceEncoder.Decoder} (which classifies an ESC by
     * its single preceding byte). Splitting at both depths isolates each
     * {@code AddPiece} as its own token so it can be removed independently.
     */
    private static List<int[]> splitCommands(byte[] state) {
        final List<int[]> tokens = new ArrayList<>();
        int contentStart = 0;   // start of the current token's content
        int delimStart = 0;     // start of the current token's preceding delimiter
        for (int i = 0; i < state.length; i++) {
            if (state[i] == CMD_DELIM) {
                final int bs = (i > 0 && state[i - 1] == ESCAPE) ? 1 : 0;
                final int thisDelimStart = i - bs;                 // include the escaping backslash
                tokens.add(new int[]{delimStart, contentStart, thisDelimStart});
                delimStart = thisDelimStart;
                contentStart = i + 1;
            }
        }
        tokens.add(new int[]{delimStart, contentStart, state.length});
        return tokens;
    }

    // -----------------------------------------------------------------------
    // Analysis
    // -----------------------------------------------------------------------

    /** Total number of top-level commands (including begin_save / end_save). */
    public int getCommandCount() { return commandRanges.size(); }

    /**
     * Finds every excess piece in the saved game: a game piece whose GPID matches
     * no active PieceSlot <em>and</em> whose name matches no active PieceSlot name
     * (see the class notes). Pieces with an empty GPID or empty name are never
     * flagged (they cannot be positively identified as orphaned).
     *
     * @param activeGpids  PieceSlot GPIDs in the module + active extensions
     * @param activeNames  PieceSlot (innermost BasicPiece) names in the module + active extensions
     * @param inactiveGpidExt GPID → inactive extension display name (first found)
     * @param inactiveNameExt name → inactive extension display name (first found)
     */
    public List<ExcessPiece> findExcessPieces(Set<String> activeGpids,
                                              Set<String> activeNames,
                                              Map<String, String> inactiveGpidExt,
                                              Map<String, String> inactiveNameExt) {
        final List<ExcessPiece> excess = new ArrayList<>();
        for (int idx = 0; idx < commandRanges.size(); idx++) {
            final int[] r = commandRanges.get(idx);
            final int cs = r[1], ce = r[2];   // content [contentStart, contentEnd)
            // Cheap prefix test before decoding: "+/"
            if (ce - cs < 2 || state[cs] != '+' || state[cs + 1] != '/') continue;

            final String cmd = new String(state, cs, ce - cs, StandardCharsets.UTF_8);
            final List<String> parts = seqDecode(cmd.substring(ADD_PIECE_PREFIX.length()), '/');
            if (parts.size() < 3) continue;

            final String basicType = innermostTrait(parts.get(1));
            if (!basicType.startsWith(BASIC_PIECE_PREFIX)) continue;   // not a unit (deck/stack/…)

            final List<String> typeFields = seqDecode(basicType, ';');
            final String image = typeFields.size() > 3 ? typeFields.get(3) : "";
            final String name  = typeFields.size() > 4 ? typeFields.get(4) : "";

            final List<String> stateFields = seqDecode(innermostTrait(parts.get(2)), ';');
            final String gpid = stateFields.size() > 3 ? stateFields.get(3) : "";

            // Unmatchable iff neither its GPID nor its name is known to the active set.
            final boolean gpidUnmatched = !gpid.isEmpty() && !activeGpids.contains(gpid);
            final boolean nameUnmatched = !name.isEmpty() && !activeNames.contains(name);
            if (gpidUnmatched && nameUnmatched) {
                String ext = inactiveGpidExt.get(gpid);
                if (ext == null) ext = inactiveNameExt.get(name);
                excess.add(new ExcessPiece(idx, name, image, gpid, ext));
            }
        }
        return excess;
    }

    /**
     * Extracts the innermost BasicPiece name from a PieceSlot / CardSlot /
     * PrototypeDefinition definition (stored as a {@code +/null/<type>/<state>}
     * command in the build file), or {@code null} if the text is not such a piece.
     * Used to build the set of names the active set can match by.
     */
    public static String basicPieceName(String slotDefinition) {
        if (slotDefinition == null || !slotDefinition.startsWith(ADD_PIECE_PREFIX)) return null;
        final List<String> parts = seqDecode(slotDefinition.substring(ADD_PIECE_PREFIX.length()), '/');
        if (parts.size() < 2) return null;
        final String basicType = innermostTrait(parts.get(1));
        if (!basicType.startsWith(BASIC_PIECE_PREFIX)) return null;
        final List<String> fields = seqDecode(basicType, ';');
        return fields.size() > 4 ? fields.get(4) : null;
    }

    /**
     * Returns the innermost (BasicPiece) trait segment of a Decorator {@code type}
     * or {@code state} string: the substring after the last {@code \t}. The trait
     * chain nests as {@code outer\t(inner…)}; the innermost leaf carries no
     * escaping, so its content follows the final tab verbatim.
     */
    private static String innermostTrait(String traitChain) {
        final int i = traitChain.lastIndexOf('\t');
        return i < 0 ? traitChain : traitChain.substring(i + 1);
    }

    // -----------------------------------------------------------------------
    // Rewriting
    // -----------------------------------------------------------------------

    /**
     * Writes a new saved game to {@code target} with the given commands removed,
     * leaving this object's source file untouched. Surviving command tokens are
     * copied verbatim and re-joined with ESC, then re-obfuscated; the two metadata
     * entries are copied unchanged.
     *
     * @param removeIndices indices (into the command list) of pieces to drop —
     *                      typically {@link ExcessPiece#commandIndex} values
     */
    // -----------------------------------------------------------------------
    // Extension registrations
    // -----------------------------------------------------------------------

    /**
     * The saved game's record of which extensions were loaded when it was saved:
     * the contents of its {@code EXT<TAB><name><TAB><version>} commands, in log
     * order. Each {@code name} is an extension's file name without its
     * {@code .vmdx} suffix ({@code ModuleExtension.getName()}).
     *
     * <p>VASSAL rebuilds this list from the <em>currently loaded</em> extensions
     * every time it saves, so a refresh run with every extension active would
     * otherwise widen each scenario's list to the full set. Capturing it before
     * the refresh and putting it back with
     * {@link #restoreExtensionRegistrations} keeps each scenario's own record
     * exactly as it was.</p>
     */
    public List<String> getExtensionRegistrations() {
        final List<String> out = new ArrayList<>();
        for (int[] r : commandRanges) {
            final String content = new String(state, r[1], r[2] - r[1], StandardCharsets.UTF_8);
            if (content.startsWith(EXT_PREFIX)) out.add(content);
        }
        return out;
    }

    /** The extension names (without version) from {@link #getExtensionRegistrations()}. */
    public Set<String> getExtensionNames() {
        final Set<String> out = new LinkedHashSet<>();
        for (String cmd : getExtensionRegistrations()) {
            final List<String> f = seqDecode(cmd.substring(EXT_PREFIX.length()), '\t');
            if (!f.isEmpty() && !f.get(0).isEmpty()) out.add(f.get(0));
        }
        return out;
    }

    /**
     * Rewrites {@code file} so that its extension registrations are exactly
     * {@code originals}, leaving every other command byte-for-byte untouched.
     *
     * <p>The file's own {@code EXT} commands are dropped and {@code originals}
     * emitted in their place — at the position of the first one, so the list
     * keeps the spot in the log VASSAL puts it in. If the file has no {@code EXT}
     * commands at all they go directly after the first command
     * ({@code begin_save}), which is where VASSAL writes them.</p>
     *
     * <p>Does nothing when the file's registrations already match. Writes via a
     * temp file and an atomic move, like {@link #saveWithout}.</p>
     *
     * @return {@code true} if the file was rewritten
     */
    public static boolean restoreExtensionRegistrations(java.io.File file, List<String> originals)
            throws IOException {
        final SavedGame game = open(file);
        if (game.getExtensionRegistrations().equals(originals)) return false;

        final Set<Integer> drop = new HashSet<>();
        int insertBefore = -1;
        for (int i = 0; i < game.commandRanges.size(); i++) {
            final int[] r = game.commandRanges.get(i);
            final String content =
                    new String(game.state, r[1], r[2] - r[1], StandardCharsets.UTF_8);
            if (content.startsWith(EXT_PREFIX)) {
                drop.add(i);
                if (insertBefore < 0) insertBefore = i;
            }
        }
        // No EXT commands to replace: put the list straight after begin_save.
        if (insertBefore < 0) insertBefore = Math.min(1, game.commandRanges.size());

        game.write(file, drop, insertBefore, originals);
        return true;
    }

    public void saveWithout(Set<Integer> removeIndices, java.io.File target) throws IOException {
        write(target, removeIndices, -1, Collections.<String>emptyList());
    }

    /**
     * Writes the saved game to {@code target}: every command copied verbatim
     * except those at {@code removeIndices}, with {@code insertContents} spliced
     * in before index {@code insertBefore}. The two metadata entries are copied
     * byte-for-byte.
     */
    private void write(java.io.File target, Set<Integer> removeIndices,
                       int insertBefore, List<String> insertContents) throws IOException {
        final java.io.File parent = target.getParentFile();
        if (parent != null) parent.mkdirs();

        // Write to a temporary file first, then move it into place — so an
        // interrupted or failed write (e.g. the app closed mid-save) never leaves
        // a truncated file at the target path.
        final java.io.File tmp = java.io.File.createTempFile(
                target.getName() + ".", ".tmp", parent);
        try {
            try (ZipOutputStream zos = new ZipOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(tmp.toPath())))) {

                // savedGame (regenerated, obfuscated) — same entry order VASSAL writes.
                final ZipEntry sg = new ZipEntry(SAVED_GAME_ENTRY);
                if (savedGameTime >= 0) sg.setTime(savedGameTime);
                zos.putNextEntry(sg);
                writeObfuscated(zos, removeIndices, insertBefore, insertContents);
                zos.closeEntry();

                writeVerbatim(zos, SAVE_DATA_ENTRY, saveData, saveDataTime);
                writeVerbatim(zos, MODULE_DATA_ENTRY, moduleData, moduleDataTime);
            }
            try {
                Files.move(tmp.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp.toPath());   // no-op once moved; cleans up on failure
        }
    }

    private static void writeVerbatim(ZipOutputStream zos, String name, byte[] data, long time)
            throws IOException {
        final ZipEntry e = new ZipEntry(name);
        if (time >= 0) e.setTime(time);
        zos.putNextEntry(e);
        zos.write(data);
        zos.closeEntry();
    }

    /**
     * Streams the surviving command tokens through the VASSAL obfuscation
     * ({@code !VCSK} + random key (2 hex) + 2-hex-per-byte XOR key). Each removed
     * token is dropped together with its preceding delimiter, and each kept token
     * is emitted verbatim <em>with</em> its preceding delimiter — so the surviving
     * bytes (and their ESC nesting) are exactly the original minus the removed
     * pieces. The very first emitted token drops its (possibly empty) leading
     * delimiter so the stream never begins with a stray separator.
     */
    private void writeObfuscated(OutputStream out, Set<Integer> removeIndices)
            throws IOException {
        writeObfuscated(out, removeIndices, -1, Collections.<String>emptyList());
    }

    /**
     * As above, additionally emitting {@code insertContents} as top-level command
     * tokens immediately before the surviving token at index {@code insertBefore}
     * (ignored when negative). Inserted commands are separated by a bare
     * {@code <ESC>}, which is what makes them top level.
     */
    private void writeObfuscated(OutputStream out, Set<Integer> removeIndices,
                                 int insertBefore, List<String> insertContents)
            throws IOException {
        final int key = new Random().nextInt(256);
        // Accumulate hex output in a large buffer so the deflater sees big chunks
        // rather than 2 bytes at a time (which is dramatically slower).
        final HexSink sink = new HexSink(out, key);
        for (byte h : HEADER) sink.rawByte(h);
        sink.rawByte(HEX[(key & 0xF0) >>> 4]);
        sink.rawByte(HEX[key & 0x0F]);

        boolean firstEmitted = true;
        for (int idx = 0; idx < commandRanges.size(); idx++) {
            if (idx == insertBefore) {
                for (String content : insertContents) {
                    if (!firstEmitted) sink.encoded(CMD_DELIM);
                    firstEmitted = false;
                    for (byte b : content.getBytes(StandardCharsets.UTF_8)) sink.encoded(b);
                }
            }
            if (removeIndices.contains(idx)) continue;
            final int[] r = commandRanges.get(idx);
            final int from = firstEmitted ? r[1] : r[0];   // skip leading delimiter for the first token
            firstEmitted = false;
            for (int i = from; i < r[2]; i++) sink.encoded(state[i]);
        }
        sink.flush();
    }

    /**
     * Buffers the obfuscated output. {@link #rawByte} writes a byte of the
     * {@code !VCSK}+key header as-is; {@link #encoded} XORs a plaintext byte with
     * the key and writes it as two hex digits.
     */
    private static final class HexSink {
        private final OutputStream out;
        private final int key;
        private final byte[] buf = new byte[1 << 16];
        private int p;

        HexSink(OutputStream out, int key) { this.out = out; this.key = key; }

        void rawByte(byte b) throws IOException {
            if (p >= buf.length) flush();
            buf[p++] = b;
        }

        void encoded(byte b) throws IOException {
            if (p >= buf.length - 1) flush();
            final int x = (b ^ key) & 0xFF;
            buf[p++] = HEX[(x & 0xF0) >>> 4];
            buf[p++] = HEX[x & 0x0F];
        }

        void flush() throws IOException {
            if (p > 0) { out.write(buf, 0, p); p = 0; }
        }
    }

    // -----------------------------------------------------------------------
    // SequenceEncoder decoding (faithful port of VASSAL's SequenceEncoder.Decoder)
    // -----------------------------------------------------------------------

    /**
     * Splits {@code val} on unescaped {@code delim}, honouring {@code \}-escaped
     * delimiters and single-quote enclosure — the exact inverse of the encoder
     * that produced the string. Mirrors {@code ArchivePanel.seqDecode}.
     */
    private static List<String> seqDecode(String val, char delim) {
        final List<String> out = new ArrayList<>();
        if (val == null) return out;
        final int stop = val.length();
        int start = 0;
        boolean done = false;
        while (!done) {
            if (start == stop) {
                out.add("");
                break;
            }
            StringBuilder buf = null;
            String tok = null;
            int i = start;
            for (; i < stop; i++) {
                if (val.charAt(i) == delim) {
                    if (i > 0 && val.charAt(i - 1) == '\\') {
                        if (buf == null) buf = new StringBuilder();
                        buf.append(val, start, i - 1);
                        start = i;
                    } else {
                        if (buf == null || buf.length() == 0) {
                            tok = val.substring(start, i);
                        } else {
                            buf.append(val, start, i);
                        }
                        start = i + 1;
                        break;
                    }
                }
            }
            if (start < i) {
                if (buf == null || buf.length() == 0) {
                    tok = val.substring(start);
                } else {
                    buf.append(val, start, stop);
                }
                done = true;
            }
            out.add(unquote(tok != null ? tok : (buf != null ? buf.toString() : "")));
        }
        return out;
    }

    private static String unquote(String s) {
        final int len = s.length();
        return (len > 1 && s.charAt(0) == '\'' && s.charAt(len - 1) == '\'')
                ? s.substring(1, len - 1) : s;
    }

    // -----------------------------------------------------------------------
    // Result type
    // -----------------------------------------------------------------------

    /** One excess piece found in the saved game. */
    public static final class ExcessPiece {
        /** Index into the command list — passed to {@link SavedGame#saveWithout}. */
        public final int commandIndex;
        public final String name;
        public final String image;
        public final String gpid;
        /** Inactive extension that could supply this piece, or {@code null} if none. */
        public final String inactiveExtension;

        ExcessPiece(int commandIndex, String name, String image, String gpid, String inactiveExtension) {
            this.commandIndex = commandIndex;
            this.name = name;
            this.image = image;
            this.gpid = gpid;
            this.inactiveExtension = inactiveExtension;
        }

        /** Display label: {@code name [image]}, plus {@code {ext (Inactive)}} when applicable. */
        public String displayLabel() {
            final StringBuilder sb = new StringBuilder();
            sb.append(name == null || name.isEmpty() ? "(unnamed)" : name);
            if (image != null && !image.isEmpty()) sb.append("  [").append(image).append(']');
            if (inactiveExtension != null) sb.append("   {").append(inactiveExtension).append(" (Inactive)}");
            return sb.toString();
        }
    }
}
