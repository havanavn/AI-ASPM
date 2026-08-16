package aspm.app.ui;

import aspm.app.assessment.AssessmentService;
import aspm.app.runtime.Dispatcher;
import aspm.app.runtime.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The JSON the React interface reads.
 *
 * <h2>What this is not</h2>
 *
 * <p>Not a public API. The operations under {@code /api/v1} are the contract of DOC-05 and are
 * versioned; these are the shape one screen happens to need and will change whenever that screen
 * does. They are separated so the two cannot be confused — an interface endpoint that drifted into
 * being a customer integration is how a UI refactor becomes a breaking change for somebody else.
 *
 * <h2>Authorization is where it always was</h2>
 *
 * <p>Every method here delegates to {@link AssessmentService}, which composes the caller's scope into
 * the query rather than filtering afterwards ({@code SEC-AUZ-016}), and re-reads any identifier that
 * arrived from the client before writing through it ({@code SEC-AUZ-017}). Moving the rendering into
 * a browser changes none of that, and it must not: the client asking for JSON instead of HTML is not
 * a reason for the server to answer a different question.
 *
 * <p>The one thing that DOES change is that a field the interface does not display is now a field
 * that still crosses the wire. So these representations are assembled explicitly, field by field, and
 * never by serializing a domain record — ADR-047 requires a restricted field to be ABSENT rather than
 * hidden, and "absent from the page" is not the same as "absent from the payload".
 */
public final class UiApi {

    private final DataSource dataSource;
    private final AssessmentService assessments;
    private final aspm.app.inventory.InventoryService inventory;
    private final aspm.app.resource.RequestTransition transitions;
    private final aspm.app.assessment.IntakeService intake;
    private final aspm.app.authz.ObjectAuthority authority;
    private final aspm.app.inventory.ApplicationPostureQuery posture;
    private final aspm.app.resource.DependencyQuery dependencies;
    private final aspm.app.identity.ServiceCredentialAdmin credentials;
    private final aspm.app.resource.WebhookAlerts alerts;
    private final aspm.app.resource.AssessmentPlanQuery plan;
    private final aspm.app.resource.ReviewPolicyService reviewPolicy;
    private final aspm.app.resource.AiProviderService aiProviders;
    private final aspm.app.resource.VulnerabilityQuery vulnerabilities;
    private final aspm.app.resource.SuggestionLedger suggestions;
    private final aspm.app.resource.TriageAgent agents;
    private final aspm.app.resource.FindingClassifier classifier;
    private final aspm.app.resource.FindingLifecycle lifecycle;
    /**
     * The highest number the review filter offers as an exact choice: 0 through 5.
     *
     * <p>Five is not a limit on the data. An application reviewed nine times is found by choosing 5 with
     * "at least" ticked — which is what that checkbox is for, and why the exact list does not need to
     * grow without end as an estate matures.
     */
    private static final int REVIEW_CHOICES = 5;

    private final aspm.app.resource.SubmissionHealth submissionHealth;

    public UiApi(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
        this.assessments = new AssessmentService(dataSource);
        this.inventory = new aspm.app.inventory.InventoryService(dataSource);
        this.transitions = new aspm.app.resource.RequestTransition(dataSource);
        this.intake = new aspm.app.assessment.IntakeService(dataSource);
        this.authority = new aspm.app.authz.ObjectAuthority(dataSource);
        this.posture = new aspm.app.inventory.ApplicationPostureQuery(dataSource);
        this.dependencies = new aspm.app.resource.DependencyQuery(dataSource);
        this.credentials = new aspm.app.identity.ServiceCredentialAdmin(dataSource);
        this.alerts = new aspm.app.resource.WebhookAlerts(dataSource);
        this.plan = new aspm.app.resource.AssessmentPlanQuery(dataSource);
        this.reviewPolicy = new aspm.app.resource.ReviewPolicyService(dataSource);
        this.aiProviders = new aspm.app.resource.AiProviderService(dataSource);
        this.vulnerabilities = new aspm.app.resource.VulnerabilityQuery(dataSource);
        this.suggestions = new aspm.app.resource.SuggestionLedger(dataSource);
        this.agents = new aspm.app.resource.TriageAgent(dataSource);
        this.classifier = new aspm.app.resource.FindingClassifier(dataSource);
        this.lifecycle = new aspm.app.resource.FindingLifecycle(dataSource);
        this.submissionHealth = new aspm.app.resource.SubmissionHealth(dataSource);
    }

    /**
     * {@code GET /api/ui/assessment-plan}. Planning the periodic assessment of applications.
     *
     * <p>Rows, bars and monthly load in one payload rather than three endpoints, because the Gantt is
     * useless without the row it belongs to and the page has no state in which it wants one and not
     * the other. Three round trips to draw one chart is three chances to render a half-consistent
     * picture of a moving estate.
     */
    public Dispatcher.Response assessmentPlan(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        int back = clamp(request.query().get("back"), 12, 1, 60);
        int ahead = clamp(request.query().get("ahead"), 12, 1, 60);
        // Multi-select, all three. An absent parameter is no filter; a present-but-empty one is
        // "nothing selected", which must match nothing rather than widen back to everything.
        var filter = new aspm.app.resource.AssessmentPlanQuery.Filter(
                uuidList(request.query().get("org")),
                uuidList(request.query().get("team")),
                uuidList(request.query().get("assessor")),
                "true".equals(request.query().get("unassigned")));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (var row : plan.rows(principal, filter)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("assetId", row.assetId());
            entry.put("name", row.name());
            entry.put("orgPath", row.orgPath());
            entry.put("criticality", row.criticality());
            entry.put("completed", row.completed());
            entry.put("inFlight", row.inFlight());
            entry.put("abandoned", row.abandoned());
            entry.put("lastReviewAt", row.lastReviewAt());
            entry.put("intervalMonths", row.intervalMonths());
            entry.put("nextDueAt", row.nextDueAt());
            entry.put("status", row.status());
            entry.put("openRequests", row.openRequests());
            entry.put("severeOpen", row.severeOpen());
            rows.add(entry);
        }
        List<Map<String, Object>> bars = new ArrayList<>();
        for (var bar : plan.bars(principal, filter, back, ahead)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("assetId", bar.assetId());
            entry.put("requestId", bar.requestId());
            entry.put("code", bar.code());
            entry.put("label", bar.label());
            entry.put("kind", bar.kind());
            entry.put("startAt", bar.startAt());
            entry.put("endAt", bar.endAt());
            entry.put("state", bar.state());
            entry.put("open", Boolean.valueOf(bar.open()));
            entry.put("overdue", Boolean.valueOf(bar.overdue()));
            entry.put("fullReview", Boolean.valueOf(bar.fullReview()));
            bars.add(entry);
        }
        List<Map<String, Object>> load = new ArrayList<>();
        for (var point : plan.load(principal, filter, back)) {
            load.add(Map.of("label", point.label(), "due", point.due(),
                    "started", point.started(), "closed", point.closed()));
        }
        List<Map<String, Object>> projects = new ArrayList<>();
        for (var project : plan.projects(principal, filter)) {
            projects.add(Map.of("assetId", project.assetId(), "projectId", project.projectId(),
                    "name", project.name()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rows", rows);
        body.put("bars", bars);
        body.put("load", load);
        body.put("projects", projects);
        // Null where the tenant has no single full-review trigger. Sent as-is rather than defaulted:
        // the button is hidden in that case, which is better than a button that raises the wrong
        // kind of request.
        // The picker's options, carried with the data so a filter can never offer a name that
        // returns nothing, and so the list is the same list the figures were computed over.
        List<Map<String, Object>> teams = new ArrayList<>();
        for (var option : plan.options(principal, filter, true)) {
            teams.add(Map.of("id", option.id(), "name", option.name(),
                    "requests", Long.valueOf(option.requests())));
        }
        List<Map<String, Object>> assessors = new ArrayList<>();
        for (var option : plan.options(principal, filter, false)) {
            assessors.add(Map.of("id", option.id(), "name", option.name(),
                    "requests", Long.valueOf(option.requests())));
        }
        body.put("teams", teams);
        body.put("assessors", assessors);
        body.put("unassignedRequests", Long.valueOf(plan.unassignedRequests(principal, filter)));
        body.put("fullReviewTriggerId", plan.fullReviewTriggerId(principal));
        body.put("mayManagePolicy", Boolean.valueOf(
                principal.holds(aspm.app.resource.ReviewPolicyService.MANAGE)));
        body.put("maySchedule", Boolean.valueOf(principal.holds("asm.request.create")));
        return json(body);
    }

    /**
     * {@code POST /api/ui/dependencies/artifact/{id}/sbom}. Submitting a bill of materials by hand.
     *
     * <h2>Why this is not just a call to the pipeline endpoint</h2>
     *
     * <p>{@code POST /api/v1/sbom-submissions} is class F — service ingest — and the dispatcher
     * requires a service principal for it. That is correct: it is the pipeline's door, and a browser
     * session is not a pipeline. So an interactive upload gets its own class B scoped write rather
     * than a relaxation of the class on the ingest route, which would have widened the pipeline door
     * for every caller in order to let one page through.
     *
     * <p>What it does NOT get is its own ingestion. The document goes through the same
     * {@link aspm.app.resource.SbomIngestion} the pipeline uses, so a hand-uploaded snapshot and a
     * pipeline-pushed one cannot come to mean different things — the same reason the scheduled
     * re-scan writes through the shared writer.
     *
     * <h2>The target is the asset, not a name triple</h2>
     *
     * <p>The pipeline names its target by application/project/repository because it knows those and
     * not the identifier the platform assigned. This page is the other way round: it is looking at a
     * row. So it sends the asset id and the name triple is resolved here — which also means an upload
     * from this page can never create an artifact by mistyping a name.
     */
    public Dispatcher.Response uploadArtifactSbom(Dispatcher.Request request) throws Exception {
        UUID id = uuid(request.pathVariables().get("id"));
        Map<String, Object> body = request.body().orElseThrow(
                () -> new IllegalArgumentException("a request body is required"));
        if (!(body.get("document") instanceof Map<?, ?> document)) {
            throw new IllegalArgumentException("document must be the SBOM object");
        }
        var target = dependencies.artifactTarget(request.principal(), id);
        if (target.isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> sbom = (Map<String, Object>) document;
        // Hashed as submitted, not as re-serialized by us — the same rule the pipeline path follows,
        // so an identical document uploaded here and pushed there is recognised as the same content.
        String raw = aspm.app.runtime.Json.write(sbom);
        Object outcome = new aspm.app.resource.SbomIngestion(dataSource)
                .submit(request.principal(), sbom, raw, target.orElseThrow());
        if (outcome instanceof aspm.app.resource.SbomIngestion.Rejection rejection) {
            return new Dispatcher.Response(422, Map.of("status", Integer.valueOf(422),
                    "code", rejection.code(), "message", rejection.detail()), Map.of());
        }
        var report = (aspm.app.resource.SbomIngestion.Report) outcome;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("snapshotId", report.snapshotId().toString());
        payload.put("componentCount", Integer.valueOf(report.componentCount()));
        payload.put("advisoriesRecorded", Integer.valueOf(report.advisoryCount()));
        payload.put("replacedSnapshotId", report.replacedSnapshotId());
        payload.put("qualityScore", Integer.valueOf(report.quality()));
        payload.put("warnings", report.warnings());
        return json(payload);
    }

    /**
     * {@code POST /api/ui/dependencies/artifact/{id}/retire}. Stops tracking one repository.
     *
     * <p>Named "retire" and not "delete" because that is what it does. The snapshots and the findings
     * against them survive — see {@code DependencyQuery#retireArtifact} for why deleting them would
     * be erasing the evidence for a weakness that was really present.
     */
    public Dispatcher.Response retireArtifact(Dispatcher.Request request) throws Exception {
        UUID id = uuid(request.pathVariables().get("id"));
        String reason = request.body().map(b -> String.valueOf(b.getOrDefault("reason", "")))
                .orElse("");
        return dependencies.retireArtifact(request.principal(), id, reason)
                ? json(Map.of("retired", Boolean.TRUE))
                : Dispatcher.Response.notFound();
    }

    /**
     * {@code GET /api/ui/vulnerabilities}. The finding population under one set of filters.
     *
     * <p>One payload: headline, five distributions, the trend, the picker options and the findings
     * themselves. One request rather than eight because every part is computed under the SAME filter,
     * and eight round trips is eight chances to render a headline that describes a different population
     * from the table below it — the disagreement a reader cannot diagnose from the screen.
     */
    public Dispatcher.Response vulnerabilities(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        Map<String, String> q = request.query();
        var flags = new java.util.LinkedHashSet<String>();
        for (String flag : List.of("CLAIMED", "ACCEPTED", "RECURRING", "OVERRIDDEN",
                "INTERNET_FACING", "UNVERIFIED_CLOSURE")) {
            if ("true".equals(q.get(flag.toLowerCase(java.util.Locale.ROOT)))) {
                flags.add(flag);
            }
        }
        var filter = findingFilter(request);
        int months = clamp(q.get("months"), 12, 1, 36);

        var summary = vulnerabilities.summary(principal, filter);
        Map<String, Object> head = new LinkedHashMap<>();
        head.put("total", summary.total());
        head.put("open", summary.open());
        head.put("closed", summary.closed());
        head.put("criticalOpen", summary.criticalOpen());
        head.put("highOpen", summary.highOpen());
        head.put("mediumOpen", summary.mediumOpen());
        head.put("lowOpen", summary.lowOpen());
        head.put("unratedOpen", summary.unratedOpen());
        head.put("seriousOpen", summary.seriousOpen());
        head.put("claimedOpen", summary.claimedOpen());
        head.put("acceptedOpen", summary.acceptedOpen());
        head.put("unassignedOpen", summary.unassignedOpen());
        head.put("recurring", summary.recurring());
        head.put("overridden", summary.overridden());
        head.put("internetFacingOpen", summary.internetFacingOpen());
        head.put("unverifiedClosures", summary.unverifiedClosures());
        head.put("openOver30", summary.openOver30());
        head.put("openOver90", summary.openOver90());
        head.put("openOver180", summary.openOver180());
        head.put("oldestOpenDays", summary.oldestOpenDays());
        head.put("medianOpenDays", summary.medianOpenDays());
        head.put("closedLast30", summary.closedLast30());
        head.put("closedLast90", summary.closedLast90());
        head.put("assetsAffected", summary.assetsAffected());

        List<Map<String, Object>> rows = new ArrayList<>();
        for (var row : vulnerabilities.rows(principal, filter)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", row.id());
            entry.put("title", row.title());
            entry.put("severity", row.severity());
            entry.put("reportedSeverity", row.reportedSeverity());
            entry.put("severityOrdinal", row.severityOrdinal());
            entry.put("state", row.state());
            entry.put("findingClass", row.findingClass());
            entry.put("sourceTool", row.sourceTool());
            entry.put("orgPath", row.orgPath());
            entry.put("assetName", row.assetName());
            entry.put("assetId", row.assetId());
            entry.put("assetCount", row.assetCount());
            entry.put("applicationName", row.applicationName());
            entry.put("projectName", row.projectName());
            entry.put("description", row.description());
            entry.put("assignee", row.assignee());
            entry.put("claimed", Boolean.valueOf(row.claimed()));
            entry.put("accepted", Boolean.valueOf(row.accepted()));
            entry.put("internetFacing", Boolean.valueOf(row.internetFacing()));
            entry.put("recurrence", row.recurrence());
            entry.put("firstDetectedAt", row.firstDetectedAt());
            entry.put("lastDetectedAt", row.lastDetectedAt());
            entry.put("closedAt", row.closedAt());
            entry.put("closureReason", row.closureReason());
            entry.put("closureVerified", Boolean.valueOf(row.closureVerified()));
            entry.put("ageDays", row.ageDays());
            entry.put("requestId", row.requestId());
            entry.put("requestCode", row.requestCode());
            rows.add(entry);
        }
        long matching = vulnerabilities.count(principal, filter);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("summary", head);
        body.put("rows", rows);
        body.put("matching", Long.valueOf(matching));
        // Said, not hidden. A table quietly showing the first five hundred of two thousand reads as a
        // complete list, and every count beside it would then look wrong for no visible reason.
        body.put("rowCap", Integer.valueOf(vulnerabilities.rowCap()));
        // The project/repository tree, only when asked for. Computed on request rather than always,
        // because the assessment dashboard has no use for it and a payload nobody reads is a query
        // nobody notices getting slower.
        if ("1".equals(request.query().get("tree"))) {
            List<Map<String, Object>> tree = new ArrayList<>();
            for (var node : vulnerabilities.projectTree(principal, filter)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("projectId", node.projectId());
                row.put("projectName", node.projectName());
                // The organization the project sits in, so the CI/CD table can say whose estate a
                // repository belongs to without the reader having to know the team names. Same
                // definition as the projects inventory — the topmost node above the owning team.
                row.put("orgId", node.orgId());
                row.put("orgName", node.orgName());
                row.put("repositoryId", node.repositoryId());
                row.put("repositoryName", node.repositoryName());
                row.put("open", Long.valueOf(node.open()));
                row.put("critical", Long.valueOf(node.critical()));
                row.put("serious", Long.valueOf(node.serious()));
                row.put("newestAt", node.newestAt());
                tree.add(row);
            }
            body.put("projectTree", tree);
            // The organizations the CI/CD filter may narrow to, at every level of the tree — read from
            // the caller's own scope and NOT from the rows above, which are already filtered. Deriving a
            // picker from filtered rows is how the projects inventory ended up with an Application picker
            // that collapsed to the single value already chosen; the same trap, avoided the same way.
            //
            // Emitted only in the tree branch, so the vulnerability dashboard — which has its own
            // organization multi-select fed from /api/ui/applications — does not pay for a query it
            // already has an answer for.
            body.put("organizations", organizationsAsTree(inventory.nodes(principal, false)));
        }
        body.put("truncated", Boolean.valueOf(matching > vulnerabilities.rowCap()));
        // "project" is here for the pipeline view, which ranks a delivery team's own repositories.
        // Same endpoint, same rows, same dedup — the pipeline dashboard is a FRAME over this, not a
        // second query, because a weakness found by a scanner and the same weakness found by hand must
        // land on one record or the recurrence count never moves (ADR-011).
        for (String dimension : List.of("severity", "class", "tool", "age", "org", "project")) {
            List<Map<String, Object>> buckets = new ArrayList<>();
            for (var bucket : vulnerabilities.distribution(principal, filter, dimension)) {
                // Built explicitly rather than with Map.of, which throws on a null value. A dimension
                // whose key can be absent — an unrecorded organization, a finding with no source tool —
                // used to crash the whole dashboard with a NullPointerException the response reported
                // only as INTERNAL_ERROR. Naming the absence is the job of the query; not exploding on
                // it is the job of this loop, and one should not depend on the other being perfect.
                Map<String, Object> bucketRow = new LinkedHashMap<>();
                bucketRow.put("key", bucket.key() == null ? "UNKNOWN" : bucket.key());
                bucketRow.put("label", bucket.label() != null ? bucket.label()
                        : bucket.key() != null ? bucket.key() : "Not recorded");
                bucketRow.put("open", Long.valueOf(bucket.open()));
                bucketRow.put("closed", Long.valueOf(bucket.closed()));
                bucketRow.put("serious", Long.valueOf(bucket.serious()));
                buckets.add(bucketRow);
            }
            body.put("by" + Character.toUpperCase(dimension.charAt(0)) + dimension.substring(1),
                    buckets);
        }
        List<Map<String, Object>> trend = new ArrayList<>();
        for (var point : vulnerabilities.trend(principal, filter, months)) {
            trend.add(Map.of("label", point.label(), "found", Long.valueOf(point.found()),
                    "closed", Long.valueOf(point.closed())));
        }
        body.put("trend", trend);
        for (String dimension : List.of("tool", "class", "assignee", "category", "owasp", "cwe")) {
            List<Map<String, Object>> options = new ArrayList<>();
            for (var option : vulnerabilities.options(principal, filter, dimension)) {
                options.add(Map.of("id", option.id(), "name", option.name(),
                        "findings", Long.valueOf(option.findings())));
            }
            body.put(dimension + "Options", options);
        }
        List<Map<String, Object>> severities = new ArrayList<>();
        for (var level : vulnerabilities.severityLevels(principal)) {
            severities.add(Map.of("id", level.id(), "name", level.name(),
                    "findings", Long.valueOf(level.findings())));
        }
        body.put("severityOptions", severities);
        body.put("unassignedFindings",
                Long.valueOf(vulnerabilities.unassignedCount(principal, filter)));
        return json(body);
    }

    /**
     * The finding filter, read from the query string.
     *
     * <p>ONE reader, used by the dashboard and by the export. Two readers would drift — and the drift
     * would be invisible in the worst way: a spreadsheet that does not contain what the screen said it
     * would, handed to somebody who was not looking at the screen.
     */
    private static aspm.app.resource.VulnerabilityQuery.Filter findingFilter(
            Dispatcher.Request request) {
        Map<String, String> q = request.query();
        var flags = new java.util.LinkedHashSet<String>();
        for (String flag : List.of("CLAIMED", "ACCEPTED", "RECURRING", "OVERRIDDEN",
                "INTERNET_FACING", "UNVERIFIED_CLOSURE")) {
            if ("true".equals(q.get(flag.toLowerCase(java.util.Locale.ROOT)))) {
                flags.add(flag);
            }
        }
        return new aspm.app.resource.VulnerabilityQuery.Filter(
                uuidList(q.get("org")), uuidList(q.get("severity")),
                textList(q.get("class")), textList(q.get("tool")),
                uuidList(q.get("assignee")), "true".equals(q.get("unassigned")),
                q.get("state"), flags, q.get("search"),
                isoDate(q.get("from")), isoDate(q.get("to")),
                textList(q.get("category")), textList(q.get("owasp")), textList(q.get("cwe")),
                "true".equals(q.get("unclassified")), contexts(q.get("context")),
                uuidList(q.get("project")), uuidList(q.get("repository")),
                "1".equals(q.get("fromPipeline")));
    }

    /**
     * A date from the query string, or null.
     *
     * <p>Validated in shape here rather than passed through to be cast in SQL: a malformed date reaching
     * {@code ::date} is a 500 on a dashboard, and a shared link with a truncated parameter is a normal
     * way for that to happen.
     */
    private static String isoDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return java.time.LocalDate.parse(value.strip()).toString();
        } catch (java.time.format.DateTimeParseException e) {
            // Ignored rather than fatal, for the same reason a malformed identifier is: a bad parameter
            // in a pasted link should narrow imperfectly, not present an error page.
            return null;
        }
    }

    /**
     * {@code GET /api/ui/suggestions}. The AI review queue, and what capabilities exist.
     *
     * <p>Scoped through the record each suggestion is about, never by a scope column of its own —
     * a suggestion is a claim about a finding, and its headline discloses that finding.
     */
    public Dispatcher.Response suggestions(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var s : suggestions.pending(principal, request.query().get("kind"),
                request.query().get("subject"), clamp(request.query().get("limit"), 50, 1, 200))) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", s.id());
            entry.put("kind", s.kind());
            entry.put("subjectKind", s.subjectKind());
            entry.put("subjectId", s.subjectId());
            entry.put("subjectLabel", s.subjectLabel());
            entry.put("headline", s.headline());
            entry.put("detail", s.detail());
            entry.put("recommendation", s.recommendation());
            // The records the sentence rests on. Always sent — a suggestion a reader cannot check is
            // an opinion, and the whole point of the ledger is that it never is one.
            entry.put("grounding", s.grounding());
            entry.put("modelIdentity", s.modelIdentity());
            entry.put("promptVersion", s.promptVersion());
            entry.put("confidenceBand", s.confidenceBand());
            entry.put("generatedAt", s.generatedAt());
            // CURRENT, STALE or UNKNOWN. Sent as the word rather than as a boolean, because a
            // boolean would have to fold "we did not record it" into one of the two answers and the
            // reviewer needs to see which of the three they are looking at.
            entry.put("freshness", s.freshness());
            rows.add(entry);
        }
        List<Map<String, Object>> capabilities = new ArrayList<>();
        for (var c : suggestions.capabilities(principal)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", c.code());
            row.put("suggestionKind", c.suggestionKind());
            row.put("subjectKind", c.subjectKind());
            row.put("surface", c.surface());
            row.put("dataCategory", c.dataCategory());
            row.put("enabled", Boolean.valueOf(c.enabled()));
            row.put("maxPerRun", Integer.valueOf(c.maxPerRun()));
            row.put("pending", Long.valueOf(c.pending()));
            // What this capability's output has been worth, which is the only basis on which the
            // switch above is a decision rather than a preference. Counts, never a percentage: a
            // rate over four decisions is a number that looks like evidence.
            row.put("promoted", Long.valueOf(c.promoted()));
            row.put("rejected", Long.valueOf(c.rejected()));
            row.put("withdrawn", Long.valueOf(c.withdrawn()));
            row.put("lastDecidedAt", c.lastDecidedAt() == null ? "" : c.lastDecidedAt());
            capabilities.add(row);
        }
        return json(Map.of("rows", rows, "capabilities", capabilities,
                "mayPromote", Boolean.valueOf(principal.holds(
                        aspm.app.resource.SuggestionLedger.PROMOTE)),
                "mayManage", Boolean.valueOf(principal.holds("aic.capability.manage"))));
    }

    /**
     * {@code POST /api/ui/suggestions/{id}/decide}. The human action ADR-005 is about.
     *
     * <p>Class B and restricted. Accepting marks that a named person accepted it — it does NOT apply
     * the change to the finding. That happens through the finding's own write path with its own
     * permission, because giving the ledger a route into a record would be a second way for AI output
     * to reach the system of record, which is the thing ADR-005 forbids.
     */
    public Dispatcher.Response decideSuggestion(Dispatcher.Request request) throws Exception {
        UUID id = uuid(request.pathVariables().get("id"));
        Map<String, Object> body = request.body().orElse(Map.of());
        boolean promote = !Boolean.FALSE.equals(body.get("promote"));
        String reason = body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        if (suggestions.decide(request.principal(), id, promote, reason)) {
            return json(Map.of("state", promote ? "PROMOTED" : "REJECTED"));
        }
        // A promotion that matched nothing has two causes and they need different answers. The
        // suggestion may be gone or out of scope — 404, absence rather than denial (PRD-API-036) —
        // or it may still be there and STALE, in which case 404 would send a reviewer looking for a
        // row they can see on the screen in front of them. The second case is a refusal with a
        // reason, and it names what to do instead: re-run the capability and judge the current one.
        if (promote && suggestions.isStale(request.principal(), id)) {
            return new Dispatcher.Response(409, Map.of("status", 409, "code", "SUGGESTION_STALE",
                    "message", "the record this suggestion is about has changed since it was "
                            + "generated, so accepting it would attribute a decision about a state "
                            + "that no longer exists. Run the capability again and judge the "
                            + "current suggestion; this one can still be dismissed."), Map.of());
        }
        return Dispatcher.Response.notFound();
    }

    /**
     * {@code POST /api/ui/agents/{code}/run}. Invokes one capability, explicitly.
     *
     * <p>Explicit is the point: {@code PRD-AIC-056} forbids invoking a capability on view, so nothing
     * here runs because somebody opened a page. A disabled capability is refused rather than run —
     * asking for a run is not consent to switch something on.
     */
    public Dispatcher.Response runAgent(Dispatcher.Request request) throws Exception {
        String code = request.pathVariables().get("code");
        var body = request.body().orElse(Map.of());
        if (body.get("enabled") instanceof Boolean enabled) {
            if (!suggestions.setEnabled(request.principal(), code, enabled.booleanValue())) {
                return Dispatcher.Response.notFound();
            }
            return json(Map.of("code", code, "enabled", enabled));
        }
        var run = agents.run(request.principal(), code);
        return json(Map.of("capability", run.capability(), "considered", run.considered(),
                "proposed", run.proposed(), "skipped", run.skipped(), "detail", run.detail()));
    }

    /**
     * {@code GET /api/ui/top-weaknesses}. The most common weaknesses, by month.
     *
     * <p>ONE endpoint for three surfaces. The overview asks with no asset, an application page asks
     * with its own id, a project page the same — and all three get the identical three tables. A
     * per-page variant would be three queries that agree today.
     *
     * <p>Three months when no window is given, because that is the requirement's default and because a
     * shorter window on a monthly cadence shows one column and no change.
     */
    public Dispatcher.Response topWeaknesses(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        Map<String, String> q = request.query();
        int months = clamp(q.get("months"), 3, 1, 24);
        UUID asset = uuid(q.get("asset"));
        var filter = new aspm.app.resource.VulnerabilityQuery.Filter(
                uuidList(q.get("org")), null, null, null, null, false, q.get("state"),
                java.util.Set.of(), null, isoDate(q.get("from")), isoDate(q.get("to")),
                null, null, null, false, contexts(q.get("context")), null, null, false);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("months", vulnerabilities.monthLabels(months));
        for (String dimension : List.of("category", "owasp", "cwe")) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (var row : vulnerabilities.topWeaknesses(principal, filter, dimension, months,
                    asset, 10)) {
                rows.add(Map.of("key", row.key(), "label",
                        row.label() == null ? row.key() : row.label(),
                        "total", Long.valueOf(row.total()), "months", row.months()));
            }
            body.put(dimension, rows);
        }
        return json(body);
    }

    /**
     * {@code POST /api/ui/findings/classify}. The "analyse with AI" button on the finding form.
     *
     * <p>Takes the words somebody has typed and answers with three proposed classifications. It writes
     * NOTHING — the answer goes into the form, the person reads it, edits it if they disagree, and
     * their submission is the write. That is what makes classification AI-assisted without AI ever
     * writing a finding (ADR-005), and it is why this is a POST that changes no state: the body carries
     * text too long and too private for a query string, not a mutation.
     *
     * <p>The three taxonomies each have a way of saying "not determined", and it is returned rather
     * than a guess. A fabricated CWE is wrong and looks authoritative, which is the worst combination
     * a field on a security record can have.
     */
    public Dispatcher.Response classifyFinding(Dispatcher.Request request) throws Exception {
        Map<String, Object> body = request.body().orElseThrow(
                () -> new IllegalArgumentException("a request body is required"));
        String title = body.get("title") == null ? "" : String.valueOf(body.get("title"));
        if (title.isBlank() && body.get("description") == null) {
            return new Dispatcher.Response(400, Map.of("status", 400, "code", "NOTHING_TO_READ",
                    "message", "type a title or a description first — there is nothing to classify"),
                    Map.of());
        }
        var p = classifier.classify(request.principal(), title,
                body.get("description") == null ? "" : String.valueOf(body.get("description")),
                body.get("findingClass") == null ? null : String.valueOf(body.get("findingClass")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("executiveRiskCategory", p.executiveRiskCategory());
        out.put("executiveRiskLabel", p.executiveRiskLabel());
        out.put("owaspTop10_2025", p.owaspCode());
        out.put("owaspName", p.owaspName());
        out.put("cweId", p.cweId());
        out.put("cweName", p.cweName());
        // Always sent. A proposal a person cannot interrogate is a proposal they either accept blindly
        // or ignore, and both are worse than reading one sentence about where it came from.
        out.put("basis", p.basis());
        out.put("confidence", p.confidence());
        out.put("source", "AI_ASSISTED");
        return json(out);
    }

    /**
     * {@code GET /api/ui/sbom-submission-health}. Whether each CI integration is actually working.
     *
     * <p>{@code PRD-SBM-024}, which ADR-023 names as the specific mitigation for the SBOM push endpoint
     * being a single point of failure for the whole SCA capability. Revoked and expired keys are
     * INCLUDED rather than filtered out: "the pipeline stopped submitting because somebody revoked its
     * key in August" is the answer to the question this page is opened with, and a filtered list makes
     * that pipeline simply vanish.
     */
    public Dispatcher.Response sbomSubmissionHealth(Dispatcher.Request request) throws Exception {
        var principal = request.principal();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var row : submissionHealth.rows(principal)) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", row.id());
            out.put("label", row.label());
            out.put("keyId", row.keyId());
            out.put("actsAs", row.actsAs());
            out.put("scope", row.scope());
            out.put("permissions", row.permissions());
            out.put("expiresAt", row.expiresAt());
            out.put("expired", Boolean.valueOf(row.expired()));
            out.put("revokedAt", row.revokedAt());
            out.put("lastUsedAt", row.lastUsedAt());
            out.put("lastSuccessAt", row.lastSuccessAt());
            out.put("lastFailureAt", row.lastFailureAt());
            out.put("lastFailureReason", row.lastFailureReason());
            out.put("successCount", Integer.valueOf(row.successCount()));
            out.put("failureCount", Integer.valueOf(row.failureCount()));
            out.put("consecutiveFailures", Integer.valueOf(row.consecutiveFailures()));
            // Sent explicitly rather than inferred client-side from two zero counters. "Nothing has been
            // measured" and "measured, nothing wrong" must not be one representation (PP-1).
            out.put("outcomeRecorded", Boolean.valueOf(row.outcomeRecorded()));
            out.put("daysSinceSuccess", row.daysSinceSuccess());
            out.put("verdict", row.verdict());
            out.put("advice", row.advice());
            rows.add(out);
        }
        var estate = submissionHealth.estate(principal);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("liveSubmitters", Integer.valueOf(estate.liveSubmitters()));
        summary.put("broken", Integer.valueOf(estate.broken()));
        summary.put("silent", Integer.valueOf(estate.silent()));
        summary.put("neverRecorded", Integer.valueOf(estate.neverRecorded()));
        summary.put("artifactsWithoutSbom", Integer.valueOf(estate.artifactsWithoutSbom()));
        summary.put("artifactsStale", Integer.valueOf(estate.artifactsStale()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rows", rows);
        body.put("summary", summary);
        return json(body);
    }

    /**
     * {@code GET /api/ui/findings/{id}}. One finding, without an assessment request.
     *
     * <p>The board's finding endpoint resolves through a request, because an assessment finding exists
     * because somebody raised a ticket. A pipeline finding does not: a scanner ran against a commit and
     * no ticket was ever involved. Sending a delivery team through the assessment board to read their
     * own scanner output is what makes the board feel diluted and makes the team feel supervised —
     * so this is the same record reached on its own terms.
     *
     * <p>Deliberately carries no workflow vocabulary and no triage controls. What may be DONE to the
     * finding still comes from {@code /api/ui/findings/{id}/lifecycle}, which reports each move with the
     * permission it needs — so a delivery engineer sees "report as fixed" and an assessor additionally
     * sees "verified — close it", from the same endpoint, decided by permission rather than by page.
     */
    public Dispatcher.Response findingDetail(Dispatcher.Request request) throws Exception {
        UUID id = uuid(request.pathVariables().get("id"));
        if (id == null) {
            return Dispatcher.Response.notFound();
        }
        var found = vulnerabilities.detail(request.principal(), id);
        if (found.isEmpty()) {
            // The same answer an unknown identifier gets. Distinguishing "exists but is not yours"
            // would turn this into a way to test whether a finding identifier is real.
            return Dispatcher.Response.notFound();
        }
        var d = found.orElseThrow();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", d.id());
        body.put("title", d.title());
        body.put("severity", d.severity());
        body.put("reportedSeverity", d.reportedSeverity());
        body.put("findingClass", d.findingClass());
        body.put("sourceTool", d.sourceTool());
        body.put("sourceToolVersion", d.sourceToolVersion());
        body.put("sourceRuleIdentity", d.sourceRuleIdentity());
        body.put("assessmentContext", d.assessmentContext());
        body.put("orgPath", d.orgPath());
        body.put("assetName", d.assetName());
        body.put("applicationName", d.applicationName());
        body.put("projectName", d.projectName());
        body.put("repositoryName", d.repositoryName());
        body.put("firstDetectedAt", d.firstDetectedAt());
        body.put("lastDetectedAt", d.lastDetectedAt());
        body.put("ageDays", d.ageDays());
        body.put("recurrence", Integer.valueOf(d.recurrence()));
        // Rendered by the server's restricted renderer, never by the interface. Finding text is
        // attacker-authored by design — it quotes payloads recovered from customer code — so the one
        // renderer that has been reviewed for that is the only one allowed near it.
        body.put("descriptionHtml", Markdown.render(d.description()));
        body.put("proofOfConceptHtml", Markdown.render(d.proofOfConcept()));
        body.put("executiveRiskCategory", d.executiveRiskCategory());
        body.put("owaspTop10_2025", d.owaspCode());
        body.put("cweId", d.cweId());
        body.put("classificationSource", d.classificationSource());
        // Present only when the finding came from assessment work. Null for a pipeline finding, and the
        // interface uses its absence to decide whether an assessment context exists to link to at all.
        body.put("requestId", d.requestId());
        return json(body);
    }

    /**
     * {@code GET /api/ui/findings/{id}/lifecycle}. Where the finding is, what may be done, and how it
     * got there.
     *
     * <p>Returns the moves with a {@code permitted} flag and, where false, the reason — rather than
     * omitting them. A button that is simply absent teaches nobody why: "you do not hold
     * vul.finding.verify" sends somebody to ask for the right thing, and "a leaked secret cannot be
     * accepted" is a rule worth stating every time it applies rather than hiding behind a missing
     * control. Neither leaks anything: the caller already reads this finding.
     */
    public Dispatcher.Response findingLifecycle(Dispatcher.Request request) throws Exception {
        UUID id = uuid(request.pathVariables().get("id"));
        var view = lifecycle.view(request.principal(), id);
        if (view == null) {
            return Dispatcher.Response.notFound();
        }
        List<Map<String, Object>> moves = new ArrayList<>();
        for (var m : view.moves()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("to", m.to());
            row.put("label", m.label());
            row.put("permission", m.permission());
            row.put("permitted", Boolean.valueOf(m.permitted()));
            row.put("reason", m.reason());
            row.put("needsDate", Boolean.valueOf(m.needsDate()));
            moves.add(row);
        }
        List<Map<String, Object>> history = new ArrayList<>();
        for (var h : view.history()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("from", h.from());
            row.put("fromLabel", aspm.app.resource.FindingLifecycle.label(h.from()));
            row.put("to", h.to());
            row.put("toLabel", aspm.app.resource.FindingLifecycle.label(h.to()));
            row.put("note", h.note());
            row.put("acceptedUntil", h.acceptedUntil());
            row.put("occurredAt", h.occurredAt());
            row.put("actor", h.actor());
            history.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("state", view.state());
        out.put("label", view.label());
        out.put("moves", moves);
        out.put("history", history);
        out.put("claimedAt", view.claimedAt());
        out.put("claimedBy", view.claimedBy());
        out.put("acceptedUntil", view.acceptedUntil());
        out.put("proposedUntil", view.proposedUntil());
        out.put("acceptedReason", view.acceptedReason());
        out.put("closureReason", view.closureReason());
        out.put("verifiedBy", view.verifiedBy());
        out.put("recurrenceCount", Integer.valueOf(view.recurrenceCount()));
        // Zero means an acceptance requested by this caller could never be approved (INV-VUL-26 forbids
        // approving your own). Reported so the page can say so BEFORE somebody asks, rather than after.
        out.put("otherApprovers", Integer.valueOf(view.otherApprovers()));
        return json(out);
    }

    /**
     * {@code POST /api/ui/findings/{id}/transition}. Moves a finding, or explains why it did not.
     *
     * <p>The registry declares {@code vul.finding.read} because that is what it takes to REACH this
     * operation; the authority to perform any given move is checked per transition inside the service —
     * claiming a fix, verifying one, and accepting a risk are three different permissions, and the
     * registry holds one. Stating the weaker gate here and enforcing the real one there is the honest
     * arrangement: the alternative is three near-identical endpoints, where the risk is that one of them
     * is later given the wrong permission and nothing reads as odd.
     *
     * <p>A refusal comes back as 409 with the sentence explaining it. A 403 would be wrong for "a
     * closed finding cannot be reported as fixed" — nothing about the caller is the problem — and the
     * distinction matters because one of those means "ask for access" and the other means "you have
     * misread the record".
     */
    public Dispatcher.Response transitionFinding(Dispatcher.Request request) throws Exception {
        UUID id = uuid(request.pathVariables().get("id"));
        Map<String, Object> body = request.body().orElse(Map.of());
        String to = body.get("to") == null ? null : String.valueOf(body.get("to"));
        String note = body.get("note") == null ? null : String.valueOf(body.get("note"));
        String until = body.get("until") == null ? null : String.valueOf(body.get("until"));
        try {
            String now = lifecycle.transition(request.principal(), id, to, note, until);
            return json(Map.of("state", now,
                    "label", aspm.app.resource.FindingLifecycle.label(now)));
        } catch (aspm.app.resource.FindingLifecycle.Refused refused) {
            return new Dispatcher.Response(409, Map.of("status", 409, "code", "TRANSITION_REFUSED",
                    "message", refused.getMessage()), Map.of());
        }
    }

    /**
     * {@code POST /api/ui/agents/analyse}. The per-dashboard "Analyse with AI" button.
     *
     * <p>Runs every ENABLED capability declared for the named surface. Two things about the shape:
     *
     * <ul>
     *   <li>It is a POST somebody pressed. Nothing analyses on view ({@code PRD-AIC-056}) — which is
     *       what keeps sending a tenant's data to a third party a decision rather than a consequence
     *       of opening a page.
     *   <li>Its permission is {@code aic.suggestion.promote}, NOT {@code aic.capability.manage}.
     *       Deciding that a capability may run at all is an administrator's call and stays theirs;
     *       asking an already-permitted capability to look at the screen in front of you belongs to
     *       whoever will act on the answer. Requiring the administrator permission meant a triager
     *       looking at a finding could not ask about it, which made the feature theirs in name only.
     * </ul>
     */
    public Dispatcher.Response analyseSurface(Dispatcher.Request request) throws Exception {
        Map<String, Object> body = request.body().orElse(Map.of());
        String surface = String.valueOf(body.getOrDefault("surface", ""));
        if (surface.isBlank()) {
            return new Dispatcher.Response(400, Map.of("status", 400, "code", "SURFACE_REQUIRED",
                    "message", "name the dashboard to analyse"), Map.of());
        }
        List<Map<String, Object>> runs = new ArrayList<>();
        int proposed = 0;
        for (var run : agents.runSurface(request.principal(), surface)) {
            proposed += run.proposed();
            runs.add(Map.of("capability", run.capability(),
                    "considered", Integer.valueOf(run.considered()),
                    "proposed", Integer.valueOf(run.proposed()),
                    "detail", run.detail()));
        }
        return json(Map.of("surface", surface, "runs", runs,
                "proposed", Integer.valueOf(proposed),
                // Said explicitly so a button that did nothing can explain why rather than looking
                // broken: the normal reason is that nothing on this surface is switched on yet.
                "ranNothing", Boolean.valueOf(runs.isEmpty())));
    }

    /**
     * {@code GET /api/ui/vulnerabilities/export}. The filtered finding list as a spreadsheet.
     *
     * <p>The SAME filter the dashboard read, through the same reader — so the file contains exactly the
     * population the screen said it would. It is deliberately NOT capped at the table's row limit: the
     * export is what makes that cap acceptable, and capping both would leave no way to get the whole
     * filtered set.
     *
     * <p>What the filter was is written into the file, on its own sheet. A spreadsheet detached from its
     * filters is a spreadsheet somebody will read as the whole estate three months from now — and the
     * only defence is that the file says what it is.
     */
    public Dispatcher.Response vulnerabilityExport(Dispatcher.Request request) throws Exception {
        var filter = findingFilter(request);
        var rows = vulnerabilities.exportRows(request.principal(), filter);
        List<List<String>> cells = new ArrayList<>();
        for (var row : rows) {
            cells.add(List.of(
                    nullToDash(row.title()),
                    nullToDash(row.severity()),
                    // Carried even when it agrees, because a spreadsheet is sorted and filtered by
                    // people who cannot hover a tooltip to find out what the tool originally said.
                    nullToDash(row.reportedSeverity()),
                    nullToDash(row.state()),
                    nullToDash(row.findingClass()),
                    nullToDash(row.sourceTool()),
                    nullToDash(row.orgPath()),
                    // Application and project before the asset: a spreadsheet is sorted and grouped,
                    // and these are the two columns people group by.
                    nullToDash(row.applicationName()),
                    nullToDash(row.projectName()),
                    nullToDash(row.assetName()),
                    row.assetCount() > 1 ? String.valueOf(row.assetCount()) : "1",
                    nullToDash(row.assignee()),
                    row.internetFacing() ? "yes" : "no",
                    row.claimed() ? "yes" : "no",
                    row.accepted() ? "yes" : "no",
                    row.recurrence() > 0 ? String.valueOf(row.recurrence()) : "0",
                    nullToDash(row.firstDetectedAt()),
                    nullToDash(row.lastDetectedAt()),
                    nullToDash(row.closedAt()),
                    nullToDash(row.closureReason()),
                    row.state().equals("OPEN") ? "" : (row.closureVerified() ? "yes" : "no"),
                    row.ageDays() == null ? "" : String.valueOf(row.ageDays()),
                    nullToDash(row.requestCode()),
                    // Last, because it is the long one. A description in an early column pushes every
                    // other field off the screen in Excel and people widen it once and give up.
                    nullToDash(row.description())));
        }
        var findings = new aspm.app.resource.Workbook.Sheet("Findings",
                List.of("Finding", "Severity", "Severity reported by tool", "State", "Kind",
                        "Found by", "Organization", "Application", "Project", "Asset",
                        "Assets affected", "Owner",
                        "Internet-facing", "Fix claimed", "Risk accepted", "Recurrences",
                        "First detected", "Last detected", "Closed", "Closure reason",
                        "Closure verified", "Age (days)", "Assessment", "Description"),
                cells);
        // Every identifier any filter mentions, resolved to a name in one lookup.
        List<UUID> mentioned = new ArrayList<>();
        for (List<UUID> list : List.of(
                filter.orgs() == null ? List.<UUID>of() : filter.orgs(),
                filter.severities() == null ? List.<UUID>of() : filter.severities(),
                filter.assignees() == null ? List.<UUID>of() : filter.assignees())) {
            mentioned.addAll(list);
        }
        var names = vulnerabilities.namesFor(request.principal(), mentioned);
        var about = new aspm.app.resource.Workbook.Sheet("Filter",
                List.of("Filter", "Value"), filterSheet(request, rows.size(), names));
        String stamp = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString();
        return new Dispatcher.Response(200,
                new aspm.app.ui.InterfaceResource.Binary(
                        aspm.app.resource.Workbook.write(List.of(findings, about))),
                Map.of("Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "Content-Disposition",
                        "attachment; filename=\"findings-" + stamp + ".xlsx\""));
    }

    /** What the export was filtered by, so the file can never be read as the whole estate. */
    private List<List<String>> filterSheet(Dispatcher.Request request, int exported,
            Map<String, String> names) {
        Map<String, String> q = request.query();
        List<List<String>> out = new ArrayList<>();
        out.add(List.of("Exported at (UTC)", java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
                .withNano(0).toString()));
        out.add(List.of("Findings in this file", String.valueOf(exported)));
        for (var entry : List.of(
                new String[] {"org", "Organizations"},
                new String[] {"severity", "Severities"},
                new String[] {"class", "Kinds"},
                new String[] {"tool", "Found by"},
                new String[] {"assignee", "Owners"},
                new String[] {"unassigned", "Include unowned"},
                new String[] {"state", "State"},
                new String[] {"from", "First detected from"},
                new String[] {"to", "First detected to"},
                new String[] {"search", "Text search"},
                new String[] {"internet_facing", "Internet-facing only"},
                new String[] {"claimed", "Fix claimed only"},
                new String[] {"accepted", "Risk accepted only"},
                new String[] {"overridden", "Severity overridden only"},
                new String[] {"recurring", "Recurred only"},
                new String[] {"unverified_closure", "Closed unverified only"})) {
            String value = q.get(entry[0]);
            // Absent filters are written as "any" rather than omitted. A missing row invites the reader
            // to assume a filter was applied that was not, and the whole point of this sheet is that
            // nobody has to assume.
            if (value == null || value.isBlank()) {
                out.add(List.of(entry[1], "any"));
                continue;
            }
            // Identifiers become names. An unresolved one keeps its identifier rather than being
            // dropped: "a severity we can no longer name" is still a filter that was applied.
            StringBuilder readable = new StringBuilder();
            for (String part : value.split(",")) {
                if (part.isBlank()) {
                    continue;
                }
                if (!readable.isEmpty()) {
                    readable.append(", ");
                }
                readable.append(names.getOrDefault(part.strip(), part.strip()));
            }
            out.add(List.of(entry[1], readable.isEmpty() ? value : readable.toString()));
        }
        return out;
    }

    private static String nullToDash(String value) {
        return value == null ? "" : value;
    }

    /** Reads a repeated or comma-separated list of plain strings from the query string. */
    /**
     * The assessment contexts a page is about, or null for "not specified".
     *
     * <p>Deliberately NOT {@link #textList}. For a picker an empty list means "nothing is selected", and
     * that must empty the page rather than quietly widening back to the whole estate. Context is not a
     * picker — it is which of the two worlds a dashboard is showing, set by the page and not cleared by a
     * person — so a blank value means the caller did not say, and the answer is everything. A named
     * context that does not exist still narrows to nothing, because that IS a caller saying something
     * wrong rather than saying nothing.
     */
    private static List<String> contexts(String value) {
        return value == null || value.isBlank() ? null : textList(value);
    }

    private static List<String> textList(String value) {
        if (value == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String part : value.split(",")) {
            if (!part.strip().isEmpty()) {
                out.add(part.strip());
            }
        }
        return out;
    }

    /**
     * {@code GET /api/ui/ai-providers}. The tenant's configured model providers.
     *
     * <p>Never carries any part of a key — not masked, absent (ADR-047). What it does carry is a
     * fingerprint, so somebody can tell one configuration from another, and the egress flag, because
     * whether a provider may read record content is the fact most worth seeing in a list.
     */
    public Dispatcher.Response aiProviders(Dispatcher.Request request) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var row : aiProviders.list(request.principal())) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", row.id());
            entry.put("label", row.label());
            entry.put("providerKind", row.providerKind());
            entry.put("baseUrl", row.baseUrl());
            entry.put("model", row.model());
            entry.put("keyFingerprint", row.keyFingerprint());
            entry.put("sendRecordContent", Boolean.valueOf(row.sendRecordContent()));
            entry.put("active", Boolean.valueOf(row.active()));
            entry.put("sealed", Boolean.valueOf(row.sealed()));
            entry.put("keyReference", row.keyReference());
            entry.put("lastTestedAt", row.lastTestedAt());
            entry.put("lastTestStatus", row.lastTestStatus());
            entry.put("lastTestDetail", row.lastTestDetail());
            entry.put("updatedAt", row.updatedAt());
            out.add(entry);
        }
        return json(Map.of("rows", out,
                "mayManage", Boolean.valueOf(request.principal().holds(
                        aspm.app.resource.AiProviderService.MANAGE)),
                // So the form can say why it cannot accept a key, instead of refusing on submit.
                "custodyAvailable", Boolean.valueOf(aiProviders.custodyAvailable())));
    }

    /**
     * {@code POST /api/ui/ai-providers}. Configures a provider.
     *
     * <p>Class E: it accepts a live third-party credential AND decides what may leave the platform.
     * Either alone would put it here.
     */
    public Dispatcher.Response createAiProvider(Dispatcher.Request request) throws Exception {
        Map<String, Object> body = request.body().orElseThrow(
                () -> new IllegalArgumentException("a request body is required"));
        Object outcome = aiProviders.create(request.principal(),
                text(body.get("label")), text(body.get("providerKind")), text(body.get("baseUrl")),
                text(body.get("model")), text(body.get("apiKey")), text(body.get("keyReference")),
                Boolean.TRUE.equals(body.get("sendRecordContent")));
        if (outcome instanceof aspm.app.resource.AiProviderService.Rejection rejection) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("status", Integer.valueOf(422));
            payload.put("code", rejection.code());
            payload.put("message", rejection.detail());
            if (rejection.field() != null) {
                payload.put("field", rejection.field());
            }
            return new Dispatcher.Response(422, payload, Map.of());
        }
        // The id, and nothing else. There is no "show the key once" here as there is for an issued
        // service credential, because the platform did not generate this key — the person pasting it
        // already has it, and echoing it back would put a live third-party credential in a response
        // body, a browser cache and whatever logs the response.
        return new Dispatcher.Response(201, Map.of("id", String.valueOf(outcome)), Map.of());
    }

    /** {@code POST /api/ui/ai-providers/{id}/active}. Turns one on or off; never deletes it. */
    public Dispatcher.Response setAiProviderActive(Dispatcher.Request request) throws Exception {
        UUID id = uuid(request.pathVariables().get("id"));
        boolean active = request.body().map(b -> !Boolean.FALSE.equals(b.get("active")))
                .orElse(Boolean.TRUE);
        return aiProviders.setActive(request.principal(), id, active)
                ? json(Map.of("active", Boolean.valueOf(active)))
                : Dispatcher.Response.notFound();
    }

    /** {@code GET /api/ui/review-policy}. Every criticality tier, with its review interval. */
    public Dispatcher.Response reviewPolicy(Dispatcher.Request request) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var tier : reviewPolicy.tiers(request.principal())) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("tierId", tier.tierId());
            entry.put("code", tier.code());
            entry.put("ordinal", tier.ordinal());
            entry.put("intervalMonths", tier.intervalMonths());
            entry.put("warnDaysBefore", tier.warnDaysBefore());
            entry.put("applications", tier.applications());
            entry.put("updatedAt", tier.updatedAt());
            out.add(entry);
        }
        return json(Map.of("rows", out, "mayManage", Boolean.valueOf(
                request.principal().holds(aspm.app.resource.ReviewPolicyService.MANAGE))));
    }

    /**
     * {@code PUT /api/ui/review-policy/{id}}. Sets or clears one tier's review interval.
     *
     * <p>Class E, restricted, step-up. This does not schedule work — it decides how long every
     * application on the tier may go unassessed, and because the next-due date is derived rather than
     * stored, widening it makes part of the estate stop being overdue the moment it is saved.
     */
    public Dispatcher.Response setReviewPolicy(Dispatcher.Request request) throws Exception {
        UUID tier = uuid(request.pathVariables().get("id"));
        Map<String, Object> body = request.body().orElseThrow(
                () -> new IllegalArgumentException("a request body is required"));
        // An absent interval and an explicit null mean the same thing here — no obligation — but the
        // client always sends the key, so a dropped field cannot silently clear a tenant's policy.
        Integer months = body.get("intervalMonths") instanceof Number number
                ? Integer.valueOf(number.intValue()) : null;
        Integer warn = body.get("warnDaysBefore") instanceof Number number
                ? Integer.valueOf(number.intValue()) : null;
        return reviewPolicy.set(request.principal(), tier, months, warn)
                ? json(Map.of("tierId", String.valueOf(tier), "intervalMonths",
                        months == null ? "" : months))
                : Dispatcher.Response.notFound();
    }

    /**
     * Reads a repeated or comma-separated list of identifiers from the query string.
     *
     * <p>Returns {@code null} for an ABSENT parameter and an EMPTY list for a present-but-empty one,
     * and the difference decides what the caller sees: no filter, or a filter that matches nothing. A
     * picker whose last selection was removed sends the second, and collapsing the two would widen the
     * plan back to the whole estate at the moment somebody meant to narrow it to nothing.
     */
    private static List<UUID> uuidList(String value) {
        if (value == null) {
            return null;
        }
        List<UUID> out = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                out.add(UUID.fromString(trimmed));
            } catch (IllegalArgumentException e) {
                // Skipped rather than fatal. A malformed identifier in a shared link should narrow
                // imperfectly, not present an error page for a dashboard.
            }
        }
        return out;
    }

    /** Reads a bounded integer from the query string. Out-of-range is clamped, never rejected. */
    private static int clamp(String value, int fallback, int low, int high) {
        try {
            return Math.max(low, Math.min(high, Integer.parseInt(value)));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    // ==============================================================================================
    // GET /api/ui/session
    // ==============================================================================================

    /**
     * Who the caller is, and what they may reach.
     *
     * <p>The navigation is built HERE, from the operation registry filtered by the caller's
     * permissions — the same source the server-rendered navigation uses. It is not a permission list
     * the client is trusted to interpret: a menu assembled in the browser is a menu whose entries an
     * attacker adds, and every one of those entries would then be a request the server has to refuse
     * individually rather than a page that was never offered.
     */
    /**
     * {@code GET /api/ui/guide}. The user guide, rendered.
     *
     * <h2>Why the interface fetches HTML rather than Markdown</h2>
     *
     * <p>The document is rendered by {@link Markdown}, which escapes the source before it introduces any
     * markup and permits only a closed set of elements. Rendering it in the browser instead would be a
     * SECOND renderer, and the two disagreeing is the cross-site scripting this product exists to find in
     * other people's software. The interface displays what this one produced and never parses prose.
     *
     * <h2>Why this exists at all</h2>
     *
     * <p>The guide was already written — five hundred lines in two languages — and served at
     * {@code GET /guide} by the server-rendered tier. <b>Nobody could reach it.</b> The sidebar entry is a
     * client-side link, the React router has no {@code /guide} route, so clicking "How to use this"
     * matched the catch-all and rendered an empty page: 333 characters, all of them sidebar. The content
     * was never missing; the door was. Measured by clicking it, which is the only way that class of
     * defect is ever found.
     */
    public Dispatcher.Response guide(Dispatcher.Request request) {
        Messages messages = InterfaceResource.messagesFor(request);
        String source = GuidePage.load(messages.locale());
        Map<String, Object> body = new LinkedHashMap<>();
        // Said, not blank. PP-9: an empty article reads as "there is nothing to say", which for a help
        // page is a claim about the product rather than a failure to load a file.
        body.put("html", source.isBlank() ? "" : Markdown.render(source));
        body.put("locale", messages.locale() == null ? "en" : messages.locale().getLanguage());
        return json(body);
    }

    /**
     * {@code GET /api/ui/api-guide}. The integration guide, half written and half generated.
     *
     * <h2>The generated half cannot drift</h2>
     *
     * <p>The operation table comes from {@link aspm.app.api.PlatformOperations#registry()} — the same
     * registry the dispatcher enforces and refuses to start without. So this page cannot document an
     * endpoint that does not exist, cannot omit one that does, and cannot state a permission or an
     * annotation class that differs from the one enforced. Hand-written API documentation is wrong the
     * first time somebody adds an operation and nobody notices for a year.
     *
     * <p>What each annotation class REQUIRES is read off the class rather than restated: authentication,
     * step-up, an idempotency key, scope re-validation and the audit level are properties of
     * {@link aspm.app.api.AnnotationClass}, and repeating them in prose would create a second copy that goes stale.
     *
     * <h2>What is deliberately not listed</h2>
     *
     * <p>The interface's own {@code /api/ui} operations are marked as such and reported separately. They
     * exist to serve these screens and change with them; naming them as an integration surface would
     * invite somebody to build on shapes that are not promised to hold.
     */
    public Dispatcher.Response apiGuide(Dispatcher.Request request) {
        Messages messages = InterfaceResource.messagesFor(request);
        String source = GuidePage.loadApi(messages.locale());

        List<Map<String, Object>> operations = new ArrayList<>();
        for (var operation : aspm.app.api.PlatformOperations.registry().all()) {
            String path = operation.pathTemplate();
            // Only what a caller could integrate against. The interface shell, its assets and the
            // retired prefixes are routes, not operations somebody calls on purpose.
            if (!path.startsWith("/api/")) {
                continue;
            }
            var annotation = operation.annotationClass();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("method", operation.method());
            entry.put("path", path);
            entry.put("surface", path.startsWith("/api/v1") ? "v1" : "interface");
            entry.put("annotationClass", annotation.name());
            entry.put("permission", operation.requiredPermission().orElse(null));
            entry.put("authentication", annotation.authentication().name());
            entry.put("scopeRevalidation", annotation.scopeRevalidation().name());
            entry.put("requiresStepUp", Boolean.valueOf(annotation.requiresStepUp()));
            entry.put("requiresIdempotencyKey", Boolean.valueOf(annotation.requiresIdempotencyKey()));
            entry.put("invokableByHumanSession", Boolean.valueOf(annotation.invokableByHumanSession()));
            entry.put("classification", annotation.classification().name());
            entry.put("rateClass", annotation.rateClass().name());
            // *** THE FIELDS, GENERATED. ***
            //
            // Without them the table says a caller may POST to /api/v1/assets and not what to put in
            // the body, which is the point at which a newcomer stops reading and starts guessing. The
            // filterable set comes from the operation, the writable sets from the resource group, and
            // both are the ones the request validator enforces — a hand-written list beside them would
            // be wrong the first time a column moved.
            entry.put("filterable", new java.util.TreeSet<>(operation.filterableFields()));
            entry.put("restricted", new java.util.TreeSet<>(operation.restrictedFields()));
            aspm.app.resource.ResourceGroup group = groupFor(path);
            if (group != null) {
                entry.put("writableOnCreate", new java.util.TreeSet<>(group.writableOnCreate()));
                entry.put("writableOnUpdate", new java.util.TreeSet<>(group.writableOnUpdate()));
            }
            operations.add(entry);
        }
        operations.sort(java.util.Comparator
                .comparing((Map<String, Object> e) -> String.valueOf(e.get("surface")))
                .thenComparing(e -> String.valueOf(e.get("path")))
                .thenComparing(e -> String.valueOf(e.get("method"))));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("html", source.isBlank() ? "" : Markdown.render(source));
        body.put("operations", operations);
        body.put("locale", messages.locale() == null ? "en" : messages.locale().getLanguage());
        return json(body);
    }

    /**
     * The resource group a {@code /api/v1} path belongs to, or null where it is a bespoke endpoint.
     *
     * <p>Matched on the segment after the version, because that is how the composition root registers
     * them — one group, two paths, {@code /api/v1/<name>} and {@code /api/v1/<name>/{id}}.
     */
    private static aspm.app.resource.ResourceGroup groupFor(String path) {
        String[] segments = path.split("/");
        if (segments.length < 4) {
            return null;
        }
        for (var group : aspm.app.resource.ResourceCatalogue.all()) {
            if (group.name().equals(segments[3])) {
                return group;
            }
        }
        return null;
    }

    public Dispatcher.Response session(Dispatcher.Request request) {
        Principal principal = request.principal();
        if (principal == null) {
            return new Dispatcher.Response(401, Map.of("code", "UNAUTHENTICATED"), Map.of());
        }
        Messages messages = InterfaceResource.messagesFor(request);

        List<Map<String, String>> nav = new ArrayList<>();
        for (NavEntry entry : NAV) {
            if (entry.permission() == null || principal.holds(entry.permission())) {
                nav.add(Map.of("href", entry.href(), "labelKey", entry.labelKey(),
                        "label", messages.getOr(entry.labelKey(), entry.href())));
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        // *** THE PERSON'S NAME, NOT THEIR IDENTIFIER. ***
        //
        // This was the raw principal UUID, printed in the sidebar of every page. It is the first
        // thing anybody sees on signing in and it told them nothing — an identifier is for the
        // machine, and putting one where a name belongs is the interface telling a person they are
        // a row in a table.
        Map<String, String> who = identity(principal);
        body.put("displayName", who.getOrDefault("displayName",
                principal.principalId().toString()));
        body.put("username", who.get("username"));
        // The organizations they can reach, BY NAME. It used to be "2 nodes", which is a fact about
        // the data model rather than about the reader's work — PRD-UIX-011 wants the current scope
        // legible because "a user uncertain which slice they are viewing will misread every figure
        // on the page", and a count does not resolve that uncertainty at all.
        body.put("scopeLabel", scopeLabel(principal));
        // The permission list is for DISABLING controls, never for deciding what exists. Every one is
        // re-checked at the operation; this only stops the interface offering a button that will fail.
        body.put("permissions", List.copyOf(principal.permissions()));
        body.put("nav", nav);
        return json(body);
    }

    /**
     * The caller's own name and username.
     *
     * <p>One small query per page load, and worth it. The alternative was to carry the name in the
     * session cookie, which is cached authorization-adjacent state that a rename does not reach —
     * somebody would change their display name and see the old one until they signed out.
     */
    private Map<String, String> identity(Principal principal) {
        Map<String, String> out = new LinkedHashMap<>();
        try (java.sql.Connection connection =
                aspm.app.persistence.TenantConnections.open(dataSource, principal)) {
            try (java.sql.PreparedStatement statement = connection.prepareStatement(
                    "SELECT display_name, username FROM principal WHERE id = ?")) {
                statement.setObject(1, principal.principalId());
                try (java.sql.ResultSet r = statement.executeQuery()) {
                    if (r.next()) {
                        out.put("displayName", r.getString(1));
                        out.put("username", r.getString(2));
                    }
                }
            }
        } catch (java.sql.SQLException e) {
            // The sidebar is not worth failing a page for. The identifier is the fallback, which is
            // what this used to show unconditionally.
            System.getLogger("aspm.ui").log(System.Logger.Level.DEBUG, "identity lookup failed");
        }
        return out;
    }

    /**
     * The organizations the caller can reach, named.
     *
     * <p>Names, capped at three with a remainder — a person scoped to twenty nodes gets a sidebar
     * they cannot read if every one is listed, and a bare count is what this replaced. The cap keeps
     * the label legible while the remainder keeps it honest about there being more.
     *
     * <p>Roots only. A subtree grant reaches every descendant, so listing all of them would print
     * the whole tree; the node the grant names is the one a person recognises as "where I work".
     */
    private String scopeLabel(Principal principal) {
        if (principal.scopeNodeIds().isEmpty()) {
            return null;
        }
        List<String> names = new ArrayList<>();
        try (java.sql.Connection connection =
                aspm.app.persistence.TenantConnections.open(dataSource, principal)) {
            try (java.sql.PreparedStatement statement = connection.prepareStatement(
                    "SELECT name FROM org_node WHERE id = ANY (?) ORDER BY name")) {
                statement.setArray(1, connection.createArrayOf("uuid",
                        principal.scopeNodeIds().toArray(new UUID[0])));
                try (java.sql.ResultSet r = statement.executeQuery()) {
                    while (r.next()) {
                        names.add(r.getString(1));
                    }
                }
            }
        } catch (java.sql.SQLException e) {
            return null;
        }
        if (names.isEmpty()) {
            return null;
        }
        if (names.size() <= 3) {
            return String.join(", ", names);
        }
        return String.join(", ", names.subList(0, 3)) + " +" + (names.size() - 3);
    }

    /**
     * One navigation entry.
     *
     * <p>{@code href} is the path the React router owns, not the server-rendered one. It used to be
     * the {@code /…} path with the client stripping the prefix, which worked only while the two
     * interfaces agreed on every path — and they stopped agreeing the moment a screen was rebuilt
     * under a different name. The client rewriting a server-supplied path is a second opinion about
     * routing; there is one opinion now, and it is here.
     *
     * <p>A {@code null} permission means the entry is always offered. Only the account panel uses it:
     * it is authorized by identity rather than by a catalogue permission, so requiring one would hide
     * a person's own profile from them — including from the roleless principal the bootstrap creates.
     */
    private record NavEntry(String href, String labelKey, String permission) {
    }

    /** Interface routes and the permission each needs, in navigation order. */
    private static final List<NavEntry> NAV = List.of(
            new NavEntry("/overview", "nav.overview", "vul.finding.read"),
            new NavEntry("/board", "nav.board", "asm.request.read"),
            // Beside the board, because the board is the work and this is the weaknesses that work
            // is about. Placed before the asset pages: somebody arriving to triage opens findings,
            // not inventory.
            new NavEntry("/vulnerabilities", "nav.vulnerabilities", "vul.finding.read"),
            // The pipeline view, immediately after the assessment one, because they are the same
            // subject seen by two audiences and the pairing is the point. Same permission: it is the
            // same findings narrowed to automated-scan context, so a caller who may read findings may
            // read these. Their SCOPE still decides which — a project-scoped principal sees their own.
            new NavEntry("/pipeline", "nav.pipeline", "vul.finding.read"),
            new NavEntry("/applications", "nav.applications", "ast.asset.read"),
            new NavEntry("/projects", "nav.projects", "ast.asset.read"),
            new NavEntry("/organization", "nav.organization", "org.node.read"),
            // Not converted; the router hands this one off to the server-rendered page.
            //
            // *** THIS CARRIED sbm.component.read, WHICH IS NOT IN THE CATALOGUE. ***
            //
            // role_permission references permission_catalogue, so no role could hold that code and no
            // principal could ever be offered this entry — the section was invisible to everybody
            // including a tenant administrator holding every permission that exists. The permission is
            // the one the page behind it actually requires: /components is registered on
            // ApplicationPages.READ, and a navigation entry naming a different permission from the
            // operation it links to is either a dead link or an offer that fails on arrival.
            //
            // The server-rendered sidebar cannot make this mistake because Page.NavItem derives the
            // permission from the operation registry. This list declares it, and a declared copy is
            // the copy that drifts — InterfaceTest now asserts every entry here names a permission
            // some registered operation requires.
            new NavEntry("/workload", "nav.workload", "cap.team.read"),
            // Planning sits beside the workload it consumes, not under applications: the question is
            // "what is owed across the estate and when", which is a schedule, not a property of any
            // one application.
            new NavEntry("/planning", "nav.planning", ApplicationPages.READ),
            new NavEntry("/composition", "nav.composition", "sbm.coverage.read"),
            // Configuration, separate from Access on the same reasoning that separates /access from
            // /roles: "who may use the platform" and "how the platform behaves" are different jobs
            // held by different people, and one screen serving both is how somebody looking up a
            // colleague's grants ends up editing an egress destination.
            new NavEntry("/settings", "nav.settings", aspm.app.resource.AiProviderService.MANAGE),
            new NavEntry("/access", "nav.access", AdminPages.READ_USERS),
            // Separate from /access on purpose. Reading who holds what and composing what a role may
            // do are different jobs held by different people, and one screen serving both is how a
            // person looking up a colleague's grants ends up on a permission grid.
            new NavEntry("/roles", "nav.roles", AdminPages.MANAGE_ROLES),
            new NavEntry("/account", "nav.account", null),
            // The guide, last and unconditional. No permission for the reason recorded on GuidePage:
            // a person holding no role at all is exactly the reader who needs it, and gating it on a
            // catalogue permission would withhold the explanation from whoever is most confused. The
            // React router has no /guide route, so HandOff sends it to /guide — which is where the
            // page is, rather than a second copy of it.
            new NavEntry("/guide", "nav.guide", null),
            // Beside the user guide rather than under Configuration: it is documentation, and the
            // person who needs it is whoever is wiring a pipeline — not necessarily an administrator.
            new NavEntry("/api-guide", "nav.apiGuide", null));

    /**
     * Every permission the interface navigation names, for the test that asserts each one is a
     * permission some registered operation actually requires.
     *
     * <p>Returns the codes rather than the entries so {@link NavEntry} stays private: the test needs
     * the strings, and widening a type's visibility to satisfy a test makes the type part of an API it
     * was never meant to be part of.
     */
    static java.util.List<String> navigationPermissions() {
        return NAV.stream().map(NavEntry::permission).filter(java.util.Objects::nonNull).toList();
    }

    /**
     * The permission declared for one interface href, for the server-rendered sidebar to reuse.
     *
     * <p>{@code Optional.empty()} where the entry is offered unconditionally; {@code null} where this
     * table has no entry for the href at all, which is how the caller distinguishes "always visible"
     * from "not a navigable page".
     *
     * <p><b>Why the server-rendered sidebar has to ask.</b> It derived every permission from the
     * operation registry, which worked while each sidebar target was a server-rendered page with its own
     * registered operation. Those pages are React routes now, served by one class G shell, so the
     * registry no longer knows what {@code /roles} requires — it knows what the shell requires, which is
     * nothing. Asking this table keeps ONE answer for both sidebars rather than adding a second
     * declaration next to the server-rendered one.
     */
    static Optional<String> navigationPermission(String href) {
        for (NavEntry entry : NAV) {
            if (entry.href().equals(href)) {
                return entry.permission() == null ? Optional.empty() : Optional.of(entry.permission());
            }
        }
        return null;
    }

    // ==============================================================================================
    // GET /api/ui/board
    // ==============================================================================================

    public Dispatcher.Response board(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        Map<String, String> filters = new LinkedHashMap<>();
        for (String name : List.of("state", "node", "only", "trigger", "category",
                "project", "application", "assessor", "due",
                "dueFrom", "dueTo", "createdFrom", "createdTo")) {
            String value = request.query().get(name);
            // A PRESENT but empty parameter is forwarded, because "selected nothing" is a real filter
            // and must match nothing. Dropping it here would turn an emptied multi-select back into
            // the unfiltered board — see the note on AssessmentService#list.
            if (value != null) {
                filters.put(name, value);
            }
        }
        List<AssessmentService.Request> rows = assessments.board(principal, filters,
                request.query().getOrDefault("q", ""), request.query().getOrDefault("sort", "due"));

        Set<UUID> people = new LinkedHashSet<>();
        for (AssessmentService.Request row : rows) {
            if (row.contactId() != null) {
                people.add(row.contactId());
            }
            if (row.leadId() != null) {
                people.add(row.leadId());
            }
        }
        Map<UUID, String> names = assessments.principalNames(principal, people);
        Map<String, String> stateLabels = assessments.stateLabels(principal);

        List<UUID> ids = rows.stream().map(AssessmentService.Request::id).toList();
        Map<UUID, List<Map<String, Object>>> projects = assessments.requestProjects(principal, ids);
        // Derived from the scope the requester declared, not from the assessment's resolved scope.
        // The view's own column is empty until an assessor exists — 5 of 211 rows on the demo board —
        // which made the Application column look broken beside a filter that found those very rows.
        Map<UUID, List<String>> applications = assessments.requestApplications(principal, ids);

        List<Map<String, Object>> out = new ArrayList<>();
        for (AssessmentService.Request row : rows) {
            out.add(row(row, names, stateLabels, projects.getOrDefault(row.id(), List.of()),
                    applications.getOrDefault(row.id(), List.of())));
        }

        long overdue = rows.stream().filter(AssessmentService.Request::overdue).count();
        long unassigned = rows.stream()
                .filter(r -> r.leadId() == null && !r.terminal()).count();
        long open = rows.stream().mapToLong(AssessmentService.Request::findingOpen).sum();

        List<Map<String, Object>> triggers = new ArrayList<>();
        for (AssessmentService.Trigger trigger : assessments.triggers(principal)) {
            // The id as well as the code. The board filters by code, but the intake form has to
            // SUBMIT a trigger, and a list with no identifier made that Select unsettable.
            triggers.add(Map.of("id", trigger.id().toString(), "code", trigger.code(),
                    "label", trigger.label(),
                    "countsAsFullReview", trigger.countsAsFullReview()));
        }
        List<Map<String, Object>> states = new ArrayList<>();
        stateLabels.forEach((code, label) -> states.add(Map.of("code", code, "label", label)));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rows", out);
        body.put("states", states);
        body.put("triggers", triggers);
        // The filter option lists, derived from what the caller can REACH rather than from the rows
        // currently on screen. Options taken from the visible page cannot be used to widen a
        // selection: narrow to one organization and the rest disappear from the picker, leaving no way
        // back except clearing everything. They are also the same scoped queries authorization uses,
        // so the picker and the control cannot drift apart (product principle 4).
        body.putAll(assessments.boardFilterOptions(principal));
        body.put("totals", Map.of("requests", rows.size(), "overdue", overdue,
                "unassigned", unassigned, "openFindings", open));
        return json(body);
    }

    // ==============================================================================================
    // GET /api/ui/board/{id}
    // ==============================================================================================

    public Dispatcher.Response request(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        if (id == null) {
            return Dispatcher.Response.notFound();
        }
        Optional<AssessmentService.Request> found = assessments.request(principal, id);
        if (found.isEmpty()) {
            // The same 404 a non-existent request gets. A 403 here would confirm that an
            // out-of-scope request exists, which is the disclosure PRD-WRK-031 orders the checks to
            // avoid — and the ordering is worth nothing if the interface tier undoes it.
            return Dispatcher.Response.notFound();
        }
        AssessmentService.Request row = found.orElseThrow();
        Map<UUID, String> names = assessments.principalNames(principal,
                java.util.stream.Stream.of(row.contactId(), row.leadId())
                        .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet()));
        Map<String, String> stateLabels = assessments.stateLabels(principal);

        List<Map<String, Object>> moves = new ArrayList<>();
        for (var move : transitions.available(principal, id)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("event", move.event());
            entry.put("toState", move.toState());
            entry.put("toStateLabel", move.toStateLabel());
            entry.put("permitted", move.permitted());
            entry.put("reasonRequired", move.reasonRequired());
            entry.put("blockedReason", move.blockedReason().orElse(null));
            entry.put("closes", move.closes());
            moves.add(entry);
        }

        List<Map<String, Object>> findings = new ArrayList<>();
        for (AssessmentService.Finding finding : assessments.findings(principal, id)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", finding.id().toString());
            entry.put("title", finding.title());
            entry.put("severity", finding.severity());
            entry.put("state", finding.state());
            entry.put("closureReason", finding.closureReason());
            entry.put("context", finding.assessmentContext());
            entry.put("firstDetectedAt", finding.firstDetectedAt());
            entry.put("acceptedUntil", finding.acceptedUntil());
            entry.put("remediationClaimedAt", finding.remediationClaimedAt());
            entry.put("remediationClaimedBy", finding.remediationClaimedBy());
            // description and proofOfConcept are NOT here. They are attacker-influenced prose that
            // the server renders through the restricted Markdown renderer; shipping the source to a
            // browser that would have to render it itself moves that decision to the client.
            findings.add(entry);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("row", row(row, names, stateLabels,
                assessments.requestProjects(principal, List.of(id)).getOrDefault(id, List.of())));
        body.put("moves", moves);
        body.put("findings", findings);
        body.put("people", assessments.assignableprincipals(principal));
        List<Map<String, Object>> triggers = new ArrayList<>();
        for (AssessmentService.Trigger trigger : assessments.triggers(principal)) {
            triggers.add(Map.of("id", trigger.id().toString(), "code", trigger.code(),
                    "label", trigger.label(),
                    "countsAsFullReview", trigger.countsAsFullReview()));
        }
        body.put("triggers", triggers);
        body.put("triggerId", row.triggerId() == null ? null : row.triggerId().toString());
        body.put("contactId", row.contactId() == null ? null : row.contactId().toString());
        body.put("assessorId", row.leadId() == null ? null : row.leadId().toString());
        body.put("comments", comments(principal, "ASSESSMENT_REQUEST", id));
        body.put("mayAct", principal != null && principal.holds(RequestPages.TRIAGE));
        return json(body);
    }

    // ==============================================================================================
    // POST /api/ui/board/{id}/transitions
    // ==============================================================================================

    public Dispatcher.Response transition(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        if (id == null || assessments.request(principal, id).isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        // body(), not rawForm(). The dispatcher parses a JSON body into body() and leaves rawForm()
        // empty for anything that is not form-encoded, so reading rawForm here saw "{}" on every
        // request and every move was refused as "name the move to apply".
        Map<String, Object> payload = request.body().orElse(Map.of());
        String event = String.valueOf(payload.getOrDefault("event", ""));
        if (event.isBlank()) {
            return new Dispatcher.Response(400,
                    Map.of("code", "EVENT_REQUIRED", "message", "name the move to apply"), Map.of());
        }
        Object reason = payload.get("reason");
        var outcome = transitions.apply(principal, id, event,
                reason == null ? Optional.<String>empty() : Optional.of(String.valueOf(reason)));
        if (outcome instanceof aspm.app.resource.RequestTransition.Outcome.Applied applied) {
            return json(Map.of("state", applied.toState(), "alreadyInState", applied.alreadyInState()));
        }
        if (outcome instanceof aspm.app.resource.RequestTransition.Outcome.Invalid invalid) {
            return new Dispatcher.Response(409, Map.of("code", "STATE_TRANSITION_INVALID",
                    "message", "'" + invalid.event() + "' is not available from "
                            + invalid.currentState()), Map.of());
        }
        var denied = (aspm.app.resource.RequestTransition.Outcome.Denied) outcome;
        return new Dispatcher.Response(422,
                Map.of("code", denied.code(), "message", denied.detail()), Map.of());
    }

    // ==============================================================================================
    // Assignment, the reason, and comments
    // ==============================================================================================

    /** {@code POST /api/ui/board/{id}/assign}. */
    public Dispatcher.Response assign(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        if (id == null || assessments.request(principal, id).isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        Map<String, Object> payload = request.body().orElse(Map.of());
        java.time.LocalDate due = null;
        String raw = text(payload.get("due"));
        if (raw != null && !raw.isBlank()) {
            try {
                due = java.time.LocalDate.parse(raw);
            } catch (java.time.format.DateTimeParseException e) {
                return new Dispatcher.Response(400,
                        Map.of("code", "DATE_INVALID", "message", "the due date is not a date"),
                        Map.of());
            }
        }
        // The identifiers arrive from a client and are re-validated by the write itself, which only
        // accepts a principal the caller can already see (SEC-AUZ-017). The interface picks from a
        // list this same caller was given; that is a convenience, never the control.
        assessments.assignRequest(principal, id, uuid(text(payload.get("contact"))),
                uuid(text(payload.get("assessor"))), due);
        UUID trigger = uuid(text(payload.get("trigger")));
        if (trigger != null) {
            assessments.setTrigger(principal, id, trigger);
        }
        return json(Map.of("saved", true));
    }

    /** {@code GET /api/ui/board/{id}/findings/{findingId}}. */
    public Dispatcher.Response finding(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        UUID findingId = uuid(request.pathVariables().get("findingId"));
        if (id == null || findingId == null) {
            return Dispatcher.Response.notFound();
        }
        Optional<AssessmentService.Finding> found = assessments.finding(principal, id, findingId);
        if (found.isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        AssessmentService.Finding finding = found.orElseThrow();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", finding.id().toString());
        body.put("requestId", id.toString());
        body.put("title", finding.title());
        body.put("severity", finding.severity());
        body.put("state", finding.state());
        body.put("closureReason", finding.closureReason());
        body.put("context", finding.assessmentContext());
        body.put("firstDetectedAt", finding.firstDetectedAt());
        body.put("lastDetectedAt", finding.lastDetectedAt());
        body.put("acceptedUntil", finding.acceptedUntil());
        body.put("assetName", finding.assetName());
        body.put("sourceTool", finding.sourceTool());
        body.put("rowVersion", finding.rowVersion());
        // The prose, as MARKDOWN SOURCE. This is the one place the editor needs it, and it is the
        // reason the editor stores Markdown rather than HTML: the source is the same text the server
        // renders, so nothing here decides what markup exists.
        body.put("description", finding.description());
        body.put("proofOfConcept", finding.proofOfConcept());
        // …and the rendered form beside it, produced by the server's restricted renderer. The
        // interface DISPLAYS this and edits the source above; it never renders the source itself.
        body.put("descriptionHtml", Markdown.render(finding.description()));
        body.put("proofOfConceptHtml", Markdown.render(finding.proofOfConcept()));
        body.put("severities", assessments.severities(principal));
        body.put("contexts", AssessmentService.CONTEXTS);
        body.put("comments", comments(principal, "FINDING", findingId));
        body.put("mayAct", principal.holds(RequestPages.TRIAGE));
        // The retest queue, as three fields rather than a state. A claim is not a closure (V032).
        body.put("remediationClaimedAt", finding.remediationClaimedAt());
        body.put("remediationClaimedBy", finding.remediationClaimedBy());
        body.put("remediationNote", finding.remediationNote());
        body.put("mayClaimRemediation", authority.mayClaimRemediation(principal, id));
        // The three classifications, current values and the pickers. Sent with the finding rather than
        // fetched on demand: the editor needs all of it the moment it opens, and a second request would
        // let somebody start typing before the options arrived.
        var current = classifier.current(principal, findingId);
        Map<String, Object> classification = new LinkedHashMap<>();
        classification.put("executiveRiskCategory", current.category());
        classification.put("owaspTop10_2025", current.owasp());
        classification.put("cweId", current.cwe());
        classification.put("source", current.source());
        classification.put("basis", current.basis());
        body.put("classification", classification);
        body.put("riskCategories", taxonomy(classifier.categories(principal)));
        body.put("owaspCategories", taxonomy(classifier.owasp(principal)));
        body.put("cwes", taxonomy(classifier.cwes(principal)));
        return json(body);
    }

    private static List<Map<String, Object>> taxonomy(
            List<aspm.app.resource.FindingClassifier.Option> from) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var option : from) {
            out.add(Map.of("code", option.code(), "label", option.label(),
                    "hint", option.hint() == null ? "" : option.hint()));
        }
        return out;
    }

    /** {@code POST /api/ui/board/{id}/findings/{findingId}} — save the write-up. */
    public Dispatcher.Response saveFinding(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        UUID findingId = uuid(request.pathVariables().get("findingId"));
        if (id == null || findingId == null
                || assessments.finding(principal, id, findingId).isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        Map<String, Object> payload = request.body().orElse(Map.of());
        boolean saved = assessments.updateFinding(principal, findingId,
                text(payload.get("title")), uuid(text(payload.get("severity"))),
                text(payload.get("context")), text(payload.get("description")),
                text(payload.get("proofOfConcept")),
                payload.get("rowVersion") instanceof Number version ? version.intValue() : -1);
        if (!saved) {
            // Optimistic concurrency. Somebody else saved between this editor opening and this save,
            // and overwriting them silently is how a proof of concept disappears.
            return new Dispatcher.Response(409, Map.of("code", "STALE",
                    "message", "this finding changed since you opened it; reload and reapply"),
                    Map.of());
        }
        // The classification, where the editor sent one. Applied after the optimistic-concurrency
        // check passed, so a stale save cannot half-succeed by rewriting the classification of a
        // finding whose write-up it failed to update.
        //
        // Unlike the create form this does NOT refuse an empty value: 658 findings predate these
        // fields, and a required classification on the edit path would make every one of them
        // unsavable — somebody fixing a typo in a two-year-old write-up would be told to classify it
        // first. The gate belongs where records enter, not where they are corrected.
        String category = text(payload.get("executiveRiskCategory"));
        String owasp = text(payload.get("owaspTop10_2025"));
        String cwe = text(payload.get("cweId"));
        if (category != null && !category.isBlank() && owasp != null && !owasp.isBlank()
                && cwe != null && !cwe.isBlank()) {
            classifier.apply(principal, findingId, category, owasp, cwe,
                    "AI_ASSISTED".equals(text(payload.get("classificationSource")))
                            ? "AI_ASSISTED" : "ASSESSOR",
                    text(payload.get("classificationBasis")));
        }
        return json(Map.of("saved", true));
    }

    /** {@code POST /api/ui/board/{id}/comments} and the finding-scoped form of it. */
    public Dispatcher.Response comment(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        UUID findingId = uuid(request.pathVariables().get("findingId"));
        if (id == null || assessments.request(principal, id).isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        String kind = "ASSESSMENT_REQUEST";
        UUID subject = id;
        if (request.pathVariables().containsKey("findingId")) {
            if (findingId == null || assessments.finding(principal, id, findingId).isEmpty()) {
                return Dispatcher.Response.notFound();
            }
            kind = "FINDING";
            subject = findingId;
        }
        String body = text(request.body().orElse(Map.of()).get("body"));
        if (body == null || body.isBlank()) {
            return new Dispatcher.Response(400,
                    Map.of("code", "EMPTY", "message", "a comment needs a body"), Map.of());
        }
        assessments.addComment(principal, kind, subject, body);
        return json(Map.of("comments", comments(principal, kind, subject)));
    }

    /** {@code GET /api/ui/board/{id}/comments}. */
    public Dispatcher.Response requestComments(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        if (id == null || assessments.request(principal, id).isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        return json(Map.of("comments", comments(principal, "ASSESSMENT_REQUEST", id)));
    }

    /**
     * Comments, rendered.
     *
     * <p>The rendered HTML is what crosses the wire, not the Markdown source. A comment body is
     * arbitrary text somebody typed, and the decision about what markup it becomes belongs to
     * {@link Markdown} on the server — the one place that decides it for every surface. An interface
     * that rendered the source itself would be a second renderer that can disagree with the first,
     * and the way it disagrees is cross-site scripting.
     */
    private List<Map<String, Object>> comments(Principal principal, String kind, UUID subject)
            throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AssessmentService.Comment comment : assessments.comments(principal, kind, subject)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", comment.id().toString());
            entry.put("author", comment.authorName());
            entry.put("createdAt", comment.createdAt());
            entry.put("editCount", comment.editCount());
            entry.put("redacted", comment.redacted());
            entry.put("html", comment.redacted() ? null : Markdown.render(comment.body()));
            out.add(entry);
        }
        return out;
    }

    /** {@code GET /api/ui/people} — who may be named on a request. */
    public Dispatcher.Response people(Dispatcher.Request request) throws Exception {
        return json(Map.of("people", assessments.assignableprincipals(request.principal())));
    }

    // ==============================================================================================
    // The application inventory
    // ==============================================================================================

    /** {@code GET /api/ui/applications}. */
    public Dispatcher.Response applications(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        Map<String, String> filters = new LinkedHashMap<>();
        for (String name : List.of("node", "criticality", "exposure", "lifecycle", "type")) {
            String value = request.query().get(name);
            if (value != null && !value.isBlank()) {
                filters.put(name, value);
            }
        }
        List<aspm.app.inventory.InventoryService.Application> rows = inventory.applications(
                principal, filters, request.query().getOrDefault("q", ""),
                request.query().getOrDefault("sort", "name"),
                "desc".equals(request.query().get("dir")));

        List<UUID> ids = rows.stream()
                .map(aspm.app.inventory.InventoryService.Application::id).toList();
        Map<UUID, AssessmentService.Cadence> cadence = assessments.cadences(principal, ids);
        // How many projects each application is delivered as. One batched query, and the type code
        // comes from the constant the projects page selects on rather than from a literal here — a
        // deployment that has registered no PROJECT type simply gets zeros (ADR-027).
        Map<UUID, Long> projects = posture.partCounts(principal, ids,
                aspm.app.inventory.ProjectQuery.PROJECT_TYPE);

        // How many COMPLETED full reviews each application has had, bucketed for the filter.
        //
        // Counted over every application the caller can reach, BEFORE this filter narrows anything, so
        // choosing "never reviewed" does not zero the other options and leave no way back. The same rule
        // the other pickers on this page follow.
        //
        // `completed` is the count of full reviews that were CARRIED OUT. A request that was raised as a
        // full review and never executed does not count, which is why this estate reads mostly zero
        // despite the requests on the board — the obligation is met by doing the review, not by asking
        // for one, and a filter that conflated them would report coverage nobody performed (PP-1).
        // TWO count maps, because the number beside an option has to mean what the option will do.
        // With "at least" off, "2" selects applications reviewed exactly twice; with it on, twice or
        // more. One set of counts would be right in one mode and a lie in the other, and the lie is
        // the kind nobody checks — it looks like a filter simply found nothing.
        java.util.Map<String, Integer> exact = new LinkedHashMap<>();
        java.util.Map<String, Integer> atLeastCounts = new LinkedHashMap<>();
        for (int n = 0; n <= REVIEW_CHOICES; n++) {
            exact.put(String.valueOf(n), Integer.valueOf(0));
            atLeastCounts.put(String.valueOf(n), Integer.valueOf(0));
        }
        for (var app : rows) {
            var c = cadence.get(app.id());
            long completed = c == null ? 0L : c.completed();
            for (int n = 0; n <= REVIEW_CHOICES; n++) {
                String key = String.valueOf(n);
                exact.merge(key, completed == n ? 1 : 0, Integer::sum);
                atLeastCounts.merge(key, completed >= n ? 1 : 0, Integer::sum);
            }
        }

        // The filter itself. Applied here rather than inside the inventory query because cadence lives
        // in the assessment module and arrives through its own service — joining the view into the asset
        // query would reach across a module boundary that the surrounding code deliberately respects
        // (ADR-003). The list is not server-paged, so narrowing it here loses nothing.
        String reviews = request.query().get("reviews");
        boolean atLeast = "1".equals(request.query().get("atLeast"))
                || "true".equals(request.query().get("atLeast"));
        // Parsed once, and an unparseable value is remembered as such rather than treated as absent. A
        // filter that quietly stops filtering is how somebody reads a whole estate as a subset of it.
        Integer wanted = null;
        boolean malformed = false;
        if (reviews != null && !reviews.isBlank()) {
            try {
                wanted = Integer.valueOf(reviews.strip());
                if (wanted.intValue() < 0) {
                    malformed = true;
                }
            } catch (NumberFormatException e) {
                malformed = true;
            }
        }
        final Integer target = wanted;
        final boolean refuseEverything = malformed;
        java.util.function.Predicate<aspm.app.inventory.InventoryService.Application> byReviews =
                app -> {
                    if (refuseEverything) {
                        return false;
                    }
                    if (target == null) {
                        return true;
                    }
                    var c = cadence.get(app.id());
                    long completed = c == null ? 0L : c.completed();
                    return atLeast ? completed >= target.intValue() : completed == target.intValue();
                };
        List<aspm.app.inventory.InventoryService.Application> shown =
                rows.stream().filter(byReviews).toList();

        List<Map<String, Object>> out = new ArrayList<>();
        for (var app : shown) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", app.id().toString());
            entry.put("name", app.name());
            entry.put("lifecycleState", app.lifecycleState());
            entry.put("owningNodeName", app.owningNodeName());
            entry.put("owningNodeTypeCode", app.owningNodeTypeCode());
            entry.put("ancestorNames", app.ancestorNames());
            entry.put("exposureDeclared", app.exposureDeclared());
            entry.put("exposureObserved", app.exposureObserved());
            entry.put("exposureConflict", app.exposureConflict());
            entry.put("criticalityCode", app.criticalityCode());
            entry.put("criticalityInherited", app.criticalityInherited());
            entry.put("userBase", app.userBase());
            // PRD-UIX-022: an unmeasured value has no numeral form. null here, and the interface
            // renders the word — never a zero, which reads as "scored, and it scored nothing".
            entry.put("riskValue", app.riskValue());
            entry.put("riskBand", app.riskBand());
            entry.put("riskCoverage", app.riskCoverage());
            entry.put("findingCount", app.findingCount());
            entry.put("requestCount", app.requestCount());
            entry.put("projectCount", projects.getOrDefault(app.id(), 0L));
            entry.put("cadence", cadence(cadence.get(app.id())));
            out.add(entry);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rows", out);
        body.put("nodes", nodes(inventory.nodes(principal, true)));
        body.put("criticalities", inventory.tiers(principal).stream()
                .map(t -> Map.of("code", t.code(), "ordinal", t.ordinal())).toList());
        // The totals count each finding and each request ONCE, rather than summing the column above
        // it. Two applications legitimately share a service, so its findings roll into both — right
        // per row, and wrong the moment they are added. See ApplicationPostureQuery#estateTotals.
        // Totals over what is SHOWN, so the headline agrees with the table beneath it. A total that
        // ignored the active filter would be the same number reported twice with two meanings.
        var totals = posture.estateTotals(principal,
                shown.stream().map(aspm.app.inventory.InventoryService.Application::id).toList());
        body.put("totals", Map.of("applications", shown.size(), "findings", totals.findings(),
                "requests", totals.requests()));
        // The option counts, so the picker shows the distribution instead of hiding it. "Never
        // reviewed (16)" is the sentence somebody needs; an empty option they have to select to
        // discover is not.
        // Both maps travel, and the interface shows whichever matches the toggle's current position.
        body.put("reviewCounts", Map.of("exact", exact, "atLeast", atLeastCounts));
        body.put("reviewChoices", Integer.valueOf(REVIEW_CHOICES));
        body.put("mayWrite", principal.holds("ast.asset.update"));
        return json(body);
    }

    /** {@code GET /api/ui/applications/{id}}. */
    public Dispatcher.Response application(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        if (id == null) {
            return Dispatcher.Response.notFound();
        }
        var found = inventory.application(principal, id);
        if (found.isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        var app = found.orElseThrow();

        List<Map<String, Object>> components = new ArrayList<>();
        for (var component : inventory.components(principal, id)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", component.id().toString());
            entry.put("name", component.name());
            entry.put("typeCode", component.typeCode());
            entry.put("depth", component.depth());
            entry.put("path", component.path());
            entry.put("edgeType", component.edgeType());
            entry.put("lifecycleState", component.lifecycleState());
            entry.put("exposure", component.exposure());
            entry.put("attributes", component.attributes());
            entry.put("findingOpen", component.findingOpen());
            entry.put("findingTotal", component.findingTotal());
            entry.put("criticalOpen", component.criticalOpen());
            entry.put("highOpen", component.highOpen());
            entry.put("mediumOpen", component.mediumOpen());
            entry.put("scaOpen", component.scaOpen());
            entry.put("acceptedTotal", component.acceptedTotal());
            components.add(entry);
        }

        List<Map<String, Object>> reviews = new ArrayList<>();
        for (AssessmentService.FullReview review : assessments.fullReviews(principal, id)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("requestId", review.requestId().toString());
            entry.put("code", review.code());
            entry.put("title", review.title());
            entry.put("state", review.state());
            entry.put("triggerLabel", review.triggerLabel());
            entry.put("startedAt", review.startedAt());
            entry.put("startedAtIsIntakeDate", review.startedAtIsIntakeDate());
            entry.put("closedAt", review.closedAt());
            entry.put("abandoned", review.abandoned());
            entry.put("open", review.open());
            reviews.add(entry);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", app.id().toString());
        body.put("name", app.name());
        body.put("lifecycleState", app.lifecycleState());
        body.put("owningNodeName", app.owningNodeName());
        body.put("ancestorNames", app.ancestorNames());
        body.put("criticalityCode", app.criticalityCode());
        body.put("criticalityInherited", app.criticalityInherited());
        body.put("exposureDeclared", app.exposureDeclared());
        body.put("exposureObserved", app.exposureObserved());
        body.put("exposureConflict", app.exposureConflict());
        body.put("userBase", app.userBase());
        body.put("description", app.description());
        body.put("attributes", app.attributes());
        body.put("riskValue", app.riskValue());
        body.put("riskBand", app.riskBand());
        body.put("riskCoverage", app.riskCoverage());
        body.put("findingCount", app.findingCount());
        body.put("requestCount", app.requestCount());
        body.put("components", components);
        body.put("reviews", reviews);
        body.put("cadence", cadence(assessments.cadence(principal, id).orElse(null)));
        body.put("requests", inventory.requestsFor(principal, id));
        return json(body);
    }

    /**
     * {@code GET /api/ui/applications/{id}/posture} and {@code GET /api/ui/projects/{id}/posture}.
     *
     * <p>The security posture of one asset: what is open, how old it is, where inside it the findings
     * sit, how they are being found, how long they take to fix, and what has never been looked at.
     * Separated from the detail payload rather than folded into it, because that payload is read on
     * every navigation to the page and these are eleven aggregate queries — a profile card that got
     * slower every time somebody added a chart would be the wrong trade.
     *
     * <p><b>One handler for both routes, because it is one question.</b> ADR-009 keeps a single
     * {@code Asset} aggregate with a type registry, and every view this reads —
     * {@code application_posture}, {@code application_finding}, {@code application_request} — has a
     * row for every asset rooted at itself, a project included. Two handlers computing the same
     * rollup for two asset types is the second inventory that decision exists to prevent, and they
     * would drift. The two routes exist rather than one so that each carries its own entry in the
     * operation registry and can be authorized separately if that ever has to differ.
     *
     * <p><b>Authorized on the application, then unscoped within it.</b> The application is re-read
     * through {@link aspm.app.inventory.InventoryService#application}, which composes the caller's
     * scope into the query, and a caller who cannot reach it gets 404 before any figure is computed
     * ({@code SEC-AUZ-017}: an identifier that arrived from the client is re-read before it is used).
     * The rollups are then over the whole application, for the reasons set out on
     * {@link aspm.app.inventory.ApplicationPostureQuery}.
     */
    public Dispatcher.Response assetPosture(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        if (id == null) {
            return Dispatcher.Response.notFound();
        }
        // The scope decision, made once and made here. Everything below is keyed on an identifier
        // this call has already established the caller may read.
        if (inventory.application(principal, id).isEmpty()) {
            return Dispatcher.Response.notFound();
        }

        Map<String, Object> body = new LinkedHashMap<>();
        var head = posture.posture(principal, id);
        if (head.isEmpty()) {
            // The asset exists — the scope check above passed — but no posture row does. That is a
            // real state and not an error: application_posture derives from the composition walk,
            // and a row can be absent while the view is being rebuilt. Reporting it as such beats
            // rendering zeros, which would claim the application is clean.
            body.put("measured", false);
            return json(body);
        }
        var p = head.orElseThrow();
        body.put("measured", true);
        Map<String, Object> headline = new LinkedHashMap<>();
        headline.put("componentCount", p.componentCount());
        headline.put("findingTotal", p.findingTotal());
        headline.put("findingOpen", p.findingOpen());
        headline.put("findingAccepted", p.findingAccepted());
        headline.put("criticalOpen", p.criticalOpen());
        headline.put("criticalTotal", p.criticalTotal());
        headline.put("highOpen", p.highOpen());
        headline.put("highTotal", p.highTotal());
        headline.put("mediumOpen", p.mediumOpen());
        headline.put("lowOpen", p.lowOpen());
        headline.put("scaOpen", p.scaOpen());
        headline.put("scaTotal", p.scaTotal());
        headline.put("openOver30Days", p.openOver30Days());
        headline.put("openOver90Days", p.openOver90Days());
        headline.put("openOver180Days", p.openOver180Days());
        headline.put("closedLast90Days", p.closedLast90Days());
        // Nulls travel. PRD-UIX-022: the interface has to be able to tell "no finding has ever
        // closed here" from "they close the same day", and a zero cannot carry that.
        headline.put("meanDaysToClose", p.meanDaysToClose());
        headline.put("openOldestDays", p.openOldestDays());
        headline.put("remediationClaimedOpen", p.remediationClaimedOpen());
        headline.put("requestTotal", p.requestTotal());
        headline.put("requestOpen", p.requestOpen());
        headline.put("lastDetectedAt", p.lastDetectedAt());
        headline.put("lastRequestClosedAt", p.lastRequestClosedAt());
        headline.put("sbomCoveredParts", p.sbomCoveredParts());
        headline.put("sbomLatestAt", p.sbomLatestAt());
        headline.put("sbomRejectedParts", p.sbomRejectedParts());
        body.put("headline", headline);

        List<Map<String, Object>> severities = new ArrayList<>();
        for (var s : posture.severities(principal, id)) {
            severities.add(Map.of("code", s.code(), "ordinal", s.ordinal(), "total", s.total(),
                    "open", s.open(), "openOver90Days", s.openOver90Days()));
        }
        body.put("severities", severities);

        List<Map<String, Object>> trend = new ArrayList<>();
        for (var m : posture.trend(principal, id, 12)) {
            trend.add(Map.of("label", m.label(), "opened", m.opened(), "closed", m.closed()));
        }
        body.put("trend", trend);

        List<Map<String, Object>> ages = new ArrayList<>();
        for (var a : posture.ageBands(principal, id)) {
            ages.add(Map.of("label", a.label(), "critical", a.critical(), "high", a.high(),
                    "medium", a.medium(), "low", a.low(), "unrated", a.unrated()));
        }
        body.put("ageBands", ages);

        List<Map<String, Object>> parts = new ArrayList<>();
        for (var part : posture.parts(principal, id)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", part.assetId());
            entry.put("name", part.name());
            entry.put("typeCode", part.typeCode());
            entry.put("depth", part.depth());
            entry.put("open", part.open());
            entry.put("criticalOpen", part.criticalOpen());
            entry.put("highOpen", part.highOpen());
            entry.put("total", part.total());
            entry.put("lastDetectedAt", part.lastDetectedAt());
            parts.add(entry);
        }
        body.put("parts", parts);

        body.put("classes", postureSlices(posture.classes(principal, id)));
        body.put("contexts", postureSlices(posture.contexts(principal, id)));
        body.put("closures", postureSlices(posture.closures(principal, id)));
        body.put("tools", postureSlices(posture.tools(principal, id)));

        List<Map<String, Object>> remediation = new ArrayList<>();
        for (var r : posture.remediation(principal, id)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("code", r.code());
            entry.put("ordinal", r.ordinal());
            entry.put("closedCount", r.closedCount());
            entry.put("meanDaysToClose", r.meanDaysToClose());
            entry.put("medianDaysToClose", r.medianDaysToClose());
            entry.put("oldestOpenDays", r.oldestOpenDays());
            remediation.add(entry);
        }
        body.put("remediation", remediation);

        List<Map<String, Object>> assurance = new ArrayList<>();
        for (var a : posture.assurance(principal, id)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("findingClass", a.findingClass());
            entry.put("coveredParts", a.coveredParts());
            entry.put("findingCount", a.findingCount());
            entry.put("openCount", a.openCount());
            entry.put("lastEvidenceAt", a.lastEvidenceAt());
            entry.put("tools", a.tools());
            assurance.add(entry);
        }
        body.put("assurance", assurance);
        // The product-fixed class list, so the interface can render the ones that are ABSENT. A
        // coverage chart drawn only from the classes that produced findings is a chart of what was
        // looked at, and it is read as a chart of what exists (product principle 1).
        body.put("assuranceClasses", List.of("CODE", "DEPENDENCY", "RUNTIME", "SECRET",
                "CONFIGURATION", "INFRASTRUCTURE", "MANUAL"));

        var sla = posture.requestSla(principal, id);
        Map<String, Object> slaBody = new LinkedHashMap<>();
        slaBody.put("met", sla.met());
        slaBody.put("missed", sla.missed());
        slaBody.put("openPastDue", sla.openPastDue());
        slaBody.put("openWithinDue", sla.openWithinDue());
        slaBody.put("closedNoDueDate", sla.closedNoDueDate());
        slaBody.put("openNoDueDate", sla.openNoDueDate());
        body.put("requestSla", slaBody);
        return json(body);
    }

    private static List<Map<String, Object>> postureSlices(
            List<aspm.app.inventory.ApplicationPostureQuery.Slice> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var row : rows) {
            out.add(Map.of("key", row.key(), "total", row.total(), "open", row.open()));
        }
        return out;
    }

    // ==============================================================================================
    // Software composition — SBOMs, the advisories their components carry, and the tree they sit in
    // ==============================================================================================

    /**
     * {@code GET /api/ui/dependencies}. The dashboard's own payload: headline, timeline, and the
     * advisories and components that matter most right now.
     *
     * <p>The tree is NOT in here. It is fetched a level at a time, because the estate is a graph of
     * unknown depth and sending all of it to render one screen is a payload that grows with the
     * company rather than with the page.
     */
    public Dispatcher.Response dependencyOverview(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        // The organization filter, applied to the whole page. Passed through rather than applied to
        // the result: SEC-AUZ-016 wants the predicate in the retrieval, and a filter over an already
        // fetched estate would still have fetched it.
        UUID org = uuid(request.query().get("org"));
        var head = dependencies.overview(principal, org);
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("assets", head.assets());
        summary.put("assetsWithSbom", head.assetsWithSbom());
        summary.put("assetsCurrent", head.assetsCurrent());
        summary.put("snapshots", head.snapshots());
        summary.put("components", head.components());
        summary.put("directComponents", head.directComponents());
        summary.put("vulnerableComponents", head.vulnerableComponents());
        summary.put("advisoriesOpen", head.advisoriesOpen());
        summary.put("criticalOpen", head.criticalOpen());
        summary.put("highOpen", head.highOpen());
        summary.put("mediumOpen", head.mediumOpen());
        summary.put("lowOpen", head.lowOpen());
        summary.put("unratedOpen", head.unratedOpen());
        summary.put("fixableOpen", head.fixableOpen());
        summary.put("resolvedLast90Days", head.resolvedLast90Days());
        summary.put("latestSnapshotAt", head.latestSnapshotAt());
        body.put("summary", summary);

        List<Map<String, Object>> timeline = new ArrayList<>();
        for (var m : dependencies.timeline(principal, org, 12)) {
            timeline.add(Map.of("label", m.label(), "snapshots", m.snapshots(),
                    "appeared", m.advisoriesAppeared(), "resolved", m.advisoriesResolved(),
                    "components", m.componentsAdded()));
        }
        body.put("timeline", timeline);
        body.put("topAdvisories", advisoryRows(dependencies.advisories(principal, "", true, 10)));
        body.put("topComponents", componentRows(dependencies.components(principal, "", true, 10)));
        return json(body);
    }

    /** {@code GET /api/ui/alerts}. The vulnerability alert subscriptions. Never returns a secret. */
    public Dispatcher.Response alertSubscriptions(Dispatcher.Request request) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var row : alerts.list(request.principal())) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", row.id());
            entry.put("label", row.label());
            entry.put("url", row.url());
            entry.put("minSeverityOrdinal", row.minSeverityOrdinal());
            entry.put("minSeverityCode", row.minSeverityCode());
            entry.put("scopeNodeId", row.scopeNodeId());
            entry.put("scopeNodeName", row.scopeNodeName());
            entry.put("active", row.active());
            entry.put("lastDeliveryAt", row.lastDeliveryAt());
            entry.put("lastStatus", row.lastStatus());
            entry.put("consecutiveFailures", row.consecutiveFailures());
            entry.put("signed", row.signed());
            out.add(entry);
        }
        return json(Map.of("rows", out, "mayManage", Boolean.valueOf(
                request.principal().holds(aspm.app.resource.WebhookAlerts.MANAGE))));
    }

    /**
     * {@code POST /api/ui/alerts}. Subscribes an endpoint, and returns its signing secret once.
     *
     * <p>Class E: an alert subscription decides where a description of the group's unfixed
     * vulnerabilities is sent, which is configuration in the sense that matters — it changes what
     * leaves the platform.
     */
    public Dispatcher.Response createAlert(Dispatcher.Request request) throws Exception {
        Map<String, Object> body = request.body().orElseThrow(
                () -> new IllegalArgumentException("a request body is required"));
        int ordinal = body.get("minSeverityOrdinal") instanceof Number number
                ? number.intValue() : 2;
        var secret = alerts.create(request.principal(),
                String.valueOf(body.getOrDefault("label", "")),
                String.valueOf(body.getOrDefault("url", "")),
                ordinal, uuid(String.valueOf(body.get("scopeNodeId"))));
        if (secret.isEmpty()) {
            // One refusal for every reason. Distinguishing "not https" from "resolves to a private
            // address" would let a caller map the internal network through the error messages.
            return new Dispatcher.Response(422, Map.of("status", 422, "code", "DESTINATION_REFUSED",
                    "message", "the destination must be an https URL outside private address ranges"),
                    Map.of());
        }
        return new Dispatcher.Response(201, Map.of("secret", secret.orElseThrow(),
                "signingNote", "Deliveries carry X-ASPM-Signature: sha256=<HMAC of the body with "
                        + "this secret>. It is shown once."), Map.of());
    }

    /** {@code POST /api/ui/alerts/{id}/active}. Turns one on or off; never deletes it. */
    public Dispatcher.Response setAlertActive(Dispatcher.Request request) throws Exception {
        UUID id = uuid(request.pathVariables().get("id"));
        boolean active = request.body().map(b -> !Boolean.FALSE.equals(b.get("active")))
                .orElse(Boolean.TRUE);
        return alerts.setActive(request.principal(), id, active)
                ? json(Map.of("active", Boolean.valueOf(active)))
                : Dispatcher.Response.notFound();
    }

    /** {@code GET /api/ui/dependencies/tree}. One level: applications, or the children of {@code parent}. */
    public Dispatcher.Response dependencyTree(Dispatcher.Request request) throws Exception {
        UUID parent = uuid(request.query().get("parent"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (var node : dependencies.tree(request.principal(), parent,
                request.query().getOrDefault("q", ""), uuid(request.query().get("org")),
                request.query().getOrDefault("severity", ""))) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", node.id());
            entry.put("name", node.name());
            entry.put("typeCode", node.typeCode());
            entry.put("parentId", node.parentId());
            entry.put("owningNodeName", node.owningNodeName());
            // Whose estate this is, beside who owns it. The filter above this table has always taken an
            // organization; the table never said which one a row was in, so a reader could not tell
            // whether an unexpected row was a filter mistake or a real arrangement.
            entry.put("orgId", node.orgId());
            entry.put("orgName", node.orgName());
            entry.put("parts", node.parts());
            entry.put("children", node.children());
            entry.put("sbomParts", node.sbomParts());
            entry.put("componentCount", node.componentCount());
            entry.put("directCount", node.directCount());
            entry.put("advisoryOpen", node.advisoryOpen());
            entry.put("criticalOpen", node.criticalOpen());
            entry.put("highOpen", node.highOpen());
            entry.put("mediumOpen", node.mediumOpen());
            entry.put("lowOpen", node.lowOpen());
            entry.put("vulnerableComponents", node.vulnerableComponents());
            entry.put("fixableOpen", node.fixableOpen());
            entry.put("latestSnapshotAt", node.latestSnapshotAt());
            entry.put("sbomQuality", node.sbomQuality());
            entry.put("submitsSbom", node.submitsSbom());
            out.add(entry);
        }
        return json(Map.of("rows", out));
    }

    /** {@code GET /api/ui/dependencies/advisories}. Search by CVE identifier. */
    public Dispatcher.Response dependencyAdvisories(Dispatcher.Request request) throws Exception {
        boolean openOnly = !"false".equals(request.query().get("open"));
        return json(Map.of("rows", advisoryRows(dependencies.advisories(request.principal(),
                request.query().getOrDefault("q", ""), openOnly, 200))));
    }

    /**
     * {@code GET /api/ui/dependencies/node?asset=…}. Every unresolved advisory under one node.
     *
     * <p>One route for an application, a project and a repository. The subtree walk behind it is the
     * same at every level, so the interface passes whichever row was clicked and gets the same shape
     * back — three routes would be three chances to disagree about what "under" means.
     */
    public Dispatcher.Response dependencyNode(Dispatcher.Request request) throws Exception {
        UUID asset = uuid(request.query().get("asset"));
        if (asset == null) {
            return new Dispatcher.Response(400, Map.of("status", 400, "code", "ASSET_REQUIRED",
                    "message", "asset is required"), Map.of());
        }
        boolean includeResolved = "true".equals(request.query().get("resolved"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (var row : dependencies.nodeAdvisories(request.principal(), asset, includeResolved,
                request.query().getOrDefault("severity", ""))) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("advisoryId", row.advisoryId());
            entry.put("key", row.advisoryKey());
            entry.put("severity", row.severity());
            entry.put("ordinal", row.ordinal());
            entry.put("cvss", row.cvss());
            entry.put("summary", row.summary());
            entry.put("description", row.description());
            entry.put("cweIds", row.cweIds());
            entry.put("references", row.references());
            entry.put("dataSource", row.dataSource());
            entry.put("status", row.status());
            entry.put("publishedAt", row.publishedAt());
            entry.put("detectedAt", row.detectedAt());
            entry.put("source", row.source());
            entry.put("componentId", row.componentId());
            entry.put("componentName", row.componentName());
            entry.put("componentVersion", row.componentVersion());
            entry.put("purl", row.purl());
            entry.put("ecosystem", row.ecosystem());
            entry.put("direct", row.direct());
            entry.put("fixedVersion", row.fixedVersion());
            entry.put("recommendation", row.recommendation());
            entry.put("assetId", row.assetId());
            entry.put("assetName", row.assetName());
            entry.put("assetTypeCode", row.assetTypeCode());
            entry.put("applicationName", row.applicationName());
            entry.put("projectName", row.projectName());
            entry.put("snapshotAt", row.snapshotAt());
            out.add(entry);
        }
        // This node's OWN flow, beside its advisory list. The estate-wide chart answers "is the group
        // getting better"; this answers "did we fix anything", which is the question the team that
        // owns the repository actually has.
        List<Map<String, Object>> timeline = new ArrayList<>();
        for (var m : dependencies.nodeTimeline(request.principal(), asset, 12)) {
            timeline.add(Map.of("label", m.label(), "snapshots", m.snapshots(),
                    "appeared", m.advisoriesAppeared(), "resolved", m.advisoriesResolved()));
        }
        return json(Map.of("rows", out, "timeline", timeline));
    }

    /** {@code GET /api/ui/dependencies/components}. Search by package name or package URL. */
    public Dispatcher.Response dependencyComponents(Dispatcher.Request request) throws Exception {
        boolean vulnerableOnly = "true".equals(request.query().get("vulnerable"));
        return json(Map.of("rows", componentRows(dependencies.components(request.principal(),
                request.query().getOrDefault("q", ""), vulnerableOnly, 200))));
    }

    /**
     * {@code GET /api/ui/dependencies/locations}. Where one advisory or one component actually is.
     *
     * <p>Exactly one of {@code advisory} and {@code component} is honoured, and neither being present
     * is a 400 rather than a listing of everything: an unfiltered answer here would be the whole
     * estate's dependency inventory returned by a route nobody meant to call that way.
     */
    public Dispatcher.Response dependencyLocations(Dispatcher.Request request) throws Exception {
        UUID advisory = uuid(request.query().get("advisory"));
        UUID component = uuid(request.query().get("component"));
        if (advisory == null && component == null) {
            return new Dispatcher.Response(400, Map.of("status", 400, "code", "FILTER_REQUIRED",
                    "message", "advisory or component is required"), Map.of());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (var location : dependencies.locations(request.principal(), advisory, component)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("assetId", location.assetId());
            entry.put("assetName", location.assetName());
            entry.put("assetTypeCode", location.assetTypeCode());
            entry.put("path", location.path());
            entry.put("applicationId", location.applicationId());
            entry.put("applicationName", location.applicationName());
            entry.put("componentName", location.componentName());
            entry.put("componentVersion", location.componentVersion());
            entry.put("direct", location.direct());
            entry.put("fixedVersion", location.fixedVersion());
            out.add(entry);
        }
        return json(Map.of("rows", out));
    }

    /** {@code GET /api/ui/dependencies/graph}. One artifact's dependency edges, for the tree view. */
    public Dispatcher.Response dependencyGraph(Dispatcher.Request request) throws Exception {
        UUID asset = uuid(request.query().get("asset"));
        if (asset == null) {
            return new Dispatcher.Response(400, Map.of("status", 400, "code", "ASSET_REQUIRED",
                    "message", "asset is required"), Map.of());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (var edge : dependencies.dependencyGraph(request.principal(), asset)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("parentId", edge.parentId());
            entry.put("parentName", edge.parentName());
            entry.put("parentVersion", edge.parentVersion());
            entry.put("childId", edge.childId());
            entry.put("childName", edge.childName());
            entry.put("childVersion", edge.childVersion());
            entry.put("childDirect", edge.childDirect());
            entry.put("childAdvisoryOpen", edge.childAdvisoryOpen());
            entry.put("childWorstSeverity", edge.childWorstSeverity());
            out.add(entry);
        }
        return json(Map.of("rows", out));
    }

    private static List<Map<String, Object>> advisoryRows(
            List<aspm.app.resource.DependencyQuery.AdvisoryRow> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var row : rows) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", row.id());
            entry.put("key", row.advisoryKey());
            entry.put("severity", row.severityCode());
            entry.put("cvss", row.cvssScore());
            entry.put("summary", row.summary());
            entry.put("publishedAt", row.publishedAt());
            entry.put("firstRecordedAt", row.firstRecordedAt());
            entry.put("componentCount", row.componentCount());
            entry.put("assetCount", row.assetCount());
            entry.put("applicationCount", row.applicationCount());
            entry.put("unresolved", row.unresolved());
            entry.put("source", row.source());
            out.add(entry);
        }
        return out;
    }

    private static List<Map<String, Object>> componentRows(
            List<aspm.app.resource.DependencyQuery.ComponentRow> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var row : rows) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", row.id());
            entry.put("purl", row.purl());
            entry.put("ecosystem", row.ecosystem());
            entry.put("name", row.name());
            entry.put("version", row.version());
            entry.put("assetCount", row.assetCount());
            entry.put("applicationCount", row.applicationCount());
            entry.put("advisoryOpen", row.advisoryOpen());
            entry.put("criticalOpen", row.criticalOpen());
            entry.put("highOpen", row.highOpen());
            entry.put("direct", row.anyDirect());
            entry.put("licenses", row.licenses());
            out.add(entry);
        }
        return out;
    }

    // ==============================================================================================
    // Ingestion credentials, and the two exports
    // ==============================================================================================

    /** {@code GET /api/ui/service-credentials}. Never returns a secret; there is none to return. */
    public Dispatcher.Response serviceCredentials(Dispatcher.Request request) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var row : credentials.list(request.principal())) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", row.id());
            entry.put("keyId", row.keyId());
            entry.put("label", row.label());
            entry.put("principalId", row.principalId());
            entry.put("principalName", row.principalName());
            entry.put("scopeNodeId", row.scopeNodeId());
            entry.put("scopeNodeName", row.scopeNodeName());
            entry.put("permissions", row.permissions());
            entry.put("expiresAt", row.expiresAt());
            entry.put("lastUsedAt", row.lastUsedAt());
            entry.put("createdAt", row.createdAt());
            entry.put("revokedAt", row.revokedAt());
            entry.put("revokedReason", row.revokedReason());
            out.add(entry);
        }
        return json(Map.of("rows", out, "mayManage",
                Boolean.valueOf(request.principal().holds(
                        aspm.app.identity.ServiceCredentialAdmin.MANAGE))));
    }

    /**
     * {@code POST /api/ui/service-credentials}. Issues one, and discloses the secret exactly once.
     *
     * <p>Class E and step-up, because issuing a non-interactive identity is authorization
     * configuration. The response carries the secret and the response alone: only its digest is
     * stored, so there is no second chance and the interface says so rather than offering a
     * "show again" that would have to be backed by keeping it.
     */
    public Dispatcher.Response issueServiceCredential(Dispatcher.Request request) throws Exception {
        Map<String, Object> body = request.body().orElseThrow(
                () -> new IllegalArgumentException("a request body is required"));
        String label = String.valueOf(body.getOrDefault("label", "")).strip();
        UUID principalId = uuid(String.valueOf(body.get("principalId")));
        UUID scopeNodeId = uuid(String.valueOf(body.get("scopeNodeId")));
        List<String> permissions = new ArrayList<>();
        if (body.get("permissions") instanceof List<?> list) {
            list.forEach(value -> permissions.add(String.valueOf(value)));
        }
        Integer expiresInDays = body.get("expiresInDays") instanceof Number number
                ? Integer.valueOf(number.intValue()) : null;

        var issued = credentials.issue(request.principal(), label, principalId, scopeNodeId,
                permissions, expiresInDays);
        if (issued.isEmpty()) {
            // One refusal for every reason: a label that is blank, a principal that does not exist,
            // a scope node outside the caller's reach. Distinguishing them would tell a caller which
            // identifiers are real, which is the enumeration this surface must not offer.
            return new Dispatcher.Response(422, Map.of("status", 422, "code", "CANNOT_ISSUE",
                    "message", "the credential could not be issued as described"), Map.of());
        }
        var value = issued.orElseThrow();
        return new Dispatcher.Response(201, Map.of(
                "id", value.id(), "keyId", value.keyId(), "secret", value.secret(),
                "signingKeyNote", "The signing key is SHA-256 of this secret, not the secret itself."),
                Map.of());
    }

    /** {@code POST /api/ui/service-credentials/{id}/revoke}. */
    public Dispatcher.Response revokeServiceCredential(Dispatcher.Request request) throws Exception {
        UUID id = uuid(request.pathVariables().get("id"));
        String reason = request.body().map(b -> String.valueOf(b.getOrDefault("reason", "")))
                .orElse("");
        return credentials.revoke(request.principal(), id, reason)
                ? json(Map.of("revoked", Boolean.TRUE))
                : Dispatcher.Response.notFound();
    }

    /**
     * {@code GET /api/ui/dependencies/export}. The estate under one subject, as a spreadsheet or as
     * a bill of materials.
     *
     * <p>{@code org} or {@code asset}, and the asset may be an application, a project or a
     * repository — the rollup is the same composition walk at every level, so one parameter serves
     * all three rather than three routes that would drift.
     *
     * <p>{@code format=xlsx} for the CVE list, {@code format=cyclonedx} for a merged bill of
     * materials. Both are the same rows; what differs is who is going to open the file.
     */
    public Dispatcher.Response dependencyExport(Dispatcher.Request request) throws Exception {
        UUID org = uuid(request.query().get("org"));
        UUID asset = uuid(request.query().get("asset"));
        if (org == null && asset == null) {
            return new Dispatcher.Response(400, Map.of("status", 400, "code", "SUBJECT_REQUIRED",
                    "message", "org or asset is required"), Map.of());
        }
        var name = dependencies.subjectName(request.principal(), org, asset);
        if (name.isEmpty()) {
            // Out of scope and non-existent are the same answer. SEC-AUZ-017: an identifier from the
            // client is re-read, and a 403 here would confirm the identifier is real.
            return Dispatcher.Response.notFound();
        }
        var rows = dependencies.exportRows(request.principal(), org, asset);
        String subject = name.orElse("export");
        String stamp = java.time.LocalDate.now().toString();
        String safe = subject.replaceAll("[^A-Za-z0-9._-]+", "-");

        if ("cyclonedx".equalsIgnoreCase(request.query().getOrDefault("format", "xlsx"))) {
            return new Dispatcher.Response(200,
                    new aspm.app.ui.InterfaceResource.Binary(
                            cycloneDx(subject, rows).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    Map.of("Content-Type", "application/vnd.cyclonedx+json",
                            "Content-Disposition",
                            "attachment; filename=\"sbom-" + safe + "-" + stamp + ".cdx.json\""));
        }
        return new Dispatcher.Response(200,
                new aspm.app.ui.InterfaceResource.Binary(workbook(subject, rows)),
                Map.of("Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "Content-Disposition",
                        "attachment; filename=\"cve-" + safe + "-" + stamp + ".xlsx\""));
    }

    /**
     * The CVE workbook: two sheets, because two people open this file.
     *
     * <p>The first is every (component, advisory) pair — what an engineer filters. The second is one
     * row per advisory with how far it has spread — what somebody planning the week reads. Producing
     * one sheet would make one of the two do a pivot table by hand.
     */
    private static byte[] workbook(String subject,
            List<aspm.app.resource.DependencyQuery.ExportRow> rows) throws java.io.IOException {
        List<List<String>> detail = new ArrayList<>();
        Map<String, long[]> spread = new LinkedHashMap<>();
        Map<String, String[]> facts = new LinkedHashMap<>();
        for (var row : rows) {
            detail.add(List.of(
                    nullToBlank(row.applicationName()), nullToBlank(row.projectName()),
                    nullToBlank(row.repositoryName()), nullToBlank(row.orgNodeName()),
                    nullToBlank(row.ecosystem()), nullToBlank(row.componentName()),
                    nullToBlank(row.componentVersion()), nullToBlank(row.purl()),
                    nullToBlank(row.relationship()), nullToBlank(row.licenses()),
                    nullToBlank(row.advisoryKey()), nullToBlank(row.severity()),
                    nullToBlank(row.cvss()), nullToBlank(row.fixedVersion()),
                    nullToBlank(row.detectedAt()),
                    // Stated in words, because a blank in a "resolved" column reads as missing data
                    // when it means the opposite: still affected.
                    row.advisoryKey() == null ? "" : row.resolvedAt() == null
                            ? "still affected" : "resolved " + row.resolvedAt(),
                    nullToBlank(row.snapshotAt())));
            if (row.advisoryKey() != null && row.resolvedAt() == null) {
                long[] counts = spread.computeIfAbsent(row.advisoryKey(), k -> new long[2]);
                counts[0]++;
                facts.putIfAbsent(row.advisoryKey(), new String[] {
                        nullToBlank(row.severity()), nullToBlank(row.cvss()),
                        nullToBlank(row.fixedVersion())});
            }
        }
        List<List<String>> summary = new ArrayList<>();
        for (var entry : spread.entrySet()) {
            String[] fact = facts.get(entry.getKey());
            summary.add(List.of(entry.getKey(), fact[0], fact[1],
                    String.valueOf(entry.getValue()[0]),
                    fact[2].isBlank() ? "no fix published" : fact[2]));
        }
        return aspm.app.resource.Workbook.write(List.of(
                new aspm.app.resource.Workbook.Sheet("Open advisories",
                        List.of("Advisory", "Severity", "CVSS", "Occurrences still affected",
                                "Fixed in"), summary),
                new aspm.app.resource.Workbook.Sheet("Components and advisories",
                        List.of("Application", "Project", "Repository", "Organization", "Ecosystem",
                                "Component", "Version", "Package URL", "Relationship", "Licenses",
                                "Advisory", "Severity", "CVSS", "Fixed in", "Detected",
                                "Resolution", "SBOM date"), detail)));
    }

    /**
     * A merged CycloneDX document for the subject.
     *
     * <p>Merged, and the merge is stated in the document rather than implied: the metadata component
     * is the SUBJECT — an organization, an application, a project — not a build artifact, because no
     * single build produced this. A consumer reading it as one artifact's bill of materials would be
     * reading something that was never built.
     */
    private static String cycloneDx(String subject,
            List<aspm.app.resource.DependencyQuery.ExportRow> rows) {
        java.util.LinkedHashMap<String, Map<String, Object>> components = new LinkedHashMap<>();
        java.util.LinkedHashMap<String, Map<String, Object>> vulnerabilities = new LinkedHashMap<>();
        for (var row : rows) {
            if (row.purl() != null && !row.purl().isBlank()) {
                components.computeIfAbsent(row.purl(), purl -> {
                    Map<String, Object> component = new LinkedHashMap<>();
                    component.put("bom-ref", purl);
                    component.put("type", "library");
                    component.put("name", nullToBlank(row.componentName()));
                    component.put("version", nullToBlank(row.componentVersion()));
                    component.put("purl", purl);
                    return component;
                });
            }
            if (row.advisoryKey() != null && row.resolvedAt() == null && row.purl() != null) {
                Map<String, Object> vulnerability = vulnerabilities.computeIfAbsent(
                        row.advisoryKey(), key -> {
                            Map<String, Object> entry = new LinkedHashMap<>();
                            entry.put("id", key);
                            if (row.severity() != null) {
                                entry.put("ratings", List.of(new LinkedHashMap<>(Map.of(
                                        "severity", row.severity().toLowerCase(java.util.Locale.ROOT)))));
                            }
                            entry.put("affects", new ArrayList<Map<String, Object>>());
                            return entry;
                        });
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> affects =
                        (List<Map<String, Object>>) vulnerability.get("affects");
                affects.add(Map.of("ref", row.purl()));
            }
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("bomFormat", "CycloneDX");
        document.put("specVersion", "1.5");
        document.put("version", Integer.valueOf(1));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("timestamp", java.time.Instant.now().toString());
        metadata.put("component", Map.of("type", "application", "name", subject,
                "bom-ref", "export:" + subject));
        document.put("metadata", metadata);
        document.put("components", List.copyOf(components.values()));
        document.put("vulnerabilities", List.copyOf(vulnerabilities.values()));
        return aspm.app.runtime.Json.write(document);
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    // ==============================================================================================
    // Projects — the branch of an application one team delivers
    // ==============================================================================================

    /**
     * {@code GET /api/ui/projects}.
     *
     * <p>{@code applicationId} and {@code applicationName} travel with every row. They are derived
     * from the composition graph rather than stored, so an intake form that asks only for a project
     * can fill in the application from the same answer the inventory gives — and the two cannot
     * disagree, which is the point of deriving it.
     */
    public Dispatcher.Response projects(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        aspm.app.inventory.ProjectQuery query =
                new aspm.app.inventory.ProjectQuery(dataSource);
        java.util.UUID application = uuid(request.query().get("application"));
        // `org` is comma-separated and subtree-inclusive, the same name and the same meaning it has on
        // the vulnerability dashboard. One parameter, one meaning, everywhere (product principle 10) —
        // two pages spelling the same filter differently is how a shared link stops working.
        List<UUID> org = uuidList(request.query().get("org"));
        List<aspm.app.inventory.ProjectQuery.Project> rows = query.projects(principal,
                request.query().getOrDefault("q", ""), application, org);

        List<Map<String, Object>> out = new ArrayList<>();
        long withSevere = 0;
        long neverAssessed = 0;
        java.util.Set<String> teams = new LinkedHashSet<>();
        for (var project : rows) {
            // Counts OF PROJECTS, never a sum of their findings. Two projects legitimately share a
            // service — that is what happens when one team's work depends on another's — and its
            // findings roll up into both. Correct per project, and adding them would report more open
            // findings than the estate contains. A headline figure larger than the truth is the
            // mirror image of PP-1 and destroys trust in the page just as fast.
            if (project.criticalOpen() + project.highOpen() > 0) {
                withSevere++;
            }
            if (project.requestCount() == 0) {
                neverAssessed++;
            }
            if (project.owningNodeName() != null) {
                teams.add(project.owningNodeName());
            }
            out.add(project(project));
        }

        // *** THE OPTIONS COME FROM AN UNFILTERED READ, AND THAT IS A CORRECTION. ***
        //
        // They used to be derived from `rows`. With `application=A` applied, `rows` holds only A's
        // projects, so the application picker collapsed to the single value already selected and there
        // was no way to switch to B without clearing the filter first. The same trap was waiting for the
        // organization picker, and a second filter would have made it twice as visible.
        //
        // Read at the caller's own scope with no search and no filter, so the pickers always offer
        // everything they could narrow TO — while still never offering something with no project the
        // caller can reach, which is the property the old comment was protecting.
        List<aspm.app.inventory.ProjectQuery.Project> everything =
                query.projects(principal, "", null, null);

        Map<String, String> applications = new LinkedHashMap<>();
        for (var project : everything) {
            if (project.applicationId() != null) {
                applications.put(project.applicationId().toString(), project.applicationName());
            }
        }
        List<Map<String, Object>> applicationOptions = new ArrayList<>();
        applications.forEach((id, name) -> applicationOptions.add(Map.of("id", id, "name", name)));

        // Every organization node that has a project beneath it, at every level — the divisions as well
        // as the teams. The filter is subtree-inclusive, so offering only the owning teams would hide
        // exactly the level an executive thinks in; and offering a node with nothing under it would be a
        // filter that returns an empty table for no visible reason.
        //
        // `depth` is carried so the interface can indent rather than invent a hierarchy from the names.
        // `path` is what the options are ORDERED by, which puts each node under its own parent.
        Map<String, Map<String, Object>> organizations = new LinkedHashMap<>();
        for (var project : everything) {
            List<String> names = new ArrayList<>(project.ownerAncestors());
            List<String> ids = new ArrayList<>();
            project.ownerAncestorIds().forEach(id -> ids.add(id.toString()));
            if (project.owningNodeId() != null) {
                names.add(project.owningNodeName());
                ids.add(project.owningNodeId().toString());
            }
            // Guard rather than assume: the two arrays come from one ordering but a project with no
            // owning node contributes only ancestors, and a mismatch here would label a node with
            // another node's name.
            int levels = Math.min(names.size(), ids.size());
            StringBuilder path = new StringBuilder();
            for (int depth = 0; depth < levels; depth++) {
                path.append(depth == 0 ? "" : " › ").append(names.get(depth));
                String id = ids.get(depth);
                Map<String, Object> option = new LinkedHashMap<>();
                option.put("id", id);
                option.put("name", names.get(depth));
                option.put("depth", Integer.valueOf(depth));
                option.put("path", path.toString());
                organizations.putIfAbsent(id, option);
            }
        }
        List<Map<String, Object>> organizationOptions = new ArrayList<>(organizations.values());
        organizationOptions.sort(java.util.Comparator.comparing(o -> String.valueOf(o.get("path"))));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rows", out);
        body.put("applications", applicationOptions);
        body.put("organizations", organizationOptions);
        body.put("totals", Map.of("projects", rows.size(), "teams", teams.size(),
                "withSevereOpen", withSevere, "neverAssessed", neverAssessed));
        // Which of these the caller may actually raise a request against. Sent as a set rather than
        // applied as a filter, because the projects list is also a dashboard: hiding the ones they
        // cannot request against would hide projects they are entitled to SEE, and those are two
        // different questions (product principle 4 — the picker is derived, not asserted).
        List<String> raisable = new ArrayList<>();
        for (var project : rows) {
            if (authority.mayRaiseRequestFor(principal, project.id())) {
                raisable.add(project.id().toString());
            }
        }
        body.put("raisable", raisable);
        return json(body);
    }

    /** {@code GET /api/ui/projects/{id}}. */
    public Dispatcher.Response project(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        if (id == null) {
            return Dispatcher.Response.notFound();
        }
        var found = new aspm.app.inventory.ProjectQuery(dataSource).project(principal, id);
        if (found.isEmpty()) {
            // The same 404 a non-existent project gets. A project belonging to a team this caller
            // cannot reach must not be distinguishable from one that never existed.
            return Dispatcher.Response.notFound();
        }

        List<Map<String, Object>> components = new ArrayList<>();
        for (var component : inventory.components(principal, id)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", component.id().toString());
            entry.put("name", component.name());
            entry.put("typeCode", component.typeCode());
            entry.put("depth", component.depth());
            entry.put("path", component.path());
            entry.put("edgeType", component.edgeType());
            entry.put("lifecycleState", component.lifecycleState());
            entry.put("exposure", component.exposure());
            entry.put("findingOpen", component.findingOpen());
            entry.put("findingTotal", component.findingTotal());
            entry.put("criticalOpen", component.criticalOpen());
            entry.put("highOpen", component.highOpen());
            entry.put("scaOpen", component.scaOpen());
            entry.put("acceptedTotal", component.acceptedTotal());
            components.add(entry);
        }

        Map<String, Object> body = new LinkedHashMap<>(project(found.orElseThrow()));
        body.put("components", components);
        body.put("requests", inventory.requestsFor(principal, id));
        return json(body);
    }

    /**
     * {@code GET /api/ui/projects/{id}/requests} — one page of them, newest first.
     *
     * <p>Paginated because a project accumulates requests for as long as it exists. The panel lives
     * at the bottom of the dashboard for the same reason: it is the section that grows, and putting
     * a growing list above fixed information pushes the fixed information off the screen.
     */
    public Dispatcher.Response projectRequests(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        if (id == null) {
            return Dispatcher.Response.notFound();
        }
        var page = intake.requestsForProject(principal, id,
                integer(request.query().get("page"), 0), integer(request.query().get("size"), 10));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (var row : page.rows()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", row.id().toString());
            entry.put("code", row.code());
            entry.put("title", row.title());
            entry.put("state", row.state());
            entry.put("stateCategory", row.stateCategory());
            entry.put("createdAt", row.createdAt());
            entry.put("dueAt", row.dueAt());
            entry.put("requestedBy", row.requestedBy());
            entry.put("findingOpen", row.findingOpen());
            entry.put("findingTotal", row.findingTotal());
            rows.add(entry);
        }
        return json(Map.of("rows", rows, "page", page.page(), "size", page.size(),
                "total", page.total()));
    }

    /**
     * {@code POST /api/ui/requests} — raise one.
     *
     * <p>Everything the form collected arrives in one body and is written in one transaction. A
     * request that exists without its role accounts is a request an assessor cannot start, and it
     * would sit on the board looking ready.
     */
    public Dispatcher.Response createRequest(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        Map<String, Object> payload = request.body().orElse(Map.of());
        try {
            var created = intake.create(principal, draft(payload));
            return new Dispatcher.Response(201,
                    Map.of("id", created.id().toString(), "code", created.code()),
                    Map.of("Content-Type", "application/json; charset=utf-8",
                            "Location", "/board/" + created.id()));
        } catch (aspm.app.assessment.IntakeService.RejectedException rejected) {
            // 422, not 500 and not a bare 400: the body was well-formed and the platform refused it
            // for a stated reason, and the field name is what lets the form put the message where
            // the person can act on it.
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", rejected.code());
            body.put("field", rejected.field());
            body.put("message", rejected.getMessage());
            return new Dispatcher.Response(422, body, Map.of());
        }
    }

    /** Reads the intake form's body. Absent and blank are the same thing to every field here. */
    private static aspm.app.assessment.IntakeService.Draft draft(Map<String, Object> payload) {
        List<aspm.app.assessment.IntakeService.RoleAccounts> roles = new ArrayList<>();
        for (Object raw : list(payload.get("roles"))) {
            Map<String, Object> role = asMap(raw);
            List<aspm.app.assessment.IntakeService.Account> accounts = new ArrayList<>();
            for (Object rawAccount : list(role.get("accounts"))) {
                Map<String, Object> account = asMap(rawAccount);
                accounts.add(new aspm.app.assessment.IntakeService.Account(
                        text(account.get("username")), text(account.get("credentialRef")),
                        text(account.get("password")),
                        Boolean.TRUE.equals(account.get("mfaEnrolled")),
                        text(account.get("mfaBypassRef"))));
            }
            roles.add(new aspm.app.assessment.IntakeService.RoleAccounts(
                    text(role.get("roleName")), text(role.get("description")), accounts));
        }

        List<aspm.app.assessment.IntakeService.Environment> environments = new ArrayList<>();
        for (Object raw : list(payload.get("environments"))) {
            Map<String, Object> environment = asMap(raw);
            environments.add(new aspm.app.assessment.IntakeService.Environment(
                    text(environment.get("envType")), text(environment.get("baseUrl")),
                    Boolean.TRUE.equals(environment.get("vpnRequired")),
                    Boolean.TRUE.equals(environment.get("protectiveControlPresent")),
                    Boolean.TRUE.equals(environment.get("bypassArranged")),
                    text(environment.get("bypassMethod")),
                    text(environment.get("testWindowConstraints"))));
        }

        Integer apiCount = payload.get("apiCount") instanceof Number number
                ? Integer.valueOf(number.intValue()) : null;
        java.time.LocalDate dueAt = null;
        String rawDue = text(payload.get("dueAt"));
        if (rawDue != null && !rawDue.isBlank()) {
            try {
                dueAt = java.time.LocalDate.parse(rawDue);
            } catch (java.time.format.DateTimeParseException e) {
                dueAt = null;
            }
        }
        return new aspm.app.assessment.IntakeService.Draft(
                text(payload.get("title")), uuid(text(payload.get("projectId"))),
                uuid(text(payload.get("triggerId"))), text(payload.get("detail")),
                dueAt, roles, environments, apiCount,
                text(payload.get("gitRepository")), text(payload.get("technologyStack")),
                text(payload.get("notes")));
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> items ? items : List.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static int integer(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ==============================================================================================
    // Who may do what with one project, and with one request
    // ==============================================================================================

    /**
     * {@code GET /api/ui/projects/{id}/access} — the owner, and everyone allowed to raise a request.
     *
     * <p>The declared permission on this operation is the floor. Whether the caller may CHANGE any of
     * it is {@link aspm.app.authz.ObjectAuthority#mayGrantOn}, and it travels in the payload as
     * {@code mayGrant} so the interface can disable controls rather than offer ones that will fail.
     */
    public Dispatcher.Response projectAccess(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        if (id == null || new aspm.app.inventory.ProjectQuery(dataSource)
                .project(principal, id).isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("grants", grants(authority.grantsOn(principal, id)));
        body.put("people", assessments.assignableprincipals(principal));
        body.put("mayGrant", authority.mayGrantOn(principal, id));
        body.put("mayRaiseRequest", authority.mayRaiseRequestFor(principal, id));
        return json(body);
    }

    /** {@code POST /api/ui/projects/{id}/access} — grant OWN or RAISE_REQUEST. Class E. */
    public Dispatcher.Response grantProjectAccess(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        if (id == null || new aspm.app.inventory.ProjectQuery(dataSource)
                .project(principal, id).isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        // The object-level gate. The dispatcher checked a permission that only says the caller may
        // look at assets; whether they may change who owns THIS one is decided here.
        if (!authority.mayGrantOn(principal, id)) {
            return Dispatcher.Response.notFound();
        }
        Map<String, Object> payload = request.body().orElse(Map.of());
        UUID target = uuid(text(payload.get("principal")));
        if (target == null) {
            return new Dispatcher.Response(400,
                    Map.of("code", "PRINCIPAL_REQUIRED", "message", "name the person"), Map.of());
        }
        aspm.app.authz.ObjectAuthority.Capability capability;
        try {
            capability = aspm.app.authz.ObjectAuthority.Capability
                    .valueOf(String.valueOf(payload.getOrDefault("capability", "")));
        } catch (IllegalArgumentException e) {
            return new Dispatcher.Response(400, Map.of("code", "CAPABILITY_INVALID",
                    "message", "a grant is OWN or RAISE_REQUEST"), Map.of());
        }
        return json(Map.of("granted", authority.grant(principal, id, target, capability)));
    }

    /** {@code POST /api/ui/projects/{id}/access/revoke}. Class E. */
    public Dispatcher.Response revokeProjectAccess(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        if (id == null || !authority.mayGrantOn(principal, id)) {
            return Dispatcher.Response.notFound();
        }
        UUID grant = uuid(text(request.body().orElse(Map.of()).get("grant")));
        if (grant == null) {
            return new Dispatcher.Response(400,
                    Map.of("code", "GRANT_REQUIRED", "message", "name the grant to revoke"),
                    Map.of());
        }
        return json(Map.of("revoked",
                authority.revoke(principal, grant, "REVOKED_BY_" + principal.principalId())));
    }

    /** {@code GET /api/ui/board/{id}/participants}. */
    public Dispatcher.Response participants(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        if (id == null || assessments.request(principal, id).isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var participant : authority.participants(principal, id)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", participant.id().toString());
            entry.put("principalId", participant.principalId().toString());
            entry.put("displayName", participant.displayName());
            entry.put("username", participant.username());
            entry.put("addedAt", participant.addedAt());
            entry.put("addedBy", participant.addedByName());
            rows.add(entry);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("participants", rows);
        body.put("people", assessments.assignableprincipals(principal));
        body.put("mayManage", authority.mayManageParticipants(principal, id));
        return json(body);
    }

    /** {@code POST /api/ui/board/{id}/participants}. */
    public Dispatcher.Response addParticipant(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        if (id == null || assessments.request(principal, id).isEmpty()
                || !authority.mayManageParticipants(principal, id)) {
            return Dispatcher.Response.notFound();
        }
        UUID target = uuid(text(request.body().orElse(Map.of()).get("principal")));
        if (target == null) {
            return new Dispatcher.Response(400,
                    Map.of("code", "PRINCIPAL_REQUIRED", "message", "name the person"), Map.of());
        }
        return json(Map.of("added", authority.addParticipant(principal, id, target)));
    }

    /** {@code POST /api/ui/board/{id}/participants/remove}. */
    public Dispatcher.Response removeParticipant(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        if (id == null || !authority.mayManageParticipants(principal, id)) {
            return Dispatcher.Response.notFound();
        }
        UUID participant = uuid(text(request.body().orElse(Map.of()).get("participant")));
        if (participant == null) {
            return new Dispatcher.Response(400,
                    Map.of("code", "PARTICIPANT_REQUIRED", "message", "name the participant"),
                    Map.of());
        }
        return json(Map.of("removed", authority.removeParticipant(principal, participant,
                "REMOVED_BY_" + principal.principalId())));
    }

    /**
     * {@code POST /api/ui/board/{id}/findings/{findingId}/remediation} — "we fixed it".
     *
     * <p>Deliberately NOT a state change. The finding stays open and joins the retest queue; an
     * assessor closes it after retesting. See V032 for the whole argument, which comes down to this:
     * a platform where the team being assessed closes its own findings measures nothing.
     */
    public Dispatcher.Response claimRemediation(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        UUID id = uuid(request.pathVariables().get("id"));
        UUID findingId = uuid(request.pathVariables().get("findingId"));
        if (id == null || findingId == null
                || assessments.finding(principal, id, findingId).isEmpty()) {
            return Dispatcher.Response.notFound();
        }
        if (!authority.mayClaimRemediation(principal, id)) {
            return Dispatcher.Response.notFound();
        }
        Map<String, Object> payload = request.body().orElse(Map.of());
        boolean claimed = !Boolean.FALSE.equals(payload.get("claimed"));
        boolean applied = assessments.claimRemediation(principal, id, findingId, claimed,
                text(payload.get("note")));
        if (!applied && claimed) {
            // The only way an update of an existing finding writes no row is the state guard: it is
            // already closed. Said plainly rather than reported as success.
            return new Dispatcher.Response(409, Map.of("code", "NOT_OPEN",
                    "message", "that finding is already closed, so there is nothing to claim"),
                    Map.of());
        }
        return json(Map.of("claimed", claimed && applied));
    }

    private static List<Map<String, Object>> grants(
            List<aspm.app.authz.ObjectAuthority.Grant> source) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var grant : source) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", grant.id().toString());
            entry.put("assetId", grant.assetId().toString());
            entry.put("assetName", grant.assetName());
            entry.put("principalId", grant.principalId().toString());
            entry.put("displayName", grant.principalName());
            entry.put("username", grant.username());
            entry.put("capability", grant.capability());
            entry.put("grantedAt", grant.grantedAt());
            entry.put("grantedBy", grant.grantedByName());
            out.add(entry);
        }
        return out;
    }

    private static Map<String, Object> project(aspm.app.inventory.ProjectQuery.Project project) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", project.id().toString());
        entry.put("name", project.name());
        entry.put("lifecycleState", project.lifecycleState());
        entry.put("description", project.description());
        entry.put("owningNodeId",
                project.owningNodeId() == null ? null : project.owningNodeId().toString());
        entry.put("owningNodeName", project.owningNodeName());
        entry.put("ownerAncestors", project.ownerAncestors());
        // Root first, paired positionally with the names above. The interface renders the topmost as the
        // organization and needs its identifier to offer "show me only this one".
        entry.put("ownerAncestorIds", project.ownerAncestorIds().stream().map(UUID::toString).toList());
        entry.put("deliveryTeam", project.deliveryTeam());
        entry.put("criticalityCode", project.criticalityCode());
        entry.put("criticalityInherited", project.criticalityInherited());
        entry.put("exposureDeclared", project.exposureDeclared());
        entry.put("exposureObserved", project.exposureObserved());
        entry.put("exposureConflict", project.exposureConflict());
        entry.put("applicationId",
                project.applicationId() == null ? null : project.applicationId().toString());
        // Null where the project hangs off no application. Reported rather than hidden: a project
        // outside every application is the case an intake form cannot resolve, and somebody has to
        // see it before a requester does.
        entry.put("applicationName", project.applicationName());
        entry.put("componentCount", project.componentCount());
        entry.put("findingTotal", project.findingTotal());
        entry.put("findingOpen", project.findingOpen());
        entry.put("findingAccepted", project.findingAccepted());
        entry.put("criticalOpen", project.criticalOpen());
        entry.put("highOpen", project.highOpen());
        entry.put("scaOpen", project.scaOpen());
        entry.put("requestCount", project.requestCount());
        entry.put("lastDetectedAt", project.lastDetectedAt());
        return entry;
    }

    /** {@code GET /api/ui/organization}. */
    public Dispatcher.Response organization(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("nodes", nodes(inventory.nodes(principal, false)));
        body.put("nodeTypes", inventory.nodeTypes(principal).stream()
                .map(t -> Map.of("id", t.id().toString(), "code", t.code(),
                        "mayOwnAssets", t.mayOwnAssets(), "ordinal", t.ordinal()))
                .toList());
        body.put("criticalities", inventory.tiers(principal).stream()
                .map(t -> Map.of("id", t.id().toString(), "code", t.code(),
                        "ordinal", t.ordinal()))
                .toList());
        return json(body);
    }

    /**
     * The organization nodes, ordered so that a child follows its own parent.
     *
     * <p>{@link #nodes} orders by depth and then by name, which is right for the organization editor and
     * <b>wrong for an indented picker</b>: every root came first, then every depth-1 node, so
     * "Digital Platform" was drawn indented under whichever root happened to sort last. The indentation
     * asserted a parent that was not the node's parent, which is worse than no indentation at all.
     *
     * <p>Sorted here by the full path rather than fixed in {@code nodes}, because the two callers want
     * different orders and the editor's is not broken.
     */
    private static List<Map<String, Object>> organizationsAsTree(
            List<aspm.app.inventory.InventoryService.Node> rows) {
        Map<String, String> nameById = new LinkedHashMap<>();
        Map<String, String> parentById = new LinkedHashMap<>();
        for (var node : rows) {
            nameById.put(node.id().toString(), node.name());
            parentById.put(node.id().toString(),
                    node.parentId() == null ? null : node.parentId().toString());
        }
        List<Map<String, Object>> out = new ArrayList<>(nodes(rows));
        Map<String, String> pathById = new LinkedHashMap<>();
        for (String id : nameById.keySet()) {
            List<String> parts = new ArrayList<>();
            String at = id;
            // Bounded by the number of nodes, so a parent cycle cannot spin here. The closure table
            // forbids one; this loop does not depend on that being true.
            for (int guard = 0; at != null && guard <= nameById.size(); guard++) {
                parts.add(0, nameById.getOrDefault(at, ""));
                at = parentById.get(at);
            }
            pathById.put(id, String.join("\u001f", parts));
        }
        out.sort(java.util.Comparator.comparing(
                entry -> pathById.getOrDefault(String.valueOf(entry.get("id")), "")));
        return out;
    }

    private static List<Map<String, Object>> nodes(
            List<aspm.app.inventory.InventoryService.Node> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var node : rows) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", node.id().toString());
            entry.put("name", node.name());
            entry.put("typeCode", node.typeCode());
            entry.put("depth", node.depth());
            entry.put("parentName", node.parentName());
            entry.put("parentId", node.parentId() == null ? null : node.parentId().toString());
            // Carried so the editor can send it back and a concurrent rename is refused rather than
            // silently overwritten. A read that omits it forces the write to fetch its own version,
            // which is the same as having no optimistic concurrency at all.
            entry.put("rowVersion", node.rowVersion());
            entry.put("mayOwnAssets", node.mayOwnAssets());
            entry.put("childCount", node.childCount());
            entry.put("criticalityCode", node.criticalityCode());
            entry.put("assetCount", node.assetCount());
            entry.put("lifecycleState", node.lifecycleState());
            out.add(entry);
        }
        return out;
    }

    // ==============================================================================================
    // GET /api/ui/overview
    // ==============================================================================================

    /**
     * The overview dashboard, as JSON.
     *
     * <p><b>The presentation state is decided HERE, on the server, and a measure that is unmeasured
     * carries no numeral in the payload at all.</b> That is not defence in depth for its own sake:
     * {@code PRD-UIX-022} calls rendering unmeasured as zero "the interface-layer expression of the
     * PP-1 failure the whole corpus guards against", and a client that receives
     * {@code {"value": 0, "measured": 0}} is one careless {@code value ?? 0} away from committing it.
     * A payload with no number in it cannot be rendered as a number by mistake.
     */
    public Dispatcher.Response overview(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        aspm.app.resource.OverviewQuery query =
                new aspm.app.resource.OverviewQuery(dataSource);

        List<aspm.app.resource.OverviewQuery.SeverityLoad> severities = query.severities(principal);
        aspm.app.resource.OverviewQuery.RequestLoad requests = query.requests(principal);
        aspm.app.resource.OverviewQuery.Estate estate = query.estate(principal);
        List<aspm.app.resource.OverviewQuery.TrendPoint> trend = query.trend(principal, 12);
        List<aspm.app.resource.OverviewQuery.RecentFinding> recent = query.recent(principal, 8);

        long findingsTotal = severities.stream()
                .mapToLong(aspm.app.resource.OverviewQuery.SeverityLoad::total).sum();
        long findingsOpen = severities.stream()
                .mapToLong(aspm.app.resource.OverviewQuery.SeverityLoad::open).sum();
        long findingsUnassigned = severities.stream()
                .mapToLong(aspm.app.resource.OverviewQuery.SeverityLoad::unassigned).sum();
        // The two highest-ordinal-first severities a tenant defines, whatever they called them. The
        // codes are NOT enumerated here: DOC-09 and ADR-027 make the severity scale tenant data, and a
        // switch on "CRITICAL" is a fixed enumeration over a configurable surface.
        long severeOpen = severities.stream()
                .filter(s -> s.ordinal() <= 2)
                .mapToLong(aspm.app.resource.OverviewQuery.SeverityLoad::open).sum();

        List<Map<String, Object>> kpis = new ArrayList<>();
        // measured is the finding population, not the open count. Zero open findings over zero
        // findings means nothing was looked at; over forty closed ones it means the estate is clean,
        // and the difference is the entire point of the first product principle.
        kpis.add(measure("overview.openFindings", findingsOpen, (int) findingsTotal,
                Math.max(estate.assets(), (int) findingsTotal), "/board"));
        kpis.add(measure("overview.severeOpen", severeOpen, (int) findingsTotal,
                Math.max(estate.assets(), (int) findingsTotal), "/board"));
        kpis.add(measure("overview.overdueRequests", requests.overdue(), (int) requests.total(),
                (int) requests.total(), "/board?only=overdue"));
        kpis.add(measure("overview.unassignedFindings", findingsUnassigned, (int) findingsTotal,
                Math.max(estate.assets(), (int) findingsTotal), "/board"));

        List<Map<String, Object>> coverage = new ArrayList<>();
        coverage.add(coverage("overview.assessmentCoverage", estate.applicationsReviewed(),
                estate.applications(), "/applications"));
        coverage.add(coverage("overview.compositionCoverage", estate.assetsWithSbom(),
                estate.assets(), "/composition"));
        coverage.add(coverage("overview.sbomCurrency", estate.assetsWithCurrentSbom(),
                estate.assetsWithSbom(), "/composition"));

        List<Map<String, Object>> severityRows = new ArrayList<>();
        for (var severity : severities) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("code", severity.code());
            entry.put("ordinal", severity.ordinal());
            entry.put("total", severity.total());
            entry.put("open", severity.open());
            entry.put("unassigned", severity.unassigned());
            entry.put("agedOverThirtyDays", severity.agedOverThirtyDays());
            severityRows.add(entry);
        }

        List<Map<String, Object>> trendRows = new ArrayList<>();
        for (var point : trend) {
            trendRows.add(Map.of("label", point.label(), "opened", point.opened(),
                    "closed", point.closed()));
        }

        List<Map<String, Object>> recentRows = new ArrayList<>();
        for (var finding : recent) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", finding.id());
            // Null where the finding did not come from an assessment. The interface renders those
            // without a link rather than with one that 404s — a dashboard whose figures lead nowhere
            // teaches people not to click the figures.
            entry.put("requestId", finding.requestId());
            entry.put("title", finding.title());
            entry.put("severity", finding.severity());
            entry.put("state", finding.state());
            entry.put("firstDetectedAt", finding.firstDetectedAt());
            entry.put("sourceTool", finding.sourceTool());
            recentRows.add(entry);
        }

        // What the estate is telling you, composed from the facts rather than left for the reader
        // to assemble out of four panels. This is also the seam the analysis agent plugs into —
        // see OverviewInsights: same contract, rules today, a model later.
        List<Map<String, Object>> observations = new ArrayList<>();
        for (var o : new aspm.app.resource.OverviewInsights(dataSource).observations(principal)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("code", o.code());
            entry.put("level", o.level().name());
            entry.put("headline", o.headline());
            entry.put("detail", o.detail());
            entry.put("evidence", o.evidence().stream()
                    .map(e -> Map.of("label", e.label(), "value", e.value())).toList());
            entry.put("href", o.href());
            // Never absent. An observation nobody can trace back is one nobody should act on, and
            // that matters more once a model is writing them rather than a rule.
            entry.put("basis", o.basis());
            observations.add(entry);
        }

        // Per organization, because "how is MINE doing" is the first question of everybody who
        // opens this page, and a group total answers a question nobody owns. The rows are the
        // caller's scope roots, so an executive sees every company and a manager sees one — the
        // same page serves both without a filter anybody has to remember.
        List<Map<String, Object>> posture = new ArrayList<>();
        for (var org : new aspm.app.resource.OverviewInsights(dataSource).posture(principal)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("nodeId", org.nodeId());
            entry.put("name", org.name());
            entry.put("applications", org.applications());
            entry.put("neverAssessed", org.neverAssessed());
            entry.put("openNow", org.openNow());
            entry.put("openBefore", org.openBefore());
            entry.put("serious", org.serious());
            entry.put("exposedSerious", org.exposedSerious());
            // Null, never a placeholder date. "Never assessed" is the answer that matters most here
            // and a count of assessments cannot give it.
            entry.put("lastAssessedAt", org.lastAssessedAt());
            entry.put("measured", org.measured());
            posture.add(entry);
        }

        // DOC-28's model, applied. Everything below carries its factor coverage and its confidence
        // qualifier, because three of the six factors have no input in this deployment and a score
        // that hides that is worse than no score: it is a defensible-looking number nobody can
        // defend. PRD-RSK-027 decides what may be shown as a figure and what must be shown as a gap.
        aspm.app.resource.RiskScoring scoring = new aspm.app.resource.RiskScoring(dataSource);
        var overall = scoring.overall(principal);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("risk", Map.of(
                "overall", riskPosture(overall),
                "distribution", scoring.distribution(principal).stream()
                        .map(d -> Map.of("band", d.scoreBand(), "findings", d.findings())).toList(),
                "byOrganization", scoring.organizationPosture(principal).stream()
                        .map(UiApi::riskPosture).toList(),
                "topApplications", scoring.applicationPosture(principal, 10).stream()
                        .map(UiApi::riskPosture).toList(),
                "topFindings", scoring.topFindings(principal, 10).stream().map(f -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", f.findingId().toString());
                    // Null where the finding came from outside an assessment. The interface renders
                    // those without a link rather than with one that 404s.
                    entry.put("requestId",
                            f.requestId() == null ? null : f.requestId().toString());
                    entry.put("title", f.title());
                    entry.put("severity", f.severity());
                    entry.put("score", f.score());
                    entry.put("band", f.scoreBand());
                    entry.put("exposure", f.exposure());
                    entry.put("criticality", f.criticality());
                    return entry;
                }).toList(),
                "model", Map.of(
                        "version", aspm.app.resource.RiskScoring.MODEL_VERSION,
                        "factorCoverage", aspm.app.resource.RiskScoring.FACTOR_COVERAGE,
                        // Named, not counted. "55% of factors" tells a reader nothing they can act
                        // on; "no exploit-prediction, no known-exploited catalogue, no data
                        // classification" tells them exactly which three integrations would move it.
                        "absentFactors", aspm.app.resource.RiskScoring.ABSENT_FACTORS)));
        // How fast, how old, what kind, and how much of it there is. None of these depends on the
        // scoring model's missing factors — every one is a count or an elapsed time over recorded
        // events — which is why they are a separate section rather than more risk figures.
        aspm.app.resource.AttackSurface surface = new aspm.app.resource.AttackSurface(dataSource);
        var mttr = surface.remediation(principal, 365);
        // A LinkedHashMap and not Map.of, which rejects nulls — and the nulls are the point. Zero days
        // to remediate is a claim; having no closure to measure is the absence of one, and PP-1 turns
        // on keeping the two distinguishable all the way to the screen.
        Map<String, Object> remediation = new LinkedHashMap<>();
        remediation.put("closed", mttr.closed());
        remediation.put("meanDays", mttr.meanDays());
        remediation.put("medianDays", mttr.medianDays());
        remediation.put("p90Days", mttr.p90Days());
        body.put("surface", Map.of(
                "remediation", remediation,
                "remediationTrend", surface.remediationTrend(principal, 12).stream().map(p -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("label", p.label());
                    entry.put("closed", p.closed());
                    entry.put("medianDays", p.medianDays());
                    return entry;
                }).toList(),
                "aging", surface.aging(principal).stream()
                        .map(b -> Map.of("label", b.label(), "findings", b.findings(),
                                "serious", b.serious())).toList(),
                "categories", surface.categories(principal).stream()
                        .map(c -> Map.of("code", c.code(), "open", c.open(), "closed", c.closed(),
                                "serious", c.serious())).toList(),
                "assetClasses", surface.assetClasses(principal).stream()
                        .map(a -> Map.of("code", a.code(), "label", a.label(), "total", a.total(),
                                "internetFacing", a.internetFacing(),
                                "unclassified", a.unclassified())).toList(),
                "growth", surface.growth(principal, 12).stream()
                        .map(g -> Map.of("label", g.label(), "added", g.added(),
                                "cumulative", g.cumulative())).toList(),
                "internetFacing", surface.internetFacing(principal, 10).stream().map(a -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", a.id());
                    entry.put("name", a.name());
                    entry.put("typeCode", a.typeCode());
                    entry.put("criticality", a.criticality());
                    entry.put("open", a.open());
                    entry.put("serious", a.serious());
                    // Null means never assessed, which is the row that matters most in this list.
                    entry.put("lastAssessedAt", a.lastAssessedAt());
                    return entry;
                }).toList()));
        body.put("posture", posture);
        body.put("observations", observations);
        body.put("kpis", kpis);
        body.put("coverage", coverage);
        body.put("severity", severityRows);
        // The trend is measured only where the estate has findings at all. Six weeks of zeros drawn
        // as a flat line at the bottom of a chart reads as six weeks of nothing going wrong.
        body.put("trend", Map.of("weeks", trendRows, "measured", findingsTotal > 0));
        body.put("recent", recentRows);
        body.put("requests", Map.of("total", requests.total(), "open", requests.open(),
                "overdue", requests.overdue(), "unassigned", requests.unassigned(),
                "closedThirtyDays", requests.closedThirtyDays()));
        body.put("estate", Map.of("applications", estate.applications(),
                "applicationsReviewed", estate.applicationsReviewed(),
                "assets", estate.assets(), "assetsWithSbom", estate.assetsWithSbom(),
                "nodes", estate.nodes()));
        return json(body);
    }

    /**
     * One posture figure, with everything needed to decide whether to believe it.
     *
     * <p>{@code posture} is <b>null</b> at {@code INSUFFICIENT} confidence rather than sent and
     * flagged. {@code PRD-RSK-027} requires such a figure to be presented as a coverage gap, and a
     * number that is present in the payload will eventually be rendered by somebody who did not read
     * the flag beside it — a chart tooltip, an export, a copied component. Withholding the value is
     * the only version of the rule that survives the interface being extended, and it is the same
     * mechanism {@link #measure} already uses.
     *
     * <p>The four §10.2 components travel with it so a unit that disputes its number can be shown
     * which of severity pressure, concentration, commitment or coverage produced it.
     */
    private static Map<String, Object> riskPosture(aspm.app.resource.RiskScoring.Posture p) {
        Map<String, Object> entry = new LinkedHashMap<>();
        if (p == null) {
            // Distinct from a zero posture. The caller reaches no organization at all, which is an
            // access finding, not a risk finding.
            entry.put("scoped", false);
            return entry;
        }
        entry.put("scoped", true);
        entry.put("id", p.id());
        entry.put("name", p.name());
        entry.put("posture", p.presentable() ? Integer.valueOf(p.posture()) : null);
        entry.put("band", p.presentable() ? p.postureBand() : null);
        entry.put("confidence", p.confidence().name());
        entry.put("assets", p.assets());
        entry.put("measuredAssets", p.measuredAssets());
        entry.put("findings", p.findings());
        entry.put("worstScore", p.worstScore());
        entry.put("components", Map.of(
                "severityPressure", round(p.severityPressure()),
                "concentration", round(p.concentration()),
                "slaHealth", round(p.slaHealth()),
                "coveragePenalty", round(p.coveragePenalty())));
        return entry;
    }

    /** Two decimals. A component reported to fifteen digits invites precision nobody has. */
    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /**
     * One headline figure, with the population it was computed over.
     *
     * <p>{@code value} is <b>absent</b> where the measure is unmeasured. See {@link #overview} for why
     * the state is decided on this side of the wire.
     */
    private static Map<String, Object> measure(String key, long value, int measured, int inScope,
            String href) {
        Optional<aspm.module.insight.domain.PresentationState> state =
                aspm.module.insight.domain.PresentationState.forMeasure(measured, inScope, false);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("key", key);
        entry.put("value", state.isPresent() ? null : Long.valueOf(value));
        entry.put("state", state.map(Enum::name).orElse(null));
        entry.put("measured", measured);
        entry.put("inScope", inScope);
        entry.put("href", href);
        return entry;
    }

    /**
     * One coverage bar.
     *
     * <p>No percentage is computed here and none is sent. A ratio over an unmeasured population is
     * the figure this whole mechanism exists to withhold, and the interface needs both numbers anyway
     * to render the qualifier beside the bar.
     */
    private static Map<String, Object> coverage(String key, int measured, int inScope, String href) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("key", key);
        entry.put("measured", measured);
        entry.put("inScope", inScope);
        entry.put("href", href);
        return entry;
    }

    // ==============================================================================================
    // GET /api/ui/workload
    // ==============================================================================================

    /**
     * The workload dashboard, as JSON.
     *
     * <p>The per-member section is <b>absent</b> from this payload unless the caller holds
     * {@code cap.member.read.all} — not present-and-empty, and not present-and-masked. ADR-047
     * requires a restricted field to be absent from the representation, and this is the case that
     * makes the rule concrete: a key with an empty array confirms the data exists and lets a client
     * report "0 members", while a masked row confirms how many there are.
     *
     * <p>{@code PRD-CAP-013} adds that the permission is never implied by seniority, which is why the
     * test is {@code holds(…)} on the explicit code and not a role or a scope check.
     */
    public Dispatcher.Response workload(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        aspm.app.resource.WorkloadQuery query = new aspm.app.resource.WorkloadQuery(dataSource);

        Map<String, Long> headline = query.headline(principal);
        long clocks = query.serviceLevelClocks(principal);
        long capacityMembers = query.membersWithCapacity(principal);

        List<Map<String, Object>> flow = new ArrayList<>();
        for (var bucket : query.flow(principal)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("category", bucket.category());
            entry.put("state", bucket.state());
            entry.put("count", bucket.count());
            entry.put("clockRunning", bucket.clockRunning());
            flow.add(entry);
        }

        List<Map<String, Object>> stages = new ArrayList<>();
        for (var stage : query.stageTimes(principal)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("state", stage.state());
            entry.put("transitions", stage.transitions());
            entry.put("averageHours", stage.averageHours());
            entry.put("clockRunning", stage.clockRunning());
            stages.add(entry);
        }

        List<Map<String, Object>> waiting = new ArrayList<>();
        for (var item : query.waiting(principal)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("requestCode", item.requestCode());
            entry.put("state", item.state());
            entry.put("since", item.since());
            entry.put("reason", item.reason());
            entry.put("hoursWaiting", item.hoursWaiting());
            waiting.add(entry);
        }

        List<Map<String, Object>> findings = new ArrayList<>();
        for (var load : query.findingLoad(principal)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("severity", load.severity());
            entry.put("open", load.open());
            entry.put("unassigned", load.unassigned());
            entry.put("overThirtyDays", load.overThirtyDays());
            findings.add(entry);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("headline", headline);
        body.put("flow", flow);
        body.put("stages", stages);
        body.put("waiting", waiting);
        body.put("findings", findings);
        // Named counts rather than a rendered state, so the interface can say WHICH measurement is
        // missing. "Not measured" with no reason is a dead end for the person who could fix it.
        body.put("serviceLevelClocks", clocks);
        body.put("membersWithCapacity", capacityMembers);

        if (principal != null && principal.holds(WorkloadPage.INDIVIDUAL_PERMISSION)) {
            List<Map<String, Object>> members = new ArrayList<>();
            for (var member : query.memberLoad(principal)) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("principalId", member.principalId());
                entry.put("assignedFindings", member.assignedFindings());
                entry.put("assignedRequests", member.assignedRequests());
                members.add(entry);
            }
            body.put("members", members);
        }
        return json(body);
    }

    /**
     * {@code GET /api/ui/workload/analytics} — the management view.
     *
     * <p>Everything is windowed by {@code from} and {@code to}, defaulting to the last ninety days.
     * The per-person series are behind {@code cap.member.read.all} and are <b>absent</b> from the
     * payload without it (ADR-047), never empty — an empty array confirms the data exists and lets a
     * client report "0 assessors", which is the disclosure the permission exists to prevent.
     */
    public Dispatcher.Response analytics(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        aspm.app.resource.WorkloadAnalytics analytics =
                new aspm.app.resource.WorkloadAnalytics(dataSource);

        java.time.LocalDate to = date(request.query().get("to"),
                java.time.LocalDate.now(java.time.ZoneOffset.UTC));
        java.time.LocalDate from = date(request.query().get("from"), to.minusDays(90));
        if (from.isAfter(to)) {
            return new Dispatcher.Response(400, Map.of("code", "RANGE_INVALID",
                    "message", "the start of the range is after its end"), Map.of());
        }
        String granularity = "month".equalsIgnoreCase(request.query().get("granularity"))
                ? "month" : "week";

        // The window before this one, of the same length. A headline with no baseline makes the
        // reader supply one from memory, and the one they supply is unfalsifiable.
        long span = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        java.time.LocalDate priorTo = from.minusDays(1);
        java.time.LocalDate priorFrom = priorTo.minusDays(span - 1);
        var now = analytics.headline(principal, from, to);
        var before = analytics.headline(principal, priorFrom, priorTo);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", from.toString());
        body.put("to", to.toString());
        body.put("granularity", granularity);
        body.put("headline", Map.of(
                "requestsRaised", now.requestsRaised(), "requestsClosed", now.requestsClosed(),
                "findingsFound", now.findingsFound(), "findingsClosed", now.findingsClosed()));
        body.put("previous", Map.of(
                "from", priorFrom.toString(), "to", priorTo.toString(),
                "requestsRaised", before.requestsRaised(),
                "requestsClosed", before.requestsClosed(),
                "findingsFound", before.findingsFound(),
                "findingsClosed", before.findingsClosed()));
        body.put("backlog", analytics.backlog(principal, from, to, granularity).stream()
                .map(b -> Map.of("label", b.label(), "open", b.open(), "serious", b.serious()))
                .toList());
        body.put("requestTrend", analytics.requestTrend(principal, from, to, granularity).stream()
                .map(p -> Map.of("label", p.label(), "opened", p.opened(), "closed", p.closed()))
                .toList());
        body.put("findingTrend", analytics.findingTrend(principal, from, to, granularity).stream()
                .map(p -> Map.of("label", p.label(), "critical", p.critical(), "high", p.high(),
                        "medium", p.medium(), "other", p.other()))
                .toList());
        body.put("escaped", analytics.escapedToProduction(principal, from, to).stream()
                .map(e -> Map.of("label", e.label(), "escaped", e.escaped(), "total", e.total()))
                .toList());
        body.put("byTrigger", slices(analytics.requestsByTrigger(principal, from, to)));
        body.put("aging", slices(analytics.agingProfile(principal)));
        body.put("retestQueue", slices(analytics.retestQueue(principal)));
        body.put("coverage", analytics.organizationCoverage(principal).stream()
                .map(c -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("nodeId", c.nodeId());
                    entry.put("nodeName", c.nodeName());
                    entry.put("path", c.path());
                    entry.put("applications", c.applications());
                    entry.put("assessedThisYear", c.assessedThisYear());
                    entry.put("reviewsThisYear", c.reviewsThisYear());
                    entry.put("neverAssessed", c.neverAssessed());
                    return entry;
                }).toList());

        // Two counts, not a percentage. See WorkloadAnalytics#serviceLevel: attainment over zero
        // clocks is 100%, and publishing that is the PP-1 failure at its most flattering.
        var sla = analytics.serviceLevel(principal);
        body.put("serviceLevel", Map.of("policies", sla.policies(), "clocks", sla.clocks(),
                "measurable", sla.measurable()));
        // The tenant's own definition of a service level: did the request meet the date it was
        // given. Coarser than the policy machinery above and real today, which beats precise and
        // absent. Both are reported so nobody confuses them.
        body.put("attainment", analytics.dueDateAttainment(principal, from, to, granularity)
                .stream().map(a -> Map.of("label", a.label(), "met", a.met(),
                        "missed", a.missed(), "noDate", a.noDate(),
                        "stillOpenLate", a.stillOpenLate()))
                .toList());
        // Per team. Aggregate, not per person — a team of five is not an individual measure, so it
        // sits outside the cap.member.read.all gate below.
        body.put("byTeam", slices(analytics.requestsByTeam(principal, from, to)));
        body.put("findingsByTeam", slices(analytics.findingsByTeam(principal, from, to)));

        // PRD-CAP-013: individual measures need the explicit permission, never seniority. Absent
        // rather than empty.
        if (principal != null && principal.holds(WorkloadPage.INDIVIDUAL_PERMISSION)) {
            Map<String, Object> individual = new LinkedHashMap<>();
            individual.put("byAssessor", slices(analytics.requestsByAssessor(principal, from, to)));
            individual.put("byCoverageArea", slices(analytics.byCoverageArea(principal, from, to)));
            individual.put("seriousFindings",
                    slices(analytics.seriousFindingsByPerson(principal, from, to)));
            individual.put("cycleTime", analytics.cycleTimeByAssessor(principal, from, to).stream()
                    .map(c -> Map.of("key", c.key(), "label", c.label(), "closed", c.closed(),
                            "meanDays", c.meanDays(), "medianDays", c.medianDays()))
                    .toList());
            body.put("individual", individual);
        }
        return json(body);
    }

    private static List<Map<String, Object>> slices(
            List<aspm.app.resource.WorkloadAnalytics.Slice> source) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var slice : source) {
            out.add(Map.of("key", slice.key(), "label", slice.label(), "value", slice.value(),
                    "population", slice.population()));
        }
        return out;
    }

    private static java.time.LocalDate date(String value, java.time.LocalDate fallback) {
        try {
            return value == null || value.isBlank() ? fallback : java.time.LocalDate.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            return fallback;
        }
    }

    /** {@code GET /api/ui/teams} — the roster, and who is on no team. */
    public Dispatcher.Response teams(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        aspm.app.resource.TeamService service = new aspm.app.resource.TeamService(dataSource);
        List<Map<String, Object>> teams = new ArrayList<>();
        for (var team : service.teams(principal)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", team.id().toString());
            entry.put("name", team.name());
            entry.put("description", team.description());
            entry.put("active", team.active());
            entry.put("members", team.members());
            teams.add(entry);
        }
        List<Map<String, Object>> people = new ArrayList<>();
        for (var member : service.assignable(principal)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("principalId", member.principalId().toString());
            entry.put("displayName", member.displayName());
            entry.put("username", member.username());
            entry.put("teamId", member.teamId() == null ? null : member.teamId().toString());
            entry.put("teamName", member.teamName());
            people.add(entry);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("teams", teams);
        body.put("people", people);
        body.put("mayManage", principal != null
                && principal.holds(aspm.app.resource.TeamService.MANAGE));
        return json(body);
    }

    /** {@code POST /api/ui/teams} — create one. */
    public Dispatcher.Response createTeam(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        Map<String, Object> payload = request.body().orElse(Map.of());
        var created = new aspm.app.resource.TeamService(dataSource)
                .create(principal, text(payload.get("name")), text(payload.get("description")));
        if (created.isEmpty()) {
            // A name already in use, or an empty one. Said as a refusal the person can act on
            // rather than a constraint violation surfacing as a 500.
            return new Dispatcher.Response(422, Map.of("code", "TEAM_NAME_UNAVAILABLE",
                    "message", "give the team a name that is not already in use"), Map.of());
        }
        return json(Map.of("id", created.orElseThrow().toString()));
    }

    /** {@code POST /api/ui/teams/{id}/retire}. */
    public Dispatcher.Response retireTeam(Dispatcher.Request request) throws Exception {
        UUID id = uuid(request.pathVariables().get("id"));
        if (id == null) {
            return Dispatcher.Response.notFound();
        }
        return json(Map.of("retired",
                new aspm.app.resource.TeamService(dataSource).retire(request.principal(), id)));
    }

    /**
     * {@code POST /api/ui/teams/members} — move somebody onto a team, or off every team.
     *
     * <p>A move rather than an add, because membership is exclusive: a person belongs to one live
     * team so that per-team charts can be summed (V034).
     */
    public Dispatcher.Response assignTeamMember(Dispatcher.Request request) throws Exception {
        Map<String, Object> payload = request.body().orElse(Map.of());
        UUID who = uuid(text(payload.get("principal")));
        if (who == null) {
            return new Dispatcher.Response(400,
                    Map.of("code", "PRINCIPAL_REQUIRED", "message", "name the person"), Map.of());
        }
        return json(Map.of("assigned", new aspm.app.resource.TeamService(dataSource)
                .assign(request.principal(), who, uuid(text(payload.get("team"))))));
    }

    // ==============================================================================================
    // GET /api/ui/composition
    // ==============================================================================================

    /**
     * Dependency coverage, as JSON. DOC-22 §9.
     *
     * <p>{@code PRD-SBM-056} is the requirement this endpoint exists to honour: an asset that never
     * submitted an SBOM is present with {@code submitted: false}, never absent and never a row of
     * zeros. An absent row reads as absence of problems and a zero reads as a clean application, and
     * both are the PP-1 failure with different punctuation.
     *
     * <p>{@code submitted} is derived from {@code latestSnapshotAt}, never from {@code quality}.
     * {@code sbom_coverage_state.quality} is NOT NULL with a default of {@code REJECTED}, so every
     * asset carrying a coverage row has a quality whether or not it ever submitted anything — reading
     * absence off that column once reported seven assets as submitted when two had.
     */
    public Dispatcher.Response composition(Dispatcher.Request request) throws Exception {
        Principal principal = request.principal();
        List<Map<String, Object>> rows = new aspm.app.resource.SbomIngestion(dataSource)
                .coverage(principal);

        List<Map<String, Object>> out = new ArrayList<>();
        int submitted = 0;
        int current = 0;
        for (Map<String, Object> row : rows) {
            boolean hasSnapshot = row.get("latest_snapshot_at") != null;
            boolean above = hasSnapshot && "ABOVE_WARNING".equals(row.get("quality"));
            if (hasSnapshot) {
                submitted++;
            }
            if (above) {
                current++;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("assetId", row.get("asset_id"));
            entry.put("name", row.get("display_name"));
            entry.put("submitted", hasSnapshot);
            entry.put("quality", row.get("quality"));
            // Null rather than zero where nothing was submitted. A component count of 0 on an asset
            // nobody scanned is a dependency-free application, which is not a thing.
            entry.put("componentCount", hasSnapshot ? row.get("component_count") : null);
            entry.put("qualityScore", hasSnapshot ? row.get("quality_score") : null);
            entry.put("ecosystems", row.get("covered_ecosystems"));
            entry.put("latestSnapshotAt",
                    row.get("latest_snapshot_at") == null ? null
                            : String.valueOf(row.get("latest_snapshot_at")));
            out.add(entry);
        }

        int inScope = rows.size();
        List<Map<String, Object>> kpis = new ArrayList<>();
        kpis.add(measure("composition.kpi.covered", current, submitted, inScope, "/composition"));
        kpis.add(measure("composition.kpi.submitted", submitted, submitted, inScope, "/composition"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rows", out);
        body.put("kpis", kpis);
        // The never-submitted count is a real measurement OF THE ASSET POPULATION, so it is a figure
        // even when it is the whole estate. What must never render as zero is coverage; a
        // never-submitted count of zero is good news this figure can legitimately report.
        body.put("neverSubmitted", inScope - submitted);
        body.put("assets", inScope);
        return json(body);
    }

    private static Map<String, Object> cadence(AssessmentService.Cadence cadence) {
        if (cadence == null) {
            return null;
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("completed", cadence.completed());
        entry.put("inFlight", cadence.inFlight());
        entry.put("abandoned", cadence.abandoned());
        entry.put("lastAt", cadence.lastAt());
        entry.put("intervalMonths", cadence.intervalMonths());
        entry.put("nextDueAt", cadence.nextDueAt());
        entry.put("status", cadence.status());
        return entry;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    // ==============================================================================================

    private static Map<String, Object> row(AssessmentService.Request row, Map<UUID, String> names,
            Map<String, String> stateLabels) {
        return row(row, names, stateLabels, List.of());
    }

    private static Map<String, Object> row(AssessmentService.Request row, Map<UUID, String> names,
            Map<String, String> stateLabels, List<Map<String, Object>> projects) {
        return row(row, names, stateLabels, projects, List.of());
    }

    private static Map<String, Object> row(AssessmentService.Request row, Map<UUID, String> names,
            Map<String, String> stateLabels, List<Map<String, Object>> projects,
            List<String> applications) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", row.id().toString());
        entry.put("code", row.code());
        entry.put("title", row.title());
        entry.put("state", row.state());
        entry.put("stateLabel", stateLabels.getOrDefault(row.state(), row.state()));
        entry.put("stateCategory", row.stateCategory());
        entry.put("createdAt", row.createdAt());
        entry.put("dueAt", row.dueAt());
        entry.put("closedAt", row.closedAt());
        entry.put("overdue", row.overdue());
        entry.put("orgNodeName", row.orgNodeName());
        entry.put("orgAncestors", row.orgAncestors());
        // The declared scope first, the assessment's resolved scope as the fallback. A request that
        // named a project has an application from the moment it is raised; one imported without a
        // declared scope still shows whatever the assessment resolved.
        entry.put("application", applications.isEmpty()
                ? row.primaryApplication() : applications.get(0));
        // Every application, where a full review spans more than one. The single name above is what
        // the column shows; this is what a reader needs before concluding the review missed something.
        entry.put("applications", applications);
        // The project, and every project for a full application review. Read from the request's own
        // scope table rather than from the assessment's — the assessment does not exist until an
        // assessor is named, which is why this column was empty on every new request.
        entry.put("projects", projects);
        entry.put("scopeAssets", row.scopeAssets());
        entry.put("triggerCode", row.triggerCode());
        entry.put("triggerLabel", row.triggerLabel());
        entry.put("triggerIsFullReview", row.triggerIsFullReview());
        entry.put("findingTotal", row.findingTotal());
        entry.put("findingOpen", row.findingOpen());
        entry.put("findingAccepted", row.findingAccepted());
        entry.put("findingSevereOpen", row.findingSevereOpen());
        entry.put("contact", row.contactId() == null ? null : names.get(row.contactId()));
        entry.put("assessor", row.leadId() == null ? null : names.get(row.leadId()));
        return entry;
    }

    private static Dispatcher.Response json(Object body) {
        return new Dispatcher.Response(200, body,
                Map.of("Content-Type", "application/json; charset=utf-8"));
    }

    private static UUID uuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
