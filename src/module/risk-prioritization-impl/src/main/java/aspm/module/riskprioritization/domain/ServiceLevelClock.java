package aspm.module.riskprioritization.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The service level clock of DOC-09 section 9 [fixed] and DOC-28 section 11.3.
 *
 * <p>Two behaviours here look wrong on first reading and are correct. Both have tests naming them as such.
 *
 * <p><b>{@code PRD-RSK-035}: a score increase recomputes the deadline from the ORIGINAL start, and may produce
 * an immediately-breached state.</b> DOC-28's reasoning: "A newly known-exploited finding must acquire the
 * shorter deadline. Restarting would give a finding that has been open for weeks a fresh three days, which is
 * the wrong direction entirely. Recomputing from the original start may produce an immediately-breached state,
 * which is the correct and honest outcome."
 *
 * <p>An immediate breach reads like a bug and is the truth: a finding that has been open for six weeks and turns
 * out to be actively exploited <em>is</em> past a three-day deadline.
 *
 * <p><b>{@code PRD-RSK-036}: a score decrease never extends a deadline.</b> "Otherwise downgrading severity is a
 * mechanism for extending deadlines, which is a gaming path." So {@link #recomputeForScoreChange} shortens and
 * never lengthens, and extension is a separate, approved, separately-reported state.
 *
 * <p><b>{@code PRD-RSK-033}: the calendar is snapshotted at start.</b> A calendar change afterwards does not move
 * {@code dueAt} — because "a three-day deadline spanning a public holiday is not three working days", and a
 * tenant editing its holiday list should not silently move every open deadline.
 */
public final class ServiceLevelClock {

    public enum State {
        RUNNING,
        PAUSED,
        BREACHED,
        /** Approved extension. <b>Distinct from met</b> ({@code INV-RSK-11}). */
        EXTENDED,
        MET,
        CANCELLED;

        public boolean isTerminal() {
            return this == MET || this == CANCELLED;
        }
    }

    /**
     * Why the clock is paused. {@code PRD-RSK-034} requires an enumerated attribution.
     *
     * <p>"Unattributed delay defaults to blaming the accountable team, which is usually wrong and always
     * corrosive. Enumeration prevents attribution becoming free text nobody can aggregate."
     */
    public enum BlockingAttribution {
        /** Waiting on the requester. Escalation does not fire; a separate chain escalates the requester. */
        REQUESTER,
        /** Waiting on a third party — a vendor patch, an upstream fix. */
        THIRD_PARTY,
        /** Waiting on the security function itself. Included so the platform can be blamed too. */
        SECURITY_FUNCTION;

        /**
         * {@code PRD-RSK-037}: escalation does not fire while paused for requester or third-party blocking.
         *
         * <p>"A separate chain escalates the <em>blocking</em> party." Escalating the accountable team for a
         * delay they did not cause is how a team learns to ignore escalations.
         */
        public boolean suppressesRemediationEscalation() {
            return this == REQUESTER || this == THIRD_PARTY;
        }
    }

    /** A closed interval of clock time, so paused duration is reportable separately from elapsed. */
    public record Interval(Instant from, Instant to, boolean running, BlockingAttribution attribution) {

        public Duration duration() {
            return Duration.between(from, to);
        }
    }

    private final UUID id;
    private final UUID subjectId;
    private final Instant startedAt;
    private final int policyVersion;
    private final String calendarSnapshotReference;
    private final Duration originalTarget;
    private final Instant originalDueAt;

    private State state = State.RUNNING;
    private Instant dueAt;
    private Instant currentIntervalFrom;
    private BlockingAttribution currentAttribution;
    private final List<Interval> intervals = new ArrayList<>();
    private Instant breachedAt;
    private Instant resolvedAt;
    private String extensionReason;

    /**
     * Starts a clock.
     *
     * @param policyVersion pinned at start. {@code PRD-RSK-032}: "A clock MUST pin its policy version at start,
     *     and a later policy change MUST NOT move an existing deadline." A policy change that moved existing
     *     deadlines would make commitments unstable and breaches unattributable
     * @param calendarSnapshotReference the business calendar as it was. {@code PRD-RSK-033}
     * @param businessTimeTarget the target duration in business time, already computed against the snapshot
     */
    public ServiceLevelClock(UUID id, UUID subjectId, Instant startedAt, int policyVersion,
            String calendarSnapshotReference, Duration businessTimeTarget, Instant computedDueAt) {
        this.id = Objects.requireNonNull(id, "id is required");
        this.subjectId = Objects.requireNonNull(subjectId, "subjectId is required");
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt is required");
        this.calendarSnapshotReference = Objects.requireNonNull(calendarSnapshotReference,
                "the calendar snapshot reference is required (PRD-RSK-033). Without it a tenant editing its "
                        + "holiday list would silently move every open deadline, and a three-day deadline "
                        + "spanning a public holiday is not three working days.");
        this.originalTarget = Objects.requireNonNull(businessTimeTarget, "the target duration is required");
        this.originalDueAt = Objects.requireNonNull(computedDueAt, "the computed due date is required");
        if (policyVersion < 1) {
            throw new IllegalArgumentException(
                    "the policy version is required and pinned at start (PRD-RSK-032)");
        }
        this.policyVersion = policyVersion;
        this.dueAt = computedDueAt;
        this.currentIntervalFrom = startedAt;
    }

    /** Pauses. A blocking attribution is required, not optional. */
    public void pause(BlockingAttribution attribution, Instant at) {
        Objects.requireNonNull(attribution,
                "a blocking attribution is required to pause (PRD-RSK-034). Unattributed delay defaults to "
                        + "blaming the accountable team, which is usually wrong and always corrosive.");
        Objects.requireNonNull(at, "the pause instant is required");
        require(State.RUNNING, "pause");
        intervals.add(new Interval(currentIntervalFrom, at, true, null));
        currentIntervalFrom = at;
        currentAttribution = attribution;
        state = State.PAUSED;
    }

    /** Resumes. {@code dueAt} shifts by the paused duration, so paused time is not charged to the team. */
    public void resume(Instant at) {
        Objects.requireNonNull(at, "the resume instant is required");
        require(State.PAUSED, "resume");
        Duration paused = Duration.between(currentIntervalFrom, at);
        intervals.add(new Interval(currentIntervalFrom, at, false, currentAttribution));
        dueAt = dueAt.plus(paused);
        currentIntervalFrom = at;
        currentAttribution = null;
        state = State.RUNNING;
    }

    /**
     * Recomputes for a score change. {@code PRD-RSK-035} and {@code PRD-RSK-036}.
     *
     * <p><b>Shortens only.</b> A shorter policy is applied from the <b>original start</b>, which may put
     * {@code dueAt} in the past — and the clock then reports itself breached, which is the honest answer. A
     * longer policy is ignored: {@code PRD-RSK-036} makes automatic extension a gaming path, and extension
     * requires {@link #extend} with an approver and a reason.
     *
     * @param newTargetFromOriginalStart the new policy's target, measured from the original start
     * @return true where the deadline moved
     */
    public boolean recomputeForScoreChange(Duration newTargetFromOriginalStart, Instant newDueAt,
            Instant at) {
        Objects.requireNonNull(newTargetFromOriginalStart, "the new target is required");
        Objects.requireNonNull(newDueAt, "the new due date is required");
        if (state != State.RUNNING && state != State.PAUSED) {
            throw new IllegalStateException(
                    "a score change recomputes only a live clock; this one is " + state);
        }

        if (newTargetFromOriginalStart.compareTo(originalTarget) >= 0) {
            // PRD-RSK-036. Silently ignored rather than raising, because a score decrease is a legitimate and
            // frequent event — it is the DEADLINE EXTENSION that must not follow from it. Raising here would
            // make callers avoid reporting score decreases.
            return false;
        }

        // PRD-RSK-035: from the ORIGINAL start. Any paused time already granted is preserved by adding the
        // accumulated pause, so a team is not charged for a delay somebody else caused even when the deadline
        // shortens underneath them.
        Duration pausedSoFar = totalPausedDuration();
        dueAt = newDueAt.plus(pausedSoFar);
        return true;
    }

    /**
     * Whether the clock has breached.
     *
     * <p>Computed rather than requiring a sweep to have run, so a freshly recomputed clock reports the truth
     * immediately. This is what makes {@code PRD-RSK-035}'s "may become immediately breached" observable rather
     * than dependent on a scheduled job.
     */
    public boolean isBreachedAt(Instant now) {
        if (state == State.MET || state == State.CANCELLED) {
            return false;
        }
        if (state == State.BREACHED) {
            return true;
        }
        return now.isAfter(dueAt);
    }

    /** Records the breach. Idempotent, so a sweep running twice does not double-escalate. */
    public void breach(Instant at) {
        Objects.requireNonNull(at, "the breach instant is required");
        if (state == State.BREACHED) {
            return;
        }
        if (state != State.RUNNING) {
            throw new IllegalStateException("only a RUNNING clock breaches; this one is " + state);
        }
        breachedAt = at;
        state = State.BREACHED;
    }

    /**
     * Extends the deadline. A distinct state, <b>not</b> met ({@code INV-RSK-11}).
     *
     * <p>DOC-28 section 13.2 lists deadline extension as a gaming path and the control as: "Extension is a
     * distinct approved state, reported separately from met." A clock that reached {@code MET} after an extension
     * would make the extension invisible in every service-level figure.
     */
    public void extend(Instant newDueAt, String reason, Instant at) {
        Objects.requireNonNull(newDueAt, "a new due date is required");
        Objects.requireNonNull(at, "the extension instant is required");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "an extension requires a recorded reason. An unexplained extension is indistinguishable "
                            + "from a met deadline in every aggregate, which is exactly the gaming path "
                            + "DOC-28 section 13.2 names.");
        }
        if (state != State.RUNNING && state != State.BREACHED) {
            throw new IllegalStateException("only a RUNNING or BREACHED clock is extendable");
        }
        if (!newDueAt.isAfter(dueAt)) {
            throw new IllegalArgumentException("an extension must move the deadline later");
        }
        dueAt = newDueAt;
        extensionReason = reason;
        state = State.EXTENDED;
    }

    /** The subject reached a satisfying terminal state before {@code dueAt}. */
    public void meet(Instant at) {
        Objects.requireNonNull(at, "the resolution instant is required");
        if (state != State.RUNNING && state != State.EXTENDED) {
            throw new IllegalStateException(
                    "meet requires RUNNING or EXTENDED; a BREACHED clock uses meetLate, which retains the "
                            + "breach (DOC-09 section 9)");
        }
        if (at.isAfter(dueAt)) {
            throw new IllegalArgumentException(
                    "resolution at " + at + " is after dueAt " + dueAt + "; that is meetLate, and recording it "
                            + "as met would erase the breach from every service-level figure");
        }
        resolvedAt = at;
        intervals.add(new Interval(currentIntervalFrom, at, true, null));
        state = State.MET;
    }

    /**
     * The subject resolved after breach. The clock <b>stays</b> {@code BREACHED}.
     *
     * <p>DOC-09 section 9: "Breach retained with resolution time." A late resolution is not a met deadline, and a
     * state machine that converted one into the other would make the breach rate improvable by finishing late.
     */
    public void meetLate(Instant at) {
        Objects.requireNonNull(at, "the resolution instant is required");
        require(State.BREACHED, "meet_late");
        resolvedAt = at;
        intervals.add(new Interval(currentIntervalFrom, at, true, null));
        // state stays BREACHED. Deliberately.
    }

    public void cancel(Instant at) {
        Objects.requireNonNull(at, "the cancellation instant is required");
        if (state.isTerminal()) {
            throw new IllegalStateException("a terminal clock cannot be cancelled");
        }
        resolvedAt = at;
        state = State.CANCELLED;
    }

    /** Total paused duration, reportable separately from elapsed ({@code PRD-RSK-034}). */
    public Duration totalPausedDuration() {
        Duration total = intervals.stream()
                .filter(i -> !i.running())
                .map(Interval::duration)
                .reduce(Duration.ZERO, Duration::plus);
        if (state == State.PAUSED) {
            // The open interval is not yet closed, so it is not in the list. Excluding it would understate
            // paused time for exactly the clock somebody is currently looking at.
            total = total.plus(Duration.between(currentIntervalFrom, Instant.now()));
        }
        return total;
    }

    /** Whether remediation escalation should fire, per {@code PRD-RSK-037}. */
    public boolean escalationFires() {
        if (state == State.PAUSED && currentAttribution != null) {
            return !currentAttribution.suppressesRemediationEscalation();
        }
        return state == State.BREACHED;
    }

    public UUID id() {
        return id;
    }

    public UUID subjectId() {
        return subjectId;
    }

    public State state() {
        return state;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant dueAt() {
        return dueAt;
    }

    /** Retained through recomputation and extension, so the original commitment stays visible. */
    public Instant originalDueAt() {
        return originalDueAt;
    }

    public int policyVersion() {
        return policyVersion;
    }

    public String calendarSnapshotReference() {
        return calendarSnapshotReference;
    }

    public Optional<Instant> breachedAt() {
        return Optional.ofNullable(breachedAt);
    }

    public Optional<Instant> resolvedAt() {
        return Optional.ofNullable(resolvedAt);
    }

    public Optional<String> extensionReason() {
        return Optional.ofNullable(extensionReason);
    }

    public Optional<BlockingAttribution> currentAttribution() {
        return Optional.ofNullable(currentAttribution);
    }

    public List<Interval> intervals() {
        return List.copyOf(intervals);
    }

    private void require(State expected, String event) {
        if (state != expected) {
            throw new IllegalStateException(
                    "event '" + event + "' requires " + expected + " but the clock is " + state);
        }
    }
}
