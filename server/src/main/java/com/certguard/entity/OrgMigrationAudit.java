package com.certguard.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Immutable audit record for an MSP→MSP organisation migration (RFC 0010).
 *
 * Modelled on {@link PlatformAdminAudit}: no BaseEntity, no updated_at,
 * DB-managed created_at, no update path. Append-only.
 *
 * {@code direction} is either {@code FORWARD} (transfer) or {@code REVERSE} (undo).
 * {@code revokedMemberIds} is a JSONB array of OrgMember UUIDs revoked on the FORWARD
 * move — stored so the REVERSE move can restore exactly those rows.
 */
@Entity
@Table(name = "org_migration_audit")
public class OrgMigrationAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(nullable = false, length = 16)
    private String direction;

    /** Set on REVERSE records; references the FORWARD row being undone. */
    @Column(name = "reverses_migration_id")
    private UUID reversesMigrationId;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "org_name", length = 255)
    private String orgName;

    @Column(name = "source_msp_org_id", nullable = false)
    private UUID sourceMspOrgId;

    @Column(name = "source_msp_name", length = 255)
    private String sourceMspName;

    @Column(name = "target_msp_org_id", nullable = false)
    private UUID targetMspOrgId;

    @Column(name = "target_msp_name", length = 255)
    private String targetMspName;

    @Column(name = "acting_user_id", nullable = false)
    private UUID actingUserId;

    @Column(name = "acting_user_email", nullable = false, length = 255)
    private String actingUserEmail;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(name = "reference_ticket", length = 255)
    private String referenceTicket;

    /**
     * JSONB array of OrgMember UUIDs revoked during FORWARD transfer.
     * Drives restore on REVERSE — must contain exactly those IDs.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "revoked_member_ids", columnDefinition = "jsonb")
    private List<UUID> revokedMemberIds;

    @Column(name = "revoked_member_count", nullable = false)
    private int revokedMemberCount;

    @Column(name = "in_flight_scan_job_count", nullable = false)
    private int inFlightScanJobCount;

    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    protected OrgMigrationAudit() {}

    private OrgMigrationAudit(Builder b) {
        this.direction            = b.direction;
        this.reversesMigrationId  = b.reversesMigrationId;
        this.orgId                = b.orgId;
        this.orgName              = b.orgName;
        this.sourceMspOrgId       = b.sourceMspOrgId;
        this.sourceMspName        = b.sourceMspName;
        this.targetMspOrgId       = b.targetMspOrgId;
        this.targetMspName        = b.targetMspName;
        this.actingUserId         = b.actingUserId;
        this.actingUserEmail      = b.actingUserEmail;
        this.reason               = b.reason;
        this.referenceTicket      = b.referenceTicket;
        this.revokedMemberIds     = b.revokedMemberIds;
        this.revokedMemberCount   = b.revokedMemberCount;
        this.inFlightScanJobCount = b.inFlightScanJobCount;
        this.createdAt            = Instant.now();
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String direction;
        private UUID   reversesMigrationId;
        private UUID   orgId;
        private String orgName;
        private UUID   sourceMspOrgId;
        private String sourceMspName;
        private UUID   targetMspOrgId;
        private String targetMspName;
        private UUID   actingUserId;
        private String actingUserEmail;
        private String reason;
        private String referenceTicket;
        private List<UUID> revokedMemberIds;
        private int    revokedMemberCount;
        private int    inFlightScanJobCount;

        private Builder() {}

        public Builder direction(String v)            { this.direction = v;            return this; }
        public Builder reversesMigrationId(UUID v)    { this.reversesMigrationId = v;  return this; }
        public Builder orgId(UUID v)                  { this.orgId = v;                return this; }
        public Builder orgName(String v)              { this.orgName = v;              return this; }
        public Builder sourceMspOrgId(UUID v)         { this.sourceMspOrgId = v;       return this; }
        public Builder sourceMspName(String v)        { this.sourceMspName = v;        return this; }
        public Builder targetMspOrgId(UUID v)         { this.targetMspOrgId = v;       return this; }
        public Builder targetMspName(String v)        { this.targetMspName = v;        return this; }
        public Builder actingUserId(UUID v)           { this.actingUserId = v;         return this; }
        public Builder actingUserEmail(String v)      { this.actingUserEmail = v;      return this; }
        public Builder reason(String v)               { this.reason = v;               return this; }
        public Builder referenceTicket(String v)      { this.referenceTicket = v;      return this; }
        public Builder revokedMemberIds(List<UUID> v) { this.revokedMemberIds = v;     return this; }
        public Builder revokedMemberCount(int v)      { this.revokedMemberCount = v;   return this; }
        public Builder inFlightScanJobCount(int v)    { this.inFlightScanJobCount = v; return this; }

        public OrgMigrationAudit build() { return new OrgMigrationAudit(this); }
    }

    // ── Accessors ──────────────────────────────────────────────────────────────

    public UUID       getId()                   { return id; }
    public String     getDirection()            { return direction; }
    public UUID       getReversesMigrationId()  { return reversesMigrationId; }
    public UUID       getOrgId()                { return orgId; }
    public String     getOrgName()              { return orgName; }
    public UUID       getSourceMspOrgId()       { return sourceMspOrgId; }
    public String     getSourceMspName()        { return sourceMspName; }
    public UUID       getTargetMspOrgId()       { return targetMspOrgId; }
    public String     getTargetMspName()        { return targetMspName; }
    public UUID       getActingUserId()         { return actingUserId; }
    public String     getActingUserEmail()      { return actingUserEmail; }
    public String     getReason()               { return reason; }
    public String     getReferenceTicket()      { return referenceTicket; }
    public List<UUID> getRevokedMemberIds()     { return revokedMemberIds; }
    public int        getRevokedMemberCount()   { return revokedMemberCount; }
    public int        getInFlightScanJobCount() { return inFlightScanJobCount; }
    public Instant    getCreatedAt()            { return createdAt; }
}
