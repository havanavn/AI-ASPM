package aspm.app.resource;

import aspm.app.api.RequestValidation;
import aspm.app.runtime.Dispatcher;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * {@code POST /api/v1/finding-imports}. Scan reports in, findings out. DOC-05, DOC-11.
 *
 * <h2>Class F, for the same reason the SBOM door is</h2>
 *
 * <p>A signed service credential and no human caller (ADR-004 forbids bearer API keys; the signature
 * covers the body digest, so the document cannot be swapped after signing). A browser session reaching
 * this endpoint would mean the pipeline door accepts a cookie, and the interactive equivalent — if one is
 * ever wanted — belongs on its own scoped write over the same ingestion code, which is the shape the
 * dependency upload already took.
 *
 * <h2>The response is the diagnosis</h2>
 *
 * <p>{@code PRD-API-038}: every warning is returned, not logged. The submitter is the only party who can
 * fix a scan report whose severities map to nothing, whose results carry no rule identity, or whose
 * locations are URLs rather than files — and they see the response. A dashboard the platform team reads
 * three weeks later is not a feedback loop.
 *
 * <p>Counts are returned by disposition ({@code PRD-ING-041}). "42 records processed" is the number that
 * says nothing: 42 ingested, 42 already known, and 42 held back need three different responses from
 * whoever owns the pipeline.
 */
public final class FindingImportEndpoint {

    private final FindingImport imports;
    private final SubmissionHealth health;

    public FindingImportEndpoint(DataSource dataSource) {
        this.imports = new FindingImport(Objects.requireNonNull(dataSource, "a data source is required"));
        this.health = new SubmissionHealth(dataSource);
    }

    /**
     * Submits one SARIF document.
     *
     * <p>Wrapped so that every exit records an outcome against the credential that made the call, for the
     * reason {@code PRD-SBM-024} gives about the SBOM door and which applies identically here: a
     * credential showing "last success three weeks ago, no failures" reads as "nobody has pushed lately"
     * when the truth may be "this pipeline has been rejected two hundred times", and those need opposite
     * responses. The recording never fails the submission — the findings are the record.
     */
    public Dispatcher.Response submit(Dispatcher.Request request) throws Exception {
        try {
            Dispatcher.Response response = submitInternal(request);
            if (response.status() < 300) {
                health.recordSuccess(request.principal());
            } else {
                Object code = response.body() instanceof Map<?, ?> m ? m.get("code") : null;
                health.recordFailure(request.principal(), "the scan report was rejected"
                        + (code == null ? "" : " (" + code + ")"));
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
            // Platform-authored text only. The message is ours; nothing from the request body is echoed
            // into a column an administrator screen renders.
            health.recordFailure(request.principal(),
                    "the submission was malformed: " + malformed.getMessage());
            throw malformed;
        }
    }

    private Dispatcher.Response submitInternal(Dispatcher.Request request) throws Exception {
        Map<String, Object> body = request.body().orElseThrow(
                () -> new IllegalArgumentException("a request body is required"));
        RequestValidation.rejectUnknownFields(
                java.util.Set.of("application", "project", "repository", "document"), body);

        String application = text(body.get("application"));
        String project = text(body.get("project"));
        String repository = text(body.get("repository"));
        if (repository == null || repository.isBlank()) {
            // The three-part address, and `repository` is the part that cannot be inferred. A scan report
            // is about code, code lives in a repository, and a report filed at application level would
            // attribute every finding to everything the application contains.
            throw new IllegalArgumentException(
                    "name the target: application, project and repository. The repository is required — "
                            + "a scan report describes code, and code that belongs to no repository "
                            + "cannot be attributed to one later.");
        }
        if (!(body.get("document") instanceof Map<?, ?> document)) {
            throw new IllegalArgumentException("document must be the SARIF object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> sarif = (Map<String, Object>) document;

        // The raw document, re-serialized from our own parse. That makes the hash a hash of the
        // normalization rather than of the bytes as submitted, and it is the honest option available: the
        // JSON reader does not retain offsets, so there is no way to recover the original slice here. The
        // consequence is bounded and worth stating — two pipelines emitting identical content with
        // different whitespace hash the SAME, which is what a content hash should do anyway.
        String raw = aspm.app.runtime.Json.write(sarif);

        // The idempotency key. The DISPATCHER already requires the header for this class and refuses the
        // request without one, so the fallback below is unreachable through the API — verified by trying
        // it, which is how the requirement was found rather than assumed. It stays because this endpoint
        // must not depend on a check that lives somewhere else to hold: if the class ever changes, a
        // submission still gets exactly-once on its content rather than a null key.
        String key = request.headers().getOrDefault("idempotency-key", "");
        if (key.isBlank()) {
            key = "sha256:" + java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }

        Object outcome = imports.submit(request.principal(), sarif, raw,
                new SbomIngestion.Target(application, project, repository), key);

        if (outcome instanceof FindingImport.Rejection rejection) {
            Map<String, Object> refused = new LinkedHashMap<>();
            refused.put("status", Integer.valueOf(422));
            refused.put("code", rejection.code());
            refused.put("message", rejection.detail());
            // The session id even on a rejection: it is the row that records the attempt, and a submitter
            // whose document was refused needs to be able to point at it.
            refused.put("import_session_id", rejection.sessionId().toString());
            return new Dispatcher.Response(422, refused, Map.of());
        }

        FindingImport.Report report = (FindingImport.Report) outcome;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("import_session_id", report.sessionId().toString());
        payload.put("accepted", Boolean.valueOf(report.createdNow()));
        payload.put("state", report.state());
        payload.put("target_asset_id", report.targetAssetId() == null ? null
                : report.targetAssetId().toString());
        // Whether the platform had never heard of this repository. A pipeline pointing at a misspelled
        // repository would otherwise silently create one and report success against it for months.
        payload.put("target_created_unclaimed", Boolean.valueOf(report.targetCreatedUnclaimed()));
        payload.put("records_extracted", Integer.valueOf(report.recordsExtracted()));
        payload.put("ingested", Integer.valueOf(report.ingested()));
        payload.put("already_known", Integer.valueOf(report.updated()));
        // Separated from `already_known` deliberately. A weakness that came back after somebody closed it
        // is the most important thing this endpoint can report, and folding it into "already known" is how
        // it would be missed.
        payload.put("reopened", Integer.valueOf(report.reopened()));
        payload.put("merged_within_document", Integer.valueOf(report.merged()));
        payload.put("quarantined", Integer.valueOf(report.quarantined()));
        payload.put("severity_mapping_gaps", Integer.valueOf(report.mappingGaps()));
        payload.put("tools", report.tools());
        payload.put("warnings", report.warnings());
        return new Dispatcher.Response(report.createdNow() ? 201 : 200, payload,
                Map.of("Location", "/api/v1/finding-imports/" + report.sessionId()));
    }

    private static String text(Object value) {
        return value instanceof String string ? string.strip() : null;
    }
}
