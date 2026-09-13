package aspm.app.notification.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The SMTP submission client against a fake relay that records the wire. {@code PRD-NTF-003},
 * {@code PRD-NTF-014}, {@code PRD-NTF-030}.
 */
class SmtpClientTest {

    private ServerSocket relay;
    private final List<String> received = new CopyOnWriteArrayList<>();
    private Thread server;

    /** A one-connection relay: answers by script, records every client line. */
    private int start(boolean offerStartTls, boolean offerAuth) throws IOException {
        relay = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        server = new Thread(() -> {
            try (Socket s = relay.accept()) {
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
                OutputStream out = s.getOutputStream();
                say(out, "220 fake.relay ESMTP");
                String line;
                boolean inData = false;
                StringBuilder data = new StringBuilder();
                while ((line = in.readLine()) != null) {
                    if (inData) {
                        if (line.equals(".")) {
                            inData = false;
                            received.add("DATA:" + data);
                            say(out, "250 2.0.0 queued as 42");
                        } else {
                            data.append(line).append("\n");
                        }
                        continue;
                    }
                    received.add(line);
                    String upper = line.toUpperCase();
                    if (upper.startsWith("EHLO")) {
                        say(out, "250-fake.relay");
                        if (offerStartTls) {
                            say(out, "250-STARTTLS");
                        }
                        if (offerAuth) {
                            say(out, "250-AUTH PLAIN LOGIN");
                        }
                        say(out, "250 8BITMIME");
                    } else if (upper.startsWith("AUTH PLAIN")) {
                        say(out, line.endsWith("AGFsaWNlAHNlY3JldA==") ? "235 2.7.0 ok" : "535 5.7.8 bad");
                    } else if (upper.startsWith("MAIL FROM") || upper.startsWith("RCPT TO")) {
                        say(out, "250 ok");
                    } else if (upper.equals("DATA")) {
                        inData = true;
                        say(out, "354 go");
                    } else if (upper.equals("QUIT")) {
                        say(out, "221 bye");
                        return;
                    } else {
                        say(out, "500 what");
                    }
                }
            } catch (IOException ignored) {
                // The client closed; the assertions are on what was received.
            }
        }, "fake-relay");
        server.start();
        return relay.getLocalPort();
    }

    private static void say(OutputStream out, String line) throws IOException {
        out.write((line + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    @AfterEach
    void stop() throws Exception {
        if (relay != null) {
            relay.close();
        }
        if (server != null) {
            server.join(2000);
        }
    }

    @Test
    @DisplayName("PRD-NTF-003: a submission speaks EHLO, AUTH PLAIN, MAIL FROM, RCPT TO, DATA, QUIT and encodes headers and body for UTF-8")
    void submitsOneMessage() throws Exception {
        int port = start(false, true);
        SmtpClient client = new SmtpClient("127.0.0.1", port, SmtpClient.TlsMode.NONE, Optional.of("alice"),
                Optional.of("secret".toCharArray()), "aspm-test");
        String reply = client.submit(new SmtpClient.Envelope("aspm@example.com", Optional.of("AI ASPM"), "dev@example.com",
                Optional.empty(), "Yêu cầu REQ-7 chuyển sang ACCEPTED", "Xin chào.\n.leading dot line\nhttps://aspm.example.com/board/1"));
        assertTrue(reply.startsWith("250"), reply);
        server.join(2000);

        assertEquals("EHLO aspm-test", received.get(0));
        assertTrue(received.get(1).startsWith("AUTH PLAIN "), received.get(1));
        assertEquals("MAIL FROM:<aspm@example.com>", received.get(2));
        assertEquals("RCPT TO:<dev@example.com>", received.get(3));
        assertEquals("DATA", received.get(4));
        String data = received.stream().filter(r -> r.startsWith("DATA:")).findFirst().orElseThrow();
        assertTrue(data.contains("Subject: =?UTF-8?B?"), "the subject is an encoded word, never raw UTF-8");
        assertTrue(data.contains("From: =?UTF-8?B?") && data.contains("<aspm@example.com>"));
        assertTrue(data.contains("Content-Transfer-Encoding: base64"));
        assertTrue(data.contains("Auto-Submitted: auto-generated"));
        // The body round-trips through base64, dot-stuffing included.
        String encoded = data.substring(data.indexOf("\n\n") + 2).replace("\n", "");
        String body = new String(Base64.getMimeDecoder().decode(encoded), StandardCharsets.UTF_8);
        assertTrue(body.contains("Xin chào.") && body.contains(".leading dot line"), body);
        assertEquals("QUIT", received.get(received.size() - 1));
    }

    @Test
    @DisplayName("PRD-NTF-003 / SEC-SEC-025: STARTTLS mode refuses a relay that does not offer it rather than continuing in the clear")
    void startTlsRequired() throws Exception {
        int port = start(false, false);
        SmtpClient client = new SmtpClient("127.0.0.1", port, SmtpClient.TlsMode.STARTTLS, Optional.empty(), Optional.empty(), "aspm");
        SmtpClient.SmtpFailure failure = assertThrows(SmtpClient.SmtpFailure.class, () -> client.submit(
                new SmtpClient.Envelope("a@example.com", Optional.empty(), "b@example.com", Optional.empty(), "s", "b")));
        assertEquals("STARTTLS", failure.stage());
        server.join(2000);
        assertTrue(received.stream().noneMatch(r -> r.startsWith("MAIL FROM")), "nothing was submitted: " + received);
    }

    @Test
    @DisplayName("PRD-CON-025: a rejected credential is classified as AUTHENTICATION, a missing mailbox as DATA, a 4xx as TRANSIENT")
    void classification() {
        assertEquals(aspm.module.integration.domain.FailureClass.AUTHENTICATION,
                SmtpSender.classify(new SmtpClient.SmtpFailure("AUTH", 535, "bad")));
        assertEquals(aspm.module.integration.domain.FailureClass.DATA,
                SmtpSender.classify(new SmtpClient.SmtpFailure("RCPT TO", 550, "no such user")));
        assertEquals(aspm.module.integration.domain.FailureClass.TRANSIENT,
                SmtpSender.classify(new SmtpClient.SmtpFailure("MAIL FROM", 451, "try later")));
        assertEquals(aspm.module.integration.domain.FailureClass.PROTOCOL,
                SmtpSender.classify(new SmtpClient.SmtpFailure("DATA", 554, "rejected")));
    }

    @Test
    @DisplayName("PRD-NTF-030: a message carrying a secret marker is refused before any sender sees it")
    void contentGuard() {
        assertThrows(IllegalArgumentException.class, () -> new ChannelSender.Message("Bearer abc", Optional.empty(), Optional.empty(), "en"));
        assertThrows(IllegalArgumentException.class, () -> new ChannelSender.Message("ok", Optional.of("password=hunter2"), Optional.empty(), "en"));
        new ChannelSender.Message("Request REQ-1 moved to ACCEPTED", Optional.empty(), Optional.of("/board/1"), "en");
        List<String> unused = new ArrayList<>();
        assertTrue(unused.isEmpty());
    }
}
