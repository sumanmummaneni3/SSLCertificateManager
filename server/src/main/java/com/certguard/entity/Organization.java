package com.certguard.entity;

import com.certguard.enums.OrgType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity @Table(name = "organizations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Organization extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(unique = true)
    private String slug;

    // ── V5: Org type & hierarchy ──────────────────────────────────────────
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "org_type", nullable = false, columnDefinition = "org_type")
    @Builder.Default
    private OrgType orgType = OrgType.SINGLE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_org_id")
    private Organization parentOrg;

    // ── V5: Contact profile ───────────────────────────────────────────────
    @Column(name = "address_line1", length = 255)
    private String addressLine1;

    @Column(name = "address_line2", length = 255)
    private String addressLine2;

    @Column(length = 100)
    private String city;

    @Column(name = "state_province", length = 100)
    private String stateProvince;

    @Column(name = "postal_code", length = 20)
    private String postalCode;

    @Column(length = 100)
    private String country;

    @Column(length = 50)
    private String phone;

    @Column(name = "contact_email", length = 255)
    private String contactEmail;
}
