package aspm.module.assessment.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * An external assessor's access grant. Aggregate root; DOC-03 section 9.6, DOC-09 section 14.1.
 *
 * <p>DOC-03 section 9.6 on why this exists as its own mechanism rather than reusing scope: "An external assessor
 * is an untrusted party inside a system holding the enterprise's complete attack surface. Granting through the
 * normal scope mechanism means any later change to the organization tree, a role definition, or a node assignment
 * can silently widen their visibility."
 *
 * <p>The word doing the work is <b>silently</b>. Scope inheritance widening is not a bug anybody would notice —
 * it is the org tree behaving correctly, and the external party's visibility grows as a side effect of an
 * unrelated reorganization.
 *
 * <h2>{@code INV-ASM-26} — expiry is automatic, not manual</h2>
 *
 * <p>"Manual revocation reliably does not happen — access reviews find dormant external accounts as a matter of
 * routine, and each is a standing compromise of all the customer's posture data."
 *
 * <p>So {@link #valid} is computed from the clock, there is no state the grant can sit in that outlives
 * {@code validUntil}, and DOC-09 section 14.1 states plainly: <b>no extension transition exists</b>. Continuing
 * access is a new grant with a new approval, because "extendable grants become permanent".
 */
public final class ExternalAssessorGrant {

    public enum State {
        REQUESTED,
        PENDING_AGREEMENT,
        ACTIVE,
        REVOKED,
        EXPIRED
    }

    /**
     * One explicitly granted object. {@code INV-ASM-25}: never scope-derived.
     *
     * <p>An object identifier and a kind, with no node reference anywhere. There is deliberately no way to
     * express "everything under node X" — that expression is the scope mechanism this invariant exists to avoid.
     */
    public record ObjectGrant(String objectKind, UUID objectId) {

        public ObjectGrant {
            Objects.requireNonNull(objectKind, "an object kind is required");
            Objects.requireNonNull(objectId,
                    "an explicit object identifier is required (INV-ASM-25). A grant expressed as a scope "
                            + "widens whenever the tree changes, and nobody reviews a reorganization for its "
                            + "effect on an external party's visibility.");
        }
    }

    /** An accepted agreement — a non-disclosure agreement, rules of engagement, a data handling undertaking. */
    public record AgreementAcceptance(String agreementCode, int agreementVersion, Instant acceptedAt,
            String acceptedFromAddress) {

        public AgreementAcceptance {
            Objects.requireNonNull(agreementCode, "an agreement code is required");
            Objects.requireNonNull(acceptedAt, "the acceptance time is required");
            Objects.requireNonNull(acceptedFromAddress,
                    "the source address is required. An acceptance nobody can locate is an acceptance nobody "
                            + "can attribute if the agreement is later disputed.");
            if (agreementVersion < 1) {
                throw new IllegalArgumentException(
                        "an agreement version is required; accepting 'the NDA' without saying which one leaves "
                                + "no record of what was agreed");
            }
        }
    }

    /** Configured maximum grant duration. {@code INV-ASM-26} requires {@code valid_until} bounded. */
    public static final Duration MAXIMUM_DURATION = Duration.ofDays(90);

    private final UUID id;
    private final UUID principalId;
    private final UUID engagementId;
    private final List<ObjectGrant> grantedObjects;
    private final Set<String> requiredAgreements;
    private final Instant validFrom;
    private final Instant validUntil;

    private State state = State.REQUESTED;
    private final List<AgreementAcceptance> accepted = new ArrayList<>();
    private UUID revokedBy;
    private Instant revokedAt;
    private String revocationReason;
    private boolean credentialRotationFlagged;
    private UUID rotationAttestedBy;

    public ExternalAssessorGrant(UUID id, UUID principalId, UUID engagementId,
            List<ObjectGrant> grantedObjects, Set<String> requiredAgreements, Instant validFrom,
            Instant validUntil) {
        this.id = Objects.requireNonNull(id, "id is required");
        this.principalId = Objects.requireNonNull(principalId, "principalId is required");
        this.engagementId = Objects.requireNonNull(engagementId, "engagementId is required");
        this.grantedObjects = List.copyOf(
                Objects.requireNonNull(grantedObjects, "granted objects are required"));
        this.requiredAgreements = Set.copyOf(
                Objects.requireNonNull(requiredAgreements, "required agreements are required, possibly empty"));
        this.validFrom = Objects.requireNonNull(validFrom, "validFrom is required");
        this.validUntil = Objects.requireNonNull(validUntil,
                "valid_until is MANDATORY (INV-ASM-26). Manual revocation reliably does not happen, and every "
                        + "dormant external account an access review finds is a standing compromise of all the "
                        + "customer's posture data.");

        if (this.grantedObjects.isEmpty()) {
            throw new IllegalArgumentException("a grant conveying no objects conveys nothing");
        }
        if (!validUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException("valid_until must be after valid_from");
        }
        Duration duration = Duration.between(validFrom, validUntil);
        if (duration.compareTo(MAXIMUM_DURATION) > 0) {
            throw new IllegalArgumentException(
                    "a grant of " + duration.toDays() + " days exceeds the configured maximum of "
                            + MAXIMUM_DURATION.toDays() + " (INV-ASM-26). A long grant is an extension granted "
                            + "in advance, and extendable grants become permanent.");
        }
    }

    /** Moves to awaiting agreement. Issuance requires {@code asm.externalgrant.issue}, checked by the caller. */
    public void issue() {
        if (state != State.REQUESTED) {
            throw new IllegalStateException("only a REQUESTED grant is issued; this one is " + state);
        }
        this.state = State.PENDING_AGREEMENT;
    }

    public void acceptAgreement(AgreementAcceptance acceptance) {
        Objects.requireNonNull(acceptance, "an acceptance is required");
        if (state != State.PENDING_AGREEMENT) {
            throw new IllegalStateException("agreements are accepted while PENDING_AGREEMENT");
        }
        if (!requiredAgreements.contains(acceptance.agreementCode())) {
            throw new IllegalArgumentException(
                    "'" + acceptance.agreementCode() + "' is not among the required agreements "
                            + requiredAgreements + "; accepting an agreement nobody asked for does not "
                            + "substitute for one that was");
        }
        accepted.add(acceptance);
    }

    /**
     * Activates. {@code INV-ASM-27}: no access before every required agreement is accepted.
     *
     * @throws IllegalStateException naming the outstanding agreements
     */
    public void activate(Instant at) {
        Objects.requireNonNull(at, "the activation instant is required");
        if (state != State.PENDING_AGREEMENT) {
            throw new IllegalStateException("only a PENDING_AGREEMENT grant activates; this one is " + state);
        }
        Set<String> outstanding = new LinkedHashSet<>(requiredAgreements);
        accepted.forEach(a -> outstanding.remove(a.agreementCode()));
        if (!outstanding.isEmpty()) {
            throw new IllegalStateException(
                    "no access before the required agreements are accepted (INV-ASM-27); outstanding: "
                            + outstanding);
        }
        if (!at.isBefore(validUntil)) {
            throw new IllegalStateException(
                    "the grant window has already closed; activating it would produce an ACTIVE grant that is "
                            + "not valid, and something downstream would read the state rather than the clock");
        }
        this.state = State.ACTIVE;
    }

    /**
     * Whether access is permitted right now.
     *
     * <p><b>Computed from the clock</b>, not read from {@link #state}. {@code INV-ASM-26} makes expiry automatic;
     * a grant whose validity depended on a sweep having run would stay open for as long as the sweep was broken,
     * and a broken sweep is silent.
     */
    public boolean valid(Instant now) {
        Objects.requireNonNull(now, "the current instant is required");
        return state == State.ACTIVE && !now.isBefore(validFrom) && now.isBefore(validUntil);
    }

    /**
     * Whether a specific object is granted. {@code INV-ASM-25}.
     *
     * <p>Membership of an explicit list. There is no subtree walk and no node comparison, because either would
     * reintroduce the widening this invariant removes.
     */
    public boolean grants(String objectKind, UUID objectId, Instant now) {
        return valid(now) && grantedObjects.contains(new ObjectGrant(objectKind, objectId));
    }

    /** Records the expiry sweep's observation. The grant was already invalid; this records that it noticed. */
    public void markExpired(Instant at) {
        if (state != State.ACTIVE && state != State.PENDING_AGREEMENT) {
            throw new IllegalStateException("cannot expire from " + state);
        }
        if (at.isBefore(validUntil)) {
            throw new IllegalArgumentException(
                    "the grant is valid until " + validUntil + "; marking it expired early would make the "
                            + "recorded end differ from the enforced one");
        }
        this.state = State.EXPIRED;
    }

    /**
     * Revokes early.
     *
     * <p>DOC-09 section 14.1's edge case: "Engagement closure revokes the grant immediately rather than waiting
     * for expiry, and flags associated test accounts for rotation." Revocation is therefore not the primary
     * mechanism — expiry is — but it is the one that runs when the work finishes early.
     */
    public void revoke(UUID revokedByPrincipal, String reason, Instant at) {
        Objects.requireNonNull(revokedByPrincipal, "the revoking principal is required");
        Objects.requireNonNull(at, "the revocation instant is required");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("a revocation requires a reason");
        }
        if (state != State.ACTIVE && state != State.PENDING_AGREEMENT) {
            throw new IllegalStateException("cannot revoke from " + state);
        }
        this.revokedBy = revokedByPrincipal;
        this.revocationReason = reason;
        this.revokedAt = at;
        this.state = State.REVOKED;
        // INV-ASM-29. Flagged here rather than by a separate closure routine, because a routine that must
        // remember to run is one that will not run on the engagement that ended badly.
        this.credentialRotationFlagged = true;
    }

    /**
     * {@code INV-ASM-29}: on closure, associated test accounts are flagged for rotation and an attestation is
     * required.
     *
     * <p>The attestation is what closes it, not the flag. A flag nobody has to answer for is a list that grows.
     */
    public void attestCredentialRotation(UUID attestingPrincipal) {
        Objects.requireNonNull(attestingPrincipal, "an attesting principal is required");
        if (!credentialRotationFlagged) {
            throw new IllegalStateException(
                    "no rotation has been flagged; attesting to one that was not required records a control "
                            + "as exercised when it was not");
        }
        if (state != State.REVOKED && state != State.EXPIRED) {
            throw new IllegalStateException(
                    "rotation is attested at closure; attesting while the grant is " + state
                            + " would rotate credentials the assessor is still using");
        }
        this.rotationAttestedBy = attestingPrincipal;
    }

    /** True where rotation is owed and nobody has attested to it. The reportable form of {@code INV-ASM-29}. */
    public boolean rotationOutstanding() {
        return credentialRotationFlagged && rotationAttestedBy == null;
    }

    /**
     * {@code INV-ASM-28}: test credentials of a granted engagement are revealable to the grantee.
     *
     * <p>Yes, revealable. The assessor cannot test with a credential they cannot read, and refusing would push
     * the credential into an email. What the invariant adds is "audited at elevated granularity"
     * ({@code PRD-AUD-003}) — so this method reports whether a reveal is permitted and the audit obligation
     * travels with the answer, rather than being a separate thing somebody remembers.
     */
    public record RevealPermission(boolean permitted, boolean requiresElevatedAudit, String reason) {
    }

    public RevealPermission mayRevealTestCredential(UUID requestingPrincipal, UUID credentialEngagementId,
            Instant now) {
        if (!requestingPrincipal.equals(principalId)) {
            return new RevealPermission(false, false, "not the grantee of this grant");
        }
        if (!valid(now)) {
            return new RevealPermission(false, false,
                    "the grant is not valid at " + now + " (state " + state + ", until " + validUntil + ")");
        }
        if (!engagementId.equals(credentialEngagementId)) {
            return new RevealPermission(false, false,
                    "the credential belongs to a different engagement; a grant for one engagement conveying "
                            + "credentials for another is exactly the widening INV-ASM-25 removes");
        }
        return new RevealPermission(true, true,
                "revealable to the grantee, audited at elevated granularity (INV-ASM-28, PRD-AUD-003)");
    }

    public UUID id() {
        return id;
    }

    public UUID principalId() {
        return principalId;
    }

    public UUID engagementId() {
        return engagementId;
    }

    /** Explicit objects. There is no accessor returning a scope, because there is no scope. */
    public List<ObjectGrant> grantedObjects() {
        return grantedObjects;
    }

    public Set<String> requiredAgreements() {
        return requiredAgreements;
    }

    public List<AgreementAcceptance> acceptedAgreements() {
        return List.copyOf(accepted);
    }

    public State state() {
        return state;
    }

    public Instant validFrom() {
        return validFrom;
    }

    public Instant validUntil() {
        return validUntil;
    }

    public Optional<UUID> revokedBy() {
        return Optional.ofNullable(revokedBy);
    }

    public Optional<Instant> revokedAt() {
        return Optional.ofNullable(revokedAt);
    }

    public Optional<String> revocationReason() {
        return Optional.ofNullable(revocationReason);
    }

    public boolean credentialRotationFlagged() {
        return credentialRotationFlagged;
    }

    public Optional<UUID> rotationAttestedBy() {
        return Optional.ofNullable(rotationAttestedBy);
    }
}
