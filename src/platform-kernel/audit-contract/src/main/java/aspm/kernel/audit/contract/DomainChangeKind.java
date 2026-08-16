package aspm.kernel.audit.contract;

/**
 * The per-aggregate domain state changes of DOC-14 section 3, which that section defers to "the
 * machine-readable catalogue, enumerated per aggregate".
 *
 * <p>Separated from {@link AuditEventType} because the enumeration is a product of this fixed set of
 * kinds and the aggregate list, and the aggregates do not exist until prompt 4. Combining them into
 * one enum now would require either inventing aggregate names ahead of their modules or leaving the
 * enum incomplete in a way that {@code SEC-AUD-006}'s build failure could not detect.
 *
 * <p>The owning module supplies its aggregate name; this kernel supplies the kind. The composite code
 * is {@code <aggregate>.<kind>}, and {@code S11} of DOC-16 section 3 asserts every audit-emitting path
 * resolves to a catalogued type through one of the two routes.
 */
public enum DomainChangeKind {
    CREATED("created"),
    UPDATED("updated"),
    TRANSITIONED("transitioned"),
    ASSIGNED("assigned"),
    MERGED("merged"),
    RETIRED("retired"),
    REOPENED("reopened");

    private final String suffix;

    DomainChangeKind(String suffix) {
        this.suffix = suffix;
    }

    public String suffix() {
        return suffix;
    }

    /** The composite catalogue code for an aggregate, e.g. {@code finding.transitioned}. */
    public String codeFor(String aggregateName) {
        java.util.Objects.requireNonNull(aggregateName, "aggregate name is required");
        if (!aggregateName.matches("^[a-z][a-z0-9_]*$")) {
            throw new IllegalArgumentException(
                    "aggregate name '" + aggregateName + "' is not a stable lower_snake code; the "
                            + "composite event type appears in exported trails and must not vary");
        }
        return aggregateName + "." + suffix;
    }
}
