package aspm.app.egress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The single egress enforcement point for tenant-configured destinations. {@code PRD-CON-032},
 * {@code PRD-CON-033}, {@code PRD-CON-034}, {@code OPS-DEP-015}, {@code TST-AUZ-001}.
 */
class EgressGuardTest {

    private static InetAddress[] addresses(String... literals) {
        try {
            InetAddress[] out = new InetAddress[literals.length];
            for (int i = 0; i < literals.length; i++) {
                out[i] = InetAddress.getByName(literals[i]);
            }
            return out;
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final Map<String, InetAddress[]> HOSTS = Map.of(
            "idp.example.com", addresses("93.184.216.34"),
            "metadata.internal", addresses("169.254.169.254"),
            "intranet.corp", addresses("10.20.30.40"),
            "dual.example.com", addresses("93.184.216.34", "192.168.1.5"),
            "ula.example.com", addresses("fd12:3456:789a::1"),
            "cgnat.example.com", addresses("100.64.0.9"),
            "localhost", addresses("127.0.0.1"));

    private final EgressGuard guard = EgressGuard.withResolver(host -> HOSTS.getOrDefault(host, new InetAddress[0]));

    @Test
    @DisplayName("PRD-CON-033: a public https destination is permitted; every private range is refused after resolution")
    void resolvedRanges() {
        assertTrue(guard.permitted("https://idp.example.com/oauth2/default"));
        assertFalse(guard.permitted("https://metadata.internal/latest/meta-data"), "link-local: the cloud metadata service");
        assertFalse(guard.permitted("https://intranet.corp/"), "RFC 1918");
        assertFalse(guard.permitted("https://dual.example.com/"), "one private address among public ones refuses the name");
        assertFalse(guard.permitted("https://ula.example.com/"), "fc00::/7, which the JDK's isSiteLocalAddress misses");
        assertFalse(guard.permitted("https://cgnat.example.com/"), "100.64.0.0/10");
        assertFalse(guard.permitted("https://localhost/"), "loopback");
        assertFalse(guard.permitted("https://unknown.example/"), "a name that does not resolve is refused, not attempted");
    }

    @Test
    @DisplayName("PRD-CON-032: only https, only a host, never credentials in the URL")
    void shape() {
        assertFalse(guard.permitted("http://idp.example.com/"));
        assertFalse(guard.permitted("ftp://idp.example.com/"));
        assertFalse(guard.permitted("https://user:pw@idp.example.com/"));
        assertFalse(guard.permitted("https:///path"));
        assertFalse(guard.permitted(""));
        assertFalse(guard.permitted(null));
        assertFalse(guard.permitted("not a url at all ://"));
        assertTrue(guard.refusal("http://idp.example.com/").orElseThrow().contains("https"));
    }

    @Test
    @DisplayName("TST-AUZ-001: the test-mode guard admits loopback and says so; the production guard does neither")
    void testModeIsVisible() {
        EgressGuard test = EgressGuard.permittingLoopbackForTests();
        assertTrue(test.permitsLoopback());
        assertTrue(test.permitted("http://127.0.0.1:8080/x"));
        assertTrue(test.description().contains("TEST MODE"));
        assertFalse(EgressGuard.production().permitsLoopback());
        assertFalse(EgressGuard.production().permitted("http://127.0.0.1:8080/x"));
        assertFalse(EgressGuard.production().description().contains("TEST MODE"));
    }
}
