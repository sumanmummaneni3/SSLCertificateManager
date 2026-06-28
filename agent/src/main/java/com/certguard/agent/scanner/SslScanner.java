package com.certguard.agent.scanner;

import com.certguard.agent.model.ScanJob;
import com.certguard.agent.model.ScanResult;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
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
        // BC JSSE is NOT registered globally — we instantiate it directly only for
        // pass-1 (scanCtxBcJsse).  Adding it globally would let it intercept
        // SSLContext.getInstance("TLS") and interfere with pass-2.
    }

    // In-memory serial cache: targetId -> last scanned serial number
    private final Map<String, String> serialCache = new ConcurrentHashMap<>();

    /**
     * Probes host:port for TLS without using the targetId-keyed serial cache.
     * Used by PortSweepScanner to test discovered endpoints that have no registered Target.
     *
     * Returns the peer certificate chain if the TLS handshake succeeds.
     * Returns null if the connection succeeds but TLS handshake fails (OPEN_NO_TLS signal).
     * Returns null on any connection-level error (caller already confirmed TCP is open).
     */
    public X509Certificate[] probe(String host, int port) {
        return probe(host, port, 3);
    }

    /**
     * Probes host:port for TLS with a configurable timeout in seconds.
     * Reuses the two-pass BC JSSE + JVM TLS 1.3 handshake logic but bypasses
     * the targetId serial cache entirely (discovered endpoints have no Target).
     */
    public X509Certificate[] probe(String host, int port, int timeoutSeconds) {
        try {
            return fetchChain(host, port, timeoutSeconds);
        } catch (javax.net.ssl.SSLException e) {
            // Port is open (TCP succeeded) but TLS handshake failed → OPEN_NO_TLS
            log.debug("TLS probe — no TLS on {}:{}: {}", host, port, e.getMessage());
            return null;
        } catch (java.io.IOException e) {
            // Connection error during handshake — treat as non-TLS
            log.debug("TLS probe — I/O error on {}:{}: {}", host, port, e.getMessage());
            return null;
        } catch (Exception e) {
            log.debug("TLS probe — failed on {}:{}: {}", host, port, e.getMessage());
            return null;
        }
    }

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

    /**
     * Pass-1 protocols: TLS 1.2 and below only.
     * Offering TLS 1.3 in the ClientHello adds key_share / supported_versions /
     * psk_key_exchange_modes extensions that crash many router/NAS/IPMI TLS stacks
     * (handshake_failure), even though those devices speak TLS 1.2 fine.
     * Pass-2 retries with TLS 1.3 for the rare server that has dropped TLS 1.2.
     */
    private static final String[] SCAN_PROTOCOLS_PASS1 = { "TLSv1.2", "TLSv1.1", "TLSv1" };
    private static final String[] SCAN_PROTOCOLS_PASS2 = { "TLSv1.3" };

    /**
     * CVE-audited cipher list for TLS 1.2 scanning.
     *
     * Priority order: ECDHE (PFS + AEAD) → DHE (PFS) → RSA (no PFS, needed for
     * embedded devices that lack ECDHE/DHE).
     *
     * EXCLUDED (with CVE references):
     *   RC4    — CVE-2015-2808 / CVE-2013-2566 (statistical bias, bar-mitzvah)
     *   3DES   — CVE-2016-2183 (SWEET32, 64-bit block birthday attack)
     *   NULL   — no encryption
     *   ANON   — CVE-2014-3569, LOGJAM (no server authentication)
     *   EXPORT — CVE-2015-0204 (FREAK, deliberately weak key lengths)
     *   MD5-MAC— CVE-2004-2761 (MD5 collision)
     *
     * RSA key-exchange ciphers (TLS_RSA_WITH_AES_*) lack forward secrecy, which
     * is a design weakness but NOT a CVE.  They are included because many
     * embedded devices (routers, printers, IPMIs) only support RSA key exchange.
     *
     * DHE: LOGJAM (CVE-2015-4000) affects only DH params < 1024-bit.
     * JDK enforces a minimum of 2048-bit DH, so DHE ciphers here are safe.
     */
    private static final String[] TLS12_SCAN_CIPHERS = {
        // ── ECDHE — forward secrecy, AEAD ─────────────────────
        "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384",
        "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
        "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
        "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384",
        "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA",
        "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA",
        // ── DHE — forward secrecy ─────────────────────────────
        "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384",
        "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_DHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
        "TLS_DHE_RSA_WITH_AES_256_CBC_SHA256",
        "TLS_DHE_RSA_WITH_AES_128_CBC_SHA256",
        "TLS_DHE_RSA_WITH_AES_256_CBC_SHA",
        "TLS_DHE_RSA_WITH_AES_128_CBC_SHA",
        // ── RSA key exchange — no PFS, required for embedded devices ──
        "TLS_RSA_WITH_AES_256_GCM_SHA384",
        "TLS_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_RSA_WITH_AES_256_CBC_SHA256",
        "TLS_RSA_WITH_AES_128_CBC_SHA256",
        "TLS_RSA_WITH_AES_256_CBC_SHA",
        "TLS_RSA_WITH_AES_128_CBC_SHA",
        // Secure-renegotiation signaling pseudo-suite (prevents CVE-2009-3555)
        "TLS_EMPTY_RENEGOTIATION_INFO_SCSV",
    };

    /**
     * Lazily-initialised scanning SSLContexts — trust-all, no key material.
     *
     * Two contexts:
     *   SCAN_CTX_BCJSSE  — BouncyCastle JSSE provider (pass-1: TLS 1.2 + RSA key-exchange)
     *   SCAN_CTX_JVM     — JVM default JSSE provider  (pass-2: TLS 1.3)
     *
     * JDK 21+ removed TLS_RSA_WITH_* cipher suites from its JSSE implementation.
     * Many embedded devices (routers, NAS, printers) only support RSA key exchange
     * (e.g. AES256-GCM-SHA384 / TLS_RSA_WITH_AES_256_GCM_SHA384).
     * BouncyCastle JSSE still supports those ciphers, so pass-1 uses it.
     */
    private static volatile SSLContext SCAN_CTX_BCJSSE;
    private static volatile SSLContext SCAN_CTX_JVM;

    private static SSLContext scanCtxBcJsse() throws Exception {
        if (SCAN_CTX_BCJSSE == null) {
            synchronized (SslScanner.class) {
                if (SCAN_CTX_BCJSSE == null) {
                    TrustManager[] trustAll = buildTrustAll();
                    // Instantiate BC JSSE directly — NOT via Security.addProvider — so it
                    // doesn't shadow the JVM's SunJSSE for SSLContext.getInstance("TLS").
                    SSLContext ctx = SSLContext.getInstance("TLS", new BouncyCastleJsseProvider());
                    ctx.init(null, trustAll, new SecureRandom());
                    SCAN_CTX_BCJSSE = ctx;
                }
            }
        }
        return SCAN_CTX_BCJSSE;
    }

    private static SSLContext scanCtxJvm() throws Exception {
        if (SCAN_CTX_JVM == null) {
            synchronized (SslScanner.class) {
                if (SCAN_CTX_JVM == null) {
                    TrustManager[] trustAll = buildTrustAll();
                    SSLContext ctx = SSLContext.getInstance("TLS");
                    ctx.init(null, trustAll, new SecureRandom());
                    SCAN_CTX_JVM = ctx;
                }
            }
        }
        return SCAN_CTX_JVM;
    }

    private static TrustManager[] buildTrustAll() {
        return new TrustManager[]{ new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers()             { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
        }};
    }

    /**
     * Pass-1: BC JSSE, TLS 1.2 only + CVE-audited cipher list including RSA key-exchange.
     *   – Avoids TLS 1.3 extensions that crash old router/NAS/IPMI firmware.
     *   – BC JSSE supports TLS_RSA_WITH_* ciphers removed from JDK 21+.
     * Pass-2: JVM JSSE, TLS 1.3 only — for servers that have dropped TLS 1.2.
     */
    private X509Certificate[] fetchChain(String host, int port, int timeoutSeconds) throws Exception {
        try {
            return fetchChainWithProtocols(host, port, timeoutSeconds,
                    SCAN_PROTOCOLS_PASS1, TLS12_SCAN_CIPHERS, scanCtxBcJsse());
        } catch (Exception e) {
            // Retry with TLS 1.3 on any pass-1 failure.  BC JSSE may throw SSLException
            // or a subclass for handshake alerts (protocol_version, handshake_failure, etc.).
            // I/O failures (connect timeout, connection refused) also retry — TLS 1.3 won't
            // help in that case but the failure will still be reported correctly.
            log.info("Pass-1 (TLS 1.2/BC) failed for {}:{} — retrying with TLS 1.3: {}",
                    host, port, e.getMessage());
            return fetchChainWithProtocols(host, port, timeoutSeconds,
                    SCAN_PROTOCOLS_PASS2, null, scanCtxJvm());
        }
    }

    private X509Certificate[] fetchChainWithProtocols(String host, int port, int timeoutSeconds,
                                                       String[] protocols, String[] ciphers,
                                                       SSLContext ctx) throws Exception {
        int timeoutMs = timeoutSeconds * 1000;

        try (Socket raw = new Socket()) {
            raw.connect(new InetSocketAddress(host, port), timeoutMs);
            try (SSLSocket ssl = (SSLSocket) ctx.getSocketFactory()
                    .createSocket(raw, host, port, true)) {

                ssl.setSoTimeout(timeoutMs);
                enableScanProtocols(ssl, protocols, ciphers);

                // SNI for domain-based hosts
                if (!host.matches("(\\d{1,3}\\.){3}\\d{1,3}")) {
                    SSLParameters params = ssl.getSSLParameters();
                    params.setServerNames(List.of(new SNIHostName(host)));
                    ssl.setSSLParameters(params);
                }

                ssl.startHandshake();
                log.debug("Connected to {}:{} using {} / {}", host, port,
                        ssl.getSession().getProtocol(),
                        ssl.getSession().getCipherSuite());
                return (X509Certificate[]) ssl.getSession().getPeerCertificates();
            }
        }
    }

    /**
     * Intersects the requested protocol list with what the JVM actually supports,
     * then optionally applies an explicit CVE-audited cipher list (TLS 1.2 pass only).
     * For TLS 1.3 (pass-2), ciphers is null — the JVM manages TLS 1.3 cipher sets
     * internally and ignores setEnabledCipherSuites for TLS_AES_* suites.
     */
    private static void enableScanProtocols(SSLSocket ssl, String[] protocols, String[] ciphers) {
        Set<String> supportedProtos = new HashSet<>(Arrays.asList(ssl.getSupportedProtocols()));
        String[] toEnable = Arrays.stream(protocols)
                .filter(supportedProtos::contains)
                .toArray(String[]::new);
        if (toEnable.length > 0) {
            ssl.setEnabledProtocols(toEnable);
        }

        if (ciphers != null) {
            Set<String> supportedCiphers = new HashSet<>(Arrays.asList(ssl.getSupportedCipherSuites()));
            String[] ciphersToEnable = Arrays.stream(ciphers)
                    .filter(supportedCiphers::contains)
                    .toArray(String[]::new);
            if (ciphersToEnable.length > 0) {
                ssl.setEnabledCipherSuites(ciphersToEnable);
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

        // RFC 0009 §3.4 — send full chain (leaf first then intermediates) so the server
        // can perform chain validation and revocation checking without network access to
        // private CA endpoints from the server side. DELTA leaves chainB64 null (no change).
        List<String> chainB64 = new ArrayList<>();
        for (X509Certificate cert : chain) {
            chainB64.add(Base64.getEncoder().encodeToString(cert.getEncoded()));
        }
        r.setChainB64(chainB64);

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
