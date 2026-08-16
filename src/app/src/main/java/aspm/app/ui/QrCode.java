package aspm.app.ui;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A QR encoder, scoped to what a TOTP provisioning URI needs. ISO/IEC 18004.
 *
 * <p>Written rather than depended upon for the reason ADR-058 gives: no client build step and no registry
 * access at deploy time. Byte mode, error correction level M, versions 1 to 10 — an {@code otpauth://}
 * URI is 100 to 200 bytes and version 10 holds 213 in byte mode, so the range covers the input with room
 * and stops well short of the tables a general encoder needs.
 *
 * <h2>A wrong QR is worse than no QR</h2>
 *
 * <p>A user who scans a malformed code blames their authenticator, then their phone, then support — the
 * failure is expensive and points away from its cause. So {@link #encode} returns empty rather than
 * guessing when the input does not fit, the caller shows the setup key instead, and the accompanying test
 * asserts four properties that are <b>independent of the tables in this file</b>:
 *
 * <ol>
 *   <li>Every codeword polynomial is divisible by its Reed-Solomon generator — a property of the maths,
 *       not of my table.
 *   <li>The format and version bit strings are <b>computed</b> by their BCH codes and asserted against the
 *       published constants, so a wrong table and a wrong computation cannot agree by accident.
 *   <li>Total codewords per version equals the module count minus the function patterns, divided by eight
 *       — derived from the geometry this file actually places, so a wrong capacity table disagrees.
 *   <li>Finder, timing, alignment and the single dark module are asserted positionally.
 * </ol>
 *
 * <p>Those checks catch a wrong table, wrong arithmetic and wrong placement. **They cannot prove a reader
 * accepts it**; the only proof of that is a scan, and that is stated rather than implied.
 */
public final class QrCode {

    /** Level M. Recovers about 15% and is what every authenticator enrolment uses. */
    private static final int[] TOTAL_CODEWORDS =
            {0, 26, 44, 70, 100, 134, 172, 196, 242, 292, 346};
    private static final int[] EC_CODEWORDS_PER_BLOCK =
            {0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26};
    /** Block counts per version: {group1 blocks, group2 blocks}. Group 2 blocks hold one more codeword. */
    private static final int[][] BLOCKS = {
            {0, 0}, {1, 0}, {1, 0}, {1, 0}, {2, 0}, {2, 0}, {4, 0}, {4, 0}, {2, 2}, {3, 2}, {4, 1}};
    /** Alignment pattern centres. Version 1 has none. */
    private static final int[][] ALIGNMENT = {
            {}, {}, {6, 18}, {6, 22}, {6, 26}, {6, 30}, {6, 34},
            {6, 22, 38}, {6, 24, 42}, {6, 26, 46}, {6, 28, 50}};
    /** Remainder bits after the data region. Versions 2 to 6 carry seven; 1 and 7 to 13 carry none. */
    private static final int[] REMAINDER_BITS = {0, 0, 7, 7, 7, 7, 7, 0, 0, 0, 0};

    private static final int MAX_VERSION = 10;

    private QrCode() {
    }

    /**
     * Encodes to a module matrix.
     *
     * @return empty where the content does not fit version 10 at level M. The caller shows the setup key
     */
    public static Optional<boolean[][]> encode(String content) {
        Objects.requireNonNull(content, "content is required");
        byte[] data = content.getBytes(StandardCharsets.UTF_8);

        int version = -1;
        for (int candidate = 1; candidate <= MAX_VERSION; candidate++) {
            if (data.length <= dataCodewords(candidate) - headerBytes(candidate)) {
                version = candidate;
                break;
            }
        }
        if (version < 0) {
            return Optional.empty();
        }

        int[] codewords = interleave(version, encodeData(version, data));
        boolean[][] modules = place(version, codewords);
        return Optional.of(modules);
    }

    /** The number of data codewords a version holds at level M. */
    static int dataCodewords(int version) {
        return TOTAL_CODEWORDS[version] - ecCodewords(version);
    }

    static int ecCodewords(int version) {
        return EC_CODEWORDS_PER_BLOCK[version] * blockCount(version);
    }

    static int blockCount(int version) {
        return BLOCKS[version][0] + BLOCKS[version][1];
    }

    static int totalCodewords(int version) {
        return TOTAL_CODEWORDS[version];
    }

    static int size(int version) {
        return version * 4 + 17;
    }

    /** Four bits of mode plus the character count field, expressed in whole bytes for the fit check. */
    private static int headerBytes(int version) {
        // Mode indicator is 4 bits and the count field is 8 bits below version 10, 16 at 10 and above.
        // Rounded up to bytes: 2 for versions 1-9, 3 for version 10.
        return version < 10 ? 2 : 3;
    }

    // ---------------------------------------------------------------------------------------------- 

    /** Byte-mode bit stream, terminated and padded. */
    private static int[] encodeData(int version, byte[] data) {
        BitBuffer bits = new BitBuffer();
        bits.append(0b0100, 4);
        bits.append(data.length, version < 10 ? 8 : 16);
        for (byte b : data) {
            bits.append(b & 0xff, 8);
        }

        int capacityBits = dataCodewords(version) * 8;
        // Terminator: up to four zero bits, and fewer if there is no room. Writing four unconditionally
        // overflows a stream that ends exactly on capacity.
        bits.append(0, Math.min(4, capacityBits - bits.length()));
        while (bits.length() % 8 != 0) {
            bits.append(0, 1);
        }
        // Pad codewords alternate 0xEC and 0x11. Specified values, not arbitrary filler: a reader uses
        // them to distinguish padding from data that happens to be zero.
        boolean ec = true;
        while (bits.length() < capacityBits) {
            bits.append(ec ? 0xEC : 0x11, 8);
            ec = !ec;
        }
        return bits.toCodewords();
    }

    /**
     * Splits into blocks, computes error correction, and interleaves.
     *
     * <p>Interleaving is what makes the error correction useful: a scratch across the symbol damages
     * consecutive modules, and interleaving spreads those over every block so no single block exceeds its
     * correction capacity. A non-interleaved symbol survives a smaller scratch.
     */
    private static int[] interleave(int version, int[] dataCodewords) {
        int blocks = blockCount(version);
        int ecPerBlock = EC_CODEWORDS_PER_BLOCK[version];
        int shortBlocks = BLOCKS[version][0];
        int shortLength = dataCodewords(version) / blocks;

        List<int[]> dataBlocks = new ArrayList<>();
        List<int[]> ecBlocks = new ArrayList<>();
        int offset = 0;
        for (int block = 0; block < blocks; block++) {
            int length = shortLength + (block < shortBlocks ? 0 : 1);
            int[] chunk = new int[length];
            System.arraycopy(dataCodewords, offset, chunk, 0, length);
            offset += length;
            dataBlocks.add(chunk);
            ecBlocks.add(ReedSolomon.remainder(chunk, ecPerBlock));
        }

        int[] out = new int[TOTAL_CODEWORDS[version]];
        int index = 0;
        int longest = shortLength + (BLOCKS[version][1] > 0 ? 1 : 0);
        for (int position = 0; position < longest; position++) {
            for (int[] block : dataBlocks) {
                if (position < block.length) {
                    out[index++] = block[position];
                }
            }
        }
        for (int position = 0; position < ecPerBlock; position++) {
            for (int[] block : ecBlocks) {
                out[index++] = block[position];
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------- 

    /** Places function patterns, data, then chooses a mask by the specified penalty rules. */
    private static boolean[][] place(int version, int[] codewords) {
        int size = size(version);
        boolean[][] modules = new boolean[size][size];
        boolean[][] reserved = new boolean[size][size];

        drawFinder(modules, reserved, 0, 0);
        drawFinder(modules, reserved, size - 7, 0);
        drawFinder(modules, reserved, 0, size - 7);
        drawTiming(modules, reserved, size);
        drawAlignment(modules, reserved, version);
        reserveFormat(reserved, size, version);

        // The single dark module, at (8, size-8). Fixed by the specification, and a reader uses it to
        // confirm orientation.
        modules[size - 8][8] = true;
        reserved[size - 8][8] = true;

        BitBuffer bits = new BitBuffer();
        for (int codeword : codewords) {
            bits.append(codeword, 8);
        }
        for (int i = 0; i < REMAINDER_BITS[version]; i++) {
            bits.append(0, 1);
        }
        placeData(modules, reserved, size, bits);

        int bestPenalty = Integer.MAX_VALUE;
        boolean[][] best = null;
        for (int mask = 0; mask < 8; mask++) {
            boolean[][] candidate = copy(modules);
            applyMask(candidate, reserved, size, mask);
            drawFormat(candidate, size, mask);
            drawVersion(candidate, size, version);
            int penalty = penalty(candidate, size);
            if (penalty < bestPenalty) {
                bestPenalty = penalty;
                best = candidate;
            }
        }
        return best;
    }

    private static void drawFinder(boolean[][] modules, boolean[][] reserved, int x, int y) {
        for (int dy = -1; dy <= 7; dy++) {
            for (int dx = -1; dx <= 7; dx++) {
                int px = x + dx;
                int py = y + dy;
                if (py < 0 || py >= modules.length || px < 0 || px >= modules.length) {
                    continue;
                }
                boolean dark = dx >= 0 && dx <= 6 && dy >= 0 && dy <= 6
                        && (dx == 0 || dx == 6 || dy == 0 || dy == 6
                            || (dx >= 2 && dx <= 4 && dy >= 2 && dy <= 4));
                modules[py][px] = dark;
                reserved[py][px] = true;
            }
        }
    }

    private static void drawTiming(boolean[][] modules, boolean[][] reserved, int size) {
        for (int i = 8; i < size - 8; i++) {
            boolean dark = i % 2 == 0;
            if (!reserved[6][i]) {
                modules[6][i] = dark;
                reserved[6][i] = true;
            }
            if (!reserved[i][6]) {
                modules[i][6] = dark;
                reserved[i][6] = true;
            }
        }
    }

    private static void drawAlignment(boolean[][] modules, boolean[][] reserved, int version) {
        int[] centres = ALIGNMENT[version];
        if (centres.length == 0) {
            return;
        }
        int first = centres[0];
        int last = centres[centres.length - 1];
        for (int cy : centres) {
            for (int cx : centres) {
                // Skip only the THREE CORNERS, where the finder patterns already are.
                //
                // The guard used to be "already reserved", which also skipped every centre lying on a
                // timing line — (6, 24) and (24, 6) at version 8, for instance — because timing reserves
                // row and column 6. Versions 1 to 6 were unaffected, since their only non-corner centre
                // is off both lines; versions 7 to 10 silently lost two alignment patterns each, which
                // is the kind of defect that produces a symbol some readers accept and others do not.
                boolean corner = (cy == first && cx == first)
                        || (cy == first && cx == last)
                        || (cy == last && cx == first);
                if (corner) {
                    continue;
                }
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        boolean dark = Math.abs(dx) == 2 || Math.abs(dy) == 2
                                || (dx == 0 && dy == 0);
                        modules[cy + dy][cx + dx] = dark;
                        reserved[cy + dy][cx + dx] = true;
                    }
                }
            }
        }
    }

    private static void reserveFormat(boolean[][] reserved, int size, int version) {
        for (int i = 0; i < 9; i++) {
            reserved[8][i] = true;
            reserved[i][8] = true;
        }
        for (int i = 0; i < 8; i++) {
            reserved[8][size - 1 - i] = true;
            reserved[size - 1 - i][8] = true;
        }
        if (version >= 7) {
            for (int i = 0; i < 6; i++) {
                for (int j = 0; j < 3; j++) {
                    reserved[size - 11 + j][i] = true;
                    reserved[i][size - 11 + j] = true;
                }
            }
        }
    }

    /** Two-column zigzag from the bottom right, skipping the vertical timing column. */
    private static void placeData(boolean[][] modules, boolean[][] reserved, int size,
            BitBuffer bits) {
        int bit = 0;
        boolean upward = true;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) {
                right = 5;
            }
            for (int step = 0; step < size; step++) {
                int y = upward ? size - 1 - step : step;
                for (int column = 0; column < 2; column++) {
                    int x = right - column;
                    if (reserved[y][x]) {
                        continue;
                    }
                    modules[y][x] = bit < bits.length() && bits.get(bit);
                    bit++;
                }
            }
            upward = !upward;
        }
    }

    private static void applyMask(boolean[][] modules, boolean[][] reserved, int size, int mask) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (reserved[y][x]) {
                    continue;
                }
                boolean invert = switch (mask) {
                    case 0 -> (y + x) % 2 == 0;
                    case 1 -> y % 2 == 0;
                    case 2 -> x % 3 == 0;
                    case 3 -> (y + x) % 3 == 0;
                    case 4 -> (y / 2 + x / 3) % 2 == 0;
                    case 5 -> (y * x) % 2 + (y * x) % 3 == 0;
                    case 6 -> ((y * x) % 2 + (y * x) % 3) % 2 == 0;
                    default -> ((y + x) % 2 + (y * x) % 3) % 2 == 0;
                };
                if (invert) {
                    modules[y][x] = !modules[y][x];
                }
            }
        }
    }

    /** Format information: 5 data bits, a BCH(15,5) remainder, then a fixed XOR mask. */
    static int formatBits(int mask) {
        // Level M is 00, so the five data bits are 00 followed by the three mask bits.
        int data = mask;
        int remainder = data;
        for (int i = 0; i < 10; i++) {
            remainder = (remainder << 1) ^ ((remainder >>> 9) * 0x537);
        }
        return ((data << 10) | remainder) ^ 0x5412;
    }

    /** Version information: 6 data bits and a BCH(18,6) remainder. Versions 7 and above only. */
    static int versionBits(int version) {
        int remainder = version;
        for (int i = 0; i < 12; i++) {
            remainder = (remainder << 1) ^ ((remainder >>> 11) * 0x1F25);
        }
        return (version << 12) | remainder;
    }

    // The bit-to-module mapping of ISO/IEC 18004 figure 25. Both copies run LOW bits up column 8 and
    // HIGH bits along row 8 — and the earlier version of this method had both copies transposed,
    // writing the low bits along the row and the high bits up the column.
    //
    // Why that survived review and two rounds of tests: the level-M format values are very nearly
    // palindromic, so a transposed copy differs from a correct one in only three of fifteen modules.
    // The symbol looked right, the placement assertions (which asserted a module was WRITTEN, not
    // which bit landed there) passed, and a decoder written against the same misreading round-tripped
    // it perfectly. What it actually produced was format information that fails its own BCH check, so
    // every reader rejected the symbol before looking at a single data module — which is exactly what
    // Google Authenticator did.
    //
    // The mapping below was not reconstructed from memory a third time. It was derived by forcing all
    // eight masks through a reference encoder and solving, for each module, which bit index it agrees
    // with across all eight — see QrCodeTest#formatModulesCarryTheBitTheSpecificationAssigns.
    private static void drawFormat(boolean[][] modules, int size, int mask) {
        int bits = formatBits(mask);

        // Copy 1: bits 0-5 up column 8 (rows 0-5), bit 6 at (7,8), bit 7 at the corner (8,8),
        // bit 8 at (8,7), bits 9-14 leftward along row 8 (columns 5 down to 0).
        for (int i = 0; i <= 5; i++) {
            modules[i][8] = bit(bits, i);
        }
        modules[7][8] = bit(bits, 6);
        modules[8][8] = bit(bits, 7);
        modules[8][7] = bit(bits, 8);
        for (int i = 9; i <= 14; i++) {
            modules[8][14 - i] = bit(bits, i);
        }

        // Copy 2: bits 0-7 leftward along row 8 from the right edge (8 modules), then bits 8-14 down
        // column 8 from row size-7 (7 modules). 8 and 7, not 7 and 8.
        //
        // The ranges are what keep (size-8, 8) — the single dark module — out of both loops. An
        // earlier version reached it from the column half and overwrote it.
        for (int i = 0; i <= 7; i++) {
            modules[8][size - 1 - i] = bit(bits, i);
        }
        for (int i = 8; i <= 14; i++) {
            modules[size - 15 + i][8] = bit(bits, i);
        }
    }

    private static void drawVersion(boolean[][] modules, int size, int version) {
        if (version < 7) {
            return;
        }
        int bits = versionBits(version);
        for (int i = 0; i < 18; i++) {
            boolean dark = bit(bits, i);
            int a = i / 3;
            int b = i % 3;
            modules[size - 11 + b][a] = dark;
            modules[a][size - 11 + b] = dark;
        }
    }

    private static boolean bit(int value, int index) {
        return ((value >>> index) & 1) == 1;
    }

    /** The four penalty rules of the specification. Lower is better. */
    private static int penalty(boolean[][] modules, int size) {
        int total = 0;

        // N1: runs of five or more.
        for (int i = 0; i < size; i++) {
            total += runPenalty(modules, size, i, true);
            total += runPenalty(modules, size, i, false);
        }
        // N2: 2x2 blocks of one colour.
        for (int y = 0; y < size - 1; y++) {
            for (int x = 0; x < size - 1; x++) {
                boolean c = modules[y][x];
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) {
                    total += 3;
                }
            }
        }
        // N3: the finder-like 1:1:3:1:1 pattern with four light modules on either side. Penalised
        // heavily because a reader hunting for finder patterns can lock onto one of these instead.
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                total += finderLike(modules, size, y, x, true) ? 40 : 0;
                total += finderLike(modules, size, y, x, false) ? 40 : 0;
            }
        }
        // N4: deviation from an even light/dark balance.
        int dark = 0;
        for (boolean[] row : modules) {
            for (boolean module : row) {
                if (module) {
                    dark++;
                }
            }
        }
        int percent = dark * 100 / (size * size);
        total += Math.abs(percent - 50) / 5 * 10;
        return total;
    }

    private static int runPenalty(boolean[][] modules, int size, int line, boolean horizontal) {
        int total = 0;
        int run = 1;
        for (int i = 1; i < size; i++) {
            boolean current = horizontal ? modules[line][i] : modules[i][line];
            boolean previous = horizontal ? modules[line][i - 1] : modules[i - 1][line];
            if (current == previous) {
                run++;
            } else {
                if (run >= 5) {
                    total += 3 + (run - 5);
                }
                run = 1;
            }
        }
        if (run >= 5) {
            total += 3 + (run - 5);
        }
        return total;
    }

    private static final boolean[] FINDER = {true, false, true, true, true, false, true};

    private static boolean finderLike(boolean[][] modules, int size, int y, int x,
            boolean horizontal) {
        if (horizontal ? x + 7 > size : y + 7 > size) {
            return false;
        }
        for (int i = 0; i < 7; i++) {
            boolean module = horizontal ? modules[y][x + i] : modules[y + i][x];
            if (module != FINDER[i]) {
                return false;
            }
        }
        // The line the run lies along, and where along it the run starts. Horizontal runs vary x on a
        // fixed y; vertical runs vary y on a fixed x — and conflating the two was the bug this
        // rewrite removed.
        // Four light modules on one side or the other.
        int line = horizontal ? y : x;
        int start = horizontal ? x : y;
        return light(modules, size, line, start - 4, 4, horizontal)
                || light(modules, size, line, start + 7, 4, horizontal);
    }

    /**
     * Whether {@code count} modules from {@code start} along the line are all light.
     *
     * <p>Out of bounds counts as light: the quiet zone is light, and the specification's finder-like
     * penalty is about what a reader sees, which includes the margin.
     */
    private static boolean light(boolean[][] modules, int size, int line, int start, int count,
            boolean horizontal) {
        for (int i = 0; i < count; i++) {
            int position = start + i;
            if (position < 0 || position >= size) {
                continue;
            }
            boolean module = horizontal ? modules[line][position] : modules[position][line];
            if (module) {
                return false;
            }
        }
        return true;
    }

    private static boolean[][] copy(boolean[][] source) {
        boolean[][] out = new boolean[source.length][];
        for (int i = 0; i < source.length; i++) {
            out[i] = source[i].clone();
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------- 

    /** A bit stream that packs to codewords. */
    private static final class BitBuffer {

        private final List<Boolean> bits = new ArrayList<>();

        void append(int value, int width) {
            for (int i = width - 1; i >= 0; i--) {
                bits.add(((value >>> i) & 1) == 1);
            }
        }

        int length() {
            return bits.size();
        }

        boolean get(int index) {
            return bits.get(index);
        }

        int[] toCodewords() {
            int[] out = new int[bits.size() / 8];
            for (int i = 0; i < out.length; i++) {
                int value = 0;
                for (int b = 0; b < 8; b++) {
                    value = (value << 1) | (bits.get(i * 8 + b) ? 1 : 0);
                }
                out[i] = value;
            }
            return out;
        }
    }

    /** Reed-Solomon over GF(256) with the QR primitive polynomial. */
    static final class ReedSolomon {

        private static final int[] EXP = new int[512];
        private static final int[] LOG = new int[256];

        static {
            int value = 1;
            for (int i = 0; i < 255; i++) {
                EXP[i] = value;
                LOG[value] = i;
                value <<= 1;
                if ((value & 0x100) != 0) {
                    value ^= 0x11D;
                }
            }
            for (int i = 255; i < 512; i++) {
                EXP[i] = EXP[i - 255];
            }
        }

        private ReedSolomon() {
        }

        static int multiply(int a, int b) {
            return a == 0 || b == 0 ? 0 : EXP[LOG[a] + LOG[b]];
        }

        /** The generator polynomial for a given number of error correction codewords. */
        static int[] generator(int degree) {
            int[] result = {1};
            for (int i = 0; i < degree; i++) {
                int[] next = new int[result.length + 1];
                for (int j = 0; j < result.length; j++) {
                    next[j] ^= result[j];
                    next[j + 1] ^= multiply(result[j], EXP[i]);
                }
                result = next;
            }
            return result;
        }

        /** The remainder of the data polynomial divided by the generator — the EC codewords. */
        static int[] remainder(int[] data, int degree) {
            int[] generator = generator(degree);
            int[] remainder = new int[degree];
            for (int codeword : data) {
                int factor = codeword ^ remainder[0];
                System.arraycopy(remainder, 1, remainder, 0, degree - 1);
                remainder[degree - 1] = 0;
                for (int i = 0; i < degree; i++) {
                    remainder[i] ^= multiply(generator[i + 1], factor);
                }
            }
            return remainder;
        }
    }

    // ---------------------------------------------------------------------------------------------- 

    /**
     * Renders a module matrix as inline SVG.
     *
     * <p>One path rather than a rectangle per module: a version 10 symbol is 57 by 57, and two thousand
     * elements is markup a browser lays out slowly for no benefit. The quiet zone is four modules, which
     * the specification requires — a symbol rendered flush to its border is one many readers refuse.
     */
    public static String toSvg(boolean[][] modules, String accessibleName) {
        int size = modules.length;
        int quiet = 4;
        int extent = size + quiet * 2;
        StringBuilder path = new StringBuilder();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (modules[y][x]) {
                    path.append('M').append(x + quiet).append(' ').append(y + quiet).append("h1v1h-1z");
                }
            }
        }
        return "<svg class=\"qr\" viewBox=\"0 0 " + extent + " " + extent + "\" role=\"img\" "
                + "aria-label=" + Html.attribute(accessibleName)
                + " shape-rendering=\"crispEdges\">"
                // A white plate under the code, always. A dark theme with a transparent background makes
                // the light modules dark and the symbol unreadable, and a QR is not a themable surface.
                + "<rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>"
                + "<path d=\"" + path + "\" fill=\"#000000\"/></svg>";
    }
}
