package com.certguard.agent.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ScanJob#isPublicPoolJob()} — the signal PollLoop uses to
 * decide whether the PUBLIC_POOL SSRF guard must run (RFC 0013 §4.4).
 */
class ScanJobTest {

    @Test
    void isPublicPoolJob_whenJobKindIsPublicPool_returnsTrue() {
        ScanJob job = new ScanJob();
        job.setJobKind("PUBLIC_POOL");
        assertTrue(job.isPublicPoolJob());
    }

    @Test
    void isPublicPoolJob_isCaseInsensitive() {
        ScanJob job = new ScanJob();
        job.setJobKind("public_pool");
        assertTrue(job.isPublicPoolJob());
    }

    @Test
    void isPublicPoolJob_whenJobKindIsAgentPinned_returnsFalse() {
        ScanJob job = new ScanJob();
        job.setJobKind("AGENT_PINNED");
        assertFalse(job.isPublicPoolJob());
    }

    @Test
    void isPublicPoolJob_whenJobKindIsNull_returnsFalse() {
        // Backward compatibility: null jobKind must NOT trigger the pool-only SSRF
        // guard — customer agents (which never receive jobKind before a server
        // upgrade) scan private address space by design and must not be blocked.
        ScanJob job = new ScanJob();
        job.setJobKind(null);
        assertFalse(job.isPublicPoolJob());
    }

    @Test
    void isPublicPoolJob_whenJobKindIsUnrecognized_returnsFalse() {
        ScanJob job = new ScanJob();
        job.setJobKind("SOMETHING_ELSE");
        assertFalse(job.isPublicPoolJob());
    }
}
