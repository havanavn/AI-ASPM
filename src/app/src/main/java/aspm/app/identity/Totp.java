package aspm.app.identity;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * TOTP, RFC 6238. ADR-059, {@code SEC-SEC-003}.
 *
 * <p>Implemented rather than depended upon: it is HMAC plus a truncation, both in the JDK, and a
 * dependency for thirty lines would add a coordinate to track for the rest of the product's life.
 *
 * <h2>Two things this gets right that a naive implementation does not</h2>
 *
 * <ol>
 *   <li><b>A window, but a small one.</b> One step either side, because clocks drift and a user who
 *       types a code as it rolls over should not be told they are wrong. Not three steps: each step
 *       widened is 30 more seconds in which a captured code works.
 *   <li><b>Replay refusal within the step.</b> RFC 6238 §5.2 requires the verifier to reject a code it
 *       has already accepted. Without it a code observed over the user's shoulder — or lifted from a
 *       phishing proxy — remains valid for the rest of its window, which is the whole window that
 *       matters. {@link #verify} returns the accepted step so the caller can persist it.
 * </ol>
 */
public final class Totp {

    public static final int DIGITS = 6;
    public static final int PERIOD_SECONDS = 30;
    /** One step either side. See the class note on why not more. */
    private static final int DRIFT_STEPS = 1;
    private static final int SECRET_BYTES = 20;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] BASE32 =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    /** The outcome of a verification. {@code acceptedStep} is present only where it succeeded. */
    public record Result(boolean valid, long acceptedStep) {
    }

    private Totp() {
    }

    /** A new shared secret, base32-encoded as authenticator applications expect. */
    public static String newSecret() {
        byte[] raw = new byte[SECRET_BYTES];
        RANDOM.nextBytes(raw);
        return base32(raw);
    }

    /**
     * The {@code otpauth://} URI an authenticator scans.
     *
     * <p>The issuer and the account are both included. An enrolment that omits the issuer produces an
     * entry a user cannot identify among a dozen others, and one that omits the account produces two
     * indistinguishable entries when they enrol a second identity.
     */
    public static String provisioningUri(String issuer, String account, String secret) {
        return "otpauth://totp/" + urlEncode(issuer) + ":" + urlEncode(account)
                + "?secret=" + secret + "&issuer=" + urlEncode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + PERIOD_SECONDS;
    }

    /**
     * Verifies a code.
     *
     * @param lastAcceptedStep the step already consumed, or null. A code at or below it is refused
     *     however correct it is — that refusal is the replay control
     */
    public static Result verify(String secret, String code, Instant at, Long lastAcceptedStep) {
        Objects.requireNonNull(secret, "a secret is required");
        if (code == null) {
            return new Result(false, 0);
        }
        String digits = code.replaceAll("\\s", "");
        if (!digits.matches("\\d{" + DIGITS + "}")) {
            return new Result(false, 0);
        }
        long step = at.getEpochSecond() / PERIOD_SECONDS;
        byte[] key = unbase32(secret);
        for (long offset = -DRIFT_STEPS; offset <= DRIFT_STEPS; offset++) {
            long candidate = step + offset;
            if (lastAcceptedStep != null && candidate <= lastAcceptedStep) {
                // Already consumed. Skipped rather than compared, so a replay does not even reach the
                // constant-time comparison and cannot be distinguished from a wrong code by timing.
                continue;
            }
            if (constantTimeEquals(digits, generate(key, candidate))) {
                return new Result(true, candidate);
            }
        }
        return new Result(false, 0);
    }

    /** The code for a step. Exposed for the enrolment page's own verification. */
    public static String generate(byte[] key, long step) {
        byte[] message = new byte[8];
        for (int i = 7; i >= 0; i--) {
            message[i] = (byte) (step & 0xff);
            step >>= 8;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] digest = mac.doFinal(message);
            int offset = digest[digest.length - 1] & 0x0f;
            int binary = ((digest[offset] & 0x7f) << 24)
                    | ((digest[offset + 1] & 0xff) << 16)
                    | ((digest[offset + 2] & 0xff) << 8)
                    | (digest[offset + 3] & 0xff);
            int modulo = (int) Math.pow(10, DIGITS);
            return String.format("%0" + DIGITS + "d", binary % modulo);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 is required by every supported JDK", e);
        }
    }

    public static byte[] decodeSecret(String secret) {
        return unbase32(secret);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String base32(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32[(buffer >> (bits - 5)) & 0x1f]);
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32[(buffer << (5 - bits)) & 0x1f]);
        }
        return out.toString();
    }

    private static byte[] unbase32(String encoded) {
        String clean = encoded.replaceAll("[^A-Za-z2-7]", "").toUpperCase(java.util.Locale.ROOT);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        String alphabet = new String(BASE32);
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            int value = alphabet.indexOf(c);
            if (value < 0) {
                continue;
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
