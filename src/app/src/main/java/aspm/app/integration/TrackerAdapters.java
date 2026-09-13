package aspm.app.integration;

import aspm.app.egress.EgressGuard;
import aspm.app.runtime.Json;
import aspm.module.integration.domain.FailureClass;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The work-tracker and service-management adapters DOC-21 §9 lists as "outbound reference": Jira Cloud,
 * Jira Data Center, GitLab, ServiceNow, and a signed generic webhook for everything else. Options, not
 * a vendor; each is one class implementing {@link ConnectorAdapter} and nothing outside this package
 * knows which one a tenant chose ({@code PRD-CON-015}).
 *
 * <p>Every adapter documents its minimum permission set on the target ({@code PRD-CON-016}) and what
 * each operation transmits ({@code PRD-CON-036}), next to the code that transmits it, so the two cannot
 * drift. All of them send the same {@link ConnectorAdapter.Reference}: a summary and a body that name
 * the finding, its severity, its place in the tree and the platform link — and say in the ticket that
 * the ticket is not the record ({@code PRD-CON-045}).
 *
 * <p>Deployment may disable kinds ({@code ASPM_CONNECTOR_KINDS}, a comma list; default all). A disabled
 * kind is still catalogued, marked disabled, with the consequence stated ({@code PRD-CON-054}): the same
 * platform, with a connector off ({@code PRD-CON-055}).
 */
public final class TrackerAdapters {

    public static final String KINDS_VARIABLE = "ASPM_CONNECTOR_KINDS";

    private TrackerAdapters() {
    }

    /** Every adapter the platform ships. */
    public static List<ConnectorAdapter> all(EgressGuard egress) {
        return List.of(new Jira(egress, true), new Jira(egress, false), new GitLab(egress), new ServiceNow(egress), new Webhook(egress));
    }

    /** The kinds this deployment allows, from the environment; every kind when unset. */
    public static Set<String> enabledKinds(Map<String, String> environment) {
        String raw = environment.getOrDefault(KINDS_VARIABLE, "").strip();
        if (raw.isEmpty()) {
            return all(EgressGuard.production()).stream().map(ConnectorAdapter::kind).collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                out.add(part.strip().toUpperCase(Locale.ROOT));
            }
        }
        return Set.copyOf(out);
    }

    // ==============================================================================================
    // Shared parsing
    // ==============================================================================================

    @SuppressWarnings("unchecked")
    static Object path(Map<String, Object> json, String... keys) {
        Object current = json;
        for (String key : keys) {
            if (!(current instanceof Map<?, ?> m)) {
                return null;
            }
            current = ((Map<String, Object>) m).get(key);
        }
        return current;
    }

    static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    static Map<String, Object> json(ConnectorHttp.Reply reply) {
        try {
            return Json.readObject(reply.body());
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    // ==============================================================================================
    // Jira — Cloud (API token + account email, Basic) or Data Center (personal access token, Bearer)
    // ==============================================================================================

    /** Jira, both hostings. The REST v2 issue resource is common to Cloud and Data Center. */
    public static final class Jira implements ConnectorAdapter {
        private final EgressGuard egress;
        private final boolean cloud;

        Jira(EgressGuard egress, boolean cloud) {
            this.egress = Objects.requireNonNull(egress);
            this.cloud = cloud;
        }

        @Override
        public String kind() {
            return cloud ? "JIRA_CLOUD" : "JIRA_DATA_CENTER";
        }

        @Override
        public int version() {
            return 1;
        }

        @Override
        public String label() {
            return cloud ? "Jira Cloud" : "Jira Data Center / Server";
        }

        @Override
        public List<String> minimumPermissions() {
            return List.of("Browse Projects on the target project", "Create Issues on the target project");
        }

        @Override
        public Map<String, String> outboundContent() {
            return Map.of(
                    "create", "summary ([ASPM severity] title), description (finding id, severity, scope path, source tool, platform link, disclaimer), project key, issue type, label 'aspm'",
                    "observe", "the issue key only; the response's status name and status category are read",
                    "probe", "nothing: GET /rest/api/2/myself with the credential");
        }

        @Override
        public String credentialLabel() {
            return cloud ? "API token (paired with the account email)" : "Personal access token";
        }

        @Override
        public Map<String, Object> validate(Map<String, Object> config, boolean credentialPresent) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("baseUrl", ConnectorHttp.baseUrl(egress, config.get("baseUrl"), "the Jira base URL"));
            String key = ConnectorHttp.requiredText(config, "projectKey", "the project key");
            if (!key.matches("[A-Z][A-Z0-9_]{1,19}")) {
                throw new IllegalArgumentException("the project key is upper-case letters, digits or underscores, as Jira shows it");
            }
            out.put("projectKey", key);
            out.put("issueType", config.get("issueType") == null || text(config.get("issueType")).isBlank() ? "Task"
                    : ConnectorHttp.requiredText(config, "issueType", "the issue type"));
            if (cloud) {
                String email = ConnectorHttp.requiredText(config, "email", "the account email the API token belongs to");
                if (!email.contains("@")) {
                    throw new IllegalArgumentException("the account email does not look like an email address");
                }
                out.put("email", email);
            }
            if (!credentialPresent) {
                throw new IllegalArgumentException("a " + credentialLabel().toLowerCase(Locale.ROOT) + " is required");
            }
            return out;
        }

        private Map<String, String> headers(Map<String, Object> config, char[] credential) {
            return Map.of("Authorization", cloud ? ConnectorHttp.basic(text(config.get("email")), credential)
                    : "Bearer " + new String(credential));
        }

        @Override
        public Result<Created> create(Map<String, Object> config, Optional<char[]> credential, Reference reference) {
            if (credential.isEmpty()) {
                return Result.failed(FailureClass.AUTHENTICATION, "no credential");
            }
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("project", Map.of("key", text(config.get("projectKey"))));
            fields.put("summary", reference.summary());
            fields.put("description", reference.body());
            fields.put("issuetype", Map.of("name", text(config.get("issueType"))));
            fields.put("labels", List.of("aspm"));
            Result<ConnectorHttp.Reply> reply = ConnectorHttp.send(egress, "POST", config.get("baseUrl") + "/rest/api/2/issue",
                    headers(config, credential.get()), Optional.of(Json.write(Map.of("fields", fields))));
            if (!reply.succeeded()) {
                // Jira answers 400 for an unknown project or issue type: that is configuration, not the record.
                FailureClass failure = reply.failure().orElseThrow();
                return Result.failed(failure == FailureClass.DATA ? FailureClass.CONFIGURATION : failure, reply.detail());
            }
            Map<String, Object> body = json(reply.value().get());
            String id = text(body.get("id"));
            String key = text(body.get("key"));
            if (id.isEmpty() || key.isEmpty()) {
                return Result.failed(FailureClass.PROTOCOL, "the issue response carried no id/key");
            }
            return Result.ok(new Created(key, key, Optional.of(config.get("baseUrl") + "/browse/" + key), "Open", false), reply.detail());
        }

        @Override
        public Result<Observation> observe(Map<String, Object> config, Optional<char[]> credential, String externalId) {
            if (credential.isEmpty()) {
                return Result.failed(FailureClass.AUTHENTICATION, "no credential");
            }
            Result<ConnectorHttp.Reply> reply = ConnectorHttp.send(egress, "GET", config.get("baseUrl") + "/rest/api/2/issue/"
                    + ConnectorHttp.pathSegment(externalId) + "?fields=status", headers(config, credential.get()), Optional.empty());
            if (!reply.succeeded()) {
                if (reply.detail().startsWith("HTTP 404")) {
                    return Result.ok(Observation.gone(), reply.detail());
                }
                return Result.failed(reply.failure().orElseThrow(), reply.detail());
            }
            Map<String, Object> body = json(reply.value().get());
            String name = text(path(body, "fields", "status", "name"));
            String category = text(path(body, "fields", "status", "statusCategory", "key"));
            if (name.isEmpty()) {
                return Result.failed(FailureClass.PROTOCOL, "the issue response carried no status");
            }
            return Result.ok(new Observation(Optional.of(name), "done".equalsIgnoreCase(category)), reply.detail());
        }

        @Override
        public Result<String> probe(Map<String, Object> config, Optional<char[]> credential) {
            if (credential.isEmpty()) {
                return Result.failed(FailureClass.AUTHENTICATION, "no credential");
            }
            Result<ConnectorHttp.Reply> reply = ConnectorHttp.send(egress, "GET", config.get("baseUrl") + "/rest/api/2/myself",
                    headers(config, credential.get()), Optional.empty());
            return reply.succeeded() ? Result.ok("the credential is accepted by " + config.get("baseUrl"), reply.detail())
                    : Result.failed(reply.failure().orElseThrow(), reply.detail());
        }
    }

    // ==============================================================================================
    // GitLab — issues API, project access token or personal access token
    // ==============================================================================================

    public static final class GitLab implements ConnectorAdapter {
        private final EgressGuard egress;

        GitLab(EgressGuard egress) {
            this.egress = Objects.requireNonNull(egress);
        }

        @Override
        public String kind() {
            return "GITLAB";
        }

        @Override
        public int version() {
            return 1;
        }

        @Override
        public String label() {
            return "GitLab issues";
        }

        @Override
        public List<String> minimumPermissions() {
            // ADR-024 / PRD-CON-040: an issues-only token. A token with read_repository would grant source
            // access the platform must never hold, and the form says so.
            return List.of("A project access token with scope 'api' limited to the one project, role Reporter",
                    "No 'read_repository' or 'write_repository' scope (ADR-024: the platform never holds source access)");
        }

        @Override
        public Map<String, String> outboundContent() {
            return Map.of(
                    "create", "title ([ASPM severity] title), description (finding id, severity, scope path, source tool, platform link, disclaimer), label 'aspm'",
                    "observe", "the issue iid only; the response's state is read",
                    "probe", "nothing: GET /api/v4/projects/{id} with the credential");
        }

        @Override
        public String credentialLabel() {
            return "Project or personal access token";
        }

        @Override
        public Map<String, Object> validate(Map<String, Object> config, boolean credentialPresent) {
            Map<String, Object> out = new LinkedHashMap<>();
            Object base = config.get("baseUrl") == null || text(config.get("baseUrl")).isBlank() ? "https://gitlab.com" : config.get("baseUrl");
            out.put("baseUrl", ConnectorHttp.baseUrl(egress, base, "the GitLab base URL"));
            String project = ConnectorHttp.requiredText(config, "projectId", "the numeric project id");
            if (!project.matches("[0-9]{1,12}")) {
                throw new IllegalArgumentException("the project id is the number GitLab shows under the project name, not its path");
            }
            out.put("projectId", project);
            if (!credentialPresent) {
                throw new IllegalArgumentException("an access token is required");
            }
            return out;
        }

        private String projectUrl(Map<String, Object> config) {
            return config.get("baseUrl") + "/api/v4/projects/" + config.get("projectId");
        }

        @Override
        public Result<Created> create(Map<String, Object> config, Optional<char[]> credential, Reference reference) {
            if (credential.isEmpty()) {
                return Result.failed(FailureClass.AUTHENTICATION, "no credential");
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("title", reference.summary());
            body.put("description", reference.body());
            body.put("labels", "aspm");
            Result<ConnectorHttp.Reply> reply = ConnectorHttp.send(egress, "POST", projectUrl(config) + "/issues",
                    Map.of("PRIVATE-TOKEN", new String(credential.get())), Optional.of(Json.write(body)));
            if (!reply.succeeded()) {
                return Result.failed(reply.failure().orElseThrow(), reply.detail());
            }
            Map<String, Object> issue = json(reply.value().get());
            String iid = text(issue.get("iid"));
            if (iid.isEmpty()) {
                return Result.failed(FailureClass.PROTOCOL, "the issue response carried no iid");
            }
            String url = text(issue.get("web_url"));
            return Result.ok(new Created(iid, "#" + iid, url.isEmpty() ? Optional.empty() : Optional.of(url),
                    text(issue.getOrDefault("state", "opened")), "closed".equals(text(issue.get("state")))), reply.detail());
        }

        @Override
        public Result<Observation> observe(Map<String, Object> config, Optional<char[]> credential, String externalId) {
            if (credential.isEmpty()) {
                return Result.failed(FailureClass.AUTHENTICATION, "no credential");
            }
            Result<ConnectorHttp.Reply> reply = ConnectorHttp.send(egress, "GET", projectUrl(config) + "/issues/" + ConnectorHttp.pathSegment(externalId),
                    Map.of("PRIVATE-TOKEN", new String(credential.get())), Optional.empty());
            if (!reply.succeeded()) {
                if (reply.detail().startsWith("HTTP 404")) {
                    return Result.ok(Observation.gone(), reply.detail());
                }
                return Result.failed(reply.failure().orElseThrow(), reply.detail());
            }
            String state = text(json(reply.value().get()).get("state"));
            if (state.isEmpty()) {
                return Result.failed(FailureClass.PROTOCOL, "the issue response carried no state");
            }
            return Result.ok(new Observation(Optional.of(state), "closed".equals(state)), reply.detail());
        }

        @Override
        public Result<String> probe(Map<String, Object> config, Optional<char[]> credential) {
            if (credential.isEmpty()) {
                return Result.failed(FailureClass.AUTHENTICATION, "no credential");
            }
            Result<ConnectorHttp.Reply> reply = ConnectorHttp.send(egress, "GET", projectUrl(config),
                    Map.of("PRIVATE-TOKEN", new String(credential.get())), Optional.empty());
            return reply.succeeded() ? Result.ok("the credential reaches project " + config.get("projectId"), reply.detail())
                    : Result.failed(reply.failure().orElseThrow(), reply.detail());
        }
    }

    // ==============================================================================================
    // ServiceNow — Table API, Basic authentication with a dedicated integration user
    // ==============================================================================================

    public static final class ServiceNow implements ConnectorAdapter {
        private final EgressGuard egress;

        ServiceNow(EgressGuard egress) {
            this.egress = Objects.requireNonNull(egress);
        }

        @Override
        public String kind() {
            return "SERVICENOW";
        }

        @Override
        public int version() {
            return 1;
        }

        @Override
        public String label() {
            return "ServiceNow (Table API)";
        }

        @Override
        public List<String> minimumPermissions() {
            return List.of("A dedicated integration user with the 'rest_api_explorer' or 'web_service_admin' role removed",
                    "Create and read ACLs on the one target table (default 'incident'); no write beyond it");
        }

        @Override
        public Map<String, String> outboundContent() {
            return Map.of(
                    "create", "short_description ([ASPM severity] title), description (finding id, severity, scope path, source tool, platform link, disclaimer), correlation_id (finding id)",
                    "observe", "the record sys_id only; the response's state and number are read",
                    "probe", "nothing: GET one record of the table with sysparm_limit=1");
        }

        @Override
        public String credentialLabel() {
            return "Password of the integration user";
        }

        @Override
        public Map<String, Object> validate(Map<String, Object> config, boolean credentialPresent) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("baseUrl", ConnectorHttp.baseUrl(egress, config.get("baseUrl"), "the ServiceNow instance URL"));
            String table = config.get("table") == null || text(config.get("table")).isBlank() ? "incident"
                    : ConnectorHttp.requiredText(config, "table", "the table");
            if (!table.matches("[a-z][a-z0-9_]{1,63}")) {
                throw new IllegalArgumentException("the table name is lower-case letters, digits and underscores");
            }
            out.put("table", table);
            out.put("username", ConnectorHttp.requiredText(config, "username", "the integration user name"));
            String resolved = config.get("resolvedStates") == null || text(config.get("resolvedStates")).isBlank() ? "6,7"
                    : text(config.get("resolvedStates")).strip();
            if (!resolved.matches("[A-Za-z0-9_ -]+(,[A-Za-z0-9_ -]+)*")) {
                throw new IllegalArgumentException("resolved states are a comma-separated list of state values, for example 6,7");
            }
            out.put("resolvedStates", resolved);
            if (!credentialPresent) {
                throw new IllegalArgumentException("the integration user's password is required");
            }
            return out;
        }

        private String tableUrl(Map<String, Object> config) {
            return config.get("baseUrl") + "/api/now/table/" + config.get("table");
        }

        private boolean resolved(Map<String, Object> config, String state) {
            for (String s : text(config.get("resolvedStates")).split(",")) {
                if (s.strip().equalsIgnoreCase(state.strip())) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public Result<Created> create(Map<String, Object> config, Optional<char[]> credential, Reference reference) {
            if (credential.isEmpty()) {
                return Result.failed(FailureClass.AUTHENTICATION, "no credential");
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("short_description", reference.summary());
            body.put("description", reference.body());
            body.put("correlation_id", reference.findingId().toString());
            Result<ConnectorHttp.Reply> reply = ConnectorHttp.send(egress, "POST", tableUrl(config),
                    Map.of("Authorization", ConnectorHttp.basic(text(config.get("username")), credential.get())), Optional.of(Json.write(body)));
            if (!reply.succeeded()) {
                return Result.failed(reply.failure().orElseThrow(), reply.detail());
            }
            Map<String, Object> result = json(reply.value().get());
            String sysId = text(path(result, "result", "sys_id"));
            if (sysId.isEmpty()) {
                return Result.failed(FailureClass.PROTOCOL, "the table response carried no sys_id");
            }
            String number = text(path(result, "result", "number"));
            String state = text(path(result, "result", "state"));
            return Result.ok(new Created(sysId, number.isEmpty() ? sysId : number,
                    Optional.of(config.get("baseUrl") + "/nav_to.do?uri=" + config.get("table") + ".do?sys_id=" + sysId),
                    state.isEmpty() ? "new" : state, resolved(config, state)), reply.detail());
        }

        @Override
        public Result<Observation> observe(Map<String, Object> config, Optional<char[]> credential, String externalId) {
            if (credential.isEmpty()) {
                return Result.failed(FailureClass.AUTHENTICATION, "no credential");
            }
            Result<ConnectorHttp.Reply> reply = ConnectorHttp.send(egress, "GET", tableUrl(config) + "/" + ConnectorHttp.pathSegment(externalId)
                    + "?sysparm_fields=state,number", Map.of("Authorization", ConnectorHttp.basic(text(config.get("username")), credential.get())),
                    Optional.empty());
            if (!reply.succeeded()) {
                if (reply.detail().startsWith("HTTP 404")) {
                    return Result.ok(Observation.gone(), reply.detail());
                }
                return Result.failed(reply.failure().orElseThrow(), reply.detail());
            }
            String state = text(path(json(reply.value().get()), "result", "state"));
            if (state.isEmpty()) {
                return Result.failed(FailureClass.PROTOCOL, "the record response carried no state");
            }
            return Result.ok(new Observation(Optional.of(state), resolved(config, state)), reply.detail());
        }

        @Override
        public Result<String> probe(Map<String, Object> config, Optional<char[]> credential) {
            if (credential.isEmpty()) {
                return Result.failed(FailureClass.AUTHENTICATION, "no credential");
            }
            Result<ConnectorHttp.Reply> reply = ConnectorHttp.send(egress, "GET", tableUrl(config) + "?sysparm_limit=1&sysparm_fields=sys_id",
                    Map.of("Authorization", ConnectorHttp.basic(text(config.get("username")), credential.get())), Optional.empty());
            return reply.succeeded() ? Result.ok("the credential reads table " + config.get("table"), reply.detail())
                    : Result.failed(reply.failure().orElseThrow(), reply.detail());
        }
    }

    // ==============================================================================================
    // Generic signed webhook — for the tracker the platform does not know
    // ==============================================================================================

    /**
     * A tenant's own receiver. Every call is a POST to the ONE configured URL with an {@code operation}
     * field and an HMAC-SHA256 signature over the body; the receiver answers with JSON. Observation is a
     * POST too, so the destination is never assembled from an external identifier ({@code PRD-CON-032}).
     */
    public static final class Webhook implements ConnectorAdapter {
        private final EgressGuard egress;

        Webhook(EgressGuard egress) {
            this.egress = Objects.requireNonNull(egress);
        }

        @Override
        public String kind() {
            return "GENERIC_WEBHOOK";
        }

        @Override
        public int version() {
            return 1;
        }

        @Override
        public String label() {
            return "Generic signed webhook";
        }

        @Override
        public List<String> minimumPermissions() {
            return List.of("A receiver that verifies X-ASPM-Signature (HMAC-SHA256 of the body with the shared key) and creates the item itself");
        }

        @Override
        public Map<String, String> outboundContent() {
            return Map.of(
                    "create", "operation=create; findingId, summary, body (finding id, severity, scope path, source tool, platform link, disclaimer), severity, scopePath, sourceTool, link",
                    "observe", "operation=observe; externalId. The receiver answers {state, resolved} or 404 when the item is gone",
                    "probe", "operation=probe; nothing else");
        }

        @Override
        public String credentialLabel() {
            return "Shared signing key";
        }

        @Override
        public Map<String, Object> validate(Map<String, Object> config, boolean credentialPresent) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("url", ConnectorHttp.baseUrl(egress, config.get("url"), "the receiver URL"));
            if (!credentialPresent) {
                throw new IllegalArgumentException("a shared signing key is required: an unsigned webhook lets anybody who finds the URL impersonate the platform");
            }
            return out;
        }

        private Result<ConnectorHttp.Reply> post(Map<String, Object> config, char[] key, Map<String, Object> payload) {
            String body = Json.write(payload);
            String signature;
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(new String(key).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                signature = "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
            } catch (java.security.GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
            return ConnectorHttp.send(egress, "POST", text(config.get("url")),
                    Map.of("X-ASPM-Signature", signature, "X-ASPM-Operation", text(payload.get("operation"))), Optional.of(body));
        }

        @Override
        public Result<Created> create(Map<String, Object> config, Optional<char[]> credential, Reference reference) {
            if (credential.isEmpty()) {
                return Result.failed(FailureClass.AUTHENTICATION, "no signing key");
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("operation", "create");
            payload.put("findingId", reference.findingId().toString());
            payload.put("summary", reference.summary());
            payload.put("body", reference.body());
            payload.put("severity", reference.severity());
            payload.put("scopePath", reference.scopePath());
            payload.put("sourceTool", reference.sourceTool());
            payload.put("link", reference.link());
            Result<ConnectorHttp.Reply> reply = post(config, credential.get(), payload);
            if (!reply.succeeded()) {
                return Result.failed(reply.failure().orElseThrow(), reply.detail());
            }
            Map<String, Object> answer = json(reply.value().get());
            String id = text(answer.get("externalId"));
            if (id.isEmpty()) {
                return Result.failed(FailureClass.PROTOCOL, "the receiver answered without an externalId");
            }
            String url = text(answer.get("url"));
            String key = text(answer.get("key"));
            return Result.ok(new Created(id, key.isEmpty() ? id : key, url.isEmpty() || !egress.permitted(url) ? Optional.empty() : Optional.of(url),
                    text(answer.getOrDefault("state", "open")), Boolean.TRUE.equals(answer.get("resolved"))), reply.detail());
        }

        @Override
        public Result<Observation> observe(Map<String, Object> config, Optional<char[]> credential, String externalId) {
            if (credential.isEmpty()) {
                return Result.failed(FailureClass.AUTHENTICATION, "no signing key");
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("operation", "observe");
            payload.put("externalId", externalId);
            Result<ConnectorHttp.Reply> reply = post(config, credential.get(), payload);
            if (!reply.succeeded()) {
                if (reply.detail().startsWith("HTTP 404")) {
                    return Result.ok(Observation.gone(), reply.detail());
                }
                return Result.failed(reply.failure().orElseThrow(), reply.detail());
            }
            Map<String, Object> answer = json(reply.value().get());
            String state = text(answer.get("state"));
            if (state.isEmpty()) {
                return Result.failed(FailureClass.PROTOCOL, "the receiver answered without a state");
            }
            return Result.ok(new Observation(Optional.of(state), Boolean.TRUE.equals(answer.get("resolved"))), reply.detail());
        }

        @Override
        public Result<String> probe(Map<String, Object> config, Optional<char[]> credential) {
            if (credential.isEmpty()) {
                return Result.failed(FailureClass.AUTHENTICATION, "no signing key");
            }
            Result<ConnectorHttp.Reply> reply = post(config, credential.get(), Map.of("operation", "probe"));
            return reply.succeeded() ? Result.ok("the receiver accepted a signed probe", reply.detail())
                    : Result.failed(reply.failure().orElseThrow(), reply.detail());
        }
    }
}
