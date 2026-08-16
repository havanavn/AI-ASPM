package aspm.kernel.rulesengine.domain;

import aspm.kernel.rulesengine.contract.Condition;
import aspm.kernel.rulesengine.contract.ConditionEvaluation;
import aspm.kernel.rulesengine.contract.FactSet;
import aspm.kernel.rulesengine.contract.RuleOutcome;

/**
 * The single implementation of {@link ConditionEvaluation}, delegating to {@link ConditionEvaluator}.
 *
 * <p>A thin adapter rather than a rewrite: the evaluator stays static and collaborator-free, which is what makes
 * its side-effect freedom structural rather than a promise ({@code PRD-WRK-033}). This class exists only so the
 * capability can cross a module boundary as an interface.
 */
public final class SharedConditionEvaluation implements ConditionEvaluation {

    @Override
    public RuleOutcome evaluate(Condition condition, FactSet facts) {
        return ConditionEvaluator.evaluate(condition, facts);
    }
}
