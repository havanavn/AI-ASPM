package aspm.app.identity;

import aspm.app.runtime.Dispatcher;
import aspm.app.runtime.Principal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Removes sessions that expired more than a grace period ago. V050.
 *
 * <h2>Why this is a call the ticker makes and not a thread in the application</h2>
 *
 * <p>The application tier deliberately has no scheduler (V042): with two replicas a timer inside it
 * runs twice, and {@code OPS-DEP-007} requires a singleton with leader election. The scanner container
 * already ticks and already holds a signed service credential, so this is one more call on the one
 * timer that exists rather than a second mechanism for "periodically".
 *
 * <h2>The grace period is the interesting decision</h2>
 *
 * <p>Deleting at the instant of expiry would be simpler and worse. "Why was I signed out" is a question
 * that arrives after the session is already gone, and the row is the only evidence. Seven days answers
 * it; beyond that the row is a cost with no reader.
 *
 * <p>Concurrent runs are harmless: the statement is a DELETE over a closed predicate, so two callers
 * converge on the same state rather than doubling anything. That is what makes it safe on a tick with
 * no leader election, and it is the property that would NOT hold for the scan this tick also drives.
 */
public final class SessionReaper {

    /** Housekeeping, not a decision. Held by the ticking credential, not by a human role. */
    public static final String REAP = "iam.session.reap";

    /** How long an expired session is kept as evidence. See the class note. */
    private static final int GRACE_DAYS = 7;

    private final DataSource dataSource;

    public SessionReaper(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    /**
     * {@code POST /api/v1/session-reap}. Class F — the same door the scanner already uses.
     *
     * <p>Reports what it removed and what remains, because a maintenance call that answers only "ok"
     * gives an operator no way to tell a working reaper from one whose predicate stopped matching.
     */
    public Dispatcher.Response reap(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        try (Connection connection =
                aspm.app.persistence.TenantConnections.open(dataSource, principal)) {
            int removed;
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM principal_session "
                            + " WHERE absolute_expires_at < now() - make_interval(days => ?)")) {
                statement.setInt(1, GRACE_DAYS);
                removed = statement.executeUpdate();
            }
            long live = 0;
            long expired = 0;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT count(*) FILTER (WHERE absolute_expires_at > now()), "
                            + "       count(*) FILTER (WHERE absolute_expires_at <= now()) "
                            + "  FROM principal_session")) {
                try (ResultSet r = statement.executeQuery()) {
                    if (r.next()) {
                        live = r.getLong(1);
                        expired = r.getLong(2);
                    }
                }
            }
            // The removal and the counts are one unit of work: reporting a figure this transaction
            // has not committed would be reporting a number that may never become true.
            connection.commit();
            return new Dispatcher.Response(200, Map.of(
                    "removed", Integer.valueOf(removed),
                    "grace_days", Integer.valueOf(GRACE_DAYS),
                    // Both remaining counts, so an operator can see the reaper working AND see that
                    // recently expired rows are being kept on purpose rather than missed.
                    "live", Long.valueOf(live),
                    "expired_within_grace", Long.valueOf(expired)), Map.of());
        }
    }
}
