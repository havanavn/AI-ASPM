package aspm.module.assessment.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Assessment evidence. Aggregate root; DOC-03 section 9.5, {@code PRD-ASM-012}, {@code SEC-PTR-006}.
 *
 * <p>The fourth of the five highest-risk surfaces: <b>content that is expected to be malicious and must remain
 * retrievable</b>. Every design choice here follows from that sentence being literally true rather than a
 * figure of speech.
 *
 * <h2>{@code INV-ASM-21} — a malicious verdict FLAGS; it does not delete</h2>
 *
 * <p>DOC-03 section 9.5: "Pentest evidence is <i>expected</i> to be malicious: a web shell demonstrating an
 * unrestricted upload vulnerability is the proof the finding rests on. Deleting it on an antivirus verdict
 * destroys the evidence for the finding and makes the finding disputable. {@code FLAGGED_AVAILABLE} with
 * mandatory acknowledgement before retrieval is the only design that serves both safety and evidential
 * integrity."
 *
 * <p>This is the one place in the platform where the ordinary security reflex — quarantine and destroy — is the
 * wrong answer, and it is worth stating why it feels wrong: an antivirus product deleting a web shell is
 * behaving correctly by its own lights. It does not know the web shell is Exhibit A.
 *
 * <h2>{@code INV-ASM-22} — excluded from export, notification and AI context at any permission level</h2>
 *
 * <p>"At any permission level" is the whole of it. There is no accessor here that returns content, and
 * {@link #retrievalTicket} is the only path to it — a named, greppable call whose preconditions are checked
 * rather than assumed. An export routine cannot reach the bytes by accident because it cannot reach them at all.
 */
public final class Evidence {

    /** {@code INV-ASM-21}. Availability is derived from the scan, never set directly. */
    public enum Availability {
        /** Scan not complete. Not retrievable by anyone. */
        QUARANTINED,
        /** Scanned clean. */
        AVAILABLE,
        /**
         * Scanned malicious, retained, retrievable only after an explicit acknowledgement.
         *
         * <p>Not a lesser form of {@code AVAILABLE}: the acknowledgement is what makes retrieval a decision
         * somebody made rather than a click they did not read.
         */
        FLAGGED_AVAILABLE
    }

    /** The scan verdict. {@code PENDING} is a state, not an absence — see {@link #availability}. */
    public enum ScanVerdict {
        PENDING,
        CLEAN,
        MALICIOUS,
        /**
         * The scanner could not process the file. Treated as PENDING for retrieval: PP-1, not optimism.
         *
         * <p>Named for DOC-04 section 12.10's {@code malware_verdict} value. An earlier version of this enum
         * called it {@code UNSCANNABLE}, which reads better and is wrong: PP-10 is one name, one meaning, one
         * place, and a domain constant that disagrees with the column it is stored in produces a mapping layer
         * whose two sides drift.
         */
        SCAN_FAILED
    }

    /**
     * Where the bytes live. {@code SEC-PTR-006}: "storage on an origin distinct from the API with non-inline
     * disposition and server-generated filenames".
     *
     * @param storageKey <b>server-generated</b> ({@code INV-ASM-23}). Never derived from the uploaded name: a
     *     filename is attacker-controlled input that reaches a path, a header, and a browser
     * @param isolatedOrigin the host serving it, distinct from the API origin so that a rendered payload runs in
     *     a different origin from the session that fetched it
     */
    public record IsolatedStorageRef(String storageKey, String isolatedOrigin) {

        public IsolatedStorageRef {
            Objects.requireNonNull(storageKey, "a storage key is required");
            Objects.requireNonNull(isolatedOrigin, "an isolated origin is required (SEC-PTR-006)");
            if (!storageKey.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
                throw new IllegalArgumentException(
                        "the storage key must be a server-generated identifier (INV-ASM-23). A key derived "
                                + "from the uploaded filename puts attacker-controlled input into a path, a "
                                + "Content-Disposition header, and eventually a browser.");
            }
        }
    }

    /**
     * A permitted retrieval. Returned by {@link #retrievalTicket} and by nothing else.
     *
     * @param requiresAcknowledgement true for flagged evidence. The caller must have obtained an explicit
     *     acknowledgement; a ticket carrying this flag and no acknowledgement is a bug in the caller, which is
     *     why {@link #retrievalTicket} takes the acknowledgement rather than reporting the need for one
     */
    public record RetrievalTicket(IsolatedStorageRef storage, boolean requiresAcknowledgement,
            String dispositionHeader) {
    }

    /**
     * Maximum retention. {@code INV-ASM-24}: "Indefinite retention of exploit tooling is an accumulating
     * liability, not a conservative default."
     *
     * <p>Two years by default and tenant-configurable downward, not upward. A tenant that wants to keep working
     * exploit material for a decade is describing a breach they have not had yet.
     */
    public static final Duration MAXIMUM_RETENTION = Duration.ofDays(730);

    private final UUID id;
    private final Optional<UUID> assessmentId;
    private final Optional<UUID> findingId;
    private final Optional<String> checklistItemRef;
    private final IsolatedStorageRef storage;
    private final String declaredType;
    private final String verifiedType;
    private final String contentHash;
    private final String originalFilename;
    private final UUID uploadedBy;
    private final Instant uploadedAt;
    private final Instant retentionUntil;

    private ScanVerdict scanVerdict = ScanVerdict.PENDING;
    private Instant scannedAt;
    private String scannerIdentification;

    private Evidence(UUID id, UUID assessmentId, UUID findingId, String checklistItemRef,
            IsolatedStorageRef storage, String declaredType, String verifiedType, String contentHash,
            String originalFilename, UUID uploadedBy, Instant uploadedAt, Duration retention) {
        this.id = Objects.requireNonNull(id, "id is required");
        this.assessmentId = Optional.ofNullable(assessmentId);
        this.findingId = Optional.ofNullable(findingId);
        this.checklistItemRef = Optional.ofNullable(checklistItemRef);
        this.storage = Objects.requireNonNull(storage, "a storage reference is required");
        this.declaredType = Objects.requireNonNull(declaredType, "a declared type is required");
        this.verifiedType = Objects.requireNonNull(verifiedType,
                "a magic-byte verified type is required (SEC-PTR-006). Trusting the declared type is how a "
                        + "polyglot file becomes an image everywhere it is listed and a script where it is "
                        + "served.");
        this.contentHash = Objects.requireNonNull(contentHash, "a content hash is required");
        this.originalFilename = Objects.requireNonNull(originalFilename, "the original filename is required");
        this.uploadedBy = Objects.requireNonNull(uploadedBy, "uploadedBy is required");
        this.uploadedAt = Objects.requireNonNull(uploadedAt, "uploadedAt is required");
        Objects.requireNonNull(retention, "a retention period is required (INV-ASM-24)");

        if (this.assessmentId.isEmpty() && this.findingId.isEmpty()) {
            throw new IllegalArgumentException(
                    "evidence must attach to an assessment or a finding. Unattached evidence is exploit "
                            + "material nobody is accountable for and no retention sweep will find by subject.");
        }
        if (retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("retention must be a positive duration");
        }
        if (retention.compareTo(MAXIMUM_RETENTION) > 0) {
            throw new IllegalArgumentException(
                    "retention of " + retention.toDays() + " days exceeds the maximum of "
                            + MAXIMUM_RETENTION.toDays() + " (INV-ASM-24). Indefinite retention of exploit "
                            + "tooling is an accumulating liability, not a conservative default — and this "
                            + "store is a higher-value target than most systems the platform protects.");
        }
        this.retentionUntil = uploadedAt.plus(retention);
    }

    public static Evidence uploaded(UUID id, UUID assessmentId, UUID findingId, String checklistItemRef,
            IsolatedStorageRef storage, String declaredType, String verifiedType, String contentHash,
            String originalFilename, UUID uploadedBy, Instant uploadedAt, Duration retention) {
        return new Evidence(id, assessmentId, findingId, checklistItemRef, storage, declaredType, verifiedType,
                contentHash, originalFilename, uploadedBy, uploadedAt, retention);
    }

    /**
     * Records the scan result.
     *
     * <p>{@code MALICIOUS} moves the evidence to {@link Availability#FLAGGED_AVAILABLE} — it does not delete,
     * does not quarantine permanently, and does not require anybody's approval to retain. The sample is the
     * evidence.
     */
    public void recordScan(ScanVerdict verdict, String scanner, Instant at) {
        Objects.requireNonNull(verdict, "a verdict is required");
        Objects.requireNonNull(at, "the scan instant is required");
        if (verdict == ScanVerdict.PENDING) {
            throw new IllegalArgumentException("PENDING is the initial state, not a result");
        }
        if (scanner == null || scanner.isBlank()) {
            throw new IllegalArgumentException(
                    "the scanner and its version are required. A verdict whose source is unknown cannot be "
                            + "re-evaluated when the scanner is later found to have been wrong, and a false "
                            + "positive on pentest evidence is the expected case here.");
        }
        this.scanVerdict = verdict;
        this.scannerIdentification = scanner;
        this.scannedAt = at;
    }

    /** {@code INV-ASM-21}: derived from the verdict, never set. */
    public Availability availability() {
        return switch (scanVerdict) {
            // SCAN_FAILED stays quarantined. PP-1: an unscannable file is not a clean one, and an encrypted
            // archive the scanner could not open is exactly the shape a deliberate evasion takes.
            case PENDING, SCAN_FAILED -> Availability.QUARANTINED;
            case CLEAN -> Availability.AVAILABLE;
            case MALICIOUS -> Availability.FLAGGED_AVAILABLE;
        };
    }

    /**
     * The only path to the content.
     *
     * <p>{@code INV-ASM-22} makes evidence absent from every export, notification and AI context "at any
     * permission level". That is enforced by there being nothing else on this class that yields the bytes or
     * their location — see the test that scans for one.
     *
     * @param acknowledgedMaliciousContent required where the evidence is flagged. Passing {@code true}
     *     unconditionally would defeat it, which is why the parameter is named for what the caller is asserting
     *     rather than for what it enables
     * @throws IllegalStateException while quarantined, and where a flagged retrieval is unacknowledged
     */
    public RetrievalTicket retrievalTicket(boolean acknowledgedMaliciousContent) {
        Availability availability = availability();
        if (availability == Availability.QUARANTINED) {
            throw new IllegalStateException(
                    "evidence is not retrievable until the malware scan completes (INV-ASM-21); the verdict is "
                            + scanVerdict + ". An SCAN_FAILED file stays here: an encrypted archive the scanner "
                            + "could not open is exactly the shape a deliberate evasion takes (PP-1).");
        }
        if (availability == Availability.FLAGGED_AVAILABLE && !acknowledgedMaliciousContent) {
            throw new IllegalStateException(
                    "this evidence is flagged malicious and retrieval requires acknowledgement (INV-ASM-21). "
                            + "It is retained deliberately — a web shell demonstrating an unrestricted upload "
                            + "vulnerability is the proof the finding rests on, and deleting it would make the "
                            + "finding disputable. Scanner: " + scannerIdentification);
        }
        // Non-inline, always. An inline disposition on evidence renders attacker-authored content in a browser,
        // and the isolated origin is what limits the damage when somebody removes this line.
        return new RetrievalTicket(storage, availability == Availability.FLAGGED_AVAILABLE,
                "attachment; filename=\"" + safeDownloadName() + "\"");
    }

    /**
     * A download name built from the server-generated key and the verified type.
     *
     * <p>{@code INV-ASM-23}: "Filenames are server-generated. The original name is metadata only, sanitized at
     * display." The original never reaches a header — a filename is attacker-controlled input, and a
     * {@code Content-Disposition} header is a parser.
     */
    public String safeDownloadName() {
        String extension = verifiedType.contains("/")
                ? verifiedType.substring(verifiedType.lastIndexOf('/') + 1).replaceAll("[^a-z0-9]", "")
                : "bin";
        return "evidence-" + storage.storageKey() + "." + (extension.isEmpty() ? "bin" : extension);
    }

    /**
     * The original filename, for display only.
     *
     * <p>Named for its one permitted use. It is hostile content ({@code SEC-SEC-029}) and the caller must encode
     * it for the context it renders into; there is nothing this class can do about that except refuse to put it
     * anywhere structural itself.
     */
    public String originalFilenameForDisplayOnly() {
        return originalFilename;
    }

    /** True where the declared and verified types disagree — a polyglot or a mislabelled upload. */
    public boolean typeMismatch() {
        return !declaredType.equalsIgnoreCase(verifiedType);
    }

    public boolean retentionExpired(Instant now) {
        return !now.isBefore(retentionUntil);
    }

    public UUID id() {
        return id;
    }

    public Optional<UUID> assessmentId() {
        return assessmentId;
    }

    public Optional<UUID> findingId() {
        return findingId;
    }

    public Optional<String> checklistItemRef() {
        return checklistItemRef;
    }

    /**
     * {@code INV-ASM-20}: {@code RESTRICTED} unconditionally, with no configuration reducing it.
     *
     * <p>A constant rather than a field, because a field is a thing a migration can set. ADR-047 makes restricted
     * fields <b>absent</b> from representations rather than masked, so this classification is what tells a
     * serializer to omit the record entirely rather than to redact parts of it.
     */
    public String classification() {
        return "RESTRICTED";
    }

    public ScanVerdict scanVerdict() {
        return scanVerdict;
    }

    public Optional<Instant> scannedAt() {
        return Optional.ofNullable(scannedAt);
    }

    public Optional<String> scannerIdentification() {
        return Optional.ofNullable(scannerIdentification);
    }

    public String declaredType() {
        return declaredType;
    }

    public String verifiedType() {
        return verifiedType;
    }

    public String contentHash() {
        return contentHash;
    }

    public UUID uploadedBy() {
        return uploadedBy;
    }

    public Instant uploadedAt() {
        return uploadedAt;
    }

    public Instant retentionUntil() {
        return retentionUntil;
    }
}
