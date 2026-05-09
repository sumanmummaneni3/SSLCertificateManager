package com.certguard.controller;

import com.certguard.dto.request.CreateLocationRequest;
import com.certguard.dto.response.LocationResponse;
import com.certguard.security.TenantContext;
import com.certguard.service.LocationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(value = "/api/v1/locations", produces = "application/json")
@RequiredArgsConstructor
public class LocationController {

    private final LocationService locationService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER','VIEWER','PLATFORM_ADMIN')")
    public ResponseEntity<List<LocationResponse>> list() {
        return ResponseEntity.ok(locationService.listLocations(TenantContext.getOrgId()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER','VIEWER','PLATFORM_ADMIN')")
    public ResponseEntity<LocationResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(locationService.getLocation(TenantContext.getOrgId(), id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER','PLATFORM_ADMIN')")
    public ResponseEntity<LocationResponse> create(@Valid @RequestBody CreateLocationRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(locationService.createLocation(TenantContext.getOrgId(), req));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER','PLATFORM_ADMIN')")
    public ResponseEntity<LocationResponse> update(@PathVariable UUID id,
                                                    @Valid @RequestBody CreateLocationRequest req) {
        return ResponseEntity.ok(locationService.updateLocation(TenantContext.getOrgId(), id, req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER','PLATFORM_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        locationService.deleteLocation(TenantContext.getOrgId(), id);
        return ResponseEntity.noContent().build();
    }
}
