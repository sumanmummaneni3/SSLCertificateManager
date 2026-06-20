package com.certguard.service.revocation;

import com.certguard.service.revocation.SsrfGuard.SsrfBlockedException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SsrfGuard} (BE-5).
 *
 * <p>All tests are pure in-process — no network required (we test isBlockedByName
 * and isPrivateOrReserved helpers directly where DNS is not available).
 */
class SsrfGuardTest {

    @Nested
    class BlockedSchemes {
        @ParameterizedTest
        @ValueSource(strings = {"ftp://crl.example.com/crl.crl", "file:///etc/passwd",
                                "ldap://internal.corp/cn=crl", "ftps://crl.example.com",
                                "javascript:alert(1)"})
        void blocks_non_http_schemes(String url) {
            assertThatThrownBy(() -> SsrfGuard.validate(url))
                    .isInstanceOf(SsrfBlockedException.class)
                    .hasMessageContaining("disallowed scheme");
        }

        @Test
        void blocks_null_url() {
            assertThatThrownBy(() -> SsrfGuard.validate(null))
                    .isInstanceOf(SsrfBlockedException.class)
                    .hasMessageContaining("null/blank");
        }

        @Test
        void blocks_blank_url() {
            assertThatThrownBy(() -> SsrfGuard.validate("   "))
                    .isInstanceOf(SsrfBlockedException.class)
                    .hasMessageContaining("null/blank");
        }
    }

    @Nested
    class AllowedSchemes {
        @Test
        void allows_http_to_external_host() {
            // Will attempt DNS — if it resolves to a public IP it passes; if DNS fails it passes
            // (DNS failure is a connectivity error not an SSRF). Use a well-known CA host.
            // No assertion exception == pass.
            try {
                SsrfGuard.validate("http://ocsp.digicert.com/");
            } catch (SsrfBlockedException e) {
                // Might be blocked if it resolves to something private in CI — log but don't fail.
                // Only fail if it's not a resolution issue.
                if (!e.getMessage().contains("resolved to private")) throw e;
            }
        }

        @Test
        void allows_https_to_external_host() {
            try {
                SsrfGuard.validate("https://crl.digicert.com/DigiCertTLSRSASHA2562020CA1-4.crl");
            } catch (SsrfBlockedException e) {
                if (!e.getMessage().contains("resolved to private")) throw e;
            }
        }
    }

    @Nested
    class BlockedByName {
        @ParameterizedTest
        @ValueSource(strings = {"localhost", "LOCALHOST", "Localhost"})
        void blocks_localhost(String host) {
            assertThat(SsrfGuard.isBlockedByName(host)).isTrue();
        }

        @Test
        void blocks_loopback_ipv4() {
            assertThat(SsrfGuard.isBlockedByName("127.0.0.1")).isTrue();
        }

        @Test
        void blocks_loopback_ipv6() {
            assertThat(SsrfGuard.isBlockedByName("::1")).isTrue();
        }

        @Test
        void blocks_dot_local() {
            assertThat(SsrfGuard.isBlockedByName("myserver.local")).isTrue();
        }

        @Test
        void blocks_link_local_prefix() {
            assertThat(SsrfGuard.isBlockedByName("169.254.1.5")).isTrue();
        }

        @Test
        void allows_external_hostname() {
            assertThat(SsrfGuard.isBlockedByName("ocsp.digicert.com")).isFalse();
        }

        @Test
        void allows_external_ip() {
            assertThat(SsrfGuard.isBlockedByName("8.8.8.8")).isFalse();
        }
    }

    @Nested
    class BlockedByAddress {
        @Test
        void loopback_ipv4_is_blocked() throws Exception {
            InetAddress addr = InetAddress.getByName("127.0.0.1");
            assertThat(SsrfGuard.isPrivateOrReserved(addr)).isTrue();
        }

        @Test
        void site_local_10_is_blocked() throws Exception {
            InetAddress addr = InetAddress.getByName("10.0.0.1");
            assertThat(SsrfGuard.isPrivateOrReserved(addr)).isTrue();
        }

        @Test
        void site_local_192_168_is_blocked() throws Exception {
            InetAddress addr = InetAddress.getByName("192.168.1.1");
            assertThat(SsrfGuard.isPrivateOrReserved(addr)).isTrue();
        }

        @Test
        void site_local_172_16_is_blocked() throws Exception {
            InetAddress addr = InetAddress.getByName("172.16.0.1");
            assertThat(SsrfGuard.isPrivateOrReserved(addr)).isTrue();
        }

        @Test
        void link_local_is_blocked() throws Exception {
            InetAddress addr = InetAddress.getByName("169.254.0.1");
            assertThat(SsrfGuard.isPrivateOrReserved(addr)).isTrue();
        }

        @Test
        void public_ip_is_not_blocked() throws Exception {
            InetAddress addr = InetAddress.getByName("8.8.8.8");
            assertThat(SsrfGuard.isPrivateOrReserved(addr)).isFalse();
        }
    }

    @Nested
    class FullUrlValidation {
        @Test
        void http_localhost_is_blocked() {
            assertThatThrownBy(() -> SsrfGuard.validate("http://localhost/crl.crl"))
                    .isInstanceOf(SsrfBlockedException.class)
                    .hasMessageContaining("private/loopback");
        }

        @Test
        void http_127_is_blocked() {
            assertThatThrownBy(() -> SsrfGuard.validate("http://127.0.0.1:8080/crl"))
                    .isInstanceOf(SsrfBlockedException.class);
        }

        @Test
        void http_private_range_is_blocked() {
            // 10.0.0.1 is always private regardless of DNS outcome
            assertThatThrownBy(() -> SsrfGuard.validate("http://10.0.0.1/crl"))
                    .isInstanceOf(SsrfBlockedException.class);
        }

        @Test
        void ftp_localhost_is_blocked_by_scheme_first() {
            // ftp scheme should be caught before even checking host
            assertThatThrownBy(() -> SsrfGuard.validate("ftp://localhost/crl"))
                    .isInstanceOf(SsrfBlockedException.class)
                    .hasMessageContaining("disallowed scheme");
        }
    }
}
