package aspm.app.resource;

import aspm.app.persistence.TenantConnections;
import aspm.app.runtime.Principal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Reviews the platform did not observe, asserted by somebody who did — the one write path onto
 * {@code application_review_attestation}.
 *
 * <h2>Why this is a separate concept from a completed request</h2>
 *
 * Product principle 1 is usually read as measured versus not-measured. It has a third state and this
 * is it: <b>asserted</b>. An OBSERVED review is one the platform holds the request, the execution
 * record, the transitions and the findings for — a claim it can substantiate from its own data. An
 * ATTESTED review is a person's statement that work happened between two dates. Both discharge the
 * periodic obligation; only one of them is evidence.
 *
 * <p>The alternative — writing a backdated request with a fabricated execution record and terminal
 * transition — was rejected in ADR-066. It would put a workflow history nobody lived into
 * {@code assessment_request_transition}, which {@code PRD-PLT-001} names as data that cannot be
 * reconstructed.
 *
 * <h2>Attribution is the record</h2>
 *
 * {@code attested_by} is NOT NULL in the schema, unlike {@code created_by} elsewhere. An assertion
 * about coverage with nobody's name on it is not worth holding, because there is nobody to ask when it
 * turns out to be wrong.
 */
public final class ReviewAttestations {

    /**
     * The permission that decides who may assert a review happened.
     *
     * <p>A new entry in the catalogue rather than a reuse, and that is deliberate. This action changes
     * the coverage figure the whole platform reports, on one person's word. Folding it into
     * {@code asm.request.qa} or {@code asm.request.approve} would grant it to everybody who already
     * holds those, which nobody decided. The cost — every tenant must grant it before the feature
     * works for anybody — is the intended one; ADR-027 fixes the catalogue at product level precisely
     * so that a new authority has to be handed out on purpose.
     */
    public static final String ATTEST = "asm.review.attest";

    /** An attestation as the interface reads it. */
    public record Attestation(String id, String assetId, String assetName,
            String performedFrom, String performedTo, String performedBy,
            String evidenceRef, String note,
            String attestedBy, String attestedByName, String attestedAt,
            String withdrawnAt, String withdrawalReason) {

        /** Live attestations count towards coverage; withdrawn ones stay on the record and do not. */
        public boolean live() {
            return withdrawnAt == null;
        }
    }

    /** An attestation as a caller asks for it. */
    public record Draft(UUID assetId, LocalDate performedFrom, LocalDate performedTo,
            String performedBy, String evidenceRef, String note) {
    }

    private final DataSource dataSource;
    private final aspm.app.audit.AuditTrail audit =
            new aspm.app.audit.AuditTrail(java.time.Clock.systemUTC());

    public ReviewAttestations(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    /**
     * Every attestation for the applications the caller can reach, withdrawn ones included.
     *
     * <p>Withdrawn rows are returned and marked. A retracted claim is what a later review of the
     * coverage figures needs to see; hiding it would report a claim that was never made.
     */
    public List<Attestation> forScope(Principal principal) throws SQLException {
        Set<UUID> scope = principal == null ? Set.of() : principal.scopeNodeIds();
        if (scope.isEmpty()) {
            return List.of();
        }
        List<Attestation> rows = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT t.id::text, t.asset_id::text, a.display_name,
                               to_char(t.performed_from, 'YYYY-MM-DD'),
                               to_char(t.performed_to, 'YYYY-MM-DD'),
                               t.performed_by, t.evidence_ref, t.note,
                               t.attested_by::text, p.display_name,
                               to_char(t.attested_at, 'YYYY-MM-DD'),
                               to_char(t.withdrawn_at, 'YYYY-MM-DD'), t.withdrawal_reason
                          FROM application_review_attestation t
                          JOIN asset a ON a.id = t.asset_id
                          LEFT JOIN principal p ON p.id = t.attested_by
                         WHERE a.owning_node_id IN (SELECT descendant_id FROM org_closure
                                                     WHERE ancestor_id = ANY (?))
                         ORDER BY t.performed_to DESC, a.display_name
                        """)) {
            statement.setArray(1, connection.createArrayOf("uuid", scope.toArray(new UUID[0])));
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    rows.add(new Attestation(r.getString(1), r.getString(2), r.getString(3),
                            r.getString(4), r.getString(5), r.getString(6), r.getString(7),
                            r.getString(8), r.getString(9), r.getString(10), r.getString(11),
                            r.getString(12), r.getString(13)));
                }
            }
        }
        return rows;
    }

    /**
     * Asserts that a review happened.
     *
     * <p>Returns empty where the application is not the caller's to attest for, which the endpoint
     * answers as 404 — the same answer as for an application that does not exist
     * ({@code SEC-AUZ-020}).
     *
     * <p>The target must be an APPLICATION. The periodic obligation {@code full_review_policy}
     * expresses is per application, so an attestation against a project would discharge nothing and
     * would sit in the record looking as though it had.
     */
    public Optional<UUID> attest(Principal principal, Draft draft) throws SQLException {
        Objects.requireNonNull(draft, "a draft is required");
        if (draft.performedFrom() == null || draft.performedTo() == null) {
            throw new IllegalArgumentException("an attestation needs the period the work covered");
        }
        if (draft.performedTo().isBefore(draft.performedFrom())) {
            throw new IllegalArgumentException("a period ending before it starts is not a period: "
                    + draft.performedFrom() + " to " + draft.performedTo());
        }
        // Refused here as well as by the CHECK, so the caller gets a message rather than a constraint
        // violation. The CHECK stays because it is the thing that cannot be forgotten.
        if (draft.performedTo().isAfter(LocalDate.now(java.time.ZoneOffset.UTC))) {
            throw new IllegalArgumentException("an assessment cannot have finished in the future: "
                    + draft.performedTo() + ". This records history; to plan future work, use a "
                    + "planned window.");
        }
        Set<UUID> scope = principal == null ? Set.of() : principal.scopeNodeIds();
        if (scope.isEmpty()) {
            return Optional.empty();
        }
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO application_review_attestation
                            (tenant_id, asset_id, performed_from, performed_to, performed_by,
                             evidence_ref, note, attested_by, updated_by)
                        SELECT current_tenant_id(), a.id, ?, ?, ?, ?, ?, ?, ?
                          FROM asset a
                          JOIN asset_type ty ON ty.id = a.type_id AND ty.code = 'APPLICATION'
                         WHERE a.id = ?
                           AND a.lifecycle_state <> 'RETIRED'
                           AND a.owning_node_id IN (SELECT descendant_id FROM org_closure
                                                     WHERE ancestor_id = ANY (?))
                        RETURNING id
                        """)) {
            statement.setObject(1, draft.performedFrom());
            statement.setObject(2, draft.performedTo());
            statement.setString(3, blankToNull(draft.performedBy()));
            statement.setString(4, blankToNull(draft.evidenceRef()));
            statement.setString(5, blankToNull(draft.note()));
            statement.setObject(6, principal.principalId());
            statement.setObject(7, principal.principalId());
            statement.setObject(8, draft.assetId());
            statement.setArray(9, connection.createArrayOf("uuid", scope.toArray(new UUID[0])));
            UUID created;
            try (ResultSet r = statement.executeQuery()) {
                if (!r.next()) {
                    // The INSERT ... SELECT matched no asset, so nothing was written. Close the unit
                    // of work anyway: TenantConnections counts a statement that ran as a write and
                    // refuses to close a written transaction without commit, which would turn this
                    // 404 into a 500. Measured on the plan-window path; the same trap.
                    connection.commit();
                    return Optional.empty();
                }
                created = r.getObject(1, UUID.class);
            }
            // The evidence reference and the period travel into the trail. The note does not: it is
            // free text a person wrote for other people, and the trail records the claim, not the
            // prose around it.
            audit.domainChange(connection, principal, "application_review_attestation",
                    aspm.kernel.audit.contract.DomainChangeKind.CREATED, created,
                    aspm.app.audit.AuditScopes.ofAsset(connection, draft.assetId()),
                    java.util.Map.of(
                            "asset_id", draft.assetId().toString(),
                            "performed_from", draft.performedFrom().toString(),
                            "performed_to", draft.performedTo().toString(),
                            "performed_by", draft.performedBy() == null
                                    ? "not stated" : draft.performedBy(),
                            "has_evidence", Boolean.valueOf(
                                    blankToNull(draft.evidenceRef()) != null)));
            connection.commit();
            return Optional.of(created);
        }
    }

    /**
     * Withdraws an attestation. The row stays.
     *
     * <p>A reason is required, and that is not bureaucracy: an attestation is withdrawn either because
     * it was wrong or because the evidence turned out not to support it, and those have different
     * consequences for the coverage figure it was propping up. Deleting the row instead would make a
     * retracted claim indistinguishable from one never made.
     */
    public Optional<UUID> withdraw(Principal principal, UUID id, String reason) throws SQLException {
        Objects.requireNonNull(id, "an attestation identifier is required");
        if (blankToNull(reason) == null) {
            throw new IllegalArgumentException("withdrawing an attestation needs a reason: the "
                    + "coverage figure it supported changes back, and the next reader has to know why");
        }
        Set<UUID> scope = principal == null ? Set.of() : principal.scopeNodeIds();
        if (scope.isEmpty()) {
            return Optional.empty();
        }
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE application_review_attestation t
                           SET withdrawn_at = now(), withdrawn_by = ?, withdrawal_reason = ?,
                               updated_at = now(), updated_by = ?,
                               row_version = t.row_version + 1
                         WHERE t.id = ? AND t.withdrawn_at IS NULL
                           AND EXISTS (SELECT 1 FROM asset a
                                        WHERE a.id = t.asset_id
                                          AND a.owning_node_id IN
                                              (SELECT descendant_id FROM org_closure
                                                WHERE ancestor_id = ANY (?)))
                        """)) {
            statement.setObject(1, principal.principalId());
            statement.setString(2, reason.strip());
            statement.setObject(3, principal.principalId());
            statement.setObject(4, id);
            statement.setArray(5, connection.createArrayOf("uuid", scope.toArray(new UUID[0])));
            if (statement.executeUpdate() == 0) {
                // Already withdrawn, not the caller's, or absent — one answer for all three. See
                // attest() for why the empty unit of work still has to be committed.
                connection.commit();
                return Optional.empty();
            }
            audit.domainChange(connection, principal, "application_review_attestation",
                    aspm.kernel.audit.contract.DomainChangeKind.RETIRED, id, null,
                    java.util.Map.of("withdrawal_reason", reason.strip()));
            connection.commit();
            return Optional.of(id);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private Connection open(Principal principal) throws SQLException {
        Objects.requireNonNull(principal, "a principal is required: the tenant context comes from the "
                + "authenticated caller and from nowhere else (SEC-TEN-004)");
        return TenantConnections.open(dataSource, principal);
    }
}
