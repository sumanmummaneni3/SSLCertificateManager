package com.certguard.service;

import com.certguard.dto.request.AgentRegisterRequest;
import com.certguard.dto.request.AgentScanResultRequest;
import com.certguard.dto.response.AgentResponse;
import com.certguard.dto.response.RegistrationTokenResponse;
import com.certguard.dto.response.ScanJobResponse;
import com.certguard.entity.*;
import com.certguard.enums.AgentStatus;
import com.certguard.enums.AgentType;
import com.certguard.enums.CertStatus;
import com.certguard.enums.ScanJobStatus;
import com.certguard.enums.ScanSourceType;
import com.certguard.exception.ResourceNotFoundException;
import com.certguard.repository.*;
import com.certguard.security.AgentHmacService;
import com.certguard.security.PublicAddressGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
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
@Transactional(readOnly = true)
public class AgentService {

    private final AgentRepository agentRepository;
    private final AgentRegistrationTokenRepository tokenRepository;
    private final AgentScanJobRepository scanJobRepository;
    private final TargetRepository targetRepository;
    private final CertificateRecordRepository certRepository;
    private final OrganizationRepository orgRepository;
    private final AgentHmacService hmacService;
    private final NetworkScanService networkScanService;
    private final BCryptPasswordEncoder passwordEncoder;
    private final SubscriptionGuard subscriptionGuard;
    private final CertificatePersistenceService certPersistenceService;

    @Value("${app.scanning.pool.claim-batch:25}")
    private int poolClaimBatch;

    @Value("${app.scanning.pool.max-attempts:3}")
    private int maxAttempts;

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

        Agent agent;
        if (matchedToken.getAgentId() != null) {
            agent = agentRepository.findById(matchedToken.getAgentId())
                    .orElseGet(() -> Agent.builder().organization(org).build());
            agent.setName(request.getAgentName());
            agent.setAllowedCidrs(request.getAllowedCidrs() != null
                    ? request.getAllowedCidrs() : new ArrayList<>());
            agent.setMaxTargets(request.getMaxTargets());
        } else {
            agent = Agent.builder()
                    .organization(org)
                    .name(request.getAgentName())
                    .allowedCidrs(request.getAllowedCidrs() != null
                            ? request.getAllowedCidrs() : new ArrayList<>())
                    .maxTargets(request.getMaxTargets())
                    .build();
        }
        if (request.getDiscoveredSubnets() != null && !request.getDiscoveredSubnets().isEmpty()) {
            agent.setDiscoveredSubnets(request.getDiscoveredSubnets());
        }
        agent.setAgentKeyHash(agentKeyHash);
        agent.setStatus(AgentStatus.ACTIVE);
        agent.setRegisteredAt(Instant.now());
        agent.setLastSeenAt(Instant.now());
        // agentType is retained: CUSTOMER by default, or PLATFORM_SCANNER if minted as such.

        agent = agentRepository.save(agent);

        matchedToken.setUsed(true);
        tokenRepository.save(matchedToken);

        log.info("Agent registered: {} ({}) for org {}", agent.getName(), agent.getId(), orgId);
        return buildAgentResponse(agent, plainAgentKey);
    }

    @Transactional
    public void heartbeat(Agent agent) {
        agent.setLastSeenAt(Instant.now());
        agentRepository.save(agent);
    }

    /**
     * Returns pending scan jobs for an agent.
     *
     * CUSTOMER agents claim AGENT_PINNED jobs for their assigned targets.
     * PLATFORM_SCANNER agents claim PUBLIC_POOL jobs from the shared pool.
     * Network scan jobs are only dispatched to CUSTOMER agents (RFC 0013 §3).
     */
    @Transactional
    public List<ScanJobResponse> pollJobs(Agent agent) {
        if (agent.getAgentType() == AgentType.PLATFORM_SCANNER) {
            return pollPoolJobs(agent);
        }
        return pollPinnedJobs(agent);
    }

    private List<ScanJobResponse> pollPinnedJobs(Agent agent) {
        List<AgentScanJob> pending = scanJobRepository.claimPendingJobsWithLock(
                agent.getId(), agent.getMaxTargets());
        Instant now = Instant.now();
        pending.forEach(job -> {
            job.setStatus(ScanJobStatus.CLAIMED);
            job.setClaimedAt(now);
        });
        scanJobRepository.saveAll(pending);

        List<ScanJobResponse> certJobs = pending.stream().map(job -> {
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

        // RFC 0011: merge network scan jobs for CUSTOMER agents only.
        List<ScanJobResponse> networkJobs = networkScanService.pollNetworkJobs(agent);
        if (!networkJobs.isEmpty()) {
            List<ScanJobResponse> combined = new ArrayList<>(certJobs);
            combined.addAll(networkJobs);
            return combined;
        }
        return certJobs;
    }

    /**
     * Claims PUBLIC_POOL jobs for a platform scanner.
     * Stamps agent_id on claim for audit + result verification.
     * DOES NOT merge network scan jobs (RFC 0013 §3).
     */
    private List<ScanJobResponse> pollPoolJobs(Agent agent) {
        List<AgentScanJob> pending = scanJobRepository.claimPublicPoolJobsWithLock(poolClaimBatch);
        Instant now = Instant.now();
        pending.forEach(job -> {
            job.setAgent(agent);
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

    /**
     * Processes a scan result submitted by an agent (FULL, DELTA, or ERROR).
     *
     * Security invariants (RFC 0013 §4):
     *   1. HMAC verified first on all paths.
     *   2. Job found by id AND agent_id (stamped on claim — prevents cross-agent spoofing).
     *   3. Job target-id == request.targetId on ALL paths (defense-in-depth / cross-tenant guard).
     *   4. AGENT_PINNED: target.agent == caller; CIDR validated.
     *   5. PUBLIC_POOL: agent.agentType == PLATFORM_SCANNER; PublicAddressGuard enforced.
     */
    @Transactional
    public void submitResult(Agent agent, AgentScanResultRequest request, String plainAgentKey) {
        boolean hmacValid = hmacService.verify(plainAgentKey, request, request.getHmacSignature());
        if (!hmacValid) {
            throw new SecurityException("HMAC signature verification failed");
        }

        AgentScanJob job = scanJobRepository
                .findByIdAndAgentId(request.getJobId(), agent.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Scan job not found for this agent"));

        // ── ERROR path (RFC 0013 §5) ─────────────────────────────────────────
        if ("ERROR".equals(request.getScanType())) {
            handleErrorResult(agent, job, request);
            return;
        }

        Target target = targetRepository.findById(request.getTargetId())
                .orElseThrow(() -> new ResourceNotFoundException("Target not found"));

        // ── Defense-in-depth: assert job<->target binding on ALL paths (RFC 0013 §4) ──
        // CRITICAL for PUBLIC_POOL: without this check, a compromised scanner could submit
        // a result for any org's target (cross-tenant result injection).
        if (!target.getId().equals(job.getTarget().getId())) {
            throw new SecurityException(
                    "Job/target binding mismatch: job targets " + job.getTarget().getId()
                    + " but result claims " + target.getId());
        }

        Instant previousLastScannedAt = target.getLastScannedAt();

        ExpiryEvaluationService.EvaluationMode evalMode =
                TRIGGER_USER.equals(job.getTriggerSource())
                        ? ExpiryEvaluationService.EvaluationMode.FORCE
                        : ExpiryEvaluationService.EvaluationMode.SCHEDULED;

        if (AgentScanJob.KIND_PUBLIC_POOL.equals(job.getJobKind())) {
            assertPoolSubmitSecurity(agent, job, target);
            processResult(agent, target, request, evalMode, previousLastScannedAt);
        } else {
            assertPinnedSubmitSecurity(agent, target);
            processResult(agent, target, request, evalMode, previousLastScannedAt);
        }

        target.setLastScannedAt(Instant.now());
        target.setLastErrorMessage(null);
        target.setLastErrorAt(null);
        targetRepository.save(target);

        job.setStatus(ScanJobStatus.COMPLETED);
        job.setResultType(request.getScanType());
        job.setCompletedAt(Instant.now());
        job.setErrorMsg(null);
        scanJobRepository.save(job);

        log.info("Scan result processed — agent: {}, target: {}, type: {}",
                agent.getName(), target.getHost(), request.getScanType());
    }

    // ── Security assertions ───────────────────────────────────────────────────

    private void assertPinnedSubmitSecurity(Agent agent, Target target) {
        if (target.getAgent() == null || !agent.getId().equals(target.getAgent().getId())) {
            throw new SecurityException("Target is not assigned to this agent");
        }
        validateCidr(target.getHost(), agent.getAllowedCidrs());
    }

    private void assertPoolSubmitSecurity(Agent agent, AgentScanJob job, Target target) {
        if (!AgentScanJob.KIND_PUBLIC_POOL.equals(job.getJobKind())) {
            throw new SecurityException("Job is not a PUBLIC_POOL job");
        }
        if (agent.getAgentType() != AgentType.PLATFORM_SCANNER) {
            throw new SecurityException(
                    "Only PLATFORM_SCANNER agents may submit PUBLIC_POOL results; "
                    + "agent " + agent.getId() + " has type " + agent.getAgentType());
        }
        try {
            PublicAddressGuard.assertPubliclyRoutable(target.getHost());
        } catch (PublicAddressGuard.PublicAddressGuardException e) {
            throw new SecurityException("SSRF guard rejected PUBLIC_POOL target: " + e.getMessage());
        }
    }

    // ── Result processing (delegates to CertificatePersistenceService) ────────

    private void processResult(Agent agent, Target target, AgentScanResultRequest req,
                               ExpiryEvaluationService.EvaluationMode evalMode,
                               Instant previousLastScannedAt) {
        // RFC 0013 §9: provenance is derived from the SUBMITTING agent's type, which
        // assertPoolSubmitSecurity/assertPinnedSubmitSecurity have already validated
        // matches the job kind — PLATFORM_SCANNER claimed a PUBLIC_POOL job (⇒ cloud
        // scanner, identity not exposed), CUSTOMER claimed an AGENT_PINNED job (⇒ named
        // customer agent).
        ScanSourceType scanSourceType = agent.getAgentType() == AgentType.PLATFORM_SCANNER
                ? ScanSourceType.CLOUD_SCANNER
                : ScanSourceType.CUSTOMER_AGENT;

        if ("FULL".equals(req.getScanType())) {
            byte[] ocspStaple = decodeOcspStaple(req.getOcspStapleB64());
            certPersistenceService.persistFull(
                    target, agent,
                    req.getSerialNumber(), req.getCommonName(), req.getIssuer(),
                    req.getNotBefore(), req.getNotAfter(),
                    req.getKeyAlgorithm(), req.getKeySize(), req.getSignatureAlgorithm(),
                    req.getSubjectAltNames(), req.getChainDepth(),
                    req.getPublicCertB64(), req.getChainB64(), ocspStaple,
                    evalMode, previousLastScannedAt, scanSourceType);
        } else if ("DELTA".equals(req.getScanType())) {
            if (req.getCertificateId() == null)
                throw new IllegalArgumentException("DELTA result must include certificateId");
            certPersistenceService.persistDelta(
                    target, req.getCertificateId(), req.getNotAfter(),
                    evalMode, previousLastScannedAt, scanSourceType);
        } else {
            throw new IllegalArgumentException("Unknown scanType: " + req.getScanType());
        }
    }

    // ── ERROR handling ────────────────────────────────────────────────────────

    private void handleErrorResult(Agent agent, AgentScanJob job, AgentScanResultRequest request) {
        int newAttempts = job.getAttempts() + 1;
        job.setAttempts(newAttempts);
        String errMsg = request.getErrorMessage();
        if (errMsg != null && errMsg.length() > 500) errMsg = errMsg.substring(0, 500);
        job.setErrorMsg(errMsg);

        if (newAttempts < maxAttempts) {
            job.setStatus(ScanJobStatus.PENDING);
            job.setClaimedAt(null);
            scanJobRepository.save(job);
            log.warn("ERROR result for job {} (attempt {}/{}) — re-queuing for target {}",
                    job.getId(), newAttempts, maxAttempts, job.getTarget().getHost());
        } else {
            job.setStatus(ScanJobStatus.FAILED);
            job.setCompletedAt(Instant.now());
            scanJobRepository.save(job);
            log.warn("ERROR result for job {} (attempt {}/{}) — marking FAILED for target {}",
                    job.getId(), newAttempts, maxAttempts, job.getTarget().getHost());
            checkAndMarkUnreachable(job.getTarget());
        }
    }

    /**
     * Checks whether the target has had two consecutive FAILED scan jobs.
     * If so, marks all its certificate records as UNREACHABLE (RFC 0013 §5).
     */
    private void checkAndMarkUnreachable(Target target) {
        List<AgentScanJob> lastTwoFailed =
                scanJobRepository.findLastTwoFailedJobsForTarget(target.getId());
        if (lastTwoFailed.size() >= 2) {
            certRepository.findAllByTargetId(target.getId()).forEach(cert -> {
                cert.setStatus(CertStatus.UNREACHABLE);
                cert.setScannedAt(Instant.now());
                certRepository.save(cert);
            });
            target.setLastErrorAt(Instant.now());
            targetRepository.save(target);
            log.warn("Target UNREACHABLE after 2 consecutive FAILED jobs: {}:{}",
                    target.getHost(), target.getPort());
        }
    }

    // ── Queue helpers ─────────────────────────────────────────────────────────

    @Transactional
    public void queueScanJob(Target target) {
        queueScanJob(target.getId(), target.getOrganization().getId(), TRIGGER_SCHEDULED);
    }

    @Transactional
    public void queueScanJob(Target target, String triggerSource) {
        queueScanJob(target.getId(), target.getOrganization().getId(), triggerSource);
    }

    @Transactional
    public void queueScanJob(UUID targetId, UUID orgId) {
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
                .jobKind(AgentScanJob.KIND_AGENT_PINNED)
                .triggerSource(triggerSource)
                .build();
        scanJobRepository.save(job);
        log.info("Scan job queued — target: {}, agent: {}, source: {}",
                target.getHost(), target.getAgent().getName(), triggerSource);
    }

    /**
     * Enqueues a PUBLIC_POOL scan job for a public target (RFC 0013 §2).
     * Called by PublicScanEnqueueScheduler and TargetService.triggerScan (public path).
     */
    @Transactional
    public void enqueuePublicPoolJob(Target target, String triggerSource) {
        if (scanJobRepository.existsActivePoolJobForTarget(target.getId())) {
            log.debug("PUBLIC_POOL job already active for target {}", target.getId());
            return;
        }
        AgentScanJob job = AgentScanJob.builder()
                .agent(null)
                .target(target)
                .orgId(target.getOrganization().getId())
                .status(ScanJobStatus.PENDING)
                .jobKind(AgentScanJob.KIND_PUBLIC_POOL)
                .triggerSource(triggerSource)
                .build();
        scanJobRepository.save(job);
        log.info("PUBLIC_POOL job enqueued — target: {}, source: {}", target.getHost(), triggerSource);
    }

    // ── Agent management ──────────────────────────────────────────────────────

    public List<AgentResponse> listAgents(UUID orgId) {
        return agentRepository.findAllByOrganizationId(orgId).stream()
                .map(a -> buildAgentResponse(a, null))
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

    // ── Schedulers ────────────────────────────────────────────────────────────

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
        List<AgentRegistrationToken> toDelete = expired.stream()
                .filter(t -> t.getAgentId() == null)
                .collect(Collectors.toList());
        tokenRepository.deleteAll(toDelete);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private byte[] decodeOcspStaple(String ocspStapleB64) {
        if (ocspStapleB64 == null || ocspStapleB64.isBlank()) return null;
        try {
            return Base64.getDecoder().decode(ocspStapleB64);
        } catch (Exception e) {
            log.warn("Could not decode ocspStapleB64: {}", e.getMessage());
            return null;
        }
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

    private AgentResponse buildAgentResponse(Agent a, String agentKey) {
        AgentResponse.AgentResponseBuilder builder = AgentResponse.builder()
                .id(a.getId())
                .name(a.getName())
                .status(a.getStatus())
                .allowedCidrs(a.getAllowedCidrs())
                .maxTargets(a.getMaxTargets())
                .currentTargetCount(a.getCurrentTargetCount())
                .lastSeenAt(a.getLastSeenAt())
                .registeredAt(a.getRegisteredAt())
                .createdAt(a.getCreatedAt())
                .locationId(a.getLocation() != null ? a.getLocation().getId() : null)
                .locationName(a.getLocation() != null ? a.getLocation().getName() : null);
        if (agentKey != null) builder.agentKey(agentKey);
        return builder.build();
    }
}
