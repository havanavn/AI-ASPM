package aspm.app.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a service credential may exercise. {@code ADR-004}, PP-7.
 *
 * <p><b>What this is guarding against, in the past tense.</b> An empty permission array on a
 * credential meant "everything the principal holds". That is a fail-open default in the most literal
 * sense: the shape produced by omitting the field entirely was the widest one the table could express,
 * so a key minted by a script that did not know about the field carried its owner's whole authority —
 * on a platform whose owners are the people with the broadest scope. No live credential was ever
 * issued that way, because the interface requires a selection, but the resolver did not.
 *
 * <p>The intersection itself is the other half and is not new: a credential can never exceed the
 * identity behind it, so revoking a role revokes every key that identity minted.
 */
class ServiceCredentialPermissionTest {

    @Test
    @DisplayName("an empty declaration grants nothing, not everything")
    void emptyDeclarationGrantsNothing() {
        Set<String> held = Set.of("ast.asset.read", "vul.finding.triage", "iam.credential.reset");

        assertTrue(ServiceCredentialResolver.effective(Set.of(), held).isEmpty(),
                "a credential that declares no permission must be able to do nothing at all");
    }

    @Test
    @DisplayName("a declaration is intersected with what the principal holds")
    void declarationIsIntersected() {
        Set<String> declared = Set.of("ast.asset.read", "iam.credential.reset");
        Set<String> held = Set.of("ast.asset.read", "vul.finding.triage");

        assertEquals(Set.of("ast.asset.read"),
                ServiceCredentialResolver.effective(declared, held),
                "iam.credential.reset was declared but not held, so the key must not carry it");
    }

    @Test
    @DisplayName("a declaration cannot add authority the principal lost")
    void revokedRoleRevokesTheKey() {
        Set<String> declared = Set.of("sbm.sbom.submit", "ing.findings.import");

        assertTrue(ServiceCredentialResolver.effective(declared, Set.of()).isEmpty(),
                "a principal whose roles were revoked must take every credential it minted with it");
    }
}
