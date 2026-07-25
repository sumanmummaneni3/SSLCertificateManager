package com.certguard.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RFC 0015 Phase 3 — asserts {@link LastActiveOrgBackfillRunner#run} never propagates a
 * failure from {@link BackfillLastActiveOrgService}. A Spring {@code ApplicationRunner}
 * that throws aborts context startup; a transient DB error during the one deploy where
 * this actually runs must degrade to "not applied, retryable next deploy" rather than
 * crash-loop the server.
 */
@ExtendWith(MockitoExtension.class)
class LastActiveOrgBackfillRunnerTest {

    @Mock BackfillLastActiveOrgService backfillService;

    private LastActiveOrgBackfillRunner runnerWithMode(String mode) {
        LastActiveOrgBackfillRunner runner = new LastActiveOrgBackfillRunner(backfillService);
        ReflectionTestUtils.setField(runner, "mode", mode);
        return runner;
    }

    @Test
    void applyMode_serviceThrowsDataAccessException_runCompletesWithoutThrowing() {
        when(backfillService.apply()).thenThrow(new DataAccessResourceFailureException("connection refused"));
        LastActiveOrgBackfillRunner runner = runnerWithMode("apply");

        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();

        verify(backfillService).apply();
    }

    @Test
    void dryRunMode_serviceThrowsDataAccessException_runCompletesWithoutThrowing() {
        when(backfillService.dryRun()).thenThrow(new DataAccessResourceFailureException("connection refused"));
        LastActiveOrgBackfillRunner runner = runnerWithMode("dry-run");

        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();

        verify(backfillService).dryRun();
    }

    @Test
    void applyMode_serviceThrowsUnrelatedRuntimeException_runCompletesWithoutThrowing() {
        // The catch is deliberately broad (Exception, not DataAccessException) — any
        // unexpected failure should degrade gracefully, not just JDBC-flavored ones.
        when(backfillService.apply()).thenThrow(new IllegalStateException("boom"));
        LastActiveOrgBackfillRunner runner = runnerWithMode("apply");

        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();

        verify(backfillService).apply();
    }

    @Test
    void offMode_neverInvokesService() {
        LastActiveOrgBackfillRunner runner = runnerWithMode("off");

        assertThatCode(() -> runner.run(null)).doesNotThrowAnyException();

        verify(backfillService, org.mockito.Mockito.never()).apply();
        verify(backfillService, org.mockito.Mockito.never()).dryRun();
    }
}
