package com.certguard.service;

import com.certguard.entity.Target;
import com.certguard.enums.ScanningMode;
import com.certguard.repository.AgentScanJobRepository;
import com.certguard.repository.TargetRepository;
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
 * Enqueues PUBLIC_POOL scan jobs for all enabled public targets (RFC 0013 §2).
 *
 * <p>Replaces {@code SslScannerService.scheduledPublicScan} in HYBRID and POOL modes.
 * Active only when {@code app.scanning.mode} is HYBRID or POOL. In DIRECT mode this
 * scheduler runs but is a no-op so the cron slot can be shared.
 *
 * <p>Dedup: skips targets that already have a PENDING or CLAIMED PUBLIC_POOL job.
 *
 * <p>SubscriptionGuard: unlike the old direct-scan path, suspended orgs' public
 * targets will stop being enqueued (intentional alignment with private-scan enforcement,
 * RFC 0013 §2).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicScanEnqueueScheduler {

    private final TargetRepository targetRepository;
    private final AgentService agentService;
    private final SubscriptionGuard subscriptionGuard;

    @Value("${app.scanning.mode:DIRECT}")
    private String scanningMode;

    @Scheduled(cron = "${app.scanning.public.schedule-cron}")
    @SchedulerLock(name = "PublicScanEnqueueScheduler_enqueue",
                   lockAtMostFor = "PT30M", lockAtLeastFor = "PT5M")
    @Transactional
    public void enqueuePublicScans() {
        ScanningMode mode = parseScanningMode();
        if (mode == ScanningMode.DIRECT) {
            log.debug("PublicScanEnqueueScheduler: no-op in DIRECT mode");
            return;
        }

        log.info("PublicScanEnqueueScheduler: enqueuing PUBLIC_POOL jobs (mode={})", mode);
        List<Target> targets = targetRepository.findAllByIsPrivateFalseAndEnabledTrue();
        int enqueued = 0;
        int skipped  = 0;

        for (Target target : targets) {
            try {
                // Honour subscription guard — suspended orgs stop getting pool scans.
                subscriptionGuard.assertScansAllowed(target.getOrganization().getId());
                agentService.enqueuePublicPoolJob(target, AgentService.TRIGGER_SCHEDULED);
                enqueued++;
            } catch (com.certguard.exception.SubscriptionSuspendedException e) {
                log.debug("Skipping public scan for suspended org {} (target: {})",
                        target.getOrganization().getId(), target.getHost());
                skipped++;
            } catch (Exception e) {
                log.error("Error enqueuing pool job for target {}: {}", target.getHost(), e.getMessage());
                skipped++;
            }
        }

        log.info("PublicScanEnqueueScheduler: enqueued={}, skipped={} (total targets={})",
                enqueued, skipped, targets.size());
    }

    private ScanningMode parseScanningMode() {
        try {
            return ScanningMode.valueOf(scanningMode.toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown scanning mode '{}' — defaulting to DIRECT", scanningMode);
            return ScanningMode.DIRECT;
        }
    }
}
