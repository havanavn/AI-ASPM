package aspm.app.resource;

import aspm.app.runtime.Principal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * SBOM submission. DOC-22, DOC-05 §17, ADR-013, ADR-023.
 *
 * <p>ADR-013: the module <b>stores and matches</b>; it does not execute scanners over source. ADR-024:
 * the platform never fetches, clones, or persists source code. ADR-023 makes this push API the only
 * automated ingestion path in v1 — so a scanner runs wherever the code already is, and its output
 * arrives here.
 *
 * <h2>Three requirements decide the shape of this class</h2>
 *
 * <ol>
 *   <li><b>{@code PRD-SBM-037}: an unmatchable component is recorded, never skipped.</b> "Silent
 *       skipping is the mechanism by which a partially matched SBOM appears fully matched." Every
 *       component that cannot be canonicalized is stored with an enumerated reason, and it counts against
 *       the quality score.
 *   <li><b>{@code PRD-API-038}: the response carries the quality score and every warning.</b> "The
 *       submitter is the only party who can fix a low-quality SBOM, and they see the response, not the
 *       log." So the return value is the report, not a receipt.
 *   <li><b>{@code PRD-API-039}: an unknown artifact is created unclaimed, never rejected.</b>
 *       "Rejection loses data at the point of least detectability — a pipeline receiving a 4xx logs it
 *       and continues." A 404 here is an SBOM nobody ever sees again.
 * </ol>
 *
 * <p>{@code PRD-SBM-033} makes the identity the content hash: resubmitting identical content returns the
 * existing snapshot rather than creating a second. That is what lets a pipeline retry safely.
 */
public final class SbomIngestion {

    /**
     * A bill of materials is the claim the whole composition view rests on, and a submission replaces
     * the previous one. Without a record, "why did this repository's component list change on the
     * eleventh" has no answer at all — the old snapshot is archived but nothing says who replaced it
     * or with what (PP-5).
     */
    private final aspm.app.audit.AuditTrail audit =
            new aspm.app.audit.AuditTrail(java.time.Clock.systemUTC());

    /** The submission report. Everything the submitter needs to fix what they sent. */
    public record Report(UUID snapshotId, boolean createdNow, int componentCount, int quality,
            List<String> warnings, List<String> ecosystems, int unmatchableCount,
            boolean artifactCreatedUnclaimed, String artifactAssetId,
            int advisoryCount, int affectedComponentCount, int dependencyEdgeCount,
            String replacedSnapshotId) {
    }

    /**
     * The three-part address a build job knows: which application, which project, which repository.
     *
     * <p>Preferred over an opaque {@code artifact_reference} because a pipeline knows these three and
     * does not know an identifier the platform generated. Resubmitting the same three RESOLVES TO THE
     * SAME repository asset, which is what makes a resubmission a replacement rather than a second
     * artifact quietly accumulating beside the first.
     *
     * <p>{@code application} and {@code project} may be absent. A repository whose place in the tree
     * is not stated lands unclaimed, which is {@code PRD-API-039}'s position and is better than
     * refusing the submission: the coverage is real even when the ownership is not yet recorded.
     */
    public record Target(String application, String project, String repository) {
    }

    /** Rejected before anything was stored. */
    public record Rejection(String code, String detail) {
    }

    /**
     * One parsed component.
     *
     * <p>{@code bomRef} is the identifier the DOCUMENT uses for this component — CycloneDX
     * {@code bom-ref}. It is carried because two other sections of the same document point at
     * components by that reference and by nothing else: {@code dependencies[]} is the graph, and
     * {@code vulnerabilities[].affects[].ref} is which package each advisory applies to. Without it
     * both sections are unreadable, which is why V011 could only record a direct/transitive bit.
     */
    private record Parsed(String purlOriginal, String purlCanonical, String ecosystem, String name,
            String version, boolean canonicalizable, String unmatchableReason,
            List<String> licenses, boolean direct, String bomRef) {
    }

    /** Ecosystems this canonicalizer knows. An unknown one is recorded, not guessed at. */
    private static final Set<String> ECOSYSTEMS = Set.of(
            "maven", "npm", "pypi", "golang", "nuget", "cargo", "composer", "gem", "deb", "rpm",
            "apk", "hex", "pub", "swift", "conan", "cocoapods", "generic");

    private final DataSource dataSource;
    /**
     * Where the submitted document is archived so it can be re-scanned later.
     *
     * <p>Until now only the PARSED components were kept, which meant a snapshot could never be
     * re-evaluated against a newer vulnerability database — a repository that stopped building kept
     * its advisory data frozen, and a CVE published after its last push was invisible for ever. That
     * is the false negative this product exists to prevent, and it needs the original bytes.
     */
    private final ObjectStore objects = new ObjectStore(System.getenv());

    public SbomIngestion(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    // ----------------------------------------------------------------------------------------------

    /**
     * Accepts a submission.
     *
     * @param body the parsed document. CycloneDX {@code components[]} and Trivy
     *     {@code Results[].Packages[]} are both understood; the shape is detected rather than declared,
     *     because a pipeline that has to declare its format will declare the wrong one
     * @param artifactReference the artifact this SBOM describes — a display name or external reference.
     *     An unknown one creates the asset unclaimed ({@code PRD-API-039})
     */
    public Object submit(Principal principal, Map<String, Object> body, String artifactReference,
            String rawDocument) throws SQLException {
        return submit(principal, body, rawDocument, artifactReference, null);
    }

    /** The same, addressed by application/project/repository. See {@link Target}. */
    public Object submit(Principal principal, Map<String, Object> body, String rawDocument,
            Target target) throws SQLException {
        return submit(principal, body, rawDocument, null, target);
    }

    private Object submit(Principal principal, Map<String, Object> body, String rawDocument,
            String artifactReference, Target target) throws SQLException {
        try {
            return submitInternal(principal, body, artifactReference, rawDocument, target);
        } catch (IllegalStateException e) {
            // A tenant configuration gap — no asset type, no criticality tier, a credential with no
            // scope. It surfaced as a 500 the first time this ran, which tells a pipeline nothing it can
            // act on. PRD-API-038's reasoning applies: the submitter is the party who can get this
            // fixed, and they see the response.
            return new Rejection("TENANT_NOT_CONFIGURED", e.getMessage());
        }
    }

    private Object submitInternal(Principal principal, Map<String, Object> body,
            String artifactReference, String rawDocument, Target target) throws SQLException {
        Objects.requireNonNull(principal, "a principal is required");

        List<Parsed> components = parse(body);
        if (components.isEmpty()) {
            // INV-SBM-03. A zero-component snapshot "is the likely output of a misconfigured pipeline",
            // and accepting it would record coverage the scan did not establish.
            // The rejection NAMES THE FIX where the shape tells us what it is. Trivy's default JSON
            // reports vulnerabilities and omits the package list entirely, so a pipeline running
            // `trivy sbom --format json` gets a document with advisories and no components — and the
            // generic message told its operator only that they had done something wrong. PRD-API-038:
            // the submitter is the only party who can fix this and they are the one reading it.
            boolean looksLikeTrivyWithoutPackages = body.get("Results") instanceof List<?> results
                    && results.stream().anyMatch(entry -> entry instanceof Map<?, ?> map
                            && map.get("Vulnerabilities") instanceof List<?> found
                            && !found.isEmpty()
                            && !(map.get("Packages") instanceof List<?> packages
                                 && !packages.isEmpty()));
            if (looksLikeTrivyWithoutPackages) {
                return new Rejection("EMPTY_SBOM",
                        "the document reports vulnerabilities but declares no component, which is "
                                + "what Trivy's JSON output looks like without --list-all-pkgs. "
                                + "Either add that flag, or — better — submit `--format cyclonedx`, "
                                + "which carries the components, the dependency graph and the "
                                + "vulnerabilities in one document. Storing this as it stands would "
                                + "record coverage that was never established.");
            }
            return new Rejection("EMPTY_SBOM",
                    "the document declares no component. A zero-component SBOM is the likely output of "
                            + "a misconfigured pipeline, and storing it would record coverage that was "
                            + "never established.");
        }

        // The schema permits CYCLONEDX and SPDX. Trivy output is normalized into the CycloneDX shape
        // above rather than recorded as a third format: the format column says what the SNAPSHOT is,
        // and after parsing it is a CycloneDX-shaped component set whatever produced it. Recording
        // "TRIVY" would be a format the matcher does not know how to read back.
        String format = "CYCLONEDX";
        String formatVersion = String.valueOf(body.getOrDefault("specVersion", "unknown"));

        byte[] contentHash = sha256(rawDocument);
        List<String> warnings = new ArrayList<>();
        int unmatchable = (int) components.stream().filter(c -> !c.canonicalizable()).count();
        Set<String> ecosystems = new LinkedHashSet<>();
        components.stream().filter(Parsed::canonicalizable).forEach(c -> ecosystems.add(c.ecosystem()));

        if (ecosystems.isEmpty()) {
            return new Rejection("NO_MATCHABLE_ECOSYSTEM",
                    "no component could be canonicalized, so the snapshot covers no ecosystem and "
                            + "matching it would find nothing — which is indistinguishable from a clean "
                            + "application (PRD-SBM-037).");
        }

        int quality = quality(components, unmatchable, body, warnings);

        return inTenantTransaction(principal, connection -> {
            // PRD-SBM-033: identity IS the content hash. A resubmission of identical content returns the
            // existing snapshot, which is what lets a pipeline retry without creating a duplicate.
            try (PreparedStatement existing = connection.prepareStatement(
                    "SELECT id, artifact_asset_id, component_count, quality_score FROM sbom_snapshot "
                            + "WHERE content_hash = ?")) {
                existing.setBytes(1, contentHash);
                try (ResultSet results = existing.executeQuery()) {
                    if (results.next()) {
                        return new Report(results.getObject(1, UUID.class), false,
                                results.getInt(3), results.getInt(4),
                                List.of("IDENTICAL_CONTENT_ALREADY_SUBMITTED"),
                                List.copyOf(ecosystems), unmatchable, false,
                                String.valueOf(results.getObject(2)), 0, 0, 0, null);
                    }
                }
            }

            // PRD-API-039: an unknown artifact is CREATED unclaimed, not rejected. A 4xx here is an SBOM
            // nobody ever sees again, because a pipeline logs it and continues.
            Resolved artifact = target != null
                    ? resolveTarget(connection, principal, target)
                    : resolveArtifact(connection, principal, artifactReference);
            // What this submission supersedes, read BEFORE the new snapshot is written. Afterwards
            // the coverage row already points at the new one and the question is unanswerable.
            String replaced = previousSnapshot(connection, artifact.assetId());
            if (artifact.createdNow()) {
                warnings.add("ARTIFACT_CREATED_UNCLAIMED");
            }

            UUID snapshotId;
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO sbom_snapshot (tenant_id, artifact_asset_id, content_hash, format, "
                            + "format_version, source, submitted_by_principal_id, component_count, "
                            + "quality_score, quality_detail, ecosystems, scope_node_id, "
                            + "scope_ancestor_path, scope_node_type_id, scope_criticality_id, "
                            + "scope_hierarchy_ver, scope_resolved_at, storage_ref) "
                            + "VALUES (?, ?, ?, ?, ?, 'API_PUSH', ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, "
                            + "now(), ?) RETURNING id")) {
                insert.setObject(1, principal.tenantId());
                insert.setObject(2, artifact.assetId());
                insert.setBytes(3, contentHash);
                // The archive key is the CONTENT HASH, not the snapshot identifier, and it has to be:
                // an accepted snapshot is immutable (INV-SBM-01), so storage_ref cannot be filled in
                // afterwards — the first attempt at this did exactly that and the trigger refused it.
                // The content hash is the snapshot's identity anyway, so two submissions of identical
                // bytes address one object rather than two copies.
                String archiveKey = "sbom/" + principal.tenantId() + "/"
                        + java.util.HexFormat.of().formatHex(contentHash) + ".json";
                String storageRef = objects.put(ObjectStore.SBOM_BUCKET, archiveKey,
                                rawDocument.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                "application/json")
                        .orElse(null);
                if (storageRef == null && objects.configured()) {
                    // Best effort, and stated. The submission is sound; what is lost is the ability
                    // to re-scan this snapshot against a newer vulnerability database later, and a
                    // NULL storage_ref records exactly which snapshots a re-scan must skip rather
                    // than silently miss.
                    warnings.add("DOCUMENT_NOT_ARCHIVED");
                }
                insert.setString(4, format);
                insert.setString(5, formatVersion);
                insert.setObject(6, principal.principalId());
                insert.setInt(7, components.size());
                insert.setInt(8, quality);
                insert.setString(9, aspm.app.runtime.Json.write(Map.of(
                        "warnings", warnings, "unmatchable_components", Integer.valueOf(unmatchable))));
                insert.setArray(10, connection.createArrayOf("text", ecosystems.toArray()));
                insert.setObject(11, artifact.scopeNodeId());
                insert.setArray(12, connection.createArrayOf("uuid",
                        new UUID[] {artifact.scopeNodeId()}));
                insert.setObject(13, artifact.scopeNodeTypeId());
                insert.setObject(14, artifact.scopeCriticalityId());
                insert.setLong(15, artifact.hierarchyVersion());
                insert.setString(16, storageRef);
                try (ResultSet results = insert.executeQuery()) {
                    results.next();
                    snapshotId = results.getObject(1, UUID.class);
                }
            }

            // Components are interned TENANT-SCOPED (ADR-032, rejecting a global intern on
            // tenant-boundary grounds), so the same package in two tenants is two rows.
            //
            // The document's own reference for each one is kept beside the identity it became. The
            // dependency graph and the vulnerability list both point at components by that reference
            // and by nothing else, so without this map neither section can be read at all.
            Map<String, UUID> componentIdByRef = new LinkedHashMap<>();
            for (Parsed component : components) {
                UUID componentId = intern(connection, principal, component);
                if (component.bomRef() != null && !component.bomRef().isBlank()) {
                    componentIdByRef.putIfAbsent(component.bomRef(), componentId);
                }
                try (PreparedStatement entry = connection.prepareStatement(
                        "INSERT INTO component_entry (tenant_id, snapshot_id, component_id, "
                                + "relationship, license_refs) VALUES (?, ?, ?, ?, ?) "
                                + "ON CONFLICT DO NOTHING")) {
                    entry.setObject(1, principal.tenantId());
                    entry.setObject(2, snapshotId);
                    entry.setObject(3, componentId);
                    entry.setShort(4, (short) (component.direct() ? 1 : 2));
                    entry.setArray(5, connection.createArrayOf("text",
                            component.licenses().toArray()));
                    entry.executeUpdate();
                }
            }

            // The other two sections of the same document. One submission carries the bill of
            // materials, the graph and the advisories, because the pipeline that produced the first
            // produced all three in the same second against the same resolved tree — and a platform
            // that held the components without the advisories would report the artifact as clean.
            SbomGraph.Outcome graph = SbomGraph.record(connection, principal, snapshotId, body,
                    componentIdByRef);
            warnings.addAll(graph.warnings());

            updateCoverage(connection, principal, artifact, snapshotId, quality, ecosystems);
            // *** THE FIX TIMESTAMP. ***
            //
            // Replacing a repository's bill of materials is how a team records that they upgraded.
            // Until now nothing noticed: the new snapshot became the latest, the old component
            // vanished from every rollup, and its component_advisory row stayed OPEN for ever — so
            // "advisories closed this month" counted only what a seed script had written, and a real
            // remediation was invisible on the chart that exists to show it.
            //
            // Reconciled AFTER coverage moves to the new snapshot, because `asset_component` reads
            // the latest one and the question is "is this component still anywhere in the estate".
            int resolvedNow = reconcileResolved(connection);
            if (resolvedNow > 0) {
                warnings.add("ADVISORIES_RESOLVED_BY_THIS_SUBMISSION:" + resolvedNow);
            }

            // SBOM_SUBMITTED, in the transaction that stored it. One event per submission: the
            // snapshot identifier resolves to the archived document, so the payload carries what a
            // question would otherwise need the document to answer.
            audit.event(connection, principal,
                    aspm.kernel.audit.contract.AuditEventType.SBOM_SUBMITTED,
                    snapshotId, artifact.scopeNodeId(), java.util.Map.of(
                            "asset_id", artifact.assetId().toString(),
                            "components", Integer.valueOf(components.size()),
                            "quality", Integer.valueOf(quality),
                            "ecosystems", List.copyOf(ecosystems),
                            "asset_created_now", Boolean.valueOf(artifact.createdNow()),
                            "replaced_previous_snapshot", Boolean.valueOf(replaced)));

            return new Report(snapshotId, true, components.size(), quality, List.copyOf(warnings),
                    List.copyOf(ecosystems), unmatchable, artifact.createdNow(),
                    artifact.assetId().toString(), graph.advisories(),
                    graph.affectedComponents(), graph.edges(), replaced);
        });
    }

    // ----------------------------------------------------------------------------------------------

    /**
     * The quality score, and a warning for every criterion that lowered it.
     *
     * <p>{@code PRD-SBM-032} makes quality visible on the asset and in coverage reporting, and an asset
     * whose latest snapshot is below the warning threshold is "treated as <b>partially covered</b> rather
     * than covered". So the score is not decoration: it decides whether closure may rely on this
     * snapshot at all ({@code PRD-SBM-053}).
     */
    private static int quality(List<Parsed> components, int unmatchable, Map<String, Object> body,
            List<String> warnings) {
        int score = 100;

        int unmatchablePercent = (int) Math.round(100.0 * unmatchable / components.size());
        if (unmatchable > 0) {
            score -= Math.min(50, unmatchablePercent * 2);
            warnings.add("UNMATCHABLE_COMPONENTS:" + unmatchable + "/" + components.size());
        }

        long withoutLicense = components.stream().filter(c -> c.licenses().isEmpty()).count();
        if (withoutLicense > 0) {
            score -= Math.min(15, (int) Math.round(15.0 * withoutLicense / components.size()));
            warnings.add("COMPONENTS_WITHOUT_LICENSE:" + withoutLicense);
        }

        // A flat SBOM with no dependency relationships cannot answer "is this direct or transitive",
        // which is the question that decides whether a finding is actionable by this team.
        // *** THE DOCUMENT IS INGESTIBLE AND UN-RE-SCANNABLE. ***
        //
        // CycloneDX requires `type` on every component. This parser never needed it, so a document
        // without it is accepted here and REFUSED by Trivy on the scheduled re-scan —
        // "failed to unmarshal component type: unsupported type". The submitter finds out months
        // later, if ever: their bill of materials is stored, its coverage counts, and it can never be
        // re-checked against newer intelligence. That is a silent permanent gap in exactly the
        // control the re-scan exists to provide.
        //
        // Warned rather than rejected. The components parsed, the coverage is real, and refusing the
        // submission would discard measurement that genuinely happened over a field this platform
        // does not itself use. PRD-API-038 decides the rest: the submitter is the only party who can
        // fix it, and they see the response.
        int untyped = 0;
        if (body.get("components") instanceof List<?> declared) {
            for (Object item : declared) {
                if (item instanceof Map<?, ?> component
                        && (component.get("type") == null
                            || String.valueOf(component.get("type")).isBlank())) {
                    untyped++;
                }
            }
        }
        if (untyped > 0) {
            warnings.add("COMPONENTS_WITHOUT_TYPE:" + untyped + " — CycloneDX requires `type` on "
                    + "every component. This document will be stored, but a scheduled re-scan cannot "
                    + "read it, so it will never be re-checked against newer vulnerability data.");
            // Costed, not merely mentioned. A warning with no effect on the score is a warning a
            // pipeline filters out, and this one decides whether the artifact is ever looked at
            // again.
            score -= 15;
        }

        boolean anyDirect = components.stream().anyMatch(Parsed::direct);
        if (!anyDirect) {
            score -= 15;
            warnings.add("NO_DEPENDENCY_RELATIONSHIPS");
        }

        if (!body.containsKey("metadata")) {
            score -= 5;
            warnings.add("NO_METADATA");
        }
        if (components.size() < 5) {
            // Not a rejection: a genuinely small application exists. A warning, because the common
            // cause is a scanner that ran against the wrong directory.
            score -= 10;
            warnings.add("SUSPICIOUSLY_FEW_COMPONENTS:" + components.size());
        }
        return Math.max(0, Math.min(100, score));
    }

    /**
     * Parses CycloneDX or Trivy output.
     *
     * <p>Detected rather than declared: "a pipeline that has to declare its format will declare the
     * wrong one", and the cost of guessing wrong here is a rejected submission rather than a silent
     * misread — every unrecognised entry becomes an unmatchable component with a reason.
     */
    private static List<Parsed> parse(Map<String, Object> body) {
        List<Parsed> out = new ArrayList<>();
        Set<String> directRefs = directReferences(body);

        Object cyclone = body.get("components");
        if (cyclone instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    out.add(fromCycloneDx(map, directRefs));
                }
            }
            return out;
        }

        // Trivy: Results[].Packages[] or Results[].Vulnerabilities[] carrying PkgName/InstalledVersion.
        Object results = body.get("Results");
        if (results instanceof List<?> list) {
            for (Object result : list) {
                if (!(result instanceof Map<?, ?> resultMap)) {
                    continue;
                }
                Object packages = resultMap.get("Packages");
                if (packages instanceof List<?> packageList) {
                    for (Object item : packageList) {
                        if (item instanceof Map<?, ?> map) {
                            out.add(fromTrivy(map, String.valueOf(resultMap.get("Type"))));
                        }
                    }
                }
            }
        }
        return out;
    }

    private static Set<String> directReferences(Map<String, Object> body) {
        Set<String> direct = new LinkedHashSet<>();
        Object dependencies = body.get("dependencies");
        if (!(dependencies instanceof List<?> list) || list.isEmpty()) {
            return direct;
        }
        // The root's dependsOn set is the direct dependencies. Anything else is transitive.
        Object rootRef = body.get("metadata") instanceof Map<?, ?> metadata
                && metadata.get("component") instanceof Map<?, ?> component
                ? component.get("bom-ref") : null;
        for (Object entry : list) {
            if (entry instanceof Map<?, ?> map && Objects.equals(map.get("ref"), rootRef)
                    && map.get("dependsOn") instanceof List<?> refs) {
                refs.forEach(ref -> direct.add(String.valueOf(ref)));
            }
        }
        return direct;
    }

    private static Parsed fromCycloneDx(Map<?, ?> map, Set<String> directRefs) {
        String purl = map.get("purl") == null ? "" : String.valueOf(map.get("purl"));
        String name = map.get("name") == null ? "" : String.valueOf(map.get("name"));
        String version = map.get("version") == null ? "" : String.valueOf(map.get("version"));
        boolean direct = directRefs.isEmpty()
                || directRefs.contains(String.valueOf(map.get("bom-ref")));

        List<String> licenses = new ArrayList<>();
        if (map.get("licenses") instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> licenseMap
                        && licenseMap.get("license") instanceof Map<?, ?> license) {
                    Object id = license.get("id") != null ? license.get("id") : license.get("name");
                    if (id != null) {
                        licenses.add(String.valueOf(id));
                    }
                }
            }
        }
        String bomRef = map.get("bom-ref") == null ? purl : String.valueOf(map.get("bom-ref"));
        return canonicalize(purl, name, version, licenses, direct, bomRef);
    }

    private static Parsed fromTrivy(Map<?, ?> map, String type) {
        String name = map.get("Name") != null ? String.valueOf(map.get("Name"))
                : map.get("PkgName") == null ? "" : String.valueOf(map.get("PkgName"));
        String version = map.get("Version") != null ? String.valueOf(map.get("Version"))
                : map.get("InstalledVersion") == null ? ""
                        : String.valueOf(map.get("InstalledVersion"));
        String purl = map.get("PURL") != null ? String.valueOf(map.get("PURL"))
                : ecosystemFor(type).map(e -> "pkg:" + e + "/" + name + "@" + version).orElse("");
        List<String> licenses = new ArrayList<>();
        if (map.get("Licenses") instanceof List<?> list) {
            list.forEach(l -> licenses.add(String.valueOf(l)));
        }
        // Trivy does not distinguish direct from transitive in its package list, so every entry is
        // recorded as transitive rather than guessed at — and the missing relationship lowers quality.
        // Trivy has no bom-ref. The package URL is the only stable handle it offers, and it is
        // what its own Vulnerabilities[] entries can be matched back to.
        return canonicalize(purl, name, version, licenses, false, purl);
    }

    private static Optional<String> ecosystemFor(String trivyType) {
        return switch (trivyType == null ? "" : trivyType.toLowerCase(Locale.ROOT)) {
            case "gomod", "gobinary" -> Optional.of("golang");
            case "npm", "yarn", "pnpm", "node-pkg" -> Optional.of("npm");
            case "pip", "poetry", "pipenv", "python-pkg" -> Optional.of("pypi");
            case "gradle", "pom", "jar" -> Optional.of("maven");
            case "nuget", "dotnet-core" -> Optional.of("nuget");
            case "cargo" -> Optional.of("cargo");
            case "composer" -> Optional.of("composer");
            case "bundler", "gemspec" -> Optional.of("gem");
            case "debian", "ubuntu" -> Optional.of("deb");
            case "redhat", "centos", "rocky", "amazon" -> Optional.of("rpm");
            case "alpine" -> Optional.of("apk");
            default -> Optional.empty();
        };
    }

    /**
     * Canonicalization. {@code PRD-SBM-035}: stored canonicalized per ecosystem with the original
     * retained alongside, so a later canonicalization version can be compared against what arrived.
     *
     * <p>Every failure path produces a component with an enumerated reason. None returns null and none
     * skips: {@code PRD-SBM-037} makes silent skipping the mechanism by which a partially matched SBOM
     * appears fully matched.
     */
    private static Parsed canonicalize(String purl, String name, String version,
            List<String> licenses, boolean direct, String bomRef) {
        if (purl.isBlank() || !purl.startsWith("pkg:")) {
            return new Parsed(purl.isBlank() ? name : purl, "", "", name, version, false,
                    "NOT_A_PACKAGE_URL", licenses, direct, bomRef);
        }
        String remainder = purl.substring("pkg:".length());
        int slash = remainder.indexOf('/');
        if (slash <= 0) {
            return new Parsed(purl, "", "", name, version, false, "NOT_A_PACKAGE_URL", licenses, direct, bomRef);
        }
        String ecosystem = remainder.substring(0, slash).toLowerCase(Locale.ROOT);
        if (!ECOSYSTEMS.contains(ecosystem)) {
            return new Parsed(purl, "", ecosystem, name, version, false, "UNKNOWN_ECOSYSTEM",
                    licenses, direct, bomRef);
        }
        String rest = remainder.substring(slash + 1);
        int at = rest.lastIndexOf('@');
        String packageName = at > 0 ? rest.substring(0, at) : rest;
        String packageVersion = at > 0 ? rest.substring(at + 1) : version;
        int qualifier = packageVersion.indexOf('?');
        if (qualifier >= 0) {
            packageVersion = packageVersion.substring(0, qualifier);
        }
        if (packageName.isBlank()) {
            return new Parsed(purl, "", ecosystem, name, version, false, "MISSING_NAME", licenses, direct, bomRef);
        }
        if (packageVersion.isBlank()) {
            // A component with no version "finds nothing because there is nothing matchable, and the
            // result is indistinguishable from a clean application" — a false negative as good news.
            return new Parsed(purl, "", ecosystem, packageName, "", false, "MISSING_VERSION",
                    licenses, direct, bomRef);
        }
        // Ecosystem-specific rules. Maven is case-sensitive; npm and pypi are not, and pypi treats
        // underscore and hyphen as equivalent. Getting this wrong makes the same package two rows.
        String canonicalName = switch (ecosystem) {
            case "npm" -> packageName.toLowerCase(Locale.ROOT);
            case "pypi" -> packageName.toLowerCase(Locale.ROOT).replace('_', '-');
            default -> packageName;
        };
        String canonical = "pkg:" + ecosystem + "/" + canonicalName + "@" + packageVersion;
        return new Parsed(purl, canonical, ecosystem, canonicalName, packageVersion, true, null,
                licenses, direct, bomRef);
    }

    // ----------------------------------------------------------------------------------------------

    /**
     * Refuses a target the caller's scope does not reach.
     *
     * <p>Applied to an asset that ALREADY EXISTS. A newly created one lands in the caller's own scope
     * by construction and needs no check — it is the found row that could belong to anybody.
     *
     * <p>An asset with no scope recorded is refused too, and that is deliberate: scope is derived,
     * never asserted (PP-4), and a row whose organization is unknown cannot be shown to belong to the
     * caller. It is also not a shape ingestion produces — every asset it creates carries the creating
     * credential's scope — so refusing it costs nothing legitimate.
     */
    static void requireInScope(Principal principal, UUID assetScopeNode, String named) {
        java.util.Set<UUID> scope = principal == null ? java.util.Set.of() : principal.scopeNodeIds();
        if (assetScopeNode != null && scope.contains(assetScopeNode)) {
            return;
        }
        // The message names what the CALLER supplied and nothing about what was found. Saying "that
        // belongs to Vinpearl" would answer the question the refusal exists to withhold.
        throw new OutOfScopeTarget("the target '" + named + "' is not available to this credential");
    }

    /**
     * The named target exists and is outside the caller's scope.
     *
     * <h2>The defect this closes</h2>
     *
     * <p>Both target lookups selected an asset by name or identity key with <b>no scope predicate at
     * all</b>. Row-level security bounds them to the tenant and nothing narrower, so a credential
     * pinned to one division could address any repository in the group by its three-part name and
     * write into it. Measured: a key pinned to Fintech submitted a scan report addressed at
     * {@code Booking Engine/Reservations/booking-payments-api}, an asset scoped to Vinpearl, and the
     * finding was ingested against it — 201, one finding, filed in somebody else's division.
     *
     * <p>Reads were never affected: every query composes the scope predicate. This was the write path,
     * which is the half that gets tested last and matters as much.
     *
     * <h2>The residual, stated</h2>
     *
     * <p>A caller can still learn that a name is taken somewhere in the tenant, because submitting an
     * unknown name creates an asset and submitting this one is refused. Closing that would need
     * identity keys to be scope-local, which changes what a repository IS across the whole product.
     * Being able to detect the existence of a name is a far smaller thing than being able to write to
     * it, and this is the half that was worth closing today.
     */
    public static final class OutOfScopeTarget extends RuntimeException {

        private static final long serialVersionUID = 1L;

        OutOfScopeTarget(String message) {
            super(message);
        }
    }

    /**
     * A resolved target. Package-private because the SARIF import resolves the same three-part address
     * through the same method — ADR-011 puts one normalization and matching path behind both ingestion
     * doors, and a second copy of "find or create this repository" is how the two doors come to disagree
     * about which asset a repository is.
     */
    record Resolved(UUID assetId, UUID scopeNodeId, UUID scopeNodeTypeId,
            UUID scopeCriticalityId, long hierarchyVersion, boolean createdNow) {
    }

    /**
     * Finds or creates the artifact asset. {@code PRD-API-039}.
     *
     * <p>The created asset is <b>unclaimed</b>: it has no owner, so it appears in the unowned queue of
     * {@code PRD-AST-011} rather than being attributed to whoever ran the pipeline. Attributing it would
     * make the ownership record wrong in a way nobody would notice.
     */
    /**
     * Resolves the three-part address, creating what is missing and linking it into the tree.
     *
     * <p><b>The identity key is the whole address, not the repository name.</b> Two applications may
     * each have a repository called {@code api}; keying on the leaf alone would merge them into one
     * artifact and report one application's dependencies against the other. So the key is
     * {@code repo:<application>/<project>/<repository>}, and resubmitting the same three finds the
     * same row — which is exactly what makes a resubmission a replacement.
     *
     * <p>Missing parents are CREATED rather than refused, following {@code PRD-API-039}: an unknown
     * artifact is created unclaimed because a rejected submission loses coverage that genuinely
     * exists, and an unclaimed asset is a visible state somebody can resolve later. Everything
     * created lands in the credential's own scope; the submitter cannot choose it.
     */
    Resolved resolveTarget(Connection connection, Principal principal, Target target)
            throws SQLException {
        String key = "repo:" + blankToDash(target.application()) + "/"
                + blankToDash(target.project()) + "/" + target.repository().strip();

        try (PreparedStatement find = connection.prepareStatement(
                "SELECT id, scope_node_id, scope_node_type_id, scope_criticality_id, "
                        + "scope_hierarchy_ver FROM asset WHERE identity_key = ? LIMIT 1")) {
            find.setString(1, key);
            try (ResultSet results = find.executeQuery()) {
                if (results.next()) {
                    UUID scopeNode = results.getObject(2, UUID.class);
                    requireInScope(principal, scopeNode, key);
                    return new Resolved(results.getObject(1, UUID.class), scopeNode,
                            results.getObject(3, UUID.class),
                            results.getObject(4, UUID.class), results.getLong(5), false);
                }
            }
        }

        // Created under the same rules as an unknown artifact_reference, then linked to its project
        // if one was named and exists. The link is what puts it inside every rollup; without it the
        // repository is real and invisible.
        // The leaf as the display name, the whole address as the key. See the overload's note.
        Resolved created = resolveArtifact(connection, principal, key, target.repository().strip());
        linkUnder(connection, principal, created.assetId(), target.project(), "PROJECT");
        if (target.project() == null || target.project().isBlank()) {
            linkUnder(connection, principal, created.assetId(), target.application(), "APPLICATION");
        }
        return created;
    }

    /** Attaches the new repository under a named parent of the given type, where one is reachable. */
    private static void linkUnder(Connection connection, Principal principal, UUID childId,
            String parentName, String typeCode) throws SQLException {
        if (parentName == null || parentName.isBlank()) {
            return;
        }
        try (PreparedStatement link = connection.prepareStatement("""
                INSERT INTO asset_relationship (tenant_id, from_asset_id, to_asset_id, edge_type,
                                                discovery_source, valid_from, attributes)
                SELECT current_tenant_id(), p.id, ?, 'CONTAINS', 'SBOM_SUBMISSION', now(), '{}'::jsonb
                  FROM asset p JOIN asset_type t ON t.id = p.type_id
                 WHERE t.code = ? AND (p.display_name = ? OR p.identity_key = ?)
                   AND NOT EXISTS (SELECT 1 FROM asset_relationship e
                                    WHERE e.to_asset_id = ? AND e.edge_type = 'CONTAINS'
                                      AND e.valid_until IS NULL)
                 LIMIT 1
                ON CONFLICT DO NOTHING
                """)) {
            link.setObject(1, childId);
            link.setString(2, typeCode);
            link.setString(3, parentName.strip());
            link.setString(4, parentName.strip());
            link.setObject(5, childId);
            link.executeUpdate();
        }
    }

    /**
     * Closes every advisory whose component is no longer present anywhere in the estate.
     *
     * <p><b>Tenant-wide, not per asset, and that is the load-bearing part.</b> A component_advisory
     * row says "this component version is affected"; the component is interned once per tenant and
     * may sit in twenty repositories. Resolving it because ONE of them upgraded would report the
     * estate as fixed while nineteen still ship it — the flattering answer, and the one this whole
     * module exists to prevent. So the test is whether any asset's latest snapshot still contains it.
     *
     * <p>The converse is already handled: {@code SbomGraph#link} clears {@code resolved_at} when a
     * component reappears, so a rollback is recorded as a regression rather than staying closed.
     *
     * <p>{@code resolution} is stated rather than left null — the constraint requires it, and a
     * resolution nobody can explain is indistinguishable from a row somebody deleted.
     */
    private static int reconcileResolved(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE component_advisory ca
                   SET resolved_at = now(),
                       resolution  = 'COMPONENT_NO_LONGER_IN_ANY_SBOM'
                 WHERE ca.resolved_at IS NULL
                   AND NOT EXISTS (SELECT 1 FROM asset_component ac
                                    WHERE ac.component_id = ca.component_id
                                      AND ac.tenant_id = ca.tenant_id)
                """)) {
            return statement.executeUpdate();
        }
    }

    /**
     * The advisories this submission detected for the first time, as alertable events.
     *
     * <p><b>Newly detected, not merely present.</b> The filter is {@code detected_at} inside the last
     * few seconds, so a repository that resubmits an unchanged bill of materials every night does not
     * re-announce the same twelve advisories every night. An alert that fires on unchanged state is
     * an alert people filter into a folder they never open.
     *
     * <p>Read after the transaction rather than inside it: an alert about a submission that then
     * rolled back would be a notification about something that did not happen.
     */
    public List<aspm.app.resource.WebhookAlerts.Event> newAdvisoryEvents(Principal principal,
            UUID snapshotId) {
        List<aspm.app.resource.WebhookAlerts.Event> events = new ArrayList<>();
        if (snapshotId == null) {
            return events;
        }
        try {
            return inTenantTransaction(principal, connection -> {
                List<aspm.app.resource.WebhookAlerts.Event> found = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT a.advisory_key, s.code, a.cvss_score, coalesce(a.summary, a.description),
                               c.name, c.version, ca.fixed_version,
                               app.root_name, mid.project_name, ar.display_name,
                               a.id, ar.id
                          FROM sbom_snapshot snap
                          JOIN component_entry e ON e.snapshot_id = snap.id
                          JOIN component c ON c.id = e.component_id
                          JOIN component_advisory ca ON ca.component_id = c.id
                          JOIN advisory a ON a.id = ca.advisory_id
                          JOIN asset ar ON ar.id = snap.artifact_asset_id
                          LEFT JOIN severity_level s ON s.id = a.severity_id
                          LEFT JOIN LATERAL (
                                SELECT ra.display_name AS root_name FROM asset_composition cc
                                  JOIN asset ra ON ra.id = cc.root_id
                                  JOIN asset_type rt ON rt.id = ra.type_id AND rt.code = 'APPLICATION'
                                 WHERE cc.asset_id = ar.id ORDER BY cc.depth LIMIT 1) app ON true
                          LEFT JOIN LATERAL (
                                SELECT ra.display_name AS project_name FROM asset_composition cc
                                  JOIN asset ra ON ra.id = cc.root_id
                                  JOIN asset_type rt ON rt.id = ra.type_id AND rt.code = 'PROJECT'
                                 WHERE cc.asset_id = ar.id ORDER BY cc.depth LIMIT 1) mid ON true
                         WHERE snap.id = ?
                           AND ca.resolved_at IS NULL
                           AND ca.detected_at > now() - interval '2 minutes'
                        """)) {
                    statement.setObject(1, snapshotId);
                    try (ResultSet r = statement.executeQuery()) {
                        while (r.next()) {
                            Double score = r.getObject(3) == null ? null
                                    : Double.valueOf(r.getDouble(3));
                            found.add(new aspm.app.resource.WebhookAlerts.Event(
                                    r.getString(1), r.getString(2), score, r.getString(4),
                                    r.getString(5), r.getString(6), r.getString(7), r.getString(8),
                                    r.getString(9), r.getString(10), r.getObject(11, UUID.class),
                                    r.getObject(12, UUID.class)));
                        }
                    }
                }
                return found;
            });
        } catch (SQLException e) {
            // Nothing alertable rather than a failed submission. See the note on the caller.
            return List.of();
        }
    }

    /** The snapshot this submission supersedes, if the target already had one. */
    private static String previousSnapshot(Connection connection, UUID assetId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT latest_snapshot_id FROM sbom_coverage_state WHERE asset_id = ?")) {
            statement.setObject(1, assetId);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() && results.getObject(1) != null
                        ? String.valueOf(results.getObject(1)) : null;
            }
        }
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "-" : value.strip();
    }

    private Resolved resolveArtifact(Connection connection, Principal principal, String reference)
            throws SQLException {
        return resolveArtifact(connection, principal, reference, reference);
    }

    /**
     * The same, with a display name distinct from the identity key.
     *
     * <p><b>They were the same string and that was visible on every screen.</b> A three-part target
     * synthesises the key {@code repo:<application>/<project>/<repository>} — deliberately, because two
     * applications may each have a repository called {@code api} and the leaf alone would merge them — and
     * the key was also written as the display name. So the CI/CD dashboard listed rows reading
     * {@code repo:Card Issuing/Authorization/aspm-upload-check}, and where an application name itself
     * contained the prefix the nesting compounded. The identity key is a machine key; the display name is
     * for a person, and conflating them made the estate unreadable for a reason no reader could see.
     *
     * <p>Only what is CREATED changes. Existing rows keep the names they were given: a display name is
     * data somebody may have come to recognise, and rewriting it as a side effect of a bug fix would be
     * the platform editing the user's records without being asked.
     */
    private Resolved resolveArtifact(Connection connection, Principal principal, String reference,
            String displayName) throws SQLException {
        try (PreparedStatement find = connection.prepareStatement(
                "SELECT id, scope_node_id, scope_node_type_id, scope_criticality_id, "
                        + "scope_hierarchy_ver FROM asset WHERE display_name = ? OR identity_key = ? "
                        + "LIMIT 1")) {
            find.setString(1, reference);
            find.setString(2, reference);
            try (ResultSet results = find.executeQuery()) {
                if (results.next()) {
                    UUID scopeNode = results.getObject(2, UUID.class);
                    requireInScope(principal, scopeNode, reference);
                    return new Resolved(results.getObject(1, UUID.class), scopeNode,
                            results.getObject(3, UUID.class),
                            results.getObject(4, UUID.class), results.getLong(5), false);
                }
            }
        }

        // The scope the new asset lands in: the principal's own, which for a pinned service credential
        // is the credential's scope. It cannot be chosen by the submitter.
        UUID scopeNode = principal.scopeNodeIds().stream().findFirst().orElseThrow(
                () -> new IllegalStateException(
                        "the submitting credential has no scope, so a created artifact would have "
                                + "nowhere to live (SEC-AUZ-014 denies on unavailable scope)"));

        UUID typeId;
        UUID criticalityId;
        long hierarchyVersion;
        try (PreparedStatement context = connection.prepareStatement(
                "SELECT n.type_id, coalesce(n.criticality_tier_id, "
                        + "        (SELECT id FROM criticality_tier ORDER BY ordinal LIMIT 1)), "
                        + "       (SELECT max(hierarchy_version) FROM org_closure) "
                        + "  FROM org_node n WHERE n.id = ?")) {
            context.setObject(1, scopeNode);
            try (ResultSet results = context.executeQuery()) {
                if (!results.next()) {
                    throw new IllegalStateException("the credential's scope node does not exist");
                }
                typeId = results.getObject(1, UUID.class);
                criticalityId = results.getObject(2, UUID.class);
                hierarchyVersion = results.getLong(3);
            }
        }

        // *** A DEFECT BEING CORRECTED. ***
        //
        // This took `ORDER BY ordinal LIMIT 1` — whatever asset type happened to sort first, which in
        // this tenant is APPLICATION. So every SBOM naming an artifact the platform had not seen
        // created a new APPLICATION, and the application inventory grew a row per repository that
        // had ever pushed. Six of them accumulated during this module's own testing before anybody
        // looked at the inventory and counted.
        //
        // An artifact that submits a bill of materials is a REPOSITORY. Preferred where the tenant
        // has registered one, falling back to the old behaviour only where they have not — a
        // deployment with no REPOSITORY type should still be able to ingest, and it will get a
        // visibly odd type rather than a silent refusal.
        UUID assetTypeId;
        try (PreparedStatement type = connection.prepareStatement(
                "SELECT id FROM asset_type "
                        + " ORDER BY (code = 'REPOSITORY') DESC, ordinal LIMIT 1")) {
            try (ResultSet results = type.executeQuery()) {
                if (!results.next()) {
                    throw new IllegalStateException(
                            "no asset type is configured, so an artifact cannot be typed. Asset types "
                                    + "are tenant configuration (ADR-027) and the tenant has none.");
                }
                assetTypeId = results.getObject(1, UUID.class);
            }
        }

        try (PreparedStatement create = connection.prepareStatement(
                "INSERT INTO asset (tenant_id, type_id, identity_key, identity_rule_version, "
                        + "display_name, owning_node_id, criticality_mode, lifecycle_state, "
                        + "discovery_source, "
                        + "discovery_method, first_seen_at, last_confirmed_at, scope_node_id, "
                        + "scope_ancestor_path, scope_node_type_id, scope_criticality_id, "
                        + "scope_hierarchy_ver, scope_resolved_at) "
                        // owning_node_id AND the scope descriptor, from the same node.
                        //
                        // This path used to write only the descriptor. The descriptor is the historical
                        // record and the interface has never read it — the interface scopes assets on
                        // owning_node_id — so every repository this door created was owned by nobody
                        // and appeared on no application list, no project list and no dashboard for
                        // anybody whose authority is a branch of the tree. Twenty of sixty-seven assets
                        // in this deployment, all of them the ones a pipeline created, which are
                        // exactly the ones a developer looks for.
                        // last_confirmed_at is the submission instant, and that is the honest value:
                        // INV-AST-12 forbids a manual edit from advancing it, and an SBOM push is
                        // exactly the automated observation it is meant to record. Leaving it null was
                        // rejected by the schema — an asset whose existence was never confirmed is one
                        // PP-1 cannot distinguish from an asset nobody has looked at.
                        + "VALUES (?, ?, ?, 1, ?, ?, 'INHERITED', 'DISCOVERED', 'SBOM_SUBMISSION', "
                        + "'API_PUSH', now(), now(), ?, ?, ?, ?, ?, now()) RETURNING id")) {
            create.setObject(1, principal.tenantId());
            create.setObject(2, assetTypeId);
            create.setString(3, reference);
            create.setString(4, displayName);
            create.setObject(5, scopeNode);
            create.setObject(6, scopeNode);
            create.setArray(7, connection.createArrayOf("uuid", new UUID[] {scopeNode}));
            create.setObject(8, typeId);
            create.setObject(9, criticalityId);
            create.setLong(10, hierarchyVersion);
            try (ResultSet results = create.executeQuery()) {
                results.next();
                return new Resolved(results.getObject(1, UUID.class), scopeNode, typeId, criticalityId,
                        hierarchyVersion, true);
            }
        }
    }

    /**
     * The coverage row. {@code PRD-SBM-056} is called "the single most important requirement in the
     * module": an asset absent from coverage reporting is an asset where "absence reads as absence of
     * problems".
     *
     * <p>{@code PRD-SBM-032}: a snapshot at or below the warning threshold makes the asset
     * <b>partially covered</b>, not covered. The quality band is stored rather than computed at read
     * time, so every reader agrees.
     */
    private static void updateCoverage(Connection connection, Principal principal, Resolved artifact,
            UUID snapshotId, int quality, Set<String> ecosystems) throws SQLException {
        String band = quality > 60 ? "ABOVE_WARNING" : quality > 0 ? "AT_OR_BELOW_WARNING" : "REJECTED";
        try (PreparedStatement upsert = connection.prepareStatement(
                "INSERT INTO sbom_coverage_state (tenant_id, asset_id, latest_snapshot_id, "
                        + "latest_snapshot_at, quality, covered_ecosystems, freshness_threshold_days, "
                        + "accountable_owner_id) VALUES (?, ?, ?, now(), ?, ?, 30, ?) "
                        + "ON CONFLICT (tenant_id, asset_id) DO UPDATE SET "
                        + "latest_snapshot_id = EXCLUDED.latest_snapshot_id, "
                        + "latest_snapshot_at = EXCLUDED.latest_snapshot_at, "
                        + "quality = EXCLUDED.quality, "
                        + "covered_ecosystems = EXCLUDED.covered_ecosystems, updated_at = now()")) {
            upsert.setObject(1, principal.tenantId());
            upsert.setObject(2, artifact.assetId());
            upsert.setObject(3, snapshotId);
            upsert.setString(4, band);
            upsert.setArray(5, connection.createArrayOf("text", ecosystems.toArray()));
            upsert.setObject(6, principal.principalId());
            upsert.executeUpdate();
        }
    }

    private UUID intern(Connection connection, Principal principal, Parsed component)
            throws SQLException {
        // ADR-032: interned TENANT-SCOPED. A global intern was rejected on tenant-boundary grounds —
        // one tenant's component row would otherwise be a channel to another's.
        try (PreparedStatement find = connection.prepareStatement(
                "SELECT id FROM component WHERE purl_original = ? AND canonicalization_version = 1 "
                        + "LIMIT 1")) {
            find.setString(1, component.purlOriginal());
            try (ResultSet results = find.executeQuery()) {
                if (results.next()) {
                    return results.getObject(1, UUID.class);
                }
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO component (tenant_id, purl_canonical, purl_original, "
                        + "canonicalization_version, ecosystem, name, version, is_canonicalizable, "
                        + "unmatchable_reason) VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?) RETURNING id")) {
            insert.setObject(1, principal.tenantId());
            insert.setString(2, component.canonicalizable() ? component.purlCanonical()
                    : component.purlOriginal());
            insert.setString(3, component.purlOriginal());
            insert.setString(4, component.ecosystem().isBlank() ? "unknown" : component.ecosystem());
            insert.setString(5, component.name().isBlank() ? "(unnamed)" : component.name());
            insert.setString(6, component.version());
            insert.setBoolean(7, component.canonicalizable());
            insert.setString(8, component.unmatchableReason());
            try (ResultSet results = insert.executeQuery()) {
                results.next();
                return results.getObject(1, UUID.class);
            }
        }
    }

    private static byte[] sha256(String content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every supported JDK", e);
        }
    }

    @FunctionalInterface
    private interface InTransaction<T> {
        T apply(Connection connection) throws SQLException;
    }

    private <T> T inTenantTransaction(Principal principal, InTransaction<T> body) throws SQLException {
        try (Connection connection =
                aspm.app.persistence.TenantConnections.open(dataSource, principal)) {
            try {
                T result = body.apply(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /** Coverage rows for the dashboard, including assets that never submitted. */
    public List<Map<String, Object>> coverage(Principal principal) throws SQLException {
        return inTenantTransaction(principal, connection -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            // A LEFT JOIN from asset, not a scan of the coverage table. PRD-SBM-056: an asset with no
            // snapshot must appear as NEVER_SUBMITTED and MUST NOT be absent — and it is absent from
            // sbom_coverage_state until something writes a row for it.
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT a.id, a.display_name, c.quality, c.latest_snapshot_at, "
                            + "       c.covered_ecosystems, s.component_count, s.quality_score, "
                            + "       c.freshness_threshold_days "
                            + "  FROM asset a "
                            + "  LEFT JOIN sbom_coverage_state c ON c.asset_id = a.id "
                            + "  LEFT JOIN sbom_snapshot s ON s.id = c.latest_snapshot_id "
                            + " WHERE a.lifecycle_state <> 'RETIRED' "
                            + " ORDER BY a.display_name")) {
                try (ResultSet results = statement.executeQuery()) {
                    while (results.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("asset_id", results.getObject(1, UUID.class).toString());
                        row.put("display_name", results.getString(2));
                        row.put("quality", results.getString(3));
                        row.put("latest_snapshot_at", results.getObject(4));
                        java.sql.Array ecosystems = results.getArray(5);
                        row.put("covered_ecosystems", ecosystems == null ? List.of()
                                : List.of((Object[]) ecosystems.getArray()));
                        int count = results.getInt(6);
                        row.put("component_count", results.wasNull() ? null : Integer.valueOf(count));
                        int score = results.getInt(7);
                        row.put("quality_score", results.wasNull() ? null : Integer.valueOf(score));
                        rows.add(row);
                    }
                }
            }
            return List.copyOf(rows);
        });
    }
}
