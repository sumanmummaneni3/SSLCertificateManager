package com.certguard.agent.http;

import com.certguard.agent.config.AgentConfig;
import com.certguard.agent.discovery.NicSubnetDiscovery;
import com.certguard.agent.model.ScanJob;
import com.certguard.agent.model.ScanResult;
import com.certguard.agent.scanner.DeviceClass;
import com.certguard.agent.scanner.EndpointPortState;
import com.certguard.agent.scanner.PortSweepScanner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.cert.X509Certificate;
import java.util.*;

/**
 * All REST calls to the CertGuard server.
 * Plain Apache HttpClient 5 — no framework, no DI.
 */
public class ServerApiClient {

    private static final Logger log = LoggerFactory.getLogger(ServerApiClient.class);

    private final AgentConfig config;
    private final CloseableHttpClient http;
    private final ObjectMapper mapper;

    public ServerApiClient(AgentConfig config, CloseableHttpClient http) {
        this.config = config;
        this.http   = http;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    // ── Registration ──────────────────────────────────────────

    /**
     * POST /api/v1/agent/register
     * Returns the full response body as JsonNode — caller extracts id, agentKey, clientCertPem.
     */
    public JsonNode register(String registrationToken, String orgId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("registrationToken", registrationToken);
        body.put("agentName",         config.agentName());
        body.put("allowedCidrs",      config.allowedCidrs());
        body.put("maxTargets",        config.maxTargets());
        body.put("agentVersion",      "1.0.0");

        HttpPost req = new HttpPost(config.serverUrl() + "/api/v1/agent/register");
        req.setHeader("Content-Type", "application/json");
        req.setHeader("X-Org-Id", orgId);
        req.setEntity(new StringEntity(mapper.writeValueAsString(body), ContentType.APPLICATION_JSON));

        return http.execute(req, response -> {
            int status = response.getCode();
            String responseBody = new String(response.getEntity().getContent().readAllBytes());
            if (status != 201 && status != 200) {
                throw new RuntimeException("Registration failed HTTP " + status + ": " + responseBody);
            }
            return mapper.readTree(responseBody);
        });
    }

    // ── Heartbeat ─────────────────────────────────────────────

    /**
     * POST /api/v1/agent/heartbeat
     */
    public void heartbeat() throws Exception {
        HttpPost req = new HttpPost(config.serverUrl() + "/api/v1/agent/heartbeat");
        addAgentHeaders(req);

        http.execute(req, response -> {
            int status = response.getCode();
            if (status != 200) {
                log.warn("Heartbeat returned HTTP {}", status);
            }
            return null;
        });
    }

    // ── Poll jobs ─────────────────────────────────────────────

    /**
     * GET /api/v1/agent/jobs
     * Returns list of pending scan jobs for this agent.
     */
    public List<ScanJob> pollJobs() throws Exception {
        HttpGet req = new HttpGet(config.serverUrl() + "/api/v1/agent/jobs");
        addAgentHeaders(req);

        return http.execute(req, response -> {
            int status = response.getCode();
            String body = new String(response.getEntity().getContent().readAllBytes());

            if (status != 200) {
                log.warn("Poll returned HTTP {}: {}", status, body);
                return List.of();
            }

            List<ScanJob> jobs = new ArrayList<>();
            JsonNode arr = mapper.readTree(body);
            if (arr.isArray()) {
                for (JsonNode node : arr) {
                    ScanJob job = new ScanJob();
                    job.setJobId(node.get("jobId").asText());
                    job.setJobType(node.has("jobType") && !node.get("jobType").isNull()
                            ? node.get("jobType").asText() : null);

                    // CERTIFICATE_SCAN / legacy fields
                    if (node.has("targetId") && !node.get("targetId").isNull()) {
                        job.setTargetId(node.get("targetId").asText());
                    }
                    if (node.has("host") && !node.get("host").isNull()) {
                        job.setHost(node.get("host").asText());
                    }
                    job.setPort(node.has("port") ? node.get("port").asInt(443) : 443);
                    job.setLastKnownSerialHash(
                            node.has("lastKnownSerialHash") && !node.get("lastKnownSerialHash").isNull()
                                    ? node.get("lastKnownSerialHash").asText() : null);
                    job.setLastCertificateId(
                            node.has("lastCertificateId") && !node.get("lastCertificateId").isNull()
                                    ? node.get("lastCertificateId").asText() : null);

                    // NETWORK_SCAN fields
                    if ("NETWORK_SCAN".equals(job.getJobType()) && node.has("networkScan")) {
                        JsonNode ns = node.get("networkScan");
                        if (ns != null && !ns.isNull()) {
                            job.setNetworkScanId(ns.has("networkScanId")
                                    ? ns.get("networkScanId").asText() : null);
                            job.setCidr(ns.has("cidr")
                                    ? ns.get("cidr").asText() : null);
                            job.setPortProfile(ns.has("portProfile")
                                    ? ns.get("portProfile").asText() : "COMMON_TLS");
                            job.setConnectTimeoutMs(ns.has("connectTimeoutMs")
                                    ? ns.get("connectTimeoutMs").asInt(500) : 500);
                            job.setTlsTimeoutMs(ns.has("tlsTimeoutMs")
                                    ? ns.get("tlsTimeoutMs").asInt(3000) : 3000);
                            if (ns.has("customPorts") && ns.get("customPorts").isArray()) {
                                List<Integer> cp = new ArrayList<>();
                                for (JsonNode p : ns.get("customPorts")) cp.add(p.asInt());
                                job.setCustomPorts(cp);
                            }
                        }
                    }

                    jobs.add(job);
                }
            }
            log.debug("Polled {} job(s)", jobs.size());
            return jobs;
        });
    }

    // ── Network scan results (RFC 0011 Part A) ────────────────────────────────

    /**
     * POST /api/v1/agent/network-results
     * Submits a batch of host scan results for a NETWORK_SCAN job.
     * The batch includes an HMAC over (networkScanId:chunkIndex:hostCount:timestamp).
     */
    public void submitNetworkResults(String networkScanId, int chunkIndex, int totalChunks,
                                     List<PortSweepScanner.HostScanResult> hosts,
                                     String agentKey) throws Exception {
        long timestamp = System.currentTimeMillis();

        // Build host list
        List<Map<String, Object>> hostList = new ArrayList<>();
        for (PortSweepScanner.HostScanResult host : hosts) {
            List<Map<String, Object>> portList = new ArrayList<>();
            for (PortSweepScanner.PortScanResult p : host.ports()) {
                Map<String, Object> portMap = new LinkedHashMap<>();
                portMap.put("port",        p.port());
                portMap.put("state",       p.state().name());
                portMap.put("deviceClass", p.deviceClass() != null ? p.deviceClass().name() : DeviceClass.UNKNOWN.name());
                portMap.put("banners",     p.banners() != null ? p.banners() : Map.of());
                if (p.chain() != null && p.chain().length > 0) {
                    List<String> chainB64 = new ArrayList<>();
                    for (X509Certificate cert : p.chain()) {
                        chainB64.add(Base64.getEncoder().encodeToString(cert.getEncoded()));
                    }
                    portMap.put("chainB64", chainB64);
                }
                portList.add(portMap);
            }
            Map<String, Object> hostMap = new LinkedHashMap<>();
            hostMap.put("ip",    host.ip());
            hostMap.put("ports", portList);
            hostList.add(hostMap);
        }

        // Compute HMAC: "{networkScanId}:{chunkIndex}:{hostCount}:{timestamp}"
        String hmacPayload = networkScanId + ":" + chunkIndex + ":" + hosts.size() + ":" + timestamp;
        String hmac = com.certguard.agent.security.HmacSigner.signRaw(hmacPayload, agentKey);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("networkScanId", networkScanId);
        body.put("chunkIndex",    chunkIndex);
        body.put("totalChunks",   totalChunks);
        body.put("timestamp",     timestamp);
        body.put("hmac",          hmac);
        body.put("hosts",         hostList);

        HttpPost req = new HttpPost(config.serverUrl() + "/api/v1/agent/network-results");
        addAgentHeaders(req);
        req.setHeader("Content-Type", "application/json");
        req.setEntity(new StringEntity(mapper.writeValueAsString(body), ContentType.APPLICATION_JSON));

        http.execute(req, response -> {
            int statusCode = response.getCode();
            if (statusCode != 202) {
                String respBody = new String(response.getEntity().getContent().readAllBytes());
                log.error("Network results submission failed HTTP {}: {}", statusCode, respBody);
            } else {
                log.info("Network results submitted — scan: {}, chunk: {}/{}, hosts: {}",
                        networkScanId, chunkIndex + 1, totalChunks, hosts.size());
            }
            return null;
        });
    }

    // ── Anonymous discovery (RFC 0011 Part B) ─────────────────────────────────

    /**
     * GET /api/v1/anon/jobs
     * Polls for pending anonymous discovery job using X-Anon-Scan-Token.
     * Returns raw job list as JsonNode for flexibility.
     */
    public List<Map<String, Object>> getAnonJobs(String scanToken) throws Exception {
        HttpGet req = new HttpGet(config.serverUrl() + "/api/v1/anon/jobs");
        req.setHeader("X-Anon-Scan-Token", scanToken);

        return http.execute(req, response -> {
            int statusCode = response.getCode();
            String body = new String(response.getEntity().getContent().readAllBytes());

            if (statusCode != 200) {
                log.warn("Anon job poll returned HTTP {}: {}", statusCode, body);
                return List.of();
            }

            List<Map<String, Object>> jobs = new ArrayList<>();
            JsonNode arr = mapper.readTree(body);
            if (arr.isArray()) {
                for (JsonNode node : arr) {
                    Map<String, Object> job = new HashMap<>();
                    node.fields().forEachRemaining(e -> job.put(e.getKey(), e.getValue().asText()));
                    jobs.add(job);
                }
            }
            return jobs;
        });
    }

    /**
     * POST /api/v1/anon/discovery-results
     * Submits NIC subnets and discovered device data.
     * No IP addresses are included in device entries (privacy rule).
     */
    public void submitAnonDiscoveryResults(String scanToken,
                                            List<NicSubnetDiscovery.SubnetInfo> subnets,
                                            List<PortSweepScanner.HostScanResult> hostResults) throws Exception {
        // Build subnet list
        List<Map<String, Object>> subnetList = new ArrayList<>();
        for (NicSubnetDiscovery.SubnetInfo s : subnets) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("cidr",      s.cidr());
            sm.put("ifaceName", s.ifaceName());
            subnetList.add(sm);
        }

        // Build device list — NO ip field stored (privacy requirement)
        List<Map<String, Object>> deviceList = new ArrayList<>();
        for (PortSweepScanner.HostScanResult host : hostResults) {
            if (host.ports().isEmpty()) continue;

            // Aggregate port-level data into per-device summary
            List<Integer> openPorts = new ArrayList<>();
            List<String> tlsSubjects = new ArrayList<>();
            int tlsPortCount = 0;
            java.time.Instant tlsExpiryMinInstant = null;
            DeviceClass bestClass = DeviceClass.UNKNOWN;

            for (PortSweepScanner.PortScanResult p : host.ports()) {
                openPorts.add(p.port());
                if (p.state() == EndpointPortState.OPEN_TLS) {
                    tlsPortCount++;
                    if (p.chain() != null && p.chain().length > 0) {
                        X509Certificate leaf = p.chain()[0];
                        tlsSubjects.add(leaf.getSubjectX500Principal().getName());
                        java.time.Instant notAfter = leaf.getNotAfter().toInstant();
                        if (tlsExpiryMinInstant == null || notAfter.isBefore(tlsExpiryMinInstant)) {
                            tlsExpiryMinInstant = notAfter;
                        }
                    }
                }
                if (p.deviceClass() != null && p.deviceClass() != DeviceClass.UNKNOWN) {
                    bestClass = p.deviceClass();
                }
            }

            // Find subnet CIDR for this host
            String hostSubnetCidr = subnets.isEmpty() ? "unknown" : subnets.get(0).cidr();

            Map<String, Object> dm = new LinkedHashMap<>();
            dm.put("subnetCidr",   hostSubnetCidr);
            dm.put("deviceClass",  bestClass.name());
            dm.put("openPorts",    openPorts);
            dm.put("tlsPortCount", tlsPortCount);
            dm.put("tlsSubjects",  tlsSubjects);
            dm.put("tlsExpiryMin", tlsExpiryMinInstant != null ? tlsExpiryMinInstant.toString() : null);
            deviceList.add(dm);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subnets", subnetList);
        body.put("devices", deviceList);

        HttpPost req = new HttpPost(config.serverUrl() + "/api/v1/anon/discovery-results");
        req.setHeader("X-Anon-Scan-Token", scanToken);
        req.setHeader("Content-Type", "application/json");
        req.setEntity(new StringEntity(mapper.writeValueAsString(body), ContentType.APPLICATION_JSON));

        http.execute(req, response -> {
            int statusCode = response.getCode();
            if (statusCode != 200) {
                String respBody = new String(response.getEntity().getContent().readAllBytes());
                log.error("Anon discovery results submission failed HTTP {}: {}", statusCode, respBody);
            } else {
                log.info("Anon discovery results submitted — {} subnets, {} devices",
                        subnets.size(), deviceList.size());
            }
            return null;
        });
    }

    // ── Submit result ─────────────────────────────────────────

    /**
     * POST /api/v1/agent/results
     * Submits a FULL or DELTA scan result with HMAC signature.
     */
    public void submitResult(ScanResult result, String hmac) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId",         result.getJobId());
        body.put("targetId",      result.getTargetId());
        body.put("scanType",      result.getScanType());
        body.put("serialNumber",  result.getSerialNumber());
        body.put("notAfter",      result.getNotAfter().toString());
        body.put("hmacSignature", hmac);

        if ("FULL".equals(result.getScanType())) {
            body.put("commonName",         result.getCommonName());
            body.put("issuer",             result.getIssuer());
            body.put("notBefore",          result.getNotBefore() != null ? result.getNotBefore().toString() : null);
            body.put("keyAlgorithm",       result.getKeyAlgorithm());
            body.put("keySize",            result.getKeySize());
            body.put("signatureAlgorithm", result.getSignatureAlgorithm());
            body.put("subjectAltNames",    result.getSubjectAltNames());
            body.put("chainDepth",         result.getChainDepth());
            body.put("publicCertB64",      result.getPublicCertB64());
        } else {
            body.put("certificateId", result.getLastCertificateId());
        }

        HttpPost req = new HttpPost(config.serverUrl() + "/api/v1/agent/results");
        addAgentHeaders(req);
        req.setHeader("Content-Type", "application/json");
        req.setEntity(new StringEntity(mapper.writeValueAsString(body), ContentType.APPLICATION_JSON));

        http.execute(req, response -> {
            int status = response.getCode();
            if (status != 200) {
                String respBody = new String(response.getEntity().getContent().readAllBytes());
                log.error("Result submission failed HTTP {}: {}", status, respBody);
            } else {
                log.info("Result submitted — job: {}, type: {}", result.getJobId(), result.getScanType());
            }
            return null;
        });
    }

    // ── Helpers ───────────────────────────────────────────────

    private void addAgentHeaders(org.apache.hc.core5.http.HttpRequest req) {
        req.setHeader("X-Agent-Id",  config.agentId());
        req.setHeader("X-Agent-Key", config.agentKey());
    }
}
