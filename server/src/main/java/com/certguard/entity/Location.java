package com.certguard.entity;

import com.certguard.enums.LocationProvider;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.HashMap;
import java.util.Map;

@Entity @Table(name = "locations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Location extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "org_id", nullable = false)
    private Organization organization;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "location_provider")
    private LocationProvider provider;

    /** Free-text geographic region, e.g. "Asia Pacific" */
    @Column(name = "geo_region", length = 100)
    private String geoRegion;

    /** Provider-specific region code, e.g. "ap-southeast-2", "australiaeast" */
    @Column(name = "cloud_region", length = 100)
    private String cloudRegion;

    /** Physical address — required for ON_PREM and COLOCATION */
    @Column(length = 500)
    private String address;

    /** Arbitrary user-defined key-value metadata */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "custom_fields", columnDefinition = "jsonb")
    @Builder.Default
    private Map<String, String> customFields = new HashMap<>();
}
