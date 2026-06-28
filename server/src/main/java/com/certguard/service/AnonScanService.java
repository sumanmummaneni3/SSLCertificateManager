package com.certguard.service;

import com.certguard.dto.request.AnonDiscoveryResultsRequest;
import com.certguard.dto.response.AnonSessionCreateResponse;
import com.certguard.dto.response.AnonSessionDashboardResponse;
import com.certguard.entity.AnonDiscoveredDevice;
import com.certguard.entity.AnonDiscoveredSubnet;
import com.certguard.entity.AnonScanSession;
import com.certguard.enums.AnonSessionStatus;
import com.certguard.enums.DeviceClass;
import com.certguard.exception.ResourceNotFoundException;
import com.certguard.repository.AnonDiscoveredDeviceRepository;
import com.certguard.repository.AnonDiscoveredSubnetRepository;
import com.certguard.repository.AnonScanSessionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponseException;

import java.net.InetAddress;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Business logic for anonymous free-tier scan sessions (RFC 0011 Part B).
 *
 * Privacy guarantees enforced by this service:
 * - No IP address stored in any anon table.
 * - No MAC address stored.
 * - Only SHA-256 hashes of the raw tokens are persisted.
 * - All CIDRs validated against RFC1918 private ranges.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AnonScanService {

    private static final Duration SCAN_TTL  = Duration.ofHours(1);
    private static final Duration VIEW_TTL  = Duration.ofDays(7);
    private static final int MAX_SUBNETS    = 5;
    private static final int MAX_DEVICES    = 254;

    /** RFC1918 private ranges. Any CIDR/host reported must fall within these. */
    private static final List<String> PRIVATE_RANGES =
            List.of("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16");

    /** In-memory IP rate limiter: ip → [requestCount, windowStartMs]. Single-node only. */
    private final ConcurrentHashMap<String, long[]> rateLimitMap = new ConcurrentHashMap<>();
    private static final int    RATE_LIMIT_MAX      = 5;
    private static final long   RATE_LIMIT_WINDOW_MS = 24L * 60 * 60 * 1000;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AnonScanSessionRepository sessionRepository;
    private final AnonDiscoveredSubnetRepository subnetRepository;
    private final AnonDiscoveredDeviceRepository deviceRepository;

    // ── Session creation ──────────────────────────────────────────────────────

    /**
     * Creates a new anonymous session. Generates two SecureRandom 256-bit tokens;
     * only their SHA-256 hashes are stored. Returns raw tokens (shown once only).
     *
     * @param serverBaseUrl used to build the dashboardUrl returned in the response
     * @param clientIp      for rate-limiting (NOT stored)
     */
    @Transactional
    public AnonSessionCreateResponse createSession(String serverBaseUrl, String clientIp) {
        enforceRateLimit(clientIp);

        SecureRandom rng = new SecureRandom();
        byte[] scanTokenBytes  = new byte[32];
        byte[] viewTokenBytes  = new byte[32];
        rng.nextBytes(scanTokenBytes);
        rng.nextBytes(viewTokenBytes);

        String rawScanToken = hexEncode(scanTokenBytes);
        String rawViewToken = hexEncode(viewTokenBytes);

        Instant now  = Instant.now();
        AnonScanSession session = new AnonScanSession();
        session.setScanTokenHash(sha256Hex(rawScanToken));
        session.setViewTokenHash(sha256Hex(rawViewToken));
        session.setStatus(AnonSessionStatus.ACTIVE);
        session.setScanExpiresAt(now.plus(SCAN_TTL));
        session.setViewExpiresAt(now.plus(VIEW_TTL));
        sessionRepository.save(session);

        log.info("Anon session created — id: {}", session.getId());

        return new AnonSessionCreateResponse(
                rawScanToken,
                rawViewToken,
                session.getScanExpiresAt(),
                session.getViewExpiresAt(),
                serverBaseUrl + "/scan/" + rawViewToken
        );
    }

    // ── Agent interaction ─────────────────────────────────────────────────────

    /**
     * Returns a DISCOVERY job stub while the session is ACTIVE.
     * Once SCAN_COMPLETE, returns an empty list (one-scan rule, RFC 0011 §4.4).
     */
    public List<Object> getPendingJobs(AnonScanSession session) {
        if (session.getStatus() != AnonSessionStatus.ACTIVE) {
            return List.of();
        }
        // Single DISCOVERY job — sessionId doubles as jobId
        return List.of(Map.of(
                "jobId",   session.getId().toString(),
                "jobType", "DISCOVERY"
        ));
    }

    /**
     * Ingests NIC subnets and discovered devices from the anonymous agent.
     * One-scan rule: once called successfully, session transitions to SCAN_COMPLETE.
     *
     * Hard constraints enforced here:
     *   - 409 if session already SCAN_COMPLETE (one-scan rule)
     *   - 400 SCOPE_VIOLATION if any CIDR is not RFC1918
     *   - max 5 subnets, max 254 devices per session
     *   - no IP stored on any row
     */
    @Transactional
    public void ingestDiscoveryResults(AnonScanSession session,
                                        AnonDiscoveryResultsRequest req) {
        if (session.getStatus() != AnonSessionStatus.ACTIVE) {
            throw new ErrorResponseException(HttpStatus.CONFLICT,
                    problem(HttpStatus.CONFLICT, "ALREADY_SCANNED",
                            "This session has already submitted discovery results"), null);
        }

        // Validate payload caps
        if (req.subnets().size() > MAX_SUBNETS) {
            throw new ErrorResponseException(HttpStatus.BAD_REQUEST,
                    problem(HttpStatus.BAD_REQUEST, "PAYLOAD_TOO_LARGE",
                            "Too many subnets; max is " + MAX_SUBNETS), null);
        }
        if (req.devices().size() > MAX_DEVICES) {
            throw new ErrorResponseException(HttpStatus.BAD_REQUEST,
                    problem(HttpStatus.BAD_REQUEST, "PAYLOAD_TOO_LARGE",
                            "Too many devices; max is " + MAX_DEVICES), null);
        }

        // Validate all CIDRs are RFC1918
        for (AnonDiscoveryResultsRequest.SubnetDto subnet : req.subnets()) {
            if (!isPrivateCidr(subnet.cidr())) {
                log.warn("SCOPE_VIOLATION: public CIDR rejected — {}", subnet.cidr());
                throw new ErrorResponseException(HttpStatus.BAD_REQUEST,
                        problem(HttpStatus.BAD_REQUEST, "SCOPE_VIOLATION",
                                "CIDR is not within RFC1918 private ranges: " + subnet.cidr()), null);
            }
        }

        // Insert subnets
        List<AnonDiscoveredSubnet> savedSubnets = new ArrayList<>();
        for (AnonDiscoveryResultsRequest.SubnetDto dto : req.subnets()) {
            AnonDiscoveredSubnet subnet = new AnonDiscoveredSubnet();
            subnet.setSession(session);
            subnet.setCidr(dto.cidr());
            subnet.setIfaceName(dto.ifaceName());
            subnet.setSource("LOCAL_NIC");
            savedSubnets.add(subnetRepository.save(subnet));
        }

        // Build a CIDR → subnet map for device-to-subnet resolution
        Map<String, AnonDiscoveredSubnet> cidrToSubnet = savedSubnets.stream()
                .collect(Collectors.toMap(AnonDiscoveredSubnet::getCidr, s -> s));

        int tlsTotal = 0;

        // Insert devices — NO IP stored
        for (AnonDiscoveryResultsRequest.DeviceDto dto : req.devices()) {
            AnonDiscoveredSubnet parentSubnet = cidrToSubnet.get(dto.subnetCidr());
            if (parentSubnet == null) {
                log.warn("Device references unknown subnet CIDR '{}' — skipping", dto.subnetCidr());
                continue;
            }

            AnonDiscoveredDevice device = new AnonDiscoveredDevice();
            device.setSession(session);
            device.setSubnet(parentSubnet);
            device.setDeviceClass(dto.deviceClass() != null ? dto.deviceClass() : DeviceClass.UNKNOWN);

            List<Integer> openPorts = dto.openPorts() != null ? dto.openPorts() : List.of();
            device.setOpenPortCount(openPorts.size());
            device.setTlsPortCount(dto.tlsPortCount());
            device.setOpenPorts(openPorts.stream().mapToInt(Integer::intValue).toArray());

            if (dto.banners() != null && !dto.banners().isEmpty()) {
                try {
                    device.setBanners(OBJECT_MAPPER.writeValueAsString(dto.banners()));
                } catch (Exception e) {
                    log.warn("Failed to serialize device banners: {}", e.getMessage());
                }
            }

            if (dto.tlsSubjects() != null) {
                device.setTlsSubjects(dto.tlsSubjects().toArray(new String[0]));
                tlsTotal += dto.tlsPortCount();
            }
            device.setTlsExpiryMin(dto.tlsExpiryMin());
            deviceRepository.save(device);
        }

        // Transition to SCAN_COMPLETE (one-scan rule)
        session.setStatus(AnonSessionStatus.SCAN_COMPLETE);
        session.setSubnetCount(savedSubnets.size());
        session.setDeviceCount(req.devices().size());
        session.setTlsFoundCount(tlsTotal);
        sessionRepository.save(session);

        log.info("Anon scan complete — id: {}, subnets: {}, devices: {}, tls: {}",
                session.getId(), savedSubnets.size(), req.devices().size(), tlsTotal);
    }

    // ── Dashboard ─────────────────────────────────────────────────────────────

    /**
     * Returns the public dashboard data for a given raw viewToken.
     * No authentication required — the viewToken itself is the proof of access.
     */
    public AnonSessionDashboardResponse getSessionForView(String rawViewToken) {
        String hash = sha256Hex(rawViewToken);
        AnonScanSession session = sessionRepository.findByViewTokenHash(hash)
                .orElseThrow(() -> new ResourceNotFoundException("Session not found"));

        if (session.getStatus() == AnonSessionStatus.DELETED) {
            throw new ResourceNotFoundException("Session not found");
        }
        if (Instant.now().isAfter(session.getViewExpiresAt())) {
            throw new ResourceNotFoundException("Session has expired");
        }

        List<AnonDiscoveredSubnet> subnets = subnetRepository.findBySession_Id(session.getId());
        List<AnonDiscoveredDevice> devices  = deviceRepository.findBySession_Id(session.getId());

        // Build subnet → device counts
        Map<UUID, List<AnonDiscoveredDevice>> devicesBySubnet = devices.stream()
                .collect(Collectors.groupingBy(d -> d.getSubnet().getId()));

        List<AnonSessionDashboardResponse.SubnetDto> subnetDtos = subnets.stream()
                .map(s -> {
                    List<AnonDiscoveredDevice> subDevices =
                            devicesBySubnet.getOrDefault(s.getId(), List.of());
                    int tlsCount = subDevices.stream().mapToInt(AnonDiscoveredDevice::getTlsPortCount).sum();
                    return new AnonSessionDashboardResponse.SubnetDto(
                            s.getId(), s.getCidr(), subDevices.size(), tlsCount);
                })
                .collect(Collectors.toList());

        List<AnonSessionDashboardResponse.DeviceDto> deviceDtos = devices.stream()
                .map(d -> {
                    Map<String, String> banners = parseBanners(d.getBanners());
                    List<String> tlsSubjects = d.getTlsSubjects() != null
                            ? Arrays.asList(d.getTlsSubjects()) : List.of();
                    return new AnonSessionDashboardResponse.DeviceDto(
                            d.getId(),
                            d.getSubnet().getCidr(),
                            d.getDeviceClass(),
                            d.getOpenPorts(),
                            banners,
                            tlsSubjects,
                            d.getTlsExpiryMin()
                    );
                })
                .collect(Collectors.toList());

        int routerCount  = (int) devices.stream()
                .filter(d -> d.getDeviceClass() == DeviceClass.ROUTER).count();
        int serverCount  = (int) devices.stream()
                .filter(d -> d.getDeviceClass() == DeviceClass.SERVER).count();

        AnonSessionDashboardResponse.SummaryDto summary = new AnonSessionDashboardResponse.SummaryDto(
                session.getSubnetCount(), session.getDeviceCount(),
                session.getTlsFoundCount(), routerCount, serverCount);

        return new AnonSessionDashboardResponse(
                session.getStatus(), session.getScanExpiresAt(), session.getViewExpiresAt(),
                summary, subnetDtos, deviceDtos);
    }

    // ── Claim & delete ────────────────────────────────────────────────────────

    /**
     * Claims a completed anonymous session into an org.
     * Copies subnet CIDRs as a seed list, marks session CLAIMED, purges anon device rows.
     * Requires a valid JWT (caller must be org member).
     *
     * @return the list of subnet CIDRs from the anon session (for pre-populating network scan form)
     */
    @Transactional
    public List<String> claimSession(String rawViewToken, UUID orgId, UUID userId) {
        String hash = sha256Hex(rawViewToken);
        AnonScanSession session = sessionRepository.findByViewTokenHash(hash)
                .orElseThrow(() -> new ResourceNotFoundException("Session not found"));

        if (Instant.now().isAfter(session.getViewExpiresAt())) {
            throw new ResourceNotFoundException("Session has expired");
        }
        if (session.getStatus() == AnonSessionStatus.CLAIMED) {
            throw new ErrorResponseException(HttpStatus.CONFLICT,
                    problem(HttpStatus.CONFLICT, "ALREADY_CLAIMED",
                            "This session has already been claimed"), null);
        }
        if (session.getStatus() != AnonSessionStatus.SCAN_COMPLETE) {
            throw new ErrorResponseException(HttpStatus.CONFLICT,
                    problem(HttpStatus.CONFLICT, "SCAN_NOT_COMPLETE",
                            "Session scan is not yet complete"), null);
        }

        // Collect subnet CIDRs before purging
        List<AnonDiscoveredSubnet> subnets = subnetRepository.findBySession_Id(session.getId());
        List<String> subnetCidrs = subnets.stream()
                .map(AnonDiscoveredSubnet::getCidr).collect(Collectors.toList());

        // Purge anon device rows immediately after claim
        deviceRepository.deleteAll(deviceRepository.findBySession_Id(session.getId()));
        subnetRepository.deleteAll(subnets);

        session.setStatus(AnonSessionStatus.CLAIMED);
        session.setClaimedByOrgId(orgId);
        session.setClaimedAt(Instant.now());
        sessionRepository.save(session);

        log.info("Anon session claimed — id: {}, org: {}, user: {}, subnets: {}",
                session.getId(), orgId, userId, subnetCidrs);

        return subnetCidrs;
    }

    /**
     * Soft-deletes the session. Actual hard-delete is performed by AnonSessionPurgeScheduler.
     */
    @Transactional
    public void deleteSession(String rawViewToken) {
        String hash = sha256Hex(rawViewToken);
        AnonScanSession session = sessionRepository.findByViewTokenHash(hash)
                .orElseThrow(() -> new ResourceNotFoundException("Session not found"));

        session.setStatus(AnonSessionStatus.DELETED);
        sessionRepository.save(session);
        log.info("Anon session soft-deleted — id: {}", session.getId());
    }

    // ── Rate limiting ─────────────────────────────────────────────────────────

    /**
     * In-memory sliding-window rate limiter.
     * Allows max 5 sessions per IP per 24h window.
     * The IP is NOT stored anywhere beyond this ephemeral map.
     */
    private void enforceRateLimit(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) return;

        long now = System.currentTimeMillis();
        rateLimitMap.compute(clientIp, (ip, existing) -> {
            if (existing == null || (now - existing[1]) > RATE_LIMIT_WINDOW_MS) {
                return new long[]{ 1, now };
            }
            existing[0]++;
            return existing;
        });

        long[] state = rateLimitMap.get(clientIp);
        if (state != null && state[0] > RATE_LIMIT_MAX) {
            throw new ErrorResponseException(HttpStatus.TOO_MANY_REQUESTS,
                    problem(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                            "Too many anonymous sessions created from this IP"), null);
        }

        // Evict old entries periodically (simple cleanup — production would use a scheduled task)
        if (rateLimitMap.size() > 50_000) {
            rateLimitMap.entrySet().removeIf(e -> (now - e.getValue()[1]) > RATE_LIMIT_WINDOW_MS);
        }
    }

    // ── RFC1918 enforcement ───────────────────────────────────────────────────

    private boolean isPrivateCidr(String cidr) {
        try {
            String[] parts = cidr.split("/");
            InetAddress addr = InetAddress.getByName(parts[0]);
            for (String privateRange : PRIVATE_RANGES) {
                if (isInCidr(addr, privateRange)) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
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

    // ── Crypto helpers ────────────────────────────────────────────────────────

    private String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return hexEncode(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private String hexEncode(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Map<String, String> parseBanners(String json) {
        if (json == null) return null;
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    private ProblemDetail problem(HttpStatus status, String errorCode, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setProperty("errorCode", errorCode);
        return pd;
    }
}
