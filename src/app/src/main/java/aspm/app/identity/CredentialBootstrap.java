package aspm.app.identity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * First-run credential bootstrap.
 *
 * <p>A seeded principal has no credential, deliberately: Argon2id's parameters live in
 * {@link PasswordHash} and reproducing them in SQL would let the two drift, so the password is set by the
 * application or not at all.
 *
 * <h2>What makes this safe to have in the product</h2>
 *
 * <ul>
 *   <li><b>It only ever fills a gap.</b> A principal with a live credential is skipped, so this cannot
 *       reset anyone — a bootstrap that could overwrite an existing password would be a backdoor with a
 *       friendly name.
 *   <li><b>It requires an explicit value.</b> There is no default password. A deployment that sets
 *       nothing gets principals who cannot sign in, which is a visible problem; a default would be an
 *       invisible one.
 *   <li><b>Every principal it touches must change the password.</b> The value came from an environment
 *       variable, which {@code SEC-SEC-023} notes appears in process listings and crash dumps, so it is
 *       treated as compromised from the moment it is used.
 * </ul>
 *
 * <p>{@code SEC-PLT-001} requires every deliberately introduced privileged path to be recorded with the
 * controls that distinguish it from a backdoor. Those three are the controls; this is the record.
 */
public final class CredentialBootstrap {

    public static final String PASSWORD_VARIABLE = "ASPM_BOOTSTRAP_PASSWORD";

    private CredentialBootstrap() {
    }

    /**
     * Sets an initial credential for every principal that has none.
     *
     * @return the usernames it acted on, for the startup log. Never the password
     */
    public static List<String> run(DataSource dataSource, UUID tenantId, String password)
            throws SQLException {
        Objects.requireNonNull(password, "a bootstrap password is required");
        List<String> touched = new ArrayList<>();

        try (Connection connection =
                aspm.app.persistence.TenantConnections.openForTenant(dataSource, tenantId)) {

            // The policy applies to the bootstrap value too: a first password the platform's own policy
            // would reject is a first password somebody keeps.
            PasswordPolicy.Settings settings = PasswordPolicy.load(connection);
            PasswordPolicy.Result verdict = PasswordPolicy.evaluate(connection, settings,
                    password.toCharArray(), null, null, null);
            if (!verdict.acceptable()) {
                connection.rollback();
                throw new IllegalStateException(
                        PASSWORD_VARIABLE + " does not satisfy the tenant password policy: "
                                + verdict.failures() + ". The bootstrap value is held to the same policy "
                                + "as any other credential.");
            }

            List<UUID> without = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT p.id, p.username FROM principal p "
                            + " WHERE p.kind = 'HUMAN' AND p.lifecycle_state = 'ACTIVE' "
                            + "   AND NOT EXISTS (SELECT 1 FROM principal_credential c "
                            + "        WHERE c.principal_id = p.id AND c.retired_at IS NULL)");
                    ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    without.add(results.getObject(1, UUID.class));
                    touched.add(results.getString(2));
                }
            }

            for (UUID principalId : without) {
                IdentityService.replaceCredential(connection, principalId, password.toCharArray(),
                        "BOOTSTRAP");
                try (PreparedStatement flag = connection.prepareStatement(
                        "UPDATE principal SET must_change_password = true, "
                                + "row_version = row_version + 1 WHERE id = ?")) {
                    flag.setObject(1, principalId);
                    flag.executeUpdate();
                }
            }
            connection.commit();
        }
        return List.copyOf(touched);
    }
}
