package com.certguard.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * SSRF guard for PUBLIC_POOL scan targets (RFC 0013 §4).
 *
 * <p>A malicious or compromised tenant could add a "public" target pointing to
 * an internal service (e.g. 169.254.169.254, 10.0.0.5) hoping our platform
 * scanner dials it from inside the VPC. This guard rejects any hostname whose
 * DNS records resolve to a disallowed address.
 *
 * <p><strong>Do NOT reuse NicSubnetDiscovery.isPrivateCidr or Rfc1918Util</strong>
 * — both cover only RFC 1918 and fail open on exactly the addresses that matter:
 * link-local (169.254.0.0/16), loopback, CGNAT 100.64.0.0/10, IPv6 ULA, etc.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Resolve ALL A/AAAA records for the hostname. A single-record check is
 *       DNS-rebinding bait — if any resolved address is disallowed, reject.
 *   <li>For IPv6, unwrap IPv4-mapped addresses (::ffff:a.b.c.d) before classification.
 *   <li>Reject loopback, link-local, site-local, any-local, multicast (JDK predicates).
 *   <li>Also reject explicit ranges the JDK predicates miss:
 *       100.64.0.0/10 (CGNAT), 169.254.0.0/16 (belt-and-braces), IPv6 ULA fc00::/7.
 * </ol>
 *
 * <p>This class is deliberately dependency-free (no Spring, no Lombok) so the same
 * source file can be copied verbatim into the agent module (which forbids framework deps).
 * RFC 0013 §4: "one implementation, shared verbatim between server and agent modules".
 */
public final class PublicAddressGuard {

    private static final Logger log = LoggerFactory.getLogger(PublicAddressGuard.class);

    private PublicAddressGuard() {}

    /**
     * Asserts that the hostname resolves only to globally routable addresses.
     *
     * @param hostname the hostname or IP literal to validate
     * @throws PublicAddressGuardException if any resolved address is disallowed
     */
    public static void assertPubliclyRoutable(String hostname) {
        if (hostname == null || hostname.isBlank()) {
            throw new PublicAddressGuardException("hostname is null or blank");
        }

        List<InetAddress> resolved = resolveAll(hostname);
        if (resolved.isEmpty()) {
            // DNS failure is not a security event — but block to be safe (fail-closed).
            throw new PublicAddressGuardException(
                    "hostname '" + hostname + "' did not resolve to any address (DNS failure)");
        }

        for (InetAddress addr : resolved) {
            // Unwrap IPv4-mapped IPv6 (::ffff:a.b.c.d) before classification.
            InetAddress effective = unwrapIpv4Mapped(addr);
            if (isDisallowed(effective)) {
                throw new PublicAddressGuardException(
                        "hostname '" + hostname + "' resolved to disallowed address "
                        + addr.getHostAddress() + " (effective: " + effective.getHostAddress() + ")");
            }
        }
    }

    // ── Resolution ────────────────────────────────────────────────────────────

    /**
     * Resolves ALL A/AAAA records for the hostname.
     * InetAddress.getAllByName handles both hostnames and IP literals.
     */
    static List<InetAddress> resolveAll(String hostname) {
        try {
            InetAddress[] addrs = InetAddress.getAllByName(hostname);
            List<InetAddress> result = new ArrayList<>(addrs.length);
            for (InetAddress a : addrs) {
                result.add(a);
            }
            return result;
        } catch (UnknownHostException e) {
            log.debug("PublicAddressGuard: DNS resolution failed for '{}': {}", hostname, e.getMessage());
            return List.of();
        }
    }

    // ── IPv4-mapped IPv6 unwrapping ────────────────────────────────────────────

    /**
     * Unwraps an IPv4-mapped IPv6 address (::ffff:a.b.c.d) to its Inet4Address equivalent.
     * Other addresses are returned unchanged.
     */
    static InetAddress unwrapIpv4Mapped(InetAddress addr) {
        if (!(addr instanceof Inet6Address)) return addr;
        byte[] raw = addr.getAddress(); // 16 bytes for IPv6
        // IPv4-mapped: bytes 0-9 are 0x00, bytes 10-11 are 0xFF
        if (raw.length == 16
                && raw[0] == 0 && raw[1] == 0 && raw[2] == 0 && raw[3] == 0
                && raw[4] == 0 && raw[5] == 0 && raw[6] == 0 && raw[7] == 0
                && raw[8] == 0 && raw[9] == 0
                && raw[10] == (byte) 0xFF && raw[11] == (byte) 0xFF) {
            // Extract the IPv4 part (bytes 12-15)
            byte[] v4 = new byte[]{ raw[12], raw[13], raw[14], raw[15] };
            try {
                return InetAddress.getByAddress(v4);
            } catch (UnknownHostException e) {
                // Shouldn't happen with a 4-byte array, but fall through.
            }
        }
        return addr;
    }

    // ── Address classification ────────────────────────────────────────────────

    /**
     * Returns true if the address must NOT be dialled by the platform scanner.
     *
     * <p>Covers:
     * <ul>
     *   <li>JDK primitives: loopback, link-local, site-local, any-local, multicast.
     *   <li>CGNAT 100.64.0.0/10 — shared address space (RFC 6598), reachable inside ISP NAT.
     *   <li>169.254.0.0/16 — belt-and-braces in addition to isLinkLocalAddress()
     *       because cloud metadata services live here.
     *   <li>IPv6 ULA fc00::/7 — unique local addresses (RFC 4193).
     * </ul>
     */
    static boolean isDisallowed(InetAddress addr) {
        if (addr.isLoopbackAddress())   return true;  // 127.0.0.0/8, ::1
        if (addr.isLinkLocalAddress())  return true;  // 169.254.0.0/16, fe80::/10
        if (addr.isSiteLocalAddress())  return true;  // 10/8, 172.16/12, 192.168/16
        if (addr.isAnyLocalAddress())   return true;  // 0.0.0.0, ::
        if (addr.isMulticastAddress())  return true;  // 224.0.0.0/4, ff00::/8

        if (addr instanceof Inet4Address) {
            return isInCgnat(addr) || isLinkLocal169(addr);
        }

        if (addr instanceof Inet6Address) {
            return isIpv6Ula(addr);
        }

        return false;
    }

    /** 100.64.0.0/10 — CGNAT (RFC 6598). isSiteLocalAddress() does NOT cover this. */
    static boolean isInCgnat(InetAddress addr) {
        byte[] b = addr.getAddress();
        if (b.length != 4) return false;
        int first  = b[0] & 0xFF;  // 100
        int second = b[1] & 0xFF;
        // 100.64.0.0/10: first byte = 100, second byte 64-127
        return first == 100 && second >= 64 && second <= 127;
    }

    /** 169.254.0.0/16 — belt-and-braces alongside isLinkLocalAddress(). */
    static boolean isLinkLocal169(InetAddress addr) {
        byte[] b = addr.getAddress();
        if (b.length != 4) return false;
        return (b[0] & 0xFF) == 169 && (b[1] & 0xFF) == 254;
    }

    /** fc00::/7 — IPv6 Unique Local Addresses (RFC 4193). isSiteLocalAddress() does NOT cover this. */
    static boolean isIpv6Ula(InetAddress addr) {
        byte[] b = addr.getAddress();
        if (b.length != 16) return false;
        // fc00::/7: first byte has the high 7 bits matching 1111110x -> 0xFC or 0xFD
        int firstByte = b[0] & 0xFF;
        return (firstByte & 0xFE) == 0xFC;  // matches 0xFC (11111100) and 0xFD (11111101)
    }

    // ── Exception ─────────────────────────────────────────────────────────────

    /** Thrown when a hostname resolves to a disallowed (non-public) address. */
    public static final class PublicAddressGuardException extends RuntimeException {
        public PublicAddressGuardException(String msg) {
            super("PublicAddressGuard rejected: " + msg);
        }
    }
}
