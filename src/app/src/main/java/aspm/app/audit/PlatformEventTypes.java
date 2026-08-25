package aspm.app.audit;

import aspm.kernel.audit.application.ChainedAuditWriter;
import aspm.kernel.audit.contract.AuditEventType;
import aspm.kernel.audit.contract.DomainChangeKind;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Which event types this deployment recognises. {@code SEC-AUD-006}.
 *
 * <p>Two routes, and only two, because DOC-14 §3 defines two: the closed {@link AuditEventType} enum,
 * and {@code <aggregate>.<kind>} for a per-aggregate domain state change. The second route is closed
 * as well — the aggregate has to appear in {@link #AGGREGATES} — so a typo produces a refused write at
 * the emitting site rather than an event type nobody can find later.
 *
 * <p><b>Why the aggregate list is here and not derived.</b> Deriving it from the resource catalogue
 * would make it exactly as complete as the REST surface, and the interface writes aggregates the REST
 * surface does not expose. Listing it means a new aggregate has to be added deliberately, which is a
 * cheap step at the point where somebody is already deciding what the event is called; a derived list
 * would silently start accepting whatever the other list happened to contain.
 */
public final class PlatformEventTypes {

    private PlatformEventTypes() {
    }

    /**
     * The aggregates that emit domain state changes, named by their physical table.
     *
     * <p>The table name rather than a display name, because it is the one identifier that cannot drift
     * from the thing being written, and an exported trail read three years from now has to be matched
     * against a schema rather than against a vocabulary that has since been re-worded.
     */
    private static final Set<String> AGGREGATES = Set.of(
            "asset",
            // The declared-field catalogue. A separate aggregate from `asset` on purpose: deprecating
            // a field changes what every asset of a type shows without touching one asset row, so an
            // administrator reading the asset's own history would see nothing at all.
            "asset_attribute_definition",
            // The endpoint environment catalogue (ADR-061). Its own aggregate for the same reason as
            // the field catalogue above it: retiring an environment removes a domain input from every
            // editor and a column from every inventory list without touching one asset row, so the
            // change is invisible in the history of anything it affects.
            "asset_endpoint_environment",
            "asset_type",
            "org_node",
            "org_node_type",
            "criticality_tier",
            "finding",
            "assessment_request",
            "service_credential",
            "principal",
            "role",
            // Added when the interface's own write paths began recording. Until then the trail
            // covered the machine doors only — the REST resource endpoint, the two ingestion doors
            // and credential administration — so an action taken through the API left a record and
            // the SAME action taken through a form did not. These are the aggregates the interface
            // writes and the API does not expose.
            "comment",
            "prose_attachment",
            "assessor_team",
            "assessor_team_member",
            "asset_relationship",
            "risk_exception",
            "alert_webhook",
            "ai_provider",
            "full_review_policy",
            // A planned assessment window. A separate aggregate from `assessment_request` on
            // purpose: the plan and the record of work must be able to disagree, and an event that
            // said "assessment_request" for a window would put a plan into the history of work.
            "assessment_plan_window",
            "rescan_schedule",
            "password_policy");

    private static final Set<String> CODES = codes();

    private static Set<String> codes() {
        Set<String> codes = new LinkedHashSet<>();
        for (AuditEventType type : AuditEventType.values()) {
            codes.add(type.code());
        }
        for (String aggregate : AGGREGATES) {
            for (DomainChangeKind kind : DomainChangeKind.values()) {
                codes.add(kind.codeFor(aggregate));
            }
        }
        return Set.copyOf(codes);
    }

    /** The catalogue the writer checks every event against. */
    public static final ChainedAuditWriter.EventTypeCatalogue CATALOGUE = CODES::contains;

    /** Every code this deployment accepts. Exposed so a test can assert the coverage it claims. */
    public static Set<String> codesAccepted() {
        return CODES;
    }
}
