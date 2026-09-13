package aspm.app.secrets;

import aspm.sharedkernel.secrets.SecretReference;
import aspm.sharedkernel.secrets.SecretsProvider;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import aspm.app.assessment.CredentialCustody;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The secrets contract of ADR-052 and its adapters. {@code SEC-SEC-023}, {@code SEC-SEC-024},
 * {@code SEC-SEC-025}, {@code OPS-DEP-019}, {@code OPS-DEP-020}, {@code PRD-CON-021}.
 *
 * <p>The cloud adapters are exercised against a fake HTTP server so that the request each one makes —
 * path, method, headers, body — is asserted rather than assumed. What a fake cannot assert is the real
 * provider's behaviour; that is the conformance job's business, not this suite's.
 */
class SecretsTest {

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Nested
    @DisplayName("References")
    class References {

        @Test
        @DisplayName("SEC-SEC-025: a reference names a location and carries no tenant and no value")
        void shape() {
            SecretReference ref = SecretReference.parse("vault:tenants/x/smtp/password");
            assertEquals("vault", ref.provider());
            assertEquals("tenants/x/smtp/password", ref.path());
            assertEquals("vault:tenants/x/smtp/password", ref.toString());
            // The first colon splits, so a path may carry colons of its own.
            assertEquals("projects/p:secret", SecretReference.parse("gcpsm:projects/p:secret").path());
        }

        @Test
        @DisplayName("SEC-SEC-023: text that is not a reference is refused rather than guessed at")
        void refusals() {
            assertThrows(IllegalArgumentException.class, () -> SecretReference.parse("hunter2"));
            assertThrows(IllegalArgumentException.class, () -> SecretReference.parse(":path"));
            assertThrows(IllegalArgumentException.class, () -> SecretReference.parse("vault:"));
            assertThrows(IllegalArgumentException.class, () -> SecretReference.parse("vault:has space"));
            assertFalse(SecretReference.looksLikeReference("p@ssw0rd!"));
            assertTrue(SecretReference.looksLikeReference("sealed:019f0000-0000-7000-8000-000000000000"));
        }
    }

    @Nested
    @DisplayName("Process environment")
    class Env {

        @Test
        @DisplayName("OPS-DEP-020: the env adapter refuses to construct outside development without a recorded exception")
        void refusedInProduction() {
            assertThrows(IllegalStateException.class,
                    () -> EnvSecretsProvider.from(Map.of("ASPM_ENVIRONMENT", "production", "X", "y")));
            EnvSecretsProvider allowed = EnvSecretsProvider.from(Map.of("ASPM_ENVIRONMENT", "production",
                    EnvSecretsProvider.ALLOW_VARIABLE, "true", "SMTP_PASSWORD", "s3"));
            assertEquals("s3", new String(allowed.resolve(TENANT_A, SecretReference.parse("env:SMTP_PASSWORD")).orElseThrow()));
        }

        @Test
        @DisplayName("SEC-SEC-023: env is deployment-level, read-only, and never a tenant's writer")
        void deploymentLevelOnly() {
            EnvSecretsProvider env = EnvSecretsProvider.from(Map.of("ASPM_ENVIRONMENT", "development", "K", "v"));
            assertFalse(env.tenantScoped());
            assertFalse(env.writable());
            assertThrows(SecretsProvider.SecretsException.class, () -> env.store(TENANT_A, "ns", "n", "v".toCharArray()));
            // Lower-case names are not environment variables of this platform; refused without a lookup.
            assertTrue(env.resolve(TENANT_A, SecretReference.parse("env:path")).isEmpty());
        }
    }

    @Nested
    @DisplayName("Mounted files")
    class Files_ {

        @TempDir
        Path mount;

        @Test
        @DisplayName("OPS-DEP-019: a mounted file resolves, per read, with its trailing newline removed")
        void reads() throws IOException {
            Files.createDirectories(mount.resolve("smtp"));
            Files.writeString(mount.resolve("smtp/password"), "relay-secret\n");
            FileSecretsProvider files = new FileSecretsProvider(mount);
            assertEquals("relay-secret",
                    new String(files.resolve(TENANT_A, SecretReference.parse("file:smtp/password")).orElseThrow()));
            Files.writeString(mount.resolve("smtp/password"), "rotated\n");
            assertEquals("rotated",
                    new String(files.resolve(TENANT_A, SecretReference.parse("file:smtp/password")).orElseThrow()),
                    "nothing is cached, so a re-mounted secret is what the next read sees");
        }

        @Test
        @DisplayName("SEC-SEC-023: a reference cannot leave the mount — by '..', by an absolute path, or by a link")
        void confined() throws IOException {
            Path outside = Files.createTempFile("aspm-outside", ".txt");
            try {
                Files.writeString(outside, "the database password");
                Files.createSymbolicLink(mount.resolve("escape"), outside);
                FileSecretsProvider files = new FileSecretsProvider(mount);
                assertTrue(files.resolve(TENANT_A, SecretReference.parse("file:../" + outside.getFileName())).isEmpty());
                assertTrue(files.resolve(TENANT_A, SecretReference.parse("file:" + outside)).isEmpty());
                assertTrue(files.resolve(TENANT_A, SecretReference.parse("file:escape")).isEmpty(),
                        "a symbolic link whose target is outside the mount is refused on its REAL path");
            } finally {
                Files.deleteIfExists(outside);
            }
        }
    }

    @Nested
    @DisplayName("Deployment references in the environment")
    class DeploymentReferences {

        @TempDir
        Path mount;

        @Test
        @DisplayName("OPS-DEP-020: ASPM_<NAME>_REF=file:<name> becomes ASPM_<NAME> in an in-memory copy, never in the process")
        void expands() throws IOException {
            Files.writeString(mount.resolve("db-app-password"), "pg-secret\n");
            Map<String, String> environment = Map.of(
                    FileSecretsProvider.DIRECTORY_VARIABLE, mount.toString(),
                    "ASPM_DB_PASSWORD_REF", "file:db-app-password",
                    "ASPM_DB_USER", "aspm_app",
                    "ASPM_OBJECTSTORE_PASSWORD_REF", "");
            Map<String, String> expanded = Secrets.expandDeploymentReferences(environment);
            assertEquals("pg-secret", expanded.get("ASPM_DB_PASSWORD"));
            assertEquals("aspm_app", expanded.get("ASPM_DB_USER"), "unrelated variables pass through");
            assertFalse(expanded.containsKey("ASPM_OBJECTSTORE_PASSWORD"), "a blank reference is not a reference");
            assertFalse(environment.containsKey("ASPM_DB_PASSWORD"), "the source map is untouched");
        }

        @Test
        @DisplayName("a reference that does not resolve, or a value AND a reference for the same name, stops the start")
        void refusesAmbiguityAndAbsence() {
            assertThrows(IllegalStateException.class, () -> Secrets.expandDeploymentReferences(Map.of(
                    FileSecretsProvider.DIRECTORY_VARIABLE, mount.toString(),
                    "ASPM_DB_PASSWORD_REF", "file:missing")));
            assertThrows(IllegalStateException.class, () -> Secrets.expandDeploymentReferences(Map.of(
                    FileSecretsProvider.DIRECTORY_VARIABLE, mount.toString(),
                    "ASPM_DB_PASSWORD", "typed",
                    "ASPM_DB_PASSWORD_REF", "file:db-app-password")));
        }
    }

    @Nested
    @DisplayName("The router")
    class Router {

        private final Recording tenantStore = new Recording("fake", true, true);
        private final Recording deploymentStore = new Recording("dep", false, false);

        @Test
        @DisplayName("PRD-CON-021: a tenant's reference never resolves through a deployment-level adapter")
        void tenantPathRefusesDeploymentAdapters() {
            deploymentStore.values.put("dep:db/password", "hunter2");
            Secrets secrets = new Secrets(List.of(deploymentStore, tenantStore), Optional.of(tenantStore),
                    CredentialCustody.from(Map.of()));
            assertTrue(secrets.resolveTenant(TENANT_A, SecretReference.parse("dep:db/password")).isEmpty(),
                    "a tenant administrator typing a deployment reference into a form gets nothing");
            assertEquals("hunter2", new String(secrets.resolveDeployment(SecretReference.parse("dep:db/password")).orElseThrow()),
                    "the operator's own environment reference resolves through the same adapter");
        }

        @Test
        @DisplayName("SEC-SEC-023: writes go to the designated writer and nowhere else; none configured means refused")
        void writer() {
            Secrets secrets = new Secrets(List.of(deploymentStore, tenantStore), Optional.of(tenantStore),
                    CredentialCustody.from(Map.of()));
            SecretReference ref = secrets.store(TENANT_A, "identity_provider", "client_secret", "s".toCharArray());
            assertEquals("fake", ref.provider());
            assertEquals(1, tenantStore.stored.size());

            Secrets readOnly = new Secrets(List.of(deploymentStore), Optional.empty(), CredentialCustody.from(Map.of()));
            assertFalse(readOnly.writable());
            assertThrows(SecretsProvider.SecretsException.class,
                    () -> readOnly.store(TENANT_A, "ns", "n", "v".toCharArray()));
            assertThrows(IllegalStateException.class,
                    () -> new Secrets(List.of(deploymentStore), Optional.of(deploymentStore), CredentialCustody.from(Map.of())),
                    "a read-only adapter cannot be designated the writer");
        }

        @Test
        @DisplayName("OPS-DEP-019: an unknown provider name in configuration fails start rather than silently narrowing")
        void unknownProviderRefused() {
            assertThrows(IllegalStateException.class, () -> Secrets.fromEnvironment(
                    Map.of("ASPM_SECRETS_PROVIDERS", "file,mystery", "ASPM_SECRETS_DIR", "/nonexistent"), null));
        }

        @Test
        @DisplayName("OPS-DEP-020: a _REF bootstrap value that does not resolve stops the platform from starting")
        void danglingBootstrapRefused(@TempDir Path mount) {
            assertThrows(IllegalStateException.class, () -> Secrets.fromEnvironment(Map.of(
                    "ASPM_SECRETS_PROVIDERS", "file",
                    "ASPM_SECRETS_DIR", mount.toString(),
                    Secrets.CREDENTIAL_KEY_REF, "file:credential-key"), null));
        }
    }

    @Nested
    @DisplayName("Signature Version 4")
    class Sigv4 {

        @Test
        @DisplayName("PRD-CON-021: the signer reproduces AWS's published get-vanilla test vector")
        void publishedVector() {
            // From the AWS SigV4 test suite (get-vanilla): GET / on example.amazonaws.com at
            // 2015-08-30T12:36:00Z with the documented example credentials.
            SigV4.Credentials credentials = new SigV4.Credentials("AKIDEXAMPLE",
                    "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY".toCharArray(), null, null);
            Map<String, String> signed = SigV4.sign("GET", URI.create("https://example.amazonaws.com/"),
                    Map.of(), "", "us-east-1", "service", credentials, Instant.parse("2015-08-30T12:36:00Z"));
            assertEquals("20150830T123600Z", signed.get("X-Amz-Date"));
            assertEquals("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, "
                            + "SignedHeaders=host;x-amz-date, "
                            + "Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31",
                    signed.get("Authorization"));
        }
    }

    @Nested
    @DisplayName("Adapters against a fake provider")
    class Adapters {

        private HttpServer server;
        private final List<String> requests = new CopyOnWriteArrayList<>();
        private final List<String> bodies = new CopyOnWriteArrayList<>();

        private URI start(Map<String, String> routes) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String key = exchange.getRequestMethod() + " " + exchange.getRequestURI();
                requests.add(key + " | auth=" + exchange.getRequestHeaders().getFirst("Authorization")
                        + " | vault=" + exchange.getRequestHeaders().getFirst("X-Vault-Token")
                        + " | target=" + exchange.getRequestHeaders().getFirst("X-Amz-Target")
                        + " | flavor=" + exchange.getRequestHeaders().getFirst("Metadata-Flavor"));
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String path = exchange.getRequestURI().getPath();
                String response = null;
                for (Map.Entry<String, String> route : routes.entrySet()) {
                    String[] parts = route.getKey().split(" ", 2);
                    if (parts[0].equals(exchange.getRequestMethod()) && path.equals(parts[1])) {
                        response = route.getValue();
                    }
                }
                byte[] out = (response == null ? "{\"error\":\"nope\"}" : response).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(response == null ? 404 : 200, out.length);
                exchange.getResponseBody().write(out);
                exchange.close();
            });
            server.start();
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        @AfterEach
        void stop() {
            if (server != null) {
                server.stop(0);
            }
        }

        @Test
        @DisplayName("SEC-SEC-023: Vault/OpenBao — Kubernetes login, KV v2 read under the tenant prefix, write, metadata delete")
        void vault(@TempDir Path dir) throws IOException {
            Path jwt = dir.resolve("token");
            Files.writeString(jwt, "eyJ-workload-jwt\n");
            String path = "tenants/" + TENANT_A + "/identity_provider/client_secret";
            URI base = start(Map.of(
                    "POST /v1/auth/kubernetes/login", "{\"auth\":{\"client_token\":\"s.abc\",\"lease_duration\":3600}}",
                    "GET /v1/aspm/data/" + path, "{\"data\":{\"data\":{\"value\":\"the-secret\"},\"metadata\":{\"version\":2}}}",
                    "POST /v1/aspm/data/" + path, "{\"data\":{\"version\":3}}",
                    "DELETE /v1/aspm/metadata/" + path, "{}"));
            VaultSecretsProvider vault = new VaultSecretsProvider(base, "aspm", Optional.empty(), Optional.empty(),
                    Optional.of("aspm-app"), "kubernetes", jwt);

            assertEquals("the-secret", new String(vault.resolve(TENANT_A, new SecretReference("vault", path)).orElseThrow()));
            assertTrue(requests.get(0).startsWith("POST /v1/auth/kubernetes/login"), "login happens first: " + requests);
            assertTrue(bodies.get(0).contains("\"role\":\"aspm-app\"") && bodies.get(0).contains("eyJ-workload-jwt"));
            assertTrue(requests.get(1).contains("vault=s.abc"), "the leased token is presented: " + requests.get(1));

            int before = requests.size();
            assertTrue(vault.resolve(TENANT_B, new SecretReference("vault", path)).isEmpty(),
                    "I13: tenant B presenting tenant A's reference gets nothing");
            assertEquals(before, requests.size(), "…and no request was made to find that out");

            SecretReference stored = vault.store(TENANT_A, "identity_provider", "client_secret", "new".toCharArray());
            assertEquals("vault:" + path, stored.toString());
            assertTrue(bodies.get(bodies.size() - 1).contains("\"value\":\"new\""));

            vault.destroy(TENANT_A, stored);
            assertTrue(requests.get(requests.size() - 1).startsWith("DELETE /v1/aspm/metadata/" + path));
            assertFalse(vault.description().contains("s.abc"), "the banner never carries a token");
        }

        @Test
        @DisplayName("SEC-SEC-023: Azure Key Vault — client credentials, versioned get, tenant name prefix")
        void azure() throws IOException {
            String name = AzureKeyVaultSecretsProvider.secretName(TENANT_A, "notification_channel", "smtp_password");
            URI base = start(Map.of(
                    "POST /tid/oauth2/v2.0/token", "{\"access_token\":\"eyJ-az\",\"expires_in\":3599}",
                    "GET /secrets/" + name + "/v1", "{\"value\":\"relay-pw\",\"id\":\"https://v.vault.azure.net/secrets/" + name + "/v1\"}",
                    "PUT /secrets/" + name, "{\"id\":\"https://v.vault.azure.net/secrets/" + name + "/v2\"}"));
            AzureKeyVaultSecretsProvider kv = new AzureKeyVaultSecretsProvider(base, base, "tid", "cid",
                    Optional.of("cs".toCharArray()), Optional.empty());

            assertEquals("relay-pw", new String(kv.resolve(TENANT_A, new SecretReference("azkv", name + "/v1")).orElseThrow()));
            assertTrue(bodies.get(0).contains("grant_type=client_credentials") && bodies.get(0).contains("client_secret=cs"));
            assertTrue(requests.get(1).contains("auth=Bearer eyJ-az"));
            assertTrue(kv.resolve(TENANT_B, new SecretReference("azkv", name + "/v1")).isEmpty(), "I13");

            SecretReference stored = kv.store(TENANT_A, "notification_channel", "smtp_password", "x".toCharArray());
            assertEquals("azkv:" + name + "/v2", stored.toString(), "the version pins the reference for rotation overlap");
        }

        @Test
        @DisplayName("SEC-SEC-023: AWS Secrets Manager — signed GetSecretValue, tenant name prefix, web identity exchange")
        void aws(@TempDir Path dir) throws IOException {
            Path jwt = dir.resolve("token");
            Files.writeString(jwt, "eyJ-irsa");
            String name = "aspm/" + TENANT_A + "/connector/jira_token";
            URI base = start(Map.of(
                    "POST /sts", "<AssumeRoleWithWebIdentityResponse><AssumeRoleWithWebIdentityResult><Credentials>"
                            + "<AccessKeyId>ASIAX</AccessKeyId><SecretAccessKey>sk</SecretAccessKey>"
                            + "<SessionToken>st</SessionToken><Expiration>2099-01-01T00:00:00Z</Expiration>"
                            + "</Credentials></AssumeRoleWithWebIdentityResult></AssumeRoleWithWebIdentityResponse>",
                    "POST /", "{\"SecretString\":\"jira-token\",\"Name\":\"" + name + "\"}"));
            AwsSecretsManagerProvider sm = new AwsSecretsManagerProvider("ap-southeast-1", base.resolve("/"),
                    base.resolve("/sts"), Optional.empty(), Optional.of(jwt), Optional.of("arn:aws:iam::1:role/aspm"));

            assertEquals("jira-token", new String(sm.resolve(TENANT_A, new SecretReference("awssm", name)).orElseThrow()));
            assertTrue(requests.get(0).startsWith("POST /sts"), "STS first: " + requests);
            assertTrue(bodies.get(0).contains("Action=AssumeRoleWithWebIdentity") && bodies.get(0).contains("eyJ-irsa"));
            String call = requests.get(1);
            assertTrue(call.contains("target=secretsmanager.GetSecretValue"), call);
            assertTrue(call.contains("auth=AWS4-HMAC-SHA256 Credential=ASIAX/"), call);
            assertTrue(call.contains("SignedHeaders=content-type;host;x-amz-date;x-amz-security-token;x-amz-target"), call);
            assertTrue(sm.resolve(TENANT_B, new SecretReference("awssm", name)).isEmpty(), "I13");
        }

        @Test
        @DisplayName("SEC-SEC-023: Google Secret Manager — metadata-server token, versions/N:access, tenant id prefix")
        void gcp() throws IOException {
            String id = GcpSecretManagerProvider.tenantPrefix(TENANT_A) + "connector-servicenow_password";
            String encoded = java.util.Base64.getEncoder().encodeToString("snow-pw".getBytes(StandardCharsets.UTF_8));
            URI base = start(Map.of(
                    "GET /token", "{\"access_token\":\"ya29\",\"expires_in\":3599,\"token_type\":\"Bearer\"}",
                    "GET /v1/projects/my-proj-123/secrets/" + id + "/versions/4:access",
                    "{\"name\":\"projects/1/secrets/" + id + "/versions/4\",\"payload\":{\"data\":\"" + encoded + "\"}}"));
            GcpSecretManagerProvider gsm = new GcpSecretManagerProvider("my-proj-123", base, base.resolve("/token"), Optional.empty());

            assertEquals("snow-pw", new String(gsm.resolve(TENANT_A, new SecretReference("gcpsm", id + "/4")).orElseThrow()));
            assertTrue(requests.get(0).contains("flavor=Google"), "the metadata server is asked with its required header");
            assertTrue(requests.get(1).contains("auth=Bearer ya29"));
            assertTrue(gsm.resolve(TENANT_B, new SecretReference("gcpsm", id + "/4")).isEmpty(), "I13");
        }
    }

    /** A provider that records what it was asked, for the router tests. */
    static final class Recording implements SecretsProvider {
        final String kind;
        final boolean tenantScoped;
        final boolean writable;
        final Map<String, String> values = new java.util.HashMap<>();
        final List<String> stored = new ArrayList<>();

        Recording(String kind, boolean tenantScoped, boolean writable) {
            this.kind = kind;
            this.tenantScoped = tenantScoped;
            this.writable = writable;
        }

        @Override public String kind() { return kind; }
        @Override public boolean tenantScoped() { return tenantScoped; }
        @Override public boolean writable() { return writable; }

        @Override
        public Optional<char[]> resolve(UUID tenantId, SecretReference reference) {
            return Optional.ofNullable(values.get(reference.toString())).map(String::toCharArray);
        }

        @Override
        public SecretReference store(UUID tenantId, String namespace, String name, char[] value) {
            stored.add(namespace + "/" + name);
            return new SecretReference(kind, tenantId + "/" + namespace + "/" + name);
        }

        @Override public void destroy(UUID tenantId, SecretReference reference) { }
        @Override public String description() { return kind; }
    }
}
