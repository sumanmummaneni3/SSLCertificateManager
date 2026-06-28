package com.certguard.util;

import java.net.InetAddress;

/**
 * RFC 1918 private-address range checks, mirroring the logic in the agent's
 * {@code NicSubnetDiscovery.isPrivateCidr()} so the server can validate without
 * depending on the agent module.
 *
 * Ranges covered:
 * <ul>
 *   <li>10.0.0.0/8</li>
 *   <li>172.16.0.0/12</li>
 *   <li>192.168.0.0/16</li>
 * </ul>
 */
public final class Rfc1918Util {

    private static final long NET_10_0_0_0    = toLong(10,  0,  0, 0);
    private static final long NET_172_16_0_0  = toLong(172, 16, 0, 0);
    private static final long NET_192_168_0_0 = toLong(192, 168, 0, 0);

    private Rfc1918Util() {}

    /**
     * Returns {@code true} if the host portion of a CIDR string (or a bare host) falls
     * within an RFC 1918 private range.
     *
     * @param cidrOrHost IPv4 address or CIDR notation (e.g. {@code "192.168.1.0/24"})
     */
    public static boolean isRfc1918(String cidrOrHost) {
        if (cidrOrHost == null || cidrOrHost.isBlank()) return false;
        try {
            String host = cidrOrHost.contains("/") ? cidrOrHost.split("/")[0] : cidrOrHost;
            byte[] a = InetAddress.getByName(host).getAddress();
            if (a.length != 4) return false; // skip IPv6
            long ip = ((a[0] & 0xFFL) << 24)
                    | ((a[1] & 0xFFL) << 16)
                    | ((a[2] & 0xFFL) << 8)
                    |  (a[3] & 0xFFL);
            if ((ip & 0xFF000000L) == NET_10_0_0_0)   return true; // 10.0.0.0/8
            if ((ip & 0xFFF00000L) == NET_172_16_0_0)  return true; // 172.16.0.0/12
            if ((ip & 0xFFFF0000L) == NET_192_168_0_0) return true; // 192.168.0.0/16
        } catch (Exception ignored) {}
        return false;
    }

    private static long toLong(int a, int b, int c, int d) {
        return ((long) a << 24) | ((long) b << 16) | ((long) c << 8) | d;
    }
}
