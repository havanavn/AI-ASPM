package aspm.kernel.schemaregistry.contract;

/**
 * The data types a tenant-defined attribute may take.
 *
 * <p><b>Product-fixed, and that is the one thing here that is not tenant-configurable.</b> ADR-027
 * makes fields, workflows, roles and vocabulary tenant data; it does not make the type system tenant
 * data. A tenant-defined type would need tenant-defined validation, comparison, indexing and export
 * behaviour, and comparison in particular is authorization-relevant wherever an attribute participates
 * in a rule — DOC-02 section 6.2 records that four modules share one evaluator precisely because
 * divergent semantics in one of them "governs whether workflow approval gates apply", which is a
 * privilege escalation.
 *
 * <p>Each type states how it compares, because a rules engine and a search index both need a total
 * order and an undefined one is where subtle authorization defects live.
 */
public enum AttributeDataType {

    /** Free text. Compares by codepoint; not ordered for business purposes. */
    TEXT(false),

    /** Multi-line free text. Never searchable-ordered; only matched. */
    LONG_TEXT(false),

    /** Arbitrary-precision decimal. Ordered. Not floating point: a score must be reproducible. */
    DECIMAL(true),

    /** Whole number. Ordered. */
    INTEGER(true),

    /** True or false. Ordered as false &lt; true, stated so a rule's behaviour is defined. */
    BOOLEAN(true),

    /** Instant, stored UTC and rendered in the tenant's timezone per {@code NFR-INT-003}. */
    TIMESTAMP(true),

    /** Calendar date with no time, for business dates that must not shift across zones. */
    DATE(true),

    /**
     * A single choice from a tenant-defined option set.
     *
     * <p>Ordered by the option's stored ordinal, not by its label — the same separation DOC-04
     * section 8.1 requires of taxonomies, where "comparing two severities requires interpreting labels"
     * without a stored ordinal and "cross-tenant support becomes impossible".
     */
    SINGLE_SELECT(true),

    /** Several choices from a tenant-defined option set. Unordered; membership only. */
    MULTI_SELECT(false),

    /** A reference to a principal. Stored as an identifier, never as a name. */
    PRINCIPAL_REFERENCE(false),

    /** A reference to an organization node. Participates in scope, so never free text. */
    ORG_NODE_REFERENCE(false),

    /** A URL. Validated for shape; never fetched by the platform (ADR-024). */
    URL(false);

    private final boolean ordered;

    AttributeDataType(boolean ordered) {
        this.ordered = ordered;
    }

    /**
     * True where this type has a defined total order.
     *
     * <p>A rule using a relational operator on an unordered type is a configuration error the
     * schema-registry rejects, rather than a comparison whose result varies by implementation.
     */
    public boolean isOrdered() {
        return ordered;
    }
}
