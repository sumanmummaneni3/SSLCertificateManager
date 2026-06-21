package com.certguard.repository;

import com.certguard.entity.OrgMember;
import com.certguard.enums.InviteStatus;
import com.certguard.enums.OrgMemberRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * RFC 0010: finds all active (non-revoked) org_members of a client org whose user's
     * home org (user.org_id) is the source MSP. These are the direct memberships created
     * by MspClientService.createClient() that must be revoked when the org is transferred.
     */
    @Query("""
            SELECT m FROM OrgMember m
            WHERE m.organization.id = :clientOrgId
              AND m.user.organization.id = :sourceMspId
              AND m.revokedAt IS NULL
            """)
    List<OrgMember> findActiveMspStaffMembers(
            @Param("clientOrgId") UUID clientOrgId,
            @Param("sourceMspId") UUID sourceMspId);

    /**
     * RFC 0010 undo: loads OrgMember rows by their primary key ids.
     * Used to restore exactly the membership rows recorded in revoked_member_ids.
     */
    @Query("SELECT m FROM OrgMember m WHERE m.id IN :ids")
    List<OrgMember> findAllByIdIn(@Param("ids") List<UUID> ids);
}
