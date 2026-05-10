package com.certguard.service;

import com.certguard.dto.request.CreateClientOrgRequest;
import com.certguard.dto.response.OrgResponse;
import com.certguard.entity.Organization;
import com.certguard.entity.Subscription;
import com.certguard.entity.User;
import com.certguard.enums.InviteStatus;
import com.certguard.enums.OrgMemberRole;
import com.certguard.enums.OrgType;
import com.certguard.enums.SubscriptionStatus;
import com.certguard.entity.OrgMember;
import com.certguard.exception.ResourceNotFoundException;
import com.certguard.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MspClientService {

    private final OrganizationRepository orgRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final OrgMemberRepository memberRepository;
    private final UserRepository userRepository;

    public List<OrgResponse> listClients(UUID mspOrgId) {
        return orgRepository.findAllByParentOrgIdAndArchivedAtIsNull(mspOrgId)
                .stream().map(o -> {
                    Subscription sub = subscriptionRepository.findByOrganizationId(o.getId()).orElse(null);
                    return toResponse(o, sub);
                }).toList();
    }

    public OrgResponse getClient(UUID mspOrgId, UUID clientOrgId) {
        Organization client = findClientForMsp(mspOrgId, clientOrgId);
        Subscription sub = subscriptionRepository.findByOrganizationId(clientOrgId).orElse(null);
        return toResponse(client, sub);
    }

    @Transactional
    public OrgResponse createClient(UUID mspOrgId, UUID createdByUserId, CreateClientOrgRequest req) {
        Organization mspOrg = orgRepository.findById(mspOrgId)
                .orElseThrow(() -> new ResourceNotFoundException("MSP organisation not found"));
        if (mspOrg.getOrgType() != OrgType.MSP) {
            throw new IllegalStateException("Only MSP organisations can create client orgs");
        }

        Organization client = Organization.builder()
                .name(req.getName())
                .orgType(OrgType.SINGLE)
                .parentOrg(mspOrg)
                .addressLine1(req.getAddressLine1())
                .addressLine2(req.getAddressLine2())
                .city(req.getCity())
                .stateProvince(req.getStateProvince())
                .postalCode(req.getPostalCode())
                .country(req.getCountry())
                .phone(req.getPhone())
                .contactEmail(req.getContactEmail())
                .build();
        orgRepository.save(client);

        Subscription sub = Subscription.builder()
                .organization(client)
                .maxCertificateQuota(10)
                .status(SubscriptionStatus.TRIAL)
                .build();
        subscriptionRepository.save(sub);

        // Auto-add the creating MSP user as ADMIN of the new client org
        User creator = userRepository.findById(createdByUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        OrgMember membership = OrgMember.builder()
                .organization(client)
                .user(creator)
                .role(OrgMemberRole.ADMIN)
                .inviteStatus(InviteStatus.ACCEPTED)
                .build();
        memberRepository.save(membership);

        log.info("Client org '{}' created under MSP {} by {}", client.getName(), mspOrgId, createdByUserId);
        return toResponse(client, sub);
    }

    @Transactional
    public OrgResponse updateClient(UUID mspOrgId, UUID clientOrgId, CreateClientOrgRequest req) {
        Organization client = findClientForMsp(mspOrgId, clientOrgId);
        if (req.getName() != null)          client.setName(req.getName());
        if (req.getAddressLine1() != null)  client.setAddressLine1(req.getAddressLine1());
        if (req.getAddressLine2() != null)  client.setAddressLine2(req.getAddressLine2());
        if (req.getCity() != null)          client.setCity(req.getCity());
        if (req.getStateProvince() != null) client.setStateProvince(req.getStateProvince());
        if (req.getPostalCode() != null)    client.setPostalCode(req.getPostalCode());
        if (req.getCountry() != null)       client.setCountry(req.getCountry());
        if (req.getPhone() != null)         client.setPhone(req.getPhone());
        if (req.getContactEmail() != null)  client.setContactEmail(req.getContactEmail());
        Subscription sub = subscriptionRepository.findByOrganizationId(clientOrgId).orElse(null);
        return toResponse(orgRepository.save(client), sub);
    }

    private Organization findClientForMsp(UUID mspOrgId, UUID clientOrgId) {
        Organization client = orgRepository.findById(clientOrgId)
                .orElseThrow(() -> new ResourceNotFoundException("Client org not found: " + clientOrgId));
        if (client.getParentOrg() == null || !client.getParentOrg().getId().equals(mspOrgId)) {
            throw new ResourceNotFoundException("Client org not found under this MSP");
        }
        return client;
    }

    OrgResponse toResponse(Organization org, Subscription sub) {
        return OrgResponse.builder()
                .id(org.getId())
                .name(org.getName())
                .slug(org.getSlug())
                .orgType(org.getOrgType())
                .parentOrgId(org.getParentOrg() != null ? org.getParentOrg().getId() : null)
                .addressLine1(org.getAddressLine1())
                .addressLine2(org.getAddressLine2())
                .city(org.getCity())
                .stateProvince(org.getStateProvince())
                .postalCode(org.getPostalCode())
                .country(org.getCountry())
                .phone(org.getPhone())
                .contactEmail(org.getContactEmail())
                .maxCertificateQuota(sub != null ? sub.getMaxCertificateQuota() : 10)
                .status(sub != null ? sub.getStatus() : null)
                .createdAt(org.getCreatedAt())
                .build();
    }
}
