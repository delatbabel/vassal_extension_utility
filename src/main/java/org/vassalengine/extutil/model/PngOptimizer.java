package org.vassalengine.extutil.model;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;

/**
 * Losslessly re-compresses a PNG, by reducing its colour type where that loses
 * nothing and by searching for a better per-scanline filter.
 *
 * <p>Two things make module art bigger than it needs to be, and both are decisions
 * taken by whatever exported the image rather than anything VASSAL does:
 *
 * <ul>
 *   <li><b>The filter.</b> A PNG stores one filter byte per scanline, and the filter
 *       decides what deflate then sees. On flat-colour map art a predictive filter
 *       (Paeth especially) turns long runs of identical pixels into runs of differing
 *       residuals that deflate can no longer match. Rewriting such an image with
 *       filter {@code None} can halve it.</li>
 *   <li><b>The colour type.</b> An image exported as 8-bit truecolour costs three
 *       bytes a pixel whatever it contains — one measured board map holds seven
 *       distinct colours, and another is entirely grey. Both can be re-encoded as
 *       palette or greyscale with every pixel preserved exactly.</li>
 * </ul>
 *
 * <p>Measured across the VASSAL module collection, most art — counters, and the maps
 * already exported with filter {@code None} — cannot be improved at all, while the
 * large truecolour maps shrink by between two and three times. See
 * {@code docs/module-size-analysis.md}.
 *
 * <p><b>Nothing here is lossy.</b> The IDAT stream is inflated and un-filtered back
 * to raw scanlines; candidate encodings are built from those scanlines; and before
 * any result is returned it is parsed back and compared with the original
 * <em>pixel by pixel, as RGBA</em>, one row at a time. A result that does not match
 * exactly is discarded and the image is left alone. Chunks the utility does not
 * rewrite are copied through byte-for-byte.
 *
 * <p>Interlaced PNGs are skipped (their filtering is per-Adam7-pass, and they are
 * vanishingly rare in modules). Colour reduction is attempted only for 8-bit images,
 * which is all the module collection contains; anything else still gets the filter
 * search.
 */
public final class PngOptimizer {

    private static final byte[] SIG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    /** Filter strategies tried, in the order they are tried. */
    private static final int[] FIXED = {0, 2, 1, 4, 3};   // None, Up, Sub, Paeth, Average
    private static final int ADAPTIVE = -1;
    private static final String[] FILTER_NAME = {"None", "Sub", "Up", "Average", "Paeth"};

    /** An image is only replaced when the saving clears both of these. */
    private static final int MIN_GAIN_BYTES = 1024;
    private static final double MIN_GAIN_FRACTION = 0.01;

    /** Above this height a sampled pre-pass picks which strategies get a full run. */
    private static final int SAMPLE_ABOVE_ROWS = 1024;
    private static final int SAMPLE_BANDS = 16;
    private static final int SAMPLE_BAND_ROWS = 32;
    /** How many strategies survive the pre-pass and are run over the whole image. */
    private static final int FULL_RUNS = 2;

    /** Ancillary chunks whose contents are defined in terms of the colour type. */
    private static final List<String> COLOUR_DEPENDENT =
            Arrays.asList("PLTE", "tRNS", "bKGD", "sBIT", "hIST");

    private PngOptimizer() { }

    /** The outcome of optimising one image. */
    public static final class Result {
        /** The smaller PNG, or {@code null} when the image was left alone. */
        public final byte[] data;
        public final int originalSize;
        public final int optimisedSize;
        /** How it was re-encoded, e.g. {@code "None"} or {@code "palette4/None"}. */
        public final String filter;
        /** Why the image was left alone, or {@code null} when it was improved. */
        public final String skipped;

        private Result(byte[] data, int originalSize, int optimisedSize, String filter, String skipped) {
            this.data = data;
            this.originalSize = originalSize;
            this.optimisedSize = optimisedSize;
            this.filter = filter;
            this.skipped = skipped;
        }

        public boolean improved() { return data != null; }
        public int saved() { return originalSize - optimisedSize; }
    }

    private static Result skip(int size, String why) {
        return new Result(null, size, size, null, why);
    }

    /**
     * Returns a smaller, pixel-identical PNG, or a {@link Result} explaining why the
     * image was left as it is. Never throws for malformed input — such an image is
     * simply reported as skipped.
     */
    public static Result optimise(byte[] png) {
        if (png == null || png.length < 8) return skip(png == null ? 0 : png.length, "not a PNG");
        for (int i = 0; i < SIG.length; i++) {
            if (png[i] != SIG[i]) return skip(png.length, "not a PNG");
        }
        try {
            return run(png);
        }
        catch (OutOfMemoryError e) {
            return skip(png.length, "too large for available memory");
        }
        catch (Exception e) {       // malformed image: leave it exactly as it is
            return skip(png.length, "unreadable (" + e.getClass().getSimpleName() + ")");
        }
    }

    // ------------------------------------------------------------------ model

    /** One parsed chunk, with its raw payload. */
    private static final class Chunk {
        final String type;
        final byte[] data;
        Chunk(String type, byte[] data) { this.type = type; this.data = data; }
    }

    /** An image as un-filtered scanlines, plus everything needed to read them. */
    private static final class Raster {
        int width, height, depth, colour;
        int stride, bpp;
        byte[] raw;
        byte[] plte;        // RGB triples, or null
        byte[] trns;        // palette alpha, or null
        String label;       // how this encoding is named in the log

        int channels() { return channelsFor(colour); }
    }

    // ------------------------------------------------------------------ driver

    private static Result run(byte[] png) throws IOException, DataFormatException {
        final List<Chunk> chunks = new ArrayList<>();
        final ByteArrayOutputStream idat = new ByteArrayOutputStream();
        int off = 8;
        while (off + 8 <= png.length) {
            final int len = readInt(png, off);
            if (len < 0 || off + 12 + len > png.length) break;
            final String type = new String(png, off + 4, 4, StandardCharsets.US_ASCII);
            final byte[] data = new byte[len];
            System.arraycopy(png, off + 8, data, 0, len);
            if ("IDAT".equals(type)) idat.write(data);
            else chunks.add(new Chunk(type, data));
            off += 12 + len;
            if ("IEND".equals(type)) break;
        }

        final Chunk ihdr = chunks.isEmpty() ? null : chunks.get(0);
        if (ihdr == null || !"IHDR".equals(ihdr.type) || ihdr.data.length < 13) {
            return skip(png.length, "no IHDR");
        }

        final Raster src = new Raster();
        src.width = readInt(ihdr.data, 0);
        src.height = readInt(ihdr.data, 4);
        src.depth = ihdr.data[8] & 0xFF;
        src.colour = ihdr.data[9] & 0xFF;
        final int compression = ihdr.data[10] & 0xFF;
        final int filterMethod = ihdr.data[11] & 0xFF;
        final int interlace = ihdr.data[12] & 0xFF;

        if (interlace != 0) return skip(png.length, "interlaced");
        if (compression != 0 || filterMethod != 0) return skip(png.length, "unknown compression/filter method");
        if (src.width <= 0 || src.height <= 0) return skip(png.length, "empty image");
        if (src.channels() == 0) return skip(png.length, "unknown colour type " + src.colour);

        for (Chunk c : chunks) {
            if ("PLTE".equals(c.type)) src.plte = c.data;
            else if ("tRNS".equals(c.type)) src.trns = c.data;
        }
        if (src.colour == 3 && src.plte == null) return skip(png.length, "palette image with no PLTE");

        final long bitsPerRow = (long) src.width * src.channels() * src.depth;
        final long strideL = (bitsPerRow + 7) / 8;
        if (strideL * src.height > Integer.MAX_VALUE - 64) return skip(png.length, "too large to process");
        src.stride = (int) strideL;
        src.bpp = Math.max(1, (src.channels() * src.depth + 7) / 8);
        src.label = "";

        src.raw = inflateAndUnfilter(idat.toByteArray(), src.height, src.stride, src.bpp);
        if (src.raw == null) return skip(png.length, "IDAT does not match IHDR");

        // Candidate encodings: the original as it stands, plus a reduced colour type
        // where that is possible without losing a pixel.
        final List<Raster> candidates = new ArrayList<>(3);
        candidates.add(src);
        candidates.addAll(reductions(src));

        byte[] bestFile = null;
        String bestLabel = null;
        for (Raster r : candidates) {
            byte[] bestIdat = null;
            String bestFilter = null;
            for (int strategy : strategies(r)) {
                final byte[] out = deflateFiltered(r, strategy);
                if (bestIdat == null || out.length < bestIdat.length) {
                    bestIdat = out;
                    bestFilter = strategy == ADAPTIVE ? "adaptive" : FILTER_NAME[strategy];
                }
            }
            final byte[] file = rebuild(chunks, r, bestIdat, r != src);
            if (bestFile == null || file.length < bestFile.length) {
                bestFile = file;
                bestLabel = r.label.isEmpty() ? bestFilter : r.label + "/" + bestFilter;
            }
        }

        final int gain = png.length - bestFile.length;
        // A trivial gain is not worth rewriting the entry for: replacing a 51 MB map
        // to save 80 KB churns the archive (and any sync or backup of it) for nothing.
        if (gain < MIN_GAIN_BYTES || gain < png.length * MIN_GAIN_FRACTION) {
            return skip(png.length, "already optimal");
        }

        // Lossless or nothing: compare the result with the original, pixel by pixel.
        if (!verify(bestFile, src)) {
            return skip(png.length, "verification failed");
        }
        return new Result(bestFile, png.length, bestFile.length, bestLabel, null);
    }

    private static int channelsFor(int colourType) {
        switch (colourType) {
            case 0: return 1;   // greyscale
            case 2: return 3;   // truecolour
            case 3: return 1;   // palette
            case 4: return 2;   // greyscale + alpha
            case 6: return 4;   // truecolour + alpha
            default: return 0;
        }
    }

    // ------------------------------------------------------- colour reduction

    /**
     * Builds every lossless re-encoding of {@code src} worth trying — the caller
     * compresses each and keeps whichever comes out smallest, because which one wins
     * is not decidable in advance. A grey image under 256 colours, for instance, can
     * go to either a palette or greyscale, and greyscale is usually but not always
     * ahead by the 768 bytes a palette costs.
     *
     * <p>Only 8-bit images are considered, which is everything the module collection
     * holds; a 1/2/4-bit palette image is already as narrow as its palette allows,
     * and 16-bit does not occur. The candidates are: a palette with the narrowest bit
     * depth its size permits, when the image holds at most 256 distinct RGBA values;
     * greyscale, when every pixel is opaque with equal channels; and truecolour, when
     * an RGBA image turns out to be fully opaque.
     */
    private static List<Raster> reductions(Raster src) {
        final List<Raster> none = new ArrayList<>(0);
        if (src.depth != 8) return none;
        if (src.colour != 0 && src.colour != 2 && src.colour != 3 && src.colour != 6) return none;

        final ColourTable table = new ColourTable(256);
        boolean allOpaque = true;
        boolean allGrey = true;
        boolean overflow = false;

        final int[] row = new int[src.width];
        for (int y = 0; y < src.height; y++) {
            readRowRgba(src, y, row);
            for (int i = 0; i < src.width; i++) {
                final int px = row[i];
                if ((px >>> 24) != 0xFF) allOpaque = false;
                if (((px >> 16) & 0xFF) != ((px >> 8) & 0xFF) || ((px >> 8) & 0xFF) != (px & 0xFF)) {
                    allGrey = false;
                }
                if (!overflow && table.add(px) < 0) overflow = true;
            }
            if (overflow && !allGrey && !allOpaque) break;   // nothing left to learn
        }

        final List<Raster> out = new ArrayList<>(2);
        if (!overflow) {
            final int n = table.size();
            final int depth = n <= 2 ? 1 : n <= 4 ? 2 : n <= 16 ? 4 : 8;
            // Skip a palette image already at the narrowest depth for its palette.
            if (!(src.colour == 3 && depth == src.depth && n == src.plte.length / 3)) {
                out.add(toPalette(src, table, depth));
            }
        }
        if (allOpaque && allGrey && src.colour != 0) out.add(toDirect(src, 0));
        else if (allOpaque && src.colour == 6)       out.add(toDirect(src, 2));
        return out;
    }

    /** Re-encodes as a palette image of the given bit depth. */
    private static Raster toPalette(Raster src, ColourTable table, int depth) {
        final int n = table.size();
        final Raster out = new Raster();
        out.width = src.width;
        out.height = src.height;
        out.depth = depth;
        out.colour = 3;
        out.stride = (src.width * depth + 7) / 8;
        out.bpp = 1;
        out.raw = new byte[out.stride * src.height];
        out.label = "palette" + depth;

        out.plte = new byte[n * 3];
        int lastAlpha = -1;
        for (int i = 0; i < n; i++) {
            final int px = table.keyAt(i);
            out.plte[i * 3]     = (byte) ((px >> 16) & 0xFF);
            out.plte[i * 3 + 1] = (byte) ((px >> 8) & 0xFF);
            out.plte[i * 3 + 2] = (byte) (px & 0xFF);
            if ((px >>> 24) != 0xFF) lastAlpha = i;
        }
        if (lastAlpha >= 0) {           // tRNS need only run up to the last non-opaque entry
            out.trns = new byte[lastAlpha + 1];
            for (int i = 0; i <= lastAlpha; i++) out.trns[i] = (byte) (table.keyAt(i) >>> 24);
        }

        final int[] row = new int[src.width];
        final int perByte = 8 / depth;
        final int mask = (1 << depth) - 1;
        for (int y = 0; y < src.height; y++) {
            readRowRgba(src, y, row);
            final int base = y * out.stride;
            for (int x = 0; x < src.width; x++) {
                final int idx = table.indexOf(row[x]) & mask;
                if (depth == 8) {
                    out.raw[base + x] = (byte) idx;
                }
                else {
                    final int shift = 8 - depth * (x % perByte + 1);
                    out.raw[base + x / perByte] |= (byte) (idx << shift);
                }
            }
        }
        return out;
    }

    /** Re-encodes as greyscale (colour type 0) or truecolour (2), both 8-bit. */
    private static Raster toDirect(Raster src, int colourType) {
        final Raster out = new Raster();
        out.width = src.width;
        out.height = src.height;
        out.depth = 8;
        out.colour = colourType;
        out.bpp = colourType == 0 ? 1 : 3;
        out.stride = src.width * out.bpp;
        out.raw = new byte[out.stride * src.height];
        out.label = colourType == 0 ? "grey8" : "rgb8";

        final int[] row = new int[src.width];
        for (int y = 0; y < src.height; y++) {
            readRowRgba(src, y, row);
            final int base = y * out.stride;
            for (int x = 0; x < src.width; x++) {
                final int px = row[x];
                if (colourType == 0) {
                    out.raw[base + x] = (byte) (px & 0xFF);
                }
                else {
                    out.raw[base + x * 3]     = (byte) ((px >> 16) & 0xFF);
                    out.raw[base + x * 3 + 1] = (byte) ((px >> 8) & 0xFF);
                    out.raw[base + x * 3 + 2] = (byte) (px & 0xFF);
                }
            }
        }
        return out;
    }

    /**
     * Expands one scanline to ARGB ints. This is the single place pixel semantics
     * live, so reduction and verification cannot disagree about what a pixel is.
     */
    private static void readRowRgba(Raster r, int y, int[] out) {
        final int base = y * r.stride;
        final int d = r.depth;
        switch (r.colour) {
            case 0: {   // greyscale
                final int max = (1 << d) - 1;
                for (int x = 0; x < r.width; x++) {
                    int v = sample(r.raw, base, x, d);
                    v = d == 16 ? (v >> 8) : (max == 0 ? 0 : v * 255 / max);
                    out[x] = 0xFF000000 | (v << 16) | (v << 8) | v;
                }
                break;
            }
            case 2: {   // truecolour
                final int step = d == 16 ? 6 : 3;
                final int sub = d == 16 ? 2 : 1;
                for (int x = 0; x < r.width; x++) {
                    final int o = base + x * step;
                    final int rr = r.raw[o] & 0xFF;
                    final int gg = r.raw[o + sub] & 0xFF;
                    final int bb = r.raw[o + 2 * sub] & 0xFF;
                    out[x] = 0xFF000000 | (rr << 16) | (gg << 8) | bb;
                }
                break;
            }
            case 3: {   // palette
                for (int x = 0; x < r.width; x++) {
                    final int i = sample(r.raw, base, x, d);
                    final int p = i * 3;
                    final int rr = p + 2 < r.plte.length ? r.plte[p] & 0xFF : 0;
                    final int gg = p + 2 < r.plte.length ? r.plte[p + 1] & 0xFF : 0;
                    final int bb = p + 2 < r.plte.length ? r.plte[p + 2] & 0xFF : 0;
                    final int a = (r.trns != null && i < r.trns.length) ? r.trns[i] & 0xFF : 0xFF;
                    out[x] = (a << 24) | (rr << 16) | (gg << 8) | bb;
                }
                break;
            }
            case 4: {   // greyscale + alpha
                final int step = d == 16 ? 4 : 2;
                final int sub = d == 16 ? 2 : 1;
                for (int x = 0; x < r.width; x++) {
                    final int o = base + x * step;
                    final int v = r.raw[o] & 0xFF;
                    final int a = r.raw[o + sub] & 0xFF;
                    out[x] = (a << 24) | (v << 16) | (v << 8) | v;
                }
                break;
            }
            default: {  // 6, truecolour + alpha
                final int step = d == 16 ? 8 : 4;
                final int sub = d == 16 ? 2 : 1;
                for (int x = 0; x < r.width; x++) {
                    final int o = base + x * step;
                    final int rr = r.raw[o] & 0xFF;
                    final int gg = r.raw[o + sub] & 0xFF;
                    final int bb = r.raw[o + 2 * sub] & 0xFF;
                    final int a = r.raw[o + 3 * sub] & 0xFF;
                    out[x] = (a << 24) | (rr << 16) | (gg << 8) | bb;
                }
                break;
            }
        }
    }

    /** Reads sample {@code x} of a single-channel row at bit depth {@code d}. */
    private static int sample(byte[] raw, int base, int x, int d) {
        switch (d) {
            case 8:  return raw[base + x] & 0xFF;
            case 16: return ((raw[base + x * 2] & 0xFF) << 8) | (raw[base + x * 2 + 1] & 0xFF);
            default: {
                final int perByte = 8 / d;
                final int b = raw[base + x / perByte] & 0xFF;
                final int shift = 8 - d * (x % perByte + 1);
                return (b >> shift) & ((1 << d) - 1);
            }
        }
    }

    /** Open-addressed set of ARGB values that gives each one a stable index. */
    private static final class ColourTable {
        private final int[] slot;
        private final int[] keys;
        private final int mask;
        private final int cap;
        private int n;

        ColourTable(int cap) {
            this.cap = cap;
            int size = Integer.highestOneBit(Math.max(16, cap * 4) - 1) << 1;
            slot = new int[size];
            Arrays.fill(slot, -1);
            keys = new int[cap];
            mask = size - 1;
        }

        /** Returns the value's index, or -1 if the table is full and it is new. */
        int add(int key) {
            int i = hash(key);
            while (slot[i] >= 0) {
                if (keys[slot[i]] == key) return slot[i];
                i = (i + 1) & mask;
            }
            if (n == cap) return -1;
            keys[n] = key;
            slot[i] = n;
            return n++;
        }

        int indexOf(int key) {
            int i = hash(key);
            while (slot[i] >= 0) {
                if (keys[slot[i]] == key) return slot[i];
                i = (i + 1) & mask;
            }
            return -1;
        }

        private int hash(int key) { return (key * 0x9E3779B1) >>> 1 & mask; }
        int size() { return n; }
        int keyAt(int i) { return keys[i]; }
    }

    // ------------------------------------------------------- filtering/deflate

    /** Inflates the IDAT stream and reverses the per-scanline filters. */
    private static byte[] inflateAndUnfilter(byte[] compressed, int height, int stride, int bpp)
            throws DataFormatException {
        final long needL = (long) height * (stride + 1);
        if (needL > Integer.MAX_VALUE - 64) return null;
        final int need = (int) needL;
        final byte[] filtered = new byte[need];

        final Inflater inf = new Inflater();
        try {
            inf.setInput(compressed);
            int got = 0;
            while (got < need && !inf.finished()) {
                final int n = inf.inflate(filtered, got, need - got);
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                got += n;
            }
            if (got != need) return null;
        }
        finally {
            inf.end();
        }

        final byte[] raw = new byte[height * stride];
        for (int y = 0; y < height; y++) {
            final int fOff = y * (stride + 1);
            final int type = filtered[fOff] & 0xFF;
            final int rOff = y * stride;
            final int pOff = rOff - stride;
            for (int i = 0; i < stride; i++) {
                final int x = filtered[fOff + 1 + i] & 0xFF;
                final int a = i >= bpp ? raw[rOff + i - bpp] & 0xFF : 0;
                final int b = y > 0 ? raw[pOff + i] & 0xFF : 0;
                final int c = (i >= bpp && y > 0) ? raw[pOff + i - bpp] & 0xFF : 0;
                final int v;
                switch (type) {
                    case 0: v = x; break;
                    case 1: v = x + a; break;
                    case 2: v = x + b; break;
                    case 3: v = x + ((a + b) >> 1); break;
                    case 4: v = x + paeth(a, b, c); break;
                    default: return null;          // invalid filter type
                }
                raw[rOff + i] = (byte) v;
            }
        }
        return raw;
    }

    /**
     * Chooses which filter strategies to run over the whole image.
     *
     * <p>A full pass costs a filter and a deflate of every scanline, and on a
     * hundred-megapixel map six of them is minutes of work. For a tall image the
     * strategies are first ranked by deflating a sample — {@value #SAMPLE_BANDS}
     * bands of {@value #SAMPLE_BAND_ROWS} consecutive rows spread through the image
     * — and only the best {@value #FULL_RUNS} are run in full. Bands are contiguous,
     * and each row is filtered against its real predecessor, so the sampled bytes are
     * exactly the bytes a full pass would emit for those rows; what the sample can
     * misjudge is only how well the <em>whole</em> stream packs. Taking two survivors
     * rather than one covers that. (Verified: on the measured corpus the sampled and
     * exhaustive searches pick encodings of identical size.)
     */
    private static int[] strategies(Raster r) throws IOException {
        final int[] all = new int[FIXED.length + 1];
        System.arraycopy(FIXED, 0, all, 0, FIXED.length);
        all[FIXED.length] = ADAPTIVE;
        if (r.height <= SAMPLE_ABOVE_ROWS) return all;       // cheap enough to try everything

        final int step = Math.max(SAMPLE_BAND_ROWS, r.height / SAMPLE_BANDS);
        final long[] cost = new long[all.length];
        for (int i = 0; i < all.length; i++) cost[i] = sampleCost(r, all[i], step);

        final Integer[] order = new Integer[all.length];
        for (int i = 0; i < all.length; i++) order[i] = i;
        Arrays.sort(order, (x, y) -> Long.compare(cost[x], cost[y]));

        final int keep = Math.min(FULL_RUNS, all.length);
        final int[] picked = new int[keep];
        for (int i = 0; i < keep; i++) picked[i] = all[order[i]];
        return picked;
    }

    /** Deflates a banded sample of rows under one strategy and returns its size. */
    private static long sampleCost(Raster r, int strategy, int step) throws IOException {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 16);
        final Deflater def = new Deflater(Deflater.BEST_COMPRESSION);
        final byte[] row = new byte[r.stride];
        final byte[] cand = strategy == ADAPTIVE ? new byte[r.stride] : null;
        try (DeflaterOutputStream dos = new DeflaterOutputStream(bos, def, 1 << 16)) {
            for (int start = 0; start < r.height; start += step) {
                final int end = Math.min(r.height, start + SAMPLE_BAND_ROWS);
                for (int y = start; y < end; y++) emitRow(r, y, strategy, row, cand, dos);
            }
        }
        finally {
            def.end();
        }
        return bos.size();
    }

    /**
     * Filters every row under {@code strategy} and deflates the result, streaming row
     * by row so only the raster and the compressed output are ever resident.
     */
    private static byte[] deflateFiltered(Raster r, int strategy) throws IOException {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(1024, r.raw.length / 8));
        final Deflater def = new Deflater(Deflater.BEST_COMPRESSION);
        final byte[] row = new byte[r.stride];
        final byte[] cand = strategy == ADAPTIVE ? new byte[r.stride] : null;
        try (DeflaterOutputStream dos = new DeflaterOutputStream(bos, def, 1 << 16)) {
            for (int y = 0; y < r.height; y++) emitRow(r, y, strategy, row, cand, dos);
        }
        finally {
            def.end();
        }
        return bos.toByteArray();
    }

    /** Filters row {@code y} under {@code strategy} and writes it to {@code dos}. */
    private static void emitRow(Raster r, int y, int strategy, byte[] row, byte[] cand,
                                DeflaterOutputStream dos) throws IOException {
        final int rOff = y * r.stride;
        final int pOff = rOff - r.stride;
        int use = strategy;
        if (strategy == ADAPTIVE) {
            long bestScore = Long.MAX_VALUE;
            use = 0;
            for (int f = 0; f <= 4; f++) {
                final long score = filterRow(r.raw, rOff, pOff, y, r.stride, r.bpp, f, cand);
                if (score < bestScore) {
                    bestScore = score;
                    use = f;
                    System.arraycopy(cand, 0, row, 0, r.stride);
                }
            }
        }
        else {
            filterRow(r.raw, rOff, pOff, y, r.stride, r.bpp, use, row);
        }
        dos.write(use);
        dos.write(row, 0, r.stride);
    }

    /**
     * Writes one filtered scanline into {@code out} and returns its heuristic cost
     * (the sum of absolute signed byte values, the standard adaptive-filter metric).
     */
    private static long filterRow(byte[] raw, int rOff, int pOff, int y,
                                  int stride, int bpp, int type, byte[] out) {
        long score = 0;
        for (int i = 0; i < stride; i++) {
            final int x = raw[rOff + i] & 0xFF;
            final int a = i >= bpp ? raw[rOff + i - bpp] & 0xFF : 0;
            final int b = y > 0 ? raw[pOff + i] & 0xFF : 0;
            final int c = (i >= bpp && y > 0) ? raw[pOff + i - bpp] & 0xFF : 0;
            final int v;
            switch (type) {
                case 1:  v = x - a; break;
                case 2:  v = x - b; break;
                case 3:  v = x - ((a + b) >> 1); break;
                case 4:  v = x - paeth(a, b, c); break;
                default: v = x; break;
            }
            final int bv = v & 0xFF;
            out[i] = (byte) bv;
            score += bv < 128 ? bv : 256 - bv;
        }
        return score;
    }

    private static int paeth(int a, int b, int c) {
        final int p = a + b - c;
        final int pa = Math.abs(p - a);
        final int pb = Math.abs(p - b);
        final int pc = Math.abs(p - c);
        if (pa <= pb && pa <= pc) return a;
        return pb <= pc ? b : c;
    }

    // ------------------------------------------------------------- rebuilding

    /**
     * Reassembles the file around a new IDAT. When the colour type changed, the
     * chunks whose contents are defined in terms of it are replaced (PLTE, tRNS) or
     * dropped (bKGD, sBIT, hIST) rather than copied into a file they no longer
     * describe; everything else — gAMA, pHYs, text, timestamps — is kept verbatim.
     */
    private static byte[] rebuild(List<Chunk> chunks, Raster r, byte[] newIdat, boolean recoloured)
            throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(newIdat.length + 4096);
        out.write(SIG);

        final byte[] ihdr = new byte[13];
        writeIntAt(ihdr, 0, r.width);
        writeIntAt(ihdr, 4, r.height);
        ihdr[8] = (byte) r.depth;
        ihdr[9] = (byte) r.colour;
        ihdr[10] = 0;
        ihdr[11] = 0;
        ihdr[12] = 0;
        writeChunk(out, "IHDR", ihdr);

        if (r.colour == 3 && r.plte != null) writeChunk(out, "PLTE", r.plte);
        if (r.trns != null) writeChunk(out, "tRNS", r.trns);

        boolean wroteIdat = false;
        for (Chunk c : chunks) {
            if ("IHDR".equals(c.type)) continue;
            if (recoloured && COLOUR_DEPENDENT.contains(c.type)) continue;
            if (!recoloured && ("PLTE".equals(c.type) || "tRNS".equals(c.type))) continue;  // already written
            if ("IEND".equals(c.type) && !wroteIdat) {
                writeChunk(out, "IDAT", newIdat);
                wroteIdat = true;
            }
            writeChunk(out, c.type, c.data);
        }
        if (!wroteIdat) {            // no IEND in the source; append in spec order
            writeChunk(out, "IDAT", newIdat);
            writeChunk(out, "IEND", new byte[0]);
        }
        return out.toByteArray();
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        final byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        writeInt(out, data.length);
        out.write(t);
        out.write(data);
        final CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        writeInt(out, (int) crc.getValue());
    }

    // ----------------------------------------------------------- verification

    /**
     * Re-reads the produced PNG and compares it with the original a row at a time,
     * as RGBA. This is what makes the whole operation safe: a filter bug, a palette
     * bug or a mis-copied chunk all show up here as a pixel that differs, and the
     * result is thrown away.
     */
    private static boolean verify(byte[] out, Raster src) {
        try {
            final List<Chunk> chunks = new ArrayList<>();
            final ByteArrayOutputStream idat = new ByteArrayOutputStream();
            int off = 8;
            while (off + 8 <= out.length) {
                final int len = readInt(out, off);
                if (len < 0 || off + 12 + len > out.length) return false;
                final String type = new String(out, off + 4, 4, StandardCharsets.US_ASCII);
                if ("IDAT".equals(type)) idat.write(out, off + 8, len);
                else {
                    final byte[] d = new byte[len];
                    System.arraycopy(out, off + 8, d, 0, len);
                    chunks.add(new Chunk(type, d));
                }
                off += 12 + len;
                if ("IEND".equals(type)) break;
            }
            if (chunks.isEmpty() || !"IHDR".equals(chunks.get(0).type)) return false;

            final Raster got = new Raster();
            final byte[] ih = chunks.get(0).data;
            got.width = readInt(ih, 0);
            got.height = readInt(ih, 4);
            got.depth = ih[8] & 0xFF;
            got.colour = ih[9] & 0xFF;
            if (got.width != src.width || got.height != src.height) return false;
            if (ih[12] != 0) return false;

            for (Chunk c : chunks) {
                if ("PLTE".equals(c.type)) got.plte = c.data;
                else if ("tRNS".equals(c.type)) got.trns = c.data;
            }
            if (got.colour == 3 && got.plte == null) return false;

            got.stride = (int) (((long) got.width * got.channels() * got.depth + 7) / 8);
            got.bpp = Math.max(1, (got.channels() * got.depth + 7) / 8);
            got.raw = inflateAndUnfilter(idat.toByteArray(), got.height, got.stride, got.bpp);
            if (got.raw == null) return false;

            final int[] a = new int[src.width];
            final int[] b = new int[src.width];
            for (int y = 0; y < src.height; y++) {
                readRowRgba(src, y, a);
                readRowRgba(got, y, b);
                for (int x = 0; x < src.width; x++) {
                    if (a[x] == b[x]) continue;
                    // A fully transparent pixel's colour channels are not visible, so
                    // the encoders may legitimately differ there; anything else is a bug.
                    if ((a[x] >>> 24) == 0 && (b[x] >>> 24) == 0) continue;
                    return false;
                }
            }
            return true;
        }
        catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ bytes

    private static int readInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
             | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static void writeIntAt(byte[] b, int off, int v) {
        b[off]     = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }
}
