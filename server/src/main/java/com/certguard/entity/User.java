package com.certguard.entity;

import com.certguard.enums.UserRole;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "users")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class User extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "org_id", nullable = false)
    private Organization organization;

    @Column(nullable = false, unique = true)
    private String email;

    private String name;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "user_role")
    @Builder.Default
    private UserRole role = UserRole.MEMBER;

    @Column(name = "google_sub", unique = true)
    private String googleSub;

    // ── V22: Onboarding tracking ──────────────────────────────────────────
    @Column(name = "onboarding_completed_at")
    private Instant onboardingCompletedAt;
}
