package com.certguard.agent.model;

import java.time.Instant;
import java.util.List;

public class ScanResult {

    public enum Type { FULL, DELTA, ERROR }

    private Type    type;
    private String  jobId;
    private String  targetId;

    // always present
    private String  serialNumber;
    private Instant notAfter;

    // FULL only
    private String       commonName;
    private String       issuer;
    private Instant      notBefore;
    private String       keyAlgorithm;
    private Integer      keySize;
    private String       signatureAlgorithm;
    private Integer      chainDepth;
    private List<String> subjectAltNames;
    private String       publicCertB64;

    // DELTA only
    private String lastCertificateId;

    // ERROR only
    private String errorMessage;

    /**
     * FULL only — full chain as base64-encoded DER, leaf at index 0 then intermediates.
     * Null for DELTA and ERROR scan types (not needed: DELTA re-uses the stored leaf).
     * Added RFC 0009 §3.4: allows server-side chain validation and revocation checking
     * without requiring the agent to contact CA endpoints.
     * Optional field: older agents that do not send it are tolerated — server falls back to
     * AIA-based completion or records INCOMPLETE_CHAIN.
     */
    private List<String> chainB64;

    public String  getScanType()           { return type != null ? type.name() : null; }
    public Type    getType()               { return type; }
    public void    setType(Type v)         { type = v; }
    public String  getJobId()              { return jobId; }
    public void    setJobId(String v)      { jobId = v; }
    public String  getTargetId()           { return targetId; }
    public void    setTargetId(String v)   { targetId = v; }
    public String  getSerialNumber()       { return serialNumber; }
    public void    setSerialNumber(String v) { serialNumber = v; }
    public Instant getNotAfter()           { return notAfter; }
    public void    setNotAfter(Instant v)  { notAfter = v; }
    public String  getCommonName()         { return commonName; }
    public void    setCommonName(String v) { commonName = v; }
    public String  getIssuer()             { return issuer; }
    public void    setIssuer(String v)     { issuer = v; }
    public Instant getNotBefore()          { return notBefore; }
    public void    setNotBefore(Instant v) { notBefore = v; }
    public String  getKeyAlgorithm()       { return keyAlgorithm; }
    public void    setKeyAlgorithm(String v) { keyAlgorithm = v; }
    public Integer getKeySize()            { return keySize; }
    public void    setKeySize(Integer v)   { keySize = v; }
    public String  getSignatureAlgorithm() { return signatureAlgorithm; }
    public void    setSignatureAlgorithm(String v) { signatureAlgorithm = v; }
    public Integer getChainDepth()         { return chainDepth; }
    public void    setChainDepth(Integer v){ chainDepth = v; }
    public List<String> getSubjectAltNames() { return subjectAltNames; }
    public void    setSubjectAltNames(List<String> v) { subjectAltNames = v; }
    public String  getPublicCertB64()      { return publicCertB64; }
    public void    setPublicCertB64(String v) { publicCertB64 = v; }
    public String  getLastCertificateId()  { return lastCertificateId; }
    public void    setLastCertificateId(String v) { lastCertificateId = v; }
    public String  getErrorMessage()       { return errorMessage; }
    public void    setErrorMessage(String v) { errorMessage = v; }

    public List<String> getChainB64()          { return chainB64; }
    public void         setChainB64(List<String> v) { chainB64 = v; }
}
