package aspm.kernel.rulesengine.contract;

/**
 * The evaluation port for {@code CON-PLT-012}'s four callers — workflow guards, automation rules, service-level
 * policy matching, and checklist selection.
 *
 * <p><b>Why this interface exists at all.</b> The evaluator itself lives in {@code rules-engine-impl}, which no
 * other module may depend on (ADR-003, {@code CON-PLT-014}). Without a port in the contract, a consuming module's
 * only options are to reach into another module's implementation — which the build rejects — or to write its own
 * evaluator. DOC-02 section 6.2 rules the second out in one line: "Four implementations would diverge, and one of
 * them governs authorization-relevant workflow transitions." Divergence there is a privilege escalation.
 *
 * <p>The same reasoning that moved {@code ScopeResolver} into {@code authorization-contract} applies in the
 * opposite direction here: there, the implementors were external; here, the consumers are.
 *
 * <p><b>Implementations must be deterministic and side-effect free</b> ({@code PRD-WRK-033}). A guard is
 * evaluated on every transition attempt including denied ones, so a non-deterministic implementation makes a
 * transition's availability unpredictable, and one that reached a model would place AI in a decision path
 * (ADR-005). {@link Condition}'s grammar makes most of that structural — there is no way to express a function
 * call, a query, or a regular expression — but an implementation could still violate it, which is why the
 * obligation is stated on the port rather than assumed from the grammar.
 */
@FunctionalInterface
public interface ConditionEvaluation {

    /**
     * Evaluates a condition against a fact set.
     *
     * @return three-valued: {@link RuleOutcome#UNDEFINED} where a referenced fact is absent. A caller treating
     *     {@code UNDEFINED} as false would let a missing fact silently open a guarded transition
     */
    RuleOutcome evaluate(Condition condition, FactSet facts);
}
