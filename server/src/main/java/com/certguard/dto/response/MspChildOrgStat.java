package com.certguard.dto.response;

import java.util.UUID;

public record MspChildOrgStat(UUID orgId, String orgName, long targetCount) {
}
