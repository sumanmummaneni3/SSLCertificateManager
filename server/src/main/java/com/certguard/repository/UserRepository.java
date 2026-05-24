package com.certguard.repository;

import com.certguard.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    Optional<User> findByGoogleSub(String googleSub);

    /**
     * Inserts a new user row only if no row with the same email exists.
     * Safe under concurrent invite acceptance and OAuth provisioning: the DB-level
     * ON CONFLICT DO NOTHING ensures exactly one winner with no exception thrown.
     * Caller must re-fetch by email after this call to get the committed row.
     */
    @Modifying(clearAutomatically = true)
    @Query(nativeQuery = true, value =
            "INSERT INTO users (id, org_id, email, name, role, onboarding_completed_at) " +
            "VALUES (:id, :orgId, :email, :name, CAST(:role AS user_role), :now) " +
            "ON CONFLICT (email) DO NOTHING")
    void insertIfAbsent(@Param("id") UUID id,
                        @Param("orgId") UUID orgId,
                        @Param("email") String email,
                        @Param("name") String name,
                        @Param("role") String role,
                        @Param("now") Instant now);
}
