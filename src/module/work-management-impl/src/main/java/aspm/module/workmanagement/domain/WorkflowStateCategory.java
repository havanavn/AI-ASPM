package aspm.module.workmanagement.domain;

/**
 * The four state categories of DOC-04 section 16.1.
 *
 * <p>The <b>categories</b> are product-fixed; the <b>states</b> within them are tenant data (ADR-027). That split
 * is what makes cumulative flow comparable across tenants and work item types: a tenant may call its in-progress
 * state anything, and flow analysis still knows it is in progress. {@code PRD-WRK-038} requires every default
 * workflow to populate all four, "so that flow and cycle-time analysis is meaningful without tenant
 * configuration".
 *
 * <p>Category is deliberately <b>not</b> the source of the clock-pause behaviour — see
 * {@link WorkflowState#slaClockRunning()}.
 */
public enum WorkflowStateCategory {

    /** Not yet started. Entry category for a new item. */
    OPEN,

    /** Being worked. */
    IN_PROGRESS,

    /**
     * Waiting on somebody outside the accountable team.
     *
     * <p>A default lacking this category "would make blocking attribution unavailable out of the box"
     * ({@code PRD-WRK-038}), and unattributed waiting is what PP-6 exists to prevent.
     */
    WAITING_EXTERNAL,

    /** No outbound transitions. At least one is required before a definition may be activated. */
    TERMINAL;

    public boolean isTerminal() {
        return this == TERMINAL;
    }
}
