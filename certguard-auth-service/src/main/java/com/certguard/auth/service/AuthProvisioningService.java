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

    /**
     * Looks up the user by email in the main certguard DB.
     * If the user doesn't exist yet, auto-provisions org + subscription + user + org_member.
     * Returns the org context to embed in the JWT.
     */
    public OrgContextRecord resolveOrProvision(String email, String name) {
        List<Map<String, Object>> rows = mainJdbc.queryForList(
                "SELECT id, org_id, role FROM users WHERE email = ?", email);

        if (!rows.isEmpty()) {
            Map<String, Object> row = rows.get(0);
            UUID userId = (UUID) row.get("id");
            UUID orgId  = (UUID) row.get("org_id");
            String role = (String) row.get("role");
            boolean isPlatformAdmin = "PLATFORM_ADMIN".equals(role) || platformAdminEmails.contains(email);

            String orgRole = null;
            if (!isPlatformAdmin) {
                List<Map<String, Object>> memberRows = mainJdbc.queryForList(
                        "SELECT role FROM org_members WHERE org_id = ? AND user_id = ? AND invite_status = 'ACCEPTED'",
                        orgId, userId);
                orgRole = memberRows.isEmpty() ? "ADMIN" : (String) memberRows.get(0).get("role");
            }

            return new OrgContextRecord(userId, orgId, orgRole, isPlatformAdmin);
        }

        boolean isPlatformAdmin = platformAdminEmails.contains(email);
        return isPlatformAdmin
                ? provisionPlatformAdmin(email, name)
                : provisionRegularUser(email, name);
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
