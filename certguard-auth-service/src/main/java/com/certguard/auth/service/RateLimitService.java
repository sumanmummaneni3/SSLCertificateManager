package com.certguard.auth.service;

import com.certguard.auth.entity.RateLimitBucket;
import com.certguard.auth.exception.TooManyRequestsException;
import com.certguard.auth.repository.RateLimitBucketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final RateLimitBucketRepository repo;

    @Value("${auth.rate-limit.max-attempts:10}")
    private int maxAttempts;

    @Value("${auth.rate-limit.window-seconds:300}")
    private long windowSeconds;

    /**
     * Increments the attempt counter for {@code key} and throws if the limit is exceeded.
     * Keys are typically the client IP or "email:<addr>".
     */
    @Transactional
    public void check(String key) {
        Instant now = Instant.now();
        Instant windowStart = now.minusSeconds(windowSeconds);

        RateLimitBucket bucket = repo.findByBucketKey(key).orElseGet(() ->
                RateLimitBucket.builder()
                        .bucketKey(key)
                        .attemptCount(0)
                        .windowStart(now)
                        .build());

        if (bucket.getWindowStart().isBefore(windowStart)) {
            // window has expired — reset
            bucket.setAttemptCount(0);
            bucket.setWindowStart(now);
        }

        bucket.setAttemptCount(bucket.getAttemptCount() + 1);
        repo.save(bucket);

        if (bucket.getAttemptCount() > maxAttempts) {
            throw new TooManyRequestsException(
                    "Too many authentication attempts. Try again in " + windowSeconds + " seconds.");
        }
    }

    @Scheduled(fixedDelayString = "${auth.rate-limit.cleanup-interval-ms:3600000}")
    @Transactional
    public void cleanup() {
        Instant cutoff = Instant.now().minusSeconds(windowSeconds * 2);
        repo.deleteStaleWindows(cutoff);
    }
}
