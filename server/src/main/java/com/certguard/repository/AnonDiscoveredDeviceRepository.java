package com.certguard.repository;

import com.certguard.entity.AnonDiscoveredDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AnonDiscoveredDeviceRepository extends JpaRepository<AnonDiscoveredDevice, UUID> {

    List<AnonDiscoveredDevice> findBySession_Id(UUID sessionId);
}
