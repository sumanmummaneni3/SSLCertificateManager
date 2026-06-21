package com.certguard.service;

import com.certguard.dto.request.OrgTransferRequest;
import com.certguard.dto.request.OrgTransferUndoRequest;
import com.certguard.dto.response.OrgMigrationResponse;
import com.certguard.dto.response.OrgResponse;
import com.certguard.entity.*;
import com.certguard.enums.InviteStatus;
import com.certguard.enums.OrgMemberRole;
import com.certguard.enums.OrgType;
import com.certguard.exception.OrgMigrationException;
import com.certguard.exception.ResourceNotFoundException;
import com.certguard.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for OrgMigrationService (RFC 0010).
 *
 * All repository calls are mocked; TransactionSynchronizationManager is initialised
 * manually so afterCommit() hooks can be verified without a real transaction.
 *
 * LENIENT strictness is used because setUp() registers shared stubs that are intentionally
 * not exercised by every guard/failure test (the tests exit early before reaching those paths).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrgMigrationServiceTest {

    @Mock OrganizationRepository     orgRepository;
    @Mock OrgMemberRepository        memberRepository;
    @Mock AgentScanJobRepository     scanJobRepository;
    @Mock OrgMigrationAuditRepository auditRepository;
    @Mock SubscriptionRepository     subscriptionRepository;
    @Mock TokenRevocationService     tokenRevocationService;
    @Mock NotificationService        notificationService;
    @Mock OrgService                 orgService;

    OrgMigrationService service;

    // ── Shared fixtures ────────────────────────────────────────────────────────

    UUID sourceMspId = UUID.randomUUID();
    UUID targetMspId = UUID.randomUUID();
    UUID clientOrgId = UUID.randomUUID();
    UUID actorId     = UUID.randomUUID();
    String actorEmail = "admin@certguard.dev";

    Organization sourceMsp;
    Organization targetMsp;
    Organization clientOrg;

    User mspStaffUser;
    OrgMember mspStaffMember;

    @BeforeEach
    void setUp() {
        service = new OrgMigrationService(
                orgRepository, memberRepository, scanJobRepository, auditRepository,
                subscriptionRepository, tokenRevocationService, notificationService, orgService);

        // Build org fixtures
        sourceMsp = Organization.builder().name("Source MSP").orgType(OrgType.MSP).build();
        ReflectionTestUtils.setField(sourceMsp, "id", sourceMspId);

        targetMsp = Organization.builder().name("Target MSP").orgType(OrgType.MSP).build();
        ReflectionTestUtils.setField(targetMsp, "id", targetMspId);

        clientOrg = Organization.builder()
                .name("Client Org").orgType(OrgType.SINGLE).parentOrg(sourceMsp).build();
        ReflectionTestUtils.setField(clientOrg, "id", clientOrgId);

        // MSP staff member in the client org
        UUID staffUserId = UUID.randomUUID();
        mspStaffUser = User.builder().email("staff@sourcemsp.com").organization(sourceMsp).build();
        ReflectionTestUtils.setField(mspStaffUser, "id", staffUserId);

        mspStaffMember = OrgMember.builder()
                .organization(clientOrg).user(mspStaffUser)
                .role(OrgMemberRole.ADMIN).inviteStatus(InviteStatus.ACCEPTED).build();
        ReflectionTestUtils.setField(mspStaffMember, "id", UUID.randomUUID());

        // Default stubs for non-failing paths
        when(orgRepository.findByIdForUpdate(clientOrgId)).thenReturn(Optional.of(clientOrg));
        when(orgRepository.findById(targetMspId)).thenReturn(Optional.of(targetMsp));
        when(memberRepository.findActiveMspStaffMembers(clientOrgId, sourceMspId))
                .thenReturn(List.of(mspStaffMember));
        when(scanJobRepository.countInFlightByOrgId(clientOrgId)).thenReturn(3);
        when(subscriptionRepository.findByOrganizationId(clientOrgId)).thenReturn(Optional.empty());
        when(orgService.toResponse(any(), any())).thenReturn(OrgResponse.builder()
                .id(clientOrgId).name("Client Org").orgType(OrgType.SINGLE)
                .parentOrgId(targetMspId).maxCertificateQuota(10).build());

        // Audit save: return the record as-is so getId() works
        when(auditRepository.save(any())).thenAnswer(inv -> {
            OrgMigrationAudit a = inv.getArgument(0);
            ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
            return a;
        });
    }

    @BeforeEach
    void initTxSync() {
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void clearTxSync() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    // ── Forward transfer happy-path ───────────────────────────────────────────

    @Nested
    class ForwardTransfer {

        @Test
        void happyPath_reParentsOrg_andWritesAuditRow() {
            OrgMigrationResponse result = service.transfer(clientOrgId, validRequest(), actorId, actorEmail);

            // org.parentOrg must have been updated to targetMsp
            assertThat(clientOrg.getParentOrg()).isEqualTo(targetMsp);
            verify(orgRepository).save(clientOrg);

            // FORWARD audit row was written
            ArgumentCaptor<OrgMigrationAudit> auditCaptor =
                    ArgumentCaptor.forClass(OrgMigrationAudit.class);
            verify(auditRepository).save(auditCaptor.capture());
            OrgMigrationAudit audit = auditCaptor.getValue();
            assertThat(audit.getDirection()).isEqualTo("FORWARD");
            assertThat(audit.getSourceMspOrgId()).isEqualTo(sourceMspId);
            assertThat(audit.getTargetMspOrgId()).isEqualTo(targetMspId);
            assertThat(audit.getOrgId()).isEqualTo(clientOrgId);
            assertThat(audit.getReason()).isEqualTo("ticket-1234 customer request");
            assertThat(audit.getActingUserId()).isEqualTo(actorId);

            // Response summary
            assertThat(result.migration().direction()).isEqualTo("FORWARD");
            assertThat(result.migration().inFlightScanJobCount()).isEqualTo(3);
        }

        @Test
        void happyPath_revokesSourceMspStaffMembers() {
            service.transfer(clientOrgId, validRequest(), actorId, actorEmail);

            // revokedAt must be set on the member
            assertThat(mspStaffMember.getRevokedAt()).isNotNull();
            assertThat(mspStaffMember.getRevokedByUserId()).isEqualTo(actorId);
            assertThat(mspStaffMember.getRevokeReason()).contains("MSP migration");
            verify(memberRepository).saveAll(List.of(mspStaffMember));

            // Token revocation must be queued
            verify(tokenRevocationService).revokeForUserInOrg(
                    eq(mspStaffUser.getId()), eq(clientOrgId), eq(actorId), anyString());
        }

        @Test
        void happyPath_capturesRevokedMemberIdsInAudit() {
            service.transfer(clientOrgId, validRequest(), actorId, actorEmail);

            ArgumentCaptor<OrgMigrationAudit> cap = ArgumentCaptor.forClass(OrgMigrationAudit.class);
            verify(auditRepository).save(cap.capture());
            List<UUID> revokedIds = cap.getValue().getRevokedMemberIds();
            assertThat(revokedIds).containsExactly(mspStaffMember.getId());
            assertThat(cap.getValue().getRevokedMemberCount()).isEqualTo(1);
        }

        @Test
        void happyPath_snapshotsInFlightScanCount() {
            service.transfer(clientOrgId, validRequest(), actorId, actorEmail);

            ArgumentCaptor<OrgMigrationAudit> cap = ArgumentCaptor.forClass(OrgMigrationAudit.class);
            verify(auditRepository).save(cap.capture());
            assertThat(cap.getValue().getInFlightScanJobCount()).isEqualTo(3);
        }

        @Test
        void noMspStaffMembers_revokedCountIsZero() {
            when(memberRepository.findActiveMspStaffMembers(clientOrgId, sourceMspId))
                    .thenReturn(List.of());

            OrgMigrationResponse result = service.transfer(clientOrgId, validRequest(), actorId, actorEmail);

            assertThat(result.migration().revokedMemberCount()).isEqualTo(0);
            verify(tokenRevocationService, never()).revokeForUserInOrg(any(), any(), any(), any());
        }

        /**
         * RFC 0010 §3 post-commit: a notification failure must NOT roll back or throw
         * from transfer(). The try/catch in afterCommit() absorbs the exception.
         *
         * We simulate a failure by making dispatchOrgMigrationForward throw, then
         * trigger afterCommit() manually (TransactionSynchronizationManager is
         * initialised in @BeforeEach so registered synchronizations run synchronously
         * when we fire afterCompletion(STATUS_COMMITTED)).
         */
        @Test
        void notificationFailure_doesNotPropagateFromTransfer() {
            doThrow(new RuntimeException("SMTP down"))
                    .when(notificationService)
                    .dispatchOrgMigrationForward(any(), any(), any(), any(), any(), any(),
                            any(), any(), any());

            // transfer() must complete successfully even though the notification will throw
            assertThatNoException().isThrownBy(
                    () -> service.transfer(clientOrgId, validRequest(), actorId, actorEmail));

            // The audit row must still have been written
            verify(auditRepository).save(any(OrgMigrationAudit.class));

            // Trigger afterCommit callbacks (simulates transaction commit in unit test)
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(s -> s.afterCommit());

            // Notification was attempted (then swallowed)
            verify(notificationService).dispatchOrgMigrationForward(
                    any(), any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    // ── Forward transfer guard cases ──────────────────────────────────────────

    @Nested
    class ForwardTransferGuards {

        @Test
        void orgNotFound_throws404() {
            when(orgRepository.findByIdForUpdate(clientOrgId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.transfer(clientOrgId, validRequest(), actorId, actorEmail))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void archivedOrg_throws404() {
            clientOrg.setArchivedAt(Instant.now());

            assertThatThrownBy(() -> service.transfer(clientOrgId, validRequest(), actorId, actorEmail))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("archived");
        }

        @Test
        void orgWithoutParent_throwsOrgNotTransferable() {
            clientOrg.setParentOrg(null);

            assertThatThrownBy(() -> service.transfer(clientOrgId, validRequest(), actorId, actorEmail))
                    .isInstanceOf(OrgMigrationException.class)
                    .hasFieldOrPropertyWithValue("problemType", "org-not-transferable");
        }

        @Test
        void orgIsAnMsp_throwsOrgNotTransferable() {
            clientOrg.setOrgType(OrgType.MSP);

            assertThatThrownBy(() -> service.transfer(clientOrgId, validRequest(), actorId, actorEmail))
                    .isInstanceOf(OrgMigrationException.class)
                    .hasFieldOrPropertyWithValue("problemType", "org-not-transferable");
        }

        @Test
        void sourceMspMismatch_throws409() {
            UUID wrongSourceId = UUID.randomUUID();
            OrgTransferRequest req = validRequest();
            req.setExpectedSourceMspId(wrongSourceId);

            assertThatThrownBy(() -> service.transfer(clientOrgId, req, actorId, actorEmail))
                    .isInstanceOf(OrgMigrationException.class)
                    .hasFieldOrPropertyWithValue("problemType", "source-msp-mismatch");
        }

        @Test
        void targetMspNotFound_throws404() {
            when(orgRepository.findById(targetMspId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.transfer(clientOrgId, validRequest(), actorId, actorEmail))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void targetNotAnMsp_throwsIllegalArgument() {
            targetMsp.setOrgType(OrgType.SINGLE);

            assertThatThrownBy(() -> service.transfer(clientOrgId, validRequest(), actorId, actorEmail))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not an MSP");
        }

        @Test
        void noOpTransfer_sameTargetAsCurrentParent_throws409() {
            // Set target to same as current parent
            OrgTransferRequest req = new OrgTransferRequest();
            req.setTargetMspOrgId(sourceMspId);
            req.setReason("no-op test");

            // Target MSP lookup returns the source MSP (same ID)
            when(orgRepository.findById(sourceMspId)).thenReturn(Optional.of(sourceMsp));

            assertThatThrownBy(() -> service.transfer(clientOrgId, req, actorId, actorEmail))
                    .isInstanceOf(OrgMigrationException.class)
                    .hasFieldOrPropertyWithValue("problemType", "no-op-transfer");
        }
    }

    // ── Undo happy-path ───────────────────────────────────────────────────────

    @Nested
    class Undo {

        UUID migrationId = UUID.randomUUID();
        OrgMigrationAudit forwardRecord;

        @BeforeEach
        void setUpForwardRecord() {
            // Client org is currently under target MSP (FORWARD was applied)
            clientOrg.setParentOrg(targetMsp);

            forwardRecord = buildForwardAudit(migrationId);

            when(auditRepository.findById(migrationId)).thenReturn(Optional.of(forwardRecord));
            when(orgRepository.findByIdForUpdate(clientOrgId)).thenReturn(Optional.of(clientOrg));
            when(orgRepository.findById(sourceMspId)).thenReturn(Optional.of(sourceMsp));
            when(memberRepository.findAllByIdIn(forwardRecord.getRevokedMemberIds()))
                    .thenReturn(List.of(mspStaffMember));

            // Simulate mspStaffMember as revoked
            mspStaffMember.setRevokedAt(Instant.now().minusSeconds(60));
            mspStaffMember.setRevokedByUserId(actorId);
            mspStaffMember.setRevokeReason("MSP migration by platform admin");
        }

        @Test
        void happyPath_reParentsBackToSourceMsp() {
            service.undo(clientOrgId, undoRequest(migrationId), actorId, actorEmail);

            assertThat(clientOrg.getParentOrg()).isEqualTo(sourceMsp);
            verify(orgRepository).save(clientOrg);
        }

        @Test
        void happyPath_restoresMemberRevocations() {
            service.undo(clientOrgId, undoRequest(migrationId), actorId, actorEmail);

            // Revocation fields must be cleared
            assertThat(mspStaffMember.getRevokedAt()).isNull();
            assertThat(mspStaffMember.getRevokedByUserId()).isNull();
            assertThat(mspStaffMember.getRevokeReason()).isNull();
            verify(memberRepository).saveAll(List.of(mspStaffMember));
        }

        @Test
        void happyPath_clearsTokenRevocations() {
            service.undo(clientOrgId, undoRequest(migrationId), actorId, actorEmail);

            verify(tokenRevocationService).clearRevocationForUserInOrg(
                    mspStaffUser.getId(), clientOrgId);
        }

        @Test
        void happyPath_writesReverseAuditRow() {
            service.undo(clientOrgId, undoRequest(migrationId), actorId, actorEmail);

            ArgumentCaptor<OrgMigrationAudit> cap = ArgumentCaptor.forClass(OrgMigrationAudit.class);
            verify(auditRepository).save(cap.capture());
            OrgMigrationAudit reverseAudit = cap.getValue();
            assertThat(reverseAudit.getDirection()).isEqualTo("REVERSE");
            assertThat(reverseAudit.getReversesMigrationId()).isEqualTo(migrationId);
            assertThat(reverseAudit.getOrgId()).isEqualTo(clientOrgId);
        }

        @Test
        void happyPath_responseSummaryShowsRestoredCount() {
            OrgMigrationResponse result =
                    service.undo(clientOrgId, undoRequest(migrationId), actorId, actorEmail);

            assertThat(result.migration().direction()).isEqualTo("REVERSE");
            assertThat(result.migration().restoredMemberCount()).isEqualTo(1);
            assertThat(result.migration().revokedMemberCount()).isEqualTo(0);
        }

        @Test
        void undoStale_orgMovedAgain_throws409() {
            // Org has moved to a third MSP since the FORWARD
            UUID thirdMspId = UUID.randomUUID();
            Organization thirdMsp = Organization.builder().name("Third MSP").orgType(OrgType.MSP).build();
            ReflectionTestUtils.setField(thirdMsp, "id", thirdMspId);
            clientOrg.setParentOrg(thirdMsp); // no longer under forwardRecord.targetMspOrgId

            assertThatThrownBy(() -> service.undo(clientOrgId, undoRequest(migrationId), actorId, actorEmail))
                    .isInstanceOf(OrgMigrationException.class)
                    .hasFieldOrPropertyWithValue("problemType", "undo-stale");
        }

        @Test
        void migrationIdNotFound_throws404() {
            when(auditRepository.findById(migrationId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.undo(clientOrgId, undoRequest(migrationId), actorId, actorEmail))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void migrationNotForThisOrg_throwsIllegalArgument() {
            UUID wrongOrgId = UUID.randomUUID();
            forwardRecord = buildForwardAudit(migrationId, wrongOrgId);
            when(auditRepository.findById(migrationId)).thenReturn(Optional.of(forwardRecord));

            assertThatThrownBy(() -> service.undo(clientOrgId, undoRequest(migrationId), actorId, actorEmail))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not belong to org");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private OrgTransferRequest validRequest() {
        OrgTransferRequest req = new OrgTransferRequest();
        req.setTargetMspOrgId(targetMspId);
        req.setExpectedSourceMspId(sourceMspId);
        req.setReason("ticket-1234 customer request");
        req.setReferenceTicket("TICKET-1234");
        return req;
    }

    private OrgTransferUndoRequest undoRequest(UUID migrationId) {
        OrgTransferUndoRequest req = new OrgTransferUndoRequest();
        req.setMigrationId(migrationId);
        req.setReason("mistaken transfer, reversing");
        return req;
    }

    private OrgMigrationAudit buildForwardAudit(UUID id) {
        return buildForwardAudit(id, clientOrgId);
    }

    private OrgMigrationAudit buildForwardAudit(UUID id, UUID orgId) {
        OrgMigrationAudit audit = OrgMigrationAudit.builder()
                .direction("FORWARD")
                .orgId(orgId)
                .orgName("Client Org")
                .sourceMspOrgId(sourceMspId)
                .sourceMspName("Source MSP")
                .targetMspOrgId(targetMspId)
                .targetMspName("Target MSP")
                .actingUserId(actorId)
                .actingUserEmail(actorEmail)
                .reason("ticket-1234 customer request")
                .revokedMemberIds(List.of(mspStaffMember.getId()))
                .revokedMemberCount(1)
                .inFlightScanJobCount(3)
                .build();
        ReflectionTestUtils.setField(audit, "id", id);
        return audit;
    }
}
