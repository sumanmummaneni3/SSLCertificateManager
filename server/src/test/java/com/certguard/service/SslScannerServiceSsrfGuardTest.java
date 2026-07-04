package com.certguard.service;

import com.certguard.entity.Organization;
import com.certguard.entity.Target;
import com.certguard.enums.OrgType;
import com.certguard.repository.CertificateRecordRepository;
import com.certguard.repository.TargetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests for the HYBRID-mode SSRF guard on {@link SslScannerService} (architect
 * review B2 — "PublicScanFallbackScheduler dials unguarded, server itself is the
 * SSRF vector in HYBRID mode").
 *
 * <p>{@link SslScannerService#checkPublicAddressGuard(String)} is tested directly
 * (hermetic, no network I/O). {@link SslScannerService#executeFallbackScan} is tested
 * for the early-return integration with a disallowed IP literal — no live DNS or
 * socket I/O is exercised since IP literals resolve without a network round-trip and
 * the guard rejects before any dial is attempted.
 */
@ExtendWith(MockitoExtension.class)
class SslScannerServiceSsrfGuardTest {

    @Mock TargetRepository targetRepository;
    @Mock CertificateRecordRepository certRepository;
    @Mock CertificatePersistenceService certPersistenceService;

    SslScannerService sslScannerService;

    Organization org;

    @BeforeEach
    void setUp() {
        sslScannerService = new SslScannerService(targetRepository, certRepository, certPersistenceService);
        ReflectionTestUtils.setField(sslScannerService, "connectTimeoutMs", 10_000);
        org = Organization.builder().name("Acme").slug("acme").orgType(OrgType.SINGLE).build();
    }

    @Nested
    class CheckPublicAddressGuard {

        @ParameterizedTest(name = "rejects {0}")
        @ValueSource(strings = {
                "169.254.169.254", "127.0.0.1", "10.0.0.5", "192.168.1.1",
                "100.64.0.1", "0.0.0.0", "::1", "fc00::1",
        })
        void rejectsDisallowedAddress(String host) {
            assertThat(SslScannerService.checkPublicAddressGuard(host)).isNotNull();
        }

        @Test
        void allowsPublicIpv4Literal() {
            assertThat(SslScannerService.checkPublicAddressGuard("8.8.8.8")).isNull();
        }
    }

    @Nested
    class ExecuteFallbackScan {

        @Test
        void rejectsDisallowedTarget_withoutTouchingPersistence() {
            Target target = Target.builder()
                    .organization(org).host("169.254.169.254").port(443).isPrivate(false).build();

            boolean result = sslScannerService.executeFallbackScan(target, Instant.now());

            assertThat(result).isFalse();
            // Guard must reject BEFORE any dial/persist attempt.
            verifyNoInteractions(certPersistenceService);
        }

        @Test
        void rejectsRfc1918Target_withoutTouchingPersistence() {
            Target target = Target.builder()
                    .organization(org).host("10.0.0.5").port(443).isPrivate(false).build();

            boolean result = sslScannerService.executeFallbackScan(target, Instant.now());

            assertThat(result).isFalse();
            verifyNoInteractions(certPersistenceService);
        }
    }
}
