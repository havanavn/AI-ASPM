package aspm.app.secrets;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * AWS Signature Version 4 for a JSON-over-POST service call. Small on purpose: no query strings, no
 * chunked payloads, no presigning — the Secrets Manager and STS calls this tier makes need none of them,
 * and a signer that supports what nothing uses is a signer nobody has exercised.
 *
 * <p>Distinct from the object store's signer in {@code ObjectStore}, which signs path-style S3 requests;
 * the two share the algorithm and not the request shape, and factoring them together was judged worse
 * than two short, separately-tested implementations.
 */
final class SigV4 {

    record Credentials(String accessKeyId, char[] secretAccessKey, String sessionToken, Instant expiresAt) {
        boolean fresh(Instant now) {
            return expiresAt == null || now.isBefore(expiresAt.minusSeconds(120));
        }
    }

    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private SigV4() {
    }

    /**
     * Signs a POST to {@code uri} with the given extra headers (which MUST include any {@code X-Amz-Target}
     * and {@code Content-Type}) and returns the complete header map to send, including
     * {@code Authorization}, {@code X-Amz-Date} and, when present, {@code X-Amz-Security-Token}.
     */
    static Map<String, String> sign(String method, URI uri, Map<String, String> headers, String body,
            String region, String service, Credentials credentials, Instant now) {
        String amzDate = AMZ_DATE.format(now);
        String shortDate = SHORT_DATE.format(now);

        TreeMap<String, String> canonicalHeaders = new TreeMap<>();
        canonicalHeaders.put("host", uri.getHost());
        canonicalHeaders.put("x-amz-date", amzDate);
        headers.forEach((k, v) -> canonicalHeaders.put(k.toLowerCase(java.util.Locale.ROOT), v.strip()));
        if (credentials.sessionToken() != null) {
            canonicalHeaders.put("x-amz-security-token", credentials.sessionToken());
        }

        StringBuilder canonicalHeaderText = new StringBuilder();
        StringBuilder signedHeaders = new StringBuilder();
        canonicalHeaders.forEach((k, v) -> {
            canonicalHeaderText.append(k).append(':').append(v).append('\n');
            if (signedHeaders.length() > 0) {
                signedHeaders.append(';');
            }
            signedHeaders.append(k);
        });

        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        String query = uri.getRawQuery() == null ? "" : uri.getRawQuery();
        String payloadHash = hex(sha256(body == null ? "" : body));
        String canonicalRequest = method + "\n" + path + "\n" + query + "\n" + canonicalHeaderText + "\n"
                + signedHeaders + "\n" + payloadHash;

        String scope = shortDate + "/" + region + "/" + service + "/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + hex(sha256(canonicalRequest));

        byte[] kDate = hmac(("AWS4" + new String(credentials.secretAccessKey())).getBytes(StandardCharsets.UTF_8), shortDate);
        byte[] kRegion = hmac(kDate, region);
        byte[] kService = hmac(kRegion, service);
        byte[] kSigning = hmac(kService, "aws4_request");
        String signature = hex(hmac(kSigning, stringToSign));

        Map<String, String> out = new LinkedHashMap<>(headers);
        out.put("X-Amz-Date", amzDate);
        if (credentials.sessionToken() != null) {
            out.put("X-Amz-Security-Token", credentials.sessionToken());
        }
        out.put("Authorization", "AWS4-HMAC-SHA256 Credential=" + credentials.accessKeyId() + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature);
        return out;
    }

    static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is required by the platform", e);
        }
    }

    static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
