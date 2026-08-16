package aspm.kernel.rulesengine.contract;

import aspm.sharedkernel.OrgNodeId;
import java.util.List;
import java.util.Objects;

/**
 * A condition over a fact set, shared by all four callers of {@code CON-PLT-012}: workflow guards,
 * automation rules, service-level policy matching, and checklist selection.
 *
 * <p>DOC-02 section 6.2 records why one evaluator rather than four: "Four implementations would diverge,
 * and one of them governs authorization-relevant workflow transitions" — divergence there is a
 * privilege escalation. That is the whole argument for this type existing rather than each module
 * carrying its own predicate.
 *
 * <p><b>What is deliberately not expressible.</b> {@code PRD-WRK-033} requires guards to be
 * deterministic, side-effect free, and bounded in evaluation cost, and forbids invoking AI, external
 * services, or unbounded queries. There is no variant below for a function call, a query, a subquery, a
 * loop, or a regular expression. The prohibition is therefore an absence in the grammar rather than a
 * rule someone must remember — and a tenant cannot author a guard that reaches outside the fact set
 * because the syntax has no way to say it.
 *
 * <p>There is also no arithmetic. A guard comparing {@code score * 2 > threshold} would put a
 * calculation in a decision path whose result must be reproducible; {@code PRD-RSK-023} keeps score
 * computation in the scoring module where its inputs are recorded. The engine compares facts, it does
 * not derive them.
 *
 * <p><b>Specificity</b> is the count of leaf constraints, used by the most-specific-wins matching of
 * DOC-28 section 11.1. It is computed from the structure rather than declared by the author, because a
 * declared specificity can be wrong and a computed one cannot.
 */
public sealed interface Condition {

    /** Conjunction. Empty means vacuously true, which {@link #alwaysTrue()} states more clearly. */
    record All(List<Condition> operands) implements Condition {
        public All {
            operands = List.copyOf(Objects.requireNonNull(operands, "operands are required"));
        }
    }

    /** Disjunction. Empty means vacuously false. */
    record Any(List<Condition> operands) implements Condition {
        public Any {
            operands = List.copyOf(Objects.requireNonNull(operands, "operands are required"));
        }
    }

    /** Negation. Note {@code NOT UNDEFINED} is UNDEFINED — see {@link RuleOutcome#negate()}. */
    record Not(Condition operand) implements Condition {
        public Not {
            Objects.requireNonNull(operand, "operand is required");
        }
    }

    /** Compares a named fact against a literal. */
    record Comparison(String factKey, Operator operator, FactValue operand) implements Condition {
        public Comparison {
            Objects.requireNonNull(factKey, "factKey is required");
            Objects.requireNonNull(operator, "operator is required");
            Objects.requireNonNull(operand, "operand is required");
            if (operand instanceof FactValue.Absent) {
                throw new IllegalArgumentException(
                        "a condition cannot compare against Absent. To test for absence use Present "
                                + "wrapped in Not — an explicit test, so that a reader can see the author "
                                + "meant it rather than inferring it from a sentinel.");
            }
            if (operator.relational() && !operand.ordered()) {
                throw new IllegalArgumentException(
                        "operator " + operator + " is relational but " + operand.getClass().getSimpleName()
                                + " has no defined total order. A relational comparison on an unordered "
                                + "type would be implementation-dependent, and comparison is "
                                + "authorization-relevant wherever a fact participates in a guard.");
            }
        }
    }

    /**
     * True where the fact is present, whatever its value.
     *
     * <p>Its own variant rather than a comparison, because presence is the one test that is defined
     * over an absent fact — and product principle 1 makes "was this measured at all" a first-class
     * question rather than an edge case.
     */
    record Present(String factKey) implements Condition {
        public Present {
            Objects.requireNonNull(factKey, "factKey is required");
        }
    }

    /**
     * True where the named scope fact lies within the given node's subtree.
     *
     * <p>DOC-28 section 11.1's {@code org_scope} dimension "applies to a subtree". Modelled as its own
     * variant because subtree containment is a traversal over the recorded ancestor path, not a value
     * comparison — and it must use the path recorded on the object rather than the current tree, or a
     * reorganization would retroactively change which policy applied ({@code SEC-AUZ-028}).
     */
    record WithinSubtree(String factKey, OrgNodeId ancestor) implements Condition {
        public WithinSubtree {
            Objects.requireNonNull(factKey, "factKey is required");
            Objects.requireNonNull(ancestor, "ancestor is required");
        }
    }

    /** A rule with no constraints. Matches everything, with specificity zero. */
    record AlwaysTrue() implements Condition {}

    static Condition alwaysTrue() {
        return new AlwaysTrue();
    }

    /**
     * The number of leaf constraints, for the most-specific-wins matching of DOC-28 section 11.1.
     *
     * <p>Counted over the structure. A disjunction contributes the <em>minimum</em> of its branches,
     * because a rule that matches through its least specific branch is no more specific than that
     * branch — taking the maximum would let an author inflate specificity by adding an unreachable
     * narrow alternative, which is a configuration gaming path.
     */
    default int specificity() {
        return switch (this) {
            case AlwaysTrue _ -> 0;
            case Present _ -> 1;
            case Comparison _ -> 1;
            case WithinSubtree _ -> 1;
            case Not n -> n.operand().specificity();
            case All a -> a.operands().stream().mapToInt(Condition::specificity).sum();
            case Any a -> a.operands().isEmpty()
                    ? 0
                    : a.operands().stream().mapToInt(Condition::specificity).min().orElse(0);
        };
    }

    /** Node count, used by the definition-time cost bound. */
    default int nodeCount() {
        return switch (this) {
            case AlwaysTrue _ -> 1;
            case Present _ -> 1;
            case Comparison _ -> 1;
            case WithinSubtree _ -> 1;
            case Not n -> 1 + n.operand().nodeCount();
            case All a -> 1 + a.operands().stream().mapToInt(Condition::nodeCount).sum();
            case Any a -> 1 + a.operands().stream().mapToInt(Condition::nodeCount).sum();
        };
    }

    /** Expression depth, used by the definition-time cost bound. */
    default int depth() {
        return switch (this) {
            case AlwaysTrue _ -> 1;
            case Present _ -> 1;
            case Comparison _ -> 1;
            case WithinSubtree _ -> 1;
            case Not n -> 1 + n.operand().depth();
            case All a -> 1 + a.operands().stream().mapToInt(Condition::depth).max().orElse(0);
            case Any a -> 1 + a.operands().stream().mapToInt(Condition::depth).max().orElse(0);
        };
    }
}
