package com.certguard.agent.scanner;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.x500.X500Principal;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.Socket;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Banner grabbing and device classification (RFC 0011 §4.3).
 *
 * Signals used (no root required, no SNMP, no SSH library):
 *   - Port fingerprint (which ports are open)
 *   - HTTP Server: header and page <title>
 *   - SSH version string (first plaintext line on port 22)
 *   - TLS CN / O from chain[0]
 *
 * Uses Apache HttpClient 5 (existing agent dependency) for HTTP grabs.
 */
public class DeviceClassifier {

    private static final Logger log = LoggerFactory.getLogger(DeviceClassifier.class);
    private static final int BANNER_TIMEOUT_MS = 2000;
    private static final Pattern TITLE_PATTERN  = Pattern.compile(
            "<title[^>]*>([^<]{1,200})</title>", Pattern.CASE_INSENSITIVE);

    /**
     * Classifies a host based on its open ports, banner strings, and TLS certificate.
     */
    public static DeviceClass classify(int port, Map<String, String> banners,
                                        X509Certificate[] chain) {
        String httpTitle  = banners.getOrDefault("http_title",  "").toLowerCase();
        String httpServer = banners.getOrDefault("http_server", "").toLowerCase();
        String sshVersion = banners.getOrDefault("ssh_version", "").toLowerCase();

        // ROUTER signals — network-OS keywords + classic network mgmt ports
        if (port == 161 || port == 830 || port == 23) return DeviceClass.ROUTER;
        if (containsAny(httpTitle,  "routeros", "openwrt", "pfsense",
                        "cisco",    "junos",    "mikrotik",  "fortigate",
                        "ubiquiti", "edgeos"))                return DeviceClass.ROUTER;
        if (containsAny(sshVersion, "routeros", "cisco", "junos",
                        "arista",   "hpe",      "procurve"))  return DeviceClass.ROUTER;

        // SWITCH signals
        if (containsAny(httpTitle, "procurve", "ex series", "catalyst",
                        "nexus",   "summit",   "comware"))    return DeviceClass.SWITCH;

        // SERVER signals — web server headers or common app ports
        if (containsAny(httpServer, "nginx", "apache", "iis",
                        "lighttpd", "caddy", "tomcat", "gunicorn",
                        "node",     "jetty", "kestrel"))      return DeviceClass.SERVER;
        if (port == 22 || port == 80 || port == 443
                || port == 8080 || port == 8443)              return DeviceClass.SERVER;

        // WORKSTATION signals — RDP and VNC
        if (port == 3389 || port == 5900)                     return DeviceClass.WORKSTATION;

        return DeviceClass.UNKNOWN;
    }

    /**
     * Grabs HTTP/SSH banners and TLS CN/O for a single host:port.
     * Called after TCP connect confirms the port is open.
     * No elevated privileges required.
     */
    public static Map<String, String> grabBanners(InetAddress host, int port,
                                                   X509Certificate[] chain) {
        Map<String, String> banners = new HashMap<>();

        // SSH: read first plaintext line (SSH-2.0-... handshake banner)
        if (port == 22) {
            try (Socket s = new Socket(host, port);
                 BufferedReader r = new BufferedReader(
                         new InputStreamReader(s.getInputStream()))) {
                s.setSoTimeout(BANNER_TIMEOUT_MS);
                String line = r.readLine();
                if (line != null && line.startsWith("SSH-")) {
                    banners.put("ssh_version", line.trim());
                }
            } catch (Exception ignored) {
                // Best-effort — not all hosts respond within the timeout
            }
        }

        // HTTP: plain-text ports — grab Server header and <title>
        if (port == 80 || port == 8080 || port == 8008) {
            fetchHttpBanners("http", host.getHostAddress(), port, banners);
        }

        // TLS CN / O from the certificate already obtained by the TLS probe
        if (chain != null && chain.length > 0) {
            X500Principal subject = chain[0].getSubjectX500Principal();
            String cn = extractRdn(subject.getName(), "CN");
            String o  = extractRdn(subject.getName(), "O");
            if (cn != null) banners.put("tls_cn", cn);
            if (o  != null) banners.put("tls_o",  o);
        }

        return banners;
    }

    // ── HTTP grab ─────────────────────────────────────────────────────────────

    private static void fetchHttpBanners(String scheme, String host, int port,
                                          Map<String, String> out) {
        RequestConfig config = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(BANNER_TIMEOUT_MS))
                .setResponseTimeout(Timeout.ofMilliseconds(BANNER_TIMEOUT_MS))
                .build();

        try (CloseableHttpClient client = HttpClients.custom()
                .setDefaultRequestConfig(config)
                .disableRedirectHandling()
                .build()) {

            HttpGet req = new HttpGet(scheme + "://" + host + ":" + port + "/");
            client.execute(req, response -> {
                // Server header
                var serverHeader = response.getFirstHeader("Server");
                if (serverHeader != null && serverHeader.getValue() != null) {
                    out.put("http_server", serverHeader.getValue());
                }

                // Read body (limited) for <title>
                var entity = response.getEntity();
                if (entity != null) {
                    byte[] bodyBytes = entity.getContent().readNBytes(4096);
                    String body = new String(bodyBytes);
                    Matcher m = TITLE_PATTERN.matcher(body);
                    if (m.find()) {
                        out.put("http_title", m.group(1).trim());
                    }
                }
                return null;
            });
        } catch (Exception ignored) {
            // Best-effort
        }
    }

    // ── X.500 DN helpers ──────────────────────────────────────────────────────

    /**
     * Extracts an RDN value from an RFC 2253 DN string.
     * E.g. extractRdn("CN=myrouter, O=Acme", "CN") → "myrouter"
     */
    private static String extractRdn(String dn, String type) {
        if (dn == null) return null;
        String prefix = type + "=";
        for (String part : dn.split(",")) {
            String t = part.trim();
            if (t.startsWith(prefix)) {
                return t.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    // ── String helper ─────────────────────────────────────────────────────────

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) return true;
        }
        return false;
    }
}
