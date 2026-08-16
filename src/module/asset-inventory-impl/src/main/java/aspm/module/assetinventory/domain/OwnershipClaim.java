package aspm.module.assetinventory.domain;

import aspm.sharedkernel.OrgNodeId;
import aspm.sharedkernel.PrincipalId;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiPredicate;

/**
 * The ownership resolution pipeline of DOC-03 section 8.4 and {@code PRD-AST-011}.
 *
 * <p><b>{@code PROPOSED} is distinct from {@code OWNED}</b>, and DOC-03 Figure 8.1 says why: "an inferred
 * owner is a hypothesis, and treating it as fact routes findings to someone who never accepted them."
 *
 * <p><b>{@code INV-AST-18} is a security control, not a workflow nicety.</b> DOC-03 section 8.4:
 *
 * <blockquote>Claiming an asset grants visibility of its findings. Unrestricted self-service claiming is a
 * data exfiltration path: claim a competitor business unit's repository and receive its vulnerability data.
 * The claim must be authorized against the <em>proposed node</em>, not merely authenticated.</blockquote>
 *
 * <p>So {@link #confirm} takes an authorization predicate over {@code (principal, proposedNode)} and cannot be
 * called without one. There is no overload that confirms on the strength of authentication alone.
 */
public final class OwnershipClaim {

    /** How the proposed owner was arrived at. */
    public enum Basis {
        EXPLICIT,
        INFERRED_PATH_PATTERN,
        INFERRED_PIPELINE,
        INFERRED_PRIOR_FINDING,
        INFERRED_MANUAL_PROPOSAL;

        boolean isInferred() {
            return this != EXPLICIT;
        }
    }

    public enum State {
        PROPOSED,
        CONFIRMED,
        REJECTED,
        EXPIRED;

        boolean isTerminal() {
            return this != PROPOSED;
        }
    }

    /** The outcome of an escalation. Deliberately not an assignment. */
    public record Escalation(OrgNodeId notifiedAncestor, int level, Instant at) {

        public Escalation {
            Objects.requireNonNull(notifiedAncestor, "the notified ancestor is required");
            Objects.requireNonNull(at, "the escalation instant is required");
        }
    }

    private final UUID id;
    private final UUID assetId;
    private final OrgNodeId proposedNodeId;
    private final Basis basis;
    private final PrincipalId claimedBy;
    private final Instant claimedAt;

    private State state = State.PROPOSED;
    private PrincipalId resolvedBy;
    private Instant resolvedAt;
    private int escalationLevel;

    public OwnershipClaim(UUID id, UUID assetId, OrgNodeId proposedNodeId, Basis basis,
            PrincipalId claimedBy, Instant claimedAt) {
        this.id = Objects.requireNonNull(id, "id is required");
        this.assetId = Objects.requireNonNull(assetId, "assetId is required");
        this.proposedNodeId = Objects.requireNonNull(proposedNodeId, "proposedNodeId is required");
        this.basis = Objects.requireNonNull(basis, "basis is required");
        this.claimedAt = Objects.requireNonNull(claimedAt, "claimedAt is required");
        // An inferred claim has no claiming principal; an explicit one must. An explicit claim with no
        // claimant cannot be held to INV-AST-18, because there is nobody to authorize.
        if (basis == Basis.EXPLICIT && claimedBy == null) {
            throw new IllegalArgumentException(
                    "an EXPLICIT claim requires the claiming principal; without one INV-AST-18 has nobody to "
                            + "authorize against the proposed node");
        }
        this.claimedBy = claimedBy;
    }

    /**
     * Confirms the claim, assigning ownership. {@code INV-AST-18}.
     *
     * @param confirmingPrincipal the principal confirming
     * @param authorizedForNode the authorization check, evaluated against the <b>proposed node</b>. Supplied
     *     rather than performed here because authorization is the kernel's single contract
     *     ({@code SEC-AUZ-013}) and this module must not implement its own check
     * @throws IllegalStateException where the principal is not authorized for the proposed node
     */
    public void confirm(PrincipalId confirmingPrincipal,
            BiPredicate<PrincipalId, OrgNodeId> authorizedForNode, Instant at) {
        Objects.requireNonNull(confirmingPrincipal, "the confirming principal is required");
        Objects.requireNonNull(authorizedForNode,
                "an authorization predicate over the PROPOSED NODE is required. INV-AST-18: unrestricted "
                        + "self-service claiming is a data exfiltration path — claim a competitor business "
                        + "unit's repository and receive its vulnerability data.");
        Objects.requireNonNull(at, "the resolution instant is required");
        requireProposed("confirmation");

        if (!authorizedForNode.test(confirmingPrincipal, proposedNodeId)) {
            throw new IllegalStateException(
                    "principal is not authorized for the proposed node, so the claim cannot be confirmed "
                            + "(INV-AST-18). The claim must be authorized against the proposed node, not "
                            + "merely authenticated.");
        }
        this.state = State.CONFIRMED;
        this.resolvedBy = confirmingPrincipal;
        this.resolvedAt = at;
    }

    public void reject(PrincipalId rejectingPrincipal, Instant at) {
        requireProposed("rejection");
        this.state = State.REJECTED;
        this.resolvedBy = Objects.requireNonNull(rejectingPrincipal, "the rejecting principal is required");
        this.resolvedAt = Objects.requireNonNull(at, "the resolution instant is required");
    }

    public void expire(Instant at) {
        requireProposed("expiry");
        this.state = State.EXPIRED;
        this.resolvedAt = Objects.requireNonNull(at, "the resolution instant is required");
    }

    /**
     * Escalates to the nearest ancestor node owner. {@code INV-AST-20}.
     *
     * <p><b>Notifies; does not assign.</b> DOC-03 section 8.4 is explicit: "Assigning ownership to an ancestor
     * on timeout would technically clear the queue and would place accountability with someone who has no
     * operational relationship to the asset. Findings would route to a divisional manager who cannot act on
     * them, which trains that manager to ignore the platform. Escalation makes the gap someone's
     * <em>problem</em>; it does not pretend to solve it."
     *
     * <p>The claim therefore stays {@code PROPOSED} — the return type carries a notification target and
     * nothing else, and this method cannot assign ownership because it has no access to the asset.
     */
    public Escalation escalate(OrgNodeId nearestAncestorWithOwner, Instant at) {
        requireProposed("escalation");
        Objects.requireNonNull(nearestAncestorWithOwner, "the ancestor to notify is required");
        this.escalationLevel++;
        return new Escalation(nearestAncestorWithOwner, escalationLevel, at);
    }

    public UUID id() {
        return id;
    }

    public UUID assetId() {
        return assetId;
    }

    public OrgNodeId proposedNodeId() {
        return proposedNodeId;
    }

    public Basis basis() {
        return basis;
    }

    public State state() {
        return state;
    }

    public int escalationLevel() {
        return escalationLevel;
    }

    public Optional<PrincipalId> claimedBy() {
        return Optional.ofNullable(claimedBy);
    }

    /**
     * When the claim was raised.
     *
     * <p>Read by the escalation scheduler, which compares it against the tenant's threshold to decide when
     * INV-AST-20 applies. Exposed rather than kept private because the threshold is tenant configuration and
     * therefore lives outside this aggregate.
     */
    public Instant claimedAt() {
        return claimedAt;
    }

    public Optional<PrincipalId> resolvedBy() {
        return Optional.ofNullable(resolvedBy);
    }

    public Optional<Instant> resolvedAt() {
        return Optional.ofNullable(resolvedAt);
    }

    /** True where confirming this claim should assign ownership. */
    public boolean assignsOwnership() {
        return state == State.CONFIRMED;
    }

    private void requireProposed(String operation) {
        if (state.isTerminal()) {
            throw new IllegalStateException(
                    operation + " requires a PROPOSED claim; this claim is " + state
                            + ". Re-resolving a settled claim would let a rejection be quietly converted "
                            + "into a confirmation, which is INV-AST-18 bypassed by state manipulation.");
        }
    }

    /**
     * {@code INV-AST-19}: at most one {@code PROPOSED} claim per asset.
     *
     * <p>A set-level property the aggregate cannot see, so it is asserted here over the candidate set and
     * enforced in the schema by a partial unique index. Two open claims would let two business units each
     * believe they were about to own an asset, and whichever confirmed first would surprise the other.
     */
    public static void assertAtMostOneProposed(java.util.Collection<OwnershipClaim> claimsForOneAsset) {
        long proposed = claimsForOneAsset.stream().filter(c -> c.state() == State.PROPOSED).count();
        if (proposed > 1) {
            throw new IllegalStateException(
                    proposed + " PROPOSED claims for one asset; INV-AST-19 permits at most one. Two open "
                            + "claims let two business units each believe they are about to own the asset.");
        }
    }
}
