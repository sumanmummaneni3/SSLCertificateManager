package com.certguard.auth.service;

import java.util.UUID;

public record OrgContextRecord(UUID userId, UUID orgId, String orgRole, boolean platformAdmin) {}
