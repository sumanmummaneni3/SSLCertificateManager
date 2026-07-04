package com.certguard.service;

import com.certguard.entity.AgentScanJob;
import com.certguard.enums.ScanJobStatus;
import com.certguard.enums.ScanningMode;
import com.certguard.repository.AgentScanJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * HYBRID-mode fallback: if a PUBLIC_POOL job has been PENDING for more than 10 minutes
 * (meaning no scanner claimed it), execute it in-process via SslScannerService and mark
 * COMPLETED with result_type = DIRECT_FALLBACK (RFC 0013 §7).
 *
 * <p>Active only in HYBRID mode. In DIRECT mode the pool is not used; in POOL mode there
 * is no in-process scanner to fall back to.
 *
 * <p>Runs every 2 minutes so a stale job is picked up within ~12 minutes total.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicScanFallbackScheduler {

    private final AgentScanJobRepository scanJobRepository;
    private final SslScannerService sslScannerService;
    private final AgentService agentService;

    @Value("${app.scanning.mode:DIRECT}")
    private String scanningMode;

    @Value("${app.scanning.pool.fallback-pending-minutes:10}")
    private int fallbackPendingMinutes;

    @Scheduled(fixedDelay = 120_000)
    @SchedulerLock(name = "PublicScanFallbackScheduler_fallback",
                   lockAtMostFor = "PT5M", lockAtLeastFor = "PT1M")
    @Transactional
    public void runFallback() {
        if (parseScanningMode() != ScanningMode.HYBRID) return;

        Instant staleThreshold = Instant.now().minus(fallbackPendingMinutes, ChronoUnit.MINUTES);
        List<AgentScanJob> staleJobs = findStalePendingPoolJobs(staleThreshold);

        if (staleJobs.isEmpty()) return;

        log.info("PublicScanFallbackScheduler: {} stale PUBLIC_POOL job(s) pending > {} min — running in-process",
                staleJobs.size(), fallbackPendingMinutes);

        for (AgentScanJob job : staleJobs) {
            try {
                // Claim the job to prevent another scheduler instance picking it up.
                job.setStatus(ScanJobStatus.CLAIMED);
                job.setClaimedAt(Instant.now());
                scanJobRepository.save(job);

                boolean ok = sslScannerService.executeFallbackScan(
                        job.getTarget(), job.getTarget().getLastScannedAt());

                job.setStatus(ScanJobStatus.COMPLETED);
                job.setResultType("DIRECT_FALLBACK");
                job.setCompletedAt(Instant.now());
                if (!ok) {
                    job.setStatus(ScanJobStatus.FAILED);
                    job.setErrorMsg("HYBRID fallback scan failed");
                }
                scanJobRepository.save(job);

                // m2 fix: wire the same two-consecutive-FAILED hysteresis check the
                // agent ERROR path uses (AgentService.handleErrorResult) — previously
                // this scheduler's FAILED path never marked UNREACHABLE, which was
                // inconsistent between the two failure sources for the same job table.
                if (job.getStatus() == ScanJobStatus.FAILED) {
                    agentService.checkAndMarkUnreachable(job.getTarget());
                }

            } catch (Exception e) {
                log.error("HYBRID fallback failed for job {} (target: {}): {}",
                        job.getId(), job.getTarget().getHost(), e.getMessage());
                job.setStatus(ScanJobStatus.FAILED);
                job.setErrorMsg("HYBRID fallback exception: " + truncate(e.getMessage(), 480));
                job.setCompletedAt(Instant.now());
                scanJobRepository.save(job);
                agentService.checkAndMarkUnreachable(job.getTarget());
            }
        }
    }

    private List<AgentScanJob> findStalePendingPoolJobs(Instant before) {
        return scanJobRepository.findStalePoolPendingJobs(before);
    }

    private ScanningMode parseScanningMode() {
        try {
            return ScanningMode.valueOf(scanningMode.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ScanningMode.DIRECT;
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
