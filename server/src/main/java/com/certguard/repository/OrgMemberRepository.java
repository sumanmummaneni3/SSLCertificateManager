package com.certguard.repository;

import com.certguard.entity.OrgMember;
import com.certguard.enums.InviteStatus;
import com.certguard.enums.OrgMemberRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrgMemberRepository extends JpaRepository<OrgMember, UUID> {
    List<OrgMember> findAllByOrganizationId(UUID orgId);
    Optional<OrgMember> findByOrganizationIdAndUserId(UUID orgId, UUID userId);
    boolean existsByOrganizationIdAndUserIdAndInviteStatus(UUID orgId, UUID userId, InviteStatus status);
    long countByOrganizationIdAndRoleAndInviteStatus(UUID orgId, OrgMemberRole role, InviteStatus status);
    List<OrgMember> findAllByUserId(UUID userId);
}
