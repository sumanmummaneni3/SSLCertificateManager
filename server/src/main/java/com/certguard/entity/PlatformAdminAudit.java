package com.certguard.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Audit record written whenever a PLATFORM_ADMIN uses X-Acting-As-Org impersonation.
 * No BaseEntity because this table has no updated_at and its created_at is DB-managed.
 */
@Entity
@Table(name = "platform_admin_audit")
public class PlatformAdminAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "acting_user_id", nullable = false)
    private UUID actingUserId;

    @Column(name = "acting_user_email", nullable = false, length = 255)
    private String actingUserEmail;

    @Column(name = "target_org_id", nullable = false)
    private UUID targetOrgId;

    @Column(name = "target_org_name", length = 255)
    private String targetOrgName;

    @Column(name = "http_method", nullable = false, length = 10)
    private String httpMethod;

    @Column(name = "request_path", nullable = false, length = 1024)
    private String requestPath;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    protected PlatformAdminAudit() {}

    private PlatformAdminAudit(UUID actingUserId, String actingUserEmail,
                                UUID targetOrgId, String targetOrgName,
                                String httpMethod, String requestPath,
                                String reason, Integer responseStatus) {
        this.actingUserId    = actingUserId;
        this.actingUserEmail = actingUserEmail;
        this.targetOrgId     = targetOrgId;
        this.targetOrgName   = targetOrgName;
        this.httpMethod      = httpMethod;
        this.requestPath     = requestPath;
        this.reason          = reason;
        this.responseStatus  = responseStatus;
        this.createdAt       = Instant.now();
    }

    public static PlatformAdminAudit of(UUID actingUserId, String actingUserEmail,
                                         UUID targetOrgId, String targetOrgName,
                                         String httpMethod, String requestPath,
                                         String reason, Integer responseStatus) {
        return new PlatformAdminAudit(actingUserId, actingUserEmail,
                targetOrgId, targetOrgName, httpMethod, requestPath, reason, responseStatus);
    }

    public UUID getId()                { return id; }
    public UUID getActingUserId()      { return actingUserId; }
    public String getActingUserEmail() { return actingUserEmail; }
    public UUID getTargetOrgId()       { return targetOrgId; }
    public String getTargetOrgName()   { return targetOrgName; }
    public String getHttpMethod()      { return httpMethod; }
    public String getRequestPath()     { return requestPath; }
    public String getReason()          { return reason; }
    public Integer getResponseStatus() { return responseStatus; }
    public Instant getCreatedAt()      { return createdAt; }

    public void setResponseStatus(Integer responseStatus) { this.responseStatus = responseStatus; }
}
