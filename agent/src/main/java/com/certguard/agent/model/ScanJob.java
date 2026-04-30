package com.certguard.agent.model;

public class ScanJob {
    private String jobId;
    private String targetId;
    private String host;
    private int    port;
    private String lastKnownSerialHash;   // SHA-256 of last serial server has
    private String lastCertificateId;     // UUID of last cert record on server

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
}
