package aspm.kernel.rulesengine.contract;

import aspm.sharedkernel.OrgNodeId;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A value a rule may compare against, from the fact set describing the object under evaluation.
 *
 * <p>A closed hierarchy rather than {@code Object}. Two reasons, both structural:
 *
 * <ul>
 *   <li><b>Determinism.</b> {@code PRD-WRK-033} requires guard expressions to be deterministic and
 *       product principle 2 requires transitions to be reproducible. An open value type would compare
 *       through {@code equals} and {@code compareTo} on arbitrary classes, whose semantics the engine
 *       cannot state — and an unstated comparison is where a guard's behaviour becomes
 *       implementation-dependent.
 *   <li><b>Bounded cost.</b> {@code PRD-WRK-033} forbids a guard invoking AI, external services, or
 *       unbounded queries. There is no variant below that can hold a callable, a future, or a query, so
 *       a guard <em>cannot</em> reach one. That is an absence rather than a prohibition, which is DOC-26
 *       section 13.2's preference for structural controls applied to expression evaluation.
 * </ul>
 *
 * <p>{@link Absent} is a value, not a null. {@code PRD-ING-021} makes an absent source field null with
 * no inference, and product principle 1 makes measured-and-clean distinguishable from not-measured. An
 * absent fact must therefore be representable and must not silently behave as a zero, an empty string,
 * or a false.
 */
public sealed interface FactValue {

    /** Whether this value participates in relational comparison. See {@link Ordering}. */
    boolean ordered();

    /** Free text. Compared for equality and containment only; not relationally ordered. */
    record Text(String value) implements FactValue {
        public Text {
            Objects.requireNonNull(value, "text value is required; use Absent for a missing fact");
        }

        @Override
        public boolean ordered() {
            return false;
        }
    }

    /**
     * An exact decimal. Never a {@code double}.
     *
     * <p>{@code PRD-RSK-023} requires a score to be recomputable identically. Binary floating point is
     * not associative, so a sum computed in a different order yields a different value — which would
     * make an identical recomputation impossible and a rule's outcome depend on evaluation order.
     */
    record Decimal(BigDecimal value) implements FactValue {
        public Decimal {
            Objects.requireNonNull(value, "number value is required");
        }

        @Override
        public boolean ordered() {
            return true;
        }
    }

    /** True or false. Ordered as false &lt; true, stated so a relational guard is defined. */
    record Bool(boolean value) implements FactValue {
        @Override
        public boolean ordered() {
            return true;
        }
    }

    /** An instant. Stored UTC; business-calendar interpretation belongs to the caller per PRD-RSK-033. */
    record Timestamp(Instant value) implements FactValue {
        public Timestamp {
            Objects.requireNonNull(value, "timestamp value is required");
        }

        @Override
        public boolean ordered() {
            return true;
        }
    }

    /** A calendar date, for business dates that must not shift across time zones. */
    record Date(LocalDate value) implements FactValue {
        public Date {
            Objects.requireNonNull(value, "date value is required");
        }

        @Override
        public boolean ordered() {
            return true;
        }
    }

    /**
     * A taxonomy member, compared by its stored ordinal rather than by its label.
     *
     * <p>DOC-04 section 8.1: without a stored ordinal "comparing two severities requires interpreting
     * labels, and cross-tenant support becomes impossible". The code travels with the ordinal so a
     * diagnostic explanation can name what was compared without re-reading the taxonomy.
     */
    record Ordinal(String code, int ordinal) implements FactValue {
        public Ordinal {
            Objects.requireNonNull(code, "taxonomy code is required");
        }

        @Override
        public boolean ordered() {
            return true;
        }
    }

    /** An identifier. Equality and set membership only; identifiers have no meaningful order. */
    record Id(UUID value) implements FactValue {
        public Id {
            Objects.requireNonNull(value, "id value is required");
        }

        @Override
        public boolean ordered() {
            return false;
        }
    }

    /** A set of identifiers, for multi-valued facts such as labels or affected assets. */
    record IdSet(Set<UUID> values) implements FactValue {
        public IdSet {
            values = Set.copyOf(Objects.requireNonNull(values, "id set is required"));
        }

        @Override
        public boolean ordered() {
            return false;
        }
    }

    /** A set of text values, for labels and tags. */
    record TextSet(Set<String> values) implements FactValue {
        public TextSet {
            values = Set.copyOf(Objects.requireNonNull(values, "text set is required"));
        }

        @Override
        public boolean ordered() {
            return false;
        }
    }

    /**
     * An organizational scope path, root to leaf.
     *
     * <p>Ordered as a path, not as a value: subtree containment is the only comparison, which is what
     * DOC-28 section 11.1's {@code org_scope} dimension means by "applies to a subtree".
     */
    record ScopePath(List<OrgNodeId> ancestorPath, OrgNodeId nodeId) implements FactValue {
        public ScopePath {
            ancestorPath = List.copyOf(Objects.requireNonNull(ancestorPath, "ancestorPath is required"));
            Objects.requireNonNull(nodeId, "nodeId is required");
        }

        /** True where {@code candidate} is this node or one of its ancestors. */
        public boolean withinSubtreeOf(OrgNodeId candidate) {
            return nodeId.equals(candidate) || ancestorPath.contains(candidate);
        }

        @Override
        public boolean ordered() {
            return false;
        }
    }

    /**
     * The fact is not present.
     *
     * <p>Distinct from every other value and from null. A comparison against {@code Absent} yields
     * {@link RuleOutcome#UNDEFINED}, never false — see {@link RuleOutcome} for why that distinction is
     * load-bearing rather than pedantic.
     */
    record Absent() implements FactValue {
        @Override
        public boolean ordered() {
            return false;
        }
    }

    /** Marker for the ordering contract, referenced from the class comment. */
    interface Ordering {}
}
