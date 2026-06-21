package com.certguard.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Request body for PUT /api/v1/org/notification-settings
 * and PUT /api/v1/targets/{id}/notification-settings (RFC 0008 §3.4).
 *
 * <p>RFC 0009: extended with revocation policy fields. The three revocation fields
 * are optional (null = keep existing / use defaults) so existing clients continue
 * to work without changes.
 *
 * Cross-field validation (criticalDays < warningDays) is performed in the
 * service layer so it routes through the existing GlobalExceptionHandler
 * IllegalArgumentException → 400 ProblemDetail mapping.
 */
@Data
public class NotificationSettingsRequest {

    @NotNull
    private Boolean enabled;

    /** Certificates expiring within this many days enter the alert window. */
    @NotNull
    @Min(1)
    private Integer warningDays;

    /** Certificates expiring within this many days are rated CRITICAL. */
    @NotNull
    @Min(1)
    private Integer criticalDays;

    /** Minimum hours between repeat alerts for the same cert. */
    @NotNull
    @Min(1)
    private Integer dedupHours;

    // ── RFC 0009: Revocation policy (optional — null = keep existing default) ──

    /** Master revocation-check switch. Null = keep existing (true by default). */
    private Boolean revocationCheckEnabled;

    /** Failure mode: "SOFT" or "HARD". Null = keep existing ("SOFT" by default). */
    private String revocationFailMode;

    /** Alert on untrusted chain for private targets. Null = keep existing (false by default). */
    private Boolean alertOnUntrustedChain;
}
