package com.certguard.agent.http;

import com.certguard.agent.config.AgentConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.DefaultHostnameVerifier;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

/**
 * Builds an Apache HttpClient 5 with:
 *  - TLS 1.3 only
 *  - Strong cipher suites only
 *  - Server cert fingerprint pinning (or trust-all in dev mode)
 *
 * Authentication is provided by bearer agentKey + HMAC, not mTLS.
 * Uses plain JDK SSLContext + X509TrustManager — no HC5 TrustStrategy needed.
 */
public class SecureHttpClient {

    private static final Logger log = LoggerFactory.getLogger(SecureHttpClient.class);

    private static final String[] CIPHER_SUITES = {
        "TLS_AES_256_GCM_SHA384",
        "TLS_CHACHA20_POLY1305_SHA256",
        "TLS_AES_128_GCM_SHA256"
    };

    private final AgentConfig config;

    public SecureHttpClient(AgentConfig config) {
        this.config = config;
    }

    public CloseableHttpClient build() throws Exception {
        SSLContext sslContext = buildSslContext();
        String pinned = config.serverCertFingerprint();

        // Use DefaultHostnameVerifier when a fingerprint is configured (production).
        // The fingerprint pin already authenticates the server cryptographically;
        // hostname verification adds an additional layer. Only fall back to noop in
        // dev/trust-all mode where no fingerprint is set.
        var hostnameVerifier = (!pinned.isBlank())
                ? new DefaultHostnameVerifier()
                : org.apache.hc.client5.http.ssl.NoopHostnameVerifier.INSTANCE;

        if (pinned.isBlank()) {
            log.warn("No certguard.server.cert-fingerprint configured — hostname verification disabled. " +
                     "Set the fingerprint for production use.");
        }

        var sslSocketFactory = SSLConnectionSocketFactoryBuilder.create()
                .setSslContext(sslContext)
                .setTlsVersions(org.apache.hc.core5.http.ssl.TLS.V_1_3)
                .setCiphers(CIPHER_SUITES)
                .setHostnameVerifier(hostnameVerifier)
                .build();

        var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setSSLSocketFactory(sslSocketFactory)
                .build();

        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .disableRedirectHandling()
                .build();
    }

    private SSLContext buildSslContext() throws Exception {
        TrustManager[] trustManagers = buildTrustManagers();

        SSLContext ctx = SSLContext.getInstance("TLSv1.3");
        ctx.init(null, trustManagers, new SecureRandom());
        return ctx;
    }

    private TrustManager[] buildTrustManagers() {
        String pinned = config.serverCertFingerprint();

        if (config.trustSelfSigned() && pinned.isBlank()) {
            log.warn("TLS certificate verification DISABLED. " +
                     "Set certguard.server.cert-fingerprint for production.");
            return new TrustManager[]{ new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers()                 { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
            }};
        }

        return new TrustManager[]{ new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                if (chain == null || chain.length == 0)
                    throw new CertificateException("No server certificate presented");
                String serverFp = fingerprint(chain[0]);
                if (!constantTimeEquals(serverFp, pinned)) {
                    throw new CertificateException(
                        "Server cert fingerprint MISMATCH\nExpected: " + pinned +
                        "\nGot: " + serverFp);
                }
                log.debug("Server cert fingerprint verified OK");
            }
        }};
    }

    private String fingerprint(X509Certificate cert) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(cert.getEncoded());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < digest.length; i++) {
                if (i > 0) sb.append(':');
                sb.append(String.format("%02X", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] ab = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        if (ab.length != bb.length) return false;
        int diff = 0;
        for (int i = 0; i < ab.length; i++) diff |= ab[i] ^ bb[i];
        return diff == 0;
    }

}
