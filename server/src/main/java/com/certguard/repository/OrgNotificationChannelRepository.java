package com.certguard.repository;

import com.certguard.entity.OrgNotificationChannel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OrgNotificationChannelRepository extends JpaRepository<OrgNotificationChannel, UUID> {

    List<OrgNotificationChannel> findByOrganizationIdAndEnabledTrue(UUID orgId);

    List<OrgNotificationChannel> findByOrganizationId(UUID orgId);
}
