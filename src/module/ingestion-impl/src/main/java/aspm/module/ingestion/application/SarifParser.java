package aspm.module.ingestion.application;

import aspm.module.ingestion.domain.AssetAnchorResolution;
import aspm.module.ingestion.domain.AssetClassAssignment;
import aspm.module.ingestion.domain.CodeLocationNormalization;
import aspm.module.ingestion.domain.FingerprintInputs;
import aspm.module.ingestion.domain.ParserDefinition;
import aspm.module.ingestion.domain.QuarantinedRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The SARIF 2.1.0 parser. DOC-11 §4, {@code PRD-ING-024}.
 *
 * <h2>Why SARIF and not four parsers</h2>
 *
 * <p>semgrep, mobsfscan, nuclei and CodeQL all emit SARIF, so one registered parser covers four tools rather
 * than four code paths that must each be kept correct. That is the whole argument of {@code PRD-ING-024}: "a
 * shared service with per-format branches means every new format touches code that all formats depend on".
 * There are no per-tool branches below. Where tools differ they differ in OPTIONAL SARIF fields, and each is
 * read as the specification defines it — absent means absent, per {@code PRD-ING-021}, and never a default.
 *
 * <p>Each tool still needs its own fixture: {@code TST-ING-001} requires a fixture per source and version, and
 * "reads SARIF" is not evidence that it reads what semgrep actually writes. Four fixtures, one parser.
 *
 * <h2>What this class is not</h2>
 *
 * <p>It touches no database, resolves no asset and writes nothing. It turns a parsed document into records,
 * quarantine entries and mapping gaps, so that identity and severity mapping — the two things that are
 * expensive to get wrong — are testable without a running platform. The caller anchors, deduplicates and
 * persists.
 *
 * <p>It also does not decide the finding's severity ROW. It reports an ordinal, because an ordinal is
 * structural (1 is the most severe, always) while a severity's code and name are tenant vocabulary
 * (ADR-027). Resolving the ordinal against the tenant's scale is the caller's job, and an ordinal the
 * tenant's scale does not define is a mapping gap rather than a rounded-down guess.
 */
public final class SarifParser {

    /** The format code, as {@code import_session.source_format} records it. */
    public static final String FORMAT = "SARIF";

    /** The parser code, recorded per finding so a mapping error can be scoped ({@code PRD-ING-025}). */
    public static final String CODE = "sarif-2.1.0";

    /** Bumped whenever the mapping below changes in a way that alters what a finding says. */
    public static final int PARSER_VERSION = 1;

    /**
     * The only version accepted, and the only one there is.
     *
     * <p>SARIF 2.1.0 is the OASIS standard; the "2.1.0-rtm.N" strings some tools emit in {@code $schema} are
     * errata revisions of it and are accepted as that version — the object model is identical, which is the
     * only question that matters to a parser. An unrecognised version is rejected whole
     * ({@code PRD-ING-027}); it is not parsed hopefully.
     */
    public static final Set<String> SUPPORTED_VERSIONS = Set.of("2.1.0");

    private SarifParser() {
        throw new AssertionError("not instantiable");
    }

    /**
     * The registry entry.
     *
     * <p>The limits are declared rather than defaulted, per DOC-11 §9. They are sized for a real repository
     * scan: CodeQL over a large monorepo emits tens of thousands of results, and a limit below that would
     * reject the submissions this parser exists to accept — while an unbounded parse over
     * attacker-influenced input is a denial of service on the ingestion tier.
     */
    public static ParserDefinition definition() {
        return new ParserDefinition(CODE, PARSER_VERSION, FORMAT, SUPPORTED_VERSIONS,
                FingerprintInputs.FindingClass.CODE,
                // SARIF's four levels, mapped to ORDINALS and not to names. `none` is deliberately absent:
                // it means the rule did not fail, so a `none` result is informational output rather than a
                // weakness, and inventing a severity for it would put a finding on somebody's queue that its
                // own tool says is not a problem. PRD-ING-040 then makes it a reported gap, not a silent drop.
                Map.of("error", Integer.valueOf(2),
                        "warning", Integer.valueOf(3),
                        "note", Integer.valueOf(4)),
                // The submission names its target; the document does not get to assert one. A SARIF file can
                // carry repository and revision properties, and honouring them would let a caller file
                // findings against an asset it was never authorised to touch (PRD-ING-031).
                AssetAnchorResolution.Strategy.EXPLICIT_IN_SOURCE,
                // APPLICATION, so these findings count toward application posture. A repository's code IS
                // the product's subject; classifying it as infrastructure would keep it out of the one
                // figure the platform exists to report.
                AssetClassAssignment.AssetClass.APPLICATION,
                new ParserDefinition.Limits(64 * 1024 * 1024, 50_000, 64, 512 * 1024,
                        java.time.Duration.ofSeconds(60)));
    }

    // ----------------------------------------------------------------------------------------------

    /**
     * One SARIF result, mapped and ready to be anchored and fingerprinted.
     *
     * @param index the result's position in the document, counted across runs. Quarantine and every diagnostic
     *     message names it, because "one record failed" is not correctable and DOC-11 §9 requires it to be
     * @param runIndex the run this came from, and {@code indexInRun} its position within that run. Together they
     *     are a JSON pointer into the archived document — {@code /runs/0/results/12} — which is what makes the
     *     retained raw record retrievable rather than merely counted ({@code PRD-ING-022}). The flat
     *     {@code index} cannot do that job: it does not survive a document with two runs
     * @param severityOrdinal the mapped ordinal, or {@code null} where the source value maps to nothing —
     *     which the caller records as a gap and never substitutes for ({@code PRD-ING-040})
     * @param reportedSeverityRaw exactly what the source said, retained whether or not it mapped. It is the
     *     only way to answer "what did the tool call this" after a mapping is corrected
     * @param structuralContextHash what keeps two weaknesses in one file distinct once the location is reduced
     *     to a basename. Derived here rather than taken from the tool — see
     *     {@link #structuralHash} for why
     */
    public record Result(
            int index,
            int runIndex,
            int indexInRun,
            String toolName,
            String toolVersion,
            String ruleIdentity,
            String title,
            String message,
            String helpUri,
            String reportedSeverityRaw,
            Integer severityOrdinal,
            String reportedPath,
            String normalizedLocation,
            Integer startLine,
            String enclosingConstruct,
            String snippet,
            String structuralContextHash,
            Map<String, String> partialFingerprints,
            String rawRecord) {

        public Result {
            Objects.requireNonNull(ruleIdentity, "a rule identity is required");
            Objects.requireNonNull(normalizedLocation, "a normalized location is required");
            Objects.requireNonNull(structuralContextHash, "a structural context hash is required");
            partialFingerprints = Map.copyOf(
                    Objects.requireNonNull(partialFingerprints, "partialFingerprints is required"));
        }
    }

    /** A record held back, with everything needed to correct it without the source file ({@code PRD-ING-039}). */
    public record Held(int index, QuarantinedRecord.Reason reason, List<String> failingFields,
            String rawContent) {

        public Held {
            failingFields = List.copyOf(Objects.requireNonNull(failingFields, "failingFields is required"));
            Objects.requireNonNull(rawContent, "the raw content is required");
        }
    }

    /**
     * What one document yielded.
     *
     * @param gaps mapping gaps in the order found, each naming the result and the unmapped value. Reported to
     *     the submitter rather than logged: they are the only party who can fix a tool's severity vocabulary
     *     ({@code PRD-API-038})
     * @param toolNames every driver named in the document. A SARIF file may hold several runs from several
     *     tools, and reporting one of them would misattribute the rest
     * @param notWeaknesses results the document itself says are not failures — {@code kind} of {@code pass},
     *     {@code notApplicable}, {@code informational} or {@code review}. Skipped rather than ingested, and
     *     COUNTED rather than dropped silently: a submitter whose 500-result report produced 12 findings
     *     needs to see where the other 488 went, or the platform looks like it lost them
     */
    public record Parsed(List<Result> results, List<Held> quarantined, List<String> gaps,
            Set<String> toolNames, int resultCount, int notWeaknesses) {

        public Parsed {
            results = List.copyOf(Objects.requireNonNull(results, "results is required"));
            quarantined = List.copyOf(Objects.requireNonNull(quarantined, "quarantined is required"));
            gaps = List.copyOf(Objects.requireNonNull(gaps, "gaps is required"));
            toolNames = Set.copyOf(Objects.requireNonNull(toolNames, "toolNames is required"));
        }
    }

    /**
     * The document was refused whole, before any record was normalized.
     *
     * <p>Distinct from quarantine on purpose. DOC-11 §9: a malformed record must not discard the file, and a
     * malformed FILE must not be half-ingested — "a truncated record set could be read as a complete one".
     */
    public static final class Rejected extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String code;

        public Rejected(String code, String message) {
            super(message);
            this.code = Objects.requireNonNull(code, "a rejection code is required");
        }

        public String code() {
            return code;
        }
    }

    // ----------------------------------------------------------------------------------------------

    /**
     * Parses a SARIF document.
     *
     * @param document the parsed JSON object. Nesting depth is already bounded by the JSON reader, which is
     *     the recursive component and therefore the one a deeply nested body attacks
     * @param documentBytes the size of the document as submitted, checked against the declared limit before
     *     any record is read
     */
    public static Parsed parse(Map<String, Object> document, int documentBytes) {
        Objects.requireNonNull(document, "a document is required");
        ParserDefinition parser = definition();

        if (documentBytes > parser.limits().maxDocumentBytes()) {
            throw new Rejected("DOCUMENT_TOO_LARGE", "the document is " + documentBytes
                    + " bytes and the declared limit is " + parser.limits().maxDocumentBytes()
                    + " (DOC-11 section 9). Split the run rather than raising the limit: an unbounded parse "
                    + "over attacker-influenced input is a denial of service on the ingestion tier.");
        }

        String version = declaredVersion(document);
        if (!parser.acceptsFormatVersion(version)) {
            throw new Rejected("UNSUPPORTED_FORMAT_VERSION", parser.unsupportedVersionRejection(version));
        }

        List<?> runs = list(document.get("runs"));
        if (runs.isEmpty()) {
            // Not an error and not silence. A scan that ran and found nothing is a real, valuable answer —
            // PP-1's measured-and-clean — and the caller records it as a session with zero records. What
            // would be wrong is treating a document with no `runs` key as that answer, which is why the
            // key's ABSENCE is refused below rather than here.
            if (!document.containsKey("runs")) {
                throw new Rejected("SCHEMA_VALIDATION",
                        "'runs' is required by SARIF 2.1.0 and is absent. A document with no runs array is "
                                + "not an empty scan result; it is a document this parser cannot vouch for.");
            }
            return new Parsed(List.of(), List.of(), List.of(), Set.of(), 0, 0);
        }

        List<Result> results = new ArrayList<>();
        List<Held> quarantined = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        Set<String> toolNames = new LinkedHashSet<>();
        int index = 0;
        int notWeaknesses = 0;

        int runIndex = -1;
        for (Object runObject : runs) {
            runIndex++;
            Map<String, Object> run = object(runObject);
            if (run == null) {
                continue;
            }
            Map<String, Object> driver = object(path(run, "tool", "driver"));
            String toolName = text(driver == null ? null : driver.get("name"));
            // `semanticVersion` first: SARIF defines it as the tool's version in semantic-version form, and
            // `version` is free text a tool may set to a build number. A version recorded per finding
            // (PRD-ING-025) is only useful if it means the same thing across submissions.
            String toolVersion = driver == null ? null
                    : firstText(driver.get("semanticVersion"), driver.get("version"));
            if (toolName != null) {
                toolNames.add(toolName);
            }
            Map<String, Map<String, Object>> rules = rulesById(driver);

            int indexInRun = -1;
            for (Object resultObject : list(run.get("results"))) {
                index++;
                indexInRun++;
                if (index > parser.limits().maxRecordCount()) {
                    throw new Rejected("TOO_MANY_RECORDS", "the document holds more than "
                            + parser.limits().maxRecordCount() + " results, the declared limit "
                            + "(DOC-11 section 9). The whole document is refused rather than truncated: a "
                            + "partial record set read as a complete one would close findings that were "
                            + "never re-reported.");
                }
                Map<String, Object> result = object(resultObject);
                String raw = Snippet.of(resultObject);
                if (result == null) {
                    quarantined.add(new Held(index, QuarantinedRecord.Reason.SCHEMA_VALIDATION,
                            List.of("results[" + (index - 1) + "]"), raw));
                    continue;
                }
                if (raw.length() > parser.limits().maxRecordBytes()) {
                    quarantined.add(new Held(index, QuarantinedRecord.Reason.SCHEMA_VALIDATION,
                            List.of("results[" + (index - 1) + "]"),
                            raw.substring(0, parser.limits().maxRecordBytes())));
                    continue;
                }

                // *** A RESULT THE DOCUMENT SAYS IS NOT A FAILURE IS NOT A FINDING. ***
                //
                // SARIF's `kind` says whether the rule failed; `level` says how bad it is if it did. A
                // result of kind `pass`, `notApplicable`, `informational` or `review` is the tool
                // reporting that it looked and found nothing wrong — genuinely valuable output, and the
                // opposite of a weakness. Ingesting it ungraded would file a finding against a check that
                // SUCCEEDED, which puts work on somebody's queue that its own tool says is not there.
                //
                // Skipped and counted. The count is reported because 500 results producing 12 findings is
                // a number somebody will otherwise read as data loss.
                String kind = text(result.get("kind"));
                if (kind != null && !"fail".equalsIgnoreCase(kind)) {
                    notWeaknesses++;
                    continue;
                }

                Map<String, Object> rule = ruleFor(result, rules);
                String ruleIdentity = ruleIdentity(result, rule);
                if (ruleIdentity == null) {
                    // Identity, not presentation. A result with no rule identity cannot be fingerprinted, so
                    // ingesting it would create a finding that a second submission of the same scan cannot
                    // recognise — one row per run, for ever. Held with the field named so it is correctable.
                    quarantined.add(new Held(index, QuarantinedRecord.Reason.SCHEMA_VALIDATION,
                            List.of("ruleId"), raw));
                    continue;
                }

                Map<String, Object> location = primaryLocation(result);
                String reportedPath = location == null ? null
                        : text(path(location, "physicalLocation", "artifactLocation", "uri"));
                if (reportedPath == null || reportedPath.isBlank()) {
                    quarantined.add(new Held(index, QuarantinedRecord.Reason.SCHEMA_VALIDATION,
                            List.of("locations[0].physicalLocation.artifactLocation.uri"), raw));
                    continue;
                }
                if (isRuntimeTarget(reportedPath)) {
                    // *** A RUNTIME TARGET IS HELD, NOT INGESTED AS CODE. ***
                    //
                    // SARIF is a container, not a kind of finding. A DAST tool — nuclei is the one in this
                    // deployment's toolchain — reports a URL where a static analyser reports a file, and this
                    // parser produces CODE findings, whose identity is (rule, asset, normalized code
                    // location, structural context). Feeding a URL through that would reduce
                    // "https://api.example.com/v1/login" to the basename "login" and file it as static
                    // analysis over code: a finding whose class, location and dedup behaviour are all wrong,
                    // and wrong in a way that looks right in a list.
                    //
                    // Held with the reason named, so the submitter sees it rather than the platform silently
                    // deciding for them (PP-9, PRD-API-038). Ingesting these needs the RUNTIME identity path,
                    // and that is blocked on a decision recorded as a gap rather than guessed at here: the
                    // RUNTIME class declares `parameter_name` as an identity input, a template match against
                    // a URL has no parameter, and FingerprintInputs.Builder#build refuses a declared input
                    // that is absent. Supplying "" would assert the source said "no parameter" when it was
                    // never asked, which PRD-ING-021 forbids in as many words.
                    quarantined.add(new Held(index, QuarantinedRecord.Reason.SCHEMA_VALIDATION,
                            List.of("locations[0].physicalLocation.artifactLocation.uri"
                                    + " (a runtime target, not a code location)"), raw));
                    continue;
                }

                Map<String, Object> region = object(path(location, "physicalLocation", "region"));
                String snippet = region == null ? null : text(path(region, "snippet", "text"));
                Integer startLine = region == null ? null : integer(region.get("startLine"));
                String enclosing = enclosingConstruct(location);
                Map<String, String> fingerprints = stringMap(result.get("partialFingerprints"));

                String rawSeverity = reportedSeverity(result, rule);
                Integer ordinal = mapSeverity(parser, result, rule);
                if (ordinal == null) {
                    gaps.add("result " + index + " (" + ruleIdentity + "): severity '" + rawSeverity
                            + "' maps to nothing, so it is recorded with no severity rather than a guessed "
                            + "one (PRD-ING-040)");
                }

                results.add(new Result(index, runIndex, indexInRun, toolName, toolVersion, ruleIdentity,
                        title(result, rule, ruleIdentity), message(result), text(helpUri(rule)),
                        rawSeverity, ordinal,
                        reportedPath, CodeLocationNormalization.normalize(reportedPath), startLine,
                        enclosing, snippet,
                        structuralHash(enclosing, snippet, startLine, ruleIdentity),
                        fingerprints, raw));
            }
        }
        return new Parsed(results, quarantined, gaps, toolNames, index, notWeaknesses);
    }

    // ----------------------------------------------------------------------------------------------

    /**
     * The version this document declares.
     *
     * <p>Read from {@code version}, falling back to the schema URL, because several tools set one and not the
     * other. Both are read as an exact statement: {@code 2.1.0-rtm.5} is version 2.1.0 with errata, and the
     * errata do not change the object model. Anything else reaches
     * {@link ParserDefinition#acceptsFormatVersion} as-is and is refused there with the supported versions
     * named — never coerced to the nearest thing this parser knows.
     */
    private static String declaredVersion(Map<String, Object> document) {
        String declared = text(document.get("version"));
        if (declared == null) {
            String schema = text(document.get("$schema"));
            if (schema != null) {
                var matcher = java.util.regex.Pattern.compile("sarif[-/](\\d+\\.\\d+\\.\\d+)")
                        .matcher(schema.toLowerCase(Locale.ROOT));
                if (matcher.find()) {
                    declared = matcher.group(1);
                }
            }
        }
        if (declared == null) {
            throw new Rejected("SCHEMA_VALIDATION",
                    "the document declares no SARIF version, in 'version' or in '$schema'. "
                            + "PRD-ING-027: an undeclared source version is rejected rather than assumed, "
                            + "because a best-effort parse produces partially mapped findings that look "
                            + "valid. Supported: " + new java.util.TreeSet<>(SUPPORTED_VERSIONS) + ".");
        }
        int errata = declared.indexOf("-rtm.");
        return errata > 0 ? declared.substring(0, errata) : declared;
    }

    /** The driver's rules, by identifier, so a result carrying only {@code ruleIndex} still resolves. */
    private static Map<String, Map<String, Object>> rulesById(Map<String, Object> driver) {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        if (driver == null) {
            return byId;
        }
        int at = 0;
        for (Object ruleObject : list(driver.get("rules"))) {
            Map<String, Object> rule = object(ruleObject);
            if (rule != null) {
                String id = text(rule.get("id"));
                if (id != null) {
                    byId.put(id, rule);
                }
                // Indexed as well as named, because `ruleIndex` is a position in THIS array and a result may
                // carry only that. Keyed by a string that cannot collide with a rule id.
                byId.put("#" + at, rule);
            }
            at++;
        }
        return byId;
    }

    private static Map<String, Object> ruleFor(Map<String, Object> result,
            Map<String, Map<String, Object>> rules) {
        String id = firstText(result.get("ruleId"), path(result, "rule", "id"));
        if (id != null && rules.containsKey(id)) {
            return rules.get(id);
        }
        Integer index = integer(firstNonNull(result.get("ruleIndex"), path(result, "rule", "index")));
        return index == null ? null : rules.get("#" + index);
    }

    /**
     * The rule identity, which is the first input of a CODE fingerprint.
     *
     * <p>{@code ruleId} where the result carries one, then the rule object it points at. A tool that emits
     * only {@code ruleIndex} — the SARIF-compliant compact form — is resolved through the driver's rule array
     * rather than refused, because refusing it would reject nuclei and mobsfscan output that is entirely
     * valid.
     */
    private static String ruleIdentity(Map<String, Object> result, Map<String, Object> rule) {
        String direct = firstText(result.get("ruleId"), path(result, "rule", "id"));
        if (direct != null) {
            return direct;
        }
        return rule == null ? null : text(rule.get("id"));
    }

    private static String title(Map<String, Object> result, Map<String, Object> rule, String ruleIdentity) {
        // The rule's own short description first: it names the WEAKNESS ("Use of hard-coded credentials"),
        // while the result's message describes this occurrence of it. A list of findings titled by occurrence
        // reads as a log; titled by weakness it reads as a queue somebody can work.
        String fromRule = rule == null ? null
                : firstText(path(rule, "shortDescription", "text"), path(rule, "name", "text"),
                        rule.get("name"));
        if (fromRule != null) {
            return truncate(fromRule, 200);
        }
        String fromMessage = text(path(result, "message", "text"));
        return truncate(fromMessage == null ? ruleIdentity : fromMessage, 200);
    }

    /**
     * The message, with SARIF's argument substitution applied.
     *
     * <p>{@code message.text} where present. Where a tool emits only {@code message.id}, the text lives on the
     * rule's message table and its {@code {0}} placeholders are filled from {@code arguments} — the
     * specification's own mechanism. Unresolvable leaves the placeholders visible rather than blanking the
     * message: a reader seeing {@code {0}} can tell the tool omitted an argument, and a blank message hides
     * that there was anything to say.
     */
    private static String message(Map<String, Object> result) {
        String direct = text(path(result, "message", "text"));
        if (direct != null) {
            return applyArguments(direct, list(path(result, "message", "arguments")));
        }
        return null;
    }

    private static String applyArguments(String template, List<?> arguments) {
        String out = template;
        for (int i = 0; i < arguments.size(); i++) {
            String value = text(arguments.get(i));
            if (value != null) {
                out = out.replace("{" + i + "}", value);
            }
        }
        return out;
    }

    private static Object helpUri(Map<String, Object> rule) {
        return rule == null ? null : firstNonNull(rule.get("helpUri"), path(rule, "help", "text"));
    }

    /**
     * The first location, which is the one SARIF calls primary.
     *
     * <p>Only the first. A result may list many — a taint path has a source and a sink — and a finding
     * anchored to all of them would be one finding claiming several identities. The rest stay in the retained
     * raw record, so nothing is lost and the path is still readable.
     */
    private static Map<String, Object> primaryLocation(Map<String, Object> result) {
        for (Object candidate : list(result.get("locations"))) {
            Map<String, Object> location = object(candidate);
            if (location != null) {
                return location;
            }
        }
        return null;
    }

    /**
     * Whether a reported location addresses a running system rather than a file.
     *
     * <p>Scheme only. {@code file:} URIs and bare relative paths are code; {@code http:} and {@code https:} are
     * a live target. Deliberately not a guess about the shape of the rest of the string — a path can contain a
     * colon, a hostname can look like a directory, and a heuristic here would misroute findings in both
     * directions.
     */
    private static boolean isRuntimeTarget(String reportedPath) {
        String lower = reportedPath.strip().toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    private static String enclosingConstruct(Map<String, Object> location) {
        for (Object candidate : list(location.get("logicalLocations"))) {
            Map<String, Object> logical = object(candidate);
            if (logical != null) {
                String name = firstText(logical.get("fullyQualifiedName"), logical.get("name"));
                if (name != null) {
                    return name;
                }
            }
        }
        return null;
    }

    /**
     * The structural context hash — derived here, never taken from the tool's {@code partialFingerprints}.
     *
     * <p>This looks like a missed reuse and is a deliberate choice. SARIF's {@code partialFingerprints} exist
     * to track an alert across commits, and the commonest of them,
     * {@code primaryLocationLineHash}, hashes the line's content — so reformatting the line changes it. DOC-16
     * §7.1 requires the opposite property: "code reformatted; line numbers shift → same finding".
     * {@link CodeLocationNormalization#structuralContextHash} is built for exactly that, removing the
     * whitespace a formatter moves. Adopting the tool's hash would import the tool's stability guarantee, which
     * is weaker than the one this platform's dedup depends on.
     *
     * <p>The tool's fingerprints are retained on the record regardless, because they are how somebody
     * correlates a platform finding with the same alert in the tool's own interface.
     *
     * <p>Where the tool supplies neither an enclosing construct nor a snippet, the hash falls back to the rule
     * identity and the line — <b>and the line is used ONLY here</b>, never as a fingerprint input in its own
     * right. Two hits of one rule in one file with nothing to tell them apart are otherwise a single identity,
     * which silently merges them; the alternative is a fingerprint that changes on every unrelated edit, and
     * DOC-03 §10.2 is explicit that the merge failure ("distinct issues collapse, fixing one appears to fix
     * all") is the more expensive of the two. So the line is admitted as the last discriminator available,
     * and it is why a reformat can produce a duplicate for tools that emit no snippet at all — a known,
     * bounded cost recorded here rather than a surprise later.
     */
    private static String structuralHash(String enclosing, String snippet, Integer startLine,
            String ruleIdentity) {
        if (enclosing != null || snippet != null) {
            return CodeLocationNormalization.structuralContextHash(
                    enclosing == null ? "" : enclosing, snippet == null ? "" : snippet);
        }
        return CodeLocationNormalization.structuralContextHash(ruleIdentity,
                startLine == null ? "" : "line " + startLine);
    }

    /**
     * What the source called this finding's severity, whether or not it maps.
     *
     * <p>{@code security-severity} first where a tool emits one. It is the convention CodeQL and semgrep use
     * to say something SARIF's four levels cannot: that a weakness is critical. Bands follow the published
     * CVSS reading — 9.0 and above critical, 7.0 high, 4.0 medium, below that low — so a tool that reports
     * 9.8 arrives as critical rather than as "error", which SARIF would otherwise flatten into the same
     * bucket as a style violation.
     */
    private static String reportedSeverity(Map<String, Object> result, Map<String, Object> rule) {
        String security = securitySeverity(result, rule);
        if (security != null) {
            return "security-severity " + security;
        }
        String level = level(result, rule);
        return level == null ? null : level;
    }

    private static Integer mapSeverity(ParserDefinition parser, Map<String, Object> result,
            Map<String, Object> rule) {
        String security = securitySeverity(result, rule);
        if (security != null) {
            try {
                double score = Double.parseDouble(security);
                if (score >= 9.0) {
                    return Integer.valueOf(1);
                }
                if (score >= 7.0) {
                    return Integer.valueOf(2);
                }
                if (score >= 4.0) {
                    return Integer.valueOf(3);
                }
                return Integer.valueOf(4);
            } catch (NumberFormatException e) {
                // A security-severity that is not a number is a gap, not a reason to fall back to `level`.
                // Falling back would report a severity the tool did not give, and the whole point of
                // PRD-ING-040 is that a substituted severity is indistinguishable from a reported one.
                return null;
            }
        }
        return parser.mapSeverity(level(result, rule)).orElse(null);
    }

    private static String securitySeverity(Map<String, Object> result, Map<String, Object> rule) {
        Object onResult = path(result, "properties", "security-severity");
        Object onRule = rule == null ? null : path(rule, "properties", "security-severity");
        return text(firstNonNull(onResult, onRule));
    }

    /**
     * SARIF's level for a result.
     *
     * <p>The result's own value, then the rule's default configuration — the order the specification defines.
     * A result carrying neither returns null and becomes a mapping gap. SARIF does say the default is
     * {@code warning} when a rule exists and its configuration is silent; that default is deliberately NOT
     * applied, because it is the specification describing how a VIEWER should present a result, and the
     * platform is not a viewer. A finding whose severity the tool never stated must be visibly ungraded, or
     * every ungraded finding in the estate silently becomes a medium.
     */
    private static String level(Map<String, Object> result, Map<String, Object> rule) {
        String direct = text(result.get("level"));
        if (direct != null) {
            return direct.toLowerCase(Locale.ROOT);
        }
        // `kind` is not consulted here. A result the document says did not fail never reaches this method —
        // it is skipped in parse() and counted — so a null returned from here means the tool stated no
        // severity for a result it DID report as a failure, which is a mapping gap and not a pass.
        if (rule != null) {
            String configured = text(path(rule, "defaultConfiguration", "level"));
            if (configured != null) {
                return configured.toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ reading helpers

    private static Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private static String firstText(Object... candidates) {
        for (Object candidate : candidates) {
            String value = text(candidate);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** A non-blank string, or null. Blank is treated as absent: a tool that emits "" said nothing. */
    private static String text(Object value) {
        if (value instanceof String string) {
            return string.isBlank() ? null : string.strip();
        }
        if (value instanceof Number number) {
            return number.toString();
        }
        return null;
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) {
            return Integer.valueOf(number.intValue());
        }
        if (value instanceof String string) {
            try {
                return Integer.valueOf(Integer.parseInt(string.strip()));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static List<?> list(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    /** Walks a chain of object keys, stopping at the first that is absent or not an object. */
    private static Object path(Map<String, Object> from, String... keys) {
        Object current = from;
        for (String key : keys) {
            Map<String, Object> map = object(current);
            if (map == null) {
                return null;
            }
            current = map.get(key);
        }
        return current;
    }

    private static Map<String, String> stringMap(Object value) {
        Map<String, Object> source = object(value);
        if (source == null) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        source.forEach((key, entry) -> {
            String asText = text(entry);
            if (asText != null) {
                out.put(key, asText);
            }
        });
        return out;
    }

    private static String truncate(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit - 1) + "…";
    }

    /**
     * The raw record, retained per {@code PRD-ING-022}.
     *
     * <p>Serialized from the parsed value rather than sliced out of the original text, because the platform
     * has no byte offsets for a value once it is parsed. That makes it a normalization of the record and not
     * the bytes as submitted — which is correct for correction and diagnosis, and is why the document itself
     * is archived separately.
     */
    static final class Snippet {

        private Snippet() {
            throw new AssertionError("not instantiable");
        }

        static String of(Object value) {
            StringBuilder out = new StringBuilder(256);
            write(out, value);
            return out.toString();
        }

        private static void write(StringBuilder out, Object value) {
            if (value == null) {
                out.append("null");
            } else if (value instanceof Map<?, ?> map) {
                out.append('{');
                boolean first = true;
                for (var entry : map.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    quote(out, String.valueOf(entry.getKey()));
                    out.append(':');
                    write(out, entry.getValue());
                }
                out.append('}');
            } else if (value instanceof List<?> list) {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    write(out, list.get(i));
                }
                out.append(']');
            } else if (value instanceof Number || value instanceof Boolean) {
                out.append(value);
            } else {
                quote(out, String.valueOf(value));
            }
        }

        private static void quote(StringBuilder out, String value) {
            out.append('"');
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            out.append(String.format(Locale.ROOT, "\\u%04x", Integer.valueOf(c)));
                        } else {
                            out.append(c);
                        }
                    }
                }
            }
            out.append('"');
        }
    }

    /** Exposed for the caller's diagnostics: the ordinal a level maps to, without a document. */
    public static Optional<Integer> ordinalFor(String sarifLevel) {
        return definition().mapSeverity(sarifLevel == null ? null : sarifLevel.toLowerCase(Locale.ROOT));
    }
}
