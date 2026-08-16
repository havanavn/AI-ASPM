package aspm.app.api;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The framework-level request rules that apply to every operation regardless of class.
 *
 * <p>DOC-05 section 5: "Request bodies validated against a declared schema with size, depth, and
 * element limits, rejecting unknown fields." All four, and none of them per-operation.
 */
public final class RequestValidation {

    /** Bounds. A body within all three is still validated against its schema; these stop the rest. */
    public static final int MAX_BODY_BYTES = 1_048_576;

    public static final int MAX_DEPTH = 16;

    public static final int MAX_ELEMENTS = 10_000;

    private RequestValidation() {
    }

    /**
     * {@code PRD-API-020}: unknown fields are <b>rejected, not ignored</b>.
     *
     * <p>"A silently ignored field means a client typo produces a no-op the client believes
     * succeeded." The failure is worse than it sounds in this product: a client setting
     * {@code severtiy} instead of {@code severity} on a bulk triage receives 200 OK and has changed
     * nothing, and the discrepancy surfaces weeks later as findings nobody triaged.
     *
     * <p>The error names the unknown fields and, deliberately, <b>does not</b> suggest corrections. A
     * "did you mean" over the declared schema is a schema-disclosure oracle for an operation the caller
     * may not be authorized to use in full.
     *
     * @throws IllegalArgumentException listing every unknown field, so one round trip fixes them all
     */
    public static void rejectUnknownFields(Set<String> declaredFields, Map<String, ?> body) {
        Objects.requireNonNull(declaredFields, "the declared field set is required");
        Objects.requireNonNull(body, "a body is required");

        Set<String> unknown = new LinkedHashSet<>(body.keySet());
        unknown.removeAll(declaredFields);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                    "VALIDATION_FAILED: unknown field(s) " + unknown + ". Unknown fields are rejected rather "
                            + "than ignored (PRD-API-020): a silently ignored field means a client typo "
                            + "produces a no-op the client believes succeeded.");
        }
    }

    /**
     * A typed filter parameter. <b>There is no filter expression language.</b>
     *
     * <p>An expression language on a scoped collection is a second query planner that has to reapply
     * the scope predicate correctly — and the scope predicate must be applied <i>in retrieval</i>
     * ({@code SEC-AUZ-016}), not over the result of an expression the client composed. Every filter
     * language that has ever shipped on a multi-tenant API has eventually grown a way to express a
     * predicate over a field the caller cannot read.
     *
     * <p>The cost is stated: a caller wanting a filter the platform does not offer cannot express it,
     * and has to ask for one. That is the intended trade.
     */
    public record TypedFilter(String field, Operator operator, Object value) {

        public enum Operator {
            EQUALS,
            NOT_EQUALS,
            IN,
            GREATER_THAN,
            GREATER_OR_EQUAL,
            LESS_THAN,
            LESS_OR_EQUAL,
            /** Prefix match on an indexed text column. Not a regular expression, and not a suffix match. */
            STARTS_WITH,
            CONTAINS_ANY_OF
        }

        public TypedFilter {
            Objects.requireNonNull(field, "a field is required");
            Objects.requireNonNull(operator, "an operator is required");
            Objects.requireNonNull(value, "a value is required");
        }
    }

    /**
     * Validates a filter set against the fields an operation declares filterable.
     *
     * @param filterableFields the declared set. A field absent from it is not filterable, whether or not
     *     it is readable — a filter over an unreadable field is an oracle over its values
     *     ({@code SEC-AUZ-021})
     */
    public static void validateFilters(Set<String> filterableFields, List<TypedFilter> filters) {
        Objects.requireNonNull(filterableFields, "the filterable field set is required");
        Objects.requireNonNull(filters, "filters are required, possibly empty");

        Set<String> rejected = new LinkedHashSet<>();
        for (TypedFilter filter : filters) {
            if (!filterableFields.contains(filter.field())) {
                rejected.add(filter.field());
            }
        }
        if (!rejected.isEmpty()) {
            throw new IllegalArgumentException(
                    "VALIDATION_FAILED: field(s) " + rejected + " are not filterable on this operation. A "
                            + "filter over a field the caller cannot read is an oracle over its values "
                            + "(SEC-AUZ-021), so filterability is declared per operation rather than "
                            + "inferred from the schema.");
        }
    }

    /**
     * Applies {@code SEC-AUZ-022}: restricted fields are <b>absent</b>, not masked (ADR-047).
     *
     * <p>Masking leaves the key present with a placeholder value, which tells the reader the field
     * exists and has a value — and for a boolean or a low-cardinality enumeration, that is most of the
     * information. Absence tells them nothing.
     *
     * <p>Absence also survives serialization in a way masking does not: a client deserializing into a
     * typed object gets a null it must handle, rather than the literal string that a mask becomes.
     */
    public static Map<String, Object> withRestrictedFieldsAbsent(Map<String, Object> representation,
            Set<String> restrictedFields) {
        Objects.requireNonNull(representation, "a representation is required");
        Objects.requireNonNull(restrictedFields, "the restricted field set is required");

        Map<String, Object> filtered = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : representation.entrySet()) {
            if (!restrictedFields.contains(entry.getKey())) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return Map.copyOf(filtered);
    }
}
