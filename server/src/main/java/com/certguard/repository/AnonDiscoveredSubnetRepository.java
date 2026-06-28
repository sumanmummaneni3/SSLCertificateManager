package com.certguard.repository;

import com.certguard.entity.AnonDiscoveredSubnet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface AnonDiscoveredSubnetRepository extends JpaRepository<AnonDiscoveredSubnet, UUID> {

    List<AnonDiscoveredSubnet> findBySession_Id(UUID sessionId);
}
