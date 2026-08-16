package aspm.module.ingestion.domain;

import aspm.sharedkernel.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A record held back from ingestion, per DOC-11 section 9.
 *
 * <p>"A single malformed record in a 40,000-record file must not discard the file, <b>and must not be silently
 * skipped either</b>." The second half is what this type exists for.
 *
 * <p>{@code PRD-ING-039}: "Quarantined records MUST be retrievable with their raw content and failing reason,
 * correctable, and resubmittable without re-importing the source." And the reason it is a requirement rather
 * than a nicety: <b>"Quarantine that cannot be resolved is deletion with extra steps."</b>
 *
 * <p>So this type carries the raw content and the failing field, and {@link #resolve} exists. A
 * quarantine record with no correction path would be a queue that only grows, and a queue that only grows is
 * ignored within a week.
 */
public final class QuarantinedRecord {

    /** Why the record was held. The set of quarantinable failures from DOC-11 section 9's table. */
    public enum Reason {
        /** Failed schema validation. The failing field is named. */
        SCHEMA_VALIDATION,
        /** Evidence exceeded a limit. The finding is ingested; only the evidence is quarantined. */
        EVIDENCE_LIMIT_EXCEEDED,
        /**
         * A severity the source used that maps to nothing.
         *
         * <p>Note this does <b>not</b> quarantine the record: {@code PRD-ING-040} requires the finding to be
         * ingested with severity {@code UNKNOWN} and the gap reported. Present here as a reason so a mapping gap
         * is retrievable through the same queue, rather than through a second mechanism nobody checks.
         */
        SEVERITY_MAPPING_GAP
    }

    public enum State {
        QUARANTINED,
        /** Corrected and resubmitted; a new record entered the pipeline. */
        RESOLVED,
        /** Deliberately abandoned, with a reason. Not the same as forgotten. */
        DISCARDED
    }

    private final UUID id;
    private final TenantId tenantId;
    private final UUID importSessionId;
    private final Reason reason;
    private final List<String> failingFields;
    private final String rawContent;
    private final Instant quarantinedAt;

    private State state = State.QUARANTINED;
    private String resolutionNote;

    public QuarantinedRecord(UUID id, TenantId tenantId, UUID importSessionId, Reason reason,
            List<String> failingFields, String rawContent, Instant quarantinedAt) {
        this.id = Objects.requireNonNull(id, "id is required");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId is required");
        this.importSessionId = Objects.requireNonNull(importSessionId, "importSessionId is required");
        this.reason = Objects.requireNonNull(reason, "reason is required");
        this.failingFields = List.copyOf(Objects.requireNonNull(failingFields, "failingFields is required"));
        this.rawContent = Objects.requireNonNull(rawContent,
                "the raw content is required. PRD-ING-039 makes a quarantined record retrievable WITH its raw "
                        + "content and correctable; without the content there is nothing to correct, and "
                        + "quarantine that cannot be resolved is deletion with extra steps");
        this.quarantinedAt = Objects.requireNonNull(quarantinedAt, "quarantinedAt is required");

        if (reason == Reason.SCHEMA_VALIDATION && failingFields.isEmpty()) {
            throw new IllegalArgumentException(
                    "a schema validation failure must name the failing field (DOC-11 section 9). 'Failed "
                            + "validation' is not correctable, so it is not resolvable, so it is deletion.");
        }
    }

    /**
     * Marks the record resolved after correction and resubmission.
     *
     * <p>{@code PRD-ING-039} requires resubmission "without re-importing the source" — the raw content is held
     * here precisely so the original file is not needed. The new record enters the pipeline as an ordinary
     * record, so it is fingerprinted by the same code and deduplicates against existing findings normally.
     */
    public void resolve(String note) {
        // Argument validation BEFORE the mutation. A first version assigned the state and then validated,
        // so a rejected call left the record settled — and the next legitimate call then failed with
        // "requires a QUARANTINED record". A partially applied rejection is worse than either outcome,
        // because the record is now in a state nobody chose.
        Objects.requireNonNull(note, "a resolution note is required");
        requireQuarantined("resolution");
        resolutionNote = note;
        state = State.RESOLVED;
    }

    /**
     * Abandons the record deliberately.
     *
     * <p>Requires a reason, so that a discarded record is a decision somebody made rather than a row that aged
     * out. The distinction matters when a coverage question is asked later: records discarded for a stated reason
     * are a known gap, and records that quietly vanished are an unknown one.
     */
    public void discard(String reason) {
        Objects.requireNonNull(reason,
                "a discard reason is required; a record that quietly vanished is an unknown coverage gap "
                        + "whereas one discarded for a stated reason is a known one");
        requireQuarantined("discard");
        resolutionNote = reason;
        state = State.DISCARDED;
    }

    public UUID id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public UUID importSessionId() {
        return importSessionId;
    }

    public Reason reason() {
        return reason;
    }

    public List<String> failingFields() {
        return failingFields;
    }

    /** The raw content, retained so the record is correctable without the source file. */
    public String rawContent() {
        return rawContent;
    }

    public Instant quarantinedAt() {
        return quarantinedAt;
    }

    public State state() {
        return state;
    }

    public Optional<String> resolutionNote() {
        return Optional.ofNullable(resolutionNote);
    }

    private void requireQuarantined(String operation) {
        if (state != State.QUARANTINED) {
            throw new IllegalStateException(
                    operation + " requires a QUARANTINED record; this one is " + state);
        }
    }
}
