package com.certguard.service;

import com.certguard.dto.request.AgentRegisterRequest;
import com.certguard.dto.request.AgentScanResultRequest;
import com.certguard.dto.response.AgentResponse;
import com.certguard.dto.response.RegistrationTokenResponse;
import com.certguard.dto.response.ScanJobResponse;
import com.certguard.entity.*;
import com.certguard.enums.AgentStatus;
import com.certguard.enums.CertStatus;
import com.certguard.enums.ScanJobStatus;
import com.certguard.exception.ResourceNotFoundException;
import com.certguard.repository.*;
import com.certguard.security.AgentHmacService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    private final AgentRepository agentRepository;
    private final AgentRegistrationTokenRepository tokenRepository;
    private final AgentScanJobRepository scanJobRepository;
    private final TargetRepository targetRepository;
    private final CertificateRecordRepository certRepository;
    private final OrganizationRepository orgRepository;
    private final AgentHmacService hmacService;
    private final BCryptPasswordEncoder passwordEncoder;
    private final SubscriptionGuard subscriptionGuard;
    private final ExpiryEvaluationService expiryEvaluationService;

    /** Trigger-source constant used when a job is queued by the user (force/manual scan). */
    public static final String TRIGGER_USER      = "USER";
    /** Trigger-source constant used for all system/sweep-originated jobs. */
    public static final String TRIGGER_SCHEDULED = "SCHEDULED";

    @Transactional
    public RegistrationTokenResponse generateRegistrationToken(UUID orgId, String agentName, UUID createdBy) {
        String plainToken = "CGR-" + UUID.randomUUID().toString().toUpperCase();
        String tokenHash  = passwordEncoder.encode(plainToken);

        Organization org = orgRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));

        AgentRegistrationToken token = AgentRegistrationToken.builder()
                .organization(org)
                .tokenHash(tokenHash)
                .agentName(agentName)
                .used(false)
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .createdBy(createdBy)
                .build();

        tokenRepository.save(token);
        log.info("Registration token generated for agent '{}' in org {}", agentName, orgId);

        return RegistrationTokenResponse.builder()
                .tokenId(token.getId())
                .agentName(agentName)
                .token(plainToken)
                .expiresAt(token.getExpiresAt())
                .build();
    }

    @Transactional
    public AgentResponse register(AgentRegisterRequest request, UUID orgId) throws Exception {
        List<AgentRegistrationToken> candidates = tokenRepository.findAllByOrganizationId(orgId);

        AgentRegistrationToken matchedToken = candidates.stream()
                .filter(t -> !t.getUsed()
                        && t.getExpiresAt().isAfter(Instant.now())
                        && passwordEncoder.matches(request.getRegistrationToken(), t.getTokenHash())
                        && t.getAgentName().equals(request.getAgentName()))
                .findFirst()
                .orElseThrow(() -> new SecurityException("Invalid, expired or already-used registration token"));

        String plainAgentKey = "AGK-" + UUID.randomUUID().toString().replace("-", "")
                             + UUID.randomUUID().toString().replace("-", "");
        String agentKeyHash = passwordEncoder.encode(plainAgentKey);

        Organization org = matchedToken.getOrganization();

        // If the token was issued via the bundle flow, update the pre-created PENDING
        // agent row instead of inserting a new one (prevents duplicate list entries).
        Agent agent;
        if (matchedToken.getAgentId() != null) {
            agent = agentRepository.findById(matchedToken.getAgentId())
                    .orElseGet(() -> Agent.builder().organization(org).build());
            agent.setName(request.getAgentName());
            agent.setAllowedCidrs(request.getAllowedCidrs());
            agent.setMaxTargets(request.getMaxTargets());
        } else {
            agent = Agent.builder()
                    .organization(org)
                    .name(request.getAgentName())
                    .allowedCidrs(request.getAllowedCidrs())
                    .maxTargets(request.getMaxTargets())
                    .build();
        }
        agent.setAgentKeyHash(agentKeyHash);
        agent.setStatus(AgentStatus.ACTIVE);
        agent.setRegisteredAt(Instant.now());
        agent.setLastSeenAt(Instant.now());

        agent = agentRepository.save(agent);

        matchedToken.setUsed(true);
        tokenRepository.save(matchedToken);

        log.info("Agent registered: {} ({}) for org {}", agent.getName(), agent.getId(), orgId);

        return AgentResponse.builder()
                .id(agent.getId())
                .name(agent.getName())
                .status(agent.getStatus())
                .allowedCidrs(agent.getAllowedCidrs())
                .maxTargets(agent.getMaxTargets())
                .currentTargetCount(0)
                .registeredAt(agent.getRegisteredAt())
                .agentKey(plainAgentKey)
                .createdAt(agent.getCreatedAt())
                .locationId(agent.getLocation() != null ? agent.getLocation().getId() : null)
                .locationName(agent.getLocation() != null ? agent.getLocation().getName() : null)
                .build();
    }

    @Transactional
    public void heartbeat(Agent agent) {
        agent.setLastSeenAt(Instant.now());
        agentRepository.save(agent);
    }

    @Transactional
    public List<ScanJobResponse> pollJobs(Agent agent) {
        // Use FOR UPDATE SKIP LOCKED to atomically claim jobs without races.
        // Cap at max-targets to bound the work per poll cycle.
        List<AgentScanJob> pending = scanJobRepository.claimPendingJobsWithLock(
                agent.getId(), agent.getMaxTargets());
        Instant now = Instant.now();
        pending.forEach(job -> {
            job.setStatus(ScanJobStatus.CLAIMED);
            job.setClaimedAt(now);
        });
        scanJobRepository.saveAll(pending);

        return pending.stream().map(job -> {
            Optional<CertificateRecord> lastCert = certRepository
                    .findTopByTargetIdOrderByScannedAtDesc(job.getTarget().getId());
            return ScanJobResponse.builder()
                    .jobId(job.getId())
                    .targetId(job.getTarget().getId())
                    .host(job.getTarget().getHost())
                    .port(job.getTarget().getPort())
                    .lastKnownSerialHash(lastCert.map(c -> sha256Hex(c.getSerialNumber())).orElse(null))
                    .lastCertificateId(lastCert.map(BaseEntity::getId).orElse(null))
                    .build();
        }).collect(Collectors.toList());
    }

    @Transactional
    public void submitResult(Agent agent, AgentScanResultRequest request, String plainAgentKey) {
        boolean hmacValid = hmacService.verify(plainAgentKey, request, request.getHmacSignature());
        if (!hmacValid) {
            throw new SecurityException("HMAC signature verification failed");
        }

        AgentScanJob job = scanJobRepository
                .findByIdAndAgentId(request.getJobId(), agent.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Scan job not found for this agent"));

        Target target = targetRepository.findById(request.getTargetId())
                .orElseThrow(() -> new ResourceNotFoundException("Target not found"));

        if (target.getAgent() == null || !agent.getId().equals(target.getAgent().getId())) {
            throw new SecurityException("Target is not assigned to this agent");
        }

        validateCidr(target.getHost(), agent.getAllowedCidrs());

        // Capture the previous lastScannedAt BEFORE the scan overwrites it — required for
        // FORCE debounce in ExpiryEvaluationService (RFC 0008 §5).
        Instant previousLastScannedAt = target.getLastScannedAt();

        // Map job's trigger_source to EvaluationMode (RFC 0008 §6.3).
        ExpiryEvaluationService.EvaluationMode evalMode =
                TRIGGER_USER.equals(job.getTriggerSource())
                        ? ExpiryEvaluationService.EvaluationMode.FORCE
                        : ExpiryEvaluationService.EvaluationMode.SCHEDULED;

        if ("FULL".equals(request.getScanType())) {
            processFull(agent, target, request, evalMode, previousLastScannedAt);
        } else if ("DELTA".equals(request.getScanType())) {
            processDelta(target, request, evalMode, previousLastScannedAt);
        } else {
            throw new IllegalArgumentException("Unknown scanType: " + request.getScanType());
        }

        target.setLastScannedAt(Instant.now());
        targetRepository.save(target);

        job.setStatus(ScanJobStatus.COMPLETED);
        job.setResultType(request.getScanType());
        job.setCompletedAt(Instant.now());
        scanJobRepository.save(job);

        log.info("Scan result processed — agent: {}, target: {}, type: {}",
                agent.getName(), target.getHost(), request.getScanType());
    }

    private void processFull(Agent agent, Target target, AgentScanResultRequest req,
                             ExpiryEvaluationService.EvaluationMode evalMode,
                             Instant previousLastScannedAt) {
        CertificateRecord record = certRepository
                .findByTargetIdAndSerialNumber(target.getId(), req.getSerialNumber())
                .orElse(CertificateRecord.builder()
                        .target(target)
                        .orgId(target.getOrganization().getId())
                        .serialNumber(req.getSerialNumber())
                        .build());

        record.setCommonName(req.getCommonName());
        record.setIssuer(req.getIssuer());
        record.setNotBefore(req.getNotBefore());
        record.setExpiryDate(req.getNotAfter());
        record.setKeyAlgorithm(req.getKeyAlgorithm());
        record.setKeySize(req.getKeySize());
        record.setSignatureAlgorithm(req.getSignatureAlgorithm());
        record.setSubjectAltNames(req.getSubjectAltNames());
        record.setChainDepth(req.getChainDepth());
        record.setPublicCertB64(req.getPublicCertB64());
        record.setStatus(expiryEvaluationService.determineCertStatus(req.getNotAfter(), target, target.getOrganization().getId()));
        record.setScannedByAgent(agent);
        record.setScannedAt(Instant.now());
        certRepository.save(record);

        // Post-scan expiry evaluation (RFC 0008 §7).
        expiryEvaluationService.evaluateAndNotify(record, evalMode, previousLastScannedAt);
    }

    private void processDelta(Target target, AgentScanResultRequest req,
                              ExpiryEvaluationService.EvaluationMode evalMode,
                              Instant previousLastScannedAt) {
        if (req.getCertificateId() == null)
            throw new IllegalArgumentException("DELTA result must include certificateId");

        CertificateRecord existing = certRepository.findById(req.getCertificateId())
                .orElseThrow(() -> new ResourceNotFoundException("Certificate record not found"));

        existing.setExpiryDate(req.getNotAfter());
        existing.setStatus(expiryEvaluationService.determineCertStatus(req.getNotAfter(), target, target.getOrganization().getId()));
        existing.setScannedAt(Instant.now());
        certRepository.save(existing);

        // Post-scan expiry evaluation (RFC 0008 §7).
        expiryEvaluationService.evaluateAndNotify(existing, evalMode, previousLastScannedAt);
    }

    /**
     * Queues a scan job for a private target. Defaults to SCHEDULED trigger source
     * (used by the nightly private-scan sweep). Use the overload with triggerSource
     * for user-initiated (FORCE) scans.
     */
    @Transactional
    public void queueScanJob(Target target) {
        queueScanJob(target.getId(), target.getOrganization().getId(), TRIGGER_SCHEDULED);
    }

    /**
     * User-facing overload — called from TargetService.triggerScan for manual scans.
     * Passes TRIGGER_USER so submitResult selects EvaluationMode.FORCE (RFC 0008 §6.3).
     */
    @Transactional
    public void queueScanJob(Target target, String triggerSource) {
        queueScanJob(target.getId(), target.getOrganization().getId(), triggerSource);
    }

    @Transactional
    public void queueScanJob(UUID targetId, UUID orgId) {
        // Preserve existing callers — default to SCHEDULED.
        queueScanJob(targetId, orgId, TRIGGER_SCHEDULED);
    }

    @Transactional
    public void queueScanJob(UUID targetId, UUID orgId, String triggerSource) {
        subscriptionGuard.assertScansAllowed(orgId);
        Target target = targetRepository.findByIdAndOrganizationId(targetId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Target not found"));

        if (target.getAgent() == null)
            throw new IllegalStateException("Target has no assigned agent");

        boolean alreadyPending = scanJobRepository.existsByTargetIdAndStatusIn(
                targetId, List.of(ScanJobStatus.PENDING, ScanJobStatus.CLAIMED));
        if (alreadyPending) {
            log.info("Scan job already pending for target {}", targetId);
            return;
        }

        AgentScanJob job = AgentScanJob.builder()
                .agent(target.getAgent()).target(target).orgId(orgId)
                .status(ScanJobStatus.PENDING)
                .triggerSource(triggerSource)
                .build();
        scanJobRepository.save(job);
        log.info("Scan job queued — target: {}, agent: {}, source: {}",
                target.getHost(), target.getAgent().getName(), triggerSource);
    }

    public List<AgentResponse> listAgents(UUID orgId) {
        return agentRepository.findAllByOrganizationId(orgId).stream()
                .map(a -> AgentResponse.builder()
                        .id(a.getId()).name(a.getName()).status(a.getStatus())
                        .allowedCidrs(a.getAllowedCidrs()).maxTargets(a.getMaxTargets())
                        .currentTargetCount(a.getCurrentTargetCount())
                        .lastSeenAt(a.getLastSeenAt()).registeredAt(a.getRegisteredAt())
                        .createdAt(a.getCreatedAt())
                        .locationId(a.getLocation() != null ? a.getLocation().getId() : null)
                        .locationName(a.getLocation() != null ? a.getLocation().getName() : null)
                        .build())
                .collect(Collectors.toList());
    }

    @Transactional
    public void revokeAgent(UUID agentId, UUID orgId) {
        Agent agent = agentRepository.findByIdAndOrganizationId(agentId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Agent not found"));
        agent.setStatus(AgentStatus.REVOKED);
        agentRepository.save(agent);
        log.warn("Agent revoked: {} ({})", agent.getName(), agentId);
    }

    /**
     * Resets CLAIMED jobs that have been stuck for more than 10 minutes back to PENDING.
     * Handles the case where an agent went offline mid-scan.
     * Runs every 5 minutes.
     */
    @Scheduled(fixedDelay = 300_000)
    @SchedulerLock(name = "AgentService_resetStaleClaimedJobs",
                   lockAtMostFor = "PT5M", lockAtLeastFor = "PT4M")
    @Transactional
    public void resetStaleClaimedJobs() {
        Instant threshold = Instant.now().minus(10, ChronoUnit.MINUTES);
        List<AgentScanJob> stale = scanJobRepository.findStaleClaimedJobs(threshold);
        if (stale.isEmpty()) return;
        stale.forEach(job -> {
            job.setStatus(ScanJobStatus.PENDING);
            job.setClaimedAt(null);
            log.warn("Reset stale CLAIMED job {} for target {} — agent may be offline",
                    job.getId(), job.getTarget().getHost());
        });
        scanJobRepository.saveAll(stale);
    }

    @Scheduled(fixedDelay = 300_000)
    @SchedulerLock(name = "AgentService_cleanupExpiredTokens",
                   lockAtMostFor = "PT5M", lockAtLeastFor = "PT4M")
    @Transactional
    public void cleanupExpiredTokens() {
        List<AgentRegistrationToken> expired = tokenRepository.findExpiredAndUsed(Instant.now());
        // Skip tokens still linked to a PENDING bundle agent — deleting them would
        // orphan the agent row and prevent it from ever completing registration.
        List<AgentRegistrationToken> toDelete = expired.stream()
                .filter(t -> t.getAgentId() == null)
                .collect(Collectors.toList());
        tokenRepository.deleteAll(toDelete);
    }

    private void validateCidr(String host, List<String> allowedCidrs) {
        if (allowedCidrs == null || allowedCidrs.isEmpty())
            throw new SecurityException("Agent has no allowed CIDR ranges");
        if (!host.matches("(\\d{1,3}\\.){3}\\d{1,3}")) return;
        try {
            InetAddress targetAddr = InetAddress.getByName(host);
            for (String cidr : allowedCidrs) { if (isInCidr(targetAddr, cidr)) return; }
            throw new SecurityException("Host " + host + " not within allowed CIDR ranges");
        } catch (UnknownHostException e) {
            throw new SecurityException("Cannot resolve host: " + host);
        }
    }

    private boolean isInCidr(InetAddress address, String cidr) {
        try {
            String[] parts = cidr.split("/");
            InetAddress network = InetAddress.getByName(parts[0]);
            int prefix = Integer.parseInt(parts[1]);
            byte[] a = address.getAddress(), n = network.getAddress();
            if (a.length != n.length) return false;
            int full = prefix / 8, rem = prefix % 8;
            for (int i = 0; i < full; i++) { if (a[i] != n[i]) return false; }
            if (rem > 0) { int mask = 0xFF & (0xFF << (8 - rem)); return (a[full] & mask) == (n[full] & mask); }
            return true;
        } catch (Exception e) { return false; }
    }

    private String sha256Hex(String input) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes());
            var sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return input; }
    }
}
