package aspm.module.riskprioritization.domain;

/**
 * The score band of DOC-28 section 6.3. {@code PRD-RSK-019} requires bands presented with the numeric value
 * secondary, "because a score of 73 and a score of 71 are not meaningfully different — the inputs are not that
 * precise", and presenting them as if they are "invites argument about differences that carry no information".
 *
 * <p>The band <b>names</b> are product-fixed; the <b>thresholds</b> are tenant-configurable within bounds
 * (DOC-28 section 7.1, {@link BandThresholds}). This split matters: a tenant that renamed bands would break every
 * cross-tenant comparison and every default service level policy that matches on a band (DOC-28 section 11.1),
 * while moving a threshold only changes where its own findings fall.
 *
 * <p>Not to be confused with a tenant's <b>severity</b> taxonomy, which is configurable data
 * ({@code PRD-VUL-005}). Severity is what a scanner reported; a band is what this model concluded.
 */
public enum ScoreBand {

    INFORMATIONAL,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    /**
     * Whether a band carries a remediation commitment by default.
     *
     * <p>DOC-28 section 11.2: informational carries none, because "assigning a deadline to findings that will not
     * be remediated trains everyone to ignore deadline notifications, which destroys the mechanism for the
     * findings that matter". A tenant may still configure a policy that matches informational; this is the
     * default, not a prohibition.
     */
    public boolean carriesCommitmentByDefault() {
        return this != INFORMATIONAL;
    }
}
