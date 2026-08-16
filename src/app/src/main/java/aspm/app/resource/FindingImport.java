package aspm.app.resource;

import aspm.app.persistence.TenantConnections;
import aspm.app.runtime.Principal;
import aspm.module.ingestion.application.SarifParser;
import aspm.module.ingestion.domain.FindingFingerprint;
import aspm.module.ingestion.domain.FingerprintComputation;
import aspm.sharedkernel.TenantId;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Scan-report ingestion: a parsed document becomes findings. DOC-11, ADR-011.
 *
 * <h2>Why this is the second automated ingestion path and what that cost</h2>
 *
 * <p>ADR-023 made the SBOM push API "the only automated ingestion path in v1". That decision held while the
 * platform had no parser: a second door with no parser behind it is a second attack surface for nothing. It
 * does not hold now, and the reason it was made is the reason it has to change — the CI/CD findings surface
 * existed with no way to populate it, so every static-analysis result in the group lived in a pipeline log.
 * The supersession is recorded in DOC-19; this class is not the place to decide it, only the place that
 * depends on it.
 *
 * <h2>One matching path, not two</h2>
 *
 * <p>Target resolution goes through {@link SbomIngestion#resolveTarget}, the same method the bill-of-materials
 * door uses. ADR-011 puts one normalization and matching pipeline behind file import and native matching, and
 * the practical form of that here is narrow and important: both doors must agree on which asset the string
 * {@code "payments-api"} means. Two "find or create this repository" implementations agree until one of them
 * is fixed.
 *
 * <h2>What deduplication does and does not do</h2>
 *
 * <p>Identity is {@link FingerprintComputation#forCode}: rule, asset, normalized location, structural context.
 * A second submission of the same scan finds the same rows and moves {@code last_detected_at}; it does not
 * create findings and it does not touch triage state. Three cases are deliberately different from each other:
 *
 * <ul>
 *   <li><b>Still open.</b> The detection timestamp moves and nothing else. Re-detecting a finding that was
 *       never fixed is not a recurrence; treating it as one would make {@code recurrence_count} count scans.
 *   <li><b>Closed.</b> Reopened, and {@code recurrence_count} increments. A weakness that comes back after
 *       somebody closed it is the single most important thing this pipeline can tell anybody, and a
 *       re-detection that left it closed would make the closure permanent on the strength of one scan.
 *   <li><b>Risk accepted.</b> Left accepted. An acceptance is a decision with an owner and an expiry
 *       ({@code SEC-VUL} acceptance rules); a scanner re-reporting the weakness is not new information —
 *       the weakness being present is precisely what was accepted.
 * </ul>
 *
 * <h2>Absence of evidence</h2>
 *
 * <p>Nothing here closes a finding. A scan that no longer reports a weakness may have been narrowed, may have
 * failed silently, may have been run against a different revision — and product principle 1 forbids reading
 * any of those as "fixed". Closure stays a human transition with a verification method
 * ({@code INV-VUL-11}).
 */
public final class FindingImport {

    /** The document was refused whole. Nothing was ingested; the session records why. */
    public record Rejection(String code, String detail, UUID sessionId) {
    }

    /**
     * What the submission did, in the terms {@code PRD-ING-041} requires: by disposition, never a total.
     *
     * @param warnings everything the submitter should act on — mapping gaps, held records, an unarchived
     *     document. Returned rather than logged, because they are the only party who can fix any of it
     *     ({@code PRD-API-038})
     */
    public record Report(UUID sessionId, boolean createdNow, UUID targetAssetId,
            boolean targetCreatedUnclaimed, String state, int recordsExtracted, int ingested,
            int updated, int reopened, int merged, int quarantined, int mappingGaps,
            Set<String> tools, List<String> warnings) {
    }

    private final DataSource dataSource;
    private final SbomIngestion sboms;
    private final ObjectStore objects = new ObjectStore(System.getenv());

    /**
     * An import is the platform's highest-volume write and the one whose provenance matters most: every
     * finding it creates is evidence somebody will later act on, and DOC-14 lists import lifecycle among
     * the events that must be recoverable. One event per SESSION, not per finding — a chained event per
     * ingested record would make the trail's size the scan's size and bury the six numbers that answer
     * the question anybody asks of it.
     */
    private final aspm.app.audit.AuditTrail audit =
            new aspm.app.audit.AuditTrail(java.time.Clock.systemUTC());

    public FindingImport(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
        this.sboms = new SbomIngestion(dataSource);
    }

    /**
     * Ingests one SARIF document.
     *
     * @param rawDocument the document as submitted. Hashed as received rather than re-serialized: two
     *     pipelines emitting the same content formatted differently must be recognised as the same
     *     submission, and hashing our own parse would hash a normalization instead
     * @param idempotencyKey the submitter's key. A retry returns the FIRST session's outcome unchanged
     *     rather than ingesting again
     * @return a {@link Report}, or a {@link Rejection} where the document was refused whole
     */
    public Object submit(Principal principal, Map<String, Object> document, String rawDocument,
            SbomIngestion.Target target, String idempotencyKey) throws SQLException {
        Objects.requireNonNull(principal, "a principal is required");
        Objects.requireNonNull(document, "a document is required");
        Objects.requireNonNull(target, "a target is required");
        Objects.requireNonNull(idempotencyKey, "an idempotency key is required");

        byte[] documentBytes = rawDocument.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] documentHash = sha256(documentBytes);

        try (Connection connection = open(principal)) {
            // The retry check comes FIRST, before parsing and before any write. A CI job that timed out
            // waiting for a response and retried must get the first answer back, not a second ingestion:
            // the second would re-detect every finding, and re-detection of a closed finding reopens it
            // and increments a recurrence count. A retry would then manufacture "this keeps coming back".
            Report existing = existingSession(connection, idempotencyKey);
            if (existing != null) {
                return existing;
            }

            SarifParser.Parsed parsed;
            try {
                parsed = SarifParser.parse(document, documentBytes.length);
            } catch (SarifParser.Rejected rejected) {
                // A FAILED session is written even though nothing was ingested, and that is the point of
                // PRD-ING-020: a submission that vanished without trace is indistinguishable from one
                // nobody sent, and the two need opposite responses from whoever owns the pipeline.
                UUID failed = recordFailure(connection, principal, idempotencyKey, documentHash,
                        documentBytes.length, rejected.code() + ": " + rejected.getMessage());
                return new Rejection(rejected.code(), rejected.getMessage(), failed);
            }

            List<String> warnings = new ArrayList<>(parsed.gaps());

            // Archived BEFORE the transaction, because the reference is written into every finding row and
            // an accepted finding whose raw record cannot be produced is a retention promise nobody kept
            // (PRD-ING-022). Best effort and stated: a NULL document_ref records exactly which sessions
            // cannot produce their raw records rather than leaving it unknowable.
            String archiveKey = "sarif/" + principal.tenantId() + "/"
                    + java.util.HexFormat.of().formatHex(documentHash) + ".sarif.json";
            String documentRef = objects.put(ObjectStore.SBOM_BUCKET, archiveKey, documentBytes,
                    "application/json").orElse(null);
            if (documentRef == null && objects.configured()) {
                warnings.add("DOCUMENT_NOT_ARCHIVED: the raw record of each finding names the session "
                        + "rather than a retrievable location");
            }

            connection.setAutoCommit(false);
            try {
                SbomIngestion.Resolved resolved = sboms.resolveTarget(connection, principal, target);
                Scope scope = scopeOf(connection, resolved.assetId());
                UUID sessionId = openSession(connection, principal, idempotencyKey, documentHash,
                        documentBytes.length, documentRef, resolved.assetId(), parsed.resultCount());

                Map<Integer, UUID> severities = severitiesByOrdinal(connection);
                Counts counts = new Counts();
                Set<String> digestsThisDocument = new HashSet<>();

                for (SarifParser.Result result : parsed.results()) {
                    FindingFingerprint fingerprint = FingerprintComputation.forCode(
                            new TenantId(principal.tenantId()), result.ruleIdentity(), scope.identityKey(),
                            result.normalizedLocation(), result.structuralContextHash());

                    // Merged WITHIN the document, counted once. Two results that reduce to one identity
                    // are one weakness reported twice — CODE identity excludes the line number by design
                    // (DOC-03 §10.2), so two hits of one rule in one file with the same structural context
                    // ARE the same finding. Counting them separately would report a recurrence for a
                    // document nobody resubmitted.
                    if (!digestsThisDocument.add(fingerprint.digestHex())) {
                        counts.merged++;
                        continue;
                    }

                    UUID severityId = result.severityOrdinal() == null ? null
                            : severities.get(result.severityOrdinal());
                    if (result.severityOrdinal() != null && severityId == null) {
                        // The ordinal is structural; the SCALE is tenant data (ADR-027). A tenant whose
                        // scale has three levels has no ordinal 4, and the honest answer is an ungraded
                        // finding and a reported gap — not the nearest level, which would be a severity
                        // the tool never gave (PRD-ING-040).
                        warnings.add("result " + result.index() + ": severity ordinal "
                                + result.severityOrdinal() + " is not defined in this tenant's severity "
                                + "scale, so the finding is recorded ungraded");
                    }

                    Upsert upsert = upsert(connection, principal, result, fingerprint, severityId,
                            sessionId, documentRef, scope);
                    switch (upsert.disposition()) {
                        case INSERTED -> counts.ingested++;
                        case REOPENED -> counts.reopened++;
                        default -> counts.updated++;
                    }
                    retainInputs(connection, upsert.findingId(), fingerprint);
                    linkAsset(connection, principal, upsert.findingId(), resolved.assetId(), result);
                }

                int held = 0;
                for (SarifParser.Held record : parsed.quarantined()) {
                    quarantine(connection, principal, sessionId, record);
                    held++;
                }
                if (held > 0) {
                    warnings.add(held + " record(s) held in quarantine and not ingested; each names the "
                            + "field that failed and keeps its raw content so it can be corrected without "
                            + "the source file");
                }

                int gaps = parsed.gaps().size();
                String state = held > 0 ? "COMPLETED_WITH_QUARANTINE" : "COMPLETED";
                closeSession(connection, sessionId, state, counts, held, gaps);
                // IMPORT_COMPLETED, in the transaction that completed it. The counts are the payload
                // because they are what a later question actually asks — "did the scan that ran on the
                // fourteenth find anything, and did any of it fail to parse" is answered by these six
                // numbers and by nothing else in the row.
                audit.event(connection, principal,
                        aspm.kernel.audit.contract.AuditEventType.IMPORT_COMPLETED,
                        sessionId, resolved.scopeNodeId(), java.util.Map.of(
                                "asset_id", resolved.assetId().toString(),
                                "state", state,
                                "results", Integer.valueOf(parsed.resultCount()),
                                "ingested", Integer.valueOf(counts.ingested),
                                "updated", Integer.valueOf(counts.updated),
                                "reopened", Integer.valueOf(counts.reopened),
                                "quarantined", Integer.valueOf(held),
                                "tools", java.util.List.copyOf(parsed.toolNames())));
                connection.commit();

                return new Report(sessionId, true, resolved.assetId(), resolved.createdNow(), state,
                        parsed.resultCount(), counts.ingested, counts.updated, counts.reopened,
                        counts.merged, held, gaps, parsed.toolNames(), List.copyOf(warnings));
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    // ----------------------------------------------------------------------------------------------

    private static final class Counts {
        private int ingested;
        private int updated;
        private int reopened;
        private int merged;
    }

    private enum Disposition { INSERTED, UPDATED, REOPENED }

    private record Upsert(UUID findingId, Disposition disposition) {
    }

    /** The target asset's identity and its scope descriptors, denormalized onto every finding it carries. */
    private record Scope(String identityKey, UUID nodeId, java.sql.Array ancestorPath, UUID nodeTypeId,
            UUID criticalityId, Long hierarchyVersion) {
    }

    /**
     * Reads the asset's identity key and scope descriptors.
     *
     * <p>The IDENTITY KEY is what goes into the fingerprint, not the display name. A repository renamed in
     * the interface must not change the identity of every finding on it — that would orphan the triage state
     * of the whole repository on a rename, which is the "too tight" failure DOC-03 §10.2 names.
     */
    private static Scope scopeOf(Connection connection, UUID assetId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT identity_key, scope_node_id, scope_ancestor_path, scope_node_type_id, "
                        + "scope_criticality_id, scope_hierarchy_ver FROM asset WHERE id = ?")) {
            statement.setObject(1, assetId);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    throw new IllegalStateException("the resolved target asset disappeared mid-transaction");
                }
                Object version = results.getObject(6);
                return new Scope(results.getString(1), results.getObject(2, UUID.class),
                        results.getArray(3), results.getObject(4, UUID.class),
                        results.getObject(5, UUID.class),
                        version == null ? null : Long.valueOf(((Number) version).longValue()));
            }
        }
    }

    /**
     * The tenant's severity scale, by ordinal.
     *
     * <p>Only ACTIVE levels. A retired level is not a severity a new finding may be given — it exists so
     * historical findings still resolve, and offering it to an import would resurrect a vocabulary the
     * tenant retired.
     */
    private static Map<Integer, UUID> severitiesByOrdinal(Connection connection) throws SQLException {
        Map<Integer, UUID> byOrdinal = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT ordinal, id FROM severity_level WHERE lifecycle_state = 'ACTIVE'")) {
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    byOrdinal.put(Integer.valueOf(results.getInt(1)), results.getObject(2, UUID.class));
                }
            }
        }
        return byOrdinal;
    }

    /**
     * Inserts the finding, or recognises it and moves its detection timestamp.
     *
     * <p>{@code ON CONFLICT DO NOTHING} first, then an update where nothing was inserted. Two statements in
     * the already-known case and one in the new-finding case.
     *
     * <p><b>It was one statement with {@code DO UPDATE ... RETURNING (xmax = 0)}</b> — the usual way to ask
     * "did that insert or update" — and Postgres refuses it here: {@code finding} is partitioned, and
     * "cannot retrieve a system column in this context" is what a partitioned table answers when a
     * {@code RETURNING} clause reads {@code xmax}. Found by running it, not by reading it. The shape below
     * needs no system column, and it is correct under concurrency for the same reason the single statement
     * was: two pipelines pushing the same repository at once cannot both insert, and the one that loses the
     * race finds the row in the update.
     *
     * <p>The update sets the detection timestamp, the session and the tool version, and <b>nothing else</b>.
     * It does not touch the reported severity (immutable by trigger and by {@code INV-VUL-08}), the scope
     * descriptors (immutable by trigger), the assignee, or any state a person set. An import is evidence that
     * a weakness is still there; it is not a licence to overwrite what somebody decided about it.
     */
    private Upsert upsert(Connection connection, Principal principal, SarifParser.Result result,
            FindingFingerprint fingerprint, UUID severityId, UUID sessionId, String documentRef,
            Scope scope) throws SQLException {
        UUID findingId = null;
        String lifecycle = null;
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO finding (tenant_id, fingerprint_digest, fingerprint_algorithm_version,
                        finding_class, title, description, reported_severity_id, reported_severity_raw,
                        state, lifecycle_state, source_tool, source_tool_version, source_rule_identity,
                        source_import_session_id, raw_source_record_ref, assessment_context,
                        first_detected_at, last_detected_at, created_by, updated_by,
                        scope_node_id, scope_ancestor_path, scope_node_type_id, scope_criticality_id,
                        scope_hierarchy_ver, scope_resolved_at)
                VALUES (current_tenant_id(), ?, ?, 'CODE', ?, ?, ?, ?, 'OPEN', 'OPEN', ?, ?, ?, ?, ?,
                        'AUTOMATED_SCAN', now(), now(), ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (tenant_id, fingerprint_algorithm_version, fingerprint_digest) DO NOTHING
                RETURNING id, lifecycle_state
                """)) {
            int at = 1;
            statement.setBytes(at++, fingerprint.digest());
            statement.setInt(at++, fingerprint.algorithmVersion());
            statement.setString(at++, result.title());
            statement.setString(at++, description(result));
            statement.setObject(at++, severityId);
            statement.setString(at++, result.reportedSeverityRaw());
            // The TOOL, not the parser. A finding says semgrep found it; the parser is recorded on the
            // session. Conflating them would make every finding in the estate say "sarif".
            statement.setString(at++, result.toolName() == null ? "unknown-scanner" : result.toolName());
            statement.setString(at++, result.toolVersion());
            statement.setString(at++, result.ruleIdentity());
            statement.setObject(at++, sessionId);
            statement.setString(at++, rawRecordRef(documentRef, sessionId, result));
            statement.setObject(at++, principal.principalId());
            statement.setObject(at++, principal.principalId());
            statement.setObject(at++, scope.nodeId());
            statement.setArray(at++, scope.ancestorPath());
            statement.setObject(at++, scope.nodeTypeId());
            statement.setObject(at++, scope.criticalityId());
            if (scope.hierarchyVersion() == null) {
                statement.setNull(at++, java.sql.Types.BIGINT);
            } else {
                statement.setLong(at++, scope.hierarchyVersion().longValue());
            }
            try (ResultSet results = statement.executeQuery()) {
                // ONE call to next(), and the row is either there or the insert conflicted. An earlier
                // version called it twice — a leftover from the single-statement upsert this replaced —
                // so every insert consumed its own returned row and then reported "no row", and every new
                // finding was counted as already known. The rows were correct; only the counts lied,
                // which is the kind of defect a response body hides until somebody reconciles it.
                if (results.next()) {
                    findingId = results.getObject(1, UUID.class);
                    lifecycle = results.getString(2);
                }
            }
        }
        if (findingId != null) {
            return new Upsert(findingId, Disposition.INSERTED);
        }

        // Already known. The update is keyed on the fingerprint rather than on an identifier we would have
        // had to select first: one statement, and no window between reading the row and writing it.
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE finding
                   SET last_detected_at = now(),
                       updated_at = now(),
                       updated_by = ?,
                       source_import_session_id = ?,
                       source_tool_version = coalesce(?, source_tool_version)
                 WHERE fingerprint_algorithm_version = ? AND fingerprint_digest = ?
                RETURNING id, lifecycle_state
                """)) {
            statement.setObject(1, principal.principalId());
            statement.setObject(2, sessionId);
            statement.setString(3, result.toolVersion());
            statement.setInt(4, fingerprint.algorithmVersion());
            statement.setBytes(5, fingerprint.digest());
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    // Neither inserted nor found. The only way here is a row the caller cannot see, and
                    // under FORCE row-level security that means another tenant's — which the fingerprint
                    // makes impossible, because the tenant is in the hash (INV-VUL-01). Loud rather than
                    // skipped: a finding silently dropped is a coverage gap nobody can find.
                    throw new IllegalStateException("the finding was neither inserted nor found by its "
                            + "fingerprint; identity resolution is inconsistent for result "
                            + result.index());
                }
                findingId = results.getObject(1, UUID.class);
                lifecycle = results.getString(2);
            }
        }
        if ("CLOSED".equals(lifecycle)) {
            reopen(connection, principal, findingId);
            return new Upsert(findingId, Disposition.REOPENED);
        }
        // ACCEPTED_RISK falls through to UPDATED on purpose. The weakness being present is what was
        // accepted, so re-reporting it is not news; reopening it would overturn a decision with an owner
        // and an expiry on the strength of a scan, and the expiry is what ends an acceptance.
        return new Upsert(findingId, Disposition.UPDATED);
    }

    /**
     * Reopens a closed finding that a scan found again.
     *
     * <p>{@code REOPEN} rather than {@code OPEN}: the lifecycle distinguishes a finding that was never
     * fixed from one that came back, and the second is a different conversation with whoever fixed it.
     * The closure fields are cleared because {@code ck_finding__closure_pair} requires them to agree, and
     * {@code recurrence_count} increments because this is the one event that word describes.
     */
    private static void reopen(Connection connection, Principal principal, UUID findingId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE finding
                   SET state = 'OPEN', lifecycle_state = 'REOPEN',
                       closed_at = NULL, closure_reason = NULL,
                       closure_verified_by = NULL, closure_verification_method = NULL,
                       recurrence_count = recurrence_count + 1,
                       updated_at = now(), updated_by = ?, row_version = row_version + 1
                 WHERE id = ?
                """)) {
            statement.setObject(1, principal.principalId());
            statement.setObject(2, findingId);
            statement.executeUpdate();
        }
    }

    /**
     * Retains the hashed inputs. {@code INV-VUL-04}.
     *
     * <p>Written once and never rewritten: these are the inputs the CURRENT digest was computed from, and
     * overwriting them on a re-detection would leave a row whose digest no longer follows from its inputs —
     * which is exactly the record a future algorithm version needs in order to recompute anything.
     */
    private static void retainInputs(Connection connection, UUID findingId,
            FindingFingerprint fingerprint) throws SQLException {
        Map<String, Object> inputs = new LinkedHashMap<>(fingerprint.inputSnapshot().values());
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO finding_fingerprint_input (finding_id, tenant_id, algorithm_version, inputs) "
                        + "VALUES (?, current_tenant_id(), ?, ?::jsonb) "
                        + "ON CONFLICT (finding_id, tenant_id) DO NOTHING")) {
            statement.setObject(1, findingId);
            statement.setInt(2, fingerprint.algorithmVersion());
            statement.setString(3, aspm.app.runtime.Json.write(inputs));
            statement.executeUpdate();
        }
    }

    /**
     * Links the finding to the repository it was found in, with the location as evidence.
     *
     * <p>The line number lives HERE and not in the fingerprint. That is the whole of DOC-03 §10.2's
     * exclusion: a reader needs the line to find the code, and identity must not move when an unrelated edit
     * shifts it. So the same fact is presented and not hashed.
     */
    private static void linkAsset(Connection connection, Principal principal, UUID findingId,
            UUID assetId, SarifParser.Result result) throws SQLException {
        Map<String, Object> location = new LinkedHashMap<>();
        location.put("path", result.reportedPath());
        if (result.startLine() != null) {
            location.put("line", result.startLine());
        }
        if (result.enclosingConstruct() != null) {
            location.put("construct", result.enclosingConstruct());
        }
        if (result.snippet() != null) {
            location.put("snippet", result.snippet());
        }
        if (!result.partialFingerprints().isEmpty()) {
            // The tool's own fingerprints, kept so somebody can correlate this row with the same alert in
            // the tool's interface. Not used for identity — see SarifParser#structuralHash.
            location.put("tool_fingerprints", result.partialFingerprints());
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO finding_asset_impact (tenant_id, finding_id, asset_id, location_detail,
                        first_detected_at, last_detected_at, created_by)
                VALUES (current_tenant_id(), ?, ?, ?::jsonb, now(), now(), ?)
                ON CONFLICT (tenant_id, finding_id, asset_id) DO UPDATE
                   SET last_detected_at = now(),
                       location_detail = excluded.location_detail,
                       resolved_at = NULL,
                       updated_at = now(),
                       row_version = finding_asset_impact.row_version + 1
                """)) {
            statement.setObject(1, findingId);
            statement.setObject(2, assetId);
            statement.setString(3, aspm.app.runtime.Json.write(location));
            statement.setObject(4, principal.principalId());
            statement.executeUpdate();
        }
    }

    private static void quarantine(Connection connection, Principal principal, UUID sessionId,
            SarifParser.Held record) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO import_quarantine (tenant_id, import_session_id, record_index, reason, "
                        + "failing_fields, raw_content) "
                        + "VALUES (current_tenant_id(), ?, ?, ?, ?, ?)")) {
            statement.setObject(1, sessionId);
            statement.setInt(2, record.index());
            statement.setString(3, record.reason().name());
            statement.setArray(4, connection.createArrayOf("text", record.failingFields().toArray()));
            statement.setString(5, record.rawContent());
            statement.executeUpdate();
        }
    }

    /**
     * The reference to the raw record. {@code PRD-ING-022}.
     *
     * <p>A JSON pointer into the archived document where there is one, so the record is genuinely
     * retrievable. Where the archive was unavailable it names the session and the position instead — which
     * cannot produce the record, and says so by its scheme rather than by looking like a URL that 404s.
     */
    private static String rawRecordRef(String documentRef, UUID sessionId, SarifParser.Result result) {
        String pointer = "#/runs/" + result.runIndex() + "/results/" + result.indexInRun();
        return documentRef == null ? "import://" + sessionId + pointer : documentRef + pointer;
    }

    /**
     * What the finding says beyond its title.
     *
     * <p>The tool's message, the location, and the rule's help link. Assembled here rather than in the
     * parser because it is presentation, and the parser's output has to stay the thing tests assert identity
     * against. Restricted Markdown, like every other body in the platform: the content is attacker-authored
     * by design ({@code SEC-SEC-032} and the fifth risk surface), so it is text and never markup.
     */
    private static String description(SarifParser.Result result) {
        StringBuilder out = new StringBuilder(256);
        if (result.message() != null) {
            out.append(result.message()).append("\n\n");
        }
        out.append("Reported at `").append(result.reportedPath());
        if (result.startLine() != null) {
            out.append(':').append(result.startLine());
        }
        out.append("`");
        if (result.enclosingConstruct() != null) {
            out.append(" in `").append(result.enclosingConstruct()).append('`');
        }
        out.append(".\n\nRule `").append(result.ruleIdentity()).append('`');
        if (result.toolName() != null) {
            out.append(", reported by ").append(result.toolName());
            if (result.toolVersion() != null) {
                out.append(' ').append(result.toolVersion());
            }
        }
        out.append('.');
        if (result.helpUri() != null) {
            out.append("\n\nRule documentation: ").append(result.helpUri());
        }
        if (result.severityOrdinal() == null) {
            out.append("\n\n**Ungraded.** The source reported ")
                    .append(result.reportedSeverityRaw() == null ? "no severity"
                            : "`" + result.reportedSeverityRaw() + "`")
                    .append(", which maps to nothing in this platform's scale. Recorded ungraded rather "
                            + "than defaulted, so it is visible as a mapping gap and not as a medium.");
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ the session row

    private static Report existingSession(Connection connection, String idempotencyKey)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, target_asset_id, state, records_extracted, ingested_count, updated_count, "
                        + "merged_count, quarantined_count, mapping_gap_count "
                        + "FROM import_session WHERE idempotency_key = ?")) {
            statement.setString(1, idempotencyKey);
            try (ResultSet results = statement.executeQuery()) {
                if (!results.next()) {
                    return null;
                }
                return new Report(results.getObject(1, UUID.class), false,
                        results.getObject(2, UUID.class), false, results.getString(3),
                        results.getInt(4), results.getInt(5), results.getInt(6), 0,
                        results.getInt(7), results.getInt(8), results.getInt(9), Set.of(),
                        List.of("this idempotency key was already submitted; the first session's outcome "
                                + "is returned unchanged and nothing was ingested again"));
            }
        }
    }

    private UUID openSession(Connection connection, Principal principal,
            String idempotencyKey, byte[] documentHash, int documentBytes, String documentRef,
            UUID targetAssetId, int recordsExtracted) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO import_session (tenant_id, idempotency_key, source_format,
                        source_format_version, parser_code, parser_version, target_asset_id,
                        document_sha256, document_bytes, document_ref, state, records_extracted,
                        submitted_by, reversible_until)
                VALUES (current_tenant_id(), ?, ?, ?, ?, ?, ?, ?, ?, ?, 'NORMALIZING', ?, ?,
                        now() + interval '7 days')
                RETURNING id
                """)) {
            int at = 1;
            statement.setString(at++, idempotencyKey);
            statement.setString(at++, SarifParser.FORMAT);
            statement.setString(at++, "2.1.0");
            statement.setString(at++, SarifParser.CODE);
            statement.setInt(at++, SarifParser.PARSER_VERSION);
            statement.setObject(at++, targetAssetId);
            statement.setBytes(at++, documentHash);
            statement.setInt(at++, documentBytes);
            statement.setString(at++, documentRef);
            statement.setInt(at++, recordsExtracted);
            statement.setObject(at++, principal.principalId());
            try (ResultSet results = statement.executeQuery()) {
                results.next();
                return results.getObject(1, UUID.class);
            }
        }
    }

    private static void closeSession(Connection connection, UUID sessionId, String state, Counts counts,
            int quarantined, int gaps) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE import_session
                   SET state = ?, ingested_count = ?, updated_count = ?, merged_count = ?,
                       quarantined_count = ?, mapping_gap_count = ?, completed_at = now()
                 WHERE id = ?
                """)) {
            int at = 1;
            statement.setString(at++, state);
            statement.setInt(at++, counts.ingested);
            // REOPENED rows are counted with UPDATED in the session, because the schema carries the five
            // dispositions DOC-11 names and REOPENED is one of them only in the domain model. The report
            // returned to the submitter separates them, which is where the distinction is acted on.
            statement.setInt(at++, counts.updated + counts.reopened);
            statement.setInt(at++, counts.merged);
            statement.setInt(at++, quarantined);
            statement.setInt(at++, gaps);
            statement.setObject(at++, sessionId);
            statement.executeUpdate();
        }
    }

    /**
     * Records a document that was refused whole.
     *
     * <p>Its own transaction, committed even though the submission failed. The alternative — rolling back
     * everything including the record of the attempt — is what made a rejected pipeline indistinguishable
     * from a silent one.
     */
    private UUID recordFailure(Connection connection, Principal principal,
            String idempotencyKey, byte[] documentHash, int documentBytes, String reason)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO import_session (tenant_id, idempotency_key, source_format,
                        source_format_version, parser_code, parser_version, document_sha256,
                        document_bytes, state, failure_reason, completed_at, submitted_by)
                VALUES (current_tenant_id(), ?, ?, ?, ?, ?, ?, ?, 'FAILED', ?, now(), ?)
                RETURNING id
                """)) {
            int at = 1;
            statement.setString(at++, idempotencyKey);
            statement.setString(at++, SarifParser.FORMAT);
            // What the SUBMISSION declared is unknown when the rejection is about the version itself, so
            // the session records what the parser supports. The failure reason carries what was actually
            // declared, which is the string somebody needs to see.
            statement.setString(at++, "2.1.0");
            statement.setString(at++, SarifParser.CODE);
            statement.setInt(at++, SarifParser.PARSER_VERSION);
            statement.setBytes(at++, documentHash);
            statement.setInt(at++, documentBytes);
            statement.setString(at++, reason);
            statement.setObject(at++, principal.principalId());
            try (ResultSet results = statement.executeQuery()) {
                results.next();
                return results.getObject(1, UUID.class);
            }
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private Connection open(Principal principal) throws SQLException {
        Objects.requireNonNull(principal, "a principal is required: the tenant context comes from the "
                + "authenticated caller and from nowhere else (SEC-TEN-004)");
        return TenantConnections.open(dataSource, principal);
    }
}
