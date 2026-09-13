package aspm.app.notification.channel;

import aspm.app.egress.EgressGuard;
import aspm.module.integration.domain.FailureClass;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Email through an SMTP relay. {@code PRD-NTF-003}, {@code PRD-NTF-032}, {@code OPS-DEP-014}.
 *
 * <h2>Egress</h2>
 *
 * <p>A relay is either public — reached over TLS at a public address, checked by the {@link EgressGuard}
 * like any tenant destination — or the deployment's own, named by the operator in
 * {@code ASPM_SMTP_RELAYS} (host:port entries). Only an allowlisted relay may sit on a private address
 * and only an allowlisted relay may be spoken to in the clear; a tenant cannot point this channel at an
 * arbitrary internal host and receive its banner back. That is {@code OPS-DEP-014}'s per-unit allowlist
 * expressed for the one protocol that legitimately reaches inside the network.
 */
public final class SmtpSender implements ChannelSender {

    public static final String KIND = "EMAIL_SMTP";
    public static final String RELAYS = "ASPM_SMTP_RELAYS";
    public static final String CLEARTEXT_RELAYS = "ASPM_SMTP_CLEARTEXT_RELAYS";

    private final EgressGuard egress;
    /** Operator-allowlisted relays as host:port. May be private. */
    private final Set<String> relays;
    /** The subset of those that may be spoken to without TLS. */
    private final Set<String> cleartextRelays;

    public SmtpSender(EgressGuard egress, Set<String> relays, Set<String> cleartextRelays) {
        this.egress = Objects.requireNonNull(egress);
        this.relays = Set.copyOf(relays);
        this.cleartextRelays = Set.copyOf(cleartextRelays);
    }

    public static SmtpSender fromEnvironment(EgressGuard egress, Map<String, String> environment) {
        return new SmtpSender(egress, split(environment.get(RELAYS)), split(environment.get(CLEARTEXT_RELAYS)));
    }

    private static Set<String> split(String csv) {
        Set<String> out = new java.util.LinkedHashSet<>();
        if (csv != null) {
            for (String part : csv.split(",")) {
                if (!part.isBlank()) {
                    out.add(part.strip().toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
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
        Map<String, Object> out = new LinkedHashMap<>();
        String host = text(config, "host").map(h -> h.toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new IllegalArgumentException("an SMTP host is required"));
        if (!host.matches("[a-z0-9.-]+")) {
            throw new IllegalArgumentException("the SMTP host is a host name, not a URL");
        }
        int port = config.get("port") instanceof Number n ? n.intValue() : Integer.parseInt(text(config, "port").orElse("587"));
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("the SMTP port is between 1 and 65535");
        }
        SmtpClient.TlsMode tls;
        try {
            tls = SmtpClient.TlsMode.valueOf(text(config, "tls_mode").orElse(port == 465 ? "IMPLICIT" : "STARTTLS").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("tls_mode is STARTTLS, IMPLICIT or NONE");
        }
        String key = host + ":" + port;
        boolean allowlisted = relays.contains(key);
        if (tls == SmtpClient.TlsMode.NONE && !cleartextRelays.contains(key)) {
            throw new IllegalArgumentException("cleartext SMTP is admitted only for a relay the operator listed in "
                    + CLEARTEXT_RELAYS + "; " + key + " is not one");
        }
        if (!allowlisted) {
            // A public relay is a tenant destination like any other and passes the same guard: a
            // public address, resolved now and again at send.
            egress.refusal("https://" + host + "/").ifPresent(reason -> {
                throw new IllegalArgumentException("the SMTP host was refused: " + reason + ". A relay on a private "
                        + "address must be listed by the operator in " + RELAYS);
            });
        }
        String from = text(config, "from_address").orElseThrow(() -> new IllegalArgumentException("a from address is required"));
        if (!from.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw new IllegalArgumentException("the from address is not an email address");
        }
        Optional<String> username = text(config, "username");
        if (username.isPresent() && !secretPresent) {
            throw new IllegalArgumentException("a username needs a password; enter the password as the channel secret");
        }
        out.put("host", host);
        out.put("port", port);
        out.put("tls_mode", tls.name());
        out.put("from_address", from);
        text(config, "from_name").ifPresent(n -> out.put("from_name", n));
        text(config, "reply_to").ifPresent(r -> out.put("reply_to", r));
        username.ifPresent(u -> out.put("username", u));
        out.put("helo_name", text(config, "helo_name").orElse("aspm"));
        return out;
    }

    @Override
    public Outcome send(Map<String, Object> config, Optional<char[]> secret, Optional<String> address, Message message) {
        String to = address.orElse(null);
        if (to == null || !to.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            return Outcome.failed(FailureClass.DATA, "the recipient has no usable email address");
        }
        String host = String.valueOf(config.get("host"));
        int port = ((Number) config.get("port")).intValue();
        SmtpClient.TlsMode tls = SmtpClient.TlsMode.valueOf(String.valueOf(config.get("tls_mode")));
        if (!relays.contains(host + ":" + port) && !egress.permitted("https://" + host + "/")) {
            return Outcome.failed(FailureClass.DATA, "the relay is no longer a permitted destination");
        }
        Optional<String> username = text(config, "username");
        try {
            SmtpClient client = new SmtpClient(host, port, tls, username, username.isPresent() ? secret : Optional.empty(),
                    String.valueOf(config.getOrDefault("helo_name", "aspm")));
            StringBuilder body = new StringBuilder(message.title()).append("\n");
            message.body().ifPresent(b -> body.append("\n").append(b).append("\n"));
            message.link().ifPresent(l -> body.append("\n").append(l).append("\n"));
            String reply = client.submit(new SmtpClient.Envelope(String.valueOf(config.get("from_address")),
                    text(config, "from_name"), to, text(config, "reply_to"), message.title(), body.toString()));
            return Outcome.sent(reply.length() > 120 ? reply.substring(0, 120) : reply);
        } catch (SmtpClient.SmtpFailure e) {
            return Outcome.failed(classify(e), e.stage() + " " + e.code());
        } catch (IOException e) {
            return Outcome.failed(FailureClass.TRANSIENT, e.getClass().getSimpleName());
        } catch (IllegalArgumentException e) {
            return Outcome.failed(FailureClass.DATA, e.getMessage());
        }
    }

    static FailureClass classify(SmtpClient.SmtpFailure e) {
        if ("AUTH".equals(e.stage())) {
            return FailureClass.AUTHENTICATION;
        }
        if (e.code() >= 400 && e.code() < 500) {
            return FailureClass.TRANSIENT;      // 4xx: try again later, per RFC 5321
        }
        if (e.code() == 550 || e.code() == 551 || e.code() == 553) {
            return FailureClass.DATA;           // no such mailbox: the address is wrong, not the relay
        }
        if (e.code() >= 500) {
            return FailureClass.PROTOCOL;
        }
        return FailureClass.DATA;      // STARTTLS not offered, no AUTH mechanism
    }

    private static Optional<String> text(Map<String, Object> map, String key) {
        return map.get(key) instanceof String s && !s.isBlank() ? Optional.of(s.strip()) : Optional.empty();
    }
}
