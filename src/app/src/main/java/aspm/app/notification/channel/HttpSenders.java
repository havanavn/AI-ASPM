package aspm.app.notification.channel;

import aspm.app.egress.EgressGuard;
import aspm.app.runtime.Json;
import aspm.module.integration.domain.FailureClass;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The chat and webhook senders: Slack (incoming webhook and bot API), Microsoft Teams, and a signed
 * generic webhook. {@code PRD-NTF-003}, {@code PRD-NTF-032}, {@code PRD-CON-032}, {@code PRD-CON-034},
 * {@code PRD-API-053}.
 *
 * <p>One HTTP client for all four, configured as every outbound client in this tier is: no redirects,
 * bounded timeouts. Every destination is checked against the {@link EgressGuard} immediately before
 * the request, whether it came from configuration or from the secrets store — a Slack webhook URL is a
 * secret AND a destination, and being a secret does not excuse it from being a public https address.
 *
 * <p>Outcomes are classified for the worker ({@code PRD-CON-025}): 401/403 is the credential
 * (no retry, mark unhealthy, tell the owner); 429 is the target's rate limit (back off, honouring
 * {@code Retry-After}); 5xx and connection failures are transient; any other 4xx is the message
 * (dead, do not retry). Detail is a status code or a class name, never a response body — a chat
 * platform's error text can quote the request.
 */
public final class HttpSenders {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private HttpSenders() {
    }

    /** Every sender this class provides. */
    public static List<ChannelSender> all(EgressGuard egress) {
        return List.of(new SlackWebhook(egress), new SlackApi(egress), new TeamsWebhook(egress), new GenericWebhook(egress));
    }

    // ==============================================================================================

    /** Slack incoming webhook. The URL is the credential; it lives in the secrets store. */
    public static final class SlackWebhook implements ChannelSender {
        public static final String KIND = "SLACK_WEBHOOK";
        private final EgressGuard egress;

        SlackWebhook(EgressGuard egress) {
            this.egress = egress;
        }

        @Override
        public String kind() {
            return KIND;
        }

        @Override
        public boolean requiresSecret() {
            return true;
        }

        @Override
        public Map<String, Object> validate(Map<String, Object> config, boolean secretPresent) {
            if (!secretPresent) {
                throw new IllegalArgumentException("a Slack incoming webhook URL is required, entered as the channel secret");
            }
            return Map.of();
        }

        @Override
        public Outcome send(Map<String, Object> config, Optional<char[]> secret, Optional<String> address, Message message) {
            String url = secret.map(String::new).orElse("");
            if (!url.startsWith("https://hooks.slack.com/")) {
                return Outcome.failed(FailureClass.DATA, "the secret is not a Slack incoming webhook URL");
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("text", plainText(message));
            List<Map<String, Object>> blocks = new ArrayList<>();
            blocks.add(Map.of("type", "section", "text", Map.of("type", "mrkdwn", "text", "*" + message.title() + "*")));
            message.body().ifPresent(b -> blocks.add(Map.of("type", "section", "text", Map.of("type", "mrkdwn", "text", b))));
            message.link().ifPresent(l -> blocks.add(Map.of("type", "context", "elements", List.of(Map.of("type", "mrkdwn", "text", l)))));
            payload.put("blocks", blocks);
            return post(egress, url, Map.of(), Json.write(payload));
        }
    }

    /** Slack Web API {@code chat.postMessage} with a bot token; the channel id is the address. */
    public static final class SlackApi implements ChannelSender {
        public static final String KIND = "SLACK_API";
        static final String ENDPOINT = "https://slack.com/api/chat.postMessage";
        private final EgressGuard egress;

        SlackApi(EgressGuard egress) {
            this.egress = egress;
        }

        @Override
        public String kind() {
            return KIND;
        }

        @Override
        public boolean requiresSecret() {
            return true;
        }

        @Override
        public Map<String, Object> validate(Map<String, Object> config, boolean secretPresent) {
            if (!secretPresent) {
                throw new IllegalArgumentException("a Slack bot token (xoxb-…) is required, entered as the channel secret");
            }
            String channel = text(config, "default_channel").orElseThrow(() -> new IllegalArgumentException("a default Slack channel id is required (for example C0123456789)"));
            if (!channel.matches("[A-Z][A-Z0-9]{6,20}")) {
                throw new IllegalArgumentException("a Slack channel is addressed by its id (C…/G…), not its name");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("default_channel", channel);
            return out;
        }

        @Override
        public Outcome send(Map<String, Object> config, Optional<char[]> secret, Optional<String> address, Message message) {
            String token = secret.map(String::new).orElse("");
            if (!token.startsWith("xoxb-") && !token.startsWith("xoxp-")) {
                return Outcome.failed(FailureClass.DATA, "the secret is not a Slack token");
            }
            String channel = address.filter(a -> a.matches("[A-Z][A-Z0-9]{6,20}")).orElse(String.valueOf(config.get("default_channel")));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("channel", channel);
            payload.put("text", plainText(message));
            payload.put("unfurl_links", false);
            Outcome outcome = post(egress, ENDPOINT, Map.of("Authorization", "Bearer " + token), Json.write(payload));
            // Slack answers 200 with {"ok": false, "error": "…"} for most failures.
            if (outcome.delivered() && outcome.detail().startsWith("HTTP 200 body=")) {
                String body = outcome.detail().substring("HTTP 200 body=".length());
                try {
                    Map<String, Object> reply = Json.readObject(body);
                    if (!Boolean.TRUE.equals(reply.get("ok"))) {
                        String error = String.valueOf(reply.get("error"));
                        FailureClass cls = switch (error) {
                            case "invalid_auth", "not_authed", "token_revoked", "token_expired", "account_inactive" -> FailureClass.AUTHENTICATION;
                            case "not_in_channel", "channel_not_found", "is_archived", "restricted_action" -> FailureClass.AUTHORIZATION;
                            case "ratelimited", "rate_limited" -> FailureClass.RATE_LIMITED;
                            default -> FailureClass.DATA;
                        };
                        return Outcome.failed(cls, "slack: " + error);
                    }
                } catch (IllegalArgumentException e) {
                    return Outcome.failed(FailureClass.PROTOCOL, "slack answered with something that is not JSON");
                }
                return Outcome.sent("HTTP 200 ok");
            }
            return outcome;
        }
    }

    /** Microsoft Teams incoming webhook (Workflows or the classic connector): an Adaptive Card. */
    public static final class TeamsWebhook implements ChannelSender {
        public static final String KIND = "TEAMS_WEBHOOK";
        private final EgressGuard egress;

        TeamsWebhook(EgressGuard egress) {
            this.egress = egress;
        }

        @Override
        public String kind() {
            return KIND;
        }

        @Override
        public boolean requiresSecret() {
            return true;
        }

        @Override
        public Map<String, Object> validate(Map<String, Object> config, boolean secretPresent) {
            if (!secretPresent) {
                throw new IllegalArgumentException("a Teams incoming webhook URL is required, entered as the channel secret");
            }
            return Map.of();
        }

        @Override
        public Outcome send(Map<String, Object> config, Optional<char[]> secret, Optional<String> address, Message message) {
            String url = secret.map(String::new).orElse("");
            if (!url.startsWith("https://")) {
                return Outcome.failed(FailureClass.DATA, "the secret is not a Teams webhook URL");
            }
            List<Map<String, Object>> body = new ArrayList<>();
            body.add(Map.of("type", "TextBlock", "text", message.title(), "weight", "Bolder", "wrap", true));
            message.body().ifPresent(b -> body.add(Map.of("type", "TextBlock", "text", b, "wrap", true)));
            List<Map<String, Object>> actions = new ArrayList<>();
            message.link().ifPresent(l -> actions.add(Map.of("type", "Action.OpenUrl", "title", "Open", "url", l)));
            Map<String, Object> card = new LinkedHashMap<>();
            card.put("$schema", "http://adaptivecards.io/schemas/adaptive-card.json");
            card.put("type", "AdaptiveCard");
            card.put("version", "1.4");
            card.put("body", body);
            if (!actions.isEmpty()) {
                card.put("actions", actions);
            }
            Map<String, Object> payload = Map.of("type", "message", "attachments", List.of(Map.of(
                    "contentType", "application/vnd.microsoft.card.adaptive", "content", card)));
            return post(egress, url, Map.of(), Json.write(payload));
        }
    }

    /** A signed JSON webhook to a tenant-configured URL. {@code PRD-API-053}. */
    public static final class GenericWebhook implements ChannelSender {
        public static final String KIND = "GENERIC_WEBHOOK";
        private final EgressGuard egress;

        GenericWebhook(EgressGuard egress) {
            this.egress = egress;
        }

        @Override
        public String kind() {
            return KIND;
        }

        @Override
        public boolean requiresSecret() {
            return false;
        }

        @Override
        public Map<String, Object> validate(Map<String, Object> config, boolean secretPresent) {
            String url = text(config, "url").orElseThrow(() -> new IllegalArgumentException("a webhook URL is required"));
            egress.refusal(url).ifPresent(reason -> {
                throw new IllegalArgumentException("the webhook URL was refused: " + reason);
            });
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("url", url);
            return out;
        }

        @Override
        public Outcome send(Map<String, Object> config, Optional<char[]> secret, Optional<String> address, Message message) {
            String url = String.valueOf(config.get("url"));
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("event", "notification");
            payload.put("title", message.title());
            message.body().ifPresent(b -> payload.put("body", b));
            message.link().ifPresent(l -> payload.put("link", l));
            payload.put("locale", message.locale());
            String body = Json.write(payload);
            Map<String, String> headers = new LinkedHashMap<>();
            secret.ifPresent(s -> headers.put("X-ASPM-Signature", "sha256=" + hmac(new String(s).getBytes(StandardCharsets.UTF_8), body)));
            return post(egress, url, headers, body);
        }
    }

    // ==============================================================================================

    static ChannelSender.Outcome post(EgressGuard egress, String url, Map<String, String> headers, String body) {
        if (!egress.permitted(url)) {
            return ChannelSender.Outcome.failed(FailureClass.DATA, "the destination is not a permitted egress destination");
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("User-Agent", "aspm-notify/1")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(request::header);
        try {
            HttpResponse<String> response = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                String reply = response.body() == null ? "" : response.body();
                return ChannelSender.Outcome.sent("HTTP " + status + " body=" + (reply.length() > 200 ? reply.substring(0, 200) : reply));
            }
            if (status == 401 || status == 403) {
                return ChannelSender.Outcome.failed(FailureClass.AUTHENTICATION, "HTTP " + status);
            }
            if (status == 429) {
                return ChannelSender.Outcome.failed(FailureClass.RATE_LIMITED, "HTTP 429"
                        + response.headers().firstValue("Retry-After").map(v -> " retry-after=" + v).orElse(""));
            }
            if (status >= 500) {
                return ChannelSender.Outcome.failed(FailureClass.TRANSIENT, "HTTP " + status);
            }
            return ChannelSender.Outcome.failed(FailureClass.DATA, "HTTP " + status);
        } catch (IOException e) {
            return ChannelSender.Outcome.failed(FailureClass.TRANSIENT, e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ChannelSender.Outcome.failed(FailureClass.TRANSIENT, "interrupted");
        }
    }

    static String plainText(ChannelSender.Message message) {
        StringBuilder text = new StringBuilder(message.title());
        message.body().ifPresent(b -> text.append("\n").append(b));
        message.link().ifPresent(l -> text.append("\n").append(l));
        return text.toString();
    }

    static String hmac(byte[] key, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is required by the platform", e);
        }
    }

    private static Optional<String> text(Map<String, Object> map, String key) {
        return map.get(key) instanceof String s && !s.isBlank() ? Optional.of(s.strip()) : Optional.empty();
    }

    /** Retry-After in seconds, when a target sent one. */
    static Optional<Duration> retryAfter(String detail) {
        Objects.requireNonNull(detail);
        int at = detail.indexOf("retry-after=");
        if (at < 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(Duration.ofSeconds(Long.parseLong(detail.substring(at + "retry-after=".length()).strip())));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
