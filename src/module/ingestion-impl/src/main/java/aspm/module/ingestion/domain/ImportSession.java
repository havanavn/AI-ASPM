package aspm.module.ingestion.domain;

import aspm.sharedkernel.TenantId;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The import session state machine of DOC-09 section 15 [fixed].
 *
 * <p><b>{@code PRD-ING-038} is the load-bearing rule: a parse failure ingests nothing.</b> DOC-09 section 15's
 * edge cases say why: "partial parse results are not normalized, because a truncated record set could be read as
 * records having been removed." A closure path seeing 12,000 of 40,000 records has no way to tell absence from
 * removal, and DOC-22 has the same failure in the composition domain.
 *
 * <p>That is why {@link State#PARSING} transitions either to {@code NORMALIZING} or to {@code FAILED}, and there
 * is no path from {@code PARSING} to a partial ingest. {@code COMPLETED_WITH_QUARANTINE} is reached from
 * {@code NORMALIZING}, after a complete parse — quarantine is per record within a successfully parsed document,
 * which is a different thing from a truncated document.
 */
public final class ImportSession {

    /** The states of DOC-09 section 15, exactly. */
    public enum State {
        QUEUED,
        PARSING,
        NORMALIZING,
        /** Terminal. Nothing was ingested. */
        FAILED,
        COMPLETED,
        /** Terminal. Valid records ingested; the quarantine queue is populated. */
        COMPLETED_WITH_QUARANTINE,
        REVERSED;

        public boolean isTerminal() {
            return this == FAILED || this == COMPLETED || this == COMPLETED_WITH_QUARANTINE
                    || this == REVERSED;
        }

        boolean ingestedAnything() {
            return this == COMPLETED || this == COMPLETED_WITH_QUARANTINE;
        }
    }

    /**
     * Per-record dispositions. {@code PRD-ING-041} requires counts by disposition, never only a total.
     *
     * <p>"A total of 39,997 out of 40,000 is not actionable; knowing that three were quarantined for one reason
     * is." So the outcome is a map over this enum and there is no {@code total()} accessor that could be reported
     * on its own.
     */
    public enum Disposition {
        INGESTED,
        UPDATED,
        REOPENED,
        SUPPRESSED,
        QUARANTINED,
        /** Duplicated another record in the same file; merged within the session and counted once. */
        MERGED
    }

    private final UUID id;
    private final TenantId tenantId;
    private final String idempotencyKey;
    private final String sourceFormat;
    private final String sourceFormatVersion;
    private final Instant initiatedAt;
    private final Instant reversibleUntil;

    private State state = State.QUEUED;
    private String failureReason;
    private final Map<Disposition, Integer> counts = new EnumMap<>(Disposition.class);
    private final Map<String, Integer> stageRecordCounts = new java.util.LinkedHashMap<>();

    public ImportSession(UUID id, TenantId tenantId, String idempotencyKey, String sourceFormat,
            String sourceFormatVersion, Instant initiatedAt, java.time.Duration reversibleFor) {
        this.id = Objects.requireNonNull(id, "id is required");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId is required");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey,
                "an idempotency key is required; DOC-11 section 10 derives it from source content and target so "
                        + "a repeat returns the original session rather than ingesting again");
        this.sourceFormat = Objects.requireNonNull(sourceFormat, "sourceFormat is required");
        this.sourceFormatVersion = Objects.requireNonNull(sourceFormatVersion,
                "the format VERSION is required. TST-ING-001: a source tool changing its output produces silent "
                        + "field mis-mapping, not a parse error, and the per-version fixture is the only "
                        + "detection mechanism — which needs the version recorded");
        this.initiatedAt = Objects.requireNonNull(initiatedAt, "initiatedAt is required");
        Objects.requireNonNull(reversibleFor, "a reversal window is required");
        this.reversibleUntil = initiatedAt.plus(reversibleFor);
        for (Disposition d : Disposition.values()) {
            counts.put(d, 0);
        }
    }

    public void startParsing() {
        require(State.QUEUED, "start");
        state = State.PARSING;
    }

    /**
     * The parse succeeded completely.
     *
     * @param quarantinedRecordCount records that failed schema validation within a fully parsed document.
     *     Distinct from a parse failure: the document was read in full, so absence means absence
     */
    public void parsed(int recordsExtracted, int quarantinedRecordCount) {
        require(State.PARSING, "parsed");
        if (recordsExtracted < 0 || quarantinedRecordCount < 0) {
            throw new IllegalArgumentException("record counts are non-negative");
        }
        stageRecordCounts.put("PARSE", recordsExtracted);
        counts.merge(Disposition.QUARANTINED, quarantinedRecordCount, Integer::sum);
        state = State.NORMALIZING;
    }

    /**
     * The parse failed. Nothing is ingested. {@code PRD-ING-038}.
     *
     * <p>There is deliberately no {@code parsedPartially} method. A truncated record set reaching normalization
     * would be indistinguishable to closure logic from records having been removed, and closure that treats
     * absence as removal is the failure product principle 1 exists to prevent.
     */
    public void parseFailed(String reason) {
        // Validated before the mutation, for the reason recorded in QuarantinedRecord.resolve: a rejected
        // call that has already changed state leaves the object in a state nobody chose.
        Objects.requireNonNull(reason,
                "a failure reason is required and is returned to the submitter; DOC-11 section 9 requires the "
                        + "limit or unsupported version to be NAMED, because 'rejected' is not actionable");
        require(State.PARSING, "parse_failed");
        failureReason = reason;
        state = State.FAILED;
    }

    /** Rejected before parsing — a size limit, an unsupported format version, an unused idempotency key. */
    public void rejectBeforeParsing(String reason) {
        Objects.requireNonNull(reason, "a rejection names its reason");
        require(State.QUEUED, "reject");
        failureReason = reason;
        state = State.FAILED;
    }

    /**
     * Normalization completed.
     *
     * <p>The terminal state is chosen from the quarantine count rather than passed in, so a caller cannot report
     * {@code COMPLETED} on a session that quarantined records — which would hide the queue that
     * {@code PRD-ING-039} requires to be resolvable.
     */
    public void normalized(Map<Disposition, Integer> dispositionCounts) {
        require(State.NORMALIZING, "complete");
        Objects.requireNonNull(dispositionCounts, "disposition counts are required (PRD-ING-041)");
        dispositionCounts.forEach((disposition, count) -> {
            if (count < 0) {
                throw new IllegalArgumentException("record counts are non-negative");
            }
            counts.merge(disposition, count, Integer::sum);
        });
        stageRecordCounts.put("NORMALIZE",
                counts.values().stream().mapToInt(Integer::intValue).sum());
        state = counts.get(Disposition.QUARANTINED) > 0
                ? State.COMPLETED_WITH_QUARANTINE
                : State.COMPLETED;
    }

    /**
     * Reverses the session.
     *
     * <p>DOC-09 section 15: "Reversal restores prior state rather than deleting, so a finding that existed before
     * the import and was modified by it returns to its earlier state rather than disappearing." This method
     * records the state change; the restoration itself is the caller's, and DOC-11 section 10 enumerates it
     * per effect.
     *
     * @param at must be within the reversal window
     * @param hasDistinctPermission whether the caller holds the distinct reversal permission DOC-09 section 15
     *     requires — supplied rather than checked here, because authorization is the kernel's single contract
     */
    public void reverse(Instant at, boolean hasDistinctPermission) {
        if (!state.ingestedAnything()) {
            throw new IllegalStateException(
                    "only a session that ingested something can be reversed; this session is " + state
                            + ". Reversing a FAILED session would suggest something was undone when nothing "
                            + "was done (PRD-ING-038).");
        }
        if (!at.isBefore(reversibleUntil)) {
            throw new IllegalStateException(
                    "the reversal window closed at " + reversibleUntil + ". Beyond it, findings have been "
                            + "triaged, assigned and commented on, and restoring prior state would discard work "
                            + "done since.");
        }
        if (!hasDistinctPermission) {
            throw new IllegalStateException(
                    "reversal requires a permission distinct from import (DOC-09 section 15). Reversal removes "
                            + "findings, so a principal who may import must not thereby be able to un-import.");
        }
        state = State.REVERSED;
    }

    public UUID id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public String sourceFormat() {
        return sourceFormat;
    }

    public String sourceFormatVersion() {
        return sourceFormatVersion;
    }

    public State state() {
        return state;
    }

    public Optional<String> failureReason() {
        return Optional.ofNullable(failureReason);
    }

    /** Counts by disposition. {@code PRD-ING-041}: never only a total. */
    public Map<Disposition, Integer> dispositionCounts() {
        return Map.copyOf(counts);
    }

    /** Per-stage record counts. {@code PRD-ING-020}: a session must be diagnosable to a stage. */
    public Map<String, Integer> stageRecordCounts() {
        return Map.copyOf(stageRecordCounts);
    }

    public Instant reversibleUntil() {
        return reversibleUntil;
    }

    /**
     * When the session was initiated.
     *
     * <p>Read by the session listing and by the reversal-window report. Exposed rather than kept private because
     * PRD-ING-020 requires a session to be diagnosable, and "when did this start" is the first question asked of
     * a session that ingested 12,000 of 40,000 records.
     */
    public Instant initiatedAt() {
        return initiatedAt;
    }

    /** True where anything reached the finding store. */
    public boolean ingestedAnything() {
        return state.ingestedAnything();
    }

    private void require(State expected, String event) {
        if (state != expected) {
            throw new IllegalStateException(
                    "event '" + event + "' requires state " + expected + " but the session is " + state
                            + ". DOC-09 section 15's transitions are the whole permitted set; there is no "
                            + "partial-ingest path from PARSING because a truncated record set could be read as "
                            + "records having been removed (PRD-ING-038).");
        }
    }
}
