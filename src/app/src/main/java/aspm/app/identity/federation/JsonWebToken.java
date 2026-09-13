package aspm.app.identity.federation;

import aspm.app.runtime.Json;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A JWS-signed ID token, parsed and verified with the JDK alone. {@code SEC-SEC-002}, {@code PRD-IAM-001}.
 *
 * <h2>Why no library</h2>
 *
 * <p>The platform's dependency set is small on purpose (ADR-050: every coordinate pinned, deterministic
 * resolution, air-gapped bundles). An ID token is three base64url segments; verifying one needs a JSON
 * parser the tier already has, a public key built from a JWK's {@code n}/{@code e} or {@code x}/{@code y},
 * and {@code java.security.Signature}. What a library would add is algorithm agility — and algorithm
 * agility is the historic vulnerability class of JWT (the {@code alg: none} and RSA-as-HMAC confusions),
 * which this class closes by accepting exactly the algorithms the provider's discovery document
 * advertises AND this list allows: {@code RS256}, {@code RS384}, {@code RS512}, {@code PS256},
 * {@code ES256}, {@code ES384}. Nothing symmetric, nothing "none".
 *
 * <h2>What is verified, in order</h2>
 *
 * <ol>
 *   <li>Structure: three segments, a header with {@code alg} and {@code kid}.
 *   <li>Algorithm: in the allowed set, and the key found for {@code kid} has the matching {@code kty}.
 *   <li>Signature over {@code header.payload}.
 *   <li>Claims: {@code iss} equals the configured issuer exactly; {@code aud} contains the client id
 *       (and {@code azp}, when present, equals it); {@code exp} is in the future and {@code iat} not
 *       unreasonably in the past, with 60 seconds of skew; {@code nonce} equals the handshake's.
 * </ol>
 *
 * <p>Every failure is one {@link Rejected} with a stable reason code. The reason is for the
 * platform's own record; the person at the browser sees "sign-in was not accepted" and nothing that
 * distinguishes a bad signature from a wrong audience, because that distinction helps an attacker
 * more than a user.
 */
public final class JsonWebToken {

    /** The algorithms this platform will verify. Symmetric and "none" are absent on purpose. */
    static final List<String> ALLOWED_ALGORITHMS = List.of("RS256", "RS384", "RS512", "PS256", "ES256", "ES384");

    static final long CLOCK_SKEW_SECONDS = 60;

    /** A verification failure. */
    public static final class Rejected extends Exception {
        private static final long serialVersionUID = 1L;
        private final String code;

        public Rejected(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final Map<String, Object> header;
    private final Map<String, Object> claims;
    private final byte[] signingInput;
    private final byte[] signature;

    private JsonWebToken(Map<String, Object> header, Map<String, Object> claims, byte[] signingInput, byte[] signature) {
        this.header = header;
        this.claims = claims;
        this.signingInput = signingInput;
        this.signature = signature;
    }

    /** Parses without verifying. Nothing read from the result may be trusted until {@link #verify} passes. */
    public static JsonWebToken parse(String compact) throws Rejected {
        if (compact == null) {
            throw new Rejected("TOKEN_MISSING");
        }
        String[] parts = compact.strip().split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new Rejected("TOKEN_MALFORMED");
        }
        try {
            Base64.Decoder decoder = Base64.getUrlDecoder();
            Map<String, Object> header = Json.readObject(new String(decoder.decode(parts[0]), StandardCharsets.UTF_8));
            Map<String, Object> claims = Json.readObject(new String(decoder.decode(parts[1]), StandardCharsets.UTF_8));
            return new JsonWebToken(header, claims,
                    (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII), decoder.decode(parts[2]));
        } catch (IllegalArgumentException e) {
            throw new Rejected("TOKEN_MALFORMED");
        }
    }

    public Map<String, Object> header() {
        return header;
    }

    /** Claims, trusted only after {@link #verify}. */
    public Map<String, Object> claims() {
        return claims;
    }

    public Optional<String> kid() {
        return header.get("kid") instanceof String s && !s.isBlank() ? Optional.of(s) : Optional.empty();
    }

    public String algorithm() {
        return header.get("alg") instanceof String s ? s : "";
    }

    /**
     * Verifies signature and claims.
     *
     * @param keys the provider's current JWK set, as parsed JSON objects
     * @param issuer the configured issuer, compared exactly
     * @param clientId the audience the token must name
     * @param nonce the handshake nonce the token must echo
     */
    public void verify(List<Map<String, Object>> keys, String issuer, String clientId, String nonce, Instant now)
            throws Rejected {
        String alg = algorithm();
        if (!ALLOWED_ALGORITHMS.contains(alg)) {
            throw new Rejected("ALGORITHM_NOT_ALLOWED");
        }
        Map<String, Object> jwk = selectKey(keys, alg);
        PublicKey publicKey = publicKey(jwk, alg);
        if (!signatureValid(publicKey, alg)) {
            throw new Rejected("SIGNATURE_INVALID");
        }
        // Claims, only after the signature: an unsigned token's claims are attacker text.
        if (!Objects.equals(issuer, claims.get("iss"))) {
            throw new Rejected("ISSUER_MISMATCH");
        }
        Object aud = claims.get("aud");
        boolean audienceOk = aud instanceof String s ? s.equals(clientId)
                : aud instanceof List<?> l && l.stream().anyMatch(clientId::equals);
        if (!audienceOk) {
            throw new Rejected("AUDIENCE_MISMATCH");
        }
        if (claims.get("azp") instanceof String azp && !azp.equals(clientId)) {
            throw new Rejected("AUTHORIZED_PARTY_MISMATCH");
        }
        long exp = number(claims.get("exp")).orElseThrow(() -> new Rejected("EXPIRY_MISSING"));
        if (now.getEpochSecond() > exp + CLOCK_SKEW_SECONDS) {
            throw new Rejected("TOKEN_EXPIRED");
        }
        Optional<Long> iat = number(claims.get("iat"));
        if (iat.isPresent() && iat.get() > now.getEpochSecond() + CLOCK_SKEW_SECONDS) {
            throw new Rejected("ISSUED_IN_FUTURE");
        }
        Optional<Long> nbf = number(claims.get("nbf"));
        if (nbf.isPresent() && nbf.get() > now.getEpochSecond() + CLOCK_SKEW_SECONDS) {
            throw new Rejected("NOT_YET_VALID");
        }
        if (nonce == null || !nonce.equals(claims.get("nonce"))) {
            throw new Rejected("NONCE_MISMATCH");
        }
    }

    private Map<String, Object> selectKey(List<Map<String, Object>> keys, String alg) throws Rejected {
        Optional<String> kid = kid();
        String wantedType = alg.startsWith("ES") ? "EC" : "RSA";
        List<Map<String, Object>> candidates = keys.stream()
                .filter(k -> wantedType.equals(k.get("kty")))
                .filter(k -> !(k.get("use") instanceof String use) || "sig".equals(use))
                .filter(k -> kid.isEmpty() || kid.get().equals(k.get("kid")))
                .toList();
        if (candidates.size() != 1) {
            // Zero is "unknown key"; more than one without a kid is ambiguity we do not resolve by
            // trying each, because trying each turns a key set into an oracle.
            throw new Rejected(candidates.isEmpty() ? "KEY_NOT_FOUND" : "KEY_AMBIGUOUS");
        }
        return candidates.get(0);
    }

    static PublicKey publicKey(Map<String, Object> jwk, String alg) throws Rejected {
        try {
            Base64.Decoder decoder = Base64.getUrlDecoder();
            if ("RSA".equals(jwk.get("kty"))) {
                BigInteger n = new BigInteger(1, decoder.decode(text(jwk, "n")));
                BigInteger e = new BigInteger(1, decoder.decode(text(jwk, "e")));
                if (n.bitLength() < 2048) {
                    throw new Rejected("KEY_TOO_SMALL");
                }
                return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e));
            }
            if ("EC".equals(jwk.get("kty"))) {
                String curve = text(jwk, "crv");
                String expectedCurve = "ES256".equals(alg) ? "P-256" : "P-384";
                if (!expectedCurve.equals(curve)) {
                    throw new Rejected("CURVE_MISMATCH");
                }
                java.security.AlgorithmParameters parameters = java.security.AlgorithmParameters.getInstance("EC");
                parameters.init(new java.security.spec.ECGenParameterSpec("P-256".equals(curve) ? "secp256r1" : "secp384r1"));
                java.security.spec.ECParameterSpec spec = parameters.getParameterSpec(java.security.spec.ECParameterSpec.class);
                ECPoint point = new ECPoint(new BigInteger(1, decoder.decode(text(jwk, "x"))),
                        new BigInteger(1, decoder.decode(text(jwk, "y"))));
                return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(point, spec));
            }
            throw new Rejected("KEY_TYPE_UNSUPPORTED");
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            throw new Rejected("KEY_INVALID");
        }
    }

    private boolean signatureValid(PublicKey key, String alg) {
        try {
            Signature verifier;
            byte[] sig = signature;
            switch (alg) {
                case "RS256" -> verifier = Signature.getInstance("SHA256withRSA");
                case "RS384" -> verifier = Signature.getInstance("SHA384withRSA");
                case "RS512" -> verifier = Signature.getInstance("SHA512withRSA");
                case "PS256" -> {
                    verifier = Signature.getInstance("RSASSA-PSS");
                    verifier.setParameter(new java.security.spec.PSSParameterSpec("SHA-256", "MGF1",
                            java.security.spec.MGF1ParameterSpec.SHA256, 32, 1));
                }
                case "ES256" -> {
                    verifier = Signature.getInstance("SHA256withECDSA");
                    sig = derFromJose(signature, 32);
                }
                case "ES384" -> {
                    verifier = Signature.getInstance("SHA384withECDSA");
                    sig = derFromJose(signature, 48);
                }
                default -> {
                    return false;
                }
            }
            verifier.initVerify(key);
            verifier.update(signingInput);
            return verifier.verify(sig);
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    /** JOSE encodes an ECDSA signature as r||s; the JDK wants DER. */
    static byte[] derFromJose(byte[] jose, int partLength) {
        if (jose.length != partLength * 2) {
            throw new IllegalArgumentException("ECDSA signature has the wrong length");
        }
        byte[] r = trimLeadingZeros(java.util.Arrays.copyOfRange(jose, 0, partLength));
        byte[] s = trimLeadingZeros(java.util.Arrays.copyOfRange(jose, partLength, jose.length));
        byte[] rDer = derInteger(r);
        byte[] sDer = derInteger(s);
        byte[] body = new byte[rDer.length + sDer.length];
        System.arraycopy(rDer, 0, body, 0, rDer.length);
        System.arraycopy(sDer, 0, body, rDer.length, sDer.length);
        byte[] lengthBytes = derLength(body.length);
        byte[] out = new byte[1 + lengthBytes.length + body.length];
        out[0] = 0x30;
        System.arraycopy(lengthBytes, 0, out, 1, lengthBytes.length);
        System.arraycopy(body, 0, out, 1 + lengthBytes.length, body.length);
        return out;
    }

    private static byte[] derInteger(byte[] magnitude) {
        boolean pad = (magnitude[0] & 0x80) != 0;
        byte[] content = pad ? new byte[magnitude.length + 1] : magnitude;
        if (pad) {
            System.arraycopy(magnitude, 0, content, 1, magnitude.length);
        }
        byte[] lengthBytes = derLength(content.length);
        byte[] out = new byte[1 + lengthBytes.length + content.length];
        out[0] = 0x02;
        System.arraycopy(lengthBytes, 0, out, 1, lengthBytes.length);
        System.arraycopy(content, 0, out, 1 + lengthBytes.length, content.length);
        return out;
    }

    private static byte[] derLength(int length) {
        return length < 128 ? new byte[] {(byte) length} : new byte[] {(byte) 0x81, (byte) length};
    }

    private static byte[] trimLeadingZeros(byte[] bytes) {
        int i = 0;
        while (i < bytes.length - 1 && bytes[i] == 0) {
            i++;
        }
        return java.util.Arrays.copyOfRange(bytes, i, bytes.length);
    }

    private static String text(Map<String, Object> jwk, String field) throws Rejected {
        if (jwk.get(field) instanceof String s && !s.isBlank()) {
            return s;
        }
        throw new Rejected("KEY_INVALID");
    }

    private static Optional<Long> number(Object value) {
        return value instanceof Number n ? Optional.of(n.longValue()) : Optional.empty();
    }
}
