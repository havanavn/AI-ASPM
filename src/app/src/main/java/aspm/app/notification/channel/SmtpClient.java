package aspm.app.notification.channel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * A minimal SMTP submission client: EHLO, STARTTLS or implicit TLS, AUTH PLAIN or LOGIN, one message,
 * QUIT. RFC 5321 / 4954 / 3207 / 5322 / 2047, the subset a relay submission needs and nothing else.
 *
 * <h2>Why not a mail library</h2>
 *
 * <p>The platform's dependency set is small on purpose (ADR-050: pinned, deterministic, bundled for the
 * air-gapped topology). Submitting one message to one relay is a hundred lines of protocol; a mail
 * library is a hundred thousand lines of everything else, including IMAP, and its transitive
 * dependency graph would be the largest in the platform. This class does what the notification
 * channel needs, states its limits, and is tested against a fake relay that asserts the wire.
 *
 * <h2>Transport security</h2>
 *
 * <ul>
 *   <li>{@code STARTTLS}: connect in the clear, {@code EHLO}, {@code STARTTLS}, upgrade, {@code EHLO}
 *       again. The default for port 587. Refused if the relay does not offer STARTTLS.
 *   <li>{@code IMPLICIT}: TLS from the first byte (port 465).
 *   <li>{@code NONE}: cleartext. Admitted ONLY for a relay the operator allowlisted in
 *       {@code ASPM_SMTP_CLEARTEXT_RELAYS} — a mail relay inside the mesh where TLS terminates at the
 *       sidecar is the one legitimate case (DOC-15 §14: "an internal mail relay if present"). A tenant
 *       cannot choose it for an arbitrary host.
 * </ul>
 *
 * <p>Certificates are verified by the JDK's default trust store; a relay with a private CA is served by
 * adding that CA to the runtime's trust store, which is the operator's deployment concern and not a
 * flag here — a "trust everything" switch is how every TLS failure ends up permanently switched off.
 *
 * <h2>Message format</h2>
 *
 * <p>UTF-8 throughout: headers RFC 2047 encoded, body {@code text/plain; charset=utf-8} in base64 so
 * that no relay's 7-bit path or line-length limit can alter it. One recipient per submission, because
 * every notification is rendered for one person ({@code PRD-NTF-014}).
 */
public final class SmtpClient {

    public enum TlsMode { STARTTLS, IMPLICIT, NONE }

    /** A relay's answer that the submission cannot proceed past, with the reply code for classification. */
    public static final class SmtpFailure extends IOException {
        private static final long serialVersionUID = 1L;
        private final int code;
        private final String stage;

        public SmtpFailure(String stage, int code, String line) {
            super(stage + ": " + code + (line == null ? "" : " " + line));
            this.stage = stage;
            this.code = code;
        }

        public int code() {
            return code;
        }

        public String stage() {
            return stage;
        }
    }

    /** A message to submit. */
    public record Envelope(String fromAddress, Optional<String> fromName, String toAddress, Optional<String> replyTo,
            String subject, String textBody) {
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final DateTimeFormatter RFC_5322_DATE = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ROOT);

    private final String host;
    private final int port;
    private final TlsMode tlsMode;
    private final Optional<String> username;
    private final Optional<char[]> password;
    private final String heloName;

    public SmtpClient(String host, int port, TlsMode tlsMode, Optional<String> username, Optional<char[]> password, String heloName) {
        this.host = Objects.requireNonNull(host);
        this.port = port;
        this.tlsMode = Objects.requireNonNull(tlsMode);
        this.username = username;
        this.password = password;
        this.heloName = heloName == null || heloName.isBlank() ? "aspm" : heloName;
        if (username.isPresent() != password.isPresent()) {
            throw new IllegalArgumentException("SMTP authentication needs both a username and a password");
        }
    }

    /** Submits one message. Returns the relay's final reply to DATA (for the delivery record). */
    public String submit(Envelope envelope) throws IOException {
        Socket socket = tlsMode == TlsMode.IMPLICIT
                ? SSLSocketFactory.getDefault().createSocket()
                : new Socket();
        try (Socket s = socket) {
            s.connect(new InetSocketAddress(host, port), (int) TIMEOUT.toMillis());
            s.setSoTimeout((int) TIMEOUT.toMillis());
            if (s instanceof SSLSocket ssl) {
                ssl.startHandshake();
            }
            Conversation c = new Conversation(s);
            c.expect("greeting", 220);
            List<String> extensions = c.ehlo(heloName);
            if (tlsMode == TlsMode.STARTTLS) {
                if (extensions.stream().noneMatch(e -> e.equalsIgnoreCase("STARTTLS"))) {
                    throw new SmtpFailure("STARTTLS", 0, "the relay does not offer STARTTLS; refusing to continue in the clear");
                }
                c.command("STARTTLS");
                c.expect("STARTTLS", 220);
                SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                        .createSocket(s, host, port, true);
                ssl.setUseClientMode(true);
                ssl.startHandshake();
                c = new Conversation(ssl);
                extensions = c.ehlo(heloName);
            }
            if (username.isPresent()) {
                boolean plain = extensions.stream().anyMatch(e -> e.toUpperCase(Locale.ROOT).startsWith("AUTH") && e.toUpperCase(Locale.ROOT).contains("PLAIN"));
                boolean login = extensions.stream().anyMatch(e -> e.toUpperCase(Locale.ROOT).startsWith("AUTH") && e.toUpperCase(Locale.ROOT).contains("LOGIN"));
                if (plain) {
                    String credentials = Base64.getEncoder().encodeToString(
                            ("\0" + username.get() + "\0" + new String(password.get())).getBytes(StandardCharsets.UTF_8));
                    c.command("AUTH PLAIN " + credentials);
                    c.expect("AUTH", 235);
                } else if (login) {
                    c.command("AUTH LOGIN");
                    c.expect("AUTH", 334);
                    c.command(Base64.getEncoder().encodeToString(username.get().getBytes(StandardCharsets.UTF_8)));
                    c.expect("AUTH", 334);
                    c.command(Base64.getEncoder().encodeToString(new String(password.get()).getBytes(StandardCharsets.UTF_8)));
                    c.expect("AUTH", 235);
                } else {
                    throw new SmtpFailure("AUTH", 0, "the relay offers neither AUTH PLAIN nor AUTH LOGIN");
                }
            }
            c.command("MAIL FROM:<" + envelope.fromAddress() + ">");
            c.expect("MAIL FROM", 250);
            c.command("RCPT TO:<" + envelope.toAddress() + ">");
            c.expect("RCPT TO", 250, 251);
            c.command("DATA");
            c.expect("DATA", 354);
            c.raw(message(envelope));
            c.raw("\r\n.\r\n");
            String accepted = c.expect("DATA", 250);
            c.command("QUIT");
            return accepted;
        }
    }

    /** The RFC 5322 message, dot-stuffed for the DATA phase. */
    static String message(Envelope envelope) {
        StringBuilder m = new StringBuilder();
        m.append("From: ").append(mailbox(envelope.fromName(), envelope.fromAddress())).append("\r\n");
        m.append("To: <").append(envelope.toAddress()).append(">\r\n");
        envelope.replyTo().ifPresent(r -> m.append("Reply-To: <").append(r).append(">\r\n"));
        m.append("Subject: ").append(encodedWord(envelope.subject())).append("\r\n");
        m.append("Date: ").append(RFC_5322_DATE.format(ZonedDateTime.now())).append("\r\n");
        m.append("Message-ID: <").append(UUID.randomUUID()).append("@aspm>\r\n");
        m.append("MIME-Version: 1.0\r\n");
        m.append("Content-Type: text/plain; charset=utf-8\r\n");
        m.append("Content-Transfer-Encoding: base64\r\n");
        // A notification is not a marketing message and should not trigger auto-replies (RFC 3834).
        m.append("Auto-Submitted: auto-generated\r\n");
        m.append("X-Auto-Response-Suppress: All\r\n");
        m.append("\r\n");
        String body = Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(envelope.textBody().getBytes(StandardCharsets.UTF_8));
        m.append(body);
        // Dot-stuffing: a line that begins with '.' gains a second one.
        return m.toString().replace("\r\n.", "\r\n..");
    }

    private static String mailbox(Optional<String> name, String address) {
        return name.filter(n -> !n.isBlank()).map(n -> encodedWord(n) + " <" + address + ">").orElse("<" + address + ">");
    }

    /** RFC 2047 encoded-word, always applied so no header ever carries raw UTF-8. */
    static String encodedWord(String text) {
        return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    /** The line-oriented half of the protocol. */
    private static final class Conversation {
        private final BufferedReader in;
        private final OutputStream out;

        Conversation(Socket socket) throws IOException {
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            this.out = socket.getOutputStream();
        }

        List<String> ehlo(String name) throws IOException {
            command("EHLO " + name);
            List<String> lines = readReply();
            int code = code(lines.get(lines.size() - 1));
            if (code != 250) {
                throw new SmtpFailure("EHLO", code, lines.get(lines.size() - 1));
            }
            List<String> extensions = new ArrayList<>();
            for (int i = 1; i < lines.size(); i++) {
                extensions.add(lines.get(i).length() > 4 ? lines.get(i).substring(4).strip() : "");
            }
            return extensions;
        }

        void command(String line) throws IOException {
            raw(line + "\r\n");
        }

        void raw(String text) throws IOException {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        String expect(String stage, int... accepted) throws IOException {
            List<String> lines = readReply();
            String last = lines.get(lines.size() - 1);
            int code = code(last);
            for (int a : accepted) {
                if (a == code) {
                    return last;
                }
            }
            throw new SmtpFailure(stage, code, last);
        }

        private List<String> readReply() throws IOException {
            List<String> lines = new ArrayList<>();
            while (true) {
                String line = in.readLine();
                if (line == null) {
                    throw new SmtpFailure("connection", 0, "the relay closed the connection");
                }
                lines.add(line);
                if (line.length() < 4 || line.charAt(3) != '-') {
                    return lines;
                }
            }
        }

        private static int code(String line) {
            try {
                return Integer.parseInt(line.substring(0, 3));
            } catch (RuntimeException e) {
                return 0;
            }
        }
    }
}
