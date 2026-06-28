package com.certguard.repository;

import com.certguard.entity.DiscoveredEndpoint;
import com.certguard.enums.DeviceClass;
import com.certguard.enums.EndpointPortState;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface DiscoveredEndpointRepository extends JpaRepository<DiscoveredEndpoint, UUID> {

    Page<DiscoveredEndpoint> findByNetworkScan_Id(UUID scanId, Pageable pageable);

    Page<DiscoveredEndpoint> findByNetworkScan_IdAndState(UUID scanId, EndpointPortState state, Pageable pageable);

    Page<DiscoveredEndpoint> findByNetworkScan_IdAndDeviceClass(UUID scanId, DeviceClass deviceClass, Pageable pageable);
}
