package com.certguard.service;

import com.certguard.dto.request.AgentNetworkResultsBatch;
import com.certguard.dto.request.NetworkScanCreateRequest;
import com.certguard.dto.response.DiscoveredEndpointResponse;
import com.certguard.dto.response.NetworkScanResponse;
import com.certguard.dto.response.ScanJobResponse;
import com.certguard.entity.*;
import com.certguard.enums.*;
import com.certguard.exception.ResourceNotFoundException;
import com.certguard.repository.*;
import com.certguard.service.chain.ChainValidationResult;
import com.certguard.service.chain.ChainValidationService;
import com.certguard.service.revocation.RevocationCheckService;
import com.certguard.service.revocation.RevocationResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponseException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.*;

/**
 * Business logic for authenticated network sweep jobs (RFC 0011 Part A).
 * Three-layer: controller → this service → repositories.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class NetworkScanService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 500;
    private static final int DEFAULT_TLS_TIMEOUT_MS = 3000;

    private final NetworkScanRepository networkScanRepository;
    private final DiscoveredEndpointRepository endpointRepository;
    private final AgentRepository agentRepository;
    private final OrganizationRepository orgRepository;
    private final SubscriptionGuard subscriptionGuard;
    private final ChainValidationService chainValidationService;
    private final RevocationCheckService revocationCheckService;
    private final ExpiryEvaluationService expiryEvaluationService;
    private final ObjectMapper objectMapper;

    // ── Create ────────────────────────────────────────────────────────────────

    /**
     * Validates and enqueues a new network sweep.
     * Enforces: subscription check, agent ownership + ACTIVE status,
     * CIDR within agent's allowed_cidrs, no concurrent scan for this agent.
     */
    @Transactional
    public NetworkScanResponse createScan(UUID orgId, NetworkScanCreateRequest req,
                                          UUID requestingUserId) {
        subscriptionGuard.assertScansAllowed(orgId);

        Agent agent = agentRepository.findByIdAndOrganizationId(req.agentId(), orgId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Agent not found or does not belong to this organization"));

        if (agent.getStatus() != AgentStatus.ACTIVE) {
            throw new ErrorResponseException(HttpStatus.CONFLICT,
                    problem(HttpStatus.CONFLICT, "AGENT_NOT_ACTIVE",
                            "Agent is not ACTIVE: " + agent.getStatus()), null);
        }

        if (!isCidrSubsetOfAllowed(req.cidr(), agent.getAllowedCidrs())) {
            throw new ErrorResponseException(HttpStatus.BAD_REQUEST,
                    problem(HttpStatus.BAD_REQUEST, "CIDR_OUT_OF_SCOPE",
                            "Requested CIDR " + req.cidr() + " is not within agent's allowed_cidrs"), null);
        }

        List<NetworkScan> active = networkScanRepository.findByAgent_IdAndStatusIn(
                agent.getId(), List.of(NetworkScanStatus.PENDING, NetworkScanStatus.IN_PROGRESS));
        if (!active.isEmpty()) {
            throw new ErrorResponseException(HttpStatus.CONFLICT,
                    problem(HttpStatus.CONFLICT, "SCAN_ALREADY_ACTIVE",
                            "Agent already has an active network scan"), null);
        }

        Organization org = orgRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));

        NetworkScan scan = new NetworkScan();
        scan.setOrganization(org);
        scan.setAgent(agent);
        scan.setCidr(req.cidr());
        scan.setPortProfile(req.portProfile());
        if (req.customPorts() != null && !req.customPorts().isEmpty()) {
            scan.setCustomPorts(req.customPorts().stream().mapToInt(Integer::intValue).toArray());
        }
        scan.setStatus(NetworkScanStatus.PENDING);
        networkScanRepository.save(scan);

        log.info("Network scan created — org: {}, agent: {}, cidr: {}, profile: {}",
                orgId, agent.getName(), req.cidr(), req.portProfile());

        return toResponse(scan);
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    public Page<NetworkScanResponse> listScans(UUID orgId, Pageable pageable) {
        return networkScanRepository
                .findByOrgIdOrderByCreatedAtDesc(orgId, pageable)
                .map(this::toResponse);
    }

    public NetworkScanResponse getScan(UUID orgId, UUID scanId) {
        return networkScanRepository.findByIdAndOrgId(scanId, orgId)
                .map(this::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Network scan not found"));
    }

    public Page<DiscoveredEndpointResponse> listEndpoints(UUID orgId, UUID scanId,
                                                           EndpointPortState state,
                                                           DeviceClass deviceClass,
                                                           Pageable pageable) {
        // Verify the scan belongs to this org first
        networkScanRepository.findByIdAndOrgId(scanId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Network scan not found"));

        Page<DiscoveredEndpoint> page;
        if (state != null) {
            page = endpointRepository.findByNetworkScan_IdAndState(scanId, state, pageable);
        } else if (deviceClass != null) {
            page = endpointRepository.findByNetworkScan_IdAndDeviceClass(scanId, deviceClass, pageable);
        } else {
            page = endpointRepository.findByNetworkScan_Id(scanId, pageable);
        }
        return page.map(this::toEndpointResponse);
    }

    // ── Cancel ────────────────────────────────────────────────────────────────

    @Transactional
    public void cancelScan(UUID orgId, UUID scanId) {
        NetworkScan scan = networkScanRepository.findByIdAndOrgId(scanId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Network scan not found"));

        if (scan.getStatus() != NetworkScanStatus.PENDING
                && scan.getStatus() != NetworkScanStatus.IN_PROGRESS) {
            throw new ErrorResponseException(HttpStatus.CONFLICT,
                    problem(HttpStatus.CONFLICT, "SCAN_NOT_CANCELLABLE",
                            "Scan is not in a cancellable state: " + scan.getStatus()), null);
        }
        scan.setStatus(NetworkScanStatus.CANCELLED);
        networkScanRepository.save(scan);
        log.info("Network scan cancelled — id: {}, org: {}", scanId, orgId);
    }

    // ── Agent batch result ingestion ──────────────────────────────────────────

    /**
     * Processes a batch of host/port results from the agent.
     * Called by AgentController after AgentAuthFilter authentication.
     *
     * @param batch       the deserialized batch payload
     * @param agent       the authenticated agent (from request attribute)
     * @param rawAgentKey the raw agent key (from X-Agent-Key header, used for HMAC)
     */
    @Transactional
    public void ingestBatchResults(AgentNetworkResultsBatch batch, Agent agent, String rawAgentKey) {
        // 1. Validate HMAC
        String expectedHmac = computeBatchHmac(rawAgentKey, batch);
        if (!constantTimeEquals(expectedHmac, batch.hmac())) {
            throw new SecurityException("HMAC verification failed for network results batch");
        }

        // 2. Load and validate the network scan
        NetworkScan scan = networkScanRepository.findById(batch.networkScanId())
                .orElseThrow(() -> new ResourceNotFoundException("Network scan not found: " + batch.networkScanId()));

        if (!agent.getId().equals(scan.getAgent().getId())) {
            throw new SecurityException("Network scan does not belong to this agent");
        }
        if (scan.getStatus() != NetworkScanStatus.PENDING
                && scan.getStatus() != NetworkScanStatus.IN_PROGRESS) {
            throw new ErrorResponseException(HttpStatus.CONFLICT,
                    problem(HttpStatus.CONFLICT, "SCAN_NOT_ACTIVE",
                            "Network scan is not active: " + scan.getStatus()), null);
        }

        // Mark IN_PROGRESS on first chunk
        if (scan.getStatus() == NetworkScanStatus.PENDING) {
            scan.setStatus(NetworkScanStatus.IN_PROGRESS);
        }

        UUID orgId = scan.getOrgId();
        int openPortDelta = 0;
        int tlsFoundDelta = 0;
        int hostsScannedDelta = batch.hosts().size();

        // 3. Process each host/port result
        for (AgentNetworkResultsBatch.HostResult hostResult : batch.hosts()) {
            for (AgentNetworkResultsBatch.PortResult portResult : hostResult.ports()) {
                DiscoveredEndpoint endpoint = new DiscoveredEndpoint();
                endpoint.setNetworkScan(scan);
                endpoint.setOrgId(orgId);
                endpoint.setIp(hostResult.ip());
                endpoint.setPort(portResult.port());
                endpoint.setState(portResult.state());
                endpoint.setDeviceClass(portResult.deviceClass() != null
                        ? portResult.deviceClass() : DeviceClass.UNKNOWN);

                if (portResult.banners() != null && !portResult.banners().isEmpty()) {
                    try {
                        endpoint.setBanners(objectMapper.writeValueAsString(portResult.banners()));
                    } catch (Exception e) {
                        log.warn("Failed to serialize banners for {}:{}: {}", hostResult.ip(), portResult.port(), e.getMessage());
                    }
                }

                if (portResult.state() == EndpointPortState.OPEN_TLS
                        && portResult.chainB64() != null && !portResult.chainB64().isEmpty()) {
                    openPortDelta++;
                    tlsFoundDelta++;
                    processOpenTlsPort(endpoint, portResult.chainB64(), orgId);
                } else if (portResult.state() == EndpointPortState.OPEN_NO_TLS) {
                    openPortDelta++;
                }

                endpointRepository.save(endpoint);
            }
        }

        // 4. Update counters
        scan.setHostsScanned(scan.getHostsScanned() + hostsScannedDelta);
        scan.setOpenPortCount(scan.getOpenPortCount() + openPortDelta);
        scan.setTlsFoundCount(scan.getTlsFoundCount() + tlsFoundDelta);

        // 5. Complete when last chunk arrives
        if (batch.chunkIndex() == batch.totalChunks() - 1) {
            scan.setStatus(NetworkScanStatus.COMPLETE);
            log.info("Network scan complete — id: {}, hosts: {}, open: {}, tls: {}",
                    scan.getId(), scan.getHostsScanned(), scan.getOpenPortCount(), scan.getTlsFoundCount());
        }

        networkScanRepository.save(scan);
    }

    /**
     * Returns PENDING network scans for this agent as ScanJobResponse entries.
     * Called by AgentService.pollJobs to merge into the regular job list.
     */
    public List<ScanJobResponse> pollNetworkJobs(Agent agent) {
        List<NetworkScan> pending = networkScanRepository.findByAgent_IdAndStatusIn(
                agent.getId(), List.of(NetworkScanStatus.PENDING));

        return pending.stream().map(scan -> {
            List<Integer> customPorts = null;
            if (scan.getCustomPorts() != null) {
                customPorts = new ArrayList<>();
                for (int p : scan.getCustomPorts()) customPorts.add(p);
            }
            return ScanJobResponse.builder()
                    .jobId(scan.getId())       // network scan ID doubles as the job ID
                    .jobType("NETWORK_SCAN")
                    .networkScan(ScanJobResponse.NetworkScanPayload.builder()
                            .networkScanId(scan.getId())
                            .cidr(scan.getCidr())
                            .portProfile(scan.getPortProfile())
                            .customPorts(customPorts)
                            .connectTimeoutMs(DEFAULT_CONNECT_TIMEOUT_MS)
                            .tlsTimeoutMs(DEFAULT_TLS_TIMEOUT_MS)
                            .build())
                    .build();
        }).toList();
    }

    // ── TLS processing helpers ────────────────────────────────────────────────

    private void processOpenTlsPort(DiscoveredEndpoint endpoint, List<String> chainB64, UUID orgId) {
        try {
            X509Certificate[] chain = decodeCertChain(chainB64);
            if (chain == null || chain.length == 0) {
                log.warn("Empty chain decoded for {}:{}", endpoint.getIp(), endpoint.getPort());
                return;
            }

            X509Certificate leaf = chain[0];
            endpoint.setTlsSubjectCn(extractCn(leaf.getSubjectX500Principal().getName()));
            endpoint.setTlsNotAfter(leaf.getNotAfter().toInstant());

            ChainValidationResult chainResult = chainValidationService.validate(chain);
            RevocationResult revResult = revocationCheckService.check(chain, null, false);

            CertStatus status = expiryEvaluationService.determineCertStatus(
                    leaf.getNotAfter().toInstant(), revResult, chainResult, null, orgId);
            endpoint.setTlsCertStatus(status);

        } catch (Exception e) {
            log.warn("TLS processing failed for {}:{}: {}", endpoint.getIp(), endpoint.getPort(), e.getMessage());
        }
    }

    private X509Certificate[] decodeCertChain(List<String> chainB64) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            List<X509Certificate> certs = new ArrayList<>();
            for (String b64 : chainB64) {
                byte[] der = Base64.getDecoder().decode(b64);
                certs.add((X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der)));
            }
            return certs.toArray(new X509Certificate[0]);
        } catch (Exception e) {
            log.warn("Failed to decode certificate chain: {}", e.getMessage());
            return null;
        }
    }

    // ── HMAC helpers ──────────────────────────────────────────────────────────

    private String computeBatchHmac(String agentKey, AgentNetworkResultsBatch batch) {
        try {
            String payload = batch.networkScanId() + ":"
                    + batch.chunkIndex() + ":"
                    + batch.hosts().size() + ":"
                    + batch.timestamp();
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(agentKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute batch HMAC", e);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] ab = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        if (ab.length != bb.length) return false;
        int result = 0;
        for (int i = 0; i < ab.length; i++) result |= ab[i] ^ bb[i];
        return result == 0;
    }

    // ── CIDR helpers ──────────────────────────────────────────────────────────

    /**
     * Checks whether the requested CIDR is entirely within at least one of the agent's
     * allowed CIDRs. Both must be valid IPv4 CIDR notation.
     */
    private boolean isCidrSubsetOfAllowed(String requested, List<String> allowedCidrs) {
        if (allowedCidrs == null || allowedCidrs.isEmpty()) return false;
        try {
            String[] reqParts = requested.split("/");
            InetAddress reqNet = InetAddress.getByName(reqParts[0]);
            int reqPrefix = Integer.parseInt(reqParts[1]);

            for (String allowed : allowedCidrs) {
                String[] allowedParts = allowed.split("/");
                InetAddress allowedNet = InetAddress.getByName(allowedParts[0]);
                int allowedPrefix = Integer.parseInt(allowedParts[1]);

                // Requested prefix must be >= allowed prefix (more specific or equal)
                // AND requested network must be within the allowed network
                if (reqPrefix >= allowedPrefix && isInCidr(reqNet, allowed)) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn("CIDR validation error: {}", e.getMessage());
        }
        return false;
    }

    private boolean isInCidr(InetAddress address, String cidr) {
        try {
            String[] parts = cidr.split("/");
            InetAddress network = InetAddress.getByName(parts[0]);
            int prefix = Integer.parseInt(parts[1]);
            byte[] a = address.getAddress();
            byte[] n = network.getAddress();
            if (a.length != n.length) return false;
            int full = prefix / 8, rem = prefix % 8;
            for (int i = 0; i < full; i++) { if (a[i] != n[i]) return false; }
            if (rem > 0) {
                int mask = 0xFF & (0xFF << (8 - rem));
                return (a[full] & mask) == (n[full] & mask);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Mapping helpers ───────────────────────────────────────────────────────

    private NetworkScanResponse toResponse(NetworkScan scan) {
        return new NetworkScanResponse(
                scan.getId(),
                scan.getOrgId(),
                scan.getAgent().getId(),
                scan.getAgent().getName(),
                scan.getCidr(),
                scan.getPortProfile(),
                scan.getStatus(),
                scan.getHostsTotal(),
                scan.getHostsScanned(),
                scan.getOpenPortCount(),
                scan.getTlsFoundCount(),
                scan.getErrorMessage(),
                scan.getCreatedAt(),
                scan.getUpdatedAt()
        );
    }

    private DiscoveredEndpointResponse toEndpointResponse(DiscoveredEndpoint ep) {
        Map<String, String> banners = null;
        if (ep.getBanners() != null) {
            try {
                banners = objectMapper.readValue(ep.getBanners(),
                        new TypeReference<Map<String, String>>() {});
            } catch (Exception e) {
                log.warn("Failed to parse banners JSON for endpoint {}", ep.getId());
            }
        }
        return new DiscoveredEndpointResponse(
                ep.getId(),
                ep.getNetworkScan().getId(),
                ep.getIp(),
                ep.getPort(),
                ep.getState(),
                ep.getDeviceClass(),
                banners,
                ep.getCertRecord() != null ? ep.getCertRecord().getId() : null,
                ep.getTlsSubjectCn(),
                ep.getTlsNotAfter(),
                ep.getTlsCertStatus(),
                ep.getCreatedAt()
        );
    }

    private String extractCn(String dn) {
        for (String part : dn.split(",")) {
            String t = part.trim();
            if (t.startsWith("CN=")) return t.substring(3);
        }
        return dn;
    }

    private ProblemDetail problem(HttpStatus status, String errorCode, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setProperty("errorCode", errorCode);
        return pd;
    }
}
