package aspm.module.compositionanalysis.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Per-ecosystem version ordering. {@code PRD-SBM-038}, and the six difficulty classes of DOC-22 section 6.3.
 *
 * <p>DOC-22 opens section 6.3 with "This is the most error-prone element of the module and deserves its own
 * treatment", and the six rows of its difficulty table each name what getting it wrong produces. They are worth
 * having in front of you while reading this class, because every branch below exists for one of them:
 *
 * <table>
 *   <caption>DOC-22 section 6.3</caption>
 *   <tr><th>Difficulty</th><th>Consequence of getting it wrong</th></tr>
 *   <tr><td>Pre-release ordering</td>
 *       <td>A vulnerable pre-release reported clean, or a fixed release reported vulnerable</td></tr>
 *   <tr><td>Epoch and revision components</td><td>Epoch ignored inverts the entire comparison</td></tr>
 *   <tr><td>Non-semantic schemes</td><td>Applying semantic rules produces arbitrary orderings</td></tr>
 *   <tr><td>Backported fixes</td>
 *       <td>The most consequential: a patched package reported vulnerable, producing findings the team cannot
 *       action and which destroy trust in the module</td></tr>
 *   <tr><td>Range boundary inclusivity</td>
 *       <td>Off-by-one at exactly the version most deployments are on</td></tr>
 *   <tr><td>Multiple disjoint affected ranges</td><td>Matching only the first misses the second entirely</td></tr>
 * </table>
 *
 * <h2>The comparator returns an Optional, not an int</h2>
 *
 * <p>{@code PRD-SBM-039}: where ordering cannot be determined, the platform records {@code INDETERMINATE}
 * "rather than asserting either presence or absence". An {@code int}-returning comparator has no way to say
 * <i>I do not know</i> — it must return a number, and every number is an assertion. Returning
 * {@code Optional.empty()} is what makes the honest answer expressible.
 *
 * <p>This class is pure and stateless. Ordering must be deterministic and versioned ({@code PRD-SBM-036}); a
 * comparator with state is one whose answer can depend on what it compared previously.
 */
public final class VersionOrdering {

    /**
     * The ordering rules version, recorded on every match run.
     *
     * <p>{@code PRD-SBM-036}: "Rules will improve, and improvement changes which components match. Without a
     * recorded version, a change in results cannot be distinguished from a change in the estate." Bumping this
     * is what tells a reader that a finding appearing today and not yesterday may be the matcher, not the code.
     */
    public static final int RULES_VERSION = 1;

    private VersionOrdering() {
    }

    /**
     * Compares two versions under an ecosystem's rules.
     *
     * @return negative, zero or positive as {@code left} orders before, equal to, or after {@code right}; empty
     *     where the ecosystem's ordering is not implemented or either version does not parse under its scheme
     */
    public static Optional<Integer> compare(Ecosystem ecosystem, String left, String right) {
        Objects.requireNonNull(ecosystem, "an ecosystem is required (PRD-SBM-038)");
        if (left == null || right == null || left.isBlank() || right.isBlank()) {
            return Optional.empty();
        }
        if (!ecosystem.orderable()) {
            // PRD-SBM-039. A default comparator here would be the "single comparison scheme across ecosystems"
            // PRD-SBM-038 forbids, wearing a fallback's clothing.
            return Optional.empty();
        }
        return switch (ecosystem) {
            case SEMVER, GOLANG -> compareSemver(left, right);
            case PYPI -> comparePep440(left, right);
            case MAVEN -> compareMaven(left, right);
            case DEB -> compareDebian(left, right);
            case RPM -> compareRpm(left, right);
            case UNKNOWN -> Optional.empty();
        };
    }

    // ---------------------------------------------------------------- semver

    /**
     * Semantic versioning, and Go modules which use it with a leading {@code v}.
     *
     * <p><b>Pre-release ordering.</b> {@code 1.0.0-alpha} orders BEFORE {@code 1.0.0}. This is the case DOC-22
     * names first, and getting it backwards reports "a fixed release as vulnerable" — because a range of
     * "affected below 1.0.0" would then exclude the pre-release that is affected.
     *
     * <p><b>Build metadata is ignored</b> for ordering, per the specification. Two versions differing only in
     * build metadata are the same version, and treating them as ordered would make {@code 1.0.0+build.1} and
     * {@code 1.0.0+build.2} straddle a range boundary.
     */
    private static Optional<Integer> compareSemver(String left, String right) {
        String l = stripGoPrefix(left);
        String r = stripGoPrefix(right);
        // Go pseudo-versions and +incompatible suffixes are build metadata for ordering purposes.
        l = beforeFirst(l, '+');
        r = beforeFirst(r, '+');

        String leftCore = beforeFirst(l, '-');
        String rightCore = beforeFirst(r, '-');
        Optional<Integer> core = compareNumericDotted(leftCore, rightCore, 3);
        if (core.isEmpty() || core.get() != 0) {
            return core;
        }

        String leftPre = afterFirst(l, '-');
        String rightPre = afterFirst(r, '-');
        boolean leftIsRelease = leftPre.isEmpty();
        boolean rightIsRelease = rightPre.isEmpty();
        if (leftIsRelease && rightIsRelease) {
            return Optional.of(0);
        }
        // A release orders AFTER any of its pre-releases.
        if (leftIsRelease) {
            return Optional.of(1);
        }
        if (rightIsRelease) {
            return Optional.of(-1);
        }
        return comparePreReleaseIdentifiers(leftPre, rightPre);
    }

    private static String stripGoPrefix(String version) {
        return version.startsWith("v") || version.startsWith("V") ? version.substring(1) : version;
    }

    /**
     * Dot-separated pre-release identifiers.
     *
     * <p>Numeric identifiers compare numerically and order <b>before</b> alphanumeric ones; a longer identifier
     * list orders after a shorter one that is otherwise equal. Both rules come from the specification and both
     * are easy to get wrong by comparing the whole string, which would put {@code alpha.10} before
     * {@code alpha.2}.
     */
    private static Optional<Integer> comparePreReleaseIdentifiers(String left, String right) {
        // Limit -1 keeps trailing empty segments. Without it "alpha." splits to ["alpha"] and compares equal
        // to "alpha", so a malformed version silently becomes a well-formed one — the exact class of surprise
        // Error Prone's StringSplitter warns about, and it matters here because the result is a match verdict.
        String[] l = left.split("\\.", -1);
        String[] r = right.split("\\.", -1);
        for (int i = 0; i < Math.max(l.length, r.length); i++) {
            if (i >= l.length) {
                return Optional.of(-1);
            }
            if (i >= r.length) {
                return Optional.of(1);
            }
            boolean leftNumeric = isNumeric(l[i]);
            boolean rightNumeric = isNumeric(r[i]);
            if (leftNumeric && rightNumeric) {
                int c = new java.math.BigInteger(l[i]).compareTo(new java.math.BigInteger(r[i]));
                if (c != 0) {
                    return Optional.of(Integer.signum(c));
                }
            } else if (leftNumeric) {
                return Optional.of(-1);
            } else if (rightNumeric) {
                return Optional.of(1);
            } else {
                int c = l[i].compareTo(r[i]);
                if (c != 0) {
                    return Optional.of(Integer.signum(c));
                }
            }
        }
        return Optional.of(0);
    }

    // ---------------------------------------------------------------- PEP 440

    /**
     * Python, PEP 440.
     *
     * <p><b>Epoch.</b> {@code 1!1.0} orders after {@code 2.0}, because the epoch dominates. DOC-22: "Epoch
     * ignored inverts the entire comparison" — and it does, completely, for every package that has ever needed
     * one.
     *
     * <p><b>Suffix ordering</b> is {@code dev < a < b < rc < release < post}. A post-release orders AFTER the
     * release, which is the opposite direction from every pre-release and is where a naive implementation puts
     * {@code 1.0.post1} before {@code 1.0} and reports a patched package as vulnerable.
     */
    private static Optional<Integer> comparePep440(String left, String right) {
        Pep440 l = Pep440.parse(left);
        Pep440 r = Pep440.parse(right);
        if (l == null || r == null) {
            return Optional.empty();
        }
        if (l.epoch != r.epoch) {
            return Optional.of(Integer.compare(l.epoch, r.epoch));
        }
        Optional<Integer> release = compareNumericDotted(l.release, r.release, 0);
        if (release.isEmpty() || release.get() != 0) {
            return release;
        }
        if (l.suffixRank != r.suffixRank) {
            return Optional.of(Integer.compare(l.suffixRank, r.suffixRank));
        }
        return Optional.of(Integer.compare(l.suffixNumber, r.suffixNumber));
    }

    /** Parsed PEP 440 version. Package-private shape, deliberately minimal. */
    private static final class Pep440 {

        /** dev(0) < a(1) < b(2) < rc(3) < release(4) < post(5). */
        private static final int RELEASE_RANK = 4;

        int epoch;
        String release;
        int suffixRank = RELEASE_RANK;
        int suffixNumber;

        static Pep440 parse(String raw) {
            String version = raw.strip().toLowerCase(java.util.Locale.ROOT);
            Pep440 parsed = new Pep440();

            int bang = version.indexOf('!');
            if (bang >= 0) {
                String epochPart = version.substring(0, bang);
                if (!isNumeric(epochPart)) {
                    return null;
                }
                parsed.epoch = Integer.parseInt(epochPart);
                version = version.substring(bang + 1);
            }

            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("^(\\d+(?:\\.\\d+)*)"
                            + "(?:[-_.]?(dev|a|alpha|b|beta|rc|c|pre|preview|post|rev|r)[-_.]?(\\d*))?$")
                    .matcher(version);
            if (!matcher.matches()) {
                return null;
            }
            parsed.release = matcher.group(1);
            String suffix = matcher.group(2);
            if (suffix != null) {
                parsed.suffixRank = switch (suffix) {
                    case "dev" -> 0;
                    case "a", "alpha" -> 1;
                    case "b", "beta" -> 2;
                    case "rc", "c", "pre", "preview" -> 3;
                    // AFTER the release. The direction that trips a naive implementation.
                    case "post", "rev", "r" -> 5;
                    default -> RELEASE_RANK;
                };
                String number = matcher.group(3);
                parsed.suffixNumber = number == null || number.isEmpty() ? 0 : Integer.parseInt(number);
            }
            return parsed;
        }
    }

    // ---------------------------------------------------------------- Maven

    /**
     * Maven.
     *
     * <p>Qualifier ordering is <b>positional and unlike semver's</b>: {@code alpha < beta < milestone < rc <
     * snapshot < (release) < sp}. Note {@code sp} — a service pack orders after the release, the same trap PEP
     * 440's {@code post} sets, and Maven additionally orders an <i>unknown</i> qualifier after the release
     * rather than before it.
     */
    private static Optional<Integer> compareMaven(String left, String right) {
        List<String> l = mavenTokens(left);
        List<String> r = mavenTokens(right);
        for (int i = 0; i < Math.max(l.size(), r.size()); i++) {
            String leftToken = i < l.size() ? l.get(i) : null;
            String rightToken = i < r.size() ? r.get(i) : null;
            int c = compareMavenToken(leftToken, rightToken);
            if (c != 0) {
                return Optional.of(Integer.signum(c));
            }
        }
        return Optional.of(0);
    }

    private static List<String> mavenTokens(String version) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean currentIsDigit = false;
        for (char ch : version.toLowerCase(java.util.Locale.ROOT).toCharArray()) {
            if (ch == '.' || ch == '-' || ch == '_') {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            boolean digit = Character.isDigit(ch);
            if (current.length() > 0 && digit != currentIsDigit) {
                tokens.add(current.toString());
                current.setLength(0);
            }
            currentIsDigit = digit;
            current.append(ch);
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private static int compareMavenToken(String left, String right) {
        // An absent token is a zero against a number and a release against a qualifier.
        if (left == null) {
            return isNumeric(right) ? -Integer.signum(Integer.parseInt(right.isEmpty() ? "0" : right))
                    : -mavenQualifierRank(right) + mavenQualifierRank(null);
        }
        if (right == null) {
            return -compareMavenToken(right, left);
        }
        boolean leftNumeric = isNumeric(left);
        boolean rightNumeric = isNumeric(right);
        if (leftNumeric && rightNumeric) {
            return new java.math.BigInteger(left).compareTo(new java.math.BigInteger(right));
        }
        if (leftNumeric) {
            // A numeric token orders after a qualifier: 1.0-rc < 1.0.1.
            return 1;
        }
        if (rightNumeric) {
            return -1;
        }
        int rank = Integer.compare(mavenQualifierRank(left), mavenQualifierRank(right));
        return rank != 0 ? rank : left.compareTo(right);
    }

    private static int mavenQualifierRank(String qualifier) {
        if (qualifier == null) {
            return 5;
        }
        return switch (qualifier) {
            case "alpha", "a" -> 0;
            case "beta", "b" -> 1;
            case "milestone", "m" -> 2;
            case "rc", "cr" -> 3;
            case "snapshot" -> 4;
            case "", "ga", "final", "release" -> 5;
            case "sp" -> 6;
            // An unknown qualifier orders AFTER the release in Maven, which is the opposite of semver's rule
            // for an unknown pre-release identifier. Same-looking input, opposite answer, different ecosystem —
            // which is PRD-SBM-038's whole point.
            default -> 7;
        };
    }

    // ---------------------------------------------------------------- Debian and RPM

    /**
     * Debian.
     *
     * <p>{@code [epoch:]upstream[-revision]}. The <b>revision</b> is where a backported security fix lives:
     * {@code 1.2.3-1} and {@code 1.2.3-1+deb12u2} carry the same upstream version, and the second is patched.
     * Ordering them correctly is necessary but not sufficient — {@code PRD-SBM-040} requires distribution patch
     * metadata to take precedence over upstream range comparison entirely, which
     * {@link VersionComparison} applies.
     */
    private static Optional<Integer> compareDebian(String left, String right) {
        DebianVersion l = DebianVersion.parse(left);
        DebianVersion r = DebianVersion.parse(right);
        if (l == null || r == null) {
            return Optional.empty();
        }
        if (l.epoch != r.epoch) {
            return Optional.of(Integer.compare(l.epoch, r.epoch));
        }
        int upstream = compareDebianPart(l.upstream, r.upstream);
        if (upstream != 0) {
            return Optional.of(Integer.signum(upstream));
        }
        return Optional.of(Integer.signum(compareDebianPart(l.revision, r.revision)));
    }

    /** RPM's {@code epoch:version-release}. Same shape as Debian; the release segment carries the backport. */
    private static Optional<Integer> compareRpm(String left, String right) {
        return compareDebian(left, right);
    }

    private static final class DebianVersion {

        int epoch;
        String upstream = "";
        String revision = "";

        static DebianVersion parse(String raw) {
            String version = raw.strip();
            DebianVersion parsed = new DebianVersion();
            int colon = version.indexOf(':');
            if (colon >= 0) {
                String epochPart = version.substring(0, colon);
                if (!isNumeric(epochPart)) {
                    return null;
                }
                parsed.epoch = Integer.parseInt(epochPart);
                version = version.substring(colon + 1);
            }
            int hyphen = version.lastIndexOf('-');
            if (hyphen >= 0) {
                parsed.upstream = version.substring(0, hyphen);
                parsed.revision = version.substring(hyphen + 1);
            } else {
                parsed.upstream = version;
            }
            return parsed;
        }
    }

    /**
     * The Debian comparison algorithm: alternate non-digit and digit runs, with {@code ~} sorting before
     * everything including the empty string.
     *
     * <p>The tilde rule is what makes {@code 1.0~rc1} order before {@code 1.0}, and it is the mechanism
     * distributions use to package pre-releases. Omitting it inverts every pre-release comparison in the two
     * ecosystems where most operating-system components live.
     */
    private static int compareDebianPart(String left, String right) {
        int i = 0;
        int j = 0;
        while (i < left.length() || j < right.length()) {
            // Non-digit run.
            while ((i < left.length() && !Character.isDigit(left.charAt(i)))
                    || (j < right.length() && !Character.isDigit(right.charAt(j)))) {
                int leftOrder = i < left.length() ? debianCharOrder(left.charAt(i)) : 0;
                int rightOrder = j < right.length() ? debianCharOrder(right.charAt(j)) : 0;
                if (leftOrder != rightOrder) {
                    return leftOrder - rightOrder;
                }
                i++;
                j++;
            }
            // Digit run. Leading zeros are insignificant.
            int leftStart = i;
            int rightStart = j;
            while (i < left.length() && Character.isDigit(left.charAt(i))) {
                i++;
            }
            while (j < right.length() && Character.isDigit(right.charAt(j))) {
                j++;
            }
            String leftDigits = left.substring(leftStart, i).replaceFirst("^0+(?=.)", "");
            String rightDigits = right.substring(rightStart, j).replaceFirst("^0+(?=.)", "");
            if (leftDigits.isEmpty() && rightDigits.isEmpty()) {
                continue;
            }
            if (leftDigits.isEmpty()) {
                return -1;
            }
            if (rightDigits.isEmpty()) {
                return 1;
            }
            int c = new java.math.BigInteger(leftDigits).compareTo(new java.math.BigInteger(rightDigits));
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /** Letters sort before non-letters; {@code ~} sorts before everything, including the end of string. */
    private static int debianCharOrder(char ch) {
        if (ch == '~') {
            return -1;
        }
        if (Character.isLetter(ch)) {
            return ch;
        }
        return ch + 256;
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Dot-separated numeric comparison.
     *
     * @param minimumSegments pads both sides to this length with zeros, so {@code 1.0} equals {@code 1.0.0}
     *     under semver. A version with fewer segments is not a lesser version.
     */
    private static Optional<Integer> compareNumericDotted(String left, String right, int minimumSegments) {
        // Limit -1: "1.0." must NOT compare equal to "1.0". The empty trailing segment fails isNumeric below
        // and the comparison returns empty, which becomes INDETERMINATE — the honest answer for input the
        // scheme does not admit (PRD-SBM-039).
        String[] l = left.split("\\.", -1);
        String[] r = right.split("\\.", -1);
        int length = Math.max(Math.max(l.length, r.length), minimumSegments);
        for (int i = 0; i < length; i++) {
            String leftSegment = i < l.length ? l[i] : "0";
            String rightSegment = i < r.length ? r[i] : "0";
            if (!isNumeric(leftSegment) || !isNumeric(rightSegment)) {
                // A non-numeric segment in what should be a numeric core. PRD-SBM-039: say so rather than
                // falling back to a string comparison that would order 1.0.x arbitrarily.
                return Optional.empty();
            }
            int c = new java.math.BigInteger(leftSegment).compareTo(new java.math.BigInteger(rightSegment));
            if (c != 0) {
                return Optional.of(Integer.signum(c));
            }
        }
        return Optional.of(0);
    }

    private static boolean isNumeric(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String beforeFirst(String value, char separator) {
        int index = value.indexOf(separator);
        return index < 0 ? value : value.substring(0, index);
    }

    private static String afterFirst(String value, char separator) {
        int index = value.indexOf(separator);
        return index < 0 ? "" : value.substring(index + 1);
    }
}
