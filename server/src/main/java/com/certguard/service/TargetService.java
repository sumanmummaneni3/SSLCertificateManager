package com.certguard.service;

import com.certguard.dto.request.CreateTargetRequest;
import com.certguard.dto.request.UpdateTargetRequest;
import com.certguard.dto.response.CertificateSummary;
import com.certguard.dto.response.ScanStatusResponse;
import com.certguard.dto.response.TargetResponse;
import com.certguard.entity.*;
import com.certguard.enums.HostType;
import com.certguard.exception.QuotaExceededException;
import com.certguard.exception.ResourceNotFoundException;
import com.certguard.repository.*;
import java.util.Map;
import com.certguard.util.HostTypeDetector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TargetService {

    private final TargetRepository targetRepository;
    private final OrganizationRepository organizationRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final CertificateRecordRepository certRepository;
    private final AgentRepository agentRepository;
    private final AgentScanJobRepository scanJobRepository;
    private final LocationRepository locationRepository;
    private final SslScannerService sslScannerService;
    private final SubscriptionGuard subscriptionGuard;

    @Transactional(readOnly = true)
    public Page<TargetResponse> listTargets(UUID orgId, Pageable pageable) {
        Page<Target> page = targetRepository.findAllByOrganizationId(orgId, pageable);

        // Batch-load the latest cert for every target in one query to avoid N+1.
        List<UUID> targetIds = page.stream().map(Target::getId).collect(Collectors.toList());
        Map<UUID, CertificateRecord> latestCertByTarget = certRepository
                .findLatestByTargetIds(targetIds)
                .stream()
                // Ordered DESC by scannedAt; keep only the first occurrence per targetId.
                .collect(Collectors.toMap(
                        c -> c.getTarget().getId(),
                        c -> c,
                        (existing, replacement) -> existing));  // keep first (latest)

        return page.map(target -> toResponse(target, latestCertByTarget.get(target.getId())));
    }

    @Transactional
    public TargetResponse createTarget(UUID orgId, CreateTargetRequest request) {
        subscriptionGuard.assertScansAllowed(orgId);
        enforceTargetQuota(orgId);
        String host = request.getHost().trim().toLowerCase();

        if (targetRepository.existsByOrganizationIdAndHostAndPort(orgId, host, request.getPort()))
            throw new IllegalArgumentException("Target already exists: " + host + ":" + request.getPort());

        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));

        HostType hostType = HostTypeDetector.detect(host);
        boolean isPrivate = request.isPrivate();
        if (!isPrivate && HostTypeDetector.shouldDefaultToPrivate(host)) {
            isPrivate = true;
            log.info("Auto-set isPrivate=true for internal host: {}", host);
        }

        Agent agent = null;
        if (isPrivate && request.getAgentId() != null) {
            agent = agentRepository.findByIdAndOrganizationId(request.getAgentId(), orgId)
                    .orElseThrow(() -> new ResourceNotFoundException("Agent not found"));

            // Quota check
            if (agent.getCurrentTargetCount() >= agent.getMaxTargets())
                throw new QuotaExceededException(
                    "Agent has reached its max-targets limit of " + agent.getMaxTargets()
                    + ". Remove existing targets or increase the limit.");

            // CIDR validation — only for IP addresses, skip for hostnames
            if (hostType == HostType.IP || hostType == HostType.DOMAIN) {
                validateHostInAgentCidrs(host, agent);
            } else {
                log.info("Skipping CIDR check for hostname '{}' — will be enforced by agent at scan time", host);
            }
        }

        // Resolve optional location
        com.certguard.entity.Location location = null;
        if (request.getLocationId() != null) {
            location = locationRepository.findByIdAndOrganizationId(request.getLocationId(), orgId)
                    .orElseThrow(() -> new com.certguard.exception.ResourceNotFoundException("Location not found: " + request.getLocationId()));
        }

        Target target = Target.builder()
                .organization(org).host(host).port(request.getPort())
                .hostType(hostType).isPrivate(isPrivate)
                .description(request.getDescription()).agent(agent).enabled(true)
                .location(location)
                .notificationChannels(request.getNotificationChannels() != null ? request.getNotificationChannels() : new java.util.HashMap<>())
                .build();
        target = targetRepository.save(target);

        if (agent != null) {
            agent.setCurrentTargetCount(agent.getCurrentTargetCount() + 1);
            agentRepository.save(agent);
        }

        if (!isPrivate) {
            sslScannerService.scanTargetAsync(target);
        }

        log.info("Target created: {} [{}] :{} private={}", host, hostType, target.getPort(), isPrivate);
        return toResponse(target);
    }

    @Transactional
    public TargetResponse updateTarget(UUID orgId, UUID targetId, UpdateTargetRequest request) {
        Target target = findTargetForOrg(orgId, targetId);
        if (request.getHost() != null) {
            String host = request.getHost().trim().toLowerCase();
            int newPort = request.getPort() != null ? request.getPort() : target.getPort();
            if (!host.equals(target.getHost()) || newPort != target.getPort()) {
                if (targetRepository.existsByOrganizationIdAndHostAndPort(orgId, host, newPort))
                    throw new IllegalArgumentException("Target already exists: " + host + ":" + newPort);
            }
            target.setHost(host);
            target.setHostType(HostTypeDetector.detect(host));
        }
        if (request.getPort() != null)        target.setPort(request.getPort());
        if (request.getIsPrivate() != null)   target.setIsPrivate(request.getIsPrivate());
        if (request.getEnabled() != null)     target.setEnabled(request.getEnabled());
        if (request.getDescription() != null) target.setDescription(request.getDescription());

        if (request.getAgentId() != null) {
            Agent newAgent = agentRepository.findByIdAndOrganizationId(request.getAgentId(), orgId)
                    .orElseThrow(() -> new ResourceNotFoundException("Agent not found"));

            boolean isNewAssignment = target.getAgent() == null
                    || !target.getAgent().getId().equals(request.getAgentId());

            if (isNewAssignment) {
                if (newAgent.getCurrentTargetCount() >= newAgent.getMaxTargets())
                    throw new QuotaExceededException(
                        "Agent has reached its max-targets limit of " + newAgent.getMaxTargets());

                if (target.getAgent() != null) {
                    Agent old = target.getAgent();
                    old.setCurrentTargetCount(Math.max(0, old.getCurrentTargetCount() - 1));
                    agentRepository.save(old);
                }

                newAgent.setCurrentTargetCount(newAgent.getCurrentTargetCount() + 1);
                agentRepository.save(newAgent);
            }

            target.setAgent(newAgent);
            target.setIsPrivate(true);
            log.info("Agent '{}' assigned to target {}", newAgent.getName(), target.getHost());
        }

        return toResponse(targetRepository.save(target));
    }

    @Transactional
    public void deleteTarget(UUID orgId, UUID targetId) {
        Target target = findTargetForOrg(orgId, targetId);

        // Decrement agent target count when a private target is removed
        if (target.getIsPrivate() && target.getAgent() != null) {
            Agent agent = target.getAgent();
            int newCount = Math.max(0, agent.getCurrentTargetCount() - 1);
            agent.setCurrentTargetCount(newCount);
            agentRepository.save(agent);
            log.info("Decremented target count for agent '{}' to {}", agent.getName(), newCount);
        }

        targetRepository.delete(target);
    }

    /**
     * Triggers a scan for a target.
     * - Public target → direct SSL scan via SslScannerService (synchronous)
     * - Private target → queue an agent_scan_job (async, agent picks up within poll interval)
     */
    @Transactional
    public String triggerScan(UUID orgId, UUID targetId,
                               SslScannerService sslScannerService,
                               AgentService agentService) {
        subscriptionGuard.assertScansAllowed(orgId);
        Target target = findTargetForOrg(orgId, targetId);

        if (!target.getIsPrivate()) {
            // Public — scan directly
            sslScannerService.scanTarget(target);
            return "Scan triggered for " + target.getHost();
        }

        // Private — queue job for agent
        if (target.getAgent() == null) {
            throw new IllegalStateException(
                "Private target has no assigned agent. Assign an agent before scanning.");
        }
        agentService.queueScanJob(target);
        return "Scan job queued for agent '" + target.getAgent().getName() + "'";
    }

    /**
     * Returns the latest scan job status for a target.
     * Used by the UI to poll PENDING → CLAIMED → COMPLETED after triggering a scan.
     */
    @Transactional(readOnly = true)
    public ScanStatusResponse getLatestScanStatus(UUID orgId, UUID targetId) {
        findTargetForOrg(orgId, targetId); // auth check
        List<AgentScanJob> jobs = scanJobRepository.findByTargetIdOrderByCreatedAtDesc(targetId);
        if (jobs.isEmpty()) return null;
        AgentScanJob job = jobs.get(0);
        return ScanStatusResponse.builder()
                .jobId(job.getId())
                .targetId(targetId)
                .status(job.getStatus())
                .resultType(job.getResultType())
                .errorMsg(job.getErrorMsg())
                .createdAt(job.getCreatedAt())
                .claimedAt(job.getClaimedAt())
                .completedAt(job.getCompletedAt())
                .build();
    }

    // ── Private helpers ────────────────────────────────────────

    private void validateHostInAgentCidrs(String host, Agent agent) {
        if (agent.getAllowedCidrs() == null || agent.getAllowedCidrs().isEmpty()) {
            log.warn("Agent '{}' has no allowed CIDRs — skipping CIDR validation", agent.getName());
            return;
        }
        try {
            InetAddress addr = InetAddress.getByName(host);
            byte[] addrBytes = addr.getAddress();
            for (String cidr : agent.getAllowedCidrs()) {
                if (isInCidr(addrBytes, cidr)) return; // passes
            }
            throw new IllegalArgumentException(
                "Host '" + host + "' is not within any of the agent's allowed CIDRs: "
                + agent.getAllowedCidrs()
                + ". Update the agent's allowed-cidrs setting to include this host.");
        } catch (java.net.UnknownHostException e) {
            log.warn("Could not resolve '{}' for CIDR check — allowing", host);
        }
    }

    private boolean isInCidr(byte[] addrBytes, String cidr) {
        try {
            String[] parts  = cidr.split("/");
            byte[] netBytes = InetAddress.getByName(parts[0]).getAddress();
            if (netBytes.length != addrBytes.length) return false;
            int prefix = Integer.parseInt(parts[1]);
            int full   = prefix / 8;
            int rem    = prefix % 8;
            for (int i = 0; i < full; i++)
                if (addrBytes[i] != netBytes[i]) return false;
            if (rem > 0) {
                int mask = 0xFF & (0xFF << (8 - rem));
                if ((addrBytes[full] & mask) != (netBytes[full] & mask)) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Transactional(readOnly = true)
    public TargetResponse getTarget(UUID orgId, UUID targetId) {
        return toResponse(findTargetForOrg(orgId, targetId));
    }

    @Transactional
    public TargetResponse updateNotificationChannels(UUID orgId, UUID targetId, Map<String, Object> channels) {
        if (channels != null && channels.size() > 20) {
            throw new IllegalArgumentException("notificationChannels map may not exceed 20 entries");
        }
        Target target = findTargetForOrg(orgId, targetId);
        target.setNotificationChannels(channels);
        return toResponse(targetRepository.save(target));
    }

    private Target findTargetForOrg(UUID orgId, UUID targetId) {
        return targetRepository.findByIdAndOrganizationId(targetId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Target not found: " + targetId));
    }

    private void enforceTargetQuota(UUID orgId) {
        UUID quotaOrgId = organizationRepository.findBillingOwner(orgId);
        Subscription sub = subscriptionRepository.findByOrganizationId(quotaOrgId)
                .orElseThrow(() -> new ResourceNotFoundException("No subscription found"));
        List<UUID> scope = new java.util.ArrayList<>(
                organizationRepository.findActiveChildIds(quotaOrgId));
        scope.add(quotaOrgId);
        long current = targetRepository.countByOrganizationIdIn(scope);
        if (current >= sub.getMaxCertificateQuota())
            throw new QuotaExceededException("Certificate quota of " + sub.getMaxCertificateQuota() + " reached. Contact your platform administrator to increase the limit.");
    }

    /**
     * Single-target overload used by getTarget() and createTarget() — loads the
     * latest cert record with a per-target query (acceptable for single lookups).
     */
    private TargetResponse toResponse(Target target) {
        Optional<CertificateRecord> latestCert = certRepository
                .findTopByTargetIdOrderByScannedAtDesc(target.getId());
        return toResponse(target, latestCert.orElse(null));
    }

    /**
     * Overload used by listTargets() — accepts a pre-loaded cert to avoid N+1.
     * {@code latestCert} may be null when no cert has been scanned yet.
     */
    private TargetResponse toResponse(Target target, CertificateRecord latestCert) {
        CertificateSummary certSummary = (latestCert != null) ? toCertSummary(latestCert) : null;

        return TargetResponse.builder()
                .id(target.getId()).host(target.getHost()).port(target.getPort())
                .hostType(target.getHostType()).isPrivate(target.getIsPrivate())
                .description(target.getDescription()).enabled(target.getEnabled())
                .lastScannedAt(target.getLastScannedAt())
                .lastErrorMessage(target.getLastErrorMessage())
                .lastErrorAt(target.getLastErrorAt())
                .createdAt(target.getCreatedAt())
                .agentId(target.getAgent() != null ? target.getAgent().getId() : null)
                .agentName(target.getAgent() != null ? target.getAgent().getName() : null)
                .locationId(target.getLocation() != null ? target.getLocation().getId() : null)
                .locationName(target.getLocation() != null ? target.getLocation().getName() : null)
                .notificationChannels(target.getNotificationChannels())
                .latestCertificate(certSummary)
                .build();
    }

    private CertificateSummary toCertSummary(CertificateRecord cert) {
        long days = ChronoUnit.DAYS.between(Instant.now(), cert.getExpiryDate());
        return CertificateSummary.builder()
                .id(cert.getId()).commonName(cert.getCommonName()).issuer(cert.getIssuer())
                .expiryDate(cert.getExpiryDate()).daysRemaining(days).status(cert.getStatus())
                .build();
    }
}
