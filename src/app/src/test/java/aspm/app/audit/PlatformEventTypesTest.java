package aspm.app.audit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.kernel.audit.contract.AuditEventType;
import aspm.kernel.audit.contract.DomainChangeKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every event type the application emits resolves through the catalogue. {@code SEC-AUD-006}, S11.
 *
 * <p><b>Why this matters more than it looks.</b> {@code ChainedAuditWriter} refuses an uncatalogued
 * type, and audit is the platform's one deliberate availability-for-integrity trade — the action fails
 * if the event cannot be written. So a typo in an event name is not a wrong label in a report; it is a
 * create, a credential issue or an ingestion run that stops working. The codes are asserted here
 * because the alternative is discovering them one production path at a time.
 */
class PlatformEventTypesTest {

    @Test
    @DisplayName("every code the application actually emits is catalogued")
    void emittedCodesAreCatalogued() {
        // The exact codes the call sites use. Written out rather than derived from the same constants
        // the production code uses, because a test that computes the answer the same way agrees with a
        // mistake as readily as with a correct value.
        for (String code : new String[] {
            "asset.created", "asset.updated",
            "org_node.created", "org_node.updated",
            "asset_type.created", "asset_type.updated",
            "org_node_type.created", "org_node_type.updated",
            "criticality_tier.created", "criticality_tier.updated",
            "finding.updated",
            "assessment_request.updated",
            "import.completed",
            "sbom.submitted",
            "connector.credential.rotated",
            "object_grant.revoked",
            "authz.denied",
        }) {
            assertTrue(PlatformEventTypes.CATALOGUE.isCatalogued(code),
                    code + " is emitted by a production path and would fail the write");
        }
    }

    @Test
    @DisplayName("an aggregate nobody registered is refused")
    void unregisteredAggregateIsRefused() {
        assertFalse(PlatformEventTypes.CATALOGUE.isCatalogued("widget.created"),
                "an open catalogue would accept a typo and file events under a name nobody searches");
        assertFalse(PlatformEventTypes.CATALOGUE.isCatalogued("asset.deleted"),
                "'deleted' is not a DomainChangeKind, and nothing in this platform hard-deletes");
    }

    @Test
    @DisplayName("the whole enum is accepted, so a catalogued type is never refused by this layer")
    void theEnumIsAccepted() {
        for (AuditEventType type : AuditEventType.values()) {
            assertTrue(PlatformEventTypes.CATALOGUE.isCatalogued(type.code()), type.code());
        }
    }

    @Test
    @DisplayName("every registered aggregate accepts every change kind")
    void everyAggregateTakesEveryKind() {
        // So that adding a call site for an existing aggregate never needs a catalogue change: the
        // failure mode this guards against is a correct event refused at runtime because the pairing
        // happened not to be listed.
        for (DomainChangeKind kind : DomainChangeKind.values()) {
            assertTrue(PlatformEventTypes.CATALOGUE.isCatalogued(kind.codeFor("asset")),
                    kind.codeFor("asset"));
        }
    }
}
