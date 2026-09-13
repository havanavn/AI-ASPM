package aspm.app.secrets;

import aspm.sharedkernel.secrets.SecretReference;
import aspm.sharedkernel.secrets.SecretsProvider;
import aspm.app.assessment.CredentialCustody;
import aspm.app.persistence.TenantConnections;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * {@code sealed:<row id>} — the platform-provided default of ADR-052: AES-256-GCM under a key held
 * outside the database, one row per secret in {@code platform_secret} (V073). {@code SEC-SEC-023},
 * {@code SEC-PTR-007}, {@code OPS-DEP-021}.
 *
 * <h2>What this is, and what it is not</h2>
 *
 * <p>It is the option for a deployment that has no enterprise vault: evaluation, the internal-first
 * deployment of OQ-010, a small subsidiary. The value never enters the database in the clear, the key
 * never enters the database at all, and a backup restored without the key is ciphertext. Per-tenant
 * partition is the row-level policy on the table: a reference presented under the wrong tenant finds
 * no row, which is the same answer as a reference that never existed.
 *
 * <p>It is not {@code OPS-DEP-021}: the key is one deployment-wide AES key rather than a per-tenant data
 * key wrapped by a hardware-backed key-encryption key. That gap is the one ADR-052 accepted for the
 * platform-provided default and is why the default is a default — a group that needs the stronger
 * property brings a vault, and the reference in the configuration row changes prefix and nothing else.
 *
 * <p>The key comes from wherever {@link CredentialCustody} gets it today: {@code ASPM_CREDENTIAL_KEY}, or
 * a {@code file:} reference resolved through another provider at start (see {@link Secrets}). It is the
 * same key that seals test credentials and AI provider keys, so there is one key to rotate and one place
 * that knows the algorithm.
 */
public final class SealedSecretsProvider implements SecretsProvider {

    public static final String KIND = "sealed";

    private final DataSource dataSource;
    private final CredentialCustody custody;

    public SealedSecretsProvider(DataSource dataSource, CredentialCustody custody) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.custody = Objects.requireNonNull(custody);
    }

    public boolean available() {
        return custody.available();
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public Optional<char[]> resolve(UUID tenantId, SecretReference reference) {
        if (!KIND.equals(reference.provider()) || !custody.available()) {
            return Optional.empty();
        }
        UUID id;
        try {
            id = UUID.fromString(reference.path());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        try (Connection connection = TenantConnections.openForTenant(dataSource, tenantId)) {
            byte[] ciphertext;
            byte[] nonce;
            String algorithm;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT ciphertext, nonce, algorithm FROM platform_secret "
                            + "WHERE id = ? AND destroyed_at IS NULL")) {
                statement.setObject(1, id);
                try (ResultSet r = statement.executeQuery()) {
                    if (!r.next()) {
                        connection.commit();
                        return Optional.empty();
                    }
                    ciphertext = r.getBytes(1);
                    nonce = r.getBytes(2);
                    algorithm = r.getString(3);
                }
            }
            // Per-object access record, cheap and always on: PRD-CON-021 and ADR-052 want access to a
            // held credential to be observable, and "when was this last read" is the question an
            // incident asks first.
            try (PreparedStatement touch = connection.prepareStatement(
                    "UPDATE platform_secret SET last_accessed_at = now(), access_count = access_count + 1 "
                            + "WHERE id = ?")) {
                touch.setObject(1, id);
                touch.executeUpdate();
            }
            connection.commit();
            return custody.open(ciphertext, nonce, algorithm).map(String::toCharArray);
        } catch (SQLException e) {
            // Unreachable store reads as absent. The caller cannot act on the difference, and the
            // difference is logged where operators look.
            System.getLogger("aspm.secrets").log(System.Logger.Level.ERROR,
                    "sealed secret resolution failed: " + e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public boolean writable() {
        return custody.available();
    }

    @Override
    public SecretReference store(UUID tenantId, String namespace, String name, char[] value) {
        SecretsProvider.segment(namespace, "namespace");
        SecretsProvider.segment(name, "name");
        if (!custody.available()) {
            throw new SecretsException("NO_SEALING_KEY", "no credential key is configured, so this deployment "
                    + "cannot hold a secret; set " + CredentialCustody.KEY_VARIABLE + " or configure a vault");
        }
        if (value == null || value.length == 0) {
            throw new IllegalArgumentException("nothing to store");
        }
        CredentialCustody.Sealed sealed = custody.seal(new String(value));
        try (Connection connection = TenantConnections.openForTenant(dataSource, tenantId);
                PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO platform_secret (tenant_id, namespace, name, ciphertext, nonce, "
                                + "algorithm, fingerprint) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id")) {
            statement.setObject(1, tenantId);
            statement.setString(2, namespace);
            statement.setString(3, name);
            statement.setBytes(4, sealed.ciphertext());
            statement.setBytes(5, sealed.nonce());
            statement.setString(6, sealed.algorithm());
            statement.setString(7, fingerprint(value));
            UUID id;
            try (ResultSet r = statement.executeQuery()) {
                r.next();
                id = r.getObject(1, UUID.class);
            }
            connection.commit();
            return new SecretReference(KIND, id.toString());
        } catch (SQLException e) {
            throw new SecretsException("STORE_FAILED", e.getClass().getSimpleName());
        }
    }

    @Override
    public void destroy(UUID tenantId, SecretReference reference) {
        if (!KIND.equals(reference.provider())) {
            return;
        }
        UUID id;
        try {
            id = UUID.fromString(reference.path());
        } catch (IllegalArgumentException e) {
            return;
        }
        // The ciphertext is overwritten, not only flagged: a destroyed secret that is still readable
        // to anybody holding the key is not destroyed (SEC-TEN-016, ADR-034 on demonstrable deletion).
        try (Connection connection = TenantConnections.openForTenant(dataSource, tenantId);
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE platform_secret SET destroyed_at = now(), ciphertext = '\\x'::bytea, "
                                + "nonce = '\\x'::bytea WHERE id = ? AND destroyed_at IS NULL")) {
            statement.setObject(1, id);
            statement.executeUpdate();
            connection.commit();
        } catch (SQLException e) {
            throw new SecretsException("DESTROY_FAILED", e.getClass().getSimpleName());
        }
    }

    @Override
    public String description() {
        return custody.available()
                ? "sealed (platform default: AES-256-GCM rows in platform_secret, key outside the database; "
                        + "ONE deployment key, not per-tenant wrapped keys — OPS-DEP-021 gap accepted in ADR-052)"
                : "sealed (UNAVAILABLE: no " + CredentialCustody.KEY_VARIABLE + "; tenant-entered secrets are refused)";
    }

    /** Eight hex characters of SHA-256: enough to tell "did I paste the new one", never the value. */
    static String fingerprint(char[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(new String(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }
}
