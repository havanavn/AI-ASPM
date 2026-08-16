package aspm.app.resource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The object store, as much of S3 as this platform uses: put an object, get it back.
 *
 * <h2>Why a hand-written signer rather than the AWS SDK</h2>
 *
 * <p>The SDK is a large dependency graph on a platform that holds the exploitable attack surface of
 * an entire group — and the software composition dashboard three floors down would be the first to
 * report it. SigV4 is a documented hashing procedure and the two operations needed here are PUT and
 * GET of a single object. That is a hundred lines against several hundred transitive dependencies.
 *
 * <p>The same argument as {@link Workbook}: write the small thing rather than review the large one.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p>No multipart, no listing, no presigned URLs, no retries. An SBOM document is one object of at
 * most a few megabytes, and a failure to store one is reported to the submitter rather than retried
 * silently. Adding an operation here should be a deliberate act, because every one of them is a new
 * way for content to leave.
 *
 * <h2>Configuration</h2>
 *
 * <p>Endpoint and credentials come from the environment, which is where the compose file already
 * puts them. Absent configuration is not an error at construction — {@link #configured()} reports it
 * and the caller decides. A platform that refused to start because an optional store was missing
 * would make the object store a hard dependency of ingestion, which it is not yet.
 */
public final class ObjectStore {

    /** Where SBOM documents live. Created by {@code objectstore-init} in the compose file. */
    public static final String SBOM_BUCKET = "aspm-evidence";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final String endpoint;
    private final String accessKey;
    private final String secretKey;
    private final String region;

    public ObjectStore(Map<String, String> environment) {
        this.endpoint = trimSlash(environment.getOrDefault("ASPM_OBJECTSTORE_ENDPOINT", ""));
        this.accessKey = environment.getOrDefault("ASPM_OBJECTSTORE_USER", "");
        this.secretKey = environment.getOrDefault("ASPM_OBJECTSTORE_PASSWORD", "");
        // MinIO ignores the region but SigV4 signs it, so both sides must agree on a value. The
        // default matches what MinIO reports when nothing is set.
        this.region = environment.getOrDefault("ASPM_OBJECTSTORE_REGION", "us-east-1");
    }

    /** Whether a store is configured at all. A deployment without one simply stores nothing. */
    public boolean configured() {
        return !endpoint.isBlank() && !accessKey.isBlank() && !secretKey.isBlank();
    }

    /**
     * Stores an object and returns the reference to record against the row that owns it.
     *
     * @return {@code s3://bucket/key} on success, empty on any failure. Empty rather than an
     *     exception: the caller is the ingestion path, and a bill of materials that parsed correctly
     *     must not be rejected because the archive copy could not be written. What is lost is the
     *     ability to re-scan that snapshot later, which the reference being NULL records exactly.
     */
    public Optional<String> put(String bucket, String key, byte[] content, String contentType) {
        if (!configured()) {
            return Optional.empty();
        }
        try {
            String payloadHash = hex(sha256(content));
            HttpRequest request = signed("PUT", bucket, key, payloadHash, contentType)
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                    .build();
            HttpResponse<Void> response = CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() >= 200 && response.statusCode() < 300
                    ? Optional.of("s3://" + bucket + "/" + key)
                    : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Reads an object back, by the reference {@link #put} returned. */
    public Optional<byte[]> get(String storageRef) {
        if (!configured() || storageRef == null || !storageRef.startsWith("s3://")) {
            return Optional.empty();
        }
        String path = storageRef.substring("s3://".length());
        int slash = path.indexOf('/');
        if (slash <= 0) {
            return Optional.empty();
        }
        try {
            HttpRequest request = signed("GET", path.substring(0, slash), path.substring(slash + 1),
                    hex(sha256(new byte[0])), null).GET().build();
            HttpResponse<byte[]> response = CLIENT.send(request,
                    HttpResponse.BodyHandlers.ofByteArray());
            return response.statusCode() == 200 ? Optional.of(response.body()) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    // ----------------------------------------------------------------------------------------------

    /**
     * Builds a request carrying an AWS Signature Version 4 header.
     *
     * <p>Path style ({@code endpoint/bucket/key}) rather than virtual host style, because MinIO in a
     * compose network is reached by service name and {@code bucket.objectstore} does not resolve.
     *
     * <p>The payload hash is signed, so a proxy cannot alter the body without invalidating the
     * signature — which is the property that makes this safe over the plain HTTP of an internal
     * network, and is why {@code UNSIGNED-PAYLOAD} is not used.
     */
    private HttpRequest.Builder signed(String method, String bucket, String key, String payloadHash,
            String contentType) {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        String stamp = STAMP.format(now);
        String day = DAY.format(now);
        URI uri = URI.create(endpoint + "/" + bucket + "/" + key);
        String host = uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");

        // The canonical request. Header order is alphabetical and is part of what is signed, so the
        // list below and the signed-headers string must not drift apart.
        StringBuilder canonicalHeaders = new StringBuilder();
        StringBuilder signedHeaders = new StringBuilder();
        if (contentType != null) {
            canonicalHeaders.append("content-type:").append(contentType).append('\n');
            signedHeaders.append("content-type;");
        }
        canonicalHeaders.append("host:").append(host).append('\n')
                .append("x-amz-content-sha256:").append(payloadHash).append('\n')
                .append("x-amz-date:").append(stamp).append('\n');
        signedHeaders.append("host;x-amz-content-sha256;x-amz-date");

        String canonical = method + "\n" + uri.getRawPath() + "\n\n"
                + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;
        String credentialScope = day + "/" + region + "/s3/aws4_request";
        String toSign = "AWS4-HMAC-SHA256\n" + stamp + "\n" + credentialScope + "\n"
                + hex(sha256(canonical.getBytes(StandardCharsets.UTF_8)));

        byte[] signingKey = hmac(hmac(hmac(hmac(
                ("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), day),
                region), "s3"), "aws4_request");
        String signature = hex(hmac(signingKey, toSign));

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("x-amz-date", stamp)
                .header("x-amz-content-sha256", payloadHash)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + accessKey + "/"
                        + credentialScope + ", SignedHeaders=" + signedHeaders
                        + ", Signature=" + signature);
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        return builder;
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 is required by the platform", e);
        }
    }

    private static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }

    private static String hex(byte[] value) {
        return HexFormat.of().formatHex(value);
    }

    private static String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
