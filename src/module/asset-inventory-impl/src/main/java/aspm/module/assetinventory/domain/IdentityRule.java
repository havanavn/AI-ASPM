package aspm.module.assetinventory.domain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Per-type asset identity resolution, per DOC-03 section 8.5 and {@code PRD-AST-006}.
 *
 * <p>DOC-03 calls this "the asset-graph analogue of finding identity, and it fails in the same two
 * directions": too loose merges distinct assets, too strict fragments one asset's history across several.
 *
 * <p><b>The version is not decoration.</b> DOC-03 section 8.5: "{@code IdentityRule.version} exists because
 * rules will improve, and improvement changes identity… Without a re-resolution path, the first version of
 * a rule is permanent — and the first version is always the least informed." Every resolved identity
 * therefore records the version that produced it, and re-resolution is a <em>merge</em> (DOC-03 section 8.6)
 * rather than a recompute, because it must preserve ownership, criticality, tags and finding association.
 */
public final class IdentityRule {

    /** How normalized keys are compared. */
    public enum MatchStrategy {
        EXACT,
        NORMALIZED_EXACT,
        /** Any member of a recorded alias set identifies the same asset. */
        ALIAS_SET
    }

    /** The asset types with product-supplied identity rules, per DOC-03 section 8.5's table. */
    public enum KnownType {
        REPOSITORY,
        SERVICE,
        API,
        DOMAIN,
        ARTIFACT,
        COMPONENT
    }

    /**
     * A normalization applied before comparison.
     *
     * <p>An enum rather than a lambda so that the set applied to a type is recordable, diffable and
     * versionable. A rule whose normalizations cannot be written down cannot be re-resolved against.
     */
    public enum Normalization {
        LOWERCASE,
        TRIM,
        STRIP_URL_SCHEME,
        STRIP_URL_CREDENTIALS,
        STRIP_GIT_SUFFIX,
        STRIP_TRAILING_SLASH,
        STRIP_TRAILING_DOT,
        PUNYCODE_NORMALIZE,
        /** Collapse high-cardinality path segments to placeholders. See {@link #collapsePathParameters}. */
        COLLAPSE_PATH_PARAMETERS,
        /** Prefer a digest over a mutable tag where both are present. */
        PREFER_DIGEST,
        CANONICALIZE_PURL
    }

    private final KnownType type;
    private final List<String> naturalKeyAttributes;
    private final List<Normalization> normalizations;
    private final MatchStrategy matchStrategy;
    private final int version;

    public IdentityRule(KnownType type, List<String> naturalKeyAttributes,
            List<Normalization> normalizations, MatchStrategy matchStrategy, int version) {
        this.type = Objects.requireNonNull(type, "type is required");
        this.naturalKeyAttributes = List.copyOf(
                Objects.requireNonNull(naturalKeyAttributes, "naturalKeyAttributes is required"));
        this.normalizations =
                List.copyOf(Objects.requireNonNull(normalizations, "normalizations are required"));
        this.matchStrategy = Objects.requireNonNull(matchStrategy, "matchStrategy is required");
        if (version < 1) {
            throw new IllegalArgumentException("rule version is monotonic from 1");
        }
        if (this.naturalKeyAttributes.isEmpty()) {
            throw new IllegalArgumentException(
                    "an identity rule with no natural key attributes would make every asset of this type "
                            + "identical, collapsing the inventory into one row");
        }
        this.version = version;
    }

    public KnownType type() {
        return type;
    }

    public List<String> naturalKeyAttributes() {
        return naturalKeyAttributes;
    }

    public List<Normalization> normalizations() {
        return normalizations;
    }

    public MatchStrategy matchStrategy() {
        return matchStrategy;
    }

    public int version() {
        return version;
    }

    /** The product-supplied rules of DOC-03 section 8.5, at version 1. */
    public static IdentityRule productDefault(KnownType type) {
        return switch (type) {
            // "The same repository is reported as an SSH URL, an HTTPS URL, and a project path. Without
            // normalization each becomes a separate asset and finding history fragments three ways."
            case REPOSITORY -> new IdentityRule(type, List.of("host", "namespace", "name"),
                    List.of(Normalization.LOWERCASE, Normalization.STRIP_URL_SCHEME,
                            Normalization.STRIP_URL_CREDENTIALS, Normalization.STRIP_GIT_SUFFIX,
                            Normalization.STRIP_TRAILING_SLASH),
                    MatchStrategy.NORMALIZED_EXACT, 1);
            // "Service names are not globally unique — two business units both run 'gateway'." Hence the
            // owning node is part of the key.
            case SERVICE -> new IdentityRule(type, List.of("owning_node", "service_name"),
                    List.of(Normalization.TRIM, Normalization.LOWERCASE),
                    MatchStrategy.NORMALIZED_EXACT, 1);
            // "/users/123 and /users/456 are one API endpoint, not two. Without collapsing, an inventory of
            // a REST service is unbounded."
            case API -> new IdentityRule(type, List.of("service", "method", "path"),
                    List.of(Normalization.TRIM, Normalization.COLLAPSE_PATH_PARAMETERS),
                    MatchStrategy.NORMALIZED_EXACT, 1);
            case DOMAIN -> new IdentityRule(type, List.of("fqdn"),
                    List.of(Normalization.LOWERCASE, Normalization.STRIP_TRAILING_DOT,
                            Normalization.PUNYCODE_NORMALIZE),
                    MatchStrategy.NORMALIZED_EXACT, 1);
            // "A digest is exact; a tag is mutable and can be reassigned to different content."
            case ARTIFACT -> new IdentityRule(type, List.of("registry_qualified_name", "version_or_digest"),
                    List.of(Normalization.TRIM, Normalization.PREFER_DIGEST),
                    MatchStrategy.NORMALIZED_EXACT, 1);
            case COMPONENT -> new IdentityRule(type, List.of("purl"),
                    List.of(Normalization.CANONICALIZE_PURL), MatchStrategy.NORMALIZED_EXACT, 1);
        };
    }

    // ---------------------------------------------------------------- normalization primitives

    private static final Pattern SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://");
    private static final Pattern CREDENTIALS = Pattern.compile("^[^/@]+@");

    /**
     * The placeholder a collapsed segment becomes.
     *
     * <p>The conservativeness rationale that used to sit here moved with the heuristic to
     * {@link aspm.sharedkernel.PathNormalization}, so that a reader finds it beside the code it governs.
     */
    public static final String PARAMETER_PLACEHOLDER = aspm.sharedkernel.PathNormalization.PARAMETER_PLACEHOLDER;

    /**
     * Collapses high-cardinality path segments.
     *
     * <p><b>Delegates to {@link aspm.sharedkernel.PathNormalization}.</b> This heuristic was first implemented
     * here, and finding identity in the ingestion module needs the same one — DOC-03 section 10.2's runtime class
     * collapses request paths for the same reason section 8.5 collapses API paths. Neither module depends on the
     * other, so a second copy was the alternative, and two copies of a heuristic that must agree is how they stop
     * agreeing. Product principle 10: one name, one meaning, one place.
     *
     * <p>The rule version still records which heuristic produced a value, so "a change to the heuristic is
     * traceable" as DOC-03 section 8.5 requires — a re-resolution can identify which assets need re-resolving.
     */
    public static String collapsePathParameters(String path) {
        return aspm.sharedkernel.PathNormalization.collapseParameters(path);
    }

    /** Applies this rule's normalizations to one attribute value. */
    public String normalizeValue(String raw) {
        Objects.requireNonNull(raw, "raw value is required; an absent attribute is not normalizable");
        String value = raw;
        for (Normalization normalization : normalizations) {
            value = switch (normalization) {
                case TRIM -> value.strip();
                case LOWERCASE -> value.toLowerCase(Locale.ROOT);
                case STRIP_URL_SCHEME -> SCHEME.matcher(value).replaceFirst("");
                case STRIP_URL_CREDENTIALS -> CREDENTIALS.matcher(value).replaceFirst("");
                case STRIP_GIT_SUFFIX -> value.endsWith(".git")
                        ? value.substring(0, value.length() - 4)
                        : value;
                case STRIP_TRAILING_SLASH -> value.endsWith("/")
                        ? value.substring(0, value.length() - 1)
                        : value;
                case STRIP_TRAILING_DOT -> value.endsWith(".")
                        ? value.substring(0, value.length() - 1)
                        : value;
                // Punycode and PURL canonicalization are ecosystem-specific and belong to their libraries.
                // Named here so the rule is complete and the gap is visible rather than absent; applying a
                // half-implementation would produce a normalized form that a later correct one disagrees
                // with, and identity would shift under an asset without a rule version change.
                case PUNYCODE_NORMALIZE, CANONICALIZE_PURL, PREFER_DIGEST -> value;
                case COLLAPSE_PATH_PARAMETERS -> collapsePathParameters(value);
            };
        }
        return value;
    }

    /**
     * Which normalizations this rule declares but does not yet fully implement.
     *
     * <p>Exposed rather than hidden, because {@code PRD-AST-006}'s identity resolution is only as good as its
     * weakest normalization and an unimplemented one silently produces false splits. A caller can assert on
     * this set, and prompt 11 (composition analysis) owns PURL canonicalization.
     */
    public Set<Normalization> declaredButNotImplemented() {
        Set<Normalization> pending = new LinkedHashSet<>();
        for (Normalization n : normalizations) {
            if (n == Normalization.PUNYCODE_NORMALIZE
                    || n == Normalization.CANONICALIZE_PURL
                    || n == Normalization.PREFER_DIGEST) {
                pending.add(n);
            }
        }
        return Set.copyOf(pending);
    }
}
