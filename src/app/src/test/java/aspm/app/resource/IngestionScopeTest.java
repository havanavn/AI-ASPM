package aspm.app.resource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.app.runtime.Principal;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ingestion doors resolve their target inside the caller's scope. {@code SEC-AUZ-016}, PP-4.
 *
 * <p><b>What this is guarding against, in the past tense.</b> Both target lookups selected an asset by
 * name or identity key with no scope predicate at all — row-level security bounds them to the tenant
 * and nothing narrower. So a service credential pinned to one division could address any repository in
 * the group by its three-part name and write into it. It was measured rather than reasoned: a key
 * pinned to Fintech submitted a scan report addressed at a repository scoped to Vinpearl and received
 * 201 with one finding ingested, filed in another division's estate.
 *
 * <p>Reads were never affected — every query composes the predicate. This was the write path, which
 * is the half tested last and the half that matters as much.
 */
class IngestionScopeTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID FINTECH = UUID.fromString("22222222-0000-4000-8000-000000000001");
    private static final UUID VINPEARL = UUID.fromString("22222222-0000-4000-8000-000000000002");

    private static Principal pinnedTo(UUID... nodes) {
        return new Principal(TENANT, UUID.randomUUID(), Set.of("sbm.sbom.submit"), Set.of(nodes),
                false, true, false);
    }

    @Test
    @DisplayName("a target inside the caller's scope resolves")
    void inScopeResolves() {
        assertDoesNotThrow(() ->
                SbomIngestion.requireInScope(pinnedTo(FINTECH, VINPEARL), VINPEARL, "repo:a/b/c"));
    }

    @Test
    @DisplayName("a target in another division is refused")
    void crossScopeIsRefused() {
        var refused = assertThrows(SbomIngestion.OutOfScopeTarget.class, () ->
                SbomIngestion.requireInScope(pinnedTo(FINTECH), VINPEARL, "repo:a/b/c"));
        // The message names what the CALLER supplied and nothing about what was found. Saying which
        // division owns it would answer the question the refusal exists to withhold.
        assertTrue(refused.getMessage().contains("repo:a/b/c"), refused.getMessage());
        assertFalse(refused.getMessage().contains(VINPEARL.toString()), refused.getMessage());
    }

    @Test
    @DisplayName("an asset with no scope recorded is refused rather than admitted")
    void unscopedTargetIsRefused() {
        // Scope is derived, never asserted (PP-4): a row whose organization is unknown cannot be shown
        // to belong to the caller. It is also not a shape ingestion produces — every asset it creates
        // carries the creating credential's scope — so refusing it costs nothing legitimate.
        assertThrows(SbomIngestion.OutOfScopeTarget.class, () ->
                SbomIngestion.requireInScope(pinnedTo(FINTECH), null, "repo:a/b/c"));
    }

    @Test
    @DisplayName("a caller with no scope at all reaches nothing")
    void noScopeReachesNothing() {
        // SEC-AUZ-014 denies on unavailable scope. An empty set must never read as "everything".
        assertThrows(SbomIngestion.OutOfScopeTarget.class, () ->
                SbomIngestion.requireInScope(pinnedTo(), VINPEARL, "repo:a/b/c"));
    }
}
