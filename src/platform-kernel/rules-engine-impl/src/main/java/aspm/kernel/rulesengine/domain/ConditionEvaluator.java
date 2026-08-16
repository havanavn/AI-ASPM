package aspm.kernel.rulesengine.domain;

import aspm.kernel.rulesengine.contract.Condition;
import aspm.kernel.rulesengine.contract.FactSet;
import aspm.kernel.rulesengine.contract.FactValue;
import aspm.kernel.rulesengine.contract.Operator;
import aspm.kernel.rulesengine.contract.RuleOutcome;
import java.util.Objects;
import java.util.Set;

/**
 * The single evaluator required by {@code CON-PLT-012}, serving workflow guards, automation rules,
 * service-level policy matching, and checklist selection.
 *
 * <p>Deterministic, side-effect free, and bounded — {@code PRD-WRK-033}. Boundedness is structural
 * rather than enforced at runtime: the grammar has no call, query, loop or pattern variant, so the cost
 * of an expression is fixed by its node count, and {@link ConditionValidator} bounds that at definition
 * time. There is no timeout here because there is nothing that can run long.
 *
 * <p>Side-effect freedom is likewise structural: this class has no collaborators, no fields, and no way
 * to reach anything but the {@link FactSet} it is given.
 */
public final class ConditionEvaluator {

    private ConditionEvaluator() {
        throw new AssertionError("not instantiable");
    }

    /** Evaluates a condition against a fact set. Never raises for a data condition. */
    public static RuleOutcome evaluate(Condition condition, FactSet facts) {
        Objects.requireNonNull(condition, "condition is required");
        Objects.requireNonNull(facts, "facts are required");

        return switch (condition) {
            case Condition.AlwaysTrue _ -> RuleOutcome.TRUE;

            case Condition.Present p -> RuleOutcome.of(facts.isPresent(p.factKey()));

            case Condition.Not n -> evaluate(n.operand(), facts).negate();

            case Condition.All all -> {
                // Empty conjunction is vacuously true. Kleene AND, so a definite FALSE wins over an
                // UNDEFINED sibling and an UNDEFINED prevents an otherwise-true conjunction.
                RuleOutcome result = RuleOutcome.TRUE;
                for (Condition operand : all.operands()) {
                    result = result.and(evaluate(operand, facts));
                    if (result == RuleOutcome.FALSE) {
                        // Short-circuit is safe precisely because evaluation is side-effect free: no
                        // observable behaviour depends on whether the remaining operands ran.
                        break;
                    }
                }
                yield result;
            }

            case Condition.Any any -> {
                RuleOutcome result = RuleOutcome.FALSE;
                for (Condition operand : any.operands()) {
                    result = result.or(evaluate(operand, facts));
                    if (result == RuleOutcome.TRUE) {
                        break;
                    }
                }
                yield result;
            }

            case Condition.WithinSubtree w -> {
                FactValue value = facts.get(w.factKey());
                yield value instanceof FactValue.ScopePath path
                        ? RuleOutcome.of(path.withinSubtreeOf(w.ancestor()))
                        // Absent, or a fact of the wrong shape. Not false: a scope rule whose scope fact
                        // is missing has not been evaluated, and treating that as "outside the subtree"
                        // would silently apply a more general policy.
                        : RuleOutcome.UNDEFINED;
            }

            case Condition.Comparison c -> compare(facts.get(c.factKey()), c.operator(), c.operand());
        };
    }

    private static RuleOutcome compare(FactValue actual, Operator operator, FactValue operand) {
        if (actual instanceof FactValue.Absent) {
            // The load-bearing line. An absent fact makes the comparison UNDEFINED, and UNDEFINED
            // survives negation — so NOT(severity = LOW) does not pass for an object whose severity was
            // never recorded. Product principle 1, expressed in the arithmetic.
            return RuleOutcome.UNDEFINED;
        }

        return switch (operator) {
            case EQUALS -> equality(actual, operand);
            case NOT_EQUALS -> equality(actual, operand).negate();
            case IN -> membership(actual, operand);
            case CONTAINS -> containment(actual, operand);
            case LESS_THAN, LESS_THAN_OR_EQUAL, GREATER_THAN, GREATER_THAN_OR_EQUAL ->
                    relational(actual, operator, operand);
        };
    }

    /**
     * Equality, defined only between values of the same variant.
     *
     * <p>Cross-type equality is UNDEFINED rather than false. A rule comparing a severity ordinal to a
     * text literal is a configuration defect, and reporting it as "false" would make the rule quietly
     * inert — the failure mode a tenant cannot diagnose, because the rule looks correct and simply never
     * fires.
     */
    private static RuleOutcome equality(FactValue actual, FactValue operand) {
        if (!actual.getClass().equals(operand.getClass())) {
            return RuleOutcome.UNDEFINED;
        }
        // Ordinal equality compares the ordinal, not the label: two tenants may label the same ordinal
        // differently, and DOC-04 section 8.1 makes the code stable while the label is not.
        if (actual instanceof FactValue.Ordinal a && operand instanceof FactValue.Ordinal b) {
            return RuleOutcome.of(a.ordinal() == b.ordinal());
        }
        if (actual instanceof FactValue.Decimal a && operand instanceof FactValue.Decimal b) {
            // compareTo, not equals: BigDecimal.equals distinguishes 1.0 from 1.00, which is a scale
            // difference and not a value difference, and a rule must not depend on how a value was typed.
            return RuleOutcome.of(a.value().compareTo(b.value()) == 0);
        }
        return RuleOutcome.of(actual.equals(operand));
    }

    private static RuleOutcome membership(FactValue actual, FactValue operand) {
        return switch (operand) {
            case FactValue.IdSet set when actual instanceof FactValue.Id id ->
                    RuleOutcome.of(set.values().contains(id.value()));
            case FactValue.TextSet set when actual instanceof FactValue.Text text ->
                    RuleOutcome.of(set.values().contains(text.value()));
            case FactValue.TextSet set when actual instanceof FactValue.Ordinal ordinal ->
                    RuleOutcome.of(set.values().contains(ordinal.code()));
            default -> RuleOutcome.UNDEFINED;
        };
    }

    private static RuleOutcome containment(FactValue actual, FactValue operand) {
        return switch (actual) {
            case FactValue.IdSet set when operand instanceof FactValue.Id id ->
                    RuleOutcome.of(set.values().contains(id.value()));
            case FactValue.TextSet set when operand instanceof FactValue.Text text ->
                    RuleOutcome.of(set.values().contains(text.value()));
            default -> RuleOutcome.UNDEFINED;
        };
    }

    private static RuleOutcome relational(FactValue actual, Operator operator, FactValue operand) {
        Integer comparison = orderOf(actual, operand);
        if (comparison == null) {
            return RuleOutcome.UNDEFINED;
        }
        return RuleOutcome.of(switch (operator) {
            case LESS_THAN -> comparison < 0;
            case LESS_THAN_OR_EQUAL -> comparison <= 0;
            case GREATER_THAN -> comparison > 0;
            case GREATER_THAN_OR_EQUAL -> comparison >= 0;
            default -> throw new IllegalStateException("not a relational operator: " + operator);
        });
    }

    /** The comparison, or null where the pair has no defined order. */
    private static Integer orderOf(FactValue actual, FactValue operand) {
        if (!actual.getClass().equals(operand.getClass())) {
            return null;
        }
        return switch (actual) {
            case FactValue.Decimal a when operand instanceof FactValue.Decimal b ->
                    a.value().compareTo(b.value());
            case FactValue.Ordinal a when operand instanceof FactValue.Ordinal b ->
                    Integer.compare(a.ordinal(), b.ordinal());
            case FactValue.Timestamp a when operand instanceof FactValue.Timestamp b ->
                    a.value().compareTo(b.value());
            case FactValue.Date a when operand instanceof FactValue.Date b ->
                    a.value().compareTo(b.value());
            case FactValue.Bool a when operand instanceof FactValue.Bool b ->
                    Boolean.compare(a.value(), b.value());
            default -> null;
        };
    }

    /** The fact keys a condition reads, for the definition-time completeness check. */
    public static Set<String> referencedFactKeys(Condition condition) {
        Objects.requireNonNull(condition, "condition is required");
        java.util.Set<String> keys = new java.util.TreeSet<>();
        collectKeys(condition, keys);
        return java.util.Collections.unmodifiableSet(keys);
    }

    private static void collectKeys(Condition condition, Set<String> into) {
        switch (condition) {
            case Condition.AlwaysTrue _ -> {
                // no keys
            }
            case Condition.Present p -> into.add(p.factKey());
            case Condition.Comparison c -> into.add(c.factKey());
            case Condition.WithinSubtree w -> into.add(w.factKey());
            case Condition.Not n -> collectKeys(n.operand(), into);
            case Condition.All a -> a.operands().forEach(o -> collectKeys(o, into));
            case Condition.Any a -> a.operands().forEach(o -> collectKeys(o, into));
        }
    }
}
