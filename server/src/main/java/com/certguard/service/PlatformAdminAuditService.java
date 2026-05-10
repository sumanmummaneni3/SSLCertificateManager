package com.certguard.service;

import com.certguard.entity.PlatformAdminAudit;
import com.certguard.repository.PlatformAdminAuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Persists platform-admin impersonation audit rows asynchronously so that
 * the filter thread is never blocked by a DB write.
 * Exceptions are swallowed (log-only) — audit failures must not fail requests.
 */
@Service
public class PlatformAdminAuditService {

    private static final Logger log = LoggerFactory.getLogger(PlatformAdminAuditService.class);

    private final PlatformAdminAuditRepository auditRepository;

    public PlatformAdminAuditService(PlatformAdminAuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    /**
     * Write one audit row. Called from JwtAuthenticationFilter after the filter chain
     * completes so that responseStatus is known. The @Async annotation places this work
     * on the certguard-async thread pool defined in AsyncConfig.
     */
    @Async
    @Transactional
    public void recordAsync(UUID actingUserId,
                             String actingUserEmail,
                             UUID targetOrgId,
                             String targetOrgName,
                             String httpMethod,
                             String requestPath,
                             String reason,
                             int responseStatus) {
        try {
            PlatformAdminAudit audit = PlatformAdminAudit.of(
                    actingUserId, actingUserEmail,
                    targetOrgId, targetOrgName,
                    httpMethod, requestPath,
                    reason, responseStatus);
            auditRepository.save(audit);
        } catch (Exception ex) {
            // Audit failure must never propagate to the caller.
            log.error("Failed to persist platform-admin audit row for user={} targetOrg={}: {}",
                    actingUserId, targetOrgId, ex.getMessage(), ex);
        }
    }
}
