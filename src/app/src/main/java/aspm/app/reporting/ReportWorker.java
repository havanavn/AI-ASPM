package aspm.app.reporting;

import aspm.app.persistence.TenantConnections;
import aspm.kernel.tenantcontext.contract.EstablishedFrom;
import aspm.kernel.tenantcontext.contract.TenantContext;
import aspm.kernel.tenantcontext.contract.TenantContextHolder;
import aspm.sharedkernel.TenantId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

/**
 * Runs due report schedules. ADR-054 (BATCH class), {@code PRD-DSH-043}, {@code PRD-DSH-045}, {@code CON-PLT-031}.
 *
 * <p>A due schedule is claimed by pushing its {@code next_run_at} forward under {@code FOR UPDATE SKIP
 * LOCKED}, so two worker replicas never render the same run; the service then sets the real next run
 * when it finishes. A run that dies mid-way leaves the schedule parked one lease ahead, and the next tick
 * after that runs it — late, not twice.
 */
public final class ReportWorker implements Runnable, AutoCloseable {

    static final Duration LEASE = Duration.ofMinutes(10);
    static final Duration IDLE_SLEEP = Duration.ofSeconds(30);

    private final DataSource dataSource;
    private final UUID tenantId;
    private final ReportService service;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread thread;

    public ReportWorker(DataSource dataSource, UUID tenantId, ReportService service) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.service = Objects.requireNonNull(service);
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            thread = Thread.ofVirtual().name("aspm-report-worker").start(this);
        }
    }

    @Override
    public void close() {
        running.set(false);
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    @Override
    public void run() {
        System.Logger log = System.getLogger("aspm.report");
        log.log(System.Logger.Level.INFO, "report worker started for tenant " + tenantId);
        while (running.get()) {
            int processed;
            try {
                processed = tick();
            } catch (Exception e) {
                log.log(System.Logger.Level.ERROR, "report tick failed: " + e.getClass().getSimpleName());
                processed = 0;
            }
            if (processed == 0) {
                try {
                    Thread.sleep(IDLE_SLEEP);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** One pass: claim due schedules, run each. Returns how many ran. */
    public int tick() throws Exception {
        TenantContext context = TenantContext.of(new TenantId(tenantId), "vn", EstablishedFrom.SCHEDULED_JOB_BINDING, Instant.now());
        return TenantContextHolder.callWith(context, () -> {
            List<UUID> due = claim();
            for (UUID scheduleId : due) {
                service.runSchedule(tenantId, scheduleId);
            }
            return due.size();
        });
    }

    private List<UUID> claim() throws SQLException {
        List<UUID> out = new ArrayList<>();
        try (Connection connection = TenantConnections.openForTenant(dataSource, tenantId);
                PreparedStatement statement = connection.prepareStatement("""
                        UPDATE report_schedule s SET next_run_at = now() + make_interval(secs => ?), updated_at = now()
                         WHERE s.id IN (SELECT id FROM report_schedule WHERE lifecycle_state = 'ACTIVE' AND next_run_at <= now()
                                        ORDER BY next_run_at FOR UPDATE SKIP LOCKED LIMIT 5)
                        RETURNING s.id
                        """)) {
            statement.setDouble(1, LEASE.toSeconds());
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    out.add(r.getObject(1, UUID.class));
                }
            }
            connection.commit();
        }
        return out;
    }
}
