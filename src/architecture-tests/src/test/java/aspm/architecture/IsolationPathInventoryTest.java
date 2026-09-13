package aspm.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The twenty isolation paths of DOC-16 section 5, present as an inventory from the first kernel
 * session onwards.
 *
 * <p>Prompt 3 requires these "including paths whose subsystem is not yet built, because a path added
 * later without its test is unenforced". {@code TST-TEN-001} is the requirement, and DOC-16 calls it
 * "the weakest link in the isolation model because it is procedural" — a subsystem can be added
 * without its path, and no test detects the absence of the test that was never written.
 *
 * <p><b>How this file makes the procedural link mechanical.</b> {@link #theInventoryIsComplete}
 * enumerates I1 through I20 and fails if any is missing a method annotated with its identifier. A
 * path whose subsystem does not exist yet is {@link Disabled} with the prompt that will implement it,
 * so it appears as <em>skipped</em> in the report — visible and countable — rather than absent and
 * therefore invisible. A disabled test is a debt on a ledger; a missing test is not.
 *
 * <p><b>What a reviewer should check.</b> That the skip count falls as prompts land, and that no path
 * is silently converted from {@code @Disabled} to a passing test that asserts nothing.
 */
class IsolationPathInventoryTest {

    /** Marks a method as covering a DOC-16 section 5 isolation path. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface IsolationPath {
        String value();
    }

    /** DOC-16 section 5, verbatim identifiers. */
    private static final Set<String> REQUIRED_PATHS = new TreeSet<>(Set.of(
            "I1", "I2", "I3", "I4", "I5", "I6", "I7", "I8", "I9", "I10",
            "I11", "I12", "I13", "I14", "I15", "I16", "I17", "I18", "I19", "I20"));

    @Test
    @DisplayName("TST-TEN-001: every isolation path of DOC-16 section 5 has a test method in this inventory")
    void theInventoryIsComplete() {
        Set<String> covered = Arrays.stream(IsolationPathInventoryTest.class.getDeclaredMethods())
                .map(m -> m.getAnnotation(IsolationPath.class))
                .filter(java.util.Objects::nonNull)
                .map(IsolationPath::value)
                .collect(Collectors.toCollection(TreeSet::new));

        assertEquals(REQUIRED_PATHS, covered,
                "DOC-16 section 5 defines twenty isolation paths and TST-TEN-001 forbids introducing a "
                        + "subsystem without adding its path and assertion. A path missing from this "
                        + "inventory is a path nothing enforces, and no other test detects its absence.");
    }

    // -----------------------------------------------------------------------------------------
    // Paths whose subsystem exists at the end of prompt 3.
    // -----------------------------------------------------------------------------------------

    @Test
    @IsolationPath("I8")
    @DisplayName("I8: a background job without a tenant binding does not execute")
    void backgroundJobWithoutBindingDoesNotExecute() {
        // TenantBoundWork has no constructor without a context, so an unbound work item is not
        // constructible. Asserted in FailClosedTest; repeated here as the inventory's entry so the
        // path is covered rather than merely covered elsewhere.
        assertTrue(Arrays.stream(
                        aspm.kernel.tenantcontext.contract.TenantBoundWork.class.getConstructors())
                .allMatch(c -> c.getParameterCount() == 3
                        && c.getParameterTypes()[0]
                                == aspm.kernel.tenantcontext.contract.TenantContext.class),
                "SEC-TEN-006: every constructor must require an explicit tenant binding as its first "
                        + "argument, or an unbound work item becomes constructible");
    }

    @Test
    @IsolationPath("I9")
    @DisplayName("I9: an event handler establishes tenant from the event, not from an ambient context")
    void eventHandlerEstablishesFromTheEvent() {
        // DomainEvent carries tenantId explicitly. A handler therefore has a binding available and
        // does not need to inherit one, which is what DOC-24 section 6.2 entry 2 requires.
        assertTrue(Arrays.stream(aspm.events.DomainEvent.class.getMethods())
                        .anyMatch(m -> m.getName().equals("tenantId")),
                "SEC-TEN-006: an event without its tenant forces a handler to infer one");
    }

    // -----------------------------------------------------------------------------------------
    // Paths whose subsystem does not exist yet. Each names the prompt that will implement it.
    // These are SKIPPED, not absent: a skip is a visible debt, an absence is not.
    // -----------------------------------------------------------------------------------------

    @Test
    @IsolationPath("I1")
    @Disabled("API layer is prompt 12. I1: identifier substitution returns not-found, indistinguishable "
            + "from non-existence, in equivalent time (SEC-AUZ-020, A3).")
    void apiSingleObjectRead() {}

    @Test
    @IsolationPath("I2")
    @Disabled("API layer is prompt 12. I2: no foreign row in a collection read; count is post-filter "
            + "(SEC-AUZ-016).")
    void apiCollectionRead() {}

    @Test
    @IsolationPath("I3")
    @Disabled("API layer is prompt 12. I3: a foreign identifier in path or body is rejected.")
    void apiWrite() {}

    @Test
    @IsolationPath("I4")
    @Disabled("Search is prompt 9 (work management) and rides ADR-051's in-engine full-text search. "
            + "I4: no foreign document; hit count post-filter; relevance unaffected by foreign content "
            + "(SEC-TEN-011).")
    void search() {}

    @Test
    @IsolationPath("I5")
    @Disabled("Read models are prompt 13. I5: no foreign contribution to an aggregate; no derivation "
            + "by subtraction (SEC-AUZ-026).")
    void aggregation() {}

    @Test
    @IsolationPath("I6")
    @Disabled("Export is prompt 6/11. I6: no foreign row in any format (PRD-ING-014).")
    void export() {}

    @Test
    @IsolationPath("I7")
    @DisplayName("I7: a notification is rendered for one recipient — the rendered type names them and the renderer takes them first (PRD-NTF-007)")
    void notification() {
        // Structural half: the domain's rendered type carries the recipient as its first component, and
        // the only way to produce one takes the recipient as its first argument — so a rendering shared
        // between recipients is not constructible. The behavioural half — one row per recipient, the
        // subject re-checked against that recipient at delivery, SUPPRESSED rather than redacted when
        // it is no longer visible — is asserted against the platform's schema in
        // aspm.app.notification.NotificationDeliveryTest.
        var rendered = aspm.module.notification.domain.RenderedNotification.class;
        assertTrue(rendered.getRecordComponents().length > 0
                        && rendered.getRecordComponents()[0].getName().equals("recipientId")
                        && rendered.getRecordComponents()[0].getType() == java.util.UUID.class,
                "PRD-NTF-014: a rendered notification names its one recipient first");
        var renderer = aspm.module.notification.domain.NotificationDispatch.Renderer.class;
        assertTrue(Arrays.stream(renderer.getMethods())
                        .filter(m -> m.getName().startsWith("render"))
                        .allMatch(m -> m.getParameterCount() >= 1 && m.getParameterTypes()[0] == java.util.UUID.class),
                "every render operation takes the recipient first; there is no render-for-everyone");
    }

    @Test
    @IsolationPath("I10")
    @Disabled("Cache is not yet wired. I10: foreign key construction impossible. ADR-055 records that "
            + "no cache technology provides this at the store layer, so the control is the mandatory "
            + "key constructor plus the Error Prone ban on direct client access — this test must assert "
            + "both, and it is the highest-severity accepted risk in the stack decisions.")
    void cache() {}

    @Test
    @IsolationPath("I11")
    @Disabled("AI assistance is prompt 17. I11: no foreign record in retrieved context; prompt cache "
            + "tenant-keyed (INV-AIC-09).")
    void aiContext() {}

    @Test
    @IsolationPath("I12")
    @Disabled("Evidence handling is prompt 10; object storage is ADR-056. I12: a signed reference is "
            + "bound to tenant and object, and a per-tenant supplied encryption key means a policy "
            + "failure still yields ciphertext.")
    void fileAndEvidenceRetrieval() {}

    @Test
    @IsolationPath("I13")
    @DisplayName("I13: a secret reference carries no tenant, and every resolution demands one (SEC-SEC-023)")
    void secretResolution() {
        // The contract of ADR-052 whose adapters live in aspm.app.secrets. Two structural facts make a
        // reference minted in tenant A useless in tenant B: the reference record has no tenant component,
        // so a reference alone cannot select a tenant; and the only read operation takes the tenant as
        // its first argument, so an adapter has what it needs to refuse — and each adapter's refusal is
        // asserted behaviourally in aspm.app.secrets.SecretsTest against a fake provider (no request is
        // made for a foreign reference). The sealed adapter is refused by the row-level policy itself.
        var reference = aspm.sharedkernel.secrets.SecretReference.class;
        assertTrue(Arrays.stream(reference.getRecordComponents())
                        .noneMatch(c -> c.getType() == java.util.UUID.class),
                "a SecretReference must not carry a tenant: the tenant is an argument to resolution, "
                        + "never a property of the reference");
        var contract = aspm.sharedkernel.secrets.SecretsProvider.class;
        var resolve = Arrays.stream(contract.getMethods())
                .filter(m -> m.getName().equals("resolve"))
                .toList();
        assertTrue(!resolve.isEmpty() && resolve.stream().allMatch(m -> m.getParameterCount() == 2
                        && m.getParameterTypes()[0] == java.util.UUID.class
                        && m.getParameterTypes()[1] == reference),
                "every resolve on the secrets contract takes (tenant, reference), so no adapter can be "
                        + "called without the tenant it must check against");
        assertTrue(Arrays.stream(contract.getMethods()).noneMatch(m -> m.getName().startsWith("list")
                        || m.getName().startsWith("search") || m.getName().startsWith("find")),
                "SEC-SEC-024: the contract has no enumeration, so 'non-retrievable after entry' holds "
                        + "as the absence of a read path (ADR-047, ADR-052)");
    }

    @Test
    @IsolationPath("I14")
    @Disabled("API error rendering is prompt 12. I14: no foreign identifier or content in any error "
            + "(PRD-API-004).")
    void errorResponses() {}

    @Test
    @IsolationPath("I15")
    @Disabled("Idempotency and rate limiting are prompt 12. I15: both namespaces tenant-namespaced "
            + "(DOC-24 section 6.2 entries 15 and 16).")
    void idempotencyAndRateLimitNamespaces() {}

    @Test
    @IsolationPath("I16")
    @Disabled("Finding identity is prompt 6 — the highest-risk block. I16: no cross-tenant "
            + "deduplication and no existence inference, because fingerprints are tenant-scoped in "
            + "their hash INPUTS and not merely in the query filter (INV-VUL-01).")
    void fingerprintDeduplication() {}

    @Test
    @IsolationPath("I17")
    @Disabled("Requires a live engine; blocked in this environment. I17: the post-migration "
            + "cross-tenant assertion passes (OPS-DEP-031, SEC-TEN-049). Migrations run with "
            + "enforcement bypassed and are the highest-risk operation in the platform.")
    void migration() {}

    @Test
    @IsolationPath("I18")
    @Disabled("Backup and restore are prompt 19. I18: a cross-tenant restore yields unreadable "
            + "ciphertext because backups are encrypted with tenant key material (OPS-DEP-035).")
    void restore() {}

    @Test
    @IsolationPath("I19")
    @Disabled("Requires a live engine and a pool; blocked in this environment. I19: session state is "
            + "reset on return and a pooled connection is not reusable with a stale tenant context "
            + "(SEC-TEN-007, OPS-DEP-010). DOC-24 calls this a documented cross-tenant disclosure "
            + "mechanism in row-level-security deployments.")
    void connectionPooling() {}

    @Test
    @IsolationPath("I20")
    @Disabled("Shared vulnerability intelligence is prompt 11. I20: the shared tables contain no "
            + "tenant-derived column, asserted against the schema (SEC-TEN-012).")
    void sharedIntelligence() {}
}
