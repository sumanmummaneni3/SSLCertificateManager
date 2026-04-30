package com.certguard.security;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

@Slf4j
@Component
public class AgentCertificateAuthority {

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @Value("${app.agent.ca.cert-path:/opt/certguard/certs/agent-ca.pem}")
    private String caCertPath;

    @Value("${app.agent.ca.key-path:/opt/certguard/certs/agent-ca-key.pem}")
    private String caKeyPath;

    @Value("${app.agent.cert.validity-days:365}")
    private int clientCertValidityDays;

    private X509Certificate caCert;
    private PrivateKey caPrivateKey;

    @PostConstruct
    public void init() {
        try {
            Path certFile = Path.of(caCertPath);
            Path keyFile  = Path.of(caKeyPath);

            // Ensure parent directory exists
            Files.createDirectories(certFile.getParent());

            if (Files.exists(certFile) && Files.exists(keyFile)) {
                loadExistingCA(certFile, keyFile);
                log.info("Agent CA loaded from {}", certFile);
            } else {
                generateAndSaveCA(certFile, keyFile);
                log.info("Agent CA generated and saved to {}", certFile);
            }
        } catch (Exception e) {
            log.error("Failed to initialise Agent CA: {}", e.getMessage(), e);
            throw new RuntimeException("Agent CA init failed", e);
        }
    }

    private void generateAndSaveCA(Path certFile, Path keyFile) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", "BC");
        kpg.initialize(4096);
        KeyPair caKeyPair = kpg.generateKeyPair();

        X500Name caName = new X500Name("CN=CertGuard Agent CA,O=CertGuard,C=US");
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
        Date notBefore = Date.from(Instant.now().minus(1, ChronoUnit.HOURS));
        Date notAfter  = Date.from(Instant.now().plus(3650, ChronoUnit.DAYS));

        var builder = new JcaX509v3CertificateBuilder(
                caName, serial, notBefore, notAfter, caName, caKeyPair.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(caKeyPair.getPrivate());

        caCert = new JcaX509CertificateConverter().setProvider("BC")
                .getCertificate(builder.build(signer));
        caPrivateKey = caKeyPair.getPrivate();

        Files.writeString(certFile, toPem(caCert.getEncoded(), "CERTIFICATE"));
        Files.writeString(keyFile,  toPem(caPrivateKey.getEncoded(), "PRIVATE KEY"));

        try {
            Files.setPosixFilePermissions(keyFile,
                    PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            log.warn("Could not set file permissions on {}", keyFile);
        }
    }

    private void loadExistingCA(Path certFile, Path keyFile) throws Exception {
        String certPem = Files.readString(certFile);
        String keyPem  = Files.readString(keyFile);

        byte[] certDer = fromPem(certPem, "CERTIFICATE");
        byte[] keyDer  = fromPem(keyPem, "PRIVATE KEY");

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        caCert = (X509Certificate) cf.generateCertificate(
                new java.io.ByteArrayInputStream(certDer));

        KeyFactory kf = KeyFactory.getInstance("RSA");
        caPrivateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(keyDer));
    }

    public String issueClientCertificate(UUID agentId, UUID orgId) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", "BC");
        kpg.initialize(2048);
        KeyPair agentKeyPair = kpg.generateKeyPair();

        X500Name subject = new X500Name(
                "CN=agent-" + agentId + ",O=CertGuard,OU=" + orgId + ",C=US");
        X500Name issuer  = new X500Name(
                caCert.getSubjectX500Principal().getName());

        BigInteger serial  = new BigInteger(64, new SecureRandom());
        Date notBefore = Date.from(Instant.now().minus(1, ChronoUnit.HOURS));
        Date notAfter  = Date.from(Instant.now().plus(clientCertValidityDays, ChronoUnit.DAYS));

        var builder = new JcaX509v3CertificateBuilder(
                issuer, serial, notBefore, notAfter, subject, agentKeyPair.getPublic());
        builder.addExtension(Extension.basicConstraints, false, new BasicConstraints(false));
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        builder.addExtension(Extension.extendedKeyUsage, false,
                new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(caPrivateKey);

        X509Certificate clientCert = new JcaX509CertificateConverter()
                .setProvider("BC").getCertificate(builder.build(signer));

        return toPem(clientCert.getEncoded(), "CERTIFICATE");
    }

    public String computeFingerprint(String certPem) throws Exception {
        byte[] der = fromPem(certPem, "CERTIFICATE");
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(der);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            if (!sb.isEmpty()) sb.append(':');
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    public String getCaCertPem() throws Exception {
        return toPem(caCert.getEncoded(), "CERTIFICATE");
    }

    private String toPem(byte[] der, String type) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    private byte[] fromPem(String pem, String type) {
        String stripped = pem
                .replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s+", "");
        return Base64.getDecoder().decode(stripped);
    }
}
