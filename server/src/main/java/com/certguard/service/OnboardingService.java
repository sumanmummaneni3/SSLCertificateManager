package com.certguard.service;

import com.certguard.dto.request.CompleteOnboardingRequest;
import com.certguard.dto.response.OrgResponse;
import com.certguard.entity.Organization;
import com.certguard.entity.User;
import com.certguard.enums.OrgType;
import com.certguard.exception.ResourceNotFoundException;
import com.certguard.repository.OrganizationRepository;
import com.certguard.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OnboardingService {

    private final OrganizationRepository orgRepository;
    private final UserRepository userRepository;
    private final OrgService orgService;

    @Transactional
    public OrgResponse complete(UUID userId, UUID orgId, CompleteOnboardingRequest req) {
        Organization org = orgRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));

        org.setName(req.getOrgName().trim());
        if (req.getContactEmail() != null) org.setContactEmail(req.getContactEmail());
        if (req.getCountry() != null)      org.setCountry(req.getCountry());

        // Decision #4: self-service MSP promotion is disabled.
        // User intent is recorded via the wizard UI, but type stays SINGLE.
        // PLATFORM_ADMIN runs PATCH /api/v1/admin/orgs/{id}/promote-msp after sales call.
        org.setOrgType(OrgType.SINGLE);
        orgRepository.save(org);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (user.getOnboardingCompletedAt() == null) {
            user.setOnboardingCompletedAt(Instant.now());
            userRepository.save(user);
        }

        return orgService.getOrg(orgId);
    }
}
