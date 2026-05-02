package com.certguard.service;

import com.certguard.dto.IssueBundleResult;
import com.certguard.dto.request.CreateAgentRequest;
import com.certguard.entity.*;
import com.certguard.enums.AgentStatus;
import com.certguard.exception.BundleExpiredException;
import com.certguard.exception.ResourceNotFoundException;
import com.certguard.repository.AgentInstallKeyRepository;
import com.certguard.repository.AgentRegistrationTokenRepository;
import com.certguard.repository.AgentRepository;
import com.certguard.repository.LocationRepository;
import com.certguard.repository.OrganizationRepository;
import com.certguard.security.AgentBundleCrypto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for AgentBundleService — all dependencies mocked.
 *
 * Conventions:
 * - methodName_condition_expectedOutcome
 */
@ExtendWith(MockitoExtension.class)
class AgentBundleServiceTest {

    @Mock AgentInstallKeyRepository installKeyRepository;
    @Mock AgentRepository agentRepository;
    @Mock AgentRegistrationTokenRepository tokenRepository;
    @Mock OrganizationRepository orgRepository;
    @Mock LocationRepository locationRepository;
    @Spy  AgentBundleCrypto bundleCrypto;
    @Spy  BCryptPasswordEncoder passwordEncoder;
    AgentBundleService service;

    UUID orgId;
    UUID userId;
    Organization org;

    @BeforeEach
    void setUp() {
        service = new AgentBundleService(
                installKeyRepository, agentRepository, tokenRepository,
                orgRepository, locationRepository, bundleCrypto, passwordEncoder);

        // Inject @Value fields via reflection
        ReflectionTestUtils.setField(service, "downloadUrlTtlSeconds", 3600);
        ReflectionTestUtils.setField(service, "baseUrl",               "https://certguard.example.com:8443");
        ReflectionTestUtils.setField(service, "argon2MemoryKib",       65536);
        ReflectionTestUtils.setField(service, "argon2Iterations",      3);
        ReflectionTestUtils.setField(service, "argon2Parallelism",     1);

        orgId  = UUID.randomUUID();
        userId = UUID.randomUUID();
        org    = Organization.builder().name("Test Org").slug("test-org").build();
    }

    @Nested
    class IssueBundle {

        @Test
        void issueBundle_validRequest_createsAgentAndInstallKeyRow() throws Exception {
            CreateAgentRequest req = new CreateAgentRequest();
            req.setAgentName("prod-agent-01");
            req.setAllowedCidrs(List.of("10.0.0.0/8"));
            req.setMaxTargets(50);

            when(orgRepository.findById(orgId)).thenReturn(Optional.of(org));

            UUID agentId = UUID.randomUUID();
            Agent savedAgent = Agent.builder()
                    .organization(org).name("prod-agent-01")
                    .agentKeyHash("PENDING").allowedCidrs(List.of("10.0.0.0/8"))
                    .maxTargets(50).status(AgentStatus.PENDING)
                    .build();
            ReflectionTestUtils.setField(savedAgent, "id", agentId);
            when(agentRepository.save(any(Agent.class))).thenReturn(savedAgent);
            when(tokenRepository.save(any(AgentRegistrationToken.class))).thenAnswer(i -> i.getArgument(0));
            when(installKeyRepository.save(any(AgentInstallKey.class))).thenAnswer(i -> i.getArgument(0));

            IssueBundleResult result = service.issueBundle(req, orgId, userId);

            assertThat(result).isNotNull();
            assertThat(result.installKey()).startsWith("CGK-");
            assertThat(result.bundleDownloadUrl()).contains("/api/v1/agents/");
            assertThat(result.bundleDownloadUrl()).contains("/bundle?dlToken=");
            assertThat(result.expiresAt()).isAfter(Instant.now());

            // Verify agent was saved with PENDING status
            ArgumentCaptor<Agent> agentCaptor = ArgumentCaptor.forClass(Agent.class);
            verify(agentRepository).save(agentCaptor.capture());
            assertThat(agentCaptor.getValue().getStatus()).isEqualTo(AgentStatus.PENDING);
            assertThat(agentCaptor.getValue().getName()).isEqualTo("prod-agent-01");

            // Verify install key row was saved
            ArgumentCaptor<AgentInstallKey> keyCaptor = ArgumentCaptor.forClass(AgentInstallKey.class);
            verify(installKeyRepository).save(keyCaptor.capture());
            AgentInstallKey savedKey = keyCaptor.getValue();
            assertThat(savedKey.getOrgId()).isEqualTo(orgId);
            assertThat(savedKey.getInstallKeyHash()).isNotBlank();
            assertThat(savedKey.getBundleDownloadTokenHash()).isNotBlank();
            assertThat(savedKey.getSealedPayload()).isNotEmpty();
            assertThat(savedKey.getBundleDownloadedAt()).isNull();
            assertThat(savedKey.getExpiresAt()).isAfter(Instant.now());
            assertThat(savedKey.getCreatedBy()).isEqualTo(userId);

            // Verify registration token was saved
            ArgumentCaptor<AgentRegistrationToken> tokenCaptor =
                    ArgumentCaptor.forClass(AgentRegistrationToken.class);
            verify(tokenRepository).save(tokenCaptor.capture());
            assertThat(tokenCaptor.getValue().getTokenHash()).isNotBlank();
        }

        @Test
        void issueBundle_orgNotFound_throwsResourceNotFoundException() {
            CreateAgentRequest req = new CreateAgentRequest();
            req.setAgentName("orphan-agent");
            req.setAllowedCidrs(List.of("10.0.0.0/8"));

            when(orgRepository.findById(orgId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.issueBundle(req, orgId, userId))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("Organization not found");

            verifyNoInteractions(agentRepository);
            verifyNoInteractions(installKeyRepository);
        }

        @Test
        void issueBundle_installKeyHasCgkPrefix() throws Exception {
            CreateAgentRequest req = new CreateAgentRequest();
            req.setAgentName("prefix-check-agent");
            req.setAllowedCidrs(List.of("192.168.0.0/16"));

            when(orgRepository.findById(orgId)).thenReturn(Optional.of(org));
            Agent savedAgent = Agent.builder()
                    .organization(org).name("prefix-check-agent")
                    .agentKeyHash("PENDING").allowedCidrs(List.of("192.168.0.0/16"))
                    .maxTargets(50).status(AgentStatus.PENDING).build();
            ReflectionTestUtils.setField(savedAgent, "id", UUID.randomUUID());
            when(agentRepository.save(any())).thenReturn(savedAgent);
            when(tokenRepository.save(any())).thenAnswer(i -> i.getArgument(0));
            when(installKeyRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            IssueBundleResult result = service.issueBundle(req, orgId, userId);

            assertThat(result.installKey()).startsWith("CGK-");
            assertThat(result.installKey().length()).isGreaterThan(10);
        }
    }

    @Nested
    class BuildBundleZip {

        @Test
        void buildBundleZip_validToken_marksDownloadedAndReturnsZip() throws Exception {
            UUID agentId = UUID.randomUUID();
            String dlToken = "valid-token-for-download-test-abc";

            Agent agent = Agent.builder()
                    .organization(org).name("zip-agent")
                    .agentKeyHash("PENDING").allowedCidrs(List.of("10.0.0.0/8"))
                    .maxTargets(50).status(AgentStatus.PENDING).build();
            // Set the agent ID via reflection (UUID normally set by DB)
            ReflectionTestUtils.setField(agent, "id", agentId);

            // Build a minimal sealed payload for the test
            byte[] salt       = new byte[16];
            byte[] wrappingKey = bundleCrypto.deriveWrappingKey(
                    "CGK-TEST".toCharArray(), salt, 65536, 3, 1);
            byte[] sealedPayload = bundleCrypto.sealPayload(
                    "{\"agentId\":\"test\"}".getBytes(), wrappingKey, salt);

            AgentInstallKey key = AgentInstallKey.builder()
                    .agent(agent)
                    .orgId(orgId)
                    .kdfSalt(salt)
                    .kdfMemoryKib(65536)
                    .kdfIterations(3)
                    .kdfParallelism(1)
                    .installKeyHash("$2a$10$test")
                    .bundleDownloadTokenHash("computed-in-service")
                    .sealedPayload(sealedPayload)
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .build();

            // The service SHA-256 hashes the token; mock the lookup by hash
            when(installKeyRepository.findByBundleDownloadTokenHash(anyString()))
                    .thenReturn(Optional.of(key));
            when(installKeyRepository.save(any())).thenAnswer(i -> i.getArgument(0));

            byte[] zip = service.buildBundleZip(agentId, dlToken);

            assertThat(zip).isNotEmpty();

            // Verify it's a valid ZIP with expected entries
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
                var entries = new java.util.HashSet<String>();
                var entry = zis.getNextEntry();
                while (entry != null) {
                    entries.add(entry.getName());
                    entry = zis.getNextEntry();
                }
                assertThat(entries).contains("bundle.cgb", "application.properties",
                        "run.sh", "run.bat", "README.txt");
            }

            // Verify downloaded timestamp was set
            ArgumentCaptor<AgentInstallKey> captor = ArgumentCaptor.forClass(AgentInstallKey.class);
            verify(installKeyRepository).save(captor.capture());
            assertThat(captor.getValue().getBundleDownloadedAt()).isNotNull();
        }

        @Test
        void buildBundleZip_alreadyDownloaded_throwsBundleExpiredException() {
            UUID agentId  = UUID.randomUUID();
            String dlToken = "already-consumed-token";

            Agent agent = Agent.builder()
                    .organization(org).name("replay-agent")
                    .agentKeyHash("PENDING").allowedCidrs(List.of()).maxTargets(50)
                    .status(AgentStatus.PENDING).build();
            ReflectionTestUtils.setField(agent, "id", agentId);

            AgentInstallKey key = AgentInstallKey.builder()
                    .agent(agent).orgId(orgId)
                    .kdfSalt(new byte[16]).kdfMemoryKib(65536)
                    .kdfIterations(3).kdfParallelism(1)
                    .installKeyHash("hash").bundleDownloadTokenHash("hash")
                    .sealedPayload(new byte[32])
                    .bundleDownloadedAt(Instant.now().minusSeconds(60)) // already downloaded
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .build();

            when(installKeyRepository.findByBundleDownloadTokenHash(anyString()))
                    .thenReturn(Optional.of(key));

            assertThatThrownBy(() -> service.buildBundleZip(agentId, dlToken))
                    .isInstanceOf(BundleExpiredException.class)
                    .hasMessageContaining("already been downloaded");
        }

        @Test
        void buildBundleZip_expiredToken_throwsBundleExpiredException() {
            UUID agentId  = UUID.randomUUID();
            String dlToken = "expired-token";

            Agent agent = Agent.builder()
                    .organization(org).name("expired-agent")
                    .agentKeyHash("PENDING").allowedCidrs(List.of()).maxTargets(50)
                    .status(AgentStatus.PENDING).build();
            ReflectionTestUtils.setField(agent, "id", agentId);

            AgentInstallKey key = AgentInstallKey.builder()
                    .agent(agent).orgId(orgId)
                    .kdfSalt(new byte[16]).kdfMemoryKib(65536)
                    .kdfIterations(3).kdfParallelism(1)
                    .installKeyHash("hash").bundleDownloadTokenHash("hash")
                    .sealedPayload(new byte[32])
                    .expiresAt(Instant.now().minusSeconds(10)) // expired
                    .build();

            when(installKeyRepository.findByBundleDownloadTokenHash(anyString()))
                    .thenReturn(Optional.of(key));

            assertThatThrownBy(() -> service.buildBundleZip(agentId, dlToken))
                    .isInstanceOf(BundleExpiredException.class)
                    .hasMessageContaining("expired");
        }

        @Test
        void buildBundleZip_tokenNotFound_throwsResourceNotFoundException() {
            UUID agentId  = UUID.randomUUID();
            String dlToken = "nonexistent-token";

            when(installKeyRepository.findByBundleDownloadTokenHash(anyString()))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.buildBundleZip(agentId, dlToken))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void buildBundleZip_agentIdMismatch_throwsResourceNotFoundException() {
            UUID realAgentId  = UUID.randomUUID();
            UUID wrongAgentId = UUID.randomUUID();
            String dlToken    = "agent-mismatch-token";

            Agent realAgent = Agent.builder()
                    .organization(org).name("real-agent")
                    .agentKeyHash("PENDING").allowedCidrs(List.of()).maxTargets(50)
                    .status(AgentStatus.PENDING).build();
            ReflectionTestUtils.setField(realAgent, "id", realAgentId);

            AgentInstallKey key = AgentInstallKey.builder()
                    .agent(realAgent).orgId(orgId)
                    .kdfSalt(new byte[16]).kdfMemoryKib(65536)
                    .kdfIterations(3).kdfParallelism(1)
                    .installKeyHash("hash").bundleDownloadTokenHash("hash")
                    .sealedPayload(new byte[32])
                    .expiresAt(Instant.now().plusSeconds(3600))
                    .build();

            when(installKeyRepository.findByBundleDownloadTokenHash(anyString()))
                    .thenReturn(Optional.of(key));

            assertThatThrownBy(() -> service.buildBundleZip(wrongAgentId, dlToken))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }
}
