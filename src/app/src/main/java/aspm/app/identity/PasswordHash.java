package aspm.app.identity;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/**
 * Argon2id password hashing. {@code SEC-SEC-014}.
 *
 * <p>"Credentials MUST be stored using a <b>memory-hard</b> password hashing function with
 * per-credential salt and parameters tuned to a target verification cost, and parameters MUST be stored
 * alongside."
 *
 * <p>The JDK offers PBKDF2, which is iteration-hard and not memory-hard: an attacker with a GPU or an
 * ASIC gains far more from parallelism against PBKDF2 than against Argon2id, and the requirement names
 * the property rather than an algorithm. Shipping PBKDF2 and recording ASVS Level 3 conformance would be
 * the documented-conformance failure that level exists to prevent.
 *
 * <h2>Parameters travel with the hash</h2>
 *
 * <p>Stored per credential, not globally. A credential verified under old parameters is re-hashed on the
 * next successful sign-in, so the cost can be raised without invalidating anything — and a global
 * parameter set forces a mass reset, which is why nobody ever raises it.
 *
 * <p>The comparison is constant-time. A byte-by-byte early return leaks the hash one byte at a time to
 * an attacker who can measure it, and this is the one comparison in the platform where that matters.
 */
public final class PasswordHash {

    /**
     * Current parameters. OWASP's Argon2id guidance for the second-factor-protected case is 19 MiB and
     * two passes as a <b>minimum</b>; 64 MiB and three are chosen here because verification happens once
     * per sign-in rather than per request, so the cost is affordable where it is not for a hot path.
     */
    public static final int MEMORY_KIB = 65536;
    public static final int ITERATIONS = 3;
    public static final int PARALLELISM = 1;
    public static final int SALT_LENGTH = 16;
    public static final int HASH_LENGTH = 32;
    public static final String ALGORITHM = "ARGON2ID";

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * A stored credential: the hash and the parameters it was produced with.
     *
     * <p>A class rather than a record, and Error Prone's {@code ArrayRecordComponent} is the reason: a
     * record with array components gets an {@code equals} that compares references. Two credentials with
     * identical bytes would compare unequal, and — worse — somebody would eventually use that
     * {@code equals} to check a password. Comparison here goes through
     * {@code MessageDigest.isEqual} and nothing else.
     */
    public static final class Stored {

        private final String algorithm;
        private final int memoryKib;
        private final int iterations;
        private final int parallelism;
        private final byte[] salt;
        private final byte[] hash;

        public Stored(String algorithm, int memoryKib, int iterations, int parallelism,
                byte[] salt, byte[] hash) {
            this.algorithm = Objects.requireNonNull(algorithm, "an algorithm is required");
            this.memoryKib = memoryKib;
            this.iterations = iterations;
            this.parallelism = parallelism;
            this.salt = salt.clone();
            this.hash = hash.clone();
        }

        public String algorithm() {
            return algorithm;
        }

        public int memoryKib() {
            return memoryKib;
        }

        public int iterations() {
            return iterations;
        }

        public int parallelism() {
            return parallelism;
        }

        public byte[] salt() {
            return salt.clone();
        }

        public byte[] hash() {
            return hash.clone();
        }

        /** Whether this credential was produced below the current cost and should be re-hashed. */
        public boolean belowCurrentCost() {
            return memoryKib < MEMORY_KIB || iterations < ITERATIONS;
        }

        /**
         * Deliberately not the bytes. A credential appearing in a log line is the credential in the log,
         * and a default toString is how that happens.
         */
        @Override
        public String toString() {
            return "Stored[" + algorithm + " m=" + memoryKib + " t=" + iterations
                    + " p=" + parallelism + "]";
        }
    }

    private PasswordHash() {
    }

    public static Stored hash(char[] password) {
        Objects.requireNonNull(password, "a password is required");
        byte[] salt = new byte[SALT_LENGTH];
        RANDOM.nextBytes(salt);
        return new Stored(ALGORITHM, MEMORY_KIB, ITERATIONS, PARALLELISM, salt,
                derive(password, salt, MEMORY_KIB, ITERATIONS, PARALLELISM));
    }

    /**
     * Verifies a password against a stored credential.
     *
     * <p>Uses the parameters <b>from the stored credential</b>, not the current ones. Verifying with
     * today's parameters against a hash produced with yesterday's would fail every existing credential
     * the moment the cost is raised — which is the failure mode that makes teams never raise it.
     */
    public static boolean verify(char[] password, Stored stored) {
        Objects.requireNonNull(stored, "a stored credential is required");
        byte[] candidate = derive(password, stored.salt(), stored.memoryKib(), stored.iterations(),
                stored.parallelism());
        try {
            return java.security.MessageDigest.isEqual(candidate, stored.hash());
        } finally {
            Arrays.fill(candidate, (byte) 0);
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int memoryKib, int iterations,
            int parallelism) {
        Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(memoryKib)
                .withIterations(iterations)
                .withParallelism(parallelism)
                .withSalt(salt)
                .build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(parameters);
        byte[] out = new byte[HASH_LENGTH];
        generator.generateBytes(password, out);
        return out;
    }

    /** A cryptographically random token, url-safe. Used for sessions and reset links. */
    public static String randomToken(int bytes) {
        byte[] raw = new byte[bytes];
        RANDOM.nextBytes(raw);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /**
     * SHA-256 of a token, for storage.
     *
     * <p>A session table holding raw tokens is a table of live bearer credentials, and the operator with
     * database access is inside this platform's threat model. Hashing is not slow here on purpose: a
     * 256-bit random token has no guessable preimage, so the work factor a password needs would buy
     * nothing and would put Argon2 on the request path.
     */
    public static byte[] tokenHash(String token) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every supported JDK", e);
        }
    }
}
