package com.certguard.service;

import com.certguard.dto.request.CreateLocationRequest;
import com.certguard.dto.response.LocationResponse;
import com.certguard.entity.Location;
import com.certguard.entity.Organization;
import com.certguard.exception.ResourceNotFoundException;
import com.certguard.repository.LocationRepository;
import com.certguard.repository.OrganizationRepository;
import com.certguard.repository.TargetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LocationService {

    private final LocationRepository locationRepository;
    private final OrganizationRepository orgRepository;
    private final TargetRepository targetRepository;

    public List<LocationResponse> listLocations(UUID orgId) {
        return locationRepository.findAllByOrganizationId(orgId)
                .stream().map(l -> toResponse(l, countTargets(l.getId()))).toList();
    }

    public LocationResponse getLocation(UUID orgId, UUID locationId) {
        Location loc = findForOrg(orgId, locationId);
        return toResponse(loc, countTargets(loc.getId()));
    }

    @Transactional
    public LocationResponse createLocation(UUID orgId, CreateLocationRequest req) {
        Organization org = orgRepository.findById(orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));

        Location loc = Location.builder()
                .organization(org)
                .name(req.getName())
                .provider(req.getProvider())
                .geoRegion(req.getGeoRegion())
                .cloudRegion(req.getCloudRegion())
                .address(req.getAddress())
                .customFields(req.getCustomFields() != null ? req.getCustomFields() : new java.util.HashMap<>())
                .build();

        return toResponse(locationRepository.save(loc), 0);
    }

    @Transactional
    public LocationResponse updateLocation(UUID orgId, UUID locationId, CreateLocationRequest req) {
        Location loc = findForOrg(orgId, locationId);
        if (req.getName() != null)        loc.setName(req.getName());
        if (req.getGeoRegion() != null)   loc.setGeoRegion(req.getGeoRegion());
        if (req.getCloudRegion() != null) loc.setCloudRegion(req.getCloudRegion());
        if (req.getAddress() != null)     loc.setAddress(req.getAddress());
        if (req.getCustomFields() != null) loc.setCustomFields(req.getCustomFields());
        return toResponse(locationRepository.save(loc), countTargets(loc.getId()));
    }

    @Transactional
    public void deleteLocation(UUID orgId, UUID locationId) {
        Location loc = findForOrg(orgId, locationId);
        long targetCount = countTargets(locationId);
        if (targetCount > 0) {
            throw new IllegalStateException(
                "Cannot delete location with " + targetCount + " target(s). Reassign or remove targets first.");
        }
        locationRepository.delete(loc);
    }

    private Location findForOrg(UUID orgId, UUID locationId) {
        return locationRepository.findByIdAndOrganizationId(locationId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("Location not found: " + locationId));
    }

    private long countTargets(UUID locationId) {
        return targetRepository.countByLocationId(locationId);
    }

    private LocationResponse toResponse(Location loc, long targetCount) {
        return LocationResponse.builder()
                .id(loc.getId())
                .orgId(loc.getOrganization().getId())
                .name(loc.getName())
                .provider(loc.getProvider())
                .geoRegion(loc.getGeoRegion())
                .cloudRegion(loc.getCloudRegion())
                .address(loc.getAddress())
                .customFields(loc.getCustomFields())
                .targetCount((int) targetCount)
                .createdAt(loc.getCreatedAt())
                .updatedAt(loc.getUpdatedAt())
                .build();
    }
}
