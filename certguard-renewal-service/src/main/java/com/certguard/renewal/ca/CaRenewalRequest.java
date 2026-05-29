package com.certguard.renewal.ca;

import java.util.List;
import java.util.UUID;

public record CaRenewalRequest(
        String csrPem,
        String commonName,
        List<String> sans,
        UUID orgId,
        int validityDays
) {}
