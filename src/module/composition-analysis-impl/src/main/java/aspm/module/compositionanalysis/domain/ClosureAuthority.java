package aspm.module.compositionanalysis.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * May this match run drive a finding closure? {@code PRD-SBM-053}, {@code PRD-SBM-055}, and the module-wide
 * invariant {@code PRD-SBM-065}.
 *
 * <p>DOC-22 section 8.2 states what is at stake: "A failed run returns no components. Under naive logic —
 * component absent therefore remediated — a single failure auto-closes every dependency finding for that project.
 * <b>This has occurred in production deployments of comparable tooling and destroys data trust irrecoverably</b>,
 * because the team learns that closure is meaningless."
 *
 * <p>The same invariant is {@code INV-VUL-13} on the vulnerability side, and prompt 7 built the finding half of
 * it. This is the composition half: the finding aggregate refuses to close without coverage evidence, and this
 * class is what decides whether a run can produce that evidence at all.
 *
 * <h2>Why a type, and why {@link #authorize} returns a reason on refusal</h2>
 *
 * <p>Four preconditions must hold <b>together</b>, and a fifth is per-component. Expressed as an {@code if} in
 * the closure path, the fifth gets forgotten — it is the one DOC-22 calls "the most subtle closure error in the
 * module". Expressed as a value that must be obtained before closing, all five are in one place with one reader.
 *
 * <p>A refusal names every unmet precondition, because the operator asking "why did this run not close anything"
 * is usually asking about a coverage gap they can fix.
 */
public final class ClosureAuthority {

    /** How a match run ended. Only one value permits closure, and it is not the only value that means success. */
    public enum RunOutcome {
        COMPLETED,
        /**
         * {@code PRD-SBM-050}: nothing changed since the last successful run, so the run was skipped.
         *
         * <p>Recorded as a run, deliberately — "without a record, the coverage timeline shows a gap, and a gap
         * is indistinguishable from a failure". It does <b>not</b> authorize closure: no components were
         * evaluated, so absence proves nothing.
         */
        SKIPPED_NO_CHANGE,
        FAILED,
        TIMED_OUT,
        /** The worker's lease expired and the run was reclaimed ({@code PRD-SBM-048}). */
        LEASE_EXPIRED,
        CANCELLED
    }

    /** Snapshot quality relative to the warning threshold. {@code PRD-SBM-053}. */
    public enum SnapshotQuality {
        ABOVE_WARNING,
        AT_OR_BELOW_WARNING,
        REJECTED
    }

    /**
     * Everything the decision needs, gathered at one point.
     *
     * @param coverageConfirmed the run enumerated the snapshot's components and knows it did. Distinct from
     *     "the run completed": a run can complete having read an empty snapshot
     * @param intelligenceAge how old the dataset was. Stale intelligence does not stop matching
     *     ({@code PRD-SBM-063}) but it does stop closure — matching against six-month-old data can find a
     *     vulnerability, and cannot establish that one is gone
     * @param snapshotEcosystems the ecosystems this snapshot actually covered. The input to
     *     {@code PRD-SBM-055}
     */
    public record RunContext(RunOutcome outcome, boolean coverageConfirmed, Duration intelligenceAge,
            Duration intelligenceStalenessThreshold, SnapshotQuality snapshotQuality,
            Set<Ecosystem> snapshotEcosystems, Instant evaluatedAt) {

        public RunContext {
            Objects.requireNonNull(outcome, "a run outcome is required");
            Objects.requireNonNull(intelligenceAge, "the intelligence age is required (PRD-SBM-061)");
            Objects.requireNonNull(intelligenceStalenessThreshold, "a staleness threshold is required");
            Objects.requireNonNull(snapshotQuality, "the snapshot quality is required");
            snapshotEcosystems = Set.copyOf(
                    Objects.requireNonNull(snapshotEcosystems, "the covered ecosystems are required"));
            Objects.requireNonNull(evaluatedAt, "evaluatedAt is required");
        }

        boolean intelligenceStale() {
            return intelligenceAge.compareTo(intelligenceStalenessThreshold) > 0;
        }
    }

    /** The decision. Obtainable only from {@link #authorize}. */
    public record Decision(boolean mayDriveClosure, List<String> refusals) {

        public Decision {
            refusals = List.copyOf(Objects.requireNonNull(refusals, "refusals are required, possibly empty"));
        }

        /** What to tell an operator asking why nothing closed. */
        public String explanation() {
            return mayDriveClosure
                    ? "this run may drive closure: it completed with confirmed coverage, against non-stale "
                            + "intelligence, over a snapshot above the quality warning threshold"
                    : "this run MUST NOT drive closure (PRD-SBM-053, PRD-SBM-065): " + refusals;
        }
    }

    private ClosureAuthority() {
    }

    /**
     * The run-level gate. Four preconditions, all required.
     *
     * <p>Note what is <b>not</b> here: any notion of "the run mostly worked". Every failure mode in DOC-22
     * section 11 must "degrade toward <i>less</i> confidence, never more" ({@code PRD-SBM-065}), and a partial
     * credit rule is how less becomes more.
     */
    public static Decision authorize(RunContext context) {
        Objects.requireNonNull(context, "a run context is required");
        List<String> refusals = new ArrayList<>();

        if (context.outcome() != RunOutcome.COMPLETED) {
            refusals.add("the run ended " + context.outcome()
                    + "; a failed run returns no components, and under naive logic — component absent "
                    + "therefore remediated — one failure auto-closes every dependency finding for the "
                    + "project. That has happened in production tooling and destroys data trust "
                    + "irrecoverably.");
        }
        if (!context.coverageConfirmed()) {
            refusals.add("coverage was not confirmed; a run can complete having read a snapshot it did not "
                    + "fully enumerate, and absence of evidence is not evidence of remediation (PP-1)");
        }
        if (context.intelligenceStale()) {
            refusals.add("the intelligence dataset is " + context.intelligenceAge().toDays()
                    + " days old, beyond the " + context.intelligenceStalenessThreshold().toDays()
                    + "-day threshold. Stale intelligence can still FIND a vulnerability, which is why "
                    + "matching continues (PRD-SBM-063); it cannot establish that one is gone.");
        }
        if (context.snapshotQuality() != SnapshotQuality.ABOVE_WARNING) {
            refusals.add("snapshot quality is " + context.snapshotQuality()
                    + "; a low-quality snapshot under-reports components, and every under-reported component "
                    + "looks like a removed one");
        }

        return new Decision(refusals.isEmpty(), refusals);
    }

    /**
     * The per-component gate. {@code PRD-SBM-055}, "the most subtle closure error in the module".
     *
     * <p>DOC-22's scenario, worth reproducing because it is not hypothetical: "A team splits its pipeline and
     * one job begins submitting an SBOM covering only its own ecosystem. Every finding in the other ecosystems
     * appears remediated. Nothing failed, the run completed successfully, the snapshot quality is high — and the
     * closure is wrong for hundreds of findings."
     *
     * <p>Every run-level precondition passes in that scenario. That is why this check is separate and why it
     * takes the component's ecosystem rather than being folded into {@link #authorize}: the run is fine, and
     * the answer differs per component.
     *
     * @param componentEcosystem the ecosystem of the component whose finding is a closure candidate
     * @return true only where the run may close AND this snapshot actually covered that ecosystem
     */
    public static boolean mayCloseComponent(Decision runDecision, RunContext context,
            Ecosystem componentEcosystem) {
        Objects.requireNonNull(runDecision, "a run decision is required");
        Objects.requireNonNull(context, "a run context is required");
        Objects.requireNonNull(componentEcosystem, "the component's ecosystem is required");

        if (!runDecision.mayDriveClosure()) {
            return false;
        }
        // Ecosystem-aware absence is the only safe interpretation. A component of an ecosystem this snapshot
        // did not cover is not absent — it was not looked for.
        return context.snapshotEcosystems().contains(componentEcosystem);
    }

    /**
     * Why a specific component was not closed, for the operator and for the audit record.
     *
     * <p>{@code PRD-SBM-054} requires automatic closure to be distinguishable from human-verified closure and to
     * record its evidence. The symmetric obligation — recording why closure did <i>not</i> happen — is what
     * stops a suppressed closure looking like an oversight.
     */
    public static String refusalFor(Decision runDecision, RunContext context, Ecosystem componentEcosystem) {
        if (!runDecision.mayDriveClosure()) {
            return runDecision.explanation();
        }
        if (!context.snapshotEcosystems().contains(componentEcosystem)) {
            return "the snapshot did not cover the " + componentEcosystem + " ecosystem, so this component's "
                    + "absence means it was not looked for rather than that it was removed (PRD-SBM-055). "
                    + "Covered ecosystems: " + context.snapshotEcosystems();
        }
        return "closure is permitted";
    }
}
