package com.certguard.agent.scanner;

import com.certguard.agent.model.ScanJob;
import com.certguard.agent.model.ScanResult;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.net.ssl.*;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.security.*;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that SslScanner can connect to TLS 1.2 and TLS 1.3 endpoints and
 * that self-signed certificates do not cause a failure.
 *
 * Each test starts a local SSLServerSocket pinned to a specific protocol, runs
 * one accept/handshake cycle in a background thread, then drives SslScanner
 * against it on the loopback interface.
 */
class SslScannerProtocolTest {

    @BeforeAll
    static void registerBouncyCastle() {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    // ── Shared scanner instance ────────────────────────────────

    private final SslScanner scanner = new SslScanner();

    // Track server sockets opened in tests so we can close them after each test
    private SSLServerSocket lastServerSocket;
    private ExecutorService serverExecutor;

    @AfterEach
    void tearDown() throws Exception {
        if (lastServerSocket != null && !lastServerSocket.isClosed()) {
            lastServerSocket.close();
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
            serverExecutor.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    // ── Tests ──────────────────────────────────────────────────

    @Test
    void scanner_connectsTls12_extractsCertificate() throws Exception {
        KeyPair kp = generateKeyPair();
        X509Certificate cert = selfSignedCert(kp, "CN=tls12-test.local");
        SSLContext serverCtx = serverSslContext(kp, cert, "TLSv1.2");

        int port = startServer(serverCtx, new String[]{"TLSv1.2"});

        ScanResult result = runScan("127.0.0.1", port);

        assertEquals(ScanResult.Type.FULL, result.getType(),
                "Expected FULL result but got " + result.getType() +
                ": " + result.getErrorMessage());
        assertNotNull(result.getSerialNumber(), "Serial number must not be null");
        assertFalse(result.getSerialNumber().isBlank(), "Serial number must not be blank");
        assertNotNull(result.getNotAfter(), "notAfter must not be null");
        assertNotNull(result.getPublicCertB64(), "Base64 cert must not be null");
    }

    @Test
    void scanner_connectsTls13_extractsCertificate() throws Exception {
        // Only run if the JVM supports TLS 1.3
        SSLContext probe = SSLContext.getInstance("TLS");
        probe.init(null, null, null);
        boolean tls13Supported = java.util.Arrays.asList(
                probe.createSSLEngine().getSupportedProtocols()).contains("TLSv1.3");
        org.junit.jupiter.api.Assumptions.assumeTrue(tls13Supported,
                "JVM does not support TLSv1.3 — skipping");

        KeyPair kp = generateKeyPair();
        X509Certificate cert = selfSignedCert(kp, "CN=tls13-test.local");
        SSLContext serverCtx = serverSslContext(kp, cert, "TLSv1.3");

        int port = startServer(serverCtx, new String[]{"TLSv1.3"});

        ScanResult result = runScan("127.0.0.1", port);

        assertEquals(ScanResult.Type.FULL, result.getType(),
                "Expected FULL result but got " + result.getType() +
                ": " + result.getErrorMessage());
        assertNotNull(result.getSerialNumber());
        assertFalse(result.getSerialNumber().isBlank());
        assertNotNull(result.getPublicCertB64());
    }

    @Test
    void scanner_selfSignedCert_doesNotFail() throws Exception {
        // A self-signed cert with a future expiry — scanner must accept it regardless
        KeyPair kp = generateKeyPair();
        X509Certificate cert = selfSignedCert(kp, "CN=self-signed.internal");

        // Use default SSLContext protocol selection (TLS) so the test is not
        // pinned to a specific version — proves the trust-all path works
        SSLContext serverCtx = serverSslContext(kp, cert, "TLS");
        int port = startServer(serverCtx, null /* let JVM choose */);

        ScanResult result = runScan("127.0.0.1", port);

        assertNotEquals(ScanResult.Type.ERROR, result.getType(),
                "Scanner must not reject self-signed cert — error: " + result.getErrorMessage());
        assertNotNull(result.getSerialNumber());
    }

    // ── Helpers ────────────────────────────────────────────────

    /**
     * Starts an SSLServerSocket bound to a free ephemeral port.
     * Accepts exactly one connection in a background thread, completes the
     * handshake, then closes the accepted socket.
     *
     * @param ctx            server SSLContext (contains the server's certificate)
     * @param enabledProtos  if non-null, restricts the server to these protocols
     * @return               the local port the server is listening on
     */
    private int startServer(SSLContext ctx, String[] enabledProtos) throws Exception {
        SSLServerSocketFactory ssf = ctx.getServerSocketFactory();
        SSLServerSocket serverSocket = (SSLServerSocket) ssf.createServerSocket();
        serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
        serverSocket.setNeedClientAuth(false);

        if (enabledProtos != null) {
            // Intersect with actually-supported protocols to avoid IAE on restricted JVMs
            java.util.Set<String> supported = new java.util.HashSet<>(
                    java.util.Arrays.asList(serverSocket.getSupportedProtocols()));
            String[] filtered = java.util.Arrays.stream(enabledProtos)
                    .filter(supported::contains)
                    .toArray(String[]::new);
            if (filtered.length > 0) {
                serverSocket.setEnabledProtocols(filtered);
            }
        }

        lastServerSocket = serverSocket;
        int port = serverSocket.getLocalPort();

        serverExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "test-tls-server-" + port);
            t.setDaemon(true);
            return t;
        });

        serverExecutor.submit(() -> {
            // Loop accepts to handle the two-pass scanner: pass-1 may be rejected
            // (wrong protocol version), pass-2 is the successful connection.
            while (!serverSocket.isClosed()) {
                try (SSLSocket clientConn = (SSLSocket) serverSocket.accept()) {
                    clientConn.startHandshake();
                    Thread.sleep(500);
                } catch (Exception ignored) {
                    // Covers: handshake rejection on pass-1, socket closed on teardown
                }
            }
        });

        return port;
    }

    private ScanResult runScan(String host, int port) {
        ScanJob job = new ScanJob();
        job.setJobId(UUID.randomUUID().toString());
        job.setTargetId(UUID.randomUUID().toString());
        job.setHost(host);
        job.setPort(port);
        // No lastKnownSerialHash or lastCertificateId — forces a FULL scan
        return scanner.scan(job, 10);
    }

    // ── Certificate generation ─────────────────────────────────

    private static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, new SecureRandom());
        return kpg.generateKeyPair();
    }

    private static X509Certificate selfSignedCert(KeyPair kp, String dn) throws Exception {
        X500Name subject = new X500Name(dn);
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
        Date notBefore = new Date(System.currentTimeMillis() - 1000L);
        Date notAfter  = new Date(System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000);

        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, serial, notBefore, notAfter, subject, kp.getPublic());

        ContentSigner signer = new JcaContentSignerBuilder("SHA256WithRSA")
                .setProvider("BC")
                .build(kp.getPrivate());

        return new JcaX509CertificateConverter()
                .setProvider("BC")
                .getCertificate(builder.build(signer));
    }

    /**
     * Builds an SSLContext for the test server side, loaded with the given
     * self-signed cert/key pair.
     *
     * @param protocol  passed to {@code SSLContext.getInstance()} — use "TLS" for
     *                  default, "TLSv1.2" to create a context that defaults to TLS 1.2.
     *                  Note: "TLSv1.2" context still supports 1.3 on most JVMs unless
     *                  we restrict via setEnabledProtocols on the socket.
     */
    private static SSLContext serverSslContext(KeyPair kp, X509Certificate cert,
                                               String protocol) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("server", kp.getPrivate(), new char[0],
                new java.security.cert.Certificate[]{ cert });

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, new char[0]);

        // Trust-all on server side — we don't require client cert in tests
        TrustManager[] trustAll = { new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers()                               { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] c, String a)               {}
            public void checkServerTrusted(X509Certificate[] c, String a)               {}
        }};

        // Use "TLS" as context protocol regardless of the version parameter — the
        // version restriction is applied to the socket via setEnabledProtocols().
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), trustAll, new SecureRandom());
        return ctx;
    }
}
