package com.certguard.dto.response;

import lombok.Builder;
import lombok.Value;

import java.util.UUID;

/**
 * Response for GET/PUT /api/v1/org/notification-settings
 * and GET/PUT /api/v1/targets/{id}/notification-settings (RFC 0008 §3.4).
 *
 * <p>RFC 0009: extended with revocation policy fields.
 *
 * For per-target GET:
 *   - {@code inherited = false} → a persisted override row exists; fields reflect the override.
 *   - {@code inherited = true}  → no override row; fields reflect the effective value
 *     (org default or app.yml fallback). The frontend can use this to render
 *     "Inherited from org default" affordance.
 *
 * {@code id} is the UUID of the persisted row, or null when responding from the fallback.
 */
@Value
@Builder
public class NotificationSettingsResponse {

    UUID id;
    boolean enabled;
    int warningDays;
    int criticalDays;
    int dedupHours;

    /**
     * True when no persisted override exists for this scope (per-target GET only).
     * Always false for org-default GET/PUT responses.
     */
    boolean inherited;

    // ── RFC 0009: Revocation policy fields ────────────────────────────────────

    /**
     * Master revocation-check switch for the org.
     * Defaults to true; set false to opt out of CA endpoint contact.
     */
    boolean revocationCheckEnabled;

    /**
     * Failure mode when revocation is indeterminate: SOFT (default) or HARD.
     * HARD raises an advisory alert; never fabricates REVOKED.
     */
    String revocationFailMode;

    /**
     * Whether to raise chain advisory alerts for private targets (public always get advisory).
     */
    boolean alertOnUntrustedChain;
}
