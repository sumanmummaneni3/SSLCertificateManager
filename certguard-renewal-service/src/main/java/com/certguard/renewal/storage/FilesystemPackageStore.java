package com.certguard.renewal.storage;

import com.certguard.renewal.ca.CaIssuedPackage;
import com.certguard.renewal.entity.CertificatePackage;
import com.certguard.renewal.repository.PackageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

@Slf4j
@Component
public class FilesystemPackageStore implements PackageStore {

    private final Path basePath;
    private final PackageRepository packageRepository;

    public FilesystemPackageStore(
            @Value("${app.package.storage-path:/opt/certguard-renewal/packages}") String storagePath,
            PackageRepository packageRepository) throws IOException {
        this.basePath = Paths.get(storagePath);
        this.packageRepository = packageRepository;
        Files.createDirectories(basePath);
    }

    @Override
    public CertificatePackage store(UUID orgId, UUID renewalId, CaIssuedPackage issued) throws IOException {
        String content = issued.fullPem();
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        String checksum = sha256Hex(bytes);
        String fileName = "certguard-" + renewalId + ".pem";

        Path dir = basePath.resolve(orgId.toString());
        Files.createDirectories(dir);
        Path file = dir.resolve(fileName);

        Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        log.info("Certificate package stored at: {}", file);

        CertificatePackage pkg = CertificatePackage.builder()
                .orgId(orgId)
                .renewalId(renewalId)
                .storagePath(file.toString())
                .fileName(fileName)
                .sizeBytes(bytes.length)
                .checksumSha256(checksum)
                .build();

        return packageRepository.save(pkg);
    }

    @Override
    public InputStream openStream(CertificatePackage pkg) throws IOException {
        return Files.newInputStream(Path.of(pkg.getStoragePath()));
    }

    private String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
