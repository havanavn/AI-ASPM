package aspm.app.assessment;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encryption for a test credential the platform holds until an engagement closes. V033.
 *
 * <h2>What this is, and what it is not</h2>
 *
 * <p>It is envelope-less symmetric encryption with a key supplied by the deployment. It is
 * <b>not</b> a secrets manager: there is no rotation schedule, no key hierarchy, no per-tenant key,
 * and no hardware backing. {@code OQ-026} decides what the real answer is, and this is deliberately
 * the smallest thing that makes custody defensible in the meantime rather than a half-built vault
 * that would have to be unpicked when the question is answered.
 *
 * <p><b>ADR-002 wants per-tenant keys and this is one key for the deployment.</b> Said plainly rather
 * than implied: the gap is real, it is bounded by these values living for the length of one
 * engagement rather than for the life of the tenant, and closing it is part of answering OQ-026.
 *
 * <h2>No key means no storage, and that is the point</h2>
 *
 * <p>{@link #available()} is false when the environment supplies no key, and every caller refuses to
 * accept a password rather than storing it in the clear. Product principle 9 — fail loudly, degrade
 * explicitly. A fallback to plaintext is the exact failure this class exists to prevent, and it is
 * the fallback that gets written when the alternative is an error message during a demo.
 *
 * <h2>AES-256-GCM, and why the nonce is stored beside the ciphertext</h2>
 *
 * <p>GCM authenticates as well as encrypts, so a tampered ciphertext fails to decrypt rather than
 * decrypting to something else. The nonce is not a secret — it must be unique per encryption, never
 * unpredictable — so storing it in the adjacent column is correct. <b>Reusing a nonce under one key
 * in GCM is catastrophic</b>, which is why it comes from {@link SecureRandom} on every call and is
 * never derived from the row.
 */
public final class CredentialCustody {

    /** Where the key comes from. Base64, 32 bytes decoded. */
    public static final String KEY_VARIABLE = "ASPM_CREDENTIAL_KEY";

    /** Named per row, so a later cipher change migrates rows rather than requiring a flag day. */
    public static final String ALGORITHM = "AES-256-GCM";

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    /** A password longer than this is a paste accident, not a credential. */
    public static final int MAX_LENGTH = 512;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    /** The sealed form: what goes in the three columns. */
    public record Sealed(byte[] ciphertext, byte[] nonce, String algorithm) {
    }

    private CredentialCustody(SecretKeySpec key) {
        this.key = key;
    }

    /**
     * Reads the key from the environment, or returns empty.
     *
     * <p>Empty is a supported state, not an error: a deployment that never lodges a password needs
     * no key. What is not supported is lodging one without it.
     */
    public static CredentialCustody from(java.util.Map<String, String> environment) {
        String encoded = environment.get(KEY_VARIABLE);
        if (encoded == null || encoded.isBlank()) {
            return new CredentialCustody(null);
        }
        byte[] material;
        try {
            material = Base64.getDecoder().decode(encoded.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(KEY_VARIABLE + " is not valid base64. Refusing to start "
                    + "rather than running with credential custody silently disabled — a deployment "
                    + "that configured a key expects it to work.");
        }
        if (material.length != 32) {
            throw new IllegalStateException(KEY_VARIABLE + " decodes to " + material.length
                    + " bytes; AES-256 needs 32. Generate one with: openssl rand -base64 32");
        }
        return new CredentialCustody(new SecretKeySpec(material, "AES"));
    }

    /** Whether this deployment can hold a credential at all. */
    public boolean available() {
        return key != null;
    }

    /**
     * Seals a password.
     *
     * @throws IllegalStateException where no key is configured. Deliberately not a silent no-op:
     *     the caller must refuse the submission, not accept it and drop the value
     */
    public Sealed seal(String plaintext) {
        if (key == null) {
            throw new IllegalStateException("no credential key is configured, so this deployment "
                    + "cannot hold a password. Set " + KEY_VARIABLE + ", or submit a reference to "
                    + "where the credential lives instead.");
        }
        if (plaintext == null || plaintext.isEmpty()) {
            throw new IllegalArgumentException("nothing to seal");
        }
        if (plaintext.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("that is longer than a credential; check what was "
                    + "pasted");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return new Sealed(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)), nonce,
                    ALGORITHM);
        } catch (GeneralSecurityException e) {
            // No detail. PRD-UIX-025 keeps cipher internals out of an error surface, and this one
            // reaches a page.
            throw new IllegalStateException("the credential could not be sealed");
        }
    }

    /**
     * Opens a sealed credential, or empty where it cannot be opened.
     *
     * <p>Empty rather than an exception for a wrong key or a tampered row, because the caller's
     * response is the same either way and a distinguishable failure is an oracle.
     */
    public Optional<String> open(byte[] ciphertext, byte[] nonce, String algorithm) {
        if (key == null || ciphertext == null || nonce == null || !ALGORITHM.equals(algorithm)) {
            return Optional.empty();
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return Optional.of(new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            return Optional.empty();
        }
    }
}
