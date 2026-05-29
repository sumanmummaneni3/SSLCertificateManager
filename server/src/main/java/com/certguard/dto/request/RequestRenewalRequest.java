package com.certguard.dto.request;

public record RequestRenewalRequest(
        String caProvider,
        String targetInstallPath
) {}
