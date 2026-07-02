package com.certguard.security;

import com.certguard.security.PublicAddressGuard.PublicAddressGuardException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.Inet4Address;
import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link PublicAddressGuard} (RFC 0013 §4 — SSRF defence for PUBLIC_POOL).
 *
 * <p>All tests use InetAddress literals to avoid live DNS lookups, keeping the suite
 * fast and hermetic in CI. The {@link PublicAddressGuard#isDisallowed},
 * {@link PublicAddressGuard#unwrapIpv4Mapped}, {@link PublicAddressGuard#isInCgnat},
 * {@link PublicAddressGuard#isLinkLocal169}, and {@link PublicAddressGuard#isIpv6Ula}
 * helpers are package-private (same package) so we can test them directly without
 * reflection.
 */
class PublicAddressGuardTest {

    // ── assertPubliclyRoutable: null / blank ──────────────────────────────────

    @Test
    void rejects_null_hostname() {
        assertThatThrownBy(() -> PublicAddressGuard.assertPubliclyRoutable(null))
                .isInstanceOf(PublicAddressGuardException.class);
    }

    @Test
    void rejects_blank_hostname() {
        assertThatThrownBy(() -> PublicAddressGuard.assertPubliclyRoutable("   "))
                .isInstanceOf(PublicAddressGuardException.class);
    }

    // ── assertPubliclyRoutable: IP literals (resolves without DNS) ────────────

    @ParameterizedTest(name = "rejects disallowed address {0}")
    @ValueSource(strings = {
            "127.0.0.1",       // loopback
            "169.254.169.254", // AWS metadata (link-local / 169.254.0.0/16)
            "0.0.0.0",         // any-local
            "10.0.0.1",        // site-local RFC 1918
            "172.16.0.1",      // site-local RFC 1918
            "192.168.1.1",     // site-local RFC 1918
            "100.64.0.1",      // CGNAT 100.64.0.0/10
            "::1",             // loopback IPv6
            "fe80::1",         // link-local IPv6
            "fc00::1",         // ULA IPv6 (fc00::/7)
            "fd00::1",         // ULA IPv6 (fd00::/7)
    })
    void rejects_disallowed_ip_literal(String ip) {
        assertThatThrownBy(() -> PublicAddressGuard.assertPubliclyRoutable(ip))
                .isInstanceOf(PublicAddressGuardException.class);
    }

    @Test
    void allows_public_ipv4() {
        // 8.8.8.8 is Google's DNS — universally routable.
        // assertPubliclyRoutable resolves the literal without DNS and must pass.
        PublicAddressGuard.assertPubliclyRoutable("8.8.8.8"); // no exception
    }

    @Test
    void allows_public_ipv6() {
        // 2001:4860:4860::8888 is Google's public IPv6 DNS.
        PublicAddressGuard.assertPubliclyRoutable("2001:4860:4860::8888"); // no exception
    }

    // ── isDisallowed: direct classification ──────────────────────────────────

    @Nested
    class IsDisallowedClassification {

        @Test
        void loopback_ipv4_is_disallowed() throws Exception {
            assertThat(PublicAddressGuard.isDisallowed(addr("127.0.0.1"))).isTrue();
        }

        @Test
        void loopback_ipv6_is_disallowed() throws Exception {
            assertThat(PublicAddressGuard.isDisallowed(addr("::1"))).isTrue();
        }

        @Test
        void link_local_fe80_is_disallowed() throws Exception {
            assertThat(PublicAddressGuard.isDisallowed(addr("fe80::1"))).isTrue();
        }

        @Test
        void site_local_10_is_disallowed() throws Exception {
            assertThat(PublicAddressGuard.isDisallowed(addr("10.1.2.3"))).isTrue();
        }

        @Test
        void site_local_172_16_is_disallowed() throws Exception {
            assertThat(PublicAddressGuard.isDisallowed(addr("172.31.255.255"))).isTrue();
        }

        @Test
        void site_local_192_168_is_disallowed() throws Exception {
            assertThat(PublicAddressGuard.isDisallowed(addr("192.168.100.1"))).isTrue();
        }

        @Test
        void any_local_0_0_0_0_is_disallowed() throws Exception {
            assertThat(PublicAddressGuard.isDisallowed(addr("0.0.0.0"))).isTrue();
        }

        @Test
        void multicast_is_disallowed() throws Exception {
            assertThat(PublicAddressGuard.isDisallowed(addr("224.0.0.1"))).isTrue();
        }

        @Test
        void cgnat_100_64_is_disallowed() throws Exception {
            assertThat(PublicAddressGuard.isDisallowed(addr("100.64.0.1"))).isTrue();
            assertThat(PublicAddressGuard.isDisallowed(addr("100.127.255.255"))).isTrue();
        }

        @Test
        void cgnat_boundary_100_63_is_allowed() throws Exception {
            // 100.63.x.x is NOT in CGNAT range (100.64/10 starts at 100.64)
            assertThat(PublicAddressGuard.isDisallowed(addr("100.63.255.255"))).isFalse();
        }

        @Test
        void cgnat_boundary_100_128_is_allowed() throws Exception {
            // 100.128.x.x is beyond CGNAT range
            assertThat(PublicAddressGuard.isDisallowed(addr("100.128.0.1"))).isFalse();
        }

        @Test
        void link_local_169_254_is_disallowed() throws Exception {
            assertThat(PublicAddressGuard.isDisallowed(addr("169.254.0.1"))).isTrue();
            assertThat(PublicAddressGuard.isDisallowed(addr("169.254.169.254"))).isTrue();
        }

        @Test
        void ula_fc00_is_disallowed() throws Exception {
            assertThat(PublicAddressGuard.isDisallowed(addr("fc00::1"))).isTrue();
        }

        @Test
        void ula_fd00_is_disallowed() throws Exception {
            assertThat(PublicAddressGuard.isDisallowed(addr("fd00::1"))).isTrue();
        }

        @Test
        void public_ipv4_is_allowed() throws Exception {
            assertThat(PublicAddressGuard.isDisallowed(addr("8.8.8.8"))).isFalse();
        }

        @Test
        void public_ipv6_is_allowed() throws Exception {
            assertThat(PublicAddressGuard.isDisallowed(addr("2001:4860:4860::8888"))).isFalse();
        }
    }

    // ── isInCgnat ─────────────────────────────────────────────────────────────

    @Nested
    class CgnatClassification {

        @ParameterizedTest
        @CsvSource({
            "100.64.0.0,  true",
            "100.64.0.1,  true",
            "100.100.0.1, true",
            "100.127.255.255, true",
            "100.63.255.255,  false",
            "100.128.0.0,     false",
            "101.64.0.0,      false",
            "99.64.0.0,       false",
        })
        void cgnat_boundary_cases(String ip, boolean expectedDisallowed) throws Exception {
            assertThat(PublicAddressGuard.isInCgnat(addr(ip))).isEqualTo(expectedDisallowed);
        }
    }

    // ── unwrapIpv4Mapped ──────────────────────────────────────────────────────

    @Nested
    class UnwrapIpv4Mapped {

        @Test
        void unwraps_ipv4_mapped_to_private() throws Exception {
            // ::ffff:10.0.0.1 should unwrap to 10.0.0.1 (site-local → blocked)
            InetAddress mapped = InetAddress.getByName("::ffff:10.0.0.1");
            InetAddress unwrapped = PublicAddressGuard.unwrapIpv4Mapped(mapped);
            assertThat(unwrapped).isInstanceOf(Inet4Address.class);
            assertThat(unwrapped.getHostAddress()).isEqualTo("10.0.0.1");
        }

        @Test
        void unwrapped_private_address_is_disallowed() throws Exception {
            InetAddress mapped = InetAddress.getByName("::ffff:10.0.0.1");
            InetAddress unwrapped = PublicAddressGuard.unwrapIpv4Mapped(mapped);
            assertThat(PublicAddressGuard.isDisallowed(unwrapped)).isTrue();
        }

        @Test
        void unwraps_ipv4_mapped_to_loopback() throws Exception {
            InetAddress mapped = InetAddress.getByName("::ffff:127.0.0.1");
            InetAddress unwrapped = PublicAddressGuard.unwrapIpv4Mapped(mapped);
            assertThat(unwrapped).isInstanceOf(Inet4Address.class);
            assertThat(PublicAddressGuard.isDisallowed(unwrapped)).isTrue();
        }

        @Test
        void ipv4_address_returned_unchanged() throws Exception {
            InetAddress ipv4 = addr("8.8.8.8");
            assertThat(PublicAddressGuard.unwrapIpv4Mapped(ipv4)).isSameAs(ipv4);
        }

        @Test
        void non_mapped_ipv6_returned_unchanged() throws Exception {
            InetAddress globalIpv6 = addr("2001:4860:4860::8888");
            InetAddress result = PublicAddressGuard.unwrapIpv4Mapped(globalIpv6);
            // Not IPv4-mapped, should return the original object (or equal address).
            assertThat(result.getAddress()).isEqualTo(globalIpv6.getAddress());
        }
    }

    // ── isIpv6Ula ─────────────────────────────────────────────────────────────

    @Nested
    class Ipv6UlaClassification {

        @Test
        void fc00_is_ula() throws Exception {
            assertThat(PublicAddressGuard.isIpv6Ula(addr("fc00::1"))).isTrue();
        }

        @Test
        void fd00_is_ula() throws Exception {
            assertThat(PublicAddressGuard.isIpv6Ula(addr("fd00::1"))).isTrue();
        }

        @Test
        void fe80_is_not_ula() throws Exception {
            // fe80::/10 is link-local, not ULA — covered by isLinkLocalAddress()
            assertThat(PublicAddressGuard.isIpv6Ula(addr("fe80::1"))).isFalse();
        }

        @Test
        void global_ipv6_is_not_ula() throws Exception {
            assertThat(PublicAddressGuard.isIpv6Ula(addr("2001:db8::1"))).isFalse();
        }
    }

    // ── resolveAll: IP literals resolve without network ───────────────────────

    @Test
    void resolveAll_returns_address_for_ip_literal() {
        var results = PublicAddressGuard.resolveAll("8.8.8.8");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getHostAddress()).isEqualTo("8.8.8.8");
    }

    @Test
    void resolveAll_returns_empty_for_unresolvable_domain() {
        // A deliberately invalid domain that will never resolve.
        var results = PublicAddressGuard.resolveAll("this-domain-does-not-exist.invalid");
        assertThat(results).isEmpty();
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static InetAddress addr(String ip) throws Exception {
        return InetAddress.getByName(ip);
    }
}
