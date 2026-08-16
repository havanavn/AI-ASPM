package aspm.app.resource;

import aspm.app.api.RequestValidation;
import aspm.app.runtime.Dispatcher;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * SBOM submission and coverage. DOC-05 §17, ADR-023.
 *
 * <p>{@code POST /api/v1/sbom-submissions} is class <b>F</b> — service credential only. ADR-023 makes it
 * "the only automated ingestion path in v1", and ADR-004 requires a sender-constrained credential rather
 * than a bearer key: a pipeline token that can be replayed from anywhere is a pipeline token that will be.
 *
 * <p>{@code PRD-API-038} decides the response: the quality score and every warning, because "the
 * submitter is the only party who can fix a low-quality SBOM, and they see the response, not the log".
 */
public final class SbomEndpoint {

    private static String text(Object value) {
        return value instanceof String string ? string.strip() : null;
    }

    private final SbomIngestion ingestion;
    private final WebhookAlerts alerts;
    private final RescanService rescans;
    private final SubmissionHealth health;

    public SbomEndpoint(DataSource dataSource) {
        this.ingestion = new SbomIngestion(Objects.requireNonNull(dataSource));
        this.alerts = new WebhookAlerts(dataSource);
        this.rescans = new RescanService(dataSource);
        this.health = new SubmissionHealth(dataSource);
    }

    /**
     * {@code POST /api/v1/sbom-submissions}.
     *
     * <p>Wraps the real handler so that every exit records an outcome against the credential that made
     * the call ({@code PRD-SBM-024}). Recording only successes would be worse than recording nothing:
     * a dashboard showing "last success three weeks ago, no failures" reads as "nobody has pushed
     * lately" when the truth may be "this pipeline has been rejected two hundred times", and those need
     * opposite responses.
     *
     * <p>An {@code IllegalArgumentException} here is a malformed submission — a missing target, an
     * unknown field, a document that is not an object. Those are the commonest thing a new pipeline gets
     * wrong, they are reported to the caller, and before this they left no trace anybody could find
     * afterwards. Its message is platform-authored and safe to store; nothing from the request body is.
     *
     * <p>The recording never fails the submission. A bill of materials is the record, and losing one
     * because a counter could not be written would be the tail wagging the dog — the same reasoning
     * this method already applies to alert delivery.
     */
    public Dispatcher.Response submit(Dispatcher.Request request) throws Exception {
        try {
            Dispatcher.Response response = submitInternal(request);
            if (response.status() < 300) {
                health.recordSuccess(request.principal());
            } else {
                Object code = response.body() instanceof Map<?, ?> m ? m.get("code") : null;
                Object detail = response.body() instanceof Map<?, ?> m ? m.get("message") : null;
                health.recordFailure(request.principal(), "the document was rejected"
                        + (code == null ? "" : " (" + code + ")")
                        + (detail == null ? "" : ": " + detail));
            }
            return response;
        } catch (SbomIngestion.OutOfScopeTarget refused) {
            // Absence, not denial. PRD-API-036: a caller must not be able to tell a target they may
            // not reach from one that does not exist, or the difference maps the estate. Recorded as
            // a failure against the credential, because a pipeline addressing the wrong division is
            // a misconfiguration somebody has to see.
            health.recordFailure(request.principal(),
                    "the submission named a target this credential cannot reach");
            return Dispatcher.Response.notFound();
        } catch (IllegalArgumentException malformed) {
            health.recordFailure(request.principal(),
                    "the submission was malformed: " + malformed.getMessage());
            throw malformed;
        }
    }

    private Dispatcher.Response submitInternal(Dispatcher.Request request) throws Exception {
        Map<String, Object> body = request.body().orElseThrow(
                () -> new IllegalArgumentException("a request body is required"));
        RequestValidation.rejectUnknownFields(
                java.util.Set.of("artifact_reference", "application", "project", "repository",
                        "document"), body);

        // TWO WAYS TO NAME THE TARGET, and the second is the one a pipeline should use.
        //
        // `artifact_reference` is a single opaque string, and it made the caller responsible for
        // knowing an identifier the platform assigned. A build job knows three things instead — which
        // application it belongs to, which project delivers it, and which repository it is — and
        // those are stable across a rename of anything the platform generated.
        //
        // NAMING THE SAME THREE AGAIN REPLACES: the submission resolves to the same repository asset,
        // its snapshot becomes the latest, and every rollup reads the latest. That is what "replace"
        // means here and it is the only thing it can mean — INV-SBM-01 makes an accepted snapshot
        // immutable, so the previous one is superseded rather than overwritten, and the history of
        // what was true when remains readable (PP-5).
        String reference = text(body.get("artifact_reference"));
        String application = text(body.get("application"));
        String project = text(body.get("project"));
        String repository = text(body.get("repository"));
        if ((reference == null || reference.isBlank()) && (repository == null || repository.isBlank())) {
            throw new IllegalArgumentException(
                    "name the target: either artifact_reference, or application/project/repository");
        }
        if (!(body.get("document") instanceof Map<?, ?> document)) {
            throw new IllegalArgumentException("document must be the SBOM object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> sbom = (Map<String, Object>) document;

        // The content hash is over the document as submitted, so an identical resubmission is
        // recognised byte for byte (PRD-SBM-033). Re-serializing our own parse would hash a
        // normalization instead, and two pipelines emitting the same content differently formatted
        // would create two snapshots.
        String raw = aspm.app.runtime.Json.write(sbom);

        Object outcome = repository != null && !repository.isBlank()
                ? ingestion.submit(request.principal(), sbom, raw,
                        new SbomIngestion.Target(application, project, repository))
                : ingestion.submit(request.principal(), sbom, reference, raw);

        if (outcome instanceof SbomIngestion.Rejection rejection) {
            return new Dispatcher.Response(422, Map.of(
                    "status", Integer.valueOf(422),
                    "code", rejection.code(),
                    "message", rejection.detail()), Map.of());
        }

        SbomIngestion.Report report = (SbomIngestion.Report) outcome;
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("snapshot_id", report.snapshotId().toString());
        payload.put("created", Boolean.valueOf(report.createdNow()));
        payload.put("artifact_asset_id", report.artifactAssetId());
        payload.put("artifact_created_unclaimed", Boolean.valueOf(report.artifactCreatedUnclaimed()));
        payload.put("component_count", Integer.valueOf(report.componentCount()));
        payload.put("unmatchable_components", Integer.valueOf(report.unmatchableCount()));
        payload.put("quality_score", Integer.valueOf(report.quality()));
        // The other two sections, reported back for the same reason PRD-API-038 gives for the
        // warnings: the submitter is the only party who can fix a document whose vulnerability list
        // referenced components it did not declare, and they see the response, not the log.
        payload.put("advisories_recorded", Integer.valueOf(report.advisoryCount()));
        payload.put("affected_components", Integer.valueOf(report.affectedComponentCount()));
        payload.put("dependency_edges", Integer.valueOf(report.dependencyEdgeCount()));
        // Whether this superseded an earlier bill of materials for the same target, and which one.
        // A pipeline that thinks it created a new artifact when it replaced one is a pipeline whose
        // operator cannot tell a misconfigured reference from a working one.
        payload.put("replaced_snapshot_id", report.replacedSnapshotId());

        // AFTER the submission has committed, and outside its transaction. An alert that could not
        // be sent must never fail an ingestion — the bill of materials is the record, and losing it
        // because a chat server was down would be the tail wagging the dog. Failures are recorded in
        // alert_delivery, not raised here.
        alerts.publish(request.principal(),
                ingestion.newAdvisoryEvents(request.principal(), report.snapshotId()));
        payload.put("alerts_considered", Integer.valueOf(report.advisoryCount()));
        payload.put("ecosystems", report.ecosystems());
        // Every warning, not a summary. PRD-API-038 forbids reporting them only server-side.
        payload.put("warnings", report.warnings());
        return new Dispatcher.Response(report.createdNow() ? 201 : 200, payload,
                Map.of("Location", "/api/v1/sbom-snapshots/" + report.snapshotId()));
    }

    /**
     * {@code GET /api/v1/rescans/pending}. What the scanner should look at now, documents included.
     *
     * <p>Class F — service credential only, the same identity model a CI push uses. Returns nothing
     * when the schedule is off: the worker ticks on a fixed interval and the platform decides
     * whether anything is due, which is what keeps the schedule configurable in the dashboard.
     */
    public Dispatcher.Response pendingRescans(Dispatcher.Request request) throws Exception {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (var item : rescans.pending(request.principal())) {
            out.add(Map.of("snapshot_id", item.snapshotId(), "artifact", item.artifactName(),
                    "format", item.format(), "document", item.document()));
        }
        return Dispatcher.Response.ok(Map.of("items", out));
    }

    /**
     * {@code POST /api/v1/rescans/{id}}. The scanner's verdict for one snapshot.
     *
     * <p>The body is whatever the scanner emits — CycloneDX with vulnerabilities, Trivy JSON, or the
     * normalized array — because it is read by the same parser the submission path uses. A re-scan
     * writes only the advisory half: the components did not change, nobody built anything.
     */
    public Dispatcher.Response submitRescan(Dispatcher.Request request) throws Exception {
        java.util.UUID id;
        try {
            id = java.util.UUID.fromString(request.pathVariables().get("id"));
        } catch (RuntimeException e) {
            return Dispatcher.Response.notFound();
        }
        Map<String, Object> body = request.body().orElseThrow(
                () -> new IllegalArgumentException("a request body is required"));
        String scanner = String.valueOf(body.getOrDefault("scanner", "unknown"));
        String intelligence = body.get("intelligence_version") == null ? null
                : String.valueOf(body.get("intelligence_version"));
        @SuppressWarnings("unchecked")
        Map<String, Object> results = body.get("results") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();

        var fresh = rescans.submit(request.principal(), id, results, scanner, intelligence);
        // The whole reason the schedule exists: something new was found without anybody pushing.
        alerts.publish(request.principal(), fresh);
        return Dispatcher.Response.ok(Map.of("snapshot_id", id.toString(),
                "newly_detected", Integer.valueOf(fresh.size()),
                "alerted", Integer.valueOf(fresh.size())));
    }

    /** {@code GET /api/ui/rescan-schedule}. */
    public Dispatcher.Response rescanSchedule(Dispatcher.Request request) throws Exception {
        var schedule = rescans.schedule(request.principal());
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("enabled", Boolean.valueOf(schedule.enabled()));
        body.put("intervalHours", Integer.valueOf(schedule.intervalHours()));
        body.put("batchSize", Integer.valueOf(schedule.batchSize()));
        body.put("lastTickAt", schedule.lastTickAt());
        body.put("due", Long.valueOf(schedule.due()));
        // Reported, never hidden. These predate the document archive: their bytes were never kept,
        // so they can NEVER be re-scanned, and an empty queue would read as "all up to date".
        body.put("unscannable", Long.valueOf(schedule.unscannable()));
        body.put("total", Long.valueOf(schedule.total()));
        return Dispatcher.Response.ok(body);
    }

    /** {@code POST /api/ui/rescan-schedule}. */
    public Dispatcher.Response setRescanSchedule(Dispatcher.Request request) throws Exception {
        Map<String, Object> body = request.body().orElseThrow(
                () -> new IllegalArgumentException("a request body is required"));
        rescans.setSchedule(request.principal(),
                !Boolean.FALSE.equals(body.get("enabled")),
                body.get("intervalHours") instanceof Number n ? n.intValue() : 24,
                body.get("batchSize") instanceof Number b ? b.intValue() : 25);
        return rescanSchedule(request);
    }

    /** {@code GET /api/v1/coverage-states}. Includes assets that never submitted. */
    public Dispatcher.Response coverage(Dispatcher.Request request) throws Exception {
        List<Map<String, Object>> rows = ingestion.coverage(request.principal());
        return Dispatcher.Response.ok(Map.of("items", rows.stream().map(row -> {
            Map<String, Object> item = new java.util.LinkedHashMap<>(row);
            // The status a reader needs, computed once here rather than by each reader. NEVER_SUBMITTED
            // is a status and not a null — PRD-SBM-056 exists because absence reads as absence of
            // problems.
            // latest_snapshot_at, not quality: quality is NOT NULL with a default, so it is present
            // for an asset that never submitted and reading absence off it reports a scan that never
            // happened (PRD-SBM-056).
            item.put("status", row.get("latest_snapshot_at") == null ? "NEVER_SUBMITTED"
                    : "ABOVE_WARNING".equals(row.get("quality")) ? "CURRENT" : "PARTIAL");
            item.put("latest_snapshot_at", row.get("latest_snapshot_at") == null ? null
                    : String.valueOf(row.get("latest_snapshot_at")));
            return item;
        }).toList()));
    }
}
