package aspm.app.resource;

import aspm.app.assessment.CredentialCustody;
import aspm.app.persistence.TenantConnections;
import aspm.app.runtime.Principal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Per-tenant model provider configuration. V045.
 *
 * <h2>This configures a seam, not a feature</h2>
 *
 * <p>ADR-044 defers AI capability from v1. Nothing here calls a model to do work — the only outbound
 * request it makes is an explicit connection test somebody pressed. What it establishes is that when
 * an analysis agent arrives, the provider it uses is <b>tenant configuration</b> rather than a
 * deployment-wide environment variable, because the second shape cannot be un-chosen once keys are in
 * it.
 *
 * <h2>The key is recoverable, and that is a deliberate asymmetry</h2>
 *
 * <p>{@link aspm.app.identity.ServiceCredentialAdmin} stores a digest: the platform only verifies
 * signatures other people compute, so it never needs the secret back. This is the other direction —
 * the platform must present the key to a third party — so it is sealed with
 * {@link CredentialCustody} and can be opened. That makes a row here replayable in a way an inbound
 * credential is not, which is why {@link #MANAGE} is restricted and step-up.
 *
 * <p>It is never returned. Not masked, not truncated — absent (ADR-047). {@link #resolve} exists for
 * the agent to use in-process and has no route in front of it.
 *
 * <h2>What the agent will owe, recorded here because this is where it starts</h2>
 *
 * <p>Whatever the agent generates must carry a {@code basis} naming what produced it, and the
 * provider row is what that cites: kind, model, and the prompt version the agent adds. An observation
 * whose basis is absent is one a reader cannot challenge, and ADR-005 keeps every AI output a
 * suggestion until a human promotes it.
 */
public final class AiProviderService {

    /** Configuring a provider. Restricted and step-up: a live third-party key, and an egress decision. */
    public static final String MANAGE = "cfg.ai.manage";

    /** One configured provider. Carries no part of the key. */
    public record Provider(String id, String label, String providerKind, String baseUrl, String model,
            String keyFingerprint, boolean sendRecordContent, boolean active, boolean sealed,
            String keyReference, String lastTestedAt, String lastTestStatus, String lastTestDetail,
            String updatedAt) {
    }

    /** A provider plus the opened key, for in-process use only. Never serialized. */
    public record Resolved(UUID id, String providerKind, String baseUrl, String model, String apiKey,
            boolean sendRecordContent) {
    }

    private final DataSource dataSource;

    /** {@code CON-PLT-021}: the record is written in the transaction that makes the change. */
    private final aspm.app.audit.AuditTrail audit =
            new aspm.app.audit.AuditTrail(java.time.Clock.systemUTC());
    private final CredentialCustody custody = CredentialCustody.from(System.getenv());

    public AiProviderService(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    /** Whether this deployment can hold a key at all. False where no credential key is configured. */
    public boolean custodyAvailable() {
        return custody.available();
    }

    public List<Provider> list(Principal principal) throws SQLException {
        List<Provider> out = new ArrayList<>();
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT id::text, label, provider_kind, base_url, model, key_fingerprint,
                               send_record_content, active,
                               (api_key_ciphertext IS NOT NULL), key_reference,
                               to_char(last_tested_at, 'YYYY-MM-DD HH24:MI'), last_test_status,
                               last_test_detail, to_char(updated_at, 'YYYY-MM-DD HH24:MI')
                          FROM ai_provider
                         ORDER BY active DESC, label
                        """)) {
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    out.add(new Provider(r.getString(1), r.getString(2), r.getString(3),
                            r.getString(4), r.getString(5), r.getString(6), r.getBoolean(7),
                            r.getBoolean(8), r.getBoolean(9), r.getString(10), r.getString(11),
                            r.getString(12), r.getString(13), r.getString(14)));
                }
            }
        }
        return List.copyOf(out);
    }

    /** Why a submission was refused, so the page can say which field is wrong. */
    public record Rejection(String code, String field, String detail) {
    }

    /**
     * Configures a provider.
     *
     * @param apiKey the plaintext key, sealed here and never stored bare. Ignored where
     *     {@code keyReference} is given, which is the path OQ-026's vault answer will take.
     * @return the new row's id, or a rejection naming the field
     */
    public Object create(Principal principal, String label, String providerKind, String baseUrl,
            String model, String apiKey, String keyReference, boolean sendRecordContent)
            throws SQLException {
        String trimmedLabel = label == null ? "" : label.strip();
        String kind = providerKind == null ? "" : providerKind.strip();
        String trimmedModel = model == null ? "" : model.strip();
        String endpoint = baseUrl == null || baseUrl.isBlank() ? null : baseUrl.strip();
        String reference = keyReference == null || keyReference.isBlank() ? null : keyReference.strip();

        if (trimmedLabel.isEmpty() || trimmedLabel.length() > 120) {
            return new Rejection("LABEL_REQUIRED", "label", "give this configuration a short name");
        }
        if (kind.isEmpty() || kind.length() > 80) {
            return new Rejection("PROVIDER_REQUIRED", "providerKind", "name the provider");
        }
        if (trimmedModel.isEmpty() || trimmedModel.length() > 200) {
            return new Rejection("MODEL_REQUIRED", "model", "name the model to use");
        }
        // The SAME egress guard the webhook destinations use. A base URL is an outbound destination
        // with the same exposure: an endpoint resolving to a private address turns the AI feature into
        // a request forger with the platform's network position. One guard, so the two cannot diverge.
        if (endpoint != null && !WebhookAlerts.permitted(endpoint)) {
            return new Rejection("ENDPOINT_REFUSED", "baseUrl",
                    "the endpoint must be an https URL outside private address ranges");
        }
        if (reference == null && (apiKey == null || apiKey.isBlank())) {
            return new Rejection("KEY_REQUIRED", "apiKey",
                    "paste the provider's API key, or give a reference to where it is held");
        }
        if (reference == null && !custody.available()) {
            // Refused, not stored bare. A deployment with nowhere safe to put a key must not be
            // handed one — SEC-PTR-007's rule, and the reason CredentialCustody.seal throws.
            return new Rejection("NO_CUSTODY", "apiKey",
                    "this deployment has no credential key configured, so it cannot hold a provider "
                            + "key. Configure one, or supply a reference to an external secret store");
        }

        CredentialCustody.Sealed sealed = reference == null
                ? custody.seal(apiKey.strip())
                : null;

        UUID id;
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO ai_provider (label, provider_kind, base_url, model,
                                                 api_key_ciphertext, api_key_nonce, api_key_algorithm,
                                                 key_reference, key_fingerprint, send_record_content,
                                                 created_by, updated_by)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        RETURNING id
                        """)) {
            statement.setString(1, trimmedLabel);
            statement.setString(2, kind);
            statement.setString(3, endpoint);
            statement.setString(4, trimmedModel);
            statement.setBytes(5, sealed == null ? null : sealed.ciphertext());
            statement.setBytes(6, sealed == null ? null : sealed.nonce());
            statement.setString(7, sealed == null ? null : sealed.algorithm());
            statement.setString(8, reference);
            statement.setString(9, reference == null ? fingerprint(apiKey.strip()) : null);
            statement.setBoolean(10, sendRecordContent);
            statement.setObject(11, principal.principalId());
            statement.setObject(12, principal.principalId());
            try (ResultSet r = statement.executeQuery()) {
                if (!r.next()) {
                    // Abandoned explicitly rather than by falling out of the block. The unit-of-work
                    // guard refuses to close over uncommitted writes, so "I meant to discard this" has
                    // to be said out loud — which is the point: it is otherwise indistinguishable from
                    // a forgotten commit.
                    connection.rollback();
                    return new Rejection("NOT_STORED", null, "the provider could not be stored");
                }
                id = r.getObject(1, UUID.class);
            }
            // The endpoint and the model, never the key or its ciphertext. ADR-005 keeps AI out of
            // the system of record; what this event answers is where record content may now be sent
            // and under whose decision — the question OQ-027 leaves open at the deployment level.
            audit.event(connection, principal,
                    aspm.kernel.audit.contract.AuditEventType.AI_CONFIGURATION_CHANGED, id, null,
                    java.util.Map.of("label", trimmedLabel,
                            "provider_kind", kind,
                            "base_url", endpoint == null ? "the provider default" : endpoint,
                            "model", trimmedModel,
                            "key_held_by", reference == null ? "this platform" : "an external store",
                            "send_record_content", Boolean.valueOf(sendRecordContent)));
            connection.commit();
        }
        return id.toString();
    }

    /**
     * Turns one on or off. Never deletes.
     *
     * <p>No DELETE is granted on the table. A provider that produced an observation has to stay
     * resolvable or the {@code basis} on a stored suggestion points at nothing, and ADR-005 makes the
     * suggestion ledger the record of what the AI said.
     */
    public boolean setActive(Principal principal, UUID id, boolean active) throws SQLException {
        if (id == null) {
            return false;
        }
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE ai_provider SET active = ?, updated_at = now(), updated_by = ?,
                               row_version = row_version + 1
                         WHERE id = ?
                        """)) {
            statement.setBoolean(1, active);
            statement.setObject(2, principal.principalId());
            statement.setObject(3, id);
            boolean applied = statement.executeUpdate() == 1;
            if (applied) {
                audit.event(connection, principal,
                        aspm.kernel.audit.contract.AuditEventType.AI_CONFIGURATION_CHANGED, id, null,
                        java.util.Map.of("active", Boolean.valueOf(active)));
            }
            connection.commit();
            return applied;
        }
    }

    /**
     * The provider and its opened key, for the agent to use in process.
     *
     * <p>Has no HTTP route in front of it and must never acquire one. The key leaves this method only
     * to be put in an outbound request header.
     */
    public Optional<Resolved> resolve(Principal principal, UUID id) throws SQLException {
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement("""
                        SELECT id, provider_kind, base_url, model, api_key_ciphertext, api_key_nonce,
                               api_key_algorithm, send_record_content
                          FROM ai_provider
                         -- Cast, both of them. Without it PostgreSQL cannot infer a type for a null
                         -- parameter in `? IS NULL` and refuses the statement — which nothing noticed
                         -- until a caller finally asked for "the active provider, whichever it is".
                         -- The id-specific path had been exercised; this one never had.
                         WHERE active AND (?::uuid IS NULL OR id = ?::uuid)
                         ORDER BY label LIMIT 1
                        """)) {
            statement.setObject(1, id);
            statement.setObject(2, id);
            try (ResultSet r = statement.executeQuery()) {
                if (!r.next()) {
                    return Optional.empty();
                }
                Optional<String> key = custody.open(r.getBytes(5), r.getBytes(6), r.getString(7));
                return key.map(plain -> new Resolved(r0(r), s(r, 2), s(r, 3), s(r, 4), plain,
                        b(r, 8)));
            }
        }
    }

    /** Records the outcome of a connection test. The test itself is the caller's to perform. */
    // Deliberately not audited. A connectivity test changes no configuration and grants nothing; it
    // writes the outcome of a request the operator just made and can repeat. DOC-14 keeps the trail to
    // decisions and access, and an event per button press would bury both.
    public boolean recordTest(Principal principal, UUID id, String status, String detail)
            throws SQLException {
        try (Connection connection = open(principal);
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE ai_provider SET last_tested_at = now(), last_test_status = ?,
                               last_test_detail = ?
                         WHERE id = ?
                        """)) {
            statement.setString(1, status);
            // Bounded. A provider's error body can be long and this is rendered on a page.
            statement.setString(2, detail == null ? null
                    : detail.substring(0, Math.min(300, detail.length())));
            statement.setObject(3, id);
            boolean applied = statement.executeUpdate() == 1;
            connection.commit();
            return applied;
        }
    }

    // ----------------------------------------------------------------------------------------------

    /**
     * A short digest prefix of the key.
     *
     * <p>Never characters of the key itself. Showing the last four would disclose four characters of a
     * live bearer credential, which is four more than a reader needs to answer "is this the one I just
     * pasted" — and the answer to that question is what a fingerprint is for.
     */
    private static String fingerprint(String key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 8);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    private static UUID r0(ResultSet r) {
        try {
            return r.getObject(1, UUID.class);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String s(ResultSet r, int column) {
        try {
            return r.getString(column);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean b(ResultSet r, int column) {
        try {
            return r.getBoolean(column);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private Connection open(Principal principal) throws SQLException {
        Objects.requireNonNull(principal, "a principal is required: the tenant context comes from the "
                + "authenticated caller and from nowhere else (SEC-TEN-004)");
        return TenantConnections.open(dataSource, principal);
    }
}
