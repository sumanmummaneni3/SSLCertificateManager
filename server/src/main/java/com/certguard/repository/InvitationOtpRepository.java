package com.certguard.repository;

import com.certguard.entity.InvitationOtp;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvitationOtpRepository extends JpaRepository<InvitationOtp, UUID> {

    /**
     * Finds the most recent unexpired OTP entry for the given email and org.
     */
    Optional<InvitationOtp> findFirstByEmailAndOrgIdAndExpiresAtAfterOrderByCreatedAtDesc(
            String email, UUID orgId, Instant now);

    /**
     * Deletes all OTP entries (expired or not) for a specific email+org combination.
     * Called after a successful OTP verification to clean up the consumed entry.
     */
    @Modifying
    @Query("DELETE FROM InvitationOtp o WHERE o.email = :email AND o.orgId = :orgId")
    void deleteByEmailAndOrgId(String email, UUID orgId);

    /**
     * Scheduled cleanup: delete all expired OTP rows.
     */
    @Modifying
    @Query("DELETE FROM InvitationOtp o WHERE o.expiresAt < :now")
    void deleteExpired(Instant now);
}
