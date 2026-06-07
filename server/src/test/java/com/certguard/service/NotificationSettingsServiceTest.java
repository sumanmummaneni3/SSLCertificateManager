package com.certguard.service;

import com.certguard.dto.request.NotificationSettingsRequest;
import com.certguard.dto.response.NotificationSettingsResponse;
import com.certguard.entity.NotificationSettings;
import com.certguard.entity.Organization;
import com.certguard.entity.Target;
import com.certguard.exception.ResourceNotFoundException;
import com.certguard.repository.NotificationSettingsRepository;
import com.certguard.repository.OrganizationRepository;
import com.certguard.repository.TargetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for NotificationSettingsService (RFC 0008 §3).
 *
 * Verifies:
 *  - upsert creates a new row when none exists
 *  - upsert updates the existing row
 *  - delete is idempotent (no-op when no row)
 *  - validation rejects criticalDays >= warningDays → IllegalArgumentException
 *  - getTargetSettings returns inherited=true when no per-target override exists
 *  - resolution order: per-target beats org-default beats app-yml fallback
 */
@ExtendWith(MockitoExtension.class)
class NotificationSettingsServiceTest {

    @Mock NotificationSettingsRepository settingsRepository;
    @Mock OrganizationRepository orgRepository;
    @Mock TargetRepository targetRepository;

    NotificationSettingsService service;

    UUID orgId;
    UUID targetId;
    Organization org;
    Target target;

    @BeforeEach
    void setUp() {
        service = new NotificationSettingsService(settingsRepository, orgRepository, targetRepository);
        // Inject app-yml fallback values.
        ReflectionTestUtils.setField(service, "defaultWarningDays", 30);
        ReflectionTestUtils.setField(service, "defaultCriticalDays", 7);
        ReflectionTestUtils.setField(service, "defaultDedupHours", 23);

        orgId = UUID.randomUUID();
        targetId = UUID.randomUUID();

        org = Organization.builder().name("TestOrg").build();
        ReflectionTestUtils.setField(org, "id", orgId);

        target = Target.builder().organization(org).host("example.com").port(443).enabled(true).build();
        ReflectionTestUtils.setField(target, "id", targetId);
    }

    private NotificationSettingsRequest validRequest(int warning, int critical, int dedup, boolean enabled) {
        NotificationSettingsRequest req = new NotificationSettingsRequest();
        req.setEnabled(enabled);
        req.setWarningDays(warning);
        req.setCriticalDays(critical);
        req.setDedupHours(dedup);
        return req;
    }

    private NotificationSettings buildRow(Organization o, Target t, boolean enabled,
                                          int warning, int critical, int dedup) {
        NotificationSettings ns = NotificationSettings.builder()
                .organization(o).target(t)
                .enabled(enabled).warningDays(warning).criticalDays(critical).dedupHours(dedup)
                .build();
        ReflectionTestUtils.setField(ns, "id", UUID.randomUUID());
        return ns;
    }

    // ── Validation ────────────────────────────────────────────────────────────

    @Nested
    class Validation {

        @Test
        void criticalDays_equalToWarningDays_throwsIllegalArgument() {
            NotificationSettingsRequest req = validRequest(30, 30, 23, true);
            lenient().when(orgRepository.findById(orgId)).thenReturn(Optional.of(org));
            assertThatThrownBy(() -> service.upsertOrgSettings(orgId, req))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("criticalDays");
        }

        @Test
        void criticalDays_greaterThanWarningDays_throwsIllegalArgument() {
            NotificationSettingsRequest req = validRequest(20, 25, 23, true);
            lenient().when(orgRepository.findById(orgId)).thenReturn(Optional.of(org));
            assertThatThrownBy(() -> service.upsertOrgSettings(orgId, req))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void criticalDays_lessThanWarningDays_noValidationError() {
            NotificationSettingsRequest req = validRequest(30, 7, 23, true);
            when(orgRepository.findById(orgId)).thenReturn(Optional.of(org));
            when(settingsRepository.findByOrganizationIdAndTargetIsNull(orgId))
                    .thenReturn(Optional.empty());
            NotificationSettings saved = buildRow(org, null, true, 30, 7, 23);
            when(settingsRepository.save(any())).thenReturn(saved);

            assertThatCode(() -> service.upsertOrgSettings(orgId, req)).doesNotThrowAnyException();
        }

        @Test
        void targetUpsert_criticalDays_equalToWarning_throwsIllegalArgument() {
            NotificationSettingsRequest req = validRequest(14, 14, 23, true);
            lenient().when(targetRepository.findByIdAndOrganizationId(targetId, orgId))
                    .thenReturn(Optional.of(target));
            assertThatThrownBy(() -> service.upsertTargetSettings(orgId, targetId, req))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ── Org-default CRUD ──────────────────────────────────────────────────────

    @Nested
    class OrgDefaultCrud {

        @Test
        void getOrgSettings_noRow_returnsFallbackWithNullId() {
            when(settingsRepository.findByOrganizationIdAndTargetIsNull(orgId))
                    .thenReturn(Optional.empty());

            NotificationSettingsResponse resp = service.getOrgSettings(orgId);

            assertThat(resp.getId()).isNull();
            assertThat(resp.getWarningDays()).isEqualTo(30);
            assertThat(resp.getCriticalDays()).isEqualTo(7);
            assertThat(resp.getDedupHours()).isEqualTo(23);
            assertThat(resp.isEnabled()).isTrue();
            assertThat(resp.isInherited()).isFalse(); // org default is not "inherited"
        }

        @Test
        void getOrgSettings_rowExists_returnsRowValues() {
            NotificationSettings row = buildRow(org, null, false, 45, 10, 12);
            when(settingsRepository.findByOrganizationIdAndTargetIsNull(orgId))
                    .thenReturn(Optional.of(row));

            NotificationSettingsResponse resp = service.getOrgSettings(orgId);

            assertThat(resp.getId()).isEqualTo(row.getId());
            assertThat(resp.isEnabled()).isFalse();
            assertThat(resp.getWarningDays()).isEqualTo(45);
            assertThat(resp.getCriticalDays()).isEqualTo(10);
            assertThat(resp.getDedupHours()).isEqualTo(12);
            assertThat(resp.isInherited()).isFalse();
        }

        @Test
        void upsertOrgSettings_noExistingRow_createsNewRow() {
            NotificationSettingsRequest req = validRequest(45, 10, 12, false);
            when(orgRepository.findById(orgId)).thenReturn(Optional.of(org));
            when(settingsRepository.findByOrganizationIdAndTargetIsNull(orgId))
                    .thenReturn(Optional.empty());
            NotificationSettings saved = buildRow(org, null, false, 45, 10, 12);
            when(settingsRepository.save(any())).thenReturn(saved);

            NotificationSettingsResponse resp = service.upsertOrgSettings(orgId, req);

            ArgumentCaptor<NotificationSettings> captor =
                    ArgumentCaptor.forClass(NotificationSettings.class);
            verify(settingsRepository).save(captor.capture());
            assertThat(captor.getValue().getWarningDays()).isEqualTo(45);
            assertThat(captor.getValue().getCriticalDays()).isEqualTo(10);
            assertThat(captor.getValue().getEnabled()).isFalse();
            assertThat(resp.isInherited()).isFalse();
        }

        @Test
        void upsertOrgSettings_existingRow_updatesInPlace() {
            NotificationSettingsRequest req = validRequest(60, 14, 20, true);
            when(orgRepository.findById(orgId)).thenReturn(Optional.of(org));
            NotificationSettings existing = buildRow(org, null, false, 30, 7, 23);
            when(settingsRepository.findByOrganizationIdAndTargetIsNull(orgId))
                    .thenReturn(Optional.of(existing));
            when(settingsRepository.save(same(existing))).thenReturn(existing);

            service.upsertOrgSettings(orgId, req);

            // Must save the same object, not a new one.
            verify(settingsRepository).save(same(existing));
            assertThat(existing.getWarningDays()).isEqualTo(60);
            assertThat(existing.getCriticalDays()).isEqualTo(14);
            assertThat(existing.getDedupHours()).isEqualTo(20);
        }

        @Test
        void upsertOrgSettings_orgNotFound_throwsResourceNotFound() {
            NotificationSettingsRequest req = validRequest(30, 7, 23, true);
            when(orgRepository.findById(orgId)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.upsertOrgSettings(orgId, req))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // ── Per-target override CRUD ──────────────────────────────────────────────

    @Nested
    class TargetOverrideCrud {

        @Test
        void getTargetSettings_noOverride_noOrgDefault_returnsAppYmlFallbackInherited() {
            when(targetRepository.findByIdAndOrganizationId(targetId, orgId))
                    .thenReturn(Optional.of(target));
            when(settingsRepository.findByTargetId(targetId)).thenReturn(Optional.empty());
            when(settingsRepository.findByOrganizationIdAndTargetIsNull(orgId))
                    .thenReturn(Optional.empty());

            NotificationSettingsResponse resp = service.getTargetSettings(orgId, targetId);

            assertThat(resp.isInherited()).isTrue();
            assertThat(resp.getId()).isNull();
            assertThat(resp.getWarningDays()).isEqualTo(30); // app-yml fallback
        }

        @Test
        void getTargetSettings_noOverride_orgDefaultExists_returnsOrgDefaultInherited() {
            when(targetRepository.findByIdAndOrganizationId(targetId, orgId))
                    .thenReturn(Optional.of(target));
            when(settingsRepository.findByTargetId(targetId)).thenReturn(Optional.empty());
            NotificationSettings orgDefault = buildRow(org, null, true, 45, 10, 12);
            when(settingsRepository.findByOrganizationIdAndTargetIsNull(orgId))
                    .thenReturn(Optional.of(orgDefault));

            NotificationSettingsResponse resp = service.getTargetSettings(orgId, targetId);

            assertThat(resp.isInherited()).isTrue();
            assertThat(resp.getId()).isEqualTo(orgDefault.getId());
            assertThat(resp.getWarningDays()).isEqualTo(45);
        }

        @Test
        void getTargetSettings_overrideExists_returnsOverrideNotInherited() {
            when(targetRepository.findByIdAndOrganizationId(targetId, orgId))
                    .thenReturn(Optional.of(target));
            NotificationSettings override = buildRow(org, target, false, 20, 5, 10);
            when(settingsRepository.findByTargetId(targetId)).thenReturn(Optional.of(override));

            NotificationSettingsResponse resp = service.getTargetSettings(orgId, targetId);

            assertThat(resp.isInherited()).isFalse();
            assertThat(resp.getId()).isEqualTo(override.getId());
            assertThat(resp.isEnabled()).isFalse();
            assertThat(resp.getWarningDays()).isEqualTo(20);
        }

        @Test
        void getTargetSettings_targetNotInOrg_throwsResourceNotFound() {
            when(targetRepository.findByIdAndOrganizationId(targetId, orgId))
                    .thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.getTargetSettings(orgId, targetId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void upsertTargetSettings_noExistingOverride_createsNew() {
            NotificationSettingsRequest req = validRequest(20, 5, 10, false);
            when(targetRepository.findByIdAndOrganizationId(targetId, orgId))
                    .thenReturn(Optional.of(target));
            when(settingsRepository.findByTargetId(targetId)).thenReturn(Optional.empty());
            NotificationSettings saved = buildRow(org, target, false, 20, 5, 10);
            when(settingsRepository.save(any())).thenReturn(saved);

            NotificationSettingsResponse resp = service.upsertTargetSettings(orgId, targetId, req);

            ArgumentCaptor<NotificationSettings> captor =
                    ArgumentCaptor.forClass(NotificationSettings.class);
            verify(settingsRepository).save(captor.capture());
            assertThat(captor.getValue().getTarget()).isEqualTo(target);
            assertThat(captor.getValue().getWarningDays()).isEqualTo(20);
            assertThat(resp.isInherited()).isFalse();
        }

        @Test
        void upsertTargetSettings_existingOverride_updatesInPlace() {
            NotificationSettingsRequest req = validRequest(25, 6, 11, true);
            when(targetRepository.findByIdAndOrganizationId(targetId, orgId))
                    .thenReturn(Optional.of(target));
            NotificationSettings existing = buildRow(org, target, false, 20, 5, 10);
            when(settingsRepository.findByTargetId(targetId)).thenReturn(Optional.of(existing));
            when(settingsRepository.save(same(existing))).thenReturn(existing);

            service.upsertTargetSettings(orgId, targetId, req);

            verify(settingsRepository).save(same(existing));
            assertThat(existing.getWarningDays()).isEqualTo(25);
            assertThat(existing.getCriticalDays()).isEqualTo(6);
        }

        @Test
        void upsertTargetSettings_targetNotInOrg_throwsResourceNotFound() {
            NotificationSettingsRequest req = validRequest(30, 7, 23, true);
            when(targetRepository.findByIdAndOrganizationId(targetId, orgId))
                    .thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.upsertTargetSettings(orgId, targetId, req))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void deleteTargetSettings_overrideExists_deletesIt() {
            when(targetRepository.findByIdAndOrganizationId(targetId, orgId))
                    .thenReturn(Optional.of(target));
            NotificationSettings existing = buildRow(org, target, true, 30, 7, 23);
            when(settingsRepository.findByTargetId(targetId)).thenReturn(Optional.of(existing));

            service.deleteTargetSettings(orgId, targetId);

            verify(settingsRepository).delete(existing);
        }

        @Test
        void deleteTargetSettings_noOverride_idempotentNoException() {
            when(targetRepository.findByIdAndOrganizationId(targetId, orgId))
                    .thenReturn(Optional.of(target));
            when(settingsRepository.findByTargetId(targetId)).thenReturn(Optional.empty());

            assertThatCode(() -> service.deleteTargetSettings(orgId, targetId))
                    .doesNotThrowAnyException();
            verify(settingsRepository, never()).delete(any(NotificationSettings.class));
        }

        @Test
        void deleteTargetSettings_targetNotInOrg_throwsResourceNotFound() {
            when(targetRepository.findByIdAndOrganizationId(targetId, orgId))
                    .thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.deleteTargetSettings(orgId, targetId))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }
}
