package com.certguard.renewal.web.dto;

import java.util.List;
import java.util.UUID;

public record CreateRenewalRequest(
        UUID orgId,
        UUID certId,
        UUID targetId,
        UUID agentId,
        UUID requestedBy,
        String caProvider,
        String targetInstallPath,
        String commonName,
        List<String> sans
) {}
