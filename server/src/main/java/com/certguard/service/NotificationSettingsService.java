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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * CRUD service for notification_settings rows (RFC 0008 §3).
 *
 * Handles the org-default and per-target override scopes independently.
 * Validation (cross-field: criticalDays < warningDays) is enforced here
 * so it routes through GlobalExceptionHandler's IllegalArgumentException → 400 mapping.
 */
@Service
@Transactional(readOnly = true)
public class NotificationSettingsService {

    private static final Logger log = LoggerFactory.getLogger(NotificationSettingsService.class);

    private final NotificationSettingsRepository settingsRepository;
    private final OrganizationRepository orgRepository;
    private final TargetRepository targetRepository;

    // App-yml fallback values — used when no row exists and for the effective-view response.
    @Value("${app.alert.warning-days:30}")  private int defaultWarningDays;
    @Value("${app.alert.critical-days:7}") private int defaultCriticalDays;
    @Value("${app.alert.dedup-hours:23}")  private int defaultDedupHours;

    // RFC 0009: revocation fallback defaults
    @Value("${app.revocation.enabled:true}")              private boolean defaultRevocationCheckEnabled;
    @Value("${app.revocation.fail-mode:SOFT}")            private String defaultRevocationFailMode;
    @Value("${app.chain.alert-on-untrusted:false}")       private boolean defaultAlertOnUntrustedChain;

    public NotificationSettingsService(NotificationSettingsRepository settingsRepository,
                                       OrganizationRepository orgRepository,
                                       TargetRepository targetRepository) {
        this.settingsRepository = settingsRepository;
        this.orgRepository = orgRepository;
        this.targetRepository = targetRepository;
    }

    // ── Org-default ───────────────────────────────────────────────────────────

    /**
     * Returns the org-default settings row, or a synthetic fallback response when
     * no row has been persisted yet (inherited = false because it IS the default tier).
     */
    public NotificationSettingsResponse getOrgSettings(UUID orgId) {
        return settingsRepository.findByOrganizationIdAndTargetIsNull(orgId)
                .map(ns -> toResponse(ns, false))
                .orElseGet(() -> appYmlFallbackResponse());
    }

    /**
     * Upserts the org-default row. Creates it on first PUT; updates on subsequent PUTs.
     */
    @Transactional
    public NotificationSettingsResponse upsertOrgSettings(UUID orgId, NotificationSettingsRequest req) {
        validate(req);
        Organization org = orgRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));

        NotificationSettings ns = settingsRepository
                .findByOrganizationIdAndTargetIsNull(orgId)
                .orElse(NotificationSettings.builder().organization(org).build());

        apply(ns, req);
        ns = settingsRepository.save(ns);
        log.info("Org notification settings upserted for org {}", orgId);
        return toResponse(ns, false);
    }

    // ── Per-target override ───────────────────────────────────────────────────

    /**
     * Returns the per-target override if one exists, otherwise returns the effective
     * value (org default → app.yml fallback) with {@code inherited = true}.
     */
    public NotificationSettingsResponse getTargetSettings(UUID orgId, UUID targetId) {
        assertTargetBelongsToOrg(orgId, targetId);
        return settingsRepository.findByTargetId(targetId)
                .map(ns -> toResponse(ns, false))
                .orElseGet(() -> effectiveForTarget(orgId));
    }

    /**
     * Upserts the per-target override. Creates it on first PUT; updates on subsequent PUTs.
     */
    @Transactional
    public NotificationSettingsResponse upsertTargetSettings(UUID orgId, UUID targetId,
                                                              NotificationSettingsRequest req) {
        validate(req);
        Target target = targetRepository.findByIdAndOrganizationId(targetId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Target not found"));

        NotificationSettings ns = settingsRepository.findByTargetId(targetId)
                .orElse(NotificationSettings.builder()
                        .organization(target.getOrganization())
                        .target(target)
                        .build());

        apply(ns, req);
        ns = settingsRepository.save(ns);
        log.info("Per-target notification settings upserted for target {}", targetId);
        return toResponse(ns, false);
    }

    /**
     * Deletes the per-target override, reverting to the org default (or app.yml fallback).
     * Idempotent: if no override exists, does nothing.
     */
    @Transactional
    public void deleteTargetSettings(UUID orgId, UUID targetId) {
        assertTargetBelongsToOrg(orgId, targetId);
        settingsRepository.findByTargetId(targetId).ifPresent(ns -> {
            settingsRepository.delete(ns);
            log.info("Per-target notification settings cleared for target {}", targetId);
        });
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void validate(NotificationSettingsRequest req) {
        if (req.getCriticalDays() >= req.getWarningDays()) {
            throw new IllegalArgumentException(
                "criticalDays (" + req.getCriticalDays() + ") must be less than " +
                "warningDays (" + req.getWarningDays() + ")");
        }
    }

    private void apply(NotificationSettings ns, NotificationSettingsRequest req) {
        ns.setEnabled(req.getEnabled());
        ns.setWarningDays(req.getWarningDays());
        ns.setCriticalDays(req.getCriticalDays());
        ns.setDedupHours(req.getDedupHours());
        // RFC 0009: optional revocation fields — only update when explicitly provided
        if (req.getRevocationCheckEnabled() != null) {
            ns.setRevocationCheckEnabled(req.getRevocationCheckEnabled());
        }
        if (req.getRevocationFailMode() != null) {
            com.certguard.enums.RevocationFailMode mode;
            try {
                mode = com.certguard.enums.RevocationFailMode.valueOf(req.getRevocationFailMode().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                    "Invalid revocationFailMode '" + req.getRevocationFailMode() + "'; expected SOFT or HARD");
            }
            ns.setRevocationFailMode(mode);
        }
        if (req.getAlertOnUntrustedChain() != null) {
            ns.setAlertOnUntrustedChain(req.getAlertOnUntrustedChain());
        }
    }

    private NotificationSettingsResponse toResponse(NotificationSettings ns, boolean inherited) {
        // Determine revocation fields with safe defaults if the entity columns are null
        // (existing rows pre-V35 will have null until updated).
        boolean revCheckEnabled = ns.getRevocationCheckEnabled() != null
                ? ns.getRevocationCheckEnabled() : defaultRevocationCheckEnabled;
        String revFailMode = ns.getRevocationFailMode() != null
                ? ns.getRevocationFailMode().name() : defaultRevocationFailMode;
        boolean alertUntrusted = ns.getAlertOnUntrustedChain() != null
                ? ns.getAlertOnUntrustedChain() : defaultAlertOnUntrustedChain;

        return NotificationSettingsResponse.builder()
                .id(ns.getId())
                .enabled(ns.getEnabled())
                .warningDays(ns.getWarningDays())
                .criticalDays(ns.getCriticalDays())
                .dedupHours(ns.getDedupHours())
                .inherited(inherited)
                .revocationCheckEnabled(revCheckEnabled)
                .revocationFailMode(revFailMode)
                .alertOnUntrustedChain(alertUntrusted)
                .build();
    }

    /** Effective value for a target that has no override — walks up the resolution chain. */
    private NotificationSettingsResponse effectiveForTarget(UUID orgId) {
        return settingsRepository.findByOrganizationIdAndTargetIsNull(orgId)
                .map(ns -> toResponse(ns, /* inherited= */ true))
                .orElseGet(() -> {
                    NotificationSettingsResponse r = appYmlFallbackResponse();
                    // Re-stamp inherited=true since it's from app defaults (fallback defaults inherited=false).
                    return NotificationSettingsResponse.builder()
                            .id(r.getId())
                            .enabled(r.isEnabled())
                            .warningDays(r.getWarningDays())
                            .criticalDays(r.getCriticalDays())
                            .dedupHours(r.getDedupHours())
                            .inherited(true)
                            .revocationCheckEnabled(r.isRevocationCheckEnabled())
                            .revocationFailMode(r.getRevocationFailMode())
                            .alertOnUntrustedChain(r.isAlertOnUntrustedChain())
                            .build();
                });
    }

    private NotificationSettingsResponse appYmlFallbackResponse() {
        return NotificationSettingsResponse.builder()
                .id(null)
                .enabled(true)
                .warningDays(defaultWarningDays)
                .criticalDays(defaultCriticalDays)
                .dedupHours(defaultDedupHours)
                .inherited(false)
                .revocationCheckEnabled(defaultRevocationCheckEnabled)
                .revocationFailMode(defaultRevocationFailMode)
                .alertOnUntrustedChain(defaultAlertOnUntrustedChain)
                .build();
    }

    private void assertTargetBelongsToOrg(UUID orgId, UUID targetId) {
        if (!targetRepository.findByIdAndOrganizationId(targetId, orgId).isPresent()) {
            throw new ResourceNotFoundException("Target not found: " + targetId);
        }
    }
}
