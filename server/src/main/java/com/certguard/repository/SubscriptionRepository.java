package com.certguard.repository;

import com.certguard.entity.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SubscriptionRepository extends JpaRepository<Subscription, UUID> {
    Optional<Subscription> findByOrganizationId(UUID orgId);

    /** Loads all subscriptions at once; used by AdminService to avoid N+1. */
    @Query("SELECT s FROM Subscription s JOIN FETCH s.organization")
    List<Subscription> findAllWithOrganization();
}
