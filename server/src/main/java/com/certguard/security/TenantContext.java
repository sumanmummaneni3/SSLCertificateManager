package com.certguard.security;

import java.util.UUID;

public class TenantContext {
    private static final ThreadLocal<UUID> ORG_ID = new ThreadLocal<>();
    private static final ThreadLocal<UUID> USER_ID = new ThreadLocal<>();

    public static void setOrgId(UUID orgId)  { ORG_ID.set(orgId); }
    public static UUID  getOrgId()           { return ORG_ID.get(); }
    public static void setUserId(UUID userId){ USER_ID.set(userId); }
    public static UUID  getUserId()          { return USER_ID.get(); }
    public static void clear()               { ORG_ID.remove(); USER_ID.remove(); }
}
