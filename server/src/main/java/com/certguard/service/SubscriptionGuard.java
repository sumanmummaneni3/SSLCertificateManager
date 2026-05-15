package com.certguard.service;

import com.certguard.entity.Organization;
import com.certguard.enums.SubscriptionStatus;
import com.certguard.exception.SubscriptionSuspendedException;
import com.certguard.repository.OrganizationRepository;
import com.certguard.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SubscriptionGuard {

    private final SubscriptionRepository subscriptionRepository;
    private final OrganizationRepository organizationRepository;

    /**
     * Throws {@link SubscriptionSuspendedException} if the billing-owner subscription
     * for the given org is SUSPENDED. No-ops when no subscription row exists yet.
     */
    public void assertScansAllowed(UUID orgId) {
        subscriptionRepository.findByOrganizationId(orgId).ifPresent(sub -> {
            if (sub.getStatus() == SubscriptionStatus.SUSPENDED) {
                String name = organizationRepository.findById(orgId)
                        .map(Organization::getName)
                        .orElse(orgId.toString());
                throw new SubscriptionSuspendedException(name);
            }
        });
    }
}
