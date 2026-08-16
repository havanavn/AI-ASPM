package aspm.module.ingestion.domain;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Asset anchor resolution, per DOC-11 section 6.
 *
 * <p>Five steps, each falling through on failure, and the fifth is the one that matters:
 * {@code PRD-ING-029} requires an unresolvable finding to <b>create an unclaimed asset</b> rather than be
 * discarded or rejected. "Discarding is silent data loss at the point of least detectability. Creating an
 * unclaimed asset routes the problem into a visible queue with an escalation path."
 *
 * <p><b>{@code PRD-ING-030} is why the step is recorded.</b> "A session that created 400 assets resolved almost
 * nothing and should be investigated before its findings are trusted. Without the distinction, that session looks
 * identical to one that matched cleanly." Mass asset creation during import is the signal that a mapping is
 * wrong — a template pointing at the wrong column, or a normalization rule that stopped matching — and it is only
 * a signal if somebody can see it.
 */
public final class AssetAnchorResolution {

    /** Which step resolved the anchor. Ordered as DOC-11 section 6 lists them. */
    public enum Step {
        /** An explicit asset identifier in the source. */
        EXPLICIT_IDENTIFIER(true),
        /** A match against {@code asset_external_identifier}. */
        EXTERNAL_IDENTIFIER_MATCH(true),
        /** A natural key match through the asset type's identity rule (DOC-03 section 8.5). */
        NATURAL_KEY_MATCH(true),
        /** An alias match. */
        ALIAS_MATCH(true),
        /**
         * No match; an unclaimed asset was created.
         *
         * <p>{@code PRD-ING-030} requires this to be distinguishable from a match, and this enum is that
         * distinction. A boolean "resolved" flag would make all five steps look alike.
         */
        CREATED_UNCLAIMED(false);

        private final boolean matched;

        Step(boolean matched) {
            this.matched = matched;
        }

        /** True where an existing asset was found, false where one was created. */
        public boolean matchedExistingAsset() {
            return matched;
        }
    }

    /** The parser-declared strategy: which steps this format's records can even attempt. */
    public enum Strategy {
        /** The source names an asset directly, so the explicit step applies. */
        EXPLICIT_IN_SOURCE,
        /** The source carries a repository or artifact coordinate resolvable by natural key. */
        NATURAL_KEY_FROM_COORDINATE,
        /** The source carries only a hostname or URL, resolvable as a DOMAIN natural key. */
        NATURAL_KEY_FROM_HOST,
        /** The source carries an identifier from a known external system. */
        EXTERNAL_IDENTIFIER
    }

    /** The outcome, carrying the step so a session can report per-step counts. */
    public record Outcome(UUID assetId, Step step, String resolvedFrom) {

        public Outcome {
            Objects.requireNonNull(assetId, "an asset id is required; PRD-ING-029 forbids discarding a finding "
                    + "whose asset cannot be resolved — an unclaimed asset is created instead");
            Objects.requireNonNull(step, "the resolving step is required (PRD-ING-030)");
            Objects.requireNonNull(resolvedFrom, "the value the resolution was attempted from is required, so "
                    + "an operator investigating mass creation can see WHAT failed to match");
        }

        public boolean createdAsset() {
            return step == Step.CREATED_UNCLAIMED;
        }
    }

    /** The lookups anchor resolution performs, in the order DOC-11 section 6 specifies. */
    public interface AssetLookup {

        Optional<UUID> byExplicitIdentifier(String identifier);

        Optional<UUID> byExternalIdentifier(String sourceSystem, String identifier);

        Optional<UUID> byNaturalKey(String normalizedIdentityKey);

        Optional<UUID> byAlias(String alias);

        /**
         * Creates an unclaimed asset and queues it for ownership resolution.
         *
         * <p>Returns an id, never empty. There is no failure path: {@code PRD-ING-029} forbids discarding, so a
         * creation that could fail would reintroduce the discard through the back door.
         */
        UUID createUnclaimed(String normalizedIdentityKey, String sourceSystem);
    }

    private AssetAnchorResolution() {
        throw new AssertionError("not instantiable");
    }

    /**
     * Resolves an anchor, falling through the five steps.
     *
     * <p>Never returns empty and never throws for an unresolvable anchor. The fifth step always succeeds, which is
     * {@code PRD-ING-029} expressed as a total function.
     *
     * @param sourceScopeAssertion an organizational scope the source file asserted, if any. <b>Ignored for
     *     resolution</b> — see {@link #rejectSourceAssertedScope}
     */
    public static Outcome resolve(AssetLookup lookup, String explicitIdentifier, String sourceSystem,
            String externalIdentifier, String normalizedIdentityKey, String alias,
            String sourceScopeAssertion) {
        Objects.requireNonNull(lookup, "an asset lookup is required");
        Objects.requireNonNull(normalizedIdentityKey,
                "a normalized identity key is required; it is what step 3 matches on and what step 5 creates "
                        + "from, so without it there is no anchor and no way to make one");

        if (sourceScopeAssertion != null) {
            // PRD-ING-031. Not silently dropped: an operator needs to know the file tried.
            throw new IllegalArgumentException(rejectSourceAssertedScope(sourceScopeAssertion));
        }

        if (explicitIdentifier != null) {
            var found = lookup.byExplicitIdentifier(explicitIdentifier);
            if (found.isPresent()) {
                return new Outcome(found.get(), Step.EXPLICIT_IDENTIFIER, explicitIdentifier);
            }
        }
        if (sourceSystem != null && externalIdentifier != null) {
            var found = lookup.byExternalIdentifier(sourceSystem, externalIdentifier);
            if (found.isPresent()) {
                return new Outcome(found.get(), Step.EXTERNAL_IDENTIFIER_MATCH, externalIdentifier);
            }
        }
        var byKey = lookup.byNaturalKey(normalizedIdentityKey);
        if (byKey.isPresent()) {
            return new Outcome(byKey.get(), Step.NATURAL_KEY_MATCH, normalizedIdentityKey);
        }
        if (alias != null) {
            var found = lookup.byAlias(alias);
            if (found.isPresent()) {
                return new Outcome(found.get(), Step.ALIAS_MATCH, alias);
            }
        }

        // Step 5. Always succeeds, because PRD-ING-029 forbids the alternative.
        return new Outcome(lookup.createUnclaimed(normalizedIdentityKey, sourceSystem),
                Step.CREATED_UNCLAIMED, normalizedIdentityKey);
    }

    /**
     * The rejection for a source-asserted organizational scope. {@code PRD-ING-031}.
     *
     * <p>"A file-asserted scope is a cross-scope injection primitive requiring only that the file be edited."
     * Scope is derived from the importing principal's authorization, never read from the document — which is
     * product principle 4 ("scope is derived, never asserted by the client") applied to a file rather than to an
     * API request, and the file is the easier of the two to edit.
     */
    public static String rejectSourceAssertedScope(String assertedScope) {
        return "the source asserted an organizational scope ('" + assertedScope + "'), which is not trusted "
                + "(PRD-ING-031). A file-asserted scope is a cross-scope injection primitive requiring only "
                + "that the file be edited. Scope is derived from the importing principal's authorization.";
    }
}
