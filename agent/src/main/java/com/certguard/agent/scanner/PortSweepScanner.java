package com.certguard.agent.scanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Pure-Java virtual-thread TCP connect sweep + TLS probe (RFC 0011 §3.2).
 *
 * Zero new dependencies — uses Java 25 virtual threads for fan-out,
 * a Semaphore to bound concurrency, and the existing SslScanner.probe() for TLS.
 *
 * Port profiles:
 *   COMMON_TLS  — 443, 8443, 9443, 993, 995, 465, 990, 636, 6443, 8883, 5671, 5061, 5986
 *   EXTENDED    — COMMON_TLS + 80, 8080, 8008, 3000, 4000, 5000, 8000, 8888, 9000, 9090, 9200
 */
public class PortSweepScanner {

    private static final Logger log = LoggerFactory.getLogger(PortSweepScanner.class);

    /** Default TLS port profile — covers the most common TLS-capable ports. */
    public static final List<Integer> COMMON_TLS_PORTS = List.of(
            443, 8443, 9443, 993, 995, 465, 990, 636, 6443, 8883, 5671, 5061, 5986
    );

    /** Extended profile — adds plain-HTTP and common app-server ports for banner grabbing. */
    public static final List<Integer> EXTENDED_PORTS = Stream.concat(
            COMMON_TLS_PORTS.stream(),
            Stream.of(80, 8080, 8008, 3000, 4000, 5000, 8000, 8888, 9000, 9090, 9200)
    ).collect(Collectors.toList());

    private final int connectTimeoutMs;
    private final int maxConcurrent;
    private final SslScanner sslScanner;

    public PortSweepScanner(int connectTimeoutMs, int maxConcurrent, SslScanner sslScanner) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.maxConcurrent    = maxConcurrent;
        this.sslScanner       = sslScanner;
    }

    public PortSweepScanner(SslScanner sslScanner) {
        this(500, 1000, sslScanner);
    }

    /**
     * Sweeps all hosts in the CIDR across the given port list using virtual threads.
     * Returns one HostScanResult per host with at least one open port.
     */
    public List<HostScanResult> sweep(String cidr, List<Integer> ports) throws Exception {
        List<InetAddress> hosts = expandCidr(cidr);
        log.info("Sweeping {} hosts in {} across {} ports", hosts.size(), cidr, ports.size());

        Semaphore sem = new Semaphore(maxConcurrent);
        List<Future<HostScanResult>> futures = new ArrayList<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (InetAddress host : hosts) {
                futures.add(executor.submit(() -> scanHost(host, ports, sem)));
            }
        }
        // Executor is closed/awaited by try-with-resources

        List<HostScanResult> results = futures.stream()
                .map(f -> {
                    try { return f.get(); }
                    catch (Exception e) { return null; }
                })
                .filter(Objects::nonNull)
                .filter(r -> !r.ports().isEmpty())
                .collect(Collectors.toList());

        log.info("Sweep complete — {}/{} hosts with open ports", results.size(), hosts.size());
        return results;
    }

    private HostScanResult scanHost(InetAddress host, List<Integer> ports,
                                    Semaphore sem) {
        List<PortScanResult> openPorts = new ArrayList<>();
        for (int port : ports) {
            try {
                sem.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            try {
                EndpointPortState state = tcpConnect(host, port);
                if (state == EndpointPortState.OPEN_TLS || state == EndpointPortState.OPEN_NO_TLS) {
                    X509Certificate[] chain = null;
                    if (state == EndpointPortState.OPEN_TLS) {
                        chain = sslScanner.probe(host.getHostAddress(), port);
                        if (chain == null) {
                            state = EndpointPortState.OPEN_NO_TLS;
                        }
                    }
                    Map<String, String> banners = DeviceClassifier.grabBanners(host, port, chain);
                    DeviceClass deviceClass = DeviceClassifier.classify(port, banners, chain);
                    openPorts.add(new PortScanResult(port, state, chain, deviceClass, banners));
                }
            } finally {
                sem.release();
            }
        }
        return new HostScanResult(host.getHostAddress(), openPorts);
    }

    /**
     * Attempts TCP connect. If successful, probes TLS to classify port state.
     * Returns CLOSED_OR_FILTERED on connection refusal or timeout.
     */
    private EndpointPortState tcpConnect(InetAddress host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            // TCP connect succeeded — probe for TLS
            X509Certificate[] chain = sslScanner.probe(host.getHostAddress(), port,
                    connectTimeoutMs / 1000 + 1);
            return chain != null ? EndpointPortState.OPEN_TLS : EndpointPortState.OPEN_NO_TLS;
        } catch (ConnectException | SocketTimeoutException e) {
            return EndpointPortState.CLOSED_OR_FILTERED;
        } catch (Exception e) {
            return EndpointPortState.CLOSED_OR_FILTERED;
        }
    }

    /**
     * Expands a CIDR to its host addresses (excludes network and broadcast addresses).
     * Limits to /8 to avoid runaway expansion.
     */
    private List<InetAddress> expandCidr(String cidr) throws Exception {
        String[] parts = cidr.split("/");
        byte[] base = InetAddress.getByName(parts[0]).getAddress();
        int prefix  = Integer.parseInt(parts[1]);
        int bits    = 32 - prefix;

        if (bits > 24) {
            // /7 and wider would be 33M+ hosts — refuse
            throw new IllegalArgumentException("CIDR too wide (max /8): " + cidr);
        }

        long count   = (1L << bits);
        long network = toUint32(base);
        List<InetAddress> hosts = new ArrayList<>((int) count);

        // Skip network address (first) and broadcast (last) for /30 and wider;
        // for /31 and /32 include all.
        long start = bits > 1 ? 1 : 0;
        long end   = bits > 1 ? count - 1 : count;

        for (long i = start; i < end; i++) {
            long ip = network + i;
            byte[] addr = new byte[]{
                    (byte)((ip >> 24) & 0xFF),
                    (byte)((ip >> 16) & 0xFF),
                    (byte)((ip >> 8)  & 0xFF),
                    (byte)(ip         & 0xFF)
            };
            hosts.add(InetAddress.getByAddress(addr));
        }
        return hosts;
    }

    private long toUint32(byte[] addr) {
        return ((addr[0] & 0xFFL) << 24)
                | ((addr[1] & 0xFFL) << 16)
                | ((addr[2] & 0xFFL) << 8)
                | (addr[3] & 0xFFL);
    }

    // ── Value types ───────────────────────────────────────────────────────────

    public record HostScanResult(String ip, List<PortScanResult> ports) {}

    public record PortScanResult(
            int port,
            EndpointPortState state,
            X509Certificate[] chain,
            DeviceClass deviceClass,
            Map<String, String> banners
    ) {}
}
