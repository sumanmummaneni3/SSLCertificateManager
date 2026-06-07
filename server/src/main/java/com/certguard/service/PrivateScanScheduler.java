package com.certguard.service;

import com.certguard.entity.Target;
import com.certguard.repository.TargetRepository;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
/**
 * Daily sweep that enqueues scan jobs for every enabled private target that has
 * an assigned agent (RFC 0008 §6).
 *
 * Design notes:
 * - This component only *enqueues* agent jobs; it never executes TLS handshakes
 *   directly. That is why it is a separate component from {@link SslScannerService},
 *   which owns the direct-scan concern.
 * - {@link AgentService#queueScanJob(Target, String)} already de-dupes PENDING/CLAIMED
 *   jobs, enforces {@code SubscriptionGuard}, and requires a non-null agent, so the
 *   sweep does not need to re-check those conditions. Failures from any of them are
 *   caught and logged per-target so a single bad target never aborts the sweep.
 * - The candidate set is fetched in pages of {@code enqueue-batch-size} targets and
 *   processed by {@link #enqueueChunk}. Per-target durability is provided by
 *   {@code AgentService.queueScanJob}'s own {@code @Transactional} method (each job
 *   insert is committed on its own via AgentService's proxy). The per-target try/catch
 *   in {@code enqueueChunk} ensures a failure on one target never aborts the rest of
 *   the page. NOTE: {@code enqueueChunk} itself is intentionally NOT {@code @Transactional}
 *   — it is called via {@code this.enqueueChunk()} (self-invocation), which bypasses the
 *   Spring proxy, so any annotation would be a no-op (P1-C).
 * - Job draining is naturally rate-limited per agent: agents poll at their own
 *   cadence (default 30s) and the claim path caps jobs per poll via
 *   {@code agent.getMaxTargets()}, so no thundering-herd throttle is needed here
 *   (RFC 0008 §6.4).
 */
@Component
public class PrivateScanScheduler {

    private static final Logger log = LoggerFactory.getLogger(PrivateScanScheduler.class);

    private final TargetRepository targetRepository;
    private final AgentService agentService;

    @Value("${app.scanning.private.enqueue-batch-size:500}")
    private int enqueueBatchSize;

    public PrivateScanScheduler(TargetRepository targetRepository, AgentService agentService) {
        this.targetRepository = targetRepository;
        this.agentService = agentService;
    }

    /**
     * Nightly sweep at 03:00 (configurable). Enqueues one SCHEDULED scan job per
     * eligible private target (enabled + assigned agent). Processes targets in
     * chunks of {@code app.scanning.private.enqueue-batch-size} (default 500),
     * each chunk in its own transaction.
     */
    @Scheduled(cron = "${app.scanning.private.schedule-cron:0 0 3 * * *}")
    @SchedulerLock(name = "PrivateScanScheduler_scheduledPrivateScan",
                   lockAtMostFor = "PT1H", lockAtLeastFor = "PT10M")
    public void scheduledPrivateScan() {
        log.info("Starting daily private-target scan sweep");

        int pageNumber   = 0;
        int totalQueued  = 0;
        int totalSkipped = 0;
        int totalSeen    = 0;
        Page<Target> page;

        do {
            PageRequest req = PageRequest.of(pageNumber, enqueueBatchSize);
            page = targetRepository.findAllByIsPrivateTrueAndEnabledTrueAndAgentIsNotNull(req);

            int[] result = enqueueChunk(page.getContent());
            totalQueued  += result[0];
            totalSkipped += result[1];
            totalSeen    += page.getNumberOfElements();
            pageNumber++;
        } while (page.hasNext());

        log.info("Private-target scan sweep complete — {} targets considered, {} jobs queued, {} skipped",
                totalSeen, totalQueued, totalSkipped);
    }

    /**
     * Enqueues scan jobs for one page of targets. Per-target durability comes from
     * {@code AgentService.queueScanJob}'s own {@code @Transactional} (each insert is
     * committed through AgentService's proxy). The try/catch ensures a failure on one
     * target never aborts the rest. This method is NOT {@code @Transactional} — it is
     * called via self-invocation ({@code this.enqueueChunk()}), which bypasses the
     * Spring proxy, so any transaction annotation here would be silently ignored (P1-C).
     *
     * @return int[2] where [0] = jobs queued, [1] = targets skipped
     */
    public int[] enqueueChunk(java.util.List<Target> targets) {
        int queued  = 0;
        int skipped = 0;

        for (Target target : targets) {
            try {
                agentService.queueScanJob(target, AgentService.TRIGGER_SCHEDULED);
                queued++;
            } catch (Exception ex) {
                // Expected skip conditions: SubscriptionSuspendedException (org suspended),
                // IllegalStateException (no agent — guard against race where agent was
                // de-assigned between the page fetch and this call), de-dup log (already
                // pending — queueScanJob returns normally in that case so this won't fire
                // for de-dup). Any other unexpected exception is also caught here so a
                // single bad target never aborts the rest of the sweep.
                log.warn("Skipping private scan for target {} ({}:{}): {}",
                        target.getId(), target.getHost(), target.getPort(), ex.getMessage());
                skipped++;
            }
        }

        return new int[]{queued, skipped};
    }
}
