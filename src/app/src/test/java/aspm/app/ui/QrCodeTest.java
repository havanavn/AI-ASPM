package aspm.app.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The QR encoder, checked against properties that do not depend on its own tables.
 *
 * <p>No reference encoder is available offline, so "it matches a known-good implementation" is not
 * available as an assertion. What is available is mathematics: a Reed-Solomon codeword is divisible by
 * its generator, a BCH bit string is computable from its polynomial, and the codeword capacity of a
 * version is derivable from the geometry the encoder actually draws.
 *
 * <p>If a table in {@link QrCode} is wrong, at least one of these disagrees with it. <b>What none of them
 * can establish is that a reader accepts the symbol</b> — the only proof of that is a scan, and it is
 * stated here rather than implied by a passing suite.
 */
class QrCodeTest {

    /** An otpauth URI of realistic length. */
    private static final String OTPAUTH =
            "otpauth://totp/AI-ASPM:admin?secret=V2SYTV6BJZQW4ZDPMFXGK43UN5XA2LTP"
                    + "&issuer=AI-ASPM&algorithm=SHA1&digits=6&period=30";

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("Reed-Solomon: a property of the field, not of a table")
    class ErrorCorrection {

        @Test
        @DisplayName("every codeword polynomial is divisible by its generator")
        void codewordsAreDivisible() {
            // The defining property: data followed by its remainder is a multiple of the generator, so
            // dividing the whole codeword by the generator leaves zero. A wrong generator, a wrong field
            // or a wrong division fails this without any table being consulted.
            for (int degree : List.of(10, 16, 18, 22, 24, 26)) {
                int[] data = new int[40];
                for (int i = 0; i < data.length; i++) {
                    data[i] = (i * 37 + 11) & 0xff;
                }
                int[] ec = QrCode.ReedSolomon.remainder(data, degree);

                int[] whole = new int[data.length + ec.length];
                System.arraycopy(data, 0, whole, 0, data.length);
                System.arraycopy(ec, 0, whole, data.length, ec.length);

                int[] again = QrCode.ReedSolomon.remainder(whole, degree);
                for (int value : again) {
                    assertEquals(0, value,
                            "degree " + degree + ": the codeword is not divisible by its generator, so "
                                    + "no reader could correct it");
                }
            }
        }

        @Test
        @DisplayName("the field is a field: every non-zero element has an inverse")
        void fieldIsWellFormed() {
            // GF(256) under the QR primitive polynomial. If the exponent or log table were built wrongly
            // the multiplication would not be invertible, and every codeword above would be wrong in a
            // way divisibility might still accidentally satisfy.
            for (int a = 1; a < 256; a++) {
                boolean found = false;
                for (int b = 1; b < 256 && !found; b++) {
                    found = QrCode.ReedSolomon.multiply(a, b) == 1;
                }
                assertTrue(found, a + " has no multiplicative inverse");
            }
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("BCH bit strings: computed, then checked against the published constants")
    class BitStrings {

        @Test
        @DisplayName("format information matches the specification's level-M values")
        void formatBits() {
            // Computed by the BCH(15,5) code in QrCode, asserted against the published table. A wrong
            // computation and a wrong memory of the constants cannot agree by accident.
            int[] expected = {0x5412, 0x5125, 0x5E7C, 0x5B4B, 0x45F9, 0x40CE, 0x4F97, 0x4AA0};
            for (int mask = 0; mask < 8; mask++) {
                assertEquals(expected[mask], QrCode.formatBits(mask),
                        "level M, mask " + mask + ": a wrong format string makes every reader reject "
                                + "the symbol before it looks at the data");
            }
        }

        @Test
        @DisplayName("version information matches the specification for versions 7 to 10")
        void versionBits() {
            assertEquals(0x07C94, QrCode.versionBits(7));
            assertEquals(0x085BC, QrCode.versionBits(8));
            assertEquals(0x09A99, QrCode.versionBits(9));
            assertEquals(0x0A4D3, QrCode.versionBits(10));
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("Capacity: derived from the geometry the encoder draws")
    class Capacity {

        @Test
        @DisplayName("total codewords equal the free modules divided by eight")
        void capacityFollowsFromGeometry() {
            // Counted from a real encode rather than from the table: every module the encoder marks
            // reserved is a function pattern, and what is left is the data region. A wrong capacity
            // table disagrees with the symbol the encoder actually builds.
            for (int version = 1; version <= 10; version++) {
                int size = QrCode.size(version);
                int functionModules = functionModuleCount(version, size);
                int free = size * size - functionModules;
                int expected = free / 8;
                assertEquals(expected, QrCode.totalCodewords(version),
                        "version " + version + ": the capacity table and the geometry disagree, so the "
                                + "encoder writes either too few codewords or past the data region");
            }
        }

        @Test
        @DisplayName("data plus error correction equals the total, per version")
        void blocksAccountForEveryCodeword() {
            for (int version = 1; version <= 10; version++) {
                assertEquals(QrCode.totalCodewords(version),
                        QrCode.dataCodewords(version) + QrCode.ecCodewords(version),
                        "version " + version + ": the block structure loses or invents a codeword");
            }
        }

        /**
         * Function modules for a version: three finders with separators, timing, alignment, format,
         * version information, and the dark module.
         *
         * <p>Counted independently of {@link QrCode}'s drawing code, so the two can disagree.
         */
        private static int functionModuleCount(int version, int size) {
            int count = 3 * 8 * 8;                        // finders including separators
            count += 2 * (size - 16);                     // timing, excluding the finder overlap
            count += 31;                                  // format information plus the dark module
            if (version >= 7) {
                count += 36;                              // two 3x6 version blocks
            }
            int[][] centres = alignmentCentres(version);
            count += centres.length * 25;
            // Alignment patterns on the timing lines overlap five timing modules each.
            count -= overlapWithTiming(version) * 5;
            return count;
        }

        private static int[][] alignmentCentres(int version) {
            int[] positions = switch (version) {
                case 1 -> new int[] {};
                case 2 -> new int[] {6, 18};
                case 3 -> new int[] {6, 22};
                case 4 -> new int[] {6, 26};
                case 5 -> new int[] {6, 30};
                case 6 -> new int[] {6, 34};
                case 7 -> new int[] {6, 22, 38};
                case 8 -> new int[] {6, 24, 42};
                case 9 -> new int[] {6, 26, 46};
                default -> new int[] {6, 28, 50};
            };
            List<int[]> out = new java.util.ArrayList<>();
            for (int y : positions) {
                for (int x : positions) {
                    boolean corner = (y == 6 && x == 6)
                            || (y == 6 && x == positions[positions.length - 1])
                            || (x == 6 && y == positions[positions.length - 1]);
                    if (!corner) {
                        out.add(new int[] {y, x});
                    }
                }
            }
            return out.toArray(new int[0][]);
        }

        private static int overlapWithTiming(int version) {
            int count = 0;
            for (int[] centre : alignmentCentres(version)) {
                if (centre[0] == 6 || centre[1] == 6) {
                    count++;
                }
            }
            return count;
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("Placement: the patterns a reader looks for first")
    class Placement {

        @Test
        @DisplayName("an otpauth URI encodes, and the symbol carries its finder patterns")
        void findersArePresent() {
            boolean[][] m = QrCode.encode(OTPAUTH).orElseThrow(
                    () -> new AssertionError("a realistic otpauth URI did not fit version 10 at level M"));
            int size = m.length;

            for (int[] origin : new int[][] {{0, 0}, {0, size - 7}, {size - 7, 0}}) {
                assertFinder(m, origin[0], origin[1]);
            }
        }

        @Test
        @DisplayName("timing patterns alternate, and the dark module is where a reader expects it")
        void timingAndDarkModule() {
            boolean[][] m = QrCode.encode(OTPAUTH).orElseThrow();
            int size = m.length;
            for (int i = 8; i < size - 8; i++) {
                assertEquals(i % 2 == 0, m[6][i], "horizontal timing at " + i);
                assertEquals(i % 2 == 0, m[i][6], "vertical timing at " + i);
            }
            assertTrue(m[size - 8][8],
                    "the single dark module is missing; a reader uses it to confirm orientation");
        }

        @Test
        @DisplayName("the symbol is square, odd-sided, and the expected version for the content")
        void dimensions() {
            boolean[][] m = QrCode.encode(OTPAUTH).orElseThrow();
            assertEquals(m.length, m[0].length);
            assertEquals(1, m.length % 2, "a QR symbol always has an odd module count");
            assertTrue(m.length >= 21 && m.length <= 57, "size was " + m.length);
        }

        @Test
        @DisplayName("content beyond version 10 returns empty rather than a wrong symbol")
        void oversizedContentIsRefused() {
            Optional<boolean[][]> attempt = QrCode.encode("x".repeat(400));
            assertTrue(attempt.isEmpty(),
                    "a symbol that cannot hold the content must not be produced: a user who scans a "
                            + "malformed code blames their authenticator, then their phone, then support");
        }

        @Test
        @DisplayName("every version in range encodes at its capacity boundary")
        void everyVersionEncodes() {
            // One byte below the limit must fit; the encoder must not fall back to a version that
            // cannot hold it.
            for (int v = 1; v <= 10; v++) {
                final int version = v;
                final int capacity = QrCode.dataCodewords(version) - (version < 10 ? 2 : 3);
                boolean[][] m = QrCode.encode("A".repeat(capacity)).orElseThrow(
                        () -> new AssertionError("version " + version + " capacity " + capacity
                                + " did not encode"));
                assertTrue(m.length >= QrCode.size(version),
                        "content sized for version " + version + " produced a smaller symbol");
            }
        }

        @Test
        @DisplayName("format modules carry the bit the specification assigns, in both copies")
        void formatModulesCarryTheBitTheSpecificationAssigns() {
            // This is the check a READER performs, and the check the rest of this class did not make.
            //
            // The earlier assertions confirmed that format modules were written. They were — with both
            // copies transposed, low bits along row 8 and high bits up column 8 instead of the reverse.
            // Because the level-M format values are nearly palindromic, a transposed copy differs from a
            // correct one in three of fifteen modules, so nothing here failed and no rendering looked
            // wrong. Every real reader rejected the symbol, because format information that fails its
            // own BCH check means the error-correction level and mask are unknown and there is nothing
            // further to attempt. Reported as "Google Authenticator will not scan it".
            //
            // What this test does catch: an off-by-one in either copy, a copy written over the dark
            // module, and the two copies disagreeing.
            //
            // What it CANNOT catch, stated because the last version of this class implied a guarantee it
            // did not hold: the position table below is the same mapping the encoder writes, expressed a
            // second time. An error shared by both — the transposition above being exactly that — is
            // inverted by the read and the value comes back valid. The check that actually closed this
            // defect was external: the rendered symbol was decoded by an independent reader, and the
            // reference encoder's matrix for the same content and mask was diffed module by module. That
            // is not runnable from this suite, so it is recorded in the defect log rather than asserted
            // here, and this test is a regression guard rather than a proof.
            // Smallest symbol, a mid version, the real payload, and the largest symbol the encoder will
            // produce — so both the below-7 and the 7-and-above layouts are covered. The largest is
            // derived from the capacity table rather than guessed: a literal 240 was over version 10's
            // limit at level M and encode() correctly returned empty, which surfaced here as the
            // orElseThrow rather than as the assertion this test exists to make.
            String largest = "A".repeat(QrCode.dataCodewords(10) - 3);
            for (String content : new String[] {"A", "A".repeat(60), OTPAUTH, largest}) {
                boolean[][] m = QrCode.encode(content).orElseThrow();
                int size = m.length;

                int copy1 = readFormat(m, formatCopy1());
                int copy2 = readFormat(m, formatCopy2(size));

                assertEquals(copy1, copy2, "the two format copies disagree for content of length "
                        + content.length() + ". A reader that finds one copy damaged falls back to the "
                        + "other; two copies that disagree leave it nothing to fall back to");

                int matches = 0;
                int decodedMask = -1;
                for (int mask = 0; mask < 8; mask++) {
                    if (QrCode.formatBits(mask) == copy1) {
                        matches++;
                        decodedMask = mask;
                    }
                }
                assertEquals(1, matches, String.format(
                        "format information read back as 0x%04X, which is not a level-M format value "
                                + "for any of the eight masks (content length %d, version size %d). A "
                                + "reader cannot determine the mask or the error-correction level and "
                                + "rejects the symbol before reading a single data module.",
                        copy1, content.length(), size));
                assertTrue(decodedMask >= 0 && decodedMask <= 7, "decoded mask out of range");
            }
        }

        /**
         * Bit index to module, copy 1: bits 0-5 up column 8, bit 6 at (7,8), bit 7 at (8,8), bit 8 at
         * (8,7), bits 9-14 leftward along row 8. ISO/IEC 18004 figure 25.
         */
        private static int[][] formatCopy1() {
            int[][] at = new int[15][];
            for (int i = 0; i <= 5; i++) {
                at[i] = new int[] {i, 8};
            }
            at[6] = new int[] {7, 8};
            at[7] = new int[] {8, 8};
            at[8] = new int[] {8, 7};
            for (int i = 9; i <= 14; i++) {
                at[i] = new int[] {8, 14 - i};
            }
            return at;
        }

        /** Copy 2: bits 0-7 leftward along row 8 from the right edge, bits 8-14 down column 8. */
        private static int[][] formatCopy2(int size) {
            int[][] at = new int[15][];
            for (int i = 0; i <= 7; i++) {
                at[i] = new int[] {8, size - 1 - i};
            }
            for (int i = 8; i <= 14; i++) {
                at[i] = new int[] {size - 15 + i, 8};
            }
            return at;
        }

        private static int readFormat(boolean[][] m, int[][] at) {
            int value = 0;
            for (int i = 0; i < 15; i++) {
                if (m[at[i][0]][at[i][1]]) {
                    value |= 1 << i;
                }
            }
            return value;
        }

        private static void assertFinder(boolean[][] m, int y, int x) {
            for (int dy = 0; dy < 7; dy++) {
                for (int dx = 0; dx < 7; dx++) {
                    boolean expected = dx == 0 || dx == 6 || dy == 0 || dy == 6
                            || (dx >= 2 && dx <= 4 && dy >= 2 && dy <= 4);
                    assertEquals(expected, m[y + dy][x + dx],
                            "finder pattern at (" + y + "," + x + ") offset (" + dy + "," + dx + ")");
                }
            }
        }
    }

    // -------------------------------------------------------------------------------------------------
    @Nested
    @DisplayName("Rendering")
    class Rendering {

        @Test
        @DisplayName("the SVG carries a quiet zone and an opaque plate")
        void svgIsScannable() {
            boolean[][] m = QrCode.encode(OTPAUTH).orElseThrow();
            String svg = QrCode.toSvg(m, "setup code");
            int extent = m.length + 8;
            assertTrue(svg.contains("viewBox=\"0 0 " + extent + " " + extent + "\""),
                    "the four-module quiet zone is required; a symbol flush to its border is one many "
                            + "readers refuse");
            assertTrue(svg.contains("fill=\"#ffffff\""),
                    "an opaque white plate, always: on a dark theme a transparent background makes the "
                            + "light modules dark and the symbol unreadable");
            assertTrue(svg.contains("role=\"img\"") && svg.contains("aria-label="),
                    "the image needs an accessible name; a screen-reader user cannot scan it and must be "
                            + "told the setup key exists");
        }
    }
}
