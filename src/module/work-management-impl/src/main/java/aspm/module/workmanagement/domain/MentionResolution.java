package aspm.module.workmanagement.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Mention authoring and autocomplete. {@code INV-WRK-09}, PP-4.
 *
 * <p>{@code PRD-WRK-019}'s security note: "Mentions MUST NOT disclose the existence of users outside the
 * mentioning user's authorized scope, and mention autocomplete is a user enumeration surface requiring scope
 * filtering."
 *
 * <h2>Autocomplete is the enumeration surface, and the naive implementation is a directory</h2>
 *
 * <p>A mention picker answers <i>who exists</i> for any prefix an attacker types. Unfiltered, it turns a
 * comment box into a user directory for the entire group — names, and by inference organizational structure —
 * available to the population PP-7 describes as having the narrowest permissions and the least training.
 *
 * <p><b>Two filters, not one.</b> {@link #autocomplete} filters what is offered, and {@link #resolve} filters
 * what is accepted. The second is the one that matters: a filtered picker "is a usability feature, never an
 * authorization control" (PP-4), because the request that posts a comment carries whatever identifier the client
 * chose to put in it. An implementation with only the first has a control that any HTTP client bypasses.
 *
 * <h2>Rejecting, not silently dropping</h2>
 *
 * <p>An unauthorized mention is <b>rejected</b> — the comment does not post — rather than quietly stripped.
 * Silent stripping would let an author believe they had drawn somebody into a discussion who was never notified,
 * which is the invisible failure {@code PRD-WRK-020} describes in a different context: "the requester believes
 * they responded". It also leaks nothing, because the rejection says only that the mention is not permitted, not
 * whether the principal exists.
 */
public final class MentionResolution {

    /**
     * Resolves scope-visible principals. Implemented by the identity module through its contract.
     *
     * <p>Takes the mentioning principal as an argument rather than reading an ambient context, so the scope being
     * applied is visible at every call site. Scope derived from an ambient session is scope nobody notices is
     * missing.
     */
    @FunctionalInterface
    public interface VisiblePrincipals {

        /**
         * Whether {@code candidate} is within {@code mentioner}'s authorized scope.
         *
         * <p>Must not distinguish "does not exist" from "not visible" in anything it exposes — the caller only
         * learns a boolean, which is what keeps this from being an existence oracle.
         */
        boolean visibleTo(UUID mentioner, UUID candidate);
    }

    /** One autocomplete suggestion. */
    public record Suggestion(UUID principalId, String displayName) {

        public Suggestion {
            Objects.requireNonNull(principalId, "principalId is required");
            Objects.requireNonNull(displayName, "displayName is required");
        }
    }

    /**
     * The minimum prefix before autocomplete returns anything.
     *
     * <p>An empty prefix returning the whole visible set is a directory listing even when correctly scoped: a
     * principal with broad scope would hand their entire visible population to anyone who could get them to open
     * a comment box. Two characters is not a security boundary on its own — the scope filter is — but it removes
     * the trivial enumeration and costs a user nothing.
     */
    public static final int MINIMUM_PREFIX_LENGTH = 2;

    /** Cap on suggestions returned, so a broad-scope principal's picker is not a bulk export. */
    public static final int MAX_SUGGESTIONS = 20;

    private final VisiblePrincipals visibility;

    public MentionResolution(VisiblePrincipals visibility) {
        this.visibility = Objects.requireNonNull(visibility,
                "a visibility resolver is required (INV-WRK-09). Without one, autocomplete is a user directory "
                        + "for the entire group.");
    }

    /**
     * Autocomplete. A usability feature over an authorization control, never a substitute for one.
     *
     * @param candidates the tenant's principals matching the prefix, before scope filtering
     */
    public List<Suggestion> autocomplete(UUID mentioner, String prefix, List<Suggestion> candidates) {
        Objects.requireNonNull(mentioner, "the mentioning principal is required");
        Objects.requireNonNull(prefix, "a prefix is required");
        Objects.requireNonNull(candidates, "candidates are required");
        if (prefix.strip().length() < MINIMUM_PREFIX_LENGTH) {
            return List.of();
        }
        List<Suggestion> visible = new ArrayList<>();
        for (Suggestion candidate : candidates) {
            if (visible.size() == MAX_SUGGESTIONS) {
                break;
            }
            if (visibility.visibleTo(mentioner, candidate.principalId())) {
                visible.add(candidate);
            }
        }
        return List.copyOf(visible);
    }

    /**
     * Validates the mentions in a body at post time. <b>This is the control.</b>
     *
     * @return the mentioned principals, all confirmed visible
     * @throws IllegalArgumentException naming no principal identifier, because an error message enumerating the
     *     rejected identifiers would confirm which of a submitted list exist
     */
    public Set<UUID> resolve(UUID mentioner, ConstrainedRichText body) {
        Objects.requireNonNull(mentioner, "the mentioning principal is required");
        Objects.requireNonNull(body, "a body is required");

        Set<UUID> mentioned = new LinkedHashSet<>(body.mentionedPrincipals());
        int rejected = 0;
        for (UUID candidate : mentioned) {
            if (!visibility.visibleTo(mentioner, candidate)) {
                rejected++;
            }
        }
        if (rejected > 0) {
            throw new IllegalArgumentException(
                    rejected + " mention(s) are not permitted (INV-WRK-09). A filtered picker is a usability "
                            + "feature, never an authorization control (PP-4), so the check runs here too — and "
                            + "the identifiers are withheld, because naming them would confirm which of a "
                            + "submitted list exist.");
        }
        return Set.copyOf(mentioned);
    }

    /**
     * Whether a stored mention may be rendered to a reader.
     *
     * <p>Applied at read time as well as write time, because scope changes: a principal legitimately mentioned
     * last quarter may be outside a later reader's scope, and re-checking is what keeps a historical comment from
     * becoming a disclosure. The mention renders as an opaque marker rather than disappearing, so the reader can
     * see that somebody was mentioned without learning who.
     */
    public boolean renderableTo(UUID reader, UUID mentionedPrincipal) {
        Objects.requireNonNull(reader, "the reading principal is required");
        Objects.requireNonNull(mentionedPrincipal, "the mentioned principal is required");
        return visibility.visibleTo(reader, mentionedPrincipal);
    }
}
