package aspm.module.ingestion.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Migration import from an incumbent tracker. DOC-11 section 12, ADR-028.
 *
 * <p>DOC-11 states the problem the capability exists for: replacement "fails at a predictable point: the team is
 * asked to abandon years of institutional memory, and in practice they keep the old system in read-only mode
 * 'temporarily' — which becomes permanent."
 *
 * <p>⚠️ <b>Working assumption (OQ-025):</b> the incumbent is unidentified, so this is generic over a structured
 * export with fidelity limits documented per source. {@link FidelityReport} is where those limits become visible
 * rather than assumed.
 *
 * <h2>The capability and the abuse case are the same capability</h2>
 *
 * <p>{@code PRD-ING-049}: "Migration writes historical authorship, which is <b>also the capability to fabricate
 * a record of a decision never made</b> (DOC-26 section 8). The flag is the control and it is worthless if a
 * presentation drops it."
 *
 * <p>Nothing here can be built without the flag, and {@code PRD-ING-050} closes the other half: an unresolvable
 * author becomes a <b>marked placeholder</b>, never an arbitrary existing principal, because "attributing a
 * comment to the wrong person falsifies the record, and the falsification is invisible to a reader".
 */
public final class MigrationImport {

    /**
     * The gate. {@code PRD-ING-051}: an elevated permission distinct from import, step-up authentication, and
     * <b>no scope bypass</b>.
     *
     * <p>"It writes on behalf of other people at historical timestamps. That capability warrants the strongest
     * gate available short of dual control."
     */
    public record Authorization(String permission, boolean stepUpAuthenticated, boolean scopeValidated) {

        public static final String REQUIRED_PERMISSION = "ing.migration.execute";

        public Authorization {
            Objects.requireNonNull(permission, "a permission is required");
            if (!REQUIRED_PERMISSION.equals(permission)) {
                throw new IllegalArgumentException(
                        "migration requires " + REQUIRED_PERMISSION + ", distinct from the ordinary import "
                                + "permission (PRD-ING-051). Sharing the permission would give every principal "
                                + "who can upload a scanner report the ability to write comments attributed to "
                                + "other people at historical timestamps.");
            }
            if (!stepUpAuthenticated) {
                throw new IllegalArgumentException(
                        "migration requires step-up authentication (PRD-ING-051). It writes on behalf of other "
                                + "people at historical timestamps, which warrants the strongest gate available "
                                + "short of dual control.");
            }
            if (!scopeValidated) {
                throw new IllegalArgumentException(
                        "migration MUST NOT bypass scope validation (PRD-ING-051). A bulk historical write is "
                                + "the most attractive place to skip it, because the records 'already exist' "
                                + "somewhere else.");
            }
        }
    }

    /**
     * Authorship resolution. {@code PRD-ING-050}.
     *
     * @param resolvedPrincipal the platform principal, where the external identity resolved
     * @param unresolvedPlaceholder a marked placeholder otherwise. <b>Never an arbitrary existing principal.</b>
     */
    public record Authorship(String externalAuthorId, Optional<UUID> resolvedPrincipal,
            Optional<String> unresolvedPlaceholder) {

        public Authorship {
            Objects.requireNonNull(externalAuthorId, "the external author identifier is required");
            Objects.requireNonNull(resolvedPrincipal, "resolvedPrincipal is required, empty where unresolved");
            Objects.requireNonNull(unresolvedPlaceholder,
                    "unresolvedPlaceholder is required, empty where resolved");
            if (resolvedPrincipal.isPresent() == unresolvedPlaceholder.isPresent()) {
                throw new IllegalArgumentException(
                        "exactly one of a resolved principal or a marked placeholder. Neither leaves the "
                                + "record unattributed; both would let a presentation pick whichever it "
                                + "found (PRD-ING-050).");
            }
        }

        public static Authorship resolved(String externalAuthorId, UUID principalId) {
            return new Authorship(externalAuthorId, Optional.of(principalId), Optional.empty());
        }

        /**
         * An unresolved author, marked so a reader can see the attribution is not a platform principal.
         *
         * <p>The placeholder carries the external identifier: without it the reader knows only that somebody
         * unknown said this, and with it they can go and ask the incumbent system.
         */
        public static Authorship unresolved(String externalAuthorId) {
            return new Authorship(externalAuthorId, Optional.empty(),
                    Optional.of("[unresolved external author: " + externalAuthorId + "]"));
        }

        public boolean isResolved() {
            return resolvedPrincipal.isPresent();
        }
    }

    /**
     * One migrated record.
     *
     * @param originalTimestamp {@code PRD-ING-048}. "A comment thread where every entry is attributed to
     *     'migration' on the migration date is unusable as history — the information that makes it valuable is
     *     precisely who said what, when."
     * @param externalId {@code PRD-ING-049}. The flag's evidence: an import claiming to have brought a comment
     *     across can be checked against the source
     */
    public record MigratedRecord(String externalId, String kind, Authorship authorship,
            Instant originalTimestamp, Map<String, String> carriedFields, Set<String> droppedFields) {

        public MigratedRecord {
            Objects.requireNonNull(externalId, "an external identifier is required (PRD-ING-049)");
            Objects.requireNonNull(kind, "a record kind is required");
            Objects.requireNonNull(authorship, "authorship is required (PRD-ING-050)");
            Objects.requireNonNull(originalTimestamp,
                    "the ORIGINAL timestamp is required (PRD-ING-048), not the migration date");
            carriedFields = Map.copyOf(Objects.requireNonNull(carriedFields, "carried fields are required"));
            droppedFields = Set.copyOf(Objects.requireNonNull(droppedFields, "dropped fields are required"));
            if (externalId.isBlank()) {
                throw new IllegalArgumentException(
                        "a blank external identifier makes the migration flag uncheckable against the source");
            }
        }

        /** Always true. There is no constructor producing an unflagged migrated record. */
        public boolean migrated() {
            return true;
        }
    }

    /**
     * {@code PRD-ING-052}: per-record fidelity — fields carried, fields dropped, authorship unresolved.
     *
     * <p>"A migration reporting only success conceals what was lost, and <b>what was lost is discovered months
     * later when someone looks for a decision that is no longer recorded</b>."
     *
     * <p>So there is no {@code recordsImported} accessor on its own. {@link #summary} returns the count with its
     * losses, the same shape as the closure figure in prompt 8 and for the same reason.
     */
    public record FidelityReport(List<MigratedRecord> records, Set<String> fieldsDroppedAcrossSource,
            List<String> unresolvedAuthors) {

        public FidelityReport {
            records = List.copyOf(Objects.requireNonNull(records, "records are required"));
            fieldsDroppedAcrossSource = Set.copyOf(
                    Objects.requireNonNull(fieldsDroppedAcrossSource, "dropped fields are required"));
            unresolvedAuthors = List.copyOf(
                    Objects.requireNonNull(unresolvedAuthors, "unresolved authors are required"));
        }

        /** The count, and what it cost. Deliberately not separable. */
        public String summary() {
            return records.size() + " record(s) migrated. Fields dropped across the source: "
                    + (fieldsDroppedAcrossSource.isEmpty() ? "none" : fieldsDroppedAcrossSource)
                    + ". Authorship unresolved for " + unresolvedAuthors.size() + " author(s)"
                    + (unresolvedAuthors.isEmpty() ? "" : ": " + unresolvedAuthors)
                    + ". ⚠ Working assumption (OQ-025): the adapter is generic over a structured export, so "
                    + "fidelity limits are per source and the dropped list above is what this source lost.";
        }

        public boolean lossless() {
            return fieldsDroppedAcrossSource.isEmpty() && unresolvedAuthors.isEmpty();
        }
    }

    private MigrationImport() {
    }

    /**
     * Imports a batch, producing the fidelity report.
     *
     * @param authorization the gate. Required as a value rather than checked by the caller, so a migration
     *     cannot be run without one having been constructed — and constructing one validates all three
     *     conditions
     */
    public static FidelityReport execute(Authorization authorization, List<MigratedRecord> records) {
        Objects.requireNonNull(authorization,
                "an authorization is required (PRD-ING-051). Taking it as a value rather than trusting a "
                        + "caller's check means the three conditions were validated to construct it.");
        Objects.requireNonNull(records, "records are required");

        Set<String> dropped = new LinkedHashSet<>();
        List<String> unresolved = new ArrayList<>();
        for (MigratedRecord record : records) {
            dropped.addAll(record.droppedFields());
            if (!record.authorship().isResolved()) {
                unresolved.add(record.authorship().externalAuthorId());
            }
        }
        return new FidelityReport(records, dropped, List.copyOf(unresolved));
    }
}
