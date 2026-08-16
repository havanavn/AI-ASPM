package aspm.module.ingestion.domain;

import aspm.sharedkernel.PathNormalization;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Normalizes a code location so the fingerprint survives the changes DOC-16 section 7.1 requires it to.
 *
 * <p>The rescan corpus requires the same finding after: code reformatted so line numbers shift; a file moved or
 * renamed within the asset; a scanner version upgrade; and a different branch with the same code. Three of the
 * four are properties of what is <em>excluded</em> from the fingerprint, which {@link FingerprintInputs} already
 * enforces by rejecting undeclared inputs. The file-move case is this class's work.
 *
 * <p><b>Why the location cannot simply be dropped.</b> It is part of CODE identity: two different weaknesses in
 * one file must be distinct findings. What is dropped is the parts of a location that change while the code does
 * not — branch, build-agent prefix, directory, and case. What remains is the basename, and distinctness within a
 * file comes from the structural context hash, which describes the surrounding syntax rather than its position.
 *
 * <p><b>The honest limit, recorded rather than discovered by a rescan.</b> Basename plus structural context is
 * stable under a move and under reformatting. It is <em>not</em> stable under a rename that also changes the
 * surrounding code, and nothing available at ingestion time distinguishes "the same weakness, moved and edited"
 * from "a new weakness in a new file". DOC-03 section 10.2 asks for inputs that sit between the two failure
 * modes rather than for a scheme with neither, so this limit is stated here and in {@code src/README.md}.
 */
public final class CodeLocationNormalization {

    /**
     * Prefixes that are checkout or build-agent scaffolding rather than part of identity.
     *
     * <p>An absolute build-agent path differs between CI runs of the same commit, so a fingerprint including one
     * produces a new finding per CI provider — the "too specific" failure in its purest form, and one that looks
     * like a genuine increase in findings.
     */
    private static final List<String> STRIPPED_PREFIXES = List.of(
            "/github/workspace/", "/home/runner/work/", "/workspace/", "/builds/", "./", "/", "src/");

    private CodeLocationNormalization() {
        throw new AssertionError("not instantiable");
    }

    /**
     * Reduces a reported file path to the location component of a CODE fingerprint.
     *
     * <p>Takes no line number, and there is no overload that does. A caller cannot pass one "just for now".
     */
    public static String normalize(String reportedPath) {
        Objects.requireNonNull(reportedPath, "reportedPath is required; PRD-ING-021 forbids inferring one");

        String path = reportedPath.strip().replace('\\', '/');

        boolean stripped = true;
        while (stripped) {
            stripped = false;
            for (String prefix : STRIPPED_PREFIXES) {
                if (path.length() > prefix.length()
                        && path.regionMatches(true, 0, prefix, 0, prefix.length())) {
                    path = path.substring(prefix.length());
                    stripped = true;
                }
            }
        }

        // A move within the asset changes the directory and not the file, and DOC-16 section 7.1 requires
        // "file moved or renamed within the asset" to remain the same finding.
        int lastSlash = path.lastIndexOf('/');
        String basename = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        return basename.toLowerCase(Locale.ROOT);
    }

    /**
     * A hash over the surrounding syntax, insensitive to formatting.
     *
     * <p>This is what keeps two distinct weaknesses in one file distinct once the location is reduced to a
     * basename. A scanner normally supplies it; where it does not, this derives one.
     *
     * <p><b>Collapsing whitespace runs is not enough, and the rescan corpus is what established that.</b> The
     * first version of this method replaced runs of whitespace with a single space, which is the obvious reading
     * of "ignore formatting" and is insufficient: a reformatter does not only change the <em>amount</em> of
     * whitespace, it changes <em>where</em> whitespace is. Turning {@code find(String id)} into
     * {@code find( String id )} adds spaces that collapsing preserves, so DOC-16 section 7.1's first case —
     * "code reformatted; line numbers shift → same finding" — failed.
     *
     * <p>The fix removes whitespace adjacent to any non-word character, which is where a formatter puts it:
     * around parentheses, commas, operators and braces. Whitespace <em>between two word characters</em> is
     * preserved, because that is a token boundary — {@code int x} and {@code intx} are different code, and
     * removing all whitespace would merge them.
     *
     * <p>This is a derivation, not a parse. A token-level normalization would be stronger and needs a parser per
     * language; DOC-03 section 10.2 expects the scanner to supply the structural hash, and this is the fallback
     * for when it does not.
     */
    public static String structuralContextHash(String enclosingConstruct, String snippet) {
        Objects.requireNonNull(enclosingConstruct, "the enclosing construct is required");
        Objects.requireNonNull(snippet, "the snippet is required");

        String normalized = (enclosingConstruct + " " + snippet)
                .replaceAll("\\s+", " ")
                // Remove space where either neighbour is not a word character — a formatter's whitespace.
                .replaceAll("(?<=\\W) | (?=\\W)", "")
                .strip()
                .toLowerCase(Locale.ROOT);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                hex.append(Character.forDigit((hash[i] >> 4) & 0xF, 16))
                        .append(Character.forDigit(hash[i] & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable; finding identity cannot be computed", e);
        }
    }

    /**
     * Normalizes a runtime request path.
     *
     * <p>Delegates to {@link PathNormalization}, which lives in the shared kernel because asset identity needs
     * the same heuristic and neither module depends on the other. Two copies of a heuristic that must agree is
     * how they stop agreeing.
     */
    public static String normalizeRequestPath(String reportedPath) {
        return PathNormalization.normalizeRequestPath(reportedPath);
    }
}
