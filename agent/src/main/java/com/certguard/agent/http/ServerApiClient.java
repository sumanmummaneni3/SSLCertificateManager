package com.certguard.agent.http;

import com.certguard.agent.config.AgentConfig;
import com.certguard.agent.model.ScanJob;
import com.certguard.agent.model.ScanResult;
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
                    job.setTargetId(node.get("targetId").asText());
                    job.setHost(node.get("host").asText());
                    job.setPort(node.get("port").asInt(443));
                    job.setLastKnownSerialHash(
                            node.has("lastKnownSerialHash") && !node.get("lastKnownSerialHash").isNull()
                                    ? node.get("lastKnownSerialHash").asText() : null);
                    job.setLastCertificateId(
                            node.has("lastCertificateId") && !node.get("lastCertificateId").isNull()
                                    ? node.get("lastCertificateId").asText() : null);
                    jobs.add(job);
                }
            }
            log.debug("Polled {} job(s)", jobs.size());
            return jobs;
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
