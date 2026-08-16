package aspm.module.assessment.domain;

import aspm.sharedkernel.ScopeDescriptor;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * An assessment request. Aggregate root; DOC-03 section 9.2, DOC-09 section 4.
 *
 * <p><b>Why intake is an aggregate and not a form</b> (DOC-03 section 9.2): "It carries invariants that must hold
 * as a set — two accounts per role, readiness complete, bypass recorded where a control exists — and those
 * invariants gate a state transition. A form validates fields; an aggregate enforces that a request cannot be
 * accepted in a state where the work cannot proceed."
 *
 * <p>The prompt calls this "the platform's largest external write surface and highest object-level authorization
 * risk by volume", and DOC-01 section 10.4.6 pairs that with the least-trained user population (PP-7). Every
 * check below is written on the assumption that the caller did not intend harm and did not read the
 * documentation.
 */
public final class AssessmentRequest {

    /** DOC-09 section 4. Tenant-configurable, so these are the shipped default states. */
    public enum State {
        DRAFT,
        SUBMITTED,
        TRIAGED,
        ACCEPTED,
        SCHEDULED,
        IN_ASSESSMENT,
        REJECTED,
        DEFERRED,
        WITHDRAWN,
        MERGED;

        public boolean isTerminal() {
            return this == REJECTED || this == WITHDRAWN || this == MERGED;
        }
    }

    /** {@code INV-ASM-05}: a protective control on a test environment needs a recorded arrangement. */
    public record TestEnvironment(String name, String baseUrl, boolean protectiveControlPresent,
            Optional<String> protectiveControlBypassArrangement) {

        public TestEnvironment {
            Objects.requireNonNull(name, "an environment name is required");
            Objects.requireNonNull(baseUrl, "a base URL is required");
            Objects.requireNonNull(protectiveControlBypassArrangement,
                    "the bypass arrangement is required, empty where no control is present");
            if (protectiveControlPresent && protectiveControlBypassArrangement
                    .filter(a -> !a.isBlank()).isEmpty()) {
                throw new IllegalArgumentException(
                        "environment '" + name + "' declares a protective control with no recorded bypass or "
                                + "allowlist arrangement (INV-ASM-05). A web application firewall in front of "
                                + "the target means the engagement tests the firewall, reports that the "
                                + "application is sound, and the assessment is worthless in a way nobody "
                                + "notices until an incident.");
            }
            if (!protectiveControlPresent && protectiveControlBypassArrangement.isPresent()) {
                throw new IllegalArgumentException(
                        "environment '" + name + "' records a bypass arrangement for a control it does not "
                                + "declare; one of the two is wrong and neither can be trusted");
            }
        }
    }

    /** {@code INV-ASM-04}: readiness must be complete before acceptance. */
    public record ReadinessAttestation(boolean environmentAvailable, boolean accountsProvisioned,
            boolean dataSeeded, boolean contactAvailable, Optional<Instant> attestedAt,
            Optional<UUID> attestedBy) {

        public ReadinessAttestation {
            Objects.requireNonNull(attestedAt, "attestedAt is required, empty until attested");
            Objects.requireNonNull(attestedBy, "attestedBy is required, empty until attested");
            if (attestedAt.isPresent() != attestedBy.isPresent()) {
                throw new IllegalArgumentException(
                        "an attestation needs both a time and a principal; an unattributed attestation is a "
                                + "claim nobody made");
            }
        }

        public static ReadinessAttestation empty() {
            return new ReadinessAttestation(false, false, false, false, Optional.empty(), Optional.empty());
        }

        public boolean complete() {
            return environmentAvailable && accountsProvisioned && dataSeeded && contactAvailable
                    && attestedAt.isPresent();
        }

        /** What is missing, so the requester is told rather than left to guess. */
        public List<String> gaps() {
            List<String> gaps = new ArrayList<>();
            if (!environmentAvailable) {
                gaps.add("the test environment is not available");
            }
            if (!accountsProvisioned) {
                gaps.add("the test accounts are not provisioned");
            }
            if (!dataSeeded) {
                gaps.add("the environment holds no representative data");
            }
            if (!contactAvailable) {
                gaps.add("no technical contact is available during the engagement");
            }
            if (attestedAt.isEmpty()) {
                gaps.add("nobody has attested to the above");
            }
            return List.copyOf(gaps);
        }
    }

    /**
     * {@code INV-ASM-08}: derived facts, recomputed from recorded inputs and never manually set.
     *
     * <p>PP-2 and {@code PRD-RSK-038}: the estimate drives a capacity commitment and must be explainable when
     * missed. A field somebody can type into is a field somebody will type a comfortable number into.
     */
    public record DerivedRequestFacts(int priorityScore, java.math.BigDecimal estimatedEffortDays,
            Optional<java.time.LocalDate> feasibleStart, int modelVersion) {

        public DerivedRequestFacts {
            Objects.requireNonNull(estimatedEffortDays, "an effort estimate is required");
            Objects.requireNonNull(feasibleStart, "feasibleStart is required, empty where capacity is unknown");
            if (modelVersion < 1) {
                throw new IllegalArgumentException("a model version is required, so the figure is reproducible");
            }
        }
    }

    private final UUID id;
    private final String requestCode;
    private final UUID typeId;
    private final UUID requestedOrgNodeId;
    private final List<UUID> targetAssetIds;
    private final UUID requestedBy;

    private State state = State.DRAFT;
    private ScopeDescriptor scope;
    private Instant submittedAt;
    private final List<RoleAccount> roleAccounts = new ArrayList<>();
    private final List<TestEnvironment> environments = new ArrayList<>();
    private ReadinessAttestation readiness = ReadinessAttestation.empty();
    private DerivedRequestFacts derived;
    private UUID groupId;
    private UUID priorAssessmentId;
    private String revisionIdentifier;
    private final boolean retest;

    private AssessmentRequest(UUID id, String requestCode, UUID typeId, UUID requestedOrgNodeId,
            List<UUID> targetAssetIds, UUID requestedBy, boolean retest, UUID priorAssessmentId,
            String revisionIdentifier) {
        this.id = Objects.requireNonNull(id, "id is required");
        this.requestCode = Objects.requireNonNull(requestCode, "a request code is required");
        this.typeId = Objects.requireNonNull(typeId, "typeId is required");
        this.requestedOrgNodeId = Objects.requireNonNull(requestedOrgNodeId,
                "exactly one org node scope is required (INV-ASM-06). Multi-project work is a RequestGroup of "
                        + "one request per project, because a request spanning two projects has two owners, two "
                        + "readiness states and two sets of accounts, and every one of those diverges.");
        this.targetAssetIds = List.copyOf(Objects.requireNonNull(targetAssetIds, "target assets are required"));
        this.requestedBy = Objects.requireNonNull(requestedBy, "requestedBy is required");
        this.retest = retest;
        this.priorAssessmentId = priorAssessmentId;
        this.revisionIdentifier = revisionIdentifier;

        if (targetAssetIds.isEmpty()) {
            throw new IllegalArgumentException("a request with no target asset asks for nothing");
        }
        // INV-ASM-09.
        if (retest && (priorAssessmentId == null || revisionIdentifier == null
                || revisionIdentifier.isBlank())) {
            throw new IllegalArgumentException(
                    "a RETEST must reference a prior assessment AND a new revision identifier (INV-ASM-09). "
                            + "Without the revision, a retest against the same build re-runs the same tests on "
                            + "the same code and reports the same findings as fixed or not fixed with no "
                            + "evidence either way.");
        }
        if (!retest && (priorAssessmentId != null || revisionIdentifier != null)) {
            throw new IllegalArgumentException("a prior assessment reference on a request that is not a retest");
        }
    }

    public static AssessmentRequest draft(UUID id, String requestCode, UUID typeId, UUID requestedOrgNodeId,
            List<UUID> targetAssetIds, UUID requestedBy) {
        return new AssessmentRequest(id, requestCode, typeId, requestedOrgNodeId, targetAssetIds, requestedBy,
                false, null, null);
    }

    /** {@code INV-ASM-09}. A separate factory so the two required references cannot be omitted. */
    public static AssessmentRequest retest(UUID id, String requestCode, UUID typeId, UUID requestedOrgNodeId,
            List<UUID> targetAssetIds, UUID requestedBy, UUID priorAssessmentId, String revisionIdentifier) {
        return new AssessmentRequest(id, requestCode, typeId, requestedOrgNodeId, targetAssetIds, requestedBy,
                true, priorAssessmentId, revisionIdentifier);
    }

    // ------------------------------------------------------------------ intake content

    public void addRoleAccount(RoleAccount account) {
        requireEditable();
        roleAccounts.add(Objects.requireNonNull(account, "an account is required"));
    }

    public void addEnvironment(TestEnvironment environment) {
        requireEditable();
        environments.add(Objects.requireNonNull(environment, "an environment is required"));
    }

    public void attestReadiness(ReadinessAttestation attestation) {
        requireEditable();
        this.readiness = Objects.requireNonNull(attestation, "an attestation is required");
    }

    /**
     * {@code INV-ASM-08}: derived facts are set by the platform's own computation, never by a caller supplying a
     * preferred number. There is no {@code setPriority} or {@code setEffort} for the same reason.
     */
    public void recordDerivedFacts(DerivedRequestFacts facts) {
        this.derived = Objects.requireNonNull(facts, "derived facts are required");
    }

    public void assignToGroup(UUID requestGroupId) {
        requireEditable();
        this.groupId = requestGroupId;
    }

    // ------------------------------------------------------------------ transitions

    /**
     * Submits the request, resolving and freezing the scope.
     *
     * <p>{@code INV-ASM-01}, and the prompt's first bullet: the scope is <b>re-validated server-side,
     * independently of the picker</b> ({@code SEC-AUZ-018}). The resolver is passed in rather than the caller
     * passing a resolved descriptor, because a caller who can hand over a descriptor can hand over any
     * descriptor — and the picker that produced the node identifier is a usability feature, not a control (PP-4).
     *
     * <p>{@code INV-ASM-07}: once resolved, the scope is immutable "even if the project later moves". There is no
     * setter and reorganization does not touch it ({@code PRD-WRK-042}).
     *
     * @param scopeAuthority resolves the node to a descriptor <b>and</b> answers whether the requester is
     *     authorized for it. Returning empty means not authorized OR not existing, undistinguished — the same
     *     conflation the transition evaluation order relies on ({@code SEC-AUZ-020})
     */
    public void submit(ScopeAuthority scopeAuthority, Instant at) {
        Objects.requireNonNull(scopeAuthority, "a scope authority is required (INV-ASM-01, SEC-AUZ-018)");
        Objects.requireNonNull(at, "the submission instant is required");
        if (state != State.DRAFT) {
            throw new IllegalStateException("only a DRAFT request is submitted; this one is " + state);
        }

        ScopeDescriptor resolved = scopeAuthority.resolveFor(requestedBy, requestedOrgNodeId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "not found. The requested scope did not resolve within the requester's authorized "
                                + "scope (INV-ASM-01). Re-validated on write independently of the submitted "
                                + "identifier, because the picker that produced it is a usability feature and "
                                + "never an authorization control (SEC-AUZ-018, PP-4)."));

        this.scope = resolved;
        this.submittedAt = at;
        this.state = State.SUBMITTED;
    }

    /** Resolves a node to a scope descriptor, or empty where the principal is not authorized for it. */
    @FunctionalInterface
    public interface ScopeAuthority {

        Optional<ScopeDescriptor> resolveFor(UUID principalId, UUID orgNodeId);
    }

    public void triage(Instant at) {
        requireState(State.SUBMITTED, "triage");
        this.state = State.TRIAGED;
    }

    /**
     * Accepts the request. The gate {@code INV-ASM-02}, {@code -04} and {@code -05} exist for.
     *
     * @throws IllegalStateException listing <b>every</b> unmet precondition. Reporting the first would make
     *     acceptance a sequence of round trips for a requester who is, per PP-7, the least-trained user of the
     *     platform
     */
    public void accept(Instant at) {
        Objects.requireNonNull(at, "the acceptance instant is required");
        if (state != State.TRIAGED && state != State.SUBMITTED) {
            throw new IllegalStateException("only a SUBMITTED or TRIAGED request is accepted; this one is "
                    + state);
        }

        List<String> unmet = acceptanceGaps();
        if (!unmet.isEmpty()) {
            throw new IllegalStateException(
                    "the request cannot be accepted, because the assessment could not do its job: " + unmet);
        }
        this.state = State.ACCEPTED;
    }

    /**
     * Every reason acceptance would be refused. Public so an interface can show them before the attempt.
     *
     * <p>The list is the readable form of DOC-03 section 9.2's argument that intake is an aggregate: these
     * conditions must hold <i>as a set</i>, and each one alone makes the engagement unable to deliver what it
     * will nonetheless report having delivered.
     */
    public List<String> acceptanceGaps() {
        List<String> gaps = new ArrayList<>();

        if (scope == null) {
            gaps.add("the scope has not been resolved; the request has not been submitted");
        }

        // INV-ASM-02. Two accounts of the same role is the ONLY way to demonstrate broken object-level
        // authorization: showing that user A can read user B's data requires both A and B.
        Map<String, Integer> usableByRole = new LinkedHashMap<>();
        for (RoleAccount account : roleAccounts) {
            if (account.status().usable()) {
                usableByRole.merge(account.normalizedRoleName(), 1, Integer::sum);
            }
        }
        Set<String> declaredRoles = new LinkedHashSet<>();
        for (RoleAccount account : roleAccounts) {
            declaredRoles.add(account.normalizedRoleName());
        }
        if (declaredRoles.isEmpty()) {
            gaps.add("no test accounts are declared, so no authorization testing is possible at all "
                    + "(INV-ASM-02)");
        }
        for (String role : declaredRoles) {
            int usable = usableByRole.getOrDefault(role, 0);
            if (usable < REQUIRED_ACCOUNTS_PER_ROLE) {
                gaps.add("role '" + role + "' has " + usable + " usable account(s), needs "
                        + REQUIRED_ACCOUNTS_PER_ROLE + " (INV-ASM-02). Demonstrating that user A can read "
                        + "user B's data requires both A and B; without the pair, the engagement will report "
                        + "that authorization was tested when it could not have been");
            }
        }

        // INV-ASM-04.
        if (!readiness.complete()) {
            gaps.add("readiness is incomplete (INV-ASM-04): " + readiness.gaps());
        }

        // INV-ASM-05 is enforced at TestEnvironment construction, so an unarranged control cannot be here. This
        // check catches the other half: no environment at all.
        if (environments.isEmpty()) {
            gaps.add("no test environment is declared, so there is nothing to assess (PRD-PTR-009)");
        }

        return List.copyOf(gaps);
    }

    /** {@code INV-ASM-02}. Two, and the rationale is in {@link #acceptanceGaps}. */
    public static final int REQUIRED_ACCOUNTS_PER_ROLE = 2;

    public void schedule(Instant at) {
        requireState(State.ACCEPTED, "schedule");
        this.state = State.SCHEDULED;
    }

    public void beginAssessment(Instant at) {
        requireState(State.SCHEDULED, "begin");
        this.state = State.IN_ASSESSMENT;
    }

    public void reject(String reason, Instant at) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "a rejection requires a reason. A requester told only 'no' resubmits the same request, and "
                            + "the second rejection costs both parties the same again.");
        }
        if (state.isTerminal() || state == State.IN_ASSESSMENT) {
            throw new IllegalStateException("cannot reject from " + state);
        }
        this.state = State.REJECTED;
    }

    public void defer(Instant at) {
        if (state != State.TRIAGED && state != State.ACCEPTED) {
            throw new IllegalStateException("only a TRIAGED or ACCEPTED request is deferred");
        }
        this.state = State.DEFERRED;
    }

    public void withdraw(Instant at) {
        if (state == State.IN_ASSESSMENT || state.isTerminal()) {
            throw new IllegalStateException("cannot withdraw from " + state);
        }
        this.state = State.WITHDRAWN;
    }

    public void mergeInto(UUID targetRequestId, Instant at) {
        Objects.requireNonNull(targetRequestId, "a merge target is required");
        if (targetRequestId.equals(id)) {
            throw new IllegalArgumentException("a request cannot be merged into itself");
        }
        if (state == State.IN_ASSESSMENT || state.isTerminal()) {
            throw new IllegalStateException("cannot merge from " + state);
        }
        this.groupId = targetRequestId;
        this.state = State.MERGED;
    }

    // ------------------------------------------------------------------ accessors

    private void requireEditable() {
        if (state != State.DRAFT && state != State.SUBMITTED && state != State.TRIAGED) {
            throw new IllegalStateException(
                    "the request is " + state + " and its intake content is settled. Changing accounts or "
                            + "readiness after acceptance would move the ground under a scheduled engagement.");
        }
    }

    private void requireState(State expected, String event) {
        if (state != expected) {
            throw new IllegalStateException("'" + event + "' requires " + expected + "; this one is " + state);
        }
    }

    public UUID id() {
        return id;
    }

    public String requestCode() {
        return requestCode;
    }

    public UUID typeId() {
        return typeId;
    }

    public UUID requestedOrgNodeId() {
        return requestedOrgNodeId;
    }

    public List<UUID> targetAssetIds() {
        return targetAssetIds;
    }

    public UUID requestedBy() {
        return requestedBy;
    }

    public State state() {
        return state;
    }

    /** Resolved at submission and immutable thereafter ({@code INV-ASM-07}). There is no setter. */
    public Optional<ScopeDescriptor> scope() {
        return Optional.ofNullable(scope);
    }

    public Optional<Instant> submittedAt() {
        return Optional.ofNullable(submittedAt);
    }

    public List<RoleAccount> roleAccounts() {
        return List.copyOf(roleAccounts);
    }

    public List<TestEnvironment> environments() {
        return List.copyOf(environments);
    }

    public ReadinessAttestation readiness() {
        return readiness;
    }

    public Optional<DerivedRequestFacts> derived() {
        return Optional.ofNullable(derived);
    }

    public Optional<UUID> groupId() {
        return Optional.ofNullable(groupId);
    }

    public boolean isRetest() {
        return retest;
    }

    public Optional<UUID> priorAssessmentId() {
        return Optional.ofNullable(priorAssessmentId);
    }

    public Optional<String> revisionIdentifier() {
        return Optional.ofNullable(revisionIdentifier);
    }
}
