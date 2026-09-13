package aspm.sharedkernel.secrets;

import java.util.Locale;
import java.util.Objects;

/**
 * Where a secret lives, said without saying what it is. {@code SEC-SEC-023}, {@code PRD-CON-021}, ADR-052.
 *
 * <p>Shape: {@code <provider>:<path>}. The provider prefix selects the adapter; the path means whatever
 * that adapter says it means. Two properties are deliberate and load-bearing:
 *
 * <ul>
 *   <li><b>A reference carries no tenant.</b> Every resolution takes the tenant as a separate argument
 *       and the adapter checks the path belongs to that tenant (or, for the sealed store, the row-level
 *       policy does). So a reference copied out of one tenant's configuration into another's resolves
 *       to nothing — DOC-24 §6.2 entry 12's stated failure, closed by the shape of the API rather than
 *       by a check somebody has to remember. Isolation path I13.
 *   <li><b>A reference is safe to display, log and export.</b> It names a location, never a value, so
 *       {@code SEC-SEC-025}'s redaction layer does not have to recognise it. The rule that makes this
 *       true is that no adapter ever encodes the secret into the path — and the sealed adapter's path is
 *       an opaque row id for exactly that reason.
 * </ul>
 */
public record SecretReference(String provider, String path) {

    /** Provider prefixes are short lowercase words; the path is anything non-empty. */
    public SecretReference {
        Objects.requireNonNull(provider, "a provider is required");
        Objects.requireNonNull(path, "a path is required");
        if (!provider.matches("[a-z][a-z0-9]{1,15}")) {
            throw new IllegalArgumentException("a secret reference names its provider as a short lowercase word");
        }
        if (path.isBlank() || path.length() > 512) {
            throw new IllegalArgumentException("a secret reference needs a path of at most 512 characters");
        }
        if (path.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("a secret reference path carries no whitespace");
        }
    }

    /** Parses {@code provider:path}. The FIRST colon splits, so a path may itself contain colons. */
    public static SecretReference parse(String text) {
        Objects.requireNonNull(text, "a secret reference is required");
        String value = text.strip();
        int colon = value.indexOf(':');
        if (colon <= 0 || colon == value.length() - 1) {
            throw new IllegalArgumentException(
                    "a secret reference has the shape <provider>:<path>, for example sealed:019f… or "
                            + "vault:tenants/…/smtp/password");
        }
        return new SecretReference(value.substring(0, colon).toLowerCase(Locale.ROOT),
                value.substring(colon + 1));
    }

    /** Whether the text has the shape of a reference at all, without constructing one. */
    public static boolean looksLikeReference(String text) {
        if (text == null) {
            return false;
        }
        try {
            parse(text);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public String toString() {
        return provider + ":" + path;
    }
}
