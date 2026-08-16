package aspm.app.api;

import java.util.Objects;

/**
 * The seven security annotation classes of DOC-05 section 5 and ADR-036.
 *
 * <p>ADR-036's decision is "API security as seven annotation classes, with only deviations
 * annotated". The point is stated by {@code PRD-API-019}: "The class assignment is the mechanism
 * that prevents an operation shipping without its security characteristics considered."
 *
 * <h2>Why an enum of properties rather than nine annotations per operation</h2>
 *
 * <p>DOC-00 section 15.1 requires nine annotations per operation. Written per operation, an operation
 * ships with eight — and the missing one is discovered when somebody audits, which is to say not
 * discovered. Making the class a value that carries all nine properties means an operation inherits
 * them by <b>being assigned a class</b>, and there is no per-operation code that could omit one.
 *
 * <p>{@link OperationRegistry} is what turns "every operation has a class" from a convention into a
 * checkable fact: an operation not in the registry cannot be dispatched.
 */
public enum AnnotationClass {

    /** A — scoped read. The scope predicate is applied <b>in retrieval</b>, not after it. */
    A_SCOPED_READ(Authentication.ANY, ScopeRevalidation.PATH_IDENTIFIERS, RateClass.READ,
            ReplayProtection.NOT_APPLICABLE, AuditLevel.RESTRICTED_READS_ONLY,
            Classification.CONFIDENTIAL, false),

    /**
     * B — scoped write.
     *
     * <p>Every identifier in the path <b>and body</b> re-validated "independently of provenance". The
     * body half is the one that matters: an identifier the client got from a previous response of
     * ours is still a client-supplied identifier ({@code SEC-AUZ-017}).
     */
    B_SCOPED_WRITE(Authentication.ANY, ScopeRevalidation.PATH_AND_BODY_IDENTIFIERS, RateClass.WRITE,
            ReplayProtection.IDEMPOTENCY_KEY, AuditLevel.STATE_CHANGE, Classification.CONFIDENTIAL,
            false),

    /**
     * C — restricted reveal. Step-up authentication, a dedicated permission <b>never implied</b>, and
     * a per-object read event.
     *
     * <p>"Never implied" is the load-bearing phrase: a reveal permission that any administrative role
     * happens to include is not a control, and the per-object audit is what makes a reveal
     * answerable afterwards.
     */
    C_RESTRICTED_REVEAL(Authentication.ANY_WITH_STEP_UP, ScopeRevalidation.PATH_AND_BODY_IDENTIFIERS,
            RateClass.SENSITIVE, ReplayProtection.NOT_APPLICABLE, AuditLevel.PER_OBJECT_READ,
            Classification.RESTRICTED, true),

    /** D — bulk or export. A distinct permission <b>and</b> per-item evaluation ({@code INV-WRK-12}). */
    D_BULK_OR_EXPORT(Authentication.ANY, ScopeRevalidation.PER_ITEM, RateClass.BULK,
            ReplayProtection.IDEMPOTENCY_KEY, AuditLevel.SCOPE_AND_VOLUME, Classification.CONFIDENTIAL,
            false),

    /**
     * E — configuration. Step-up, an elevated permission distinct from operational, and an audit event
     * carrying <b>before and after</b> values.
     *
     * <p>Before-and-after because configuration change "appears nowhere in a finding-level audit
     * review" (DOC-28 section 13.2) — the record of what it used to be is the only way to see what
     * changed.
     */
    E_CONFIGURATION(Authentication.ANY_WITH_STEP_UP, ScopeRevalidation.PATH_AND_BODY_IDENTIFIERS,
            RateClass.WRITE, ReplayProtection.IDEMPOTENCY_KEY, AuditLevel.BEFORE_AND_AFTER,
            Classification.INTERNAL, true),

    /**
     * F — service ingest. Service credential only, credential-pinned scope, and the payload's asserted
     * scope <b>never trusted</b> ({@code SEC-AUZ-035}, {@code INV-SBM-06}).
     */
    F_SERVICE_INGEST(Authentication.SERVICE_CREDENTIAL_ONLY, ScopeRevalidation.AGAINST_PINNED_SCOPE,
            RateClass.INGEST, ReplayProtection.IDEMPOTENCY_KEY_AND_NONCE, AuditLevel.INGEST,
            Classification.CONFIDENTIAL, false),

    /** G — unauthenticated. Health, metadata, and the authentication endpoints themselves. */
    G_UNAUTHENTICATED(Authentication.NONE, ScopeRevalidation.NOT_APPLICABLE, RateClass.ANON,
            ReplayProtection.NOT_APPLICABLE, AuditLevel.FAILURES_ONLY, Classification.PUBLIC, false);

    public enum Authentication {
        NONE,
        ANY,
        ANY_WITH_STEP_UP,
        SERVICE_CREDENTIAL_ONLY
    }

    public enum ScopeRevalidation {
        NOT_APPLICABLE,
        PATH_IDENTIFIERS,
        PATH_AND_BODY_IDENTIFIERS,
        PER_ITEM,
        AGAINST_PINNED_SCOPE
    }

    public enum RateClass {
        ANON,
        READ,
        WRITE,
        SENSITIVE,
        BULK,
        INGEST
    }

    public enum ReplayProtection {
        NOT_APPLICABLE,
        IDEMPOTENCY_KEY,
        IDEMPOTENCY_KEY_AND_NONCE
    }

    public enum AuditLevel {
        FAILURES_ONLY,
        RESTRICTED_READS_ONLY,
        STATE_CHANGE,
        PER_OBJECT_READ,
        SCOPE_AND_VOLUME,
        BEFORE_AND_AFTER,
        INGEST
    }

    public enum Classification {
        PUBLIC,
        INTERNAL,
        CONFIDENTIAL,
        RESTRICTED
    }

    private final Authentication authentication;
    private final ScopeRevalidation scopeRevalidation;
    private final RateClass rateClass;
    private final ReplayProtection replayProtection;
    private final AuditLevel auditLevel;
    private final Classification classification;
    private final boolean requiresElevatedPermission;

    AnnotationClass(Authentication authentication, ScopeRevalidation scopeRevalidation, RateClass rateClass,
            ReplayProtection replayProtection, AuditLevel auditLevel, Classification classification,
            boolean requiresElevatedPermission) {
        this.authentication = authentication;
        this.scopeRevalidation = scopeRevalidation;
        this.rateClass = rateClass;
        this.replayProtection = replayProtection;
        this.auditLevel = auditLevel;
        this.classification = classification;
        this.requiresElevatedPermission = requiresElevatedPermission;
    }

    public Authentication authentication() {
        return authentication;
    }

    public ScopeRevalidation scopeRevalidation() {
        return scopeRevalidation;
    }

    public RateClass rateClass() {
        return rateClass;
    }

    public ReplayProtection replayProtection() {
        return replayProtection;
    }

    public AuditLevel auditLevel() {
        return auditLevel;
    }

    public Classification classification() {
        return classification;
    }

    /** Whether the class demands a permission distinct from the operational one. */
    public boolean requiresElevatedPermission() {
        return requiresElevatedPermission;
    }

    /**
     * Whether an idempotency key is required on this class.
     *
     * <p>Every non-idempotent write, per the prompt's framework-level list. A key is
     * <b>tenant-namespaced</b> — see {@link IdempotencyKey} for why that is not merely tidy.
     */
    public boolean requiresIdempotencyKey() {
        return replayProtection != ReplayProtection.NOT_APPLICABLE;
    }

    /** Whether step-up authentication is required before the operation runs. */
    public boolean requiresStepUp() {
        return authentication == Authentication.ANY_WITH_STEP_UP;
    }

    /**
     * Whether a human session may invoke this class at all.
     *
     * <p>False for {@code F}: a service-ingest operation reachable by a browser session is one whose
     * pinned-scope guarantee does not apply, because a human session has no pinned scope to check
     * against.
     */
    public boolean invokableByHumanSession() {
        return authentication != Authentication.SERVICE_CREDENTIAL_ONLY
                && authentication != Authentication.NONE;
    }

    /** Resolves a class by its DOC-05 letter, so an operation table can be read against the document. */
    public static AnnotationClass ofLetter(String letter) {
        Objects.requireNonNull(letter, "a class letter is required");
        for (AnnotationClass candidate : values()) {
            if (candidate.name().startsWith(letter.strip().toUpperCase(java.util.Locale.ROOT) + "_")) {
                return candidate;
            }
        }
        throw new IllegalArgumentException(
                "'" + letter + "' is not one of the seven annotation classes of ADR-036. A new class is a "
                        + "decision about how API security is expressed, not an operation-level choice.");
    }
}
