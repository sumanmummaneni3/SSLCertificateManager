package com.certguard.repository;

import com.certguard.entity.OrgAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OrgAuditRepository extends JpaRepository<OrgAudit, UUID> {
}
