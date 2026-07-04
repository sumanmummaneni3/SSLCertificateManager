package com.certguard.agent.model;

public class ScanJob {

    /**
     * RFC 0013 §4.4: job kind discriminator — "AGENT_PINNED" or "PUBLIC_POOL".
     * PUBLIC_POOL is the authoritative signal that the target-side PublicAddressGuard
     * SSRF check must run before dialing (see PollLoop.processCertScanJob). Null/absent
     * is treated as AGENT_PINNED for backward compatibility.
     */
    public static final String JOB_KIND_PUBLIC_POOL = "PUBLIC_POOL";

    private String jobId;
    private String targetId;
    private String host;
    private int    port;
    private String lastKnownSerialHash;   // SHA-256 of last serial server has
    private String lastCertificateId;     // UUID of last cert record on server
    private String jobKind;               // RFC 0013 §4.4 — "AGENT_PINNED" | "PUBLIC_POOL" | null

    // RFC 0011 — job routing
    // null / "CERTIFICATE_SCAN" → existing TLS scan flow
    // "NETWORK_SCAN"            → PortSweepScanner flow
    // "DISCOVERY"               → Anonymous NicSubnetDiscovery flow
    private String jobType;

    // RFC 0011 — NETWORK_SCAN payload (non-null when jobType == "NETWORK_SCAN")
    private String networkScanId;
    private String cidr;
    private String portProfile;           // COMMON_TLS | EXTENDED | FULL | CUSTOM
    private java.util.List<Integer> customPorts;
    private int connectTimeoutMs = 500;
    private int tlsTimeoutMs     = 3000;

    public String getJobId()               { return jobId; }
    public void   setJobId(String v)       { jobId = v; }
    public String getTargetId()            { return targetId; }
    public void   setTargetId(String v)    { targetId = v; }
    public String getHost()                { return host; }
    public void   setHost(String v)        { host = v; }
    public int    getPort()                { return port; }
    public void   setPort(int v)           { port = v; }
    public String getLastKnownSerialHash() { return lastKnownSerialHash; }
    public void   setLastKnownSerialHash(String v) { lastKnownSerialHash = v; }
    public String getLastCertificateId()   { return lastCertificateId; }
    public void   setLastCertificateId(String v)   { lastCertificateId = v; }
    public String getJobKind()             { return jobKind; }
    public void   setJobKind(String v)     { jobKind = v; }

    /** True iff this job was claimed from the shared PUBLIC_POOL (RFC 0013 §4.4). */
    public boolean isPublicPoolJob()       { return JOB_KIND_PUBLIC_POOL.equalsIgnoreCase(jobKind); }

    public String getJobType()             { return jobType; }
    public void   setJobType(String v)     { jobType = v; }
    public String getNetworkScanId()       { return networkScanId; }
    public void   setNetworkScanId(String v) { networkScanId = v; }
    public String getCidr()                { return cidr; }
    public void   setCidr(String v)        { cidr = v; }
    public String getPortProfile()         { return portProfile; }
    public void   setPortProfile(String v) { portProfile = v; }
    public java.util.List<Integer> getCustomPorts()            { return customPorts; }
    public void   setCustomPorts(java.util.List<Integer> v)    { customPorts = v; }
    public int    getConnectTimeoutMs()    { return connectTimeoutMs; }
    public void   setConnectTimeoutMs(int v) { connectTimeoutMs = v; }
    public int    getTlsTimeoutMs()        { return tlsTimeoutMs; }
    public void   setTlsTimeoutMs(int v)   { tlsTimeoutMs = v; }
}
