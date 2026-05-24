package com.certguard.service;

import com.certguard.entity.RevokedToken;
import com.certguard.repository.RevokedTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TokenRevocationServiceTest {

    @Mock RevokedTokenRepository repository;

    TokenRevocationService service;

    UUID userId = UUID.randomUUID();
    UUID orgId  = UUID.randomUUID();
    UUID byId   = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // @PostConstruct warmCache() is NOT called when constructing directly — no stub needed.
        service = new TokenRevocationService(repository, 24L);
    }

    @Test
    void isRevoked_returnsFalse_whenNothingRevoked() {
        when(repository.findByUserIdAndOrgId(userId, orgId)).thenReturn(Optional.empty());

        assertThat(service.isRevoked(userId, orgId)).isFalse();
    }

    @Test
    void isRevoked_returnsTrue_fromCache_afterRevoke() {
        when(repository.findByUserIdAndOrgId(userId, orgId)).thenReturn(Optional.empty());
        service.revokeForUserInOrg(userId, orgId, byId, "test");

        // Reset so we can assert the next isRevoked call does NOT query DB
        clearInvocations(repository);

        assertThat(service.isRevoked(userId, orgId)).isTrue();
        verify(repository, never()).findByUserIdAndOrgId(any(), any());
    }

    @Test
    void isRevoked_returnsTrue_fromDb_onCacheMiss() {
        RevokedToken row = RevokedToken.of(userId, orgId, byId, "db-only",
                Instant.now().plus(24, ChronoUnit.HOURS));
        when(repository.findByUserIdAndOrgId(userId, orgId)).thenReturn(Optional.of(row));

        // Cache is empty (no warmCache in unit tests) → falls back to DB
        assertThat(service.isRevoked(userId, orgId)).isTrue();
        verify(repository).findByUserIdAndOrgId(userId, orgId);
    }

    @Test
    void isRevoked_returnsFalse_whenDbRowExpired() {
        RevokedToken row = RevokedToken.of(userId, orgId, byId, "old",
                Instant.now().minus(1, ChronoUnit.HOURS));
        when(repository.findByUserIdAndOrgId(userId, orgId)).thenReturn(Optional.of(row));

        assertThat(service.isRevoked(userId, orgId)).isFalse();
    }

    @Test
    void clearRevocation_invalidatesCache() {
        when(repository.findByUserIdAndOrgId(userId, orgId)).thenReturn(Optional.empty());
        service.revokeForUserInOrg(userId, orgId, byId, "reason");
        service.clearRevocationForUserInOrg(userId, orgId);

        // Reset invocation counters — next isRevoked must hit DB (cache was invalidated)
        clearInvocations(repository);

        assertThat(service.isRevoked(userId, orgId)).isFalse();
        verify(repository).findByUserIdAndOrgId(userId, orgId);
    }

    @Test
    void revokeForUserInOrg_upserts_whenExistingRow() {
        RevokedToken existing = mock(RevokedToken.class);
        when(repository.findByUserIdAndOrgId(userId, orgId)).thenReturn(Optional.of(existing));

        service.revokeForUserInOrg(userId, orgId, byId, "updated");

        verify(existing).refresh(any(), any(), any());
        verify(repository, never()).save(any(RevokedToken.class));
    }

    @Test
    void revokeForUserInOrg_inserts_whenNoExistingRow() {
        when(repository.findByUserIdAndOrgId(userId, orgId)).thenReturn(Optional.empty());

        service.revokeForUserInOrg(userId, orgId, byId, "new");

        verify(repository).save(any(RevokedToken.class));
    }
}
