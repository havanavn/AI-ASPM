package aspm.module.assessment.domain;

import aspm.sharedkernel.ScopeDescriptor;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * An assessment. Aggregate root; DOC-03 section 9.3, DOC-09 section 5.
 *
 * <h2>The two invariants that make an assessment mean anything</h2>
 *
 * <p>DOC-03 section 9.3 on {@code INV-ASM-12} and {@code INV-ASM-13}: "These two invariants are the whole of PP-1
 * applied to manual work. An assessment reporting no findings is meaningless without knowing what was examined,
 * and 'no findings' is indistinguishable from 'we did not look' unless coverage is recorded."
 *
 * <p>{@code INV-ASM-12} is enforced at {@link #complete}: an assessment with unassessed items completes only with
 * an explicit, recorded acknowledgement naming what was not done. {@code INV-ASM-13} is enforced in
 * {@link ItemResult}, which cannot represent a reasonless exclusion.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p><b>Findings</b> ({@code INV-ASM-15}). They are produced through Ingestion (ADR-011), because one
 * normalization and deduplication pipeline shared by file import and manual assessment is what makes a
 * fingerprint identical regardless of source path ({@code INV-VUL-06}). An assessment holding its own findings
 * would be a second creation site and the two would diverge.
 *
 * <p><b>Condition closure</b> ({@code INV-ASM-14}). Conditions are tracked independently, "because conditional
 * approval whose conditions are never verified is the characteristic failure of architecture review. Attaching
 * them to the assessment means they close when the assessment does, which is precisely the failure." This
 * aggregate raises them and then has no power to close them.
 */
public final class Assessment {

    /** DOC-09 section 5. Per-type variants add states; these are the shipped base. */
    public enum State {
        PLANNED,
        IN_PROGRESS,
        BLOCKED,
        AWAITING_REVIEW,
        COMPLETED,
        ABANDONED;

        public boolean isTerminal() {
            return this == COMPLETED || this == ABANDONED;
        }
    }

    /**
     * A condition attached to a conditional approval.
     *
     * <p>Raised by the assessment and closed elsewhere ({@code INV-ASM-14}). It carries its own owner and
     * deadline for that reason: a condition without them has nobody to chase and no date to chase them on, which
     * is how conditional approval becomes unconditional in practice.
     */
    public record Condition(UUID id, String statement, UUID ownerPrincipalId, java.time.LocalDate dueBy) {

        public Condition {
            Objects.requireNonNull(id, "id is required");
            Objects.requireNonNull(statement, "a statement is required");
            Objects.requireNonNull(ownerPrincipalId,
                    "a condition needs an owner (INV-ASM-14). Conditional approval whose conditions are never "
                            + "verified is the characteristic failure of architecture review, and an ownerless "
                            + "condition has nobody to chase.");
            Objects.requireNonNull(dueBy, "a condition needs a date, or there is never a day it is late");
            if (statement.isBlank()) {
                throw new IllegalArgumentException("a blank condition states nothing to satisfy");
            }
        }
    }

    /** {@code INV-ASM-12}: what was not assessed, why, and who accepted that. */
    public record IncompletenessAcknowledgement(String reason, List<UUID> unassessedItemIds,
            UUID acknowledgedBy, Instant acknowledgedAt) {

        public IncompletenessAcknowledgement {
            Objects.requireNonNull(reason, "a reason is required");
            Objects.requireNonNull(acknowledgedBy, "acknowledgedBy is required");
            Objects.requireNonNull(acknowledgedAt, "acknowledgedAt is required");
            unassessedItemIds = List.copyOf(
                    Objects.requireNonNull(unassessedItemIds, "the unassessed items are required"));
            if (reason.isBlank()) {
                throw new IllegalArgumentException(
                        "an acknowledgement without a reason is a checkbox, and a checkbox is what an "
                                + "acknowledgement requirement degrades into when it does not demand words");
            }
            if (unassessedItemIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "an acknowledgement naming no items acknowledges nothing; if coverage is complete, "
                                + "none is needed");
            }
        }
    }

    /** {@code INV-ASM-16}: derived effort and manual adjustment, separately. Same shape as {@code INV-WRK-15}. */
    public record EffortFigure(BigDecimal days, boolean manuallyAdjusted, BigDecimal derivedDays) {
    }

    private final UUID id;
    private final UUID typeId;
    private final Optional<UUID> requestId;
    private final List<UUID> scopedAssetIds;
    private final ScopeDescriptor scope;
    private final UUID leadPrincipalId;

    private State state = State.PLANNED;
    private final List<ChecklistInstance> checklists = new ArrayList<>();
    private final List<Condition> conditions = new ArrayList<>();
    private IncompletenessAcknowledgement acknowledgement;
    private String outcome;
    private Instant startedAt;
    private Instant completedAt;
    private BigDecimal effortDerivedDays = BigDecimal.ZERO;
    private BigDecimal effortManualDays;
    private Optional<String> revisionReference = Optional.empty();

    private Assessment(UUID id, UUID typeId, UUID requestId, List<UUID> scopedAssetIds,
            ScopeDescriptor assetOwnershipScope, UUID leadPrincipalId) {
        this.id = Objects.requireNonNull(id, "id is required");
        this.typeId = Objects.requireNonNull(typeId, "typeId is required");
        this.requestId = Optional.ofNullable(requestId);
        this.scopedAssetIds = List.copyOf(Objects.requireNonNull(scopedAssetIds, "scoped assets are required"));
        this.scope = Objects.requireNonNull(assetOwnershipScope,
                "the scope derives from the scoped assets' ownership, NEVER from the assessor's scope "
                        + "(INV-ASM-10). An assessor with broad scope would otherwise produce an assessment "
                        + "whose findings the owning team cannot see.");
        this.leadPrincipalId = Objects.requireNonNull(leadPrincipalId, "a lead is required");
        if (this.scopedAssetIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "an assessment needs at least one asset in scope (DOC-09 section 5); without one there is "
                            + "nothing for its findings to attach to");
        }
    }

    /**
     * Creates an assessment.
     *
     * @param assetOwnershipScope resolved from the scoped assets' <b>ownership</b>. Named for where it must come
     *     from, because the mistake {@code INV-ASM-10} guards is passing the assessor's scope
     */
    public static Assessment create(UUID id, UUID typeId, UUID requestId, List<UUID> scopedAssetIds,
            ScopeDescriptor assetOwnershipScope, UUID leadPrincipalId) {
        return new Assessment(id, typeId, requestId, scopedAssetIds, assetOwnershipScope, leadPrincipalId);
    }

    /** Instantiates a checklist at its published version ({@code INV-ASM-18}). */
    public ChecklistInstance addChecklist(ChecklistDefinition definition, UUID instanceId) {
        Objects.requireNonNull(definition, "a definition is required");
        if (state.isTerminal()) {
            throw new IllegalStateException("a " + state + " assessment does not take new checklists");
        }
        ChecklistInstance instance = definition.instantiate(instanceId, id);
        checklists.add(instance);
        return instance;
    }

    public void recordRevisionReference(String reference) {
        this.revisionReference = Optional.ofNullable(reference);
    }

    // ------------------------------------------------------------------ the machine

    public void start(Instant at) {
        requireState(State.PLANNED, "start");
        this.startedAt = Objects.requireNonNull(at, "the start instant is required");
        this.state = State.IN_PROGRESS;
    }

    /**
     * Blocks. DOC-09 section 5 requires an attribution, and the clock pauses.
     *
     * <p>The attribution is a string here rather than the work-management enum, because a cross-module type
     * dependency in that direction is not permitted (ADR-003) and inventing a shared-kernel type for a value two
     * modules spell differently would put vocabulary in the kernel that neither owns.
     */
    public void block(String blockingAttribution, Instant at) {
        requireState(State.IN_PROGRESS, "block");
        if (blockingAttribution == null || blockingAttribution.isBlank()) {
            throw new IllegalArgumentException(
                    "blocking requires an attribution (DOC-09 section 5, PRD-RSK-034). Unattributed delay "
                            + "defaults to blaming the accountable team (PP-6).");
        }
        this.state = State.BLOCKED;
    }

    public void unblock(Instant at) {
        requireState(State.BLOCKED, "unblock");
        this.state = State.IN_PROGRESS;
    }

    /**
     * Completes the working phase. The {@code INV-ASM-12} gate.
     *
     * @param acknowledgement required where any item is unassessed, and refused where none is. An
     *     acknowledgement on a complete assessment is a habit that makes the requirement meaningless when it
     *     matters
     */
    public void complete(IncompletenessAcknowledgement acknowledgement, Instant at) {
        requireState(State.IN_PROGRESS, "complete");
        Objects.requireNonNull(at, "the completion instant is required");

        CoverageSummary coverage = coverage();
        if (!coverage.complete() && acknowledgement == null) {
            throw new IllegalStateException(
                    "coverage is incomplete and no acknowledgement was recorded (INV-ASM-12). "
                            + coverage.presentation() + ". An assessment reporting no findings is meaningless "
                            + "without knowing what was examined, and 'no findings' is indistinguishable from "
                            + "'we did not look'.");
        }
        if (coverage.complete() && acknowledgement != null) {
            throw new IllegalArgumentException(
                    "coverage is complete; an acknowledgement here acknowledges nothing, and recording one "
                            + "routinely is what makes the requirement meaningless on the assessment where it "
                            + "matters");
        }
        if (acknowledgement != null) {
            List<UUID> actuallyUnassessed = unassessedItemIds();
            if (!acknowledgement.unassessedItemIds().containsAll(actuallyUnassessed)) {
                throw new IllegalArgumentException(
                        "the acknowledgement names " + acknowledgement.unassessedItemIds().size()
                                + " item(s) but " + actuallyUnassessed.size() + " are unassessed. A partial "
                                + "acknowledgement understates the gap while appearing to disclose it.");
            }
        }
        this.acknowledgement = acknowledgement;
        this.state = State.AWAITING_REVIEW;
    }

    public void returnForRework(String reason, Instant at) {
        requireState(State.AWAITING_REVIEW, "return");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("returning an assessment requires a reason");
        }
        this.state = State.IN_PROGRESS;
    }

    /**
     * Approves. DOC-09 section 5's guard: <b>reviewer ≠ lead</b>.
     *
     * @param conditionsRaised raised as independent items ({@code INV-ASM-14}). They are recorded here and
     *     closed elsewhere; this aggregate has no method to close one
     */
    public void approve(UUID reviewerId, String outcome, List<Condition> conditionsRaised, Instant at) {
        requireState(State.AWAITING_REVIEW, "approve");
        Objects.requireNonNull(reviewerId, "a reviewer is required");
        Objects.requireNonNull(outcome, "an outcome is required");
        Objects.requireNonNull(conditionsRaised, "conditions are required, possibly empty");
        if (reviewerId.equals(leadPrincipalId)) {
            throw new IllegalArgumentException(
                    "the reviewer is the assessment's own lead (DOC-09 section 5). Self-review is not review, "
                            + "and it is the control an auditor tests first.");
        }
        this.outcome = outcome;
        this.conditions.addAll(conditionsRaised);
        this.completedAt = Objects.requireNonNull(at, "the approval instant is required");
        this.state = State.COMPLETED;
    }

    /**
     * Abandons, retaining partial coverage.
     *
     * <p>DOC-09 section 5: "an abandoned assessment that examined 200 of 351 items is more informative than
     * none." Nothing is discarded, and {@link #coverage} keeps answering.
     */
    public void abandon(String reason, Instant at) {
        if (state != State.PLANNED && state != State.IN_PROGRESS) {
            throw new IllegalStateException("only a PLANNED or IN_PROGRESS assessment is abandoned");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("abandonment requires a reason");
        }
        this.completedAt = Objects.requireNonNull(at, "the abandonment instant is required");
        this.state = State.ABANDONED;
    }

    // ------------------------------------------------------------------ derived

    /**
     * {@code INV-ASM-11}: derived across every checklist instance, never set.
     *
     * <p>Summing the instances rather than averaging their ratios. Averaging two checklists of 10 and 300 items
     * would weight them equally, and a fully covered 10-item checklist would half-cancel an untouched 300-item
     * one.
     */
    public CoverageSummary coverage() {
        List<ItemResult> all = new ArrayList<>();
        for (ChecklistInstance instance : checklists) {
            all.addAll(instance.results().values());
        }
        return CoverageSummary.from(all);
    }

    public List<UUID> unassessedItemIds() {
        List<UUID> unassessed = new ArrayList<>();
        for (ChecklistInstance instance : checklists) {
            instance.unassessedItems().forEach(item -> unassessed.add(item.id()));
        }
        return List.copyOf(unassessed);
    }

    /** {@code INV-ASM-16}. Same two-field shape as {@code INV-WRK-15}, for the same reason. */
    public void recordDerivedEffort(BigDecimal days) {
        Objects.requireNonNull(days, "a derived effort is required");
        if (days.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("derived effort cannot be negative");
        }
        this.effortDerivedDays = days;
    }

    public void recordManualEffort(BigDecimal days) {
        if (days != null && days.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("manual effort cannot be negative");
        }
        this.effortManualDays = days;
    }

    public EffortFigure effort() {
        return effortManualDays != null
                ? new EffortFigure(effortManualDays, true, effortDerivedDays)
                : new EffortFigure(effortDerivedDays, false, effortDerivedDays);
    }

    // ------------------------------------------------------------------ accessors

    private void requireState(State expected, String event) {
        if (state != expected) {
            throw new IllegalStateException("'" + event + "' requires " + expected + "; this one is " + state);
        }
    }

    public UUID id() {
        return id;
    }

    public UUID typeId() {
        return typeId;
    }

    public Optional<UUID> requestId() {
        return requestId;
    }

    public List<UUID> scopedAssetIds() {
        return scopedAssetIds;
    }

    /** Derived from asset ownership ({@code INV-ASM-10}). There is no setter. */
    public ScopeDescriptor scope() {
        return scope;
    }

    public UUID leadPrincipalId() {
        return leadPrincipalId;
    }

    public State state() {
        return state;
    }

    public List<ChecklistInstance> checklists() {
        return List.copyOf(checklists);
    }

    /** Raised here, closed elsewhere ({@code INV-ASM-14}). There is deliberately no {@code closeCondition}. */
    public List<Condition> conditions() {
        return List.copyOf(conditions);
    }

    public Optional<IncompletenessAcknowledgement> incompletenessAcknowledgement() {
        return Optional.ofNullable(acknowledgement);
    }

    public Optional<String> outcome() {
        return Optional.ofNullable(outcome);
    }

    public Optional<Instant> startedAt() {
        return Optional.ofNullable(startedAt);
    }

    public Optional<Instant> completedAt() {
        return Optional.ofNullable(completedAt);
    }

    public Optional<String> revisionReference() {
        return revisionReference;
    }
}
