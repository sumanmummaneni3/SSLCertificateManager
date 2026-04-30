package com.certguard.util;
import com.certguard.enums.HostType;

public class HostTypeDetector {
    private static final java.util.regex.Pattern IPV4 =
        java.util.regex.Pattern.compile("^(\\d{1,3}\\.){3}\\d{1,3}$");

    public static HostType detect(String host) {
        if (IPV4.matcher(host).matches()) return HostType.IP;
        if (host.contains(".")) return HostType.DOMAIN;
        return HostType.HOSTNAME;
    }

    public static boolean shouldDefaultToPrivate(String host) {
        if (!IPV4.matcher(host).matches()) return false;
        return host.startsWith("192.168.") || host.startsWith("10.")
            || host.startsWith("172.16.") || host.startsWith("172.17.")
            || host.startsWith("172.18.") || host.startsWith("172.19.")
            || host.startsWith("172.2")   || host.startsWith("172.30.")
            || host.startsWith("172.31.");
    }
}
