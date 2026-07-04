package com.certguard.util;

import com.certguard.dto.response.ScanSource;
import com.certguard.entity.Agent;
import com.certguard.entity.AgentScanJob;
import com.certguard.entity.CertificateRecord;
import com.certguard.entity.Organization;
import com.certguard.entity.Target;
import com.certguard.enums.AgentType;
import com.certguard.enums.OrgType;
import com.certguard.enums.ScanJobStatus;
import com.certguard.enums.ScanSourceType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ScanSourceMapper} (RFC 0013 §9 — ratified scanSource contract).
 *
 * <p>Covers the three mapping cases explicitly called out in the ratified contract:
 * pool-completed (CLOUD_SCANNER, identity never leaked), pinned-completed
 * (CUSTOMER_AGENT + name), and legacy (no recorded provenance → omit, not defaulted).
 */
class ScanSourceMapperTest {

    private final Organization org = Organization.builder()
            .name("Acme").slug("acme").orgType(OrgType.SINGLE).build();

    private final Target target = Target.builder()
            .organization(org).host("example.com").port(443).isPrivate(false).build();

    // ── fromCertificateRecord ────────────────────────────────────────────────

    @Nested
    class FromCertificateRecord {

        @Test
        void poolCompleted_mapsToCloudScanner_withNoAgentIdentityLeaked() {
            // Even though scannedByAgent happens to be set to the claiming platform
            // scanner (as AgentService.processResult does), the mapper must NOT expose it.
            Agent platformScanner = Agent.builder()
                    .organization(org).name("platform-scanner-7").agentKeyHash("h")
                    .agentType(AgentType.PLATFORM_SCANNER).build();
            setId(platformScanner, UUID.randomUUID());

            CertificateRecord cert = CertificateRecord.builder()
                    .target(target).orgId(org.getId())
                    .commonName("example.com").serialNumber("aa")
                    .scannedByAgent(platformScanner)
                    .scanSourceType(ScanSourceType.CLOUD_SCANNER)
                    .build();

            ScanSource result = ScanSourceMapper.fromCertificateRecord(cert);

            assertThat(result).isNotNull();
            assertThat(result.getType()).isEqualTo(ScanSource.Type.CLOUD_SCANNER);
            assertThat(result.getAgentId()).isNull();
            assertThat(result.getAgentName()).isNull();
        }

        @Test
        void pinnedCompleted_mapsToCustomerAgent_withNameAndId() {
            Agent customerAgent = Agent.builder()
                    .organization(org).name("acme-office-agent").agentKeyHash("h")
                    .agentType(AgentType.CUSTOMER).build();
            UUID agentId = UUID.randomUUID();
            setId(customerAgent, agentId);

            CertificateRecord cert = CertificateRecord.builder()
                    .target(target).orgId(org.getId())
                    .commonName("internal.acme.local").serialNumber("bb")
                    .scannedByAgent(customerAgent)
                    .scanSourceType(ScanSourceType.CUSTOMER_AGENT)
                    .build();

            ScanSource result = ScanSourceMapper.fromCertificateRecord(cert);

            assertThat(result).isNotNull();
            assertThat(result.getType()).isEqualTo(ScanSource.Type.CUSTOMER_AGENT);
            assertThat(result.getAgentId()).isEqualTo(agentId);
            assertThat(result.getAgentName()).isEqualTo("acme-office-agent");
        }

        @Test
        void legacyRecord_withNoRecordedProvenance_isOmitted() {
            // scanSourceType is null — predates the V42 column. Must NOT default to
            // CLOUD_SCANNER even though scannedByAgent is also null (old public-target
            // direct scan) — nor infer CUSTOMER_AGENT from a populated scannedByAgent
            // (old private-target agent scan). Both must simply omit.
            CertificateRecord legacyDirectScan = CertificateRecord.builder()
                    .target(target).orgId(org.getId())
                    .commonName("legacy-public.example.com").serialNumber("cc")
                    .scannedByAgent(null)
                    .scanSourceType(null)
                    .build();

            assertThat(ScanSourceMapper.fromCertificateRecord(legacyDirectScan)).isNull();

            Agent oldCustomerAgent = Agent.builder()
                    .organization(org).name("old-agent").agentKeyHash("h")
                    .agentType(AgentType.CUSTOMER).build();
            setId(oldCustomerAgent, UUID.randomUUID());

            CertificateRecord legacyAgentScan = CertificateRecord.builder()
                    .target(target).orgId(org.getId())
                    .commonName("legacy-private.acme.local").serialNumber("dd")
                    .scannedByAgent(oldCustomerAgent)  // populated since V3, pre-dates scanSourceType
                    .scanSourceType(null)
                    .build();

            assertThat(ScanSourceMapper.fromCertificateRecord(legacyAgentScan)).isNull();
        }

        @Test
        void nullRecord_returnsNull() {
            assertThat(ScanSourceMapper.fromCertificateRecord(null)).isNull();
        }
    }

    // ── fromCompletedJob ──────────────────────────────────────────────────────

    @Nested
    class FromCompletedJob {

        @Test
        void completedPoolJob_platformScannerAgent_mapsToCloudScanner() {
            Agent platformScanner = Agent.builder()
                    .organization(org).name("scanner-3").agentKeyHash("h")
                    .agentType(AgentType.PLATFORM_SCANNER).build();
            setId(platformScanner, UUID.randomUUID());

            AgentScanJob job = AgentScanJob.builder()
                    .agent(platformScanner).target(target).orgId(org.getId())
                    .status(ScanJobStatus.COMPLETED)
                    .jobKind(AgentScanJob.KIND_PUBLIC_POOL)
                    .resultType("FULL")
                    .build();

            ScanSource result = ScanSourceMapper.fromCompletedJob(job);

            assertThat(result).isNotNull();
            assertThat(result.getType()).isEqualTo(ScanSource.Type.CLOUD_SCANNER);
            assertThat(result.getAgentId()).isNull();
            assertThat(result.getAgentName()).isNull();
        }

        @Test
        void completedPinnedJob_customerAgent_mapsToCustomerAgentWithName() {
            Agent customerAgent = Agent.builder()
                    .organization(org).name("acme-agent-1").agentKeyHash("h")
                    .agentType(AgentType.CUSTOMER).build();
            UUID agentId = UUID.randomUUID();
            setId(customerAgent, agentId);

            AgentScanJob job = AgentScanJob.builder()
                    .agent(customerAgent).target(target).orgId(org.getId())
                    .status(ScanJobStatus.COMPLETED)
                    .jobKind(AgentScanJob.KIND_AGENT_PINNED)
                    .resultType("FULL")
                    .build();

            ScanSource result = ScanSourceMapper.fromCompletedJob(job);

            assertThat(result).isNotNull();
            assertThat(result.getType()).isEqualTo(ScanSource.Type.CUSTOMER_AGENT);
            assertThat(result.getAgentId()).isEqualTo(agentId);
            assertThat(result.getAgentName()).isEqualTo("acme-agent-1");
        }

        @Test
        void directFallbackJob_mapsToCloudScanner_evenWithNoClaimingAgent() {
            // PublicScanFallbackScheduler never stamps job.agent — the job was originally
            // PUBLIC_POOL with agent=null and executed in-process without a claim.
            AgentScanJob job = AgentScanJob.builder()
                    .agent(null).target(target).orgId(org.getId())
                    .status(ScanJobStatus.COMPLETED)
                    .jobKind(AgentScanJob.KIND_PUBLIC_POOL)
                    .resultType("DIRECT_FALLBACK")
                    .build();

            ScanSource result = ScanSourceMapper.fromCompletedJob(job);

            assertThat(result).isNotNull();
            assertThat(result.getType()).isEqualTo(ScanSource.Type.CLOUD_SCANNER);
            assertThat(result.getAgentId()).isNull();
            assertThat(result.getAgentName()).isNull();
        }

        @Test
        void pendingJob_isOmitted() {
            AgentScanJob job = AgentScanJob.builder()
                    .agent(null).target(target).orgId(org.getId())
                    .status(ScanJobStatus.PENDING)
                    .jobKind(AgentScanJob.KIND_PUBLIC_POOL)
                    .build();

            assertThat(ScanSourceMapper.fromCompletedJob(job)).isNull();
        }

        @Test
        void claimedJob_isOmitted() {
            AgentScanJob job = AgentScanJob.builder()
                    .agent(null).target(target).orgId(org.getId())
                    .status(ScanJobStatus.CLAIMED)
                    .jobKind(AgentScanJob.KIND_PUBLIC_POOL)
                    .build();

            assertThat(ScanSourceMapper.fromCompletedJob(job)).isNull();
        }

        @Test
        void failedJob_isOmitted() {
            AgentScanJob job = AgentScanJob.builder()
                    .agent(null).target(target).orgId(org.getId())
                    .status(ScanJobStatus.FAILED)
                    .jobKind(AgentScanJob.KIND_PUBLIC_POOL)
                    .build();

            assertThat(ScanSourceMapper.fromCompletedJob(job)).isNull();
        }

        @Test
        void completedJobWithNoAgentAndNotFallback_isOmitted() {
            // Defensive: shouldn't happen in practice (COMPLETED implies a claim), but
            // the mapper must not throw or guess.
            AgentScanJob job = AgentScanJob.builder()
                    .agent(null).target(target).orgId(org.getId())
                    .status(ScanJobStatus.COMPLETED)
                    .jobKind(AgentScanJob.KIND_PUBLIC_POOL)
                    .resultType("FULL")
                    .build();

            assertThat(ScanSourceMapper.fromCompletedJob(job)).isNull();
        }

        @Test
        void nullJob_returnsNull() {
            assertThat(ScanSourceMapper.fromCompletedJob(null)).isNull();
        }
    }

    private static void setId(com.certguard.entity.BaseEntity entity, UUID id) {
        ReflectionTestUtils.setField(entity, "id", id);
    }
}
