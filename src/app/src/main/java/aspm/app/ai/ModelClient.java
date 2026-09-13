package aspm.app.ai;

import aspm.app.runtime.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The wire adapters behind the provider contract of DOC-10 §3.1: one request shape per provider
 * FAMILY, and the family is a property of the provider row, not of a capability. {@code PRD-AIC-023},
 * {@code PRD-AIC-026}.
 *
 * <p>Two families cover what customers run: the OpenAI chat-completions shape (OpenAI, Azure OpenAI,
 * vLLM, Ollama, LiteLLM, llama.cpp, TGI and every self-hosted gateway that imitates it) and
 * Anthropic's messages API. A kind the platform does not know is treated as OpenAI-compatible,
 * because that is what an unknown inference server almost always is, and the first call tells.
 *
 * <p>Neither client follows redirects, both bound the reply, both surface the provider's token usage
 * so the invocation record carries it ({@code PRD-AIC-043}) and the budget can be enforced
 * ({@code PRD-AIC-053}). The destination is checked against {@link ModelEndpoints} immediately before
 * every call — at use, not only at save.
 */
public interface ModelClient {

    /** What a capability asks for. {@code jsonObject} asks the provider for a JSON object where it can. */
    record Request(String system, String user, int maxTokens, double temperature, boolean jsonObject) {
    }

    /** What came back: the text, and what the provider says it cost. */
    record Completion(String text, int promptTokens, int completionTokens, String modelReported) {
    }

    /** A call that did not produce a completion, classified for the caller and the invocation record. */
    final class ModelException extends Exception {
        private final String code;
        private final int retryAfterSeconds;

        public ModelException(String code, String detail) {
            this(code, detail, 0);
        }

        /** @param retryAfterSeconds what the provider asked for on a 429, 0 when it said nothing */
        public ModelException(String code, String detail, int retryAfterSeconds) {
            super(detail);
            this.code = code;
            this.retryAfterSeconds = Math.max(0, retryAfterSeconds);
        }

        public String code() {
            return code;
        }

        public int retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    /** The provider family this client speaks. */
    String family();

    Completion complete(String baseUrl, String model, String apiKey, Request request) throws ModelException;

    // ==============================================================================================

    /** Product-fixed kinds a tenant chooses from, each with its family and default base URL. */
    record Kind(String code, String label, String family, String defaultBaseUrl, String hint) {
    }

    List<Kind> KINDS = List.of(
            new Kind("OPENAI", "OpenAI", "openai", "https://api.openai.com/v1", "api.openai.com; the key is an OpenAI API key"),
            new Kind("AZURE_OPENAI", "Azure OpenAI", "openai",
                    "", "https://<resource>.openai.azure.com/openai/deployments/<deployment>?api-version=2024-10-21; the model is the deployment name"),
            new Kind("ANTHROPIC", "Anthropic", "anthropic", "https://api.anthropic.com", "api.anthropic.com; the key is an Anthropic API key"),
            new Kind("OPENAI_COMPATIBLE", "OpenAI-compatible (self-hosted: vLLM, Ollama, LiteLLM, llama.cpp, TGI, a gateway)", "openai",
                    "", "the server's /v1 base URL; a private address must be vouched for by the deployment in " + ModelEndpoints.VARIABLE));

    static ModelClient forKind(String providerKind) {
        String kind = providerKind == null ? "" : providerKind.strip().toUpperCase(Locale.ROOT);
        for (Kind k : KINDS) {
            if (k.code().equals(kind)) {
                return "anthropic".equals(k.family()) ? new Anthropic() : new OpenAiCompatible(kind);
            }
        }
        return new OpenAiCompatible("OPENAI_COMPATIBLE");
    }

    // ==============================================================================================

    /**
     * What the provider last said about its per-minute request quota for a host, read from the
     * {@code x-ratelimit-*} headers gateways such as LiteLLM and OpenAI send on every answer.
     *
     * <p>Kept so a batch can stop BEFORE the quota runs out rather than after: the first live gateway
     * allowed 120 requests a minute, and a classification batch that only learned of the limit from
     * the 429 collected 134 of them in one minute. A few requests are held in reserve for the person
     * at the keyboard — a typed question should not lose to a background batch.
     */
    record Quota(int limit, int remaining, java.time.Instant resetAt) {
    }

    /** Why a call is being held back: nothing was sent, and this is what the caller shows. */
    record Hold(int remaining, int limit, int secondsLeft) {
        public String detail() {
            return "the provider's request quota for this minute is nearly used (" + remaining + " of " + limit
                    + " left, kept for interactive use); it resets in " + secondsLeft + " s";
        }
    }

    /** Requests left unspent when the quota is nearly out, so a question typed by a person still goes through. */
    int QUOTA_RESERVE = 3;

    java.util.concurrent.ConcurrentMap<String, Quota> QUOTAS = new java.util.concurrent.ConcurrentHashMap<>();

    static String quotaKey(String url) {
        try {
            URI u = URI.create(url);
            return u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
        } catch (IllegalArgumentException e) {
            return url;
        }
    }

    /** Records the provider's quota headers, when it sent any. */
    static void noteQuota(String url, java.net.http.HttpHeaders headers) {
        Optional<String> limit = headers.firstValue("x-ratelimit-limit-requests");
        Optional<String> remaining = headers.firstValue("x-ratelimit-remaining-requests");
        if (limit.isEmpty() || remaining.isEmpty()) {
            return;
        }
        int reset = headers.firstValue("x-ratelimit-reset-requests")
                .or(() -> headers.firstValue("llm_provider-x-ratelimit-reset-requests"))
                .map(ModelClient::leadingSeconds).orElse(Integer.valueOf(60)).intValue();
        try {
            QUOTAS.put(quotaKey(url), new Quota(Integer.parseInt(limit.get().trim()), Integer.parseInt(remaining.get().trim()),
                    java.time.Instant.now().plusSeconds(Math.max(1, reset))));
        } catch (NumberFormatException notNumbers) {
            // A header this code does not understand is not a reason to stop calling the provider.
        }
    }

    /** "58", "58s", "1m2s" and "1" all become whole seconds; anything else is 60. */
    static int leadingSeconds(String value) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        try {
            if (v.matches("[0-9]+")) {
                return Integer.parseInt(v);
            }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:([0-9]+)m)?(?:([0-9]+)s)?").matcher(v);
            if (m.matches() && (m.group(1) != null || m.group(2) != null)) {
                return (m.group(1) == null ? 0 : Integer.parseInt(m.group(1)) * 60) + (m.group(2) == null ? 0 : Integer.parseInt(m.group(2)));
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return 60;
    }

    /** Whether a call to this provider should be held back until the quota window resets. */
    static Optional<Hold> hold(String baseUrl) {
        Quota q = QUOTAS.get(quotaKey(baseUrl));
        if (q == null) {
            return Optional.empty();
        }
        long left = java.time.Duration.between(java.time.Instant.now(), q.resetAt()).getSeconds();
        if (left <= 0) {
            QUOTAS.remove(quotaKey(baseUrl));
            return Optional.empty();
        }
        if (q.remaining() > QUOTA_RESERVE) {
            return Optional.empty();
        }
        return Optional.of(new Hold(q.remaining(), q.limit(), (int) left));
    }

    /** Drops what is known about every provider's quota; for tests, and for an operator resetting a gateway. */
    static void forgetQuotas() {
        QUOTAS.clear();
    }

    HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    Duration TIMEOUT = Duration.ofSeconds(60);
    int MAX_REPLY_BYTES = 200_000;

    static String send(String url, Map<String, String> headers, String body) throws ModelException {
        if (!ModelEndpoints.permitted(url)) {
            throw new ModelException("ENDPOINT_REFUSED", ModelEndpoints.refusal(url));
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(request::header);
        try {
            HttpResponse<String> response = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            String reply = response.body() == null ? "" : response.body();
            noteQuota(url, response.headers());
            if (reply.length() > MAX_REPLY_BYTES) {
                throw new ModelException("PROVIDER_MALFORMED", "the provider's answer exceeded " + MAX_REPLY_BYTES + " bytes");
            }
            if (status == 401 || status == 403) {
                throw new ModelException("PROVIDER_AUTH", "the provider refused the credential (HTTP " + status + ")");
            }
            if (status == 429) {
                // The provider says how long; the caller shows that rather than "try later". A gateway
                // with a per-minute quota answers 429 with Retry-After: 60, and a button that hid that
                // number made the platform look broken when the quota was the whole story.
                int retryAfter = response.headers().firstValue("retry-after").map(v -> {
                    try {
                        return Integer.valueOf(v.trim());
                    } catch (NumberFormatException notSeconds) {
                        return Integer.valueOf(0);
                    }
                }).orElse(Integer.valueOf(0)).intValue();
                throw new ModelException("PROVIDER_RATE_LIMITED", "the provider is rate limiting (HTTP 429"
                        + (retryAfter > 0 ? ", retry after " + retryAfter + " s)" : ")") + said(reply), retryAfter);
            }
            if (status >= 300) {
                throw new ModelException("PROVIDER_REFUSED", "the provider answered HTTP " + status + said(reply));
            }
            return reply;
        } catch (IOException e) {
            throw new ModelException("PROVIDER_UNREACHABLE", "the provider could not be reached: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelException("INTERRUPTED", "the call was interrupted");
        }
    }

    /** The provider's own words on a refusal, shortened, so the record says why and not only that. */
    static String said(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String text = body.replaceAll("\\s+", " ").trim();
        return "; provider said: " + (text.length() > 240 ? text.substring(0, 240) + "…" : text);
    }

    static int intOf(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    // ==============================================================================================

    /** OpenAI chat completions, and everything that imitates it. Azure differs in header and URL only. */
    final class OpenAiCompatible implements ModelClient {
        private final String kind;

        OpenAiCompatible(String kind) {
            this.kind = Objects.requireNonNull(kind);
        }

        @Override
        public String family() {
            return "openai";
        }

        static String endpoint(String baseUrl, boolean azure) {
            String base = baseUrl == null ? "" : baseUrl.strip();
            if (azure) {
                // https://res.openai.azure.com/openai/deployments/<dep>?api-version=... → insert /chat/completions before the query.
                int q = base.indexOf('?');
                String path = q < 0 ? base : base.substring(0, q);
                String query = q < 0 ? "?api-version=2024-10-21" : base.substring(q);
                while (path.endsWith("/")) {
                    path = path.substring(0, path.length() - 1);
                }
                return (path.toLowerCase(Locale.ROOT).endsWith("/chat/completions") ? path : path + "/chat/completions") + query;
            }
            while (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            return base.toLowerCase(Locale.ROOT).endsWith("/chat/completions") ? base : base + "/chat/completions";
        }

        @Override
        public Completion complete(String baseUrl, String model, String apiKey, Request request) throws ModelException {
            boolean azure = "AZURE_OPENAI".equals(kind);
            String base = baseUrl == null || baseUrl.isBlank() ? "https://api.openai.com/v1" : baseUrl;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            List<Map<String, String>> messages = new ArrayList<>();
            if (request.system() != null && !request.system().isBlank()) {
                messages.add(Map.of("role", "system", "content", request.system()));
            }
            messages.add(Map.of("role", "user", "content", request.user()));
            body.put("messages", messages);
            body.put("temperature", request.temperature());
            body.put("max_tokens", request.maxTokens());
            body.put("stream", false);
            if (request.jsonObject()) {
                body.put("response_format", Map.of("type", "json_object"));
            }
            Map<String, String> headers = azure ? Map.of("api-key", apiKey) : Map.of("Authorization", "Bearer " + apiKey);
            String reply = send(endpoint(base, azure), headers, Json.write(body));
            Map<String, Object> root;
            try {
                root = Json.readObject(reply);
            } catch (RuntimeException e) {
                // Some servers refuse response_format; retried once without it, because the answer is the same text.
                throw new ModelException("PROVIDER_MALFORMED", "the provider's answer was not JSON");
            }
            Object choices = root.get("choices");
            if (!(choices instanceof List<?> list) || list.isEmpty() || !(list.get(0) instanceof Map<?, ?> first)) {
                throw new ModelException("PROVIDER_MALFORMED", "the provider's answer carried no choices");
            }
            String content = null;
            if (first.get("message") instanceof Map<?, ?> m && m.get("content") instanceof String s) {
                content = s;
            } else if (first.get("text") instanceof String s) {
                content = s;
            }
            if (content == null || content.isBlank()) {
                boolean reasoned = first.get("message") instanceof Map<?, ?> m2 && m2.get("reasoning_content") instanceof String rc && !rc.isBlank();
                throw new ModelException("EMPTY_REPLY", reasoned
                        ? "the model spent its whole token allowance reasoning and produced no answer; raise max_tokens or use a non-reasoning model"
                        : "the provider returned nothing usable");
            }
            Map<?, ?> usage = root.get("usage") instanceof Map<?, ?> u ? u : Map.of();
            return new Completion(content, intOf(usage.get("prompt_tokens")), intOf(usage.get("completion_tokens")), text(root.get("model")));
        }
    }

    /** Anthropic messages API. */
    final class Anthropic implements ModelClient {
        @Override
        public String family() {
            return "anthropic";
        }

        @Override
        public Completion complete(String baseUrl, String model, String apiKey, Request request) throws ModelException {
            String base = baseUrl == null || baseUrl.isBlank() ? "https://api.anthropic.com" : baseUrl.strip();
            while (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            String url = base.toLowerCase(Locale.ROOT).endsWith("/v1/messages") ? base : base + "/v1/messages";
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("max_tokens", request.maxTokens());
            body.put("temperature", request.temperature());
            if (request.system() != null && !request.system().isBlank()) {
                body.put("system", request.system() + (request.jsonObject() ? "\nAnswer with a single JSON object and nothing else." : ""));
            }
            body.put("messages", List.of(Map.of("role", "user", "content", request.user())));
            String reply = send(url, Map.of("x-api-key", apiKey, "anthropic-version", "2023-06-01"), Json.write(body));
            Map<String, Object> root;
            try {
                root = Json.readObject(reply);
            } catch (RuntimeException e) {
                throw new ModelException("PROVIDER_MALFORMED", "the provider's answer was not JSON");
            }
            StringBuilder content = new StringBuilder();
            if (root.get("content") instanceof List<?> blocks) {
                for (Object block : blocks) {
                    if (block instanceof Map<?, ?> b && "text".equals(b.get("type")) && b.get("text") instanceof String s) {
                        content.append(s);
                    }
                }
            }
            if (content.isEmpty()) {
                throw new ModelException("EMPTY_REPLY", "the provider returned nothing usable");
            }
            Map<?, ?> usage = root.get("usage") instanceof Map<?, ?> u ? u : Map.of();
            return new Completion(content.toString(), intOf(usage.get("input_tokens")), intOf(usage.get("output_tokens")), text(root.get("model")));
        }
    }

    /** Whether this kind is one the platform documents; anything else is an OpenAI-compatible guess. */
    static Optional<Kind> kind(String code) {
        String wanted = code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
        return KINDS.stream().filter(k -> k.code().equals(wanted)).findFirst();
    }
}
