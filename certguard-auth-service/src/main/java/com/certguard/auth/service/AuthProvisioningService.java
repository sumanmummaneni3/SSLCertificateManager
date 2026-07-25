package com.certguard.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class AuthProvisioningService {

    private final JdbcTemplate mainJdbc;
    private final List<String> platformAdminEmails;

    public AuthProvisioningService(
            @Qualifier("mainJdbcTemplate") JdbcTemplate mainJdbc,
            @Value("${auth.platform-admin.emails:}") String csv) {
        this.mainJdbc = mainJdbc;
        this.platformAdminEmails = Arrays.stream(csv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** Least-privilege role used when the user has no resolvable membership anywhere. Never ADMIN. */
    private static final String NO_MEMBERSHIP_ROLE = "VIEWER";

    /**
     * Looks up the user by email in the main certguard DB.
     * If the user doesn't exist yet, auto-provisions org + subscription + user + org_member.
     * Returns the org context to embed in the JWT.
     *
     * RFC 0015 Phase 1: mirrors {@code ActiveOrgResolver} in the server so both JWT-issuance
     * paths (server-local HS256, this RS256 path) agree on the same resolution order:
     *   1. users.last_active_org_id, if it still points at a valid (ACCEPTED, non-revoked) membership.
     *   2. users.org_id (home org), if valid.
     *   3. The user's most-recently-created valid membership across all orgs (created_at DESC).
     *   4. No valid membership anywhere — home org + least-privilege role. Never ADMIN.
     * The resolved org is written back to users.last_active_org_id when it changes ("sticky").
     */
    public OrgContextRecord resolveOrProvision(String email, String name) {
        List<Map<String, Object>> rows = mainJdbc.queryForList(
                "SELECT id, org_id, role, last_active_org_id FROM users WHERE email = ?", email);

        if (!rows.isEmpty()) {
            Map<String, Object> row = rows.get(0);
            UUID userId = (UUID) row.get("id");
            UUID homeOrgId = (UUID) row.get("org_id");
            String role = (String) row.get("role");
            UUID lastActiveOrgId = (UUID) row.get("last_active_org_id");
            boolean isPlatformAdmin = "PLATFORM_ADMIN".equals(role) || platformAdminEmails.contains(email);

            UUID orgId = homeOrgId;
            String orgRole = null;
            if (!isPlatformAdmin) {
                UUID resolvedOrgId = null;
                String resolvedRole = null;

                if (lastActiveOrgId != null) {
                    String r = validMembershipRole(lastActiveOrgId, userId);
                    if (r != null) {
                        resolvedOrgId = lastActiveOrgId;
                        resolvedRole = r;
                    }
                }

                if (resolvedOrgId == null) {
                    String r = validMembershipRole(homeOrgId, userId);
                    if (r != null) {
                        resolvedOrgId = homeOrgId;
                        resolvedRole = r;
                    }
                }

                if (resolvedOrgId == null) {
                    List<Map<String, Object>> mostRecent = mainJdbc.queryForList(
                            "SELECT org_id, role FROM org_members " +
                            "WHERE user_id = ? AND invite_status = 'ACCEPTED' AND revoked_at IS NULL " +
                            "ORDER BY created_at DESC LIMIT 1",
                            userId);
                    if (!mostRecent.isEmpty()) {
                        resolvedOrgId = (UUID) mostRecent.get(0).get("org_id");
                        resolvedRole = (String) mostRecent.get(0).get("role");
                    }
                }

                if (resolvedOrgId == null) {
                    // No valid membership anywhere — never default to ADMIN.
                    resolvedOrgId = homeOrgId;
                    resolvedRole = NO_MEMBERSHIP_ROLE;
                }

                orgId = resolvedOrgId;
                orgRole = resolvedRole;

                if (!orgId.equals(lastActiveOrgId)) {
                    mainJdbc.update("UPDATE users SET last_active_org_id = ? WHERE id = ?", orgId, userId);
                }
            }

            return new OrgContextRecord(userId, orgId, orgRole, isPlatformAdmin);
        }

        boolean isPlatformAdmin = platformAdminEmails.contains(email);
        return isPlatformAdmin
                ? provisionPlatformAdmin(email, name)
                : provisionRegularUser(email, name);
    }

    /**
     * Returns the role of a valid (ACCEPTED, non-revoked) membership for (orgId, userId),
     * or null if no such membership exists.
     */
    private String validMembershipRole(UUID orgId, UUID userId) {
        List<Map<String, Object>> memberRows = mainJdbc.queryForList(
                "SELECT role FROM org_members " +
                "WHERE org_id = ? AND user_id = ? AND invite_status = 'ACCEPTED' AND revoked_at IS NULL",
                orgId, userId);
        return memberRows.isEmpty() ? null : (String) memberRows.get(0).get("role");
    }

    private OrgContextRecord provisionPlatformAdmin(String email, String name) {
        List<Map<String, Object>> orgRows = mainJdbc.queryForList(
                "SELECT id FROM organizations WHERE slug = '__platform_admin__'");

        UUID orgId;
        if (orgRows.isEmpty()) {
            orgId = UUID.randomUUID();
            mainJdbc.update(
                    "INSERT INTO organizations (id, name, slug, contact_email) VALUES (?, ?, ?, ?)",
                    orgId, "__platform_admin__", "__platform_admin__", email);
            mainJdbc.update(
                    "INSERT INTO subscriptions (id, org_id, max_certificate_quota, status) " +
                    "VALUES (?, ?, ?, CAST(? AS subscription_status))",
                    UUID.randomUUID(), orgId, 0, "ACTIVE");
        } else {
            orgId = (UUID) orgRows.get(0).get("id");
        }

        UUID userId = UUID.randomUUID();
        mainJdbc.update(
                "INSERT INTO users (id, org_id, email, name, role) " +
                "VALUES (?, ?, ?, ?, CAST(? AS user_role))",
                userId, orgId, email, name != null ? name : email, "PLATFORM_ADMIN");

        log.info("Provisioned PLATFORM_ADMIN user {} in org {}", email, orgId);
        return new OrgContextRecord(userId, orgId, null, true);
    }

    private OrgContextRecord provisionRegularUser(String email, String name) {
        String orgSlug = email.split("@")[0].toLowerCase()
                .replaceAll("[^a-z0-9]", "-")
                + "-" + UUID.randomUUID().toString().substring(0, 8);

        UUID orgId = UUID.randomUUID();
        mainJdbc.update(
                "INSERT INTO organizations (id, name, slug, contact_email) VALUES (?, ?, ?, ?)",
                orgId, email.split("@")[0] + "'s Org", orgSlug, email);
        mainJdbc.update(
                "INSERT INTO subscriptions (id, org_id, max_certificate_quota, status) " +
                "VALUES (?, ?, ?, CAST(? AS subscription_status))",
                UUID.randomUUID(), orgId, 10, "TRIAL");

        UUID userId = UUID.randomUUID();
        mainJdbc.update(
                "INSERT INTO users (id, org_id, email, name, role) " +
                "VALUES (?, ?, ?, ?, CAST(? AS user_role))",
                userId, orgId, email, name != null ? name : email, "ADMIN");
        mainJdbc.update(
                "INSERT INTO org_members (id, org_id, user_id, role, invite_status) " +
                "VALUES (?, ?, ?, CAST(? AS org_member_role), CAST(? AS invite_status))",
                UUID.randomUUID(), orgId, userId, "ADMIN", "ACCEPTED");

        log.info("Auto-provisioned org '{}' for new user {}", orgSlug, email);
        return new OrgContextRecord(userId, orgId, "ADMIN", false);
    }
}
