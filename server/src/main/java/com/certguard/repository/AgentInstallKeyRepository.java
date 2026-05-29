package com.certguard.repository;

import com.certguard.entity.AgentInstallKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AgentInstallKeyRepository extends JpaRepository<AgentInstallKey, UUID> {

    Optional<AgentInstallKey> findByBundleDownloadTokenHash(String hash);

    /**
     * Returns the most recent install-key row for the given agent that has not yet
     * been downloaded. Used to avoid issuing duplicate bundles if the user clicks
     * twice before downloading.
     */
    Optional<AgentInstallKey> findFirstByAgentIdAndBundleDownloadedAtIsNull(UUID agentId);

    /**
     * Atomically marks the install-key row as consumed by setting bundle_downloaded_at
     * to the provided timestamp, but only if it has not already been consumed
     * (bundle_downloaded_at IS NULL). Returns the number of rows updated (0 or 1).
     * A return value of 0 means the token was already consumed and should be rejected.
     */
    @Modifying
    @Query("UPDATE AgentInstallKey k SET k.bundleDownloadedAt = :downloadedAt WHERE k.id = :id AND k.bundleDownloadedAt IS NULL")
    int markDownloadedIfUnconsumed(UUID id, Instant downloadedAt);

    /**
     * Cleanup query: removes expired rows that were never downloaded.
     * Rows that were downloaded are retained for audit purposes.
     */
    @Modifying
    @Query("DELETE FROM AgentInstallKey k WHERE k.expiresAt < :now AND k.bundleDownloadedAt IS NULL")
    void deleteExpiredUndownloaded(Instant now);
}
