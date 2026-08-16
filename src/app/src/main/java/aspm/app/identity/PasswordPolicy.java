package aspm.app.identity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Password policy evaluation. {@code SEC-SEC-006}, and the {@code password_policy} table of V015.
 *
 * <h2>Length first, and no composition rules</h2>
 *
 * <p>There is no "must contain a symbol" check, and its absence is a decision rather than an omission.
 * ASVS and NIST both moved away from composition rules because they produce predictable substitutions —
 * {@code Password1!} satisfies every one of them — and because they drive reuse. The schema does not even
 * have columns for them: <b>a disabled setting is a setting somebody enables.</b>
 *
 * <h2>Breach checking reports its own coverage</h2>
 *
 * <p>{@code SEC-SEC-006} requires breached-credential checking "at set and at authentication". A check
 * against an empty corpus passes every password, which is indistinguishable from a check that works. So
 * {@link Result} carries how many entries the corpus holds, and the interface says when it is thin.
 */
public final class PasswordPolicy {

    /** The tenant's policy, as stored. */
    public record Settings(int minimumLength, int maximumLength, int reuseHistory,
            boolean breachCheckAtSet, boolean breachCheckAtAuthentication, int maximumAgeDays,
            boolean mfaRequiredForAll, int sessionAbsoluteSeconds, int sessionIdleSeconds) {

        /** The product defaults, for a tenant with no row yet. */
        public static Settings defaults() {
            return new Settings(12, 128, 5, true, true, 0, true, 28800, 1800);
        }
    }

    /** The outcome of evaluating a candidate password. */
    public record Result(boolean acceptable, List<String> failures, long corpusSize) {

        public boolean corpusThin() {
            return corpusSize < 1000;
        }
    }

    private PasswordPolicy() {
    }

    public static Settings load(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT minimum_length, maximum_length, reuse_history, breach_check_at_set, "
                        + "breach_check_at_authentication, maximum_age_days, mfa_required_for_all, "
                        + "session_absolute_seconds, session_idle_seconds FROM password_policy");
                ResultSet results = statement.executeQuery()) {
            if (!results.next()) {
                return Settings.defaults();
            }
            return new Settings(results.getInt(1), results.getInt(2), results.getInt(3),
                    results.getBoolean(4), results.getBoolean(5), results.getInt(6),
                    results.getBoolean(7), results.getInt(8), results.getInt(9));
        }
    }

    /**
     * Evaluates a candidate.
     *
     * @param principalId the principal the password is for, or null at set-up time. Used for the reuse
     *     check, which reads retired credentials — the reason V015 retires rather than deletes them
     */
    public static Result evaluate(Connection connection, Settings settings, char[] candidate,
            java.util.UUID principalId, String username, String email) throws SQLException {
        List<String> failures = new ArrayList<>();
        String password = new String(candidate);

        if (password.length() < settings.minimumLength()) {
            failures.add("TOO_SHORT:" + settings.minimumLength());
        }
        if (password.length() > settings.maximumLength()) {
            // An upper bound exists because Argon2 hashes whatever it is given, and a megabyte password
            // is a denial-of-service against the verifier rather than a strong secret.
            failures.add("TOO_LONG:" + settings.maximumLength());
        }
        // The identifier itself, and the obvious variations. Not a composition rule: a password equal to
        // the username survives every length and character-class test ever written.
        String lower = password.toLowerCase(Locale.ROOT);
        if (username != null && !username.isBlank() && lower.contains(username.toLowerCase(Locale.ROOT))) {
            failures.add("CONTAINS_USERNAME");
        }
        if (email != null && !email.isBlank()) {
            String local = email.substring(0, Math.max(1, email.indexOf('@')));
            if (local.length() >= 3 && lower.contains(local.toLowerCase(Locale.ROOT))) {
                failures.add("CONTAINS_EMAIL");
            }
        }

        long corpusSize = corpusSize(connection);
        if (settings.breachCheckAtSet() && isBreached(connection, password)) {
            failures.add("BREACHED");
        }

        if (principalId != null && settings.reuseHistory() > 0
                && reusesRecent(connection, principalId, candidate, settings.reuseHistory())) {
            failures.add("REUSED");
        }

        return new Result(failures.isEmpty(), List.copyOf(failures), corpusSize);
    }

    /**
     * Whether a password appears in the breach corpus.
     *
     * <p>SHA-1 is used because that is the format every published corpus is distributed in — it is a
     * lookup key here and not a security primitive, which is the one context where SHA-1 is still the
     * right answer. Split into a prefix and a suffix so the query is a range scan, the same shape a
     * hosted corpus API uses.
     */
    public static boolean isBreached(Connection connection, String password) throws SQLException {
        String sha1 = sha1Hex(password);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM breached_password WHERE password_sha1_prefix = ? "
                        + "AND password_sha1_suffix = ?")) {
            statement.setString(1, sha1.substring(0, 5));
            statement.setString(2, sha1.substring(5));
            try (ResultSet results = statement.executeQuery()) {
                return results.next();
            }
        }
    }

    public static long corpusSize(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT breach_corpus_size()");
                ResultSet results = statement.executeQuery()) {
            results.next();
            return results.getLong(1);
        }
    }

    /**
     * Whether the candidate matches one of the principal's recent credentials.
     *
     * <p>Verified against each retired hash rather than compared as text, because the text is not stored.
     * That makes the check cost N Argon2 verifications, which is why the history is bounded to 24 in the
     * schema and defaults to 5 — a reuse history of 100 would make a password change take a minute.
     */
    private static boolean reusesRecent(Connection connection, java.util.UUID principalId,
            char[] candidate, int history) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT algorithm, memory_kib, iterations, parallelism, salt, hash "
                        + "FROM principal_credential WHERE principal_id = ? "
                        + "ORDER BY set_at DESC LIMIT ?")) {
            statement.setObject(1, principalId);
            statement.setInt(2, history);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    PasswordHash.Stored stored = new PasswordHash.Stored(results.getString(1),
                            results.getInt(2), results.getInt(3), results.getInt(4),
                            results.getBytes(5), results.getBytes(6));
                    if (PasswordHash.verify(candidate, stored)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static String sha1Hex(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-1")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(40);
            for (byte b : digest) {
                out.append(String.format("%02X", b));
            }
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is required by every supported JDK", e);
        }
    }
}
