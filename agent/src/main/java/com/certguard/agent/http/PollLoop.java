package com.certguard.agent.http;

import com.certguard.agent.config.AgentConfig;
import com.certguard.agent.model.ScanJob;
import com.certguard.agent.model.ScanResult;
import com.certguard.agent.scanner.SslScanner;
import com.certguard.agent.security.HmacSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Main agent loop using a plain ScheduledExecutorService.
 * Every poll-interval-seconds:
 *   1. Send heartbeat
 *   2. Poll for pending scan jobs
 *   3. Execute scans in parallel (bounded thread pool)
 *   4. Submit results with HMAC signature
 */
public class PollLoop {

    private static final Logger log = LoggerFactory.getLogger(PollLoop.class);

    private final AgentConfig     config;
    private final ServerApiClient api;
    private final SslScanner      scanner;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "certguard-poll");
                t.setDaemon(false);
                return t;
            });

    public PollLoop(AgentConfig config, ServerApiClient api, SslScanner scanner) {
        this.config  = config;
        this.api     = api;
        this.scanner = scanner;
    }

    public void start() {
        int interval = config.pollIntervalSeconds();
        log.info("Poll loop started — interval: {}s, threads: {}",
                interval, config.scanThreads());

        scheduler.scheduleWithFixedDelay(
                this::tick,
                0,
                interval,
                TimeUnit.SECONDS
        );
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    private void tick() {
        try {
            // 1. Heartbeat
            try {
                api.heartbeat();
            } catch (Exception e) {
                log.warn("Heartbeat failed: {}", e.getMessage());
            }

            // 2. Poll for jobs
            List<ScanJob> jobs = api.pollJobs();
            if (jobs.isEmpty()) {
                log.debug("No pending jobs");
                return;
            }

            // 3. Enforce max-targets limit
            int limit = config.maxTargets();
            if (jobs.size() > limit) {
                log.warn("Received {} jobs — capping at max-targets={}", jobs.size(), limit);
                jobs = jobs.subList(0, limit);
            }

            log.info("Processing {} job(s)", jobs.size());

            // 4. Execute scans in parallel
            ExecutorService pool = Executors.newFixedThreadPool(
                    Math.min(config.scanThreads(), jobs.size()));

            for (ScanJob job : jobs) {
                pool.submit(() -> processJob(job));
            }

            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Poll tick error: {}", e.getMessage(), e);
        }
    }

    private void processJob(ScanJob job) {
        try {
            ScanResult result = scanner.scan(job, config.scanTimeoutSeconds());

            if (result.getType() == ScanResult.Type.ERROR) {
                log.warn("Scan error — job: {}, host: {}, reason: {}",
                        job.getJobId(), job.getHost(), result.getErrorMessage());
                return;
            }

            // Sign the result before submission
            String hmac = HmacSigner.sign(result, config.agentKey());
            api.submitResult(result, hmac);

        } catch (Exception e) {
            log.error("Failed to process job {} ({}:{}): {}",
                    job.getJobId(), job.getHost(), job.getPort(), e.getMessage(), e);
        }
    }
}
