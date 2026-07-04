package com.certguard.agent.http;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link PollLoop#checkPublicAddressGuard(String)} — the SSRF-guard
 * gate that {@code processCertScanJob} invokes for PUBLIC_POOL jobs before dialing
 * (RFC 0013 §4.4 / architect review B1).
 *
 * <p>Extracted as a pure static method specifically so this invariant is directly
 * testable without constructing a full {@link PollLoop} — AgentConfig, ServerApiClient,
 * and SslScanner have no test doubles in this framework-free module (no Mockito).
 */
class PollLoopSsrfGuardTest {

    @Nested
    class RejectsDisallowedAddresses {

        @ParameterizedTest(name = "rejects {0}")
        @ValueSource(strings = {
                "127.0.0.1",        // loopback
                "169.254.169.254",  // cloud metadata / link-local
                "10.0.0.5",         // RFC1918
                "172.16.0.1",       // RFC1918
                "192.168.1.1",      // RFC1918
                "100.64.0.1",       // CGNAT
                "0.0.0.0",          // any-local
                "::1",              // loopback IPv6
                "fe80::1",          // link-local IPv6
                "fc00::1",          // ULA IPv6
        })
        void rejectsDisallowedAddress(String host) {
            String reason = PollLoop.checkPublicAddressGuard(host);
            assertNotNull(reason, "expected " + host + " to be rejected by the SSRF guard");
        }
    }

    @Nested
    class AllowsPublicAddresses {

        @Test
        void allowsPublicIpv4Literal() {
            String reason = PollLoop.checkPublicAddressGuard("8.8.8.8");
            assertNull(reason, "8.8.8.8 is publicly routable and must not be rejected");
        }

        @Test
        void allowsPublicIpv6Literal() {
            String reason = PollLoop.checkPublicAddressGuard("2001:4860:4860::8888");
            assertNull(reason, "Google public DNS IPv6 must not be rejected");
        }
    }

    @Nested
    class NullAndBlankHandling {

        @Test
        void rejectsNullHost() {
            assertNotNull(PollLoop.checkPublicAddressGuard(null));
        }

        @Test
        void rejectsBlankHost() {
            assertNotNull(PollLoop.checkPublicAddressGuard("   "));
        }
    }
}
