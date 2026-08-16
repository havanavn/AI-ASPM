package aspm.sharedkernel;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Collapsing of high-cardinality path segments.
 *
 * <p><b>Why this is in the shared kernel.</b> Two contexts need it and neither depends on the other: asset
 * identity collapses API paths so that "an inventory of a REST service" is bounded (DOC-03 section 8.5), and
 * finding identity collapses runtime request paths so that "a payload reflected at {@code /search?q=X} is one
 * finding, not one per value of X" (DOC-03 section 10.2). Product principle 10 — one name, one meaning, one
 * place — and the alternative is two implementations of a heuristic that must agree, drifting apart while both
 * look correct.
 *
 * <p>It qualifies for the shared kernel on DOC-03 section 5.2's terms because it is a pure function over a
 * string with no domain model attached. It carries no identity, no lifecycle and no reference to any aggregate.
 *
 * <p><b>Conservative in one direction on purpose.</b> DOC-03 section 8.5 calls this "the highest-consequence
 * normalization" and requires the heuristic to be conservative: a segment is a parameter only where it matches a
 * numeric, UUID, long-hex or opaque-identifier pattern. Under-collapsing fragments one endpoint into several
 * assets or findings, which is visible and fixable. Over-collapsing merges genuinely distinct endpoints, which
 * destroys their separate histories and is not visible. Where the two risks are asymmetric, the heuristic leans
 * towards the visible failure.
 *
 * <p><b>A change here changes identity.</b> Callers must record the version of the rule that produced a value —
 * {@code IdentityRule.version} for assets, {@code algorithm_version} for findings — so that a later improvement
 * can tell which records it must re-resolve. DOC-03 section 8.5: the collapse "must be recorded in the rule
 * version so that a change to the heuristic is traceable".
 */
public final class PathNormalization {

    /** The placeholder a collapsed segment becomes. */
    public static final String PARAMETER_PLACEHOLDER = "{id}";

    private static final Pattern NUMERIC = Pattern.compile("^\\d+$");
    private static final Pattern UUID_SEGMENT = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern LONG_HEX = Pattern.compile("^[0-9a-fA-F]{16,}$");
    /** Long mixed alphanumeric containing a digit — an opaque identifier rather than a route name. */
    private static final Pattern OPAQUE = Pattern.compile("^(?=.*\\d)[A-Za-z0-9_-]{12,}$");

    private PathNormalization() {
        throw new AssertionError("not instantiable");
    }

    /** Collapses high-cardinality segments, preserving structure and segment count. */
    public static String collapseParameters(String path) {
        Objects.requireNonNull(path, "path is required");
        String[] segments = path.split("/", -1);
        StringBuilder out = new StringBuilder(path.length());
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                out.append('/');
            }
            out.append(isParameterSegment(segments[i]) ? PARAMETER_PLACEHOLDER : segments[i]);
        }
        return out.toString();
    }

    /**
     * Strips the query string and fragment, lowercases, and collapses parameters.
     *
     * <p>The query string is dropped entirely rather than collapsed: for a runtime finding the reflected value
     * lives there, and DOC-03 section 10.2 excludes "concrete parameter values" from the fingerprint. The
     * parameter <em>name</em> is a separate fingerprint input, supplied by the caller.
     */
    public static String normalizeRequestPath(String reportedPath) {
        Objects.requireNonNull(reportedPath, "reportedPath is required");
        String path = reportedPath.strip();
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int fragment = path.indexOf('#');
        if (fragment >= 0) {
            path = path.substring(0, fragment);
        }
        return collapseParameters(path.toLowerCase(Locale.ROOT));
    }

    private static boolean isParameterSegment(String segment) {
        if (segment.isEmpty()) {
            return false;
        }
        return NUMERIC.matcher(segment).matches()
                || UUID_SEGMENT.matcher(segment).matches()
                || LONG_HEX.matcher(segment).matches()
                || OPAQUE.matcher(segment).matches();
    }
}
