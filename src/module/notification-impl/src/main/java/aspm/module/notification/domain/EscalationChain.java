package aspm.module.notification.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Escalation. DOC-13 section 8.
 *
 * <p>"Escalation is what makes a first notification consequential." Chains are tenant-configured because
 * escalation paths are organizational (ADR-027) — the <b>defaults</b> below are shipped configuration, and the
 * three rules in this class are not configurable, because each protects the credibility the mechanism depends
 * on.
 */
public final class EscalationChain {

    /** Who a step escalates to. Kinds are product-fixed; the principals they resolve to are tenant data. */
    public enum TargetKind {
        ASSIGNEE,
        ACCOUNTABLE_OWNER,
        NEAREST_ANCESTOR_OWNER,
        PROGRAM_OWNER,
        /** The separate chain of {@code PRD-NTF-026}, for the party actually blocking. */
        BLOCKING_PARTY
    }

    /**
     * One step, triggered at a proportion of the service level budget.
     *
     * @param atBudgetRatio 0.50, 0.75, 1.00 at breach, 2.00 beyond. A ratio rather than a duration, so one
     *     chain serves a three-day policy and a hundred-and-eighty-day one
     */
    public record Step(BigDecimal atBudgetRatio, Set<TargetKind> targets) {

        public Step {
            Objects.requireNonNull(atBudgetRatio, "a budget ratio is required");
            targets = Set.copyOf(Objects.requireNonNull(targets, "targets are required"));
            if (atBudgetRatio.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException("a step at or below zero budget fires on creation");
            }
            if (targets.isEmpty()) {
                throw new IllegalArgumentException("a step with no target notifies nobody and reads as one");
            }
        }
    }

    /** DOC-13 section 8's default chain for a service level clock. */
    public static final List<Step> DEFAULT_SERVICE_LEVEL_CHAIN = List.of(
            new Step(new BigDecimal("0.50"), Set.of(TargetKind.ASSIGNEE)),
            new Step(new BigDecimal("0.75"), Set.of(TargetKind.ASSIGNEE, TargetKind.ACCOUNTABLE_OWNER)),
            new Step(new BigDecimal("1.00"),
                    Set.of(TargetKind.ACCOUNTABLE_OWNER, TargetKind.NEAREST_ANCESTOR_OWNER)),
            new Step(new BigDecimal("2.00"), Set.of(TargetKind.PROGRAM_OWNER)));

    /** What a fire decision produced. */
    public record Firing(BigDecimal atBudgetRatio, Set<UUID> principals, boolean againstBlockingParty) {

        public Firing {
            Objects.requireNonNull(atBudgetRatio, "a ratio is required");
            principals = Set.copyOf(Objects.requireNonNull(principals, "principals are required"));
        }
    }

    private EscalationChain() {
    }

    /**
     * Decides which steps fire now.
     *
     * <p>Three rules, none configurable:
     *
     * <ul>
     *   <li><b>{@code PRD-NTF-027}: a step fires once.</b> "Without a record, a restart or a recomputation
     *       re-fires the whole chain, and a recipient who received four escalations for one item stops reading
     *       them." The highest already-fired ratio is an argument rather than internal state, so a caller
     *       cannot lose it by reconstructing this object.
     *   <li><b>{@code PRD-NTF-028}: targets resolve at fire time.</b> "The accountable owner may have changed.
     *       Resolving at start would escalate to someone who is no longer responsible." So the resolver is a
     *       parameter here, not a field captured when the clock started.
     *   <li><b>{@code PRD-NTF-026}: nothing fires against the accountable team while paused for requester or
     *       third-party blocking.</b> A separate chain escalates the blocking party. "Escalating the accountable
     *       team for a delay attributable elsewhere destroys the credibility of every subsequent escalation."
     * </ul>
     *
     * @param highestRatioAlreadyFired the last step fired, or empty where none has
     * @param pausedAttribution present while the clock is paused. Where it suppresses the remediation chain,
     *     the blocking-party chain fires instead — the delay is still escalated, at the party causing it
     */
    public static List<Firing> fire(List<Step> chain, BigDecimal currentBudgetRatio,
            Optional<BigDecimal> highestRatioAlreadyFired, Optional<PauseAttribution> pausedAttribution,
            Function<TargetKind, Set<UUID>> resolveAtFireTime) {
        Objects.requireNonNull(chain, "a chain is required");
        Objects.requireNonNull(currentBudgetRatio, "the current budget ratio is required");
        Objects.requireNonNull(highestRatioAlreadyFired, "the fired history is required (PRD-NTF-027)");
        Objects.requireNonNull(pausedAttribution, "pausedAttribution is required, empty while running");
        Objects.requireNonNull(resolveAtFireTime,
                "targets resolve at FIRE time (PRD-NTF-028). Resolving at clock start would escalate to "
                        + "someone who is no longer responsible.");

        List<Firing> firings = new ArrayList<>();
        for (Step step : chain) {
            if (step.atBudgetRatio().compareTo(currentBudgetRatio) > 0) {
                continue;
            }
            if (highestRatioAlreadyFired
                    .filter(fired -> fired.compareTo(step.atBudgetRatio()) >= 0)
                    .isPresent()) {
                continue;
            }

            if (pausedAttribution.filter(PauseAttribution::suppressesRemediationChain).isPresent()) {
                // The remediation chain is suppressed and the blocking party is escalated instead. Both
                // halves matter: suppressing without the separate chain means a blocked item escalates to
                // nobody, which is how a request waits four months.
                Set<UUID> blocking = resolveAtFireTime.apply(TargetKind.BLOCKING_PARTY);
                if (!blocking.isEmpty()) {
                    firings.add(new Firing(step.atBudgetRatio(), blocking, true));
                }
                continue;
            }

            Set<UUID> principals = new LinkedHashSet<>();
            for (TargetKind target : step.targets()) {
                principals.addAll(resolveAtFireTime.apply(target));
            }
            if (!principals.isEmpty()) {
                firings.add(new Firing(step.atBudgetRatio(), principals, false));
            }
        }
        return List.copyOf(firings);
    }

    /**
     * Why a clock is paused, as it bears on escalation.
     *
     * <p>Mirrors {@code PRD-RSK-034}'s set. The mapping from work management's six-value attribution is
     * {@code TransitionBlockingAttribution.escalationAttribution()}, and it lives there rather than here so
     * there is one judgement about a pause rather than two (PP-10).
     */
    public enum PauseAttribution {
        REQUESTER,
        THIRD_PARTY,
        SECURITY_FUNCTION;

        /** {@code PRD-NTF-026}. The security function is escalated against — the platform can be blamed too. */
        public boolean suppressesRemediationChain() {
            return this != SECURITY_FUNCTION;
        }
    }
}
