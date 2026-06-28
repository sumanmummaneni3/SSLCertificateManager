package com.certguard.agent.http;

import com.certguard.agent.config.AgentConfig;
import com.certguard.agent.config.AgentMode;
import com.certguard.agent.discovery.NicSubnetDiscovery;
import com.certguard.agent.model.ScanJob;
import com.certguard.agent.model.ScanResult;
import com.certguard.agent.scanner.DeviceClass;
import com.certguard.agent.scanner.EndpointPortState;
import com.certguard.agent.scanner.PortSweepScanner;
import com.certguard.agent.scanner.SslScanner;
import com.certguard.agent.security.HmacSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Main agent poll loop.
 *
 * AUTHENTICATED mode (default):
 *   Every poll-interval-seconds: heartbeat → poll jobs → route by jobType.
 *   - null / "CERTIFICATE_SCAN" → existing SslScanner flow
 *   - "NETWORK_SCAN"            → PortSweepScanner flow (RFC 0011 Part A)
 *
 * ANONYMOUS mode (RFC 0011 Part B):
 *   Single-shot: poll anon jobs → if DISCOVERY job present → NicSubnetDiscovery
 *   + PortSweepScanner → submit anon results → stop.
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
        AgentMode mode = config.getMode();

        if (mode == AgentMode.ANONYMOUS) {
            // Single-shot anonymous discovery; fire immediately, then stop
            log.info("ANONYMOUS mode — running single-shot discovery then exiting");
            scheduler.schedule(this::runAnonDiscovery, 0, TimeUnit.SECONDS);
        } else {
            int interval = config.pollIntervalSeconds();
            log.info("AUTHENTICATED mode — poll interval: {}s, threads: {}",
                    interval, config.scanThreads());

            scheduler.scheduleWithFixedDelay(
                    this::tick,
                    0,
                    interval,
                    TimeUnit.SECONDS
            );
        }
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    // ── AUTHENTICATED poll tick ───────────────────────────────────────────────

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

            // 4. Execute scans in parallel (all types)
            ExecutorService pool = Executors.newFixedThreadPool(
                    Math.min(config.scanThreads(), jobs.size()));

            for (ScanJob job : jobs) {
                pool.submit(() -> processScanJob(job));
            }

            pool.shutdown();
            pool.awaitTermination(5, TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("Poll tick error: {}", e.getMessage(), e);
        }
    }

    /**
     * Routes a job to the appropriate handler based on jobType.
     * Null or "CERTIFICATE_SCAN" → existing TLS scan flow.
     * "NETWORK_SCAN"             → PortSweepScanner flow.
     */
    private void processScanJob(ScanJob job) {
        String jobType = job.getJobType();
        if ("NETWORK_SCAN".equals(jobType)) {
            processNetworkScanJob(job);
        } else {
            // Existing certificate scan flow
            processCertScanJob(job);
        }
    }

    /** Certificate scan — the original flow unchanged. */
    private void processCertScanJob(ScanJob job) {
        try {
            ScanResult result = scanner.scan(job, config.scanTimeoutSeconds());

            if (result.getType() == ScanResult.Type.ERROR) {
                log.warn("Scan error — job: {}, host: {}, reason: {}",
                        job.getJobId(), job.getHost(), result.getErrorMessage());
                return;
            }

            String hmac = HmacSigner.sign(result, config.agentKey());
            api.submitResult(result, hmac);

        } catch (Exception e) {
            log.error("Failed to process cert job {} ({}:{}): {}",
                    job.getJobId(), job.getHost(), job.getPort(), e.getMessage(), e);
        }
    }

    /**
     * Network sweep — RFC 0011 Part A.
     * Runs a PortSweepScanner on the CIDR from the job payload,
     * then submits results as a single-chunk batch.
     */
    private void processNetworkScanJob(ScanJob job) {
        String networkScanId = job.getNetworkScanId();
        String cidr          = job.getCidr();
        String portProfile   = job.getPortProfile() != null ? job.getPortProfile() : "COMMON_TLS";

        if (networkScanId == null || cidr == null) {
            log.error("NETWORK_SCAN job {} missing networkScanId or cidr — skipping", job.getJobId());
            return;
        }

        log.info("Starting network sweep — scan: {}, cidr: {}, profile: {}",
                networkScanId, cidr, portProfile);

        try {
            List<Integer> ports = selectPorts(portProfile, job.getCustomPorts());
            PortSweepScanner sweeper = new PortSweepScanner(
                    job.getConnectTimeoutMs(), 1000, scanner);

            List<PortSweepScanner.HostScanResult> results = sweeper.sweep(cidr, ports);

            // Submit as a single chunk (chunkIndex=0, totalChunks=1)
            api.submitNetworkResults(networkScanId, 0, 1, results, config.agentKey());

        } catch (Exception e) {
            log.error("Network sweep failed — scan: {}, cidr: {}: {}", networkScanId, cidr,
                    e.getMessage(), e);
        }
    }

    // ── ANONYMOUS discovery (single-shot) ─────────────────────────────────────

    /**
     * Anonymous mode entry point.
     * 1. Poll /api/v1/anon/jobs for a DISCOVERY job.
     * 2. Discover local subnets via NicSubnetDiscovery.
     * 3. Sweep each subnet with PortSweepScanner (COMMON_TLS profile).
     * 4. Submit results to /api/v1/anon/discovery-results.
     * 5. Shut down — the session is now SCAN_COMPLETE on the server.
     */
    private void runAnonDiscovery() {
        String scanToken = config.getAnonScanToken();
        if (scanToken.isBlank()) {
            log.error("ANONYMOUS mode requires certguard.agent.anon.scan-token to be set — exiting");
            stop();
            return;
        }

        try {
            log.info("Polling anonymous jobs...");
            List<Map<String, Object>> anonJobs = api.getAnonJobs(scanToken);
            if (anonJobs.isEmpty()) {
                log.info("No pending anonymous discovery job — session may already be complete");
                stop();
                return;
            }

            log.info("Starting anonymous subnet discovery");
            NicSubnetDiscovery nicDiscovery = new NicSubnetDiscovery();
            List<NicSubnetDiscovery.SubnetInfo> subnets = nicDiscovery.discoverSubnets();

            if (subnets.isEmpty()) {
                log.warn("No private subnets discovered — nothing to sweep");
                api.submitAnonDiscoveryResults(scanToken, Collections.emptyList(), Collections.emptyList());
                stop();
                return;
            }

            // Sweep all discovered subnets with COMMON_TLS profile
            PortSweepScanner sweeper = new PortSweepScanner(scanner);
            List<PortSweepScanner.HostScanResult> allResults = new ArrayList<>();

            for (NicSubnetDiscovery.SubnetInfo subnet : subnets) {
                log.info("Sweeping subnet {} ({})", subnet.cidr(), subnet.ifaceName());
                try {
                    List<PortSweepScanner.HostScanResult> subnetResults =
                            sweeper.sweep(subnet.cidr(), PortSweepScanner.COMMON_TLS_PORTS);
                    allResults.addAll(subnetResults);
                } catch (Exception e) {
                    log.warn("Sweep failed for {} — skipping: {}", subnet.cidr(), e.getMessage());
                }
            }

            log.info("Discovery sweep complete — {} hosts found across {} subnet(s)",
                    allResults.size(), subnets.size());

            // Submit results (no IPs sent — server stores only aggregated device info)
            api.submitAnonDiscoveryResults(scanToken, subnets, allResults);

            log.info("Anonymous discovery complete — results submitted, session is now SCAN_COMPLETE");

        } catch (Exception e) {
            log.error("Anonymous discovery failed: {}", e.getMessage(), e);
        } finally {
            stop();
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<Integer> selectPorts(String profile, List<Integer> customPorts) {
        return switch (profile.toUpperCase()) {
            case "EXTENDED"  -> PortSweepScanner.EXTENDED_PORTS;
            case "CUSTOM"    -> (customPorts != null && !customPorts.isEmpty())
                                ? customPorts : PortSweepScanner.COMMON_TLS_PORTS;
            default          -> PortSweepScanner.COMMON_TLS_PORTS;  // COMMON_TLS + FULL
        };
    }
}
