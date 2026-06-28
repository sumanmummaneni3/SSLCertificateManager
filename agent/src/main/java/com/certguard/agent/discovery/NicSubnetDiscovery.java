package com.certguard.agent.discovery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Discovers directly-connected IPv4 subnets by inspecting the host's network interfaces.
 *
 * Only RFC1918 addresses are returned; loopback and link-local (169.254/16) are excluded.
 * No external calls, no root privileges required — uses Java standard
 * {@code NetworkInterface.getNetworkInterfaces()}.
 *
 * Privacy note (RFC 0011 §5, Part B): neither IP addresses nor MAC addresses are stored
 * by the server. This class discovers subnets only to give the sweep scanner a CIDR to work with.
 */
public class NicSubnetDiscovery {

    private static final Logger log = LoggerFactory.getLogger(NicSubnetDiscovery.class);

    /** RFC1918 private ranges that are safe to return. */
    private static final long NET_10_0_0_0     = toLong(10, 0, 0, 0);
    private static final long NET_172_16_0_0   = toLong(172, 16, 0, 0);
    private static final long NET_192_168_0_0  = toLong(192, 168, 0, 0);

    /**
     * Enumerates all network interfaces, skips loopback and link-local,
     * and returns SubnetInfo records for each private IPv4 subnet found.
     */
    public List<SubnetInfo> discoverSubnets() {
        List<SubnetInfo> subnets = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            if (nics == null) {
                log.warn("No network interfaces found");
                return subnets;
            }
            while (nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                    continue;
                }
                for (InterfaceAddress ia : nic.getInterfaceAddresses()) {
                    InetAddress addr = ia.getAddress();
                    if (!(addr instanceof Inet4Address)) {
                        continue;  // skip IPv6
                    }
                    if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()) {
                        continue;  // 127.x and 169.254.x
                    }
                    String cidr = toCidr(addr, ia.getNetworkPrefixLength());
                    if (!isPrivateCidr(cidr)) {
                        log.debug("Skipping non-private subnet {} on {}", cidr, nic.getName());
                        continue;
                    }
                    log.info("Discovered private subnet {} on {}", cidr, nic.getName());
                    subnets.add(new SubnetInfo(cidr, nic.getName()));
                }
            }
        } catch (SocketException e) {
            log.error("Failed to enumerate network interfaces: {}", e.getMessage());
        }
        return subnets;
    }

    /**
     * Converts a host address + prefix length to the network CIDR string.
     * E.g. (192.168.1.100, 24) → "192.168.1.0/24"
     */
    static String toCidr(InetAddress addr, short prefixLength) {
        byte[] raw = addr.getAddress();
        // Mask the host part to get network address
        int mask = prefixLength == 0 ? 0 : (0xFFFFFFFF << (32 - prefixLength));
        int network = ((raw[0] & 0xFF) << 24)
                    | ((raw[1] & 0xFF) << 16)
                    | ((raw[2] & 0xFF) << 8)
                    |  (raw[3] & 0xFF);
        int masked = network & mask;
        return String.format("%d.%d.%d.%d/%d",
                (masked >> 24) & 0xFF,
                (masked >> 16) & 0xFF,
                (masked >> 8)  & 0xFF,
                 masked        & 0xFF,
                prefixLength);
    }

    /**
     * Returns true if the network address of the CIDR falls within an RFC1918 range.
     */
    static boolean isPrivateCidr(String cidr) {
        try {
            String host = cidr.split("/")[0];
            byte[] a = InetAddress.getByName(host).getAddress();
            long ip = ((a[0] & 0xFFL) << 24)
                    | ((a[1] & 0xFFL) << 16)
                    | ((a[2] & 0xFFL) << 8)
                    |  (a[3] & 0xFFL);
            // 10.0.0.0/8
            if ((ip & 0xFF000000L) == NET_10_0_0_0)   return true;
            // 172.16.0.0/12
            if ((ip & 0xFFF00000L) == NET_172_16_0_0)  return true;
            // 192.168.0.0/16
            if ((ip & 0xFFFF0000L) == NET_192_168_0_0) return true;
        } catch (Exception ignored) {}
        return false;
    }

    private static long toLong(int a, int b, int c, int d) {
        return ((long) a << 24) | ((long) b << 16) | ((long) c << 8) | d;
    }

    // ── Value type ────────────────────────────────────────────────────────────

    /**
     * A discovered subnet, containing only CIDR and interface name.
     * No IP addresses or MAC addresses are stored.
     */
    public record SubnetInfo(String cidr, String ifaceName) {}
}
