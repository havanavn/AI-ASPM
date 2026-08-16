package aspm.kernel.rulesengine.contract;

import java.util.Map;
import java.util.Objects;

/**
 * The facts describing the object under evaluation.
 *
 * <p>An explicit, immutable snapshot rather than a live object. Three reasons:
 *
 * <ul>
 *   <li><b>Determinism.</b> A live object could change between two clauses of the same condition, so
 *       the same rule against the same object could yield different outcomes — which PRD-WRK-033
 *       forbids.
 *   <li><b>Bounded cost.</b> A live object invites lazy loading, and lazy loading inside a guard is the
 *       unbounded query PRD-WRK-033 prohibits. A map cannot issue a query.
 *   <li><b>Reproducibility.</b> The snapshot is exactly what an explanation can quote, so a tenant
 *       asking why a rule did or did not fire gets the facts the engine actually saw.
 * </ul>
 *
 * <p>A key absent from the map yields {@link FactValue.Absent}, never null. The caller decides what is
 * in the set; the engine never infers a fact that was not supplied (PRD-ING-021 applied to evaluation).
 */
public record FactSet(Map<String, FactValue> facts) {

    private static final FactValue.Absent ABSENT = new FactValue.Absent();

    public FactSet {
        facts = Map.copyOf(Objects.requireNonNull(facts, "facts are required; use FactSet.empty()"));
        facts.forEach((key, value) -> {
            Objects.requireNonNull(key, "a fact key must not be null");
            Objects.requireNonNull(value, "fact '" + key + "' is null; use FactValue.Absent explicitly "
                    + "so that a missing fact is a decision rather than an oversight");
        });
    }

    public static FactSet empty() {
        return new FactSet(Map.of());
    }

    /** The value for a key, or {@link FactValue.Absent} where the key was not supplied. */
    public FactValue get(String key) {
        return facts.getOrDefault(key, ABSENT);
    }

    public boolean isPresent(String key) {
        FactValue value = facts.get(key);
        return value != null && !(value instanceof FactValue.Absent);
    }
}
