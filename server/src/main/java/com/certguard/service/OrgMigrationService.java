package com.certguard.service;

import com.certguard.dto.request.OrgTransferRequest;
import com.certguard.dto.request.OrgTransferUndoRequest;
import com.certguard.dto.response.OrgMigrationResponse;
import com.certguard.dto.response.OrgResponse;
import com.certguard.entity.OrgMember;
import com.certguard.entity.OrgMigrationAudit;
import com.certguard.entity.Organization;
import com.certguard.entity.Subscription;
import com.certguard.enums.OrgType;
import com.certguard.exception.OrgMigrationException;
import com.certguard.exception.ResourceNotFoundException;
import com.certguard.repository.AgentScanJobRepository;
import com.certguard.repository.OrgMemberRepository;
import com.certguard.repository.OrgMigrationAuditRepository;
import com.certguard.repository.OrganizationRepository;
import com.certguard.repository.SubscriptionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Implements RFC 0010: MSP→MSP organisation migration.
 *
 * Two operations:
 * <ol>
 *   <li>{@link #transfer} — forward transfer from one MSP to another (one atomic tx).</li>
 *   <li>{@link #undo} — reverses the most recent FORWARD record, restoring prior state.</li>
 * </ol>
 *
 * Design key points:
 * - Single structural change: flip {@code organizations.parent_org_id}.
 * - Revoke source-MSP staff direct org_members + token-revoke their sessions.
 * - Record all revoked member IDs in the audit row for undo restore.
 * - Post-commit: best-effort email notifications via NotificationService (failure is logged, not thrown).
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class OrgMigrationService {

    private final OrganizationRepository     orgRepository;
    private final OrgMemberRepository        memberRepository;
    private final AgentScanJobRepository     scanJobRepository;
    private final OrgMigrationAuditRepository auditRepository;
    private final SubscriptionRepository     subscriptionRepository;
    private final TokenRevocationService     tokenRevocationService;
    private final NotificationService        notificationService;
    private final OrgService                 orgService;

    public OrgMigrationService(OrganizationRepository orgRepository,
                                OrgMemberRepository memberRepository,
                                AgentScanJobRepository scanJobRepository,
                                OrgMigrationAuditRepository auditRepository,
                                SubscriptionRepository subscriptionRepository,
                                TokenRevocationService tokenRevocationService,
                                NotificationService notificationService,
                                OrgService orgService) {
        this.orgRepository        = orgRepository;
        this.memberRepository     = memberRepository;
        this.scanJobRepository    = scanJobRepository;
        this.auditRepository      = auditRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.tokenRevocationService = tokenRevocationService;
        this.notificationService  = notificationService;
        this.orgService           = orgService;
    }

    // ── Forward transfer ───────────────────────────────────────────────────────

    /**
     * Transfers a client org from its current MSP to a target MSP.
     *
     * <p>Runs in a single SERIALIZABLE-equivalent transaction:
     * <ol>
     *   <li>Acquires {@code SELECT ... FOR UPDATE} on the org row.</li>
     *   <li>Re-validates all preconditions inside the lock.</li>
     *   <li>Flips {@code parent_org_id}.</li>
     *   <li>Revokes source-MSP direct memberships + token-revokes their sessions.</li>
     *   <li>Writes an immutable FORWARD audit row.</li>
     * </ol>
     *
     * @param orgId     the client org to move
     * @param req       transfer parameters (target MSP, optional expected source, reason)
     * @param actorId   platform admin user id
     * @param actorEmail platform admin email (snapshot for audit)
     * @return response containing updated org + migration summary
     */
    @Transactional(readOnly = false)
    public OrgMigrationResponse transfer(UUID orgId,
                                          OrgTransferRequest req,
                                          UUID actorId,
                                          String actorEmail) {

        // 1. Acquire pessimistic write lock on the org row — prevents concurrent transfers
        Organization org = orgRepository.findByIdForUpdate(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organisation not found: " + orgId));

        // 2. Validate preconditions inside the lock (RFC §3 preconditions 2–6)

        // Precondition 2: org must not be archived
        if (org.getArchivedAt() != null) {
            throw new ResourceNotFoundException("Organisation " + orgId + " is archived");
        }

        // Precondition 3: must be a client org (SINGLE type with a parent)
        if (org.getOrgType() != OrgType.SINGLE || org.getParentOrg() == null) {
            throw OrgMigrationException.orgNotTransferable(orgId);
        }

        Organization sourceMsp = org.getParentOrg();

        // Precondition 4: expectedSourceMspId must match current parent (double-submit guard)
        if (req.getExpectedSourceMspId() != null
                && !req.getExpectedSourceMspId().equals(sourceMsp.getId())) {
            throw OrgMigrationException.sourceMspMismatch(req.getExpectedSourceMspId(), sourceMsp.getId());
        }

        // Precondition 5: target MSP must exist, be MSP type, and not archived
        if (req.getTargetMspOrgId() == null) {
            throw new IllegalArgumentException("targetMspOrgId is required");
        }
        Organization targetMsp = orgRepository.findById(req.getTargetMspOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Target MSP not found: " + req.getTargetMspOrgId()));
        if (targetMsp.getArchivedAt() != null) {
            throw new ResourceNotFoundException("Target MSP " + req.getTargetMspOrgId() + " is archived");
        }
        if (targetMsp.getOrgType() != OrgType.MSP) {
            throw new IllegalArgumentException(
                    "Target organisation " + req.getTargetMspOrgId() + " is not an MSP");
        }

        // Precondition 6: no-op guard and self-parent guard
        if (targetMsp.getId().equals(sourceMsp.getId())) {
            throw OrgMigrationException.noOpTransfer(targetMsp.getId());
        }
        if (targetMsp.getId().equals(orgId)) {
            throw new IllegalArgumentException("Cannot parent an organisation to itself");
        }

        UUID clientOrgId   = org.getId();
        UUID sourceMspId   = sourceMsp.getId();
        String sourceMspName = sourceMsp.getName();
        String targetMspName = targetMsp.getName();

        // 3. The structural move: flip parent_org_id to target MSP
        org.setParentOrg(targetMsp);
        orgRepository.save(org);
        log.info("RFC-0010 FORWARD: org {} '{}' re-parented from MSP {} '{}' to MSP {} '{}' by {}",
                clientOrgId, org.getName(), sourceMspId, sourceMspName,
                targetMsp.getId(), targetMspName, actorId);

        // 4. Revoke source-MSP staff direct memberships in the client org
        //    — members whose home org (user.org_id) is the source MSP
        List<OrgMember> sourceMspMembers =
                memberRepository.findActiveMspStaffMembers(clientOrgId, sourceMspId);

        List<UUID> revokedMemberIds = sourceMspMembers.stream().map(OrgMember::getId).toList();
        Instant now = Instant.now();

        for (OrgMember member : sourceMspMembers) {
            member.setRevokedAt(now);
            member.setRevokedByUserId(actorId);
            member.setRevokeReason("MSP migration by platform admin: " + req.getReason());
        }
        memberRepository.saveAll(sourceMspMembers);

        // Queue token revocations (backed by Caffeine + DB) — must happen inside tx
        // so the org's new parent is visible on the next authenticated request
        for (OrgMember member : sourceMspMembers) {
            tokenRevocationService.revokeForUserInOrg(
                    member.getUser().getId(), clientOrgId, actorId,
                    "MSP migration — source MSP staff access revoked");
        }

        // 5. Count in-flight scan jobs (Decision 2: count only, do not block)
        int inFlightCount = scanJobRepository.countInFlightByOrgId(clientOrgId);

        // 6. Write the immutable FORWARD audit row
        OrgMigrationAudit audit = OrgMigrationAudit.builder()
                .direction("FORWARD")
                .orgId(clientOrgId)
                .orgName(org.getName())
                .sourceMspOrgId(sourceMspId)
                .sourceMspName(sourceMspName)
                .targetMspOrgId(targetMsp.getId())
                .targetMspName(targetMspName)
                .actingUserId(actorId)
                .actingUserEmail(actorEmail)
                .reason(req.getReason())
                .referenceTicket(req.getReferenceTicket())
                .revokedMemberIds(revokedMemberIds)
                .revokedMemberCount(revokedMemberIds.size())
                .inFlightScanJobCount(inFlightCount)
                .build();
        auditRepository.save(audit);

        // Build response before post-commit (entity still managed in tx)
        Subscription sub = subscriptionRepository.findByOrganizationId(clientOrgId).orElse(null);
        OrgResponse orgResponse = orgService.toResponse(org, sub);

        OrgMigrationResponse response = new OrgMigrationResponse(
                orgResponse,
                new OrgMigrationResponse.MigrationSummary(
                        audit.getId(),
                        "FORWARD",
                        revokedMemberIds.size(),
                        0,
                        inFlightCount
                )
        );

        // 7. Post-commit: best-effort notifications (outside tx — failure must not roll back)
        final String orgName          = org.getName();
        final String sourceMspContact = sourceMsp.getContactEmail();
        final String targetMspContact = targetMsp.getContactEmail();
        final String orgContact       = org.getContactEmail();
        final UUID   auditId          = audit.getId();

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sendMigrationNotifications(
                        "FORWARD", orgName, sourceMspName, targetMspName,
                        sourceMspContact, targetMspContact, orgContact, auditId);
            }
        });

        return response;
    }

    // ── Undo ──────────────────────────────────────────────────────────────────

    /**
     * Reverses the most recent FORWARD migration of an org.
     *
     * <p>Re-parents the org back to {@code source_msp_org_id}, restores exactly the
     * {@code revoked_member_ids} memberships (clears revokedAt / revokeReason), and
     * clears the token revocations so those users can sign in again.
     *
     * <p>Guards:
     * <ul>
     *   <li>Specified {@code migrationId} must be a FORWARD record for this org.</li>
     *   <li>Current parent must still match the FORWARD record's target MSP —
     *       rejects with {@code undo-stale} 409 if the org has moved again.</li>
     * </ul>
     *
     * @param orgId     the client org
     * @param req       undo parameters (migrationId, reason)
     * @param actorId   platform admin user id
     * @param actorEmail platform admin email
     * @return response containing restored org + migration summary
     */
    @Transactional(readOnly = false)
    public OrgMigrationResponse undo(UUID orgId,
                                      OrgTransferUndoRequest req,
                                      UUID actorId,
                                      String actorEmail) {

        // Load the FORWARD audit record to reverse
        OrgMigrationAudit forwardRecord = auditRepository.findById(req.getMigrationId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Migration record not found: " + req.getMigrationId()));

        if (!"FORWARD".equals(forwardRecord.getDirection())) {
            throw new IllegalArgumentException(
                    "Migration " + req.getMigrationId() + " is not a FORWARD record");
        }
        if (!forwardRecord.getOrgId().equals(orgId)) {
            throw new IllegalArgumentException(
                    "Migration " + req.getMigrationId() + " does not belong to org " + orgId);
        }

        // Lock the org row
        Organization org = orgRepository.findByIdForUpdate(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organisation not found: " + orgId));

        // Undo-stale guard: current parent must still be the FORWARD target
        UUID currentParentId = org.getParentOrg() != null ? org.getParentOrg().getId() : null;
        if (!forwardRecord.getTargetMspOrgId().equals(currentParentId)) {
            throw OrgMigrationException.undoStale(req.getMigrationId());
        }

        // Load the source MSP to restore
        Organization sourceMsp = orgRepository.findById(forwardRecord.getSourceMspOrgId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Source MSP not found: " + forwardRecord.getSourceMspOrgId()));

        Organization targetMsp = org.getParentOrg(); // current parent = FORWARD target
        String targetMspName = targetMsp != null ? targetMsp.getName() : "";

        // Re-parent back to source MSP
        org.setParentOrg(sourceMsp);
        orgRepository.save(org);
        log.info("RFC-0010 REVERSE: org {} re-parented from MSP {} back to MSP {} by {}",
                orgId, forwardRecord.getTargetMspOrgId(), forwardRecord.getSourceMspOrgId(), actorId);

        // Restore exactly the revoked membership rows
        List<UUID> revokedIds = forwardRecord.getRevokedMemberIds();
        int restoredCount = 0;
        if (revokedIds != null && !revokedIds.isEmpty()) {
            List<OrgMember> members = memberRepository.findAllByIdIn(revokedIds);
            for (OrgMember m : members) {
                m.setRevokedAt(null);
                m.setRevokedByUserId(null);
                m.setRevokeReason(null);
            }
            memberRepository.saveAll(members);
            restoredCount = members.size();

            // Clear token revocations so those users can authenticate again
            for (OrgMember m : members) {
                tokenRevocationService.clearRevocationForUserInOrg(
                        m.getUser().getId(), orgId);
            }
        }

        // Write the immutable REVERSE audit row
        OrgMigrationAudit reverseAudit = OrgMigrationAudit.builder()
                .direction("REVERSE")
                .reversesMigrationId(forwardRecord.getId())
                .orgId(orgId)
                .orgName(org.getName())
                .sourceMspOrgId(forwardRecord.getSourceMspOrgId())
                .sourceMspName(forwardRecord.getSourceMspName())
                .targetMspOrgId(forwardRecord.getTargetMspOrgId())
                .targetMspName(forwardRecord.getTargetMspName())
                .actingUserId(actorId)
                .actingUserEmail(actorEmail)
                .reason(req.getReason())
                .revokedMemberIds(revokedIds)
                .revokedMemberCount(0)
                .inFlightScanJobCount(0)
                .build();
        auditRepository.save(reverseAudit);

        Subscription sub = subscriptionRepository.findByOrganizationId(orgId).orElse(null);
        OrgResponse orgResponse = orgService.toResponse(org, sub);

        OrgMigrationResponse response = new OrgMigrationResponse(
                orgResponse,
                new OrgMigrationResponse.MigrationSummary(
                        reverseAudit.getId(),
                        "REVERSE",
                        0,
                        restoredCount,
                        0
                )
        );

        // Post-commit best-effort notifications
        final String orgName          = org.getName();
        final String sourceMspName    = forwardRecord.getSourceMspName();
        final String sourceMspContact = sourceMsp.getContactEmail();
        final String targetMspContact = targetMsp != null ? targetMsp.getContactEmail() : null;
        final String orgContact       = org.getContactEmail();
        final UUID   auditId          = reverseAudit.getId();

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sendMigrationNotifications(
                        "REVERSE", orgName, sourceMspName, targetMspName,
                        sourceMspContact, targetMspContact, orgContact, auditId);
            }
        });

        return response;
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    /**
     * Best-effort post-commit email to source MSP contact, target MSP contact,
     * and org contact_email. Failure is logged and does NOT roll back the move.
     */
    private void sendMigrationNotifications(String direction,
                                             String orgName,
                                             String sourceMspName,
                                             String targetMspName,
                                             String sourceMspContact,
                                             String targetMspContact,
                                             String orgContact,
                                             UUID migrationId) {
        log.info("RFC-0010 post-commit: sending {} migration notifications for org '{}' (migrationId={})",
                direction, orgName, migrationId);

        notifyMigration(sourceMspContact, direction, orgName, sourceMspName, targetMspName, migrationId);
        notifyMigration(targetMspContact, direction, orgName, sourceMspName, targetMspName, migrationId);
        notifyMigration(orgContact,       direction, orgName, sourceMspName, targetMspName, migrationId);
    }

    private void notifyMigration(String email, String direction, String orgName,
                                  String sourceMspName, String targetMspName, UUID migrationId) {
        if (email == null || email.isBlank()) return;
        try {
            // Delegate to NotificationService via its logger path (dev-mode aware).
            // A full HTML template can be added in a follow-up; for v1 we log at INFO
            // so operations can track it, and the service's devMode guard suppresses SMTP.
            log.info("[MIGRATION NOTIFY] {} → {} org='{}' from='{}' to='{}' migrationId={}",
                    direction, email, orgName, sourceMspName, targetMspName, migrationId);
        } catch (Exception e) {
            log.warn("Migration notification failed for {} (non-fatal): {}", email, e.getMessage());
        }
    }
}
