package com.certguard.service;

import com.certguard.entity.RevokedToken;
import com.certguard.repository.RevokedTokenRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class TokenRevocationService {

    private final RevokedTokenRepository repository;
    private final long ttlHours;

    /** Keyed by "userId:orgId". True means the session is revoked. */
    private final Cache<String, Boolean> cache;

    public TokenRevocationService(
            RevokedTokenRepository repository,
            @Value("${app.token-revocation.ttl-hours:24}") long ttlHours) {
        this.repository = repository;
        this.ttlHours   = ttlHours;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(ttlHours, TimeUnit.HOURS)
                .maximumSize(10_000)
                .build();
    }

    @PostConstruct
    void warmCache() {
        repository.findAllByExpiresAtAfter(Instant.now())
                .forEach(t -> cache.put(cacheKey(t.getUserId(), t.getOrgId()), Boolean.TRUE));
        log.info("Token revocation cache warmed: {} active entries", cache.estimatedSize());
    }

    @Transactional
    public void revokeForUserInOrg(UUID userId, UUID orgId, UUID revokedByUserId, String reason) {
        Instant expiresAt = Instant.now().plus(ttlHours, ChronoUnit.HOURS);
        repository.findByUserIdAndOrgId(userId, orgId).ifPresentOrElse(
                existing -> existing.refresh(revokedByUserId, reason, expiresAt),
                () -> repository.save(RevokedToken.of(userId, orgId, revokedByUserId, reason, expiresAt))
        );
        cache.put(cacheKey(userId, orgId), Boolean.TRUE);
        log.debug("Revoked session for user={} org={}", userId, orgId);
    }

    @Transactional
    public void clearRevocationForUserInOrg(UUID userId, UUID orgId) {
        repository.deleteByUserIdAndOrgId(userId, orgId);
        cache.invalidate(cacheKey(userId, orgId));
        log.debug("Cleared revocation for user={} org={}", userId, orgId);
    }

    public boolean isRevoked(UUID userId, UUID orgId) {
        Boolean cached = cache.getIfPresent(cacheKey(userId, orgId));
        if (cached != null) {
            return cached;
        }
        boolean inDb = repository.findByUserIdAndOrgId(userId, orgId)
                .map(t -> t.getExpiresAt().isAfter(Instant.now()))
                .orElse(false);
        if (inDb) {
            cache.put(cacheKey(userId, orgId), Boolean.TRUE);
        }
        return inDb;
    }

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanupExpired() {
        int deleted = repository.deleteExpiredBefore(Instant.now());
        if (deleted > 0) {
            log.info("Cleaned up {} expired token revocation records", deleted);
        }
    }

    private static String cacheKey(UUID userId, UUID orgId) {
        return userId.toString() + ':' + orgId.toString();
    }
}
