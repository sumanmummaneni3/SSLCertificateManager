package com.certguard.auth.repository;

import com.certguard.auth.entity.RateLimitBucket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RateLimitBucketRepository extends JpaRepository<RateLimitBucket, UUID> {

    Optional<RateLimitBucket> findByBucketKey(String bucketKey);

    @Modifying
    @Transactional
    @Query("DELETE FROM RateLimitBucket b WHERE b.windowStart < :cutoff")
    int deleteStaleWindows(Instant cutoff);
}
