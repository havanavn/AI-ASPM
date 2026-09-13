package aspm.app.identity.federation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import aspm.app.runtime.Json;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ID token verification with the JDK alone. {@code SEC-SEC-002}, {@code PRD-IAM-001}.
 *
 * <p>Keys are generated per run: a test that ships a private key ships a key somebody will reuse.
 */
public class JsonWebTokenTest {

    static final String ISSUER = "https://idp.example.com/oauth2/default";
    static final String CLIENT = "aspm-client";
    static final String NONCE = "n-1234567890abcdef1234567890abcdef";

    /** A signing identity a fake provider would hold: key pair, kid, and its JWK. */
    public static final class Signer {
        final KeyPair pair;
        final String kid;
        final String alg;

        public Signer(String alg) throws Exception {
            this.alg = alg;
            this.kid = "k-" + alg;
            if (alg.startsWith("ES")) {
                KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
                g.initialize(new ECGenParameterSpec("ES256".equals(alg) ? "secp256r1" : "secp384r1"));
                pair = g.generateKeyPair();
            } else {
                KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
                g.initialize(2048);
                pair = g.generateKeyPair();
            }
        }

        public Map<String, Object> jwk() {
            Map<String, Object> jwk = new LinkedHashMap<>();
            jwk.put("kid", kid);
            jwk.put("use", "sig");
            if (pair.getPublic() instanceof RSAPublicKey rsa) {
                jwk.put("kty", "RSA");
                jwk.put("n", b64(unsigned(rsa.getModulus())));
                jwk.put("e", b64(unsigned(rsa.getPublicExponent())));
            } else if (pair.getPublic() instanceof ECPublicKey ec) {
                int size = "ES256".equals(alg) ? 32 : 48;
                jwk.put("kty", "EC");
                jwk.put("crv", "ES256".equals(alg) ? "P-256" : "P-384");
                jwk.put("x", b64(fixed(ec.getW().getAffineX(), size)));
                jwk.put("y", b64(fixed(ec.getW().getAffineY(), size)));
            }
            return jwk;
        }

        public String sign(Map<String, Object> claims) throws Exception {
            return sign(claims, alg, kid);
        }

        String sign(Map<String, Object> claims, String headerAlg, String headerKid) throws Exception {
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("alg", headerAlg);
            header.put("kid", headerKid);
            header.put("typ", "JWT");
            String input = b64(Json.write(header).getBytes(StandardCharsets.UTF_8)) + "."
                    + b64(Json.write(claims).getBytes(StandardCharsets.UTF_8));
            byte[] signature;
            switch (alg) {
                case "RS256" -> signature = raw("SHA256withRSA", input);
                case "PS256" -> {
                    Signature s = Signature.getInstance("RSASSA-PSS");
                    s.setParameter(new java.security.spec.PSSParameterSpec("SHA-256", "MGF1",
                            java.security.spec.MGF1ParameterSpec.SHA256, 32, 1));
                    s.initSign(pair.getPrivate());
                    s.update(input.getBytes(StandardCharsets.US_ASCII));
                    signature = s.sign();
                }
                case "ES256" -> signature = joseFromDer(raw("SHA256withECDSA", input), 32);
                case "ES384" -> signature = joseFromDer(raw("SHA384withECDSA", input), 48);
                default -> throw new IllegalArgumentException(alg);
            }
            return input + "." + b64(signature);
        }

        private byte[] raw(String algorithm, String input) throws Exception {
            Signature s = Signature.getInstance(algorithm);
            s.initSign(pair.getPrivate());
            s.update(input.getBytes(StandardCharsets.US_ASCII));
            return s.sign();
        }
    }

    static Map<String, Object> claims(Instant now) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", ISSUER);
        claims.put("aud", CLIENT);
        claims.put("sub", "00u1abc");
        claims.put("exp", now.getEpochSecond() + 300);
        claims.put("iat", now.getEpochSecond());
        claims.put("nonce", NONCE);
        claims.put("email", "dev@example.com");
        return claims;
    }

    @Test
    @DisplayName("SEC-SEC-002: RS256, PS256, ES256 and ES384 tokens verify against the matching JWK")
    void everyAllowedAlgorithm() throws Exception {
        Instant now = Instant.now();
        for (String alg : List.of("RS256", "PS256", "ES256", "ES384")) {
            Signer signer = new Signer(alg);
            JsonWebToken token = JsonWebToken.parse(signer.sign(claims(now)));
            token.verify(List.of(signer.jwk()), ISSUER, CLIENT, NONCE, now);
            assertEquals("00u1abc", token.claims().get("sub"), alg);
        }
    }

    @Test
    @DisplayName("SEC-SEC-002: 'none', HMAC and a header algorithm that does not match the key are refused")
    void algorithmConfusion() throws Exception {
        Instant now = Instant.now();
        Signer rsa = new Signer("RS256");
        String none = b64(("{\"alg\":\"none\",\"kid\":\"" + rsa.kid + "\"}").getBytes(StandardCharsets.UTF_8))
                + "." + b64(Json.write(claims(now)).getBytes(StandardCharsets.UTF_8)) + ".AAAA";
        assertEquals("ALGORITHM_NOT_ALLOWED", reject(none, rsa, now));
        String hmac = b64(("{\"alg\":\"HS256\",\"kid\":\"" + rsa.kid + "\"}").getBytes(StandardCharsets.UTF_8))
                + "." + b64(Json.write(claims(now)).getBytes(StandardCharsets.UTF_8)) + ".AAAA";
        assertEquals("ALGORITHM_NOT_ALLOWED", reject(hmac, rsa, now));
        // Header says ES256, key set holds only RSA: no key of the right type → KEY_NOT_FOUND, never
        // "try the RSA key as EC".
        assertEquals("KEY_NOT_FOUND", reject(rsa.sign(claims(now), "ES256", rsa.kid), rsa, now));
    }

    @Test
    @DisplayName("SEC-SEC-002: a tampered payload, an unknown kid, a wrong issuer, audience or nonce, and an expired token are each refused")
    void claimsAndSignature() throws Exception {
        Instant now = Instant.now();
        Signer signer = new Signer("RS256");
        Signer other = new Signer("RS256");

        String good = signer.sign(claims(now));
        String[] parts = good.split("\\.");
        Map<String, Object> tampered = claims(now);
        tampered.put("sub", "attacker");
        String tamperedToken = parts[0] + "." + b64(Json.write(tampered).getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        assertEquals("SIGNATURE_INVALID", reject(tamperedToken, signer, now));

        assertEquals("SIGNATURE_INVALID", reject(other.sign(claims(now), "RS256", signer.kid), signer, now),
                "another key with the SAME kid does not verify");
        assertEquals("KEY_NOT_FOUND", reject(signer.sign(claims(now), "RS256", "unknown-kid"), signer, now));

        Map<String, Object> wrongIssuer = claims(now);
        wrongIssuer.put("iss", "https://evil.example.com");
        assertEquals("ISSUER_MISMATCH", reject(signer.sign(wrongIssuer), signer, now));

        Map<String, Object> wrongAudience = claims(now);
        wrongAudience.put("aud", List.of("someone-else"));
        assertEquals("AUDIENCE_MISMATCH", reject(signer.sign(wrongAudience), signer, now));

        Map<String, Object> wrongNonce = claims(now);
        wrongNonce.put("nonce", "replayed");
        assertEquals("NONCE_MISMATCH", reject(signer.sign(wrongNonce), signer, now));

        Map<String, Object> expired = claims(now);
        expired.put("exp", now.getEpochSecond() - 120);
        assertEquals("TOKEN_EXPIRED", reject(signer.sign(expired), signer, now));

        Map<String, Object> future = claims(now);
        future.put("iat", now.getEpochSecond() + 3600);
        assertEquals("ISSUED_IN_FUTURE", reject(signer.sign(future), signer, now));

        assertThrows(JsonWebToken.Rejected.class, () -> JsonWebToken.parse("a.b"));
        assertThrows(JsonWebToken.Rejected.class, () -> JsonWebToken.parse("not base64!.@@.%%"));
    }

    @Test
    @DisplayName("SEC-SEC-002: an RSA key below 2048 bits is refused even when its signature is valid")
    void smallKeyRefused() throws Exception {
        Instant now = Instant.now();
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(1024);
        KeyPair small = g.generateKeyPair();
        Signer signer = new Signer("RS256");
        // Swap in the small key for signing and its JWK for verification.
        RSAPublicKey pub = (RSAPublicKey) small.getPublic();
        Map<String, Object> jwk = new LinkedHashMap<>(signer.jwk());
        jwk.put("n", b64(unsigned(pub.getModulus())));
        jwk.put("e", b64(unsigned(pub.getPublicExponent())));
        Map<String, Object> header = Map.of("alg", "RS256", "kid", signer.kid);
        String input = b64(Json.write(header).getBytes(StandardCharsets.UTF_8)) + "."
                + b64(Json.write(claims(now)).getBytes(StandardCharsets.UTF_8));
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(small.getPrivate());
        s.update(input.getBytes(StandardCharsets.US_ASCII));
        String token = input + "." + b64(s.sign());
        JsonWebToken parsed = JsonWebToken.parse(token);
        JsonWebToken.Rejected rejected = assertThrows(JsonWebToken.Rejected.class,
                () -> parsed.verify(List.of(jwk), ISSUER, CLIENT, NONCE, now));
        assertEquals("KEY_TOO_SMALL", rejected.code());
    }

    private static String reject(String token, Signer signer, Instant now) throws Exception {
        JsonWebToken parsed = JsonWebToken.parse(token);
        JsonWebToken.Rejected rejected = assertThrows(JsonWebToken.Rejected.class,
                () -> parsed.verify(List.of(signer.jwk()), ISSUER, CLIENT, NONCE, now));
        return rejected.code();
    }

    static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        return bytes[0] == 0 ? java.util.Arrays.copyOfRange(bytes, 1, bytes.length) : bytes;
    }

    static byte[] fixed(BigInteger value, int size) {
        byte[] raw = unsigned(value);
        if (raw.length == size) {
            return raw;
        }
        byte[] out = new byte[size];
        System.arraycopy(raw, 0, out, size - raw.length, raw.length);
        return out;
    }

    /** The JDK produces DER; JOSE wants r||s of fixed width. */
    static byte[] joseFromDer(byte[] der, int size) {
        int offset = 2;
        if ((der[1] & 0x80) != 0) {
            offset += der[1] & 0x7f;
        }
        int rLength = der[offset + 1];
        byte[] r = java.util.Arrays.copyOfRange(der, offset + 2, offset + 2 + rLength);
        int sOffset = offset + 2 + rLength;
        int sLength = der[sOffset + 1];
        byte[] s = java.util.Arrays.copyOfRange(der, sOffset + 2, sOffset + 2 + sLength);
        byte[] out = new byte[size * 2];
        byte[] rr = r.length > size ? java.util.Arrays.copyOfRange(r, r.length - size, r.length) : r;
        byte[] ss = s.length > size ? java.util.Arrays.copyOfRange(s, s.length - size, s.length) : s;
        System.arraycopy(rr, 0, out, size - rr.length, rr.length);
        System.arraycopy(ss, 0, out, size * 2 - ss.length, ss.length);
        return out;
    }
}
