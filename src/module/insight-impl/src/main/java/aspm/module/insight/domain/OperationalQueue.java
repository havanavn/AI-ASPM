package aspm.module.insight.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The twelve operational queues of DOC-12 section 6.1, as an enumeration with their highlight rules.
 *
 * <p>DOC-12 singles two of them out: "<b>Queues ⑧ and ⑪ exist because their absence is the classic blind
 * spot.</b> If forty assets have silently had no data for three months, the vulnerability dashboard shows green
 * — not because they are secure but because there is no data."
 *
 * <p>That is the whole argument for having them enumerated here rather than assembled per screen. A queue that
 * exists only as a saved view somebody configured is a queue a tenant can be deployed without, and the two that
 * matter most are the two nobody thinks to configure.
 *
 * <h2>The highlight rule travels with the queue</h2>
 *
 * <p>Each constant carries its own threshold and the reason for it. A threshold living in a dashboard
 * configuration is one a tenant can raise until the red disappears — which is the reporting equivalent of the
 * gaming paths DOC-28 section 13.2 enumerates, and it produces a queue that is always empty and always wrong.
 */
public enum OperationalQueue {

    /** ① Unassigned and needs triage. */
    UNASSIGNED_NEEDS_TRIAGE(1, "Unassigned and needs triage",
            "age over 2 days, or days to go-live below the estimated effort",
            "the second condition is the one that matters: a request whose remaining time is already less "
                    + "than its estimate cannot be delivered, and saying so on day one is the only useful "
                    + "moment"),

    /** ② Awaiting requester or third party. */
    AWAITING_EXTERNAL(2, "Awaiting requester or third party", "days waiting over 7",
            "waiting is visible and attributed (PP-6); an unattributed wait defaults to blaming the "
                    + "accountable team"),

    /** ③ In progress. */
    IN_PROGRESS(3, "In progress", "service level remaining below 20%",
            "a remaining-budget threshold rather than a breach threshold, because renegotiation is only "
                    + "possible before the date is missed"),

    /** ④ Awaiting remediation. */
    AWAITING_REMEDIATION(4, "Awaiting remediation", "remediation deadline breached",
            "a widening band here is not the security team's problem, and the cumulative flow view is what "
                    + "distinguishes the two bottlenecks"),

    /** ⑤ Awaiting verification. */
    AWAITING_VERIFICATION(5, "Awaiting verification", "none",
            "no highlight: this queue is short-lived by design, and a threshold on it would fire constantly "
                    + "and be ignored"),

    /** ⑥ At risk and breached. */
    AT_RISK_AND_BREACHED(6, "At risk and breached", "sorted by days over",
            "sorted rather than highlighted — everything here is already breached, so a red marker on all of "
                    + "it distinguishes nothing"),

    /** ⑦ Report pipeline. */
    REPORT_PIPELINE(7, "Report pipeline", "none",
            "DOC-12 calls this a frequently overlooked stage: work that is finished and unreported is "
                    + "indistinguishable from work not done, to everyone outside the team"),

    /**
     * ⑧ Coverage health. <b>One of the two classic blind spots.</b>
     *
     * <p>"If forty assets have silently had no data for three months, the vulnerability dashboard shows green —
     * not because they are secure but because there is no data."
     */
    COVERAGE_HEALTH(8, "Coverage health", "never measured, or stale beyond threshold",
            "the classic blind spot. An asset with no data produces no findings, and no findings reads as "
                    + "good news everywhere it is aggregated (PP-1)"),

    /** ⑨ Unowned assets. */
    UNOWNED_ASSETS(9, "Unowned assets", "days unowned over 14",
            "an unowned asset has nobody to route its findings to, so its findings accumulate against "
                    + "nobody's queue and appear in nobody's report"),

    /** ⑩ Exception expiry. */
    EXCEPTION_EXPIRY(10, "Exception expiry", "expires within 14 days",
            "fourteen days is a renewal window, not a warning: an exception expiring tomorrow reopens a "
                    + "finding nobody has planned for"),

    /**
     * ⑪ Integration health. <b>The other classic blind spot.</b>
     *
     * <p>Surfaces <i>intermittent</i> failure through success rate, "which circuit breaking does not catch"
     * ({@code PRD-CON-031}) — a connector failing one submission in three never opens a circuit and loses a
     * third of the data.
     */
    INTEGRATION_HEALTH(11, "Integration health", "circuit open, or success rate below threshold",
            "the success rate is the half circuit breaking misses: a connector failing one submission in "
                    + "three never trips and silently loses a third of the data (PRD-CON-031)"),

    /** ⑫ Confirmed-live secrets. */
    CONFIRMED_LIVE_SECRETS(12, "Confirmed-live secrets", "always highlighted",
            "the only queue highlighted unconditionally. A validated live credential is not a risk to weigh, "
                    + "it is an active exposure whose only remediation is rotation (PRD-VUL-019)");

    private final int number;
    private final String title;
    private final String highlightRule;
    private final String whyThisRule;

    OperationalQueue(int number, String title, String highlightRule, String whyThisRule) {
        this.number = number;
        this.title = title;
        this.highlightRule = highlightRule;
        this.whyThisRule = whyThisRule;
    }

    public int number() {
        return number;
    }

    public String title() {
        return title;
    }

    /** The condition under which a row is highlighted. Carried by the queue, not by a tenant setting. */
    public String highlightRule() {
        return highlightRule;
    }

    /**
     * Why that rule and not another.
     *
     * <p>Recorded so a tenant asking to change a threshold can be told what the threshold is for — which is the
     * conversation that either produces a better threshold or ends the request.
     */
    public String whyThisRule() {
        return whyThisRule;
    }

    /** The two DOC-12 names as blind spots. Present so a deployment check can assert they exist. */
    public boolean isClassicBlindSpot() {
        return this == COVERAGE_HEALTH || this == INTEGRATION_HEALTH;
    }

    /** Every queue, in DOC-12's order. */
    public static List<OperationalQueue> inDocumentOrder() {
        return java.util.Arrays.stream(values())
                .sorted(java.util.Comparator.comparingInt(OperationalQueue::number))
                .toList();
    }
}
