package com.certguard.dto.response;

import com.certguard.entity.Target;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class MspTargetRow {
    private UUID id;
    private UUID orgId;
    private String orgName;
    private String host;
    private int port;
    private boolean isPrivate;
    private boolean enabled;
    private Instant lastScannedAt;

    public static MspTargetRow fromEntity(Target t) {
        return MspTargetRow.builder()
                .id(t.getId())
                .orgId(t.getOrganization() != null ? t.getOrganization().getId() : null)
                .orgName(t.getOrganization() != null ? t.getOrganization().getName() : null)
                .host(t.getHost())
                .port(t.getPort())
                .isPrivate(Boolean.TRUE.equals(t.getIsPrivate()))
                .enabled(Boolean.TRUE.equals(t.getEnabled()))
                .lastScannedAt(t.getLastScannedAt())
                .build();
    }
}
