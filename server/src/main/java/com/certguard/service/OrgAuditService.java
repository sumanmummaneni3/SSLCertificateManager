package com.certguard.service;

import com.certguard.entity.OrgAudit;
import com.certguard.repository.OrgAuditRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrgAuditService {

    private final OrgAuditRepository auditRepository;

    @Async
    @Transactional
    public void recordAsync(UUID orgId,
                             UUID actorUserId,
                             String actorEmail,
                             String action,
                             UUID targetUserId,
                             String targetEmail,
                             String reason) {
        try {
            auditRepository.save(OrgAudit.of(
                    orgId, actorUserId, actorEmail,
                    action, targetUserId, targetEmail, reason));
        } catch (Exception ex) {
            log.error("Failed to write org audit row for org={} actor={} action={}: {}",
                    orgId, actorUserId, action, ex.getMessage(), ex);
        }
    }
}
