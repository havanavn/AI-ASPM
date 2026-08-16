package aspm.app.resource;

import aspm.app.runtime.Principal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The two halves of a submission that are not the component list: which advisories affect which
 * component, and which component pulled which other one in. V036, ADR-013, ADR-023.
 *
 * <h2>Why this is the same request and not a second API</h2>
 *
 * <p>ADR-023 makes the SBOM push "the only automated ingestion path in v1". A separate endpoint for
 * vulnerabilities would be a second one, and it would be a worse one: the pipeline that produced the
 * SBOM is the same process that scanned it, in the same second, against the same resolved dependency
 * tree. Splitting that into two calls creates a window in which the platform holds components with no
 * advisories and reports the artifact as clean — the reading product principle 1 exists to prevent.
 *
 * <p>CycloneDX 1.4 already carries all three sections in one document ({@code components},
 * {@code dependencies}, {@code vulnerabilities}), so honouring them costs the submitter nothing. Trivy's
 * native output carries {@code Results[].Vulnerabilities[]} and is read too, because the deployment
 * that has Trivy in its pipeline should not have to convert.
 *
 * <h2>What is deliberately not done</h2>
 *
 * <p><b>No matching.</b> This records what the submitter observed. Matching interned components
 * against an advisory feed the platform operates is what {@code match_run} exists for and no feed is
 * configured, so nothing here pretends to have performed one — {@code component_advisory.source_tool}
 * records which tool made the claim precisely so a later feed can be told apart from it rather than
 * silently merged.
 *
 * <p><b>No severity invention.</b> An advisory whose document carries no rating is stored with a NULL
 * severity and appears in the interface as unrated. Defaulting it to MEDIUM would put a number on the
 * page that nobody asserted, and it would be the number an executive reads.
 */
public final class SbomGraph {

    /** What one submission's graph and vulnerability sections produced. */
    public record Outcome(int advisories, int affectedComponents, int edges, List<String> warnings) {
    }

    private SbomGraph() {
    }

    /**
     * Records the dependency edges and the advisories carried by one submitted document.
     *
     * @param componentIdByRef the document's own reference for each component, mapped to the interned
     *     component identity. Built by the caller during interning, because only the caller knows
     *     which {@code Parsed} became which row.
     */
    public static Outcome record(Connection connection, Principal principal, UUID snapshotId,
            Map<String, Object> body, Map<String, UUID> componentIdByRef) throws SQLException {
        List<String> warnings = new ArrayList<>();
        int edges = writeDependencies(connection, principal, snapshotId, body, componentIdByRef,
                warnings);
        int[] advisories = writeAdvisories(connection, principal, body, componentIdByRef, warnings);
        return new Outcome(advisories[0], advisories[1], edges, List.copyOf(warnings));
    }

    // ==============================================================================================
    // dependencies[] — the graph
    // ==============================================================================================

    /**
     * Replaces this snapshot's edges with the ones the document declares.
     *
     * <p>Delete-then-insert rather than upsert, and scoped to this snapshot alone. A resubmission of
     * the same artifact is a new snapshot, so this only ever runs against a set it just created; the
     * delete is there so a retried submission that partially wrote cannot leave an edge from an
     * earlier attempt in a graph that no longer contains its endpoint.
     */
    private static int writeDependencies(Connection connection, Principal principal, UUID snapshotId,
            Map<String, Object> body, Map<String, UUID> componentIdByRef, List<String> warnings)
            throws SQLException {
        Object dependencies = body.get("dependencies");
        if (!(dependencies instanceof List<?> list) || list.isEmpty()) {
            // Stated, not silent. Without the graph the interface can say a component is transitive
            // and cannot say what to upgrade, and the submitter is the only party who can fix that.
            warnings.add("NO_DEPENDENCY_GRAPH");
            return 0;
        }

        try (PreparedStatement clear = connection.prepareStatement(
                "DELETE FROM component_dependency WHERE snapshot_id = ?")) {
            clear.setObject(1, snapshotId);
            clear.executeUpdate();
        }

        int written = 0;
        int unresolved = 0;
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO component_dependency (tenant_id, snapshot_id, parent_component_id, "
                        + "child_component_id) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING")) {
            for (Object entry : list) {
                if (!(entry instanceof Map<?, ?> map)) {
                    continue;
                }
                UUID parent = componentIdByRef.get(String.valueOf(map.get("ref")));
                if (parent == null) {
                    // The root of the document is a dependency entry too, and it is the ARTIFACT
                    // rather than a component. Its children are the direct dependencies, which
                    // component_entry.relationship already records; there is no component row to be
                    // the parent of an edge, so it is skipped rather than counted as unresolved.
                    continue;
                }
                if (!(map.get("dependsOn") instanceof List<?> children)) {
                    continue;
                }
                for (Object child : children) {
                    UUID childId = componentIdByRef.get(String.valueOf(child));
                    if (childId == null) {
                        unresolved++;
                        continue;
                    }
                    if (childId.equals(parent)) {
                        continue;
                    }
                    insert.setObject(1, principal.tenantId());
                    insert.setObject(2, snapshotId);
                    insert.setObject(3, parent);
                    insert.setObject(4, childId);
                    insert.executeUpdate();
                    written++;
                }
            }
        }
        if (unresolved > 0) {
            // A dependsOn naming something the components list does not contain is an incomplete
            // document, and the count is reported so the submitter can see how incomplete.
            warnings.add("DEPENDENCY_REFERENCES_UNKNOWN_COMPONENT:" + unresolved);
        }
        return written;
    }

    // ==============================================================================================
    // vulnerabilities[] — the advisories
    // ==============================================================================================

    /** One advisory as the document states it, before anything is stored. */
    private record Claim(String key, String source, String severity, Double cvssScore,
            String cvssVector, String summary, String description, String publishedAt,
            String fixedVersion, String status, List<String> cweIds, List<String> references,
            String tool, Set<String> refs) {
    }

    private static int[] writeAdvisories(Connection connection, Principal principal,
            Map<String, Object> body, Map<String, UUID> componentIdByRef, List<String> warnings)
            throws SQLException {
        // The normalized array first, then the two native shapes. A submitter who put it there did
        // so deliberately — it is the escape hatch for a tool nothing here reads yet — so it wins
        // over whatever else the document happens to contain.
        List<Claim> claims = readNormalized(body);
        if (claims.isEmpty()) {
            claims = readCycloneDx(body);
        }
        if (claims.isEmpty()) {
            claims = readTrivy(body);
        }
        if (claims.isEmpty()) {
            // NOT a warning. A document with no vulnerabilities section is the normal output of a
            // tool that only builds bills of materials, and warning about it would train submitters
            // to ignore the warning list. The absence is visible where it matters: the asset shows
            // components with no advisory data, which the coverage panel reports as never matched.
            return new int[] {0, 0};
        }

        Set<UUID> affected = new LinkedHashSet<>();
        int stored = 0;
        int unresolved = 0;
        for (Claim claim : claims) {
            if (claim.key() == null || claim.key().isBlank()) {
                continue;
            }
            UUID advisoryId = upsertAdvisory(connection, principal, claim);
            boolean any = false;
            for (String ref : claim.refs()) {
                UUID componentId = componentIdByRef.get(ref);
                if (componentId == null) {
                    unresolved++;
                    continue;
                }
                link(connection, componentId, advisoryId, claim);
                affected.add(componentId);
                any = true;
            }
            if (any) {
                stored++;
            }
        }
        if (unresolved > 0) {
            warnings.add("ADVISORY_AFFECTS_UNKNOWN_COMPONENT:" + unresolved);
        }
        return new int[] {stored, affected.size()};
    }

    /**
     * Creates the advisory, or refreshes what a newer document says about one already recorded.
     *
     * <p>{@code first_recorded_at} is never rewritten. It is the answer to "when did this estate
     * first learn about it", and a resubmission of the same scan would otherwise move every advisory
     * to today and empty the "new this month" figure of meaning.
     */
    private static UUID upsertAdvisory(Connection connection, Principal principal, Claim claim)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO advisory (tenant_id, advisory_key, source, severity_id, cvss_score,
                                      cvss_vector, summary, description, cwe_ids, references_urls,
                                      data_source, published_at, created_by, updated_by)
                VALUES (current_tenant_id(), ?, ?,
                        (SELECT id FROM severity_level WHERE code = ?), ?, ?, ?, ?, ?, ?, ?,
                        CAST(? AS timestamptz), ?, ?)
                ON CONFLICT (tenant_id, advisory_key) DO UPDATE
                   SET severity_id  = coalesce(EXCLUDED.severity_id, advisory.severity_id),
                       cvss_score   = coalesce(EXCLUDED.cvss_score, advisory.cvss_score),
                       cvss_vector  = coalesce(EXCLUDED.cvss_vector, advisory.cvss_vector),
                       summary      = coalesce(EXCLUDED.summary, advisory.summary),
                       description  = coalesce(EXCLUDED.description, advisory.description),
                       -- Union rather than replace. Two scanners routinely name different subsets of
                       -- the same advisory's weaknesses and references, and letting the later
                       -- submission overwrite would make the record depend on which pipeline ran last.
                       cwe_ids      = (SELECT array_agg(DISTINCT v) FROM unnest(
                                         coalesce(advisory.cwe_ids, '{}') ||
                                         coalesce(EXCLUDED.cwe_ids, '{}')) AS v),
                       references_urls = (SELECT array_agg(DISTINCT v) FROM unnest(
                                         coalesce(advisory.references_urls, '{}') ||
                                         coalesce(EXCLUDED.references_urls, '{}')) AS v),
                       data_source  = coalesce(EXCLUDED.data_source, advisory.data_source),
                       published_at = coalesce(EXCLUDED.published_at, advisory.published_at),
                       updated_at   = now(),
                       updated_by   = EXCLUDED.updated_by,
                       row_version  = advisory.row_version + 1
                RETURNING id
                """)) {
            statement.setString(1, claim.key());
            statement.setString(2, claim.source() == null ? "SUBMITTED" : claim.source());
            statement.setString(3, claim.severity());
            if (claim.cvssScore() == null) {
                statement.setNull(4, java.sql.Types.NUMERIC);
            } else {
                statement.setDouble(4, claim.cvssScore().doubleValue());
            }
            statement.setString(5, claim.cvssVector());
            statement.setString(6, claim.summary());
            statement.setString(7, claim.description());
            statement.setArray(8, connection.createArrayOf("text",
                    claim.cweIds() == null ? new String[0] : claim.cweIds().toArray()));
            statement.setArray(9, connection.createArrayOf("text",
                    claim.references() == null ? new String[0] : claim.references().toArray()));
            statement.setString(10, claim.source());
            statement.setString(11, claim.publishedAt());
            statement.setObject(12, principal.principalId());
            statement.setObject(13, principal.principalId());
            try (ResultSet keys = statement.executeQuery()) {
                keys.next();
                return keys.getObject(1, UUID.class);
            }
        }
    }

    /**
     * Records that this component carries this advisory.
     *
     * <p>Re-observing an already-recorded pair does NOT move {@code detected_at} and does not clear a
     * resolution. A component that was resolved and is seen affected again is a genuine regression
     * and the row is reopened with its original detection date intact, because the age of a
     * vulnerability is measured from when it was first seen and not from the last time a pipeline
     * ran.
     */
    private static void link(Connection connection, UUID componentId,
            UUID advisoryId, Claim claim) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO component_advisory (tenant_id, component_id, advisory_id,
                                                fixed_version, source_tool, status)
                VALUES (current_tenant_id(), ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, component_id, advisory_id) DO UPDATE
                   SET fixed_version = coalesce(EXCLUDED.fixed_version,
                                                component_advisory.fixed_version),
                       source_tool   = EXCLUDED.source_tool,
                       status        = coalesce(EXCLUDED.status, component_advisory.status),
                       resolved_at   = NULL,
                       resolution    = NULL
                """)) {
            statement.setObject(1, componentId);
            statement.setObject(2, advisoryId);
            statement.setString(3, claim.fixedVersion());
            statement.setString(4, claim.tool() == null ? "unknown" : claim.tool());
            statement.setString(5, claim.status());
            statement.executeUpdate();
        }
    }

    // ==============================================================================================
    // Readers, one per document shape
    // ==============================================================================================

    /**
     * {@code aspm_vulnerabilities[]} — the shape any tool can be reshaped into.
     *
     * <p>A reader exists for CycloneDX and for Trivy's JSON, and there will never be one for every
     * scanner a team might run. Rather than making them wait for a release, this accepts a small
     * normalized array they can produce with a {@code jq} filter over whatever their tool emits:
     *
     * <pre>
     *   "aspm_vulnerabilities": [
     *     {"id": "CVE-2021-44228", "purl": "pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1",
     *      "severity": "CRITICAL", "cvss": 10.0, "fixed_version": "2.17.1",
     *      "summary": "…", "description": "…", "status": "will_not_fix",
     *      "cwe_ids": ["CWE-917"], "references": ["https://…"], "source": "my-scanner"}
     *   ]
     * </pre>
     *
     * <p>Only {@code id} and {@code purl} are required — the identity of the advisory and of the
     * thing it affects. Everything else is optional and absent means absent: a submission with no
     * severity produces an unrated advisory, which the interface shows as unrated rather than
     * guessing at a band.
     *
     * <p>{@code source} records which tool made the claim, exactly as it does for the native readers,
     * so a hand-piped result is never mistaken for a first-party one.
     */
    private static List<Claim> readNormalized(Map<String, Object> body) {
        List<Claim> out = new ArrayList<>();
        if (!(body.get("aspm_vulnerabilities") instanceof List<?> list)) {
            return out;
        }
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            Set<String> refs = new LinkedHashSet<>();
            if (map.get("purl") != null) {
                refs.add(text(map.get("purl")));
            }
            if (map.get("ref") != null) {
                refs.add(text(map.get("ref")));
            }
            Double score = map.get("cvss") instanceof Number number
                    ? Double.valueOf(number.doubleValue()) : null;
            out.add(new Claim(text(map.get("id")), text(map.get("source")),
                    normaliseSeverity(text(map.get("severity"))), score,
                    text(map.get("cvss_vector")), text(map.get("summary")),
                    text(map.get("description")), text(map.get("published")),
                    text(map.get("fixed_version")), text(map.get("status")),
                    strings(map.get("cwe_ids")), strings(map.get("references")),
                    map.get("source") == null ? "submitted" : text(map.get("source")), refs));
        }
        return out;
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            list.forEach(item -> out.add(text(item)));
        }
        return out;
    }

    private static String shorter(String a, String b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.length() <= b.length() ? a : b;
    }

    /**
     * The long form, falling back to the only form.
     *
     * <p>Returning null when a document carries just one text field was wrong in the common case:
     * Trivy's CycloneDX sends {@code description} with the long explanation and no {@code detail}, so
     * the single field became the summary and the detail panel had nothing to show. One text field
     * means the same text is the best available answer to both questions, which is honest — it is
     * the only text there is — and the table truncates it while the panel shows it whole.
     */
    private static String longer(String a, String b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.length() > b.length() ? a : b;
    }

    /** CycloneDX 1.4+ {@code vulnerabilities[]}. */
    private static List<Claim> readCycloneDx(Map<String, Object> body) {
        List<Claim> out = new ArrayList<>();
        if (!(body.get("vulnerabilities") instanceof List<?> list)) {
            return out;
        }
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> map)) {
                continue;
            }
            String key = text(map.get("id"));
            String source = map.get("source") instanceof Map<?, ?> src ? text(src.get("name"))
                    : null;

            String severity = null;
            Double score = null;
            String vector = null;
            if (map.get("ratings") instanceof List<?> ratings) {
                for (Object rating : ratings) {
                    if (!(rating instanceof Map<?, ?> r)) {
                        continue;
                    }
                    // The FIRST rating that carries a severity wins, and the first that carries a
                    // score wins separately. A document may rate the same advisory under two methods
                    // and taking the last would silently prefer whichever the tool emitted later.
                    if (severity == null && r.get("severity") != null) {
                        severity = normaliseSeverity(text(r.get("severity")));
                    }
                    if (score == null && r.get("score") instanceof Number number) {
                        score = Double.valueOf(number.doubleValue());
                    }
                    if (vector == null && r.get("vector") != null) {
                        vector = text(r.get("vector"));
                    }
                }
            }

            Set<String> refs = new LinkedHashSet<>();
            String fixedVersion = null;
            if (map.get("affects") instanceof List<?> affects) {
                for (Object affect : affects) {
                    if (affect instanceof Map<?, ?> a && a.get("ref") != null) {
                        refs.add(text(a.get("ref")));
                        if (fixedVersion == null && a.get("versions") instanceof List<?> versions) {
                            fixedVersion = firstFixed(versions);
                        }
                    }
                }
            }
            List<String> cwes = new ArrayList<>();
            if (map.get("cwes") instanceof List<?> cweList) {
                // CycloneDX carries CWEs as bare integers. Rendered back to the CWE-nnn form the
                // rest of the world writes, because "917" in a column headed Weakness is a number
                // nobody can look up.
                cweList.forEach(cwe -> cwes.add(cwe instanceof Number n
                        ? "CWE-" + n.intValue() : "CWE-" + text(cwe)));
            }
            List<String> references = new ArrayList<>();
            if (map.get("advisories") instanceof List<?> advisoryList) {
                for (Object advisory : advisoryList) {
                    if (advisory instanceof Map<?, ?> a && a.get("url") != null) {
                        references.add(text(a.get("url")));
                    }
                }
            }
            if (map.get("source") instanceof Map<?, ?> src && src.get("url") != null) {
                references.add(text(src.get("url")));
            }
            // Trivy's CycloneDX puts the long form in `description` and no title; CycloneDX proper
            // has `detail` for the long form. Whichever is longer is the description, and the other
            // is the summary — the table needs a line and the panel needs an explanation.
            String first = text(map.get("description"));
            String second = text(map.get("detail"));
            String summary = shorter(first, second);
            String description = longer(first, second);
            out.add(new Claim(key, source, severity, score, vector, summary, description,
                    text(map.get("published")), fixedVersion,
                    map.get("analysis") instanceof Map<?, ?> analysis
                            ? text(analysis.get("state")) : null,
                    cwes, references, source == null ? "cyclonedx" : source, refs));
        }
        return out;
    }

    /** A {@code versions[]} entry marked as the state in which the package is no longer affected. */
    private static String firstFixed(List<?> versions) {
        for (Object version : versions) {
            if (version instanceof Map<?, ?> v && "unaffected".equalsIgnoreCase(text(v.get("status")))
                    && v.get("version") != null) {
                return text(v.get("version"));
            }
        }
        return null;
    }

    /** Trivy {@code Results[].Vulnerabilities[]}. */
    private static List<Claim> readTrivy(Map<String, Object> body) {
        List<Claim> out = new ArrayList<>();
        if (!(body.get("Results") instanceof List<?> results)) {
            return out;
        }
        for (Object result : results) {
            if (!(result instanceof Map<?, ?> resultMap)
                    || !(resultMap.get("Vulnerabilities") instanceof List<?> list)) {
                continue;
            }
            for (Object entry : list) {
                if (!(entry instanceof Map<?, ?> map)) {
                    continue;
                }
                Double score = null;
                String vector = null;
                if (map.get("CVSS") instanceof Map<?, ?> cvss) {
                    for (Object value : cvss.values()) {
                        if (value instanceof Map<?, ?> v) {
                            if (score == null && v.get("V3Score") instanceof Number number) {
                                score = Double.valueOf(number.doubleValue());
                            }
                            if (vector == null && v.get("V3Vector") != null) {
                                vector = text(v.get("V3Vector"));
                            }
                        }
                    }
                }
                // Trivy names the affected package by PURL where it has one, which is the same handle
                // the component parser used as this document's bom-ref.
                String ref = map.get("PkgIdentifier") instanceof Map<?, ?> ident
                        ? text(ident.get("PURL")) : null;
                Set<String> refs = new LinkedHashSet<>();
                if (ref != null) {
                    refs.add(ref);
                }
                List<String> cwes = new ArrayList<>();
                if (map.get("CweIDs") instanceof List<?> cweList) {
                    cweList.forEach(cwe -> cwes.add(text(cwe)));
                }
                List<String> references = new ArrayList<>();
                if (map.get("References") instanceof List<?> referenceList) {
                    referenceList.forEach(reference -> references.add(text(reference)));
                }
                String dataSource = map.get("DataSource") instanceof Map<?, ?> ds
                        ? text(ds.get("Name")) : text(map.get("SeveritySource"));
                out.add(new Claim(text(map.get("VulnerabilityID")),
                        dataSource, normaliseSeverity(text(map.get("Severity"))),
                        score, vector, text(map.get("Title")), text(map.get("Description")),
                        text(map.get("PublishedDate")), text(map.get("FixedVersion")),
                        text(map.get("Status")), cwes, references, "trivy", refs));
            }
        }
        return out;
    }

    /**
     * Maps a document's severity word onto the tenant's severity codes.
     *
     * <p>Upper-cased and nothing else. The severity vocabulary is tenant-configured (ADR-027) and the
     * lookup in {@link #upsertAdvisory} is a subquery against {@code severity_level}, so a word the
     * tenant has not configured resolves to NULL and the advisory is stored unrated rather than
     * forced into the nearest band. A mapping table here would be this class deciding what a tenant's
     * severity scale means.
     */
    private static String normaliseSeverity(String raw) {
        return raw == null || raw.isBlank() ? null : raw.strip().toUpperCase(Locale.ROOT);
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
