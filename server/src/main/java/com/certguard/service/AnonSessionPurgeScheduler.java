package com.certguard.service;

import com.certguard.entity.AnonScanSession;
import com.certguard.enums.AnonSessionStatus;
import com.certguard.repository.AnonScanSessionRepository;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Daily purge of expired, claimed, and deleted anonymous scan sessions.
 *
 * Hard-deletes rows where:
 *   - view_expires_at < now()   (natural expiry after 7 days)
 *   - status IN ('CLAIMED', 'DELETED')   (user-initiated lifecycle end)
 *
 * Cascade deletes propagate to anon_discovered_subnets and anon_discovered_devices
 * via ON DELETE CASCADE defined in V37 migration.
 *
 * Modelled on CertificateExpiryScheduler. Uses ShedLock to prevent concurrent runs
 * in multi-node deployments.
 */
@Component
@RequiredArgsConstructor
public class AnonSessionPurgeScheduler {

    private static final Logger log = LoggerFactory.getLogger(AnonSessionPurgeScheduler.class);

    private final AnonScanSessionRepository sessionRepository;

    /**
     * Runs daily at 03:00 UTC.
     * Hard-deletes all sessions that have expired or been explicitly terminated.
     */
    @Scheduled(cron = "0 0 3 * * *")
    @SchedulerLock(name = "AnonSessionPurgeScheduler_purgeExpiredSessions",
                   lockAtMostFor = "PT1H", lockAtLeastFor = "PT5M")
    @Transactional
    public void purgeExpiredSessions() {
        log.info("Starting anon session purge sweep");

        Instant now = Instant.now();
        List<AnonScanSession> toPurge = sessionRepository.findByViewExpiresAtBeforeOrStatusIn(
                now, List.of(AnonSessionStatus.CLAIMED, AnonSessionStatus.DELETED));

        if (toPurge.isEmpty()) {
            log.info("Anon session purge complete — no sessions to purge");
            return;
        }

        // Child rows are cascade-deleted by the DB, but we explicitly delete
        // via repository to ensure any JPA caches are invalidated.
        int purged = 0;
        for (AnonScanSession session : toPurge) {
            try {
                // Cascade: ON DELETE CASCADE handles subnets and devices
                sessionRepository.delete(session);
                purged++;
            } catch (Exception e) {
                log.error("Failed to purge anon session {}: {}", session.getId(), e.getMessage());
            }
        }

        log.info("Anon session purge complete — {} session(s) hard-deleted", purged);
    }
}
