package com.certguard.renewal.storage;

import com.certguard.renewal.ca.CaIssuedPackage;
import com.certguard.renewal.entity.CertificatePackage;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

public interface PackageStore {
    CertificatePackage store(UUID orgId, UUID renewalId, CaIssuedPackage issued) throws IOException;
    InputStream openStream(CertificatePackage pkg) throws IOException;
}
