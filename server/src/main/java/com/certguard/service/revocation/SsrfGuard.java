package com.certguard.service.revocation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;

/**
 * SSRF guard for OCSP and CRL URLs extracted from certificates (RFC 0009 §9).
 *
 * <p>These URLs come from the (untrusted) scanned cert. Only allow http/https
 * schemes and block requests to private/loopback/link-local ranges so a
 * maliciously crafted cert cannot point the server at internal services.
 */
public final class SsrfGuard {

    private static final Logger log = LoggerFactory.getLogger(SsrfGuard.class);

    private SsrfGuard() {}

    /**
     * Validates a URL extracted from a certificate's AIA or CDP extension.
     *
     * @throws SsrfBlockedException if the URL fails the guard.
     */
    public static void validate(String url) {
        if (url == null || url.isBlank()) throw new SsrfBlockedException("null/blank URL");

        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new SsrfBlockedException("malformed URL: " + url);
        }

        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new SsrfBlockedException("disallowed scheme '" + scheme + "' in: " + url);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new SsrfBlockedException("no host in: " + url);
        }

        // Block known private/loopback ranges without DNS resolution (fast path).
        if (isBlockedByName(host)) {
            throw new SsrfBlockedException("private/loopback host: " + host);
        }

        // Resolve and check the IP address for private ranges.
        try {
            InetAddress addr = InetAddress.getByName(host);
            if (isPrivateOrReserved(addr)) {
                throw new SsrfBlockedException("resolved to private address: " + addr.getHostAddress());
            }
        } catch (SsrfBlockedException e) {
            throw e;
        } catch (Exception e) {
            // DNS resolution failure is not treated as SSRF — it's a connectivity error.
            log.debug("SSRF guard: could not resolve {}: {}", host, e.getMessage());
        }
    }

    static boolean isBlockedByName(String host) {
        String h = host.toLowerCase();
        return h.equals("localhost")
                || h.equals("127.0.0.1")
                || h.equals("::1")
                || h.endsWith(".local")
                || h.startsWith("169.254.");   // link-local
    }

    static boolean isPrivateOrReserved(InetAddress addr) {
        return addr.isLoopbackAddress()
                || addr.isSiteLocalAddress()     // 10/8, 172.16/12, 192.168/16
                || addr.isLinkLocalAddress()     // 169.254/16
                || addr.isMulticastAddress()
                || addr.isAnyLocalAddress();
    }

    public static final class SsrfBlockedException extends RuntimeException {
        public SsrfBlockedException(String msg) { super("SSRF guard blocked: " + msg); }
    }
}
