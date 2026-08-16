package aspm.module.compositionanalysis.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * A canonicalized component identity. {@code PRD-SBM-036}, {@code PRD-SBM-037}, ADR-032.
 *
 * <h2>Interned TENANT-scoped, not globally (ADR-032)</h2>
 *
 * <p>A global intern table would be smaller and faster — one row per component across the whole platform instead
 * of one per tenant. ADR-032 rejects it on tenant-boundary grounds, and the reason is worth stating because the
 * efficiency argument is genuinely attractive: a shared intern table makes "does any tenant use this component"
 * answerable, and makes the arrival of a new row an observable event. That is a cross-tenant inference channel
 * built out of what looks like a lookup table.
 *
 * <p>So {@link #tenantScopedKey} includes the tenant, and there is no method producing a global key. The cost is
 * duplication of the same component across tenants; the benefit is that the component table cannot answer a
 * question about another tenant.
 *
 * <h2>Canonicalization is versioned</h2>
 *
 * <p>{@code PRD-SBM-036}: "Rules will improve, and improvement changes which components match. Without a recorded
 * version, a change in results cannot be distinguished from a change in the estate."
 *
 * @param namespace preserved where present. DOC-22 section 6.2: dropping it merges "two packages of the same
 *     name in different namespaces into one, producing false positives on both"
 * @param qualifiers distribution and release qualifiers, retained. Dropping them means "a package version
 *     patched by one distribution and not another cannot be distinguished" — which is the backport case again,
 *     arriving through identity rather than through version comparison
 */
public record ComponentIdentity(Ecosystem ecosystem, Optional<String> namespace, String name, String version,
        Optional<String> qualifiers) {

    /** {@code PRD-SBM-036}: recorded on every match run. */
    public static final int CANONICALIZATION_VERSION = 1;

    public ComponentIdentity {
        Objects.requireNonNull(ecosystem, "an ecosystem is required");
        Objects.requireNonNull(namespace, "namespace is required, empty where the ecosystem has none");
        Objects.requireNonNull(name, "a name is required");
        Objects.requireNonNull(version, "a version is required");
        Objects.requireNonNull(qualifiers, "qualifiers are required, empty where none");
        if (name.isBlank()) {
            throw new IllegalArgumentException("a blank component name matches nothing and hides everything");
        }
        if (version.isBlank()) {
            // INV-SBM-03's reasoning at the component level: a component without a concrete version produces
            // zero matches and no error, which is "a false negative presenting as good news".
            throw new IllegalArgumentException(
                    "component '" + name + "' has no concrete version. The matcher would find nothing because "
                            + "there is nothing matchable, and the result is indistinguishable from a clean "
                            + "application (DOC-03 section 11, INV-SBM-03).");
        }
    }

    /**
     * Why a component identifier could not be canonicalized.
     *
     * <p>{@code PRD-SBM-037} requires an unmatchable component to be "recorded as unmatchable with the reason"
     * and to count against snapshot quality: "silent skipping is the mechanism by which a partially matched SBOM
     * appears fully matched".
     */
    public enum UnmatchableReason {
        NOT_A_PACKAGE_URL,
        UNKNOWN_ECOSYSTEM,
        MISSING_NAME,
        MISSING_VERSION
    }

    /** The outcome of canonicalizing one identifier: an identity, or a recorded reason it is unmatchable. */
    public record Canonicalization(Optional<ComponentIdentity> identity,
            Optional<UnmatchableReason> unmatchableReason, String rawIdentifier) {

        public Canonicalization {
            Objects.requireNonNull(identity, "identity is required, empty where unmatchable");
            Objects.requireNonNull(unmatchableReason, "the reason is required, empty where matched");
            Objects.requireNonNull(rawIdentifier, "the raw identifier is required");
            if (identity.isPresent() == unmatchableReason.isPresent()) {
                throw new IllegalArgumentException(
                        "exactly one of an identity or an unmatchable reason. Neither would be a silent skip, "
                                + "and both would be a component that is simultaneously matched and not.");
            }
        }

        public boolean matchable() {
            return identity.isPresent();
        }
    }

    /**
     * Canonicalizes a package URL. DOC-22 section 6.2.
     *
     * <p>Per-ecosystem and deterministic. Case folding is applied only where the registry treats names
     * case-insensitively — folding elsewhere would merge two genuinely distinct packages, which is the "false
     * merge" half of section 6.2's opening sentence.
     *
     * <p>Never throws for bad input: an unmatchable component is data, not an error. Throwing would abort the
     * snapshot and lose the components that <i>did</i> canonicalize.
     */
    public static Canonicalization canonicalize(String packageUrl) {
        if (packageUrl == null || !packageUrl.startsWith("pkg:")) {
            return new Canonicalization(Optional.empty(), Optional.of(UnmatchableReason.NOT_A_PACKAGE_URL),
                    String.valueOf(packageUrl));
        }
        String body = packageUrl.substring("pkg:".length());

        String qualifiers = null;
        int question = body.indexOf('?');
        if (question >= 0) {
            qualifiers = body.substring(question + 1);
            body = body.substring(0, question);
        }

        int firstSlash = body.indexOf('/');
        if (firstSlash < 0) {
            return new Canonicalization(Optional.empty(), Optional.of(UnmatchableReason.MISSING_NAME),
                    packageUrl);
        }
        String type = body.substring(0, firstSlash);
        Ecosystem ecosystem = Ecosystem.fromPurlType(type);
        if (ecosystem == Ecosystem.UNKNOWN) {
            return new Canonicalization(Optional.empty(), Optional.of(UnmatchableReason.UNKNOWN_ECOSYSTEM),
                    packageUrl);
        }

        String remainder = body.substring(firstSlash + 1);
        String version = null;
        int at = remainder.lastIndexOf('@');
        if (at >= 0) {
            version = remainder.substring(at + 1);
            remainder = remainder.substring(0, at);
        }
        if (version == null || version.isBlank()) {
            return new Canonicalization(Optional.empty(), Optional.of(UnmatchableReason.MISSING_VERSION),
                    packageUrl);
        }

        String namespace = null;
        int lastSlash = remainder.lastIndexOf('/');
        if (lastSlash >= 0) {
            namespace = remainder.substring(0, lastSlash);
            remainder = remainder.substring(lastSlash + 1);
        }
        if (remainder.isBlank()) {
            return new Canonicalization(Optional.empty(), Optional.of(UnmatchableReason.MISSING_NAME),
                    packageUrl);
        }

        return new Canonicalization(
                Optional.of(new ComponentIdentity(ecosystem, Optional.ofNullable(normalizeName(ecosystem,
                        namespace)), normalizeName(ecosystem, remainder), version,
                        Optional.ofNullable(qualifiers))),
                Optional.empty(), packageUrl);
    }

    /**
     * Per-ecosystem name normalization.
     *
     * <p>Lowercasing is applied only to ecosystems whose registries are case-insensitive. Maven's coordinates
     * are case-sensitive, and folding them would merge two distinct artifacts — DOC-22 section 6.2's "false
     * merge", where the consequence is false positives on both.
     */
    private static String normalizeName(Ecosystem ecosystem, String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return switch (ecosystem) {
            // Registries that treat names case-insensitively. PyPI additionally treats '_' , '.' and '-' as
            // equivalent, so the same package under two spellings does not become two components.
            case PYPI -> trimmed.toLowerCase(Locale.ROOT).replaceAll("[-_.]+", "-");
            case SEMVER, DEB, RPM -> trimmed.toLowerCase(Locale.ROOT);
            // Case-sensitive: Maven coordinates and Go module paths.
            case MAVEN, GOLANG, UNKNOWN -> trimmed;
        };
    }

    /**
     * The intern key, <b>tenant-scoped</b> (ADR-032).
     *
     * <p>There is deliberately no global variant. A shared intern table would make component arrival an
     * observable cross-tenant event.
     */
    public String tenantScopedKey(java.util.UUID tenantId) {
        Objects.requireNonNull(tenantId, "a tenant is required (ADR-032)");
        return tenantId + "|" + ecosystem.purlType() + "|" + namespace.orElse("") + "|" + name + "|"
                + version + "|" + qualifiers.orElse("");
    }
}
