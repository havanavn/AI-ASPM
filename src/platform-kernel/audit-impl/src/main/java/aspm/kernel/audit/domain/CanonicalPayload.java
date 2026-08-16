package aspm.kernel.audit.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Deterministic bytes for an audit payload.
 *
 * <p>The envelope's canonical form is {@link CanonicalSerializer}; this is the payload's. Both are needed
 * because the chain covers the payload's hash, so a payload whose bytes vary makes {@code payload_hash}
 * vary for identical content — producing a verification failure on unaltered data, which
 * {@code SEC-AUD-011} forbids and {@code SEC-AUD-018} escalates to a page outside operator control.
 *
 * <p><b>Map iteration order is the hazard.</b> A {@code HashMap} orders by hash, which varies with
 * insertion history. Keys are therefore sorted, and the same length-prefixed encoding is used as for the
 * envelope so a value containing a separator cannot change the parse.
 *
 * <p>Nesting is bounded rather than arbitrary, because DOC-14 section 2 defines a payload as before/after
 * values and request detail — not a document — and an unbounded structure on the platform's hottest write
 * path is a cost nobody budgeted against {@code NFR-AUD-001}.
 */
public final class CanonicalPayload {

    /** Maximum nesting depth. Deeper than this is a modelling error, not a large payload. */
    public static final int MAX_DEPTH = 8;

    /**
     * Marker for a null value.
     *
     * <p>Deliberately not the text {@code "null"}: a null and the four-character string must not hash
     * identically, or one could be substituted for the other in a before/after comparison.
     */
    private static final String NULL_MARKER = "<absent>";

    private CanonicalPayload() {
        throw new AssertionError("not instantiable");
    }

    /** Canonical bytes for a payload. An empty map yields empty bytes, matching an absent payload. */
    public static byte[] canonicalize(Map<String, Object> payload) {
        Objects.requireNonNull(payload, "payload is required; use Map.of() for none");
        StringBuilder out = new StringBuilder();
        writeMap(out, payload, 1);
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void writeMap(StringBuilder out, Map<String, Object> map, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException(
                    "audit payload nesting exceeds " + MAX_DEPTH + "; a payload is before/after values and "
                            + "request detail (DOC-14 section 2), not an arbitrary document");
        }
        // Sorted: map iteration order must never reach the hash.
        for (Map.Entry<String, Object> entry : new TreeMap<>(map).entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "a payload key must not be null");
            field(out, key, entry.getValue(), depth);
        }
    }

    private static void field(StringBuilder out, String key, Object value, int depth) {
        String rendered = render(key, value, depth);
        byte[] bytes = rendered.getBytes(StandardCharsets.UTF_8);
        out.append(key).append(':').append(bytes.length).append(':').append(rendered).append(';');
    }

    private static String render(String key, Object value, int depth) {
        if (value == null) {
            return NULL_MARKER;
        }
        if (value instanceof Map<?, ?> nested) {
            StringBuilder inner = new StringBuilder();
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) nested;
            writeMap(inner, typed, depth + 1);
            return "map{" + inner + "}";
        }
        if (value instanceof Iterable<?> items) {
            StringBuilder inner = new StringBuilder();
            int index = 0;
            for (Object item : items) {
                // Index-keyed rather than sorted: list ORDER is meaningful in a before/after payload.
                field(inner, Integer.toString(index++), item, depth + 1);
            }
            return "list[" + inner + "]";
        }
        if (value instanceof BigDecimal decimal) {
            // Normalized, so 1.0 and 1.00 hash identically: a scale difference is not a value difference.
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (value instanceof Double || value instanceof Float) {
            throw new IllegalArgumentException(
                    "binary floating point in an audit payload at key '" + key + "'. Its textual form is "
                            + "platform-dependent, so the hash would be too, and a verification failure on "
                            + "unaltered data is the outcome SEC-AUD-011 exists to prevent. Use BigDecimal.");
        }
        return value.toString();
    }
}
