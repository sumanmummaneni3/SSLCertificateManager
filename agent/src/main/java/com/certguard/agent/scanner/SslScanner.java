package com.certguard.agent.scanner;

import com.certguard.agent.model.ScanJob;
import com.certguard.agent.model.ScanResult;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Scans an SSL/TLS endpoint and returns a ScanResult.
 *
 * Tracks last-known serial number per targetId in memory.
 * If the server already has the same serial (serverSerialHash matches),
 * returns a DELTA result — only expiry + serial, no full cert data.
 * Otherwise returns a FULL result with all certificate fields.
 */
public class SslScanner {

    private static final Logger log = LoggerFactory.getLogger(SslScanner.class);

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    // In-memory serial cache: targetId -> last scanned serial number
    private final Map<String, String> serialCache = new ConcurrentHashMap<>();

    public ScanResult scan(ScanJob job, int timeoutSeconds) {
        log.info("Scanning {}:{} (job: {})", job.getHost(), job.getPort(), job.getJobId());

        try {
            X509Certificate[] chain = fetchChain(job.getHost(), job.getPort(), timeoutSeconds);

            if (chain == null || chain.length == 0) {
                return error(job, "No certificate returned");
            }

            X509Certificate leaf = chain[0];
            String serial   = leaf.getSerialNumber().toString(16).toUpperCase();
            Instant notAfter = leaf.getNotAfter().toInstant();

            // FULL vs DELTA decision:
            // DELTA only when BOTH the server AND this agent's cache agree on the serial
            String cachedSerial  = serialCache.get(job.getTargetId());
            String serialHash    = sha256Hex(serial);
            boolean serverHasIt  = job.getLastKnownSerialHash() != null
                                   && job.getLastKnownSerialHash().equals(serialHash);
            boolean agentHasIt   = serial.equals(cachedSerial);

            if (serverHasIt && agentHasIt && job.getLastCertificateId() != null) {
                log.info("DELTA — serial unchanged for {}:{}", job.getHost(), job.getPort());
                serialCache.put(job.getTargetId(), serial);
                return delta(job, serial, notAfter);
            }

            // FULL scan
            log.info("FULL — new or changed cert for {}:{}", job.getHost(), job.getPort());
            serialCache.put(job.getTargetId(), serial);
            return full(job, leaf, chain, serial, notAfter);

        } catch (Exception e) {
            log.error("Scan failed for {}:{} — {}", job.getHost(), job.getPort(), e.getMessage());
            return error(job, e.getMessage());
        }
    }

    // ── SSL connection ────────────────────────────────────────

    private X509Certificate[] fetchChain(String host, int port, int timeoutSeconds) throws Exception {
        // Trust-all TrustManager for scanning — we are inspecting certs, not verifying them
        TrustManager[] trustAll = { new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers()                               { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] c, String a)               {}
            public void checkServerTrusted(X509Certificate[] c, String a)               {}
        }};

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, trustAll, new SecureRandom());

        int timeoutMs = timeoutSeconds * 1000;

        try (Socket raw = new Socket()) {
            raw.connect(new InetSocketAddress(host, port), timeoutMs);
            try (SSLSocket ssl = (SSLSocket) ctx.getSocketFactory()
                    .createSocket(raw, host, port, true)) {

                ssl.setSoTimeout(timeoutMs);

                // SNI for domain-based hosts
                if (!host.matches("(\\d{1,3}\\.){3}\\d{1,3}")) {
                    SSLParameters params = ssl.getSSLParameters();
                    params.setServerNames(List.of(new SNIHostName(host)));
                    ssl.setSSLParameters(params);
                }

                ssl.startHandshake();
                return (X509Certificate[]) ssl.getSession().getPeerCertificates();
            }
        }
    }

    // ── Result builders ───────────────────────────────────────

    private ScanResult full(ScanJob job, X509Certificate leaf,
                            X509Certificate[] chain, String serial, Instant notAfter) throws Exception {
        ScanResult r = new ScanResult();
        r.setType(ScanResult.Type.FULL);
        r.setJobId(job.getJobId());
        r.setTargetId(job.getTargetId());
        r.setSerialNumber(serial);
        r.setNotAfter(notAfter);
        r.setNotBefore(leaf.getNotBefore().toInstant());
        r.setCommonName(extractCN(leaf.getSubjectX500Principal().getName()));
        r.setIssuer(leaf.getIssuerX500Principal().getName());
        r.setKeyAlgorithm(leaf.getPublicKey().getAlgorithm());
        r.setKeySize(keySize(leaf.getPublicKey()));
        r.setSignatureAlgorithm(leaf.getSigAlgName());
        r.setChainDepth(chain.length);
        r.setSubjectAltNames(extractSANs(leaf));
        r.setPublicCertB64(Base64.getEncoder().encodeToString(leaf.getEncoded()));
        return r;
    }

    private ScanResult delta(ScanJob job, String serial, Instant notAfter) {
        ScanResult r = new ScanResult();
        r.setType(ScanResult.Type.DELTA);
        r.setJobId(job.getJobId());
        r.setTargetId(job.getTargetId());
        r.setSerialNumber(serial);
        r.setNotAfter(notAfter);
        r.setLastCertificateId(job.getLastCertificateId());
        return r;
    }

    private ScanResult error(ScanJob job, String message) {
        ScanResult r = new ScanResult();
        r.setType(ScanResult.Type.ERROR);
        r.setJobId(job.getJobId());
        r.setTargetId(job.getTargetId());
        r.setErrorMessage(message);
        return r;
    }

    // ── Helpers ───────────────────────────────────────────────

    private String extractCN(String dn) {
        for (String part : dn.split(",")) {
            String t = part.trim();
            if (t.startsWith("CN=")) return t.substring(3);
        }
        return dn;
    }

    private int keySize(PublicKey key) {
        if (key instanceof java.security.interfaces.RSAPublicKey rsa)
            return rsa.getModulus().bitLength();
        if (key instanceof java.security.interfaces.ECPublicKey ec)
            return ec.getParams().getCurve().getField().getFieldSize();
        return 0;
    }

    @SuppressWarnings("unchecked")
    private List<String> extractSANs(X509Certificate cert) {
        try {
            Collection<List<?>> sans = cert.getSubjectAlternativeNames();
            if (sans == null) return List.of();
            return sans.stream()
                    .filter(s -> s.size() >= 2)
                    .map(s -> {
                        int type = ((Number) s.get(0)).intValue();
                        return (type == 2 ? "DNS:" : type == 7 ? "IP:" : "OTHER:") + s.get(1);
                    })
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return input;
        }
    }
}
