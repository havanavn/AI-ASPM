package aspm.module.ingestion.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A registered parser, per DOC-11 section 4 and {@code PRD-ING-024}.
 *
 * <p><b>A definition, not a code branch.</b> {@code PRD-ING-024}'s rationale: "A shared service with per-format
 * branches means every new format touches code that all formats depend on, and a parser regression silently
 * corrupts ingested data for unrelated sources."
 *
 * <p><b>{@code PRD-ING-027}: an undeclared source version is rejected, never parsed best-effort.</b> "Best-effort
 * parsing of an unknown version produces partially mapped findings that appear valid. Rejection with a named gap
 * is actionable." {@link #acceptsFormatVersion} therefore has no fuzzy match, no prefix match and no
 * highest-known-version fallback — the three shapes a best-effort implementation takes.
 */
public final class ParserDefinition {

    /** Declared limits. A document exceeding any of these rejects the session (DOC-11 section 9). */
    public record Limits(
            int maxDocumentBytes, int maxRecordCount, int maxNestingDepth, int maxRecordBytes,
            java.time.Duration timeout) {

        public Limits {
            if (maxDocumentBytes <= 0 || maxRecordCount <= 0 || maxNestingDepth <= 0 || maxRecordBytes <= 0) {
                throw new IllegalArgumentException("every limit must be positive and declared");
            }
            Objects.requireNonNull(timeout, "a parse timeout is required");
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException(
                        "a parse timeout is required and positive. A parser is a hardened, isolated worker "
                                + "processing attacker-influenced input (DOC-11 section 2); an unbounded parse is "
                                + "a denial of service on the ingestion tier.");
            }
        }
    }

    private final String code;
    private final int parserVersion;
    private final String format;
    private final Set<String> supportedFormatVersions;
    private final FingerprintInputs.FindingClass findingClass;
    private final Map<String, Integer> severityMap;
    private final AssetAnchorResolution.Strategy anchorStrategy;
    private final AssetClassAssignment.AssetClass assetClass;
    private final Limits limits;

    public ParserDefinition(String code, int parserVersion, String format,
            Set<String> supportedFormatVersions, FingerprintInputs.FindingClass findingClass,
            Map<String, Integer> severityMap, AssetAnchorResolution.Strategy anchorStrategy,
            AssetClassAssignment.AssetClass assetClass, Limits limits) {
        this.code = Objects.requireNonNull(code, "code is required");
        this.format = Objects.requireNonNull(format, "format is required");
        this.supportedFormatVersions = Set.copyOf(
                Objects.requireNonNull(supportedFormatVersions, "supportedFormatVersions is required"));
        this.findingClass = Objects.requireNonNull(findingClass, "findingClass is required");
        this.severityMap = Map.copyOf(Objects.requireNonNull(severityMap, "severityMap is required"));
        this.anchorStrategy = Objects.requireNonNull(anchorStrategy, "an anchor strategy is required");
        this.assetClass = Objects.requireNonNull(assetClass,
                "PRD-ING-032: the asset class is assigned by the parser and is not tenant-configurable");
        this.limits = Objects.requireNonNull(limits, "declared limits are required");

        if (parserVersion < 1) {
            throw new IllegalArgumentException(
                    "PRD-ING-025: every parser declares its version, and the version is recorded per finding. "
                            + "Without it, a systematic mapping error introduced by a parser change cannot be "
                            + "scoped — there is no way to ask which findings that version produced.");
        }
        this.parserVersion = parserVersion;
        if (this.supportedFormatVersions.isEmpty()) {
            throw new IllegalArgumentException(
                    "a parser must declare at least one supported format version. An empty list would make "
                            + "PRD-ING-027's rejection message name no supported versions, which is the "
                            + "unactionable rejection it exists to prevent.");
        }
    }

    /**
     * Whether this parser accepts a declared source version. {@code PRD-ING-027}.
     *
     * <p>Exact set membership. No prefix match, no semver range, no "highest known version" fallback — each of
     * those is a best-effort parse wearing a different hat, and each produces "partially mapped findings that
     * appear valid".
     */
    public boolean acceptsFormatVersion(String formatVersion) {
        return supportedFormatVersions.contains(
                Objects.requireNonNull(formatVersion, "the source format version is required"));
    }

    /**
     * The rejection message for an unsupported version, naming what is supported.
     *
     * <p>{@code PRD-ING-027} requires "the supported versions named". A rejection that does not is a dead end for
     * whoever has to act on it.
     */
    public String unsupportedVersionRejection(String formatVersion) {
        return "format " + format + " version '" + formatVersion + "' is not supported by parser " + code
                + " v" + parserVersion + ". Supported: "
                + new java.util.TreeSet<>(supportedFormatVersions)
                + ". Not parsed on a best-effort basis, because partially mapped findings appear valid "
                + "(PRD-ING-027).";
    }

    /**
     * Maps a source severity to an internal ordinal.
     *
     * <p>{@code PRD-ING-040}: an unmappable severity produces {@code UNKNOWN} and is reported as a mapping gap,
     * <b>never defaulted to a middle value</b>. "A defaulted severity is indistinguishable from a reported one and
     * silently corrupts prioritization for every finding from that source."
     *
     * @return the ordinal, or empty where the source value maps to nothing — which the caller must record as a
     *     gap rather than substitute for
     */
    public Optional<Integer> mapSeverity(String sourceValue) {
        if (sourceValue == null) {
            // PRD-ING-021: a field absent in the source is null, and the parser does not infer. An absent
            // severity is a gap, exactly as an unrecognised one is.
            return Optional.empty();
        }
        return Optional.ofNullable(severityMap.get(sourceValue));
    }

    public String code() {
        return code;
    }

    /** Recorded per finding, per {@code PRD-ING-025}. */
    public int parserVersion() {
        return parserVersion;
    }

    public String format() {
        return format;
    }

    public Set<String> supportedFormatVersions() {
        return supportedFormatVersions;
    }

    public FingerprintInputs.FindingClass findingClass() {
        return findingClass;
    }

    public AssetAnchorResolution.Strategy anchorStrategy() {
        return anchorStrategy;
    }

    public AssetClassAssignment.AssetClass assetClass() {
        return assetClass;
    }

    public Limits limits() {
        return limits;
    }

    /**
     * The registry. Validated as a set, because two parsers claiming one format and version is ambiguous and the
     * ambiguity is only visible with the whole registry in hand.
     */
    public static List<String> validateRegistry(Set<ParserDefinition> registry) {
        Objects.requireNonNull(registry, "registry is required");
        List<String> findings = new java.util.ArrayList<>();
        Map<String, String> claimedBy = new LinkedHashMap<>();

        for (ParserDefinition parser : registry) {
            for (String version : parser.supportedFormatVersions()) {
                String key = parser.format() + "@" + version;
                String existing = claimedBy.putIfAbsent(key, parser.code());
                if (existing != null && !existing.equals(parser.code())) {
                    findings.add("two parsers claim " + key + ": '" + existing + "' and '" + parser.code()
                            + "'. DOC-09 section 15 resolves a parser per format and version before parsing "
                            + "starts; an ambiguous claim makes that resolution non-deterministic, and which "
                            + "parser ran would depend on registry iteration order.");
                }
            }
        }
        return List.copyOf(findings);
    }
}
