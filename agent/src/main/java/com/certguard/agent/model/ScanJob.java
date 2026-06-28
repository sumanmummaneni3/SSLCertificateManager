package com.certguard.agent.model;

public class ScanJob {
    private String jobId;
    private String targetId;
    private String host;
    private int    port;
    private String lastKnownSerialHash;   // SHA-256 of last serial server has
    private String lastCertificateId;     // UUID of last cert record on server

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
