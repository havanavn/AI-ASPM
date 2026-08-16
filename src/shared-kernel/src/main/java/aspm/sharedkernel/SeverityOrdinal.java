package aspm.sharedkernel;

/**
 * Severity as a tenant-configured ordinal rather than a fixed enumeration.
 *
 * <p>ADR-027 and the prohibited-pattern table both reject a fixed enumeration for a
 * tenant-configurable surface. The ordinal is comparable; the label and the count of
 * bands are tenant data held by schema-registry.
 */
public record SeverityOrdinal(int value) implements Comparable<SeverityOrdinal> {
    public SeverityOrdinal {
        if (value < 0) {
            throw new IllegalArgumentException("severity ordinal is non-negative");
        }
    }

    @Override
    public int compareTo(SeverityOrdinal other) {
        return Integer.compare(value, other.value);
    }
}
