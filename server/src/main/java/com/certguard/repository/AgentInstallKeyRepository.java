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
     * Cleanup query: removes expired rows that were never downloaded.
     * Rows that were downloaded are retained for audit purposes.
     */
    @Modifying
    @Query("DELETE FROM AgentInstallKey k WHERE k.expiresAt < :now AND k.bundleDownloadedAt IS NULL")
    void deleteExpiredUndownloaded(Instant now);
}
