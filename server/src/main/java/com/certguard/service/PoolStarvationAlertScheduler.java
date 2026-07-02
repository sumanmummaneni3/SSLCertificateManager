package com.certguard.service;

import com.certguard.entity.AgentScanJob;
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
import java.util.Optional;

/**
 * Pool starvation alert: notifies platform admins if any PUBLIC_POOL job has been
 * PENDING for more than {@code app.scanning.pool.starvation-alert-minutes} minutes
 * (RFC 0013 §5). Indicates that no scanner is polling — a fleet-level incident.
 *
 * <p>Runs every 5 minutes. Uses the AgentOfflineScheduler notification shape (email).
 * Active in HYBRID and POOL modes only (in DIRECT mode there are no pool jobs).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PoolStarvationAlertScheduler {

    private final AgentScanJobRepository scanJobRepository;
    private final NotificationService notificationService;

    @Value("${app.scanning.mode:DIRECT}")
    private String scanningMode;

    @Value("${app.scanning.pool.starvation-alert-minutes:15}")
    private int starvationAlertMinutes;

    @Scheduled(fixedDelay = 300_000)
    @SchedulerLock(name = "PoolStarvationAlertScheduler_check",
                   lockAtMostFor = "PT5M", lockAtLeastFor = "PT4M")
    public void checkPoolStarvation() {
        ScanningMode mode = parseScanningMode();
        if (mode == ScanningMode.DIRECT) return;

        Instant threshold = Instant.now().minus(starvationAlertMinutes, ChronoUnit.MINUTES);
        long count = scanJobRepository.countStalePoolPendingJobs(threshold);

        if (count == 0) return;

        Optional<AgentScanJob> oldest = scanJobRepository.findOldestStalePoolPendingJob(threshold);
        long oldestAgeMinutes = oldest
                .map(j -> ChronoUnit.MINUTES.between(j.getCreatedAt(), Instant.now()))
                .orElse((long) starvationAlertMinutes);

        log.warn("POOL STARVATION: {} PUBLIC_POOL job(s) have been PENDING > {} min (oldest: {} min)",
                count, starvationAlertMinutes, oldestAgeMinutes);

        notificationService.dispatchPoolStarvationAlert(count, oldestAgeMinutes);
    }

    private ScanningMode parseScanningMode() {
        try {
            return ScanningMode.valueOf(scanningMode.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ScanningMode.DIRECT;
        }
    }
}
