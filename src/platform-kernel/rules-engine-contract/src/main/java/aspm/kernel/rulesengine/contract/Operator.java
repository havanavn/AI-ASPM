package aspm.kernel.rulesengine.contract;

/**
 * The comparison operators available to a condition.
 *
 * <p>A closed set, product-fixed. ADR-027 makes rules tenant data; it does not make the operator set
 * tenant data, because a tenant-defined operator would need tenant-defined semantics and the semantics
 * are what CON-PLT-012 exists to keep single.
 *
 * <p>No regular-expression operator. A regular expression is unbounded in evaluation cost against a
 * crafted input, and PRD-WRK-033 requires a guard to be bounded — a guard is evaluated on every
 * transition attempt including denied ones, so an attacker-influenced field plus a backtracking pattern
 * is a denial-of-service on the transition path.
 */
public enum Operator {

    EQUALS(false),
    NOT_EQUALS(false),

    /** Relational. Permitted only where the operand has a defined total order. */
    LESS_THAN(true),
    LESS_THAN_OR_EQUAL(true),
    GREATER_THAN(true),
    GREATER_THAN_OR_EQUAL(true),

    /** The fact's value is a member of the operand set. */
    IN(false),

    /** The fact is a set containing the operand. */
    CONTAINS(false);

    private final boolean relational;

    Operator(boolean relational) {
        this.relational = relational;
    }

    /** True where this operator needs a total order, and so is rejected on an unordered type. */
    public boolean relational() {
        return relational;
    }
}
