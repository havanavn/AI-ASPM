package aspm.module.workmanagement.domain;

import aspm.sharedkernel.ScopeDescriptor;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A work item. Aggregate root; DOC-03 section 13.1, DOC-04 section 16.2.
 *
 * <p>The work item tracks <b>intent</b>; the finding tracks <b>the world</b> (DOC-03 section 5.4). That
 * separation is why the remediation workflow mirrors the finding lifecycle without duplicating it — closing the
 * work item does not close the finding, because deciding you are done is not the same as the vulnerability being
 * gone.
 *
 * <h2>The four invariants this class carries</h2>
 *
 * <ul>
 *   <li><b>{@code INV-WRK-01}</b> — the workflow definition version is pinned at creation and never changes. A
 *       workflow change does not strand in-flight items.
 *   <li><b>{@code INV-WRK-05}</b> — assignment is to exactly one individual. A single field, not a collection:
 *       an item assigned to three people is assigned to nobody. Supporters are {@link ParticipantRole#SUPPORT}
 *       participants.
 *   <li><b>{@code INV-WRK-06}</b> — scope derives from the <b>subject object</b>, not from the creator. This is
 *       PP-4 at the point it is most tempting to skip: taking the creator's scope is one line shorter and lets a
 *       broad-scope user create an item nobody else in the subject's own tree can see.
 *   <li><b>{@code INV-WRK-15}</b> — derived effort and manual effort are separate fields, and the manual one
 *       never overwrites the derived one.
 * </ul>
 *
 * <h2>{@code INV-WRK-17} — concurrency</h2>
 *
 * <p>Every mutation takes the row version the caller read and rejects a mismatch. {@code PRD-WRK-021}: "Silent
 * last-write-wins loses work and, worse, loses it invisibly — the person whose change vanished believes it was
 * saved." DOC-09 section 18 gives the resolution per situation; {@link StaleWriteException} is what carries the
 * {@code 412} back.
 */
public final class WorkItem {

    /** Raised where the caller's row version is behind. Maps to {@code 412} (DOC-09 section 18). */
    public static final class StaleWriteException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final int expected;
        private final int actual;

        StaleWriteException(int expected, int actual) {
            super("the item has moved on: you read version " + expected + ", it is now " + actual
                    + ". Re-read and re-apply (PRD-WRK-021, PRD-WRK-043). Neither change is discarded — this "
                    + "is the explicit conflict that stops the loser believing their edit was saved.");
            this.expected = expected;
            this.actual = actual;
        }

        public int expected() {
            return expected;
        }

        public int actual() {
            return actual;
        }
    }

    /** What the item is about. {@code INV-WRK-16}: the item references it and the object exposes its work. */
    public enum SubjectKind {
        FINDING,
        ASSESSMENT,
        ASSET,
        EXCEPTION,
        /** Standalone work — a governance task, an enablement piece. Carries no subject identifier. */
        NONE
    }

    private final UUID id;
    private final String itemCode;
    private final UUID typeId;
    private final UUID workflowDefinitionId;
    private final int workflowDefinitionVersion;
    private final SubjectKind subjectKind;
    private final Optional<UUID> subjectId;
    private final ScopeDescriptor scope;
    private final UUID createdBy;
    private final Instant createdAt;

    private UUID stateId;
    private String title;
    private String description;
    private UUID assigneeId;
    private UUID parentItemId;
    private final Map<String, Object> attributes = new LinkedHashMap<>();
    private BigDecimal effortDerivedDays = BigDecimal.ZERO;
    private BigDecimal effortManualDays;
    private BigDecimal estimatedEffortDays;
    private int rowVersion = 1;
    private Instant updatedAt;

    private WorkItem(UUID id, String itemCode, UUID typeId, WorkflowDefinition definition,
            SubjectKind subjectKind, UUID subjectId, ScopeDescriptor scope, String title, UUID createdBy,
            Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id is required");
        this.itemCode = Objects.requireNonNull(itemCode, "an item code is required");
        this.typeId = Objects.requireNonNull(typeId, "typeId is required");
        Objects.requireNonNull(definition, "a workflow definition is required");
        this.subjectKind = Objects.requireNonNull(subjectKind, "subjectKind is required");
        this.subjectId = Optional.ofNullable(subjectId);
        this.scope = Objects.requireNonNull(scope,
                "a scope descriptor is required and derives from the SUBJECT, not the creator (INV-WRK-06). "
                        + "Taking the creator's scope is one line shorter and lets a broad-scope user create an "
                        + "item nobody in the subject's own tree can see.");
        this.title = Objects.requireNonNull(title, "a title is required");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy is required");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt is required");
        this.updatedAt = createdAt;

        if (definition.state() != WorkflowDefinition.State.ACTIVE) {
            throw new IllegalArgumentException(
                    "an item cannot be created against a " + definition.state() + " workflow definition. A "
                            + "DRAFT definition has not been validated (INV-WRK-02), and a RETIRED one was "
                            + "withdrawn for a reason.");
        }
        if (title.isBlank()) {
            throw new IllegalArgumentException("a blank title makes an item unfindable in every list view");
        }
        if (subjectKind != SubjectKind.NONE && subjectId == null) {
            throw new IllegalArgumentException(
                    "subject kind " + subjectKind + " requires a subject identifier (INV-WRK-16). Without it "
                            + "the object cannot expose its associated work, and the bidirectional link the "
                            + "invariant requires exists in one direction only.");
        }
        if (subjectKind == SubjectKind.NONE && subjectId != null) {
            throw new IllegalArgumentException("a subject identifier with kind NONE references nothing");
        }

        // INV-WRK-01: pinned here, and there is no setter.
        this.workflowDefinitionId = definition.id();
        this.workflowDefinitionVersion = definition.version();
        this.stateId = definition.initialStateId();
    }

    /**
     * Creates an item about a domain object.
     *
     * @param subjectScope the <b>subject's</b> scope descriptor, read from the object the item concerns. The
     *     parameter is named for where it must come from, because the mistake this invariant guards is passing
     *     the creator's
     */
    public static WorkItem createFor(UUID id, String itemCode, UUID typeId, WorkflowDefinition definition,
            SubjectKind subjectKind, UUID subjectId, ScopeDescriptor subjectScope, String title,
            UUID createdBy, Instant at) {
        if (subjectKind == SubjectKind.NONE) {
            throw new IllegalArgumentException("use createStandalone for an item with no subject");
        }
        return new WorkItem(id, itemCode, typeId, definition, subjectKind, subjectId, subjectScope, title,
                createdBy, at);
    }

    /**
     * Creates standalone work — governance, enablement, platform engineering.
     *
     * <p>Here the scope <b>is</b> supplied by the creator, because there is no subject to derive it from. A
     * separate factory rather than a null subject, so that the one case where {@code INV-WRK-06} does not apply
     * is explicit at the call site rather than inferred from an argument being absent.
     */
    public static WorkItem createStandalone(UUID id, String itemCode, UUID typeId,
            WorkflowDefinition definition, ScopeDescriptor scope, String title, UUID createdBy, Instant at) {
        return new WorkItem(id, itemCode, typeId, definition, SubjectKind.NONE, null, scope, title, createdBy,
                at);
    }

    // ------------------------------------------------------------------ mutation, all version-checked

    /**
     * Applies a transition's state change.
     *
     * <p>Only the state change: the transition record, the side effects and the event publication belong to the
     * caller's transaction, because {@code PRD-WRK-032} requires all four atomic together and this aggregate
     * cannot span them. Authorization and guards have already run — see {@link TransitionEvaluation}.
     */
    public void applyTransition(UUID toStateId, int expectedRowVersion, Instant at) {
        requireVersion(expectedRowVersion);
        Objects.requireNonNull(toStateId, "toStateId is required");
        this.stateId = toStateId;
        touch(at);
    }

    /**
     * Assigns to exactly one individual, or clears the assignment.
     *
     * <p>{@code INV-WRK-05}. A single field is the enforcement: there is no collection here to add a second
     * assignee to, so the invariant cannot be violated by a caller who did not know about it.
     */
    public void assignTo(UUID principalId, int expectedRowVersion, Instant at) {
        requireVersion(expectedRowVersion);
        this.assigneeId = principalId;
        touch(at);
    }

    public void retitle(String newTitle, int expectedRowVersion, Instant at) {
        requireVersion(expectedRowVersion);
        Objects.requireNonNull(newTitle, "a title is required");
        if (newTitle.isBlank()) {
            throw new IllegalArgumentException("a blank title makes an item unfindable in every list view");
        }
        this.title = newTitle;
        touch(at);
    }

    public void describe(String newDescription, int expectedRowVersion, Instant at) {
        requireVersion(expectedRowVersion);
        this.description = newDescription;
        touch(at);
    }

    public void setAttribute(String key, Object value, int expectedRowVersion, Instant at) {
        requireVersion(expectedRowVersion);
        Objects.requireNonNull(key, "an attribute key is required");
        if (value == null) {
            attributes.remove(key);
        } else {
            attributes.put(key, value);
        }
        touch(at);
    }

    /**
     * Sets the parent for a sub-item.
     *
     * <p>Sub-items are "checklist entries without independent scheduling" (DOC-03 section 13.1) — a hierarchy
     * one level deep in intent, though the schema does not enforce depth. What is enforced is the cheap case
     * that would otherwise deadlock a tree walk.
     */
    public void reparent(UUID newParentItemId, int expectedRowVersion, Instant at) {
        requireVersion(expectedRowVersion);
        if (id.equals(newParentItemId)) {
            throw new IllegalArgumentException("an item cannot be its own parent");
        }
        this.parentItemId = newParentItemId;
        touch(at);
    }

    // ------------------------------------------------------------------ effort, INV-WRK-15

    /**
     * Recomputes derived effort from the transition log.
     *
     * <p>{@code INV-WRK-15} and ADR-021: effort derives from state duration. Only time in states where the
     * clock was running counts — charging a team for time somebody else blocked would make the figure an
     * argument rather than a measurement.
     *
     * <p><b>Never touches {@link #effortManualDays}.</b> The two are separate columns in DOC-04 section 16.2 for
     * the same reason they are separate fields here: a manual adjustment is a person's judgement about what the
     * derived figure missed, and overwriting either with the other destroys the comparison that makes the
     * adjustment meaningful.
     */
    public void recomputeDerivedEffort(TransitionLog log, int expectedRowVersion, Instant at) {
        requireVersion(expectedRowVersion);
        Objects.requireNonNull(log, "a transition log is required");
        if (!log.workItemId().equals(id)) {
            throw new IllegalArgumentException("that log belongs to item " + log.workItemId());
        }
        Duration running = log.clockRunningDuration();
        this.effortDerivedDays = BigDecimal.valueOf(running.toMinutes())
                .divide(BigDecimal.valueOf(1440), 2, RoundingMode.HALF_UP);
        touch(at);
    }

    /**
     * Records a manual effort adjustment alongside the derived figure.
     *
     * @throws IllegalArgumentException on a negative value. Negative effort is not a correction, it is an
     *     attempt to reduce a total, and a capacity model built on it would understate load
     */
    public void recordManualEffort(BigDecimal days, int expectedRowVersion, Instant at) {
        requireVersion(expectedRowVersion);
        if (days != null && days.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("manual effort cannot be negative; got " + days);
        }
        this.effortManualDays = days;
        touch(at);
    }

    public void estimate(BigDecimal days, int expectedRowVersion, Instant at) {
        requireVersion(expectedRowVersion);
        if (days != null && days.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("an estimate of zero or less is not an estimate");
        }
        this.estimatedEffortDays = days;
        touch(at);
    }

    /**
     * The effort figure to report, and where it came from.
     *
     * <p>Returns both together so a consumer cannot present a manually adjusted figure as a measured one.
     * {@code PRD-RSK-039} makes estimation bias reportable, which needs the two distinguishable.
     */
    public record EffortFigure(BigDecimal days, boolean manuallyAdjusted, BigDecimal derivedDays) {
    }

    public EffortFigure effort() {
        return effortManualDays != null
                ? new EffortFigure(effortManualDays, true, effortDerivedDays)
                : new EffortFigure(effortDerivedDays, false, effortDerivedDays);
    }

    // ------------------------------------------------------------------ accessors

    private void requireVersion(int expectedRowVersion) {
        if (expectedRowVersion != rowVersion) {
            throw new StaleWriteException(expectedRowVersion, rowVersion);
        }
    }

    private void touch(Instant at) {
        Objects.requireNonNull(at, "the modification instant is required");
        rowVersion++;
        updatedAt = at;
    }

    public UUID id() {
        return id;
    }

    public String itemCode() {
        return itemCode;
    }

    public UUID typeId() {
        return typeId;
    }

    public UUID workflowDefinitionId() {
        return workflowDefinitionId;
    }

    /** Pinned at creation ({@code INV-WRK-01}). There is deliberately no setter. */
    public int workflowDefinitionVersion() {
        return workflowDefinitionVersion;
    }

    public UUID stateId() {
        return stateId;
    }

    public String title() {
        return title;
    }

    public Optional<String> description() {
        return Optional.ofNullable(description);
    }

    /** {@code INV-WRK-05}: one individual, or none. */
    public Optional<UUID> assigneeId() {
        return Optional.ofNullable(assigneeId);
    }

    public SubjectKind subjectKind() {
        return subjectKind;
    }

    public Optional<UUID> subjectId() {
        return subjectId;
    }

    /**
     * The scope as it was when the item was created.
     *
     * <p>Immutable after creation ({@code PRD-WRK-042}): reorganization must not modify scope descriptors,
     * because they record the scope as it was and that is what makes historical reporting reproducible. There is
     * no setter, and {@code V009} carries the same rule as a trigger.
     */
    public ScopeDescriptor scope() {
        return scope;
    }

    public Optional<UUID> parentItemId() {
        return Optional.ofNullable(parentItemId);
    }

    public Map<String, Object> attributes() {
        return Map.copyOf(attributes);
    }

    public Optional<BigDecimal> estimatedEffortDays() {
        return Optional.ofNullable(estimatedEffortDays);
    }

    public int rowVersion() {
        return rowVersion;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public UUID createdBy() {
        return createdBy;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
