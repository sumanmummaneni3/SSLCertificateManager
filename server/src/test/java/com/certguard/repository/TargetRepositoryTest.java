package com.certguard.repository;

import com.certguard.entity.*;
import com.certguard.enums.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.certguard.DockerAvailableCondition;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository-layer tests using a real PostgreSQL container (Testcontainers).
 * H2 is avoided because entities use Postgres-specific ENUM types and JSONB.
 *
 * Uses full @SpringBootTest context since @DataJpaTest is unavailable in
 * Spring Boot 4.0. The full context starts cleanly against the Testcontainers
 * database with ddl-auto=create-drop and Flyway disabled.
 */
@ExtendWith(DockerAvailableCondition.class)
@Testcontainers
@SpringBootTest
@ActiveProfiles("tctest")
@Transactional
class TargetRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("certguard_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      postgres::getJdbcUrl);
        registry.add("spring.datasource.username",  postgres::getUsername);
        registry.add("spring.datasource.password",  postgres::getPassword);
        registry.add("spring.flyway.enabled",       () -> "false");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.properties.hibernate.type.preferred_enum_jdbc_type", () -> "VARCHAR");
        registry.add("server.ssl.enabled",          () -> "false");
        registry.add("spring.rabbitmq.host",        () -> "localhost");
    }

    @Autowired TargetRepository targetRepository;
    @PersistenceContext EntityManager em;

    Organization org;

    @BeforeEach
    void seedOrg() {
        org = Organization.builder()
                .name("Test Org")
                .slug("test-org-" + UUID.randomUUID().toString().substring(0, 8))
                .orgType(OrgType.SINGLE)
                .build();
        em.persist(org);
        em.flush();
    }

    private Target buildAndPersistTarget(String host, int port) {
        Target t = Target.builder()
                .organization(org).host(host).port(port)
                .hostType(HostType.DOMAIN).isPrivate(false).enabled(true)
                .build();
        em.persist(t);
        em.flush();
        return t;
    }

    @Nested
    class FindAllByOrganizationId {

        @Test
        void findAllByOrganizationId_whenTargetsExist_returnsPaginatedResults() {
            buildAndPersistTarget("alpha.com", 443);
            buildAndPersistTarget("beta.com",  443);
            em.clear();

            Page<Target> page = targetRepository.findAllByOrganizationId(org.getId(), PageRequest.of(0, 10));
            assertThat(page.getTotalElements()).isEqualTo(2);
            assertThat(page.getContent()).extracting(Target::getHost)
                    .containsExactlyInAnyOrder("alpha.com", "beta.com");
        }

        @Test
        void findAllByOrganizationId_whenOtherOrg_returnsEmpty() {
            buildAndPersistTarget("alpha.com", 443);

            Page<Target> page = targetRepository.findAllByOrganizationId(UUID.randomUUID(), PageRequest.of(0, 10));
            assertThat(page.getTotalElements()).isZero();
        }
    }

    @Nested
    class FindByIdAndOrganizationId {

        @Test
        void findByIdAndOrganizationId_whenMatchingIds_returnsTarget() {
            Target saved = buildAndPersistTarget("match.com", 443);
            em.clear();

            Optional<Target> result = targetRepository.findByIdAndOrganizationId(saved.getId(), org.getId());
            assertThat(result).isPresent();
            assertThat(result.get().getHost()).isEqualTo("match.com");
        }

        @Test
        void findByIdAndOrganizationId_whenWrongOrg_returnsEmpty() {
            Target saved = buildAndPersistTarget("match.com", 443);
            em.clear();

            Optional<Target> result = targetRepository.findByIdAndOrganizationId(saved.getId(), UUID.randomUUID());
            assertThat(result).isEmpty();
        }
    }

    @Nested
    class ExistsByOrganizationIdAndHostAndPort {

        @Test
        void existsByOrganizationIdAndHostAndPort_whenExists_returnsTrue() {
            buildAndPersistTarget("dup.com", 8443);
            em.clear();

            assertThat(targetRepository.existsByOrganizationIdAndHostAndPort(org.getId(), "dup.com", 8443)).isTrue();
        }

        @Test
        void existsByOrganizationIdAndHostAndPort_whenDifferentPort_returnsFalse() {
            buildAndPersistTarget("dup.com", 8443);
            em.clear();

            assertThat(targetRepository.existsByOrganizationIdAndHostAndPort(org.getId(), "dup.com", 443)).isFalse();
        }
    }

    @Nested
    class CountByOrganizationId {

        @Test
        void countByOrganizationId_returnsCorrectCount() {
            buildAndPersistTarget("one.com", 443);
            buildAndPersistTarget("two.com", 443);
            em.flush();

            assertThat(targetRepository.countByOrganizationId(org.getId())).isEqualTo(2);
        }
    }
}
