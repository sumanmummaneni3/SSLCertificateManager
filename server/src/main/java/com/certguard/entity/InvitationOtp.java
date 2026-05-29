package com.certguard.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistent OTP entry for the invitation accept flow.
 * Replaces the in-process ConcurrentHashMap in InvitationService.
 * The plaintext OTP is never stored — only the BCrypt hash.
 */
@Entity
@Table(name = "invitation_otp",
       indexes = {
           @Index(name = "idx_invitation_otp_email_org", columnList = "email, org_id"),
           @Index(name = "idx_invitation_otp_expires_at", columnList = "expires_at")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvitationOtp {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    /** Email address of the invited user. */
    @Column(nullable = false)
    private String email;

    /** Organisation this OTP belongs to (for tenant scoping). */
    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    /** BCrypt hash of the 6-digit OTP. */
    @Column(name = "otp_hash", nullable = false)
    private String otpHash;

    /** When this OTP entry expires. */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** When the most recent OTP email was sent. Used for resend cool-down. */
    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    /** Number of times an OTP email has been re-sent for this invite flow. */
    @Column(name = "resend_count", nullable = false)
    private int resendCount;

    /** Number of failed verification attempts. */
    @Column(nullable = false)
    private int attempts;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;
}
