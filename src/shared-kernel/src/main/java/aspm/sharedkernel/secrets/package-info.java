/**
 * The secrets contract of ADR-052, as a published language.
 *
 * <p>DOC-03 section 5.2 keeps the shared kernel to identifiers and value objects, and this package is
 * the one deliberate addition: a value object ({@link aspm.sharedkernel.secrets.SecretReference}) and the
 * port every context uses to turn one into a value
 * ({@link aspm.sharedkernel.secrets.SecretsProvider}). It lives here rather than in the application
 * tier because identity (a federated provider's client secret), notification (a relay password, a bot
 * token) and integration (a tracker credential) all hold references and none of them may depend on the
 * composition root — and rather than in a kernel module because DOC-02 section 6.2 fixes the kernel at
 * five modules and a secrets store is not a sixth. The adapters — sealed, Vault/OpenBao, Azure Key
 * Vault, AWS Secrets Manager, Google Secret Manager, mounted file, process environment — are
 * infrastructure and live in {@code aspm.app.secrets}. {@code SEC-SEC-023}, {@code PRD-CON-021}.
 */
package aspm.sharedkernel.secrets;
