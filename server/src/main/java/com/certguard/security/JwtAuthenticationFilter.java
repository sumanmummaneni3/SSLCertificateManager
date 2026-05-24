package com.certguard.security;

import com.certguard.repository.OrganizationRepository;
import com.certguard.service.PlatformAdminAuditService;
import com.certguard.service.TokenRevocationService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import com.certguard.enums.OrgType;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    /** HTTP methods that mutate state and require a reason when acting cross-org. */
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final JwtTokenProvider jwtTokenProvider;
    private final OrganizationRepository organizationRepository;
    private final PlatformAdminAuditService platformAdminAuditService;
    private final TokenRevocationService tokenRevocationService;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider,
                                    OrganizationRepository organizationRepository,
                                    PlatformAdminAuditService platformAdminAuditService,
                                    TokenRevocationService tokenRevocationService) {
        this.jwtTokenProvider         = jwtTokenProvider;
        this.organizationRepository   = organizationRepository;
        this.platformAdminAuditService = platformAdminAuditService;
        this.tokenRevocationService   = tokenRevocationService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {

        // Acting-as context captured for the audit row, populated when X-Acting-As-Org is used.
        UUID   auditActingUserId    = null;
        String auditActingUserEmail = null;
        UUID   auditTargetOrgId     = null;
        String auditTargetOrgName   = null;
        String auditReason          = null;
        boolean needsAudit          = false;

        // Try trusted X-CG-* headers injected by the gateway after RS256 JWT validation.
        // The gateway strips any client-supplied X-CG-* headers before injection, so these
        // are safe to trust for requests arriving through the gateway.
        String cgUserId        = request.getHeader("X-CG-User-Id");
        String cgOrgId         = request.getHeader("X-CG-Org-Id");
        String cgRole          = request.getHeader("X-CG-Role");
        String cgEmail         = request.getHeader("X-CG-Email");
        String cgPlatformAdmin = request.getHeader("X-CG-Platform-Admin");

        boolean authedViaGateway = StringUtils.hasText(cgUserId) && StringUtils.hasText(cgOrgId);

        // Fall back to direct JWT validation (local dev / legacy path).
        Claims claims = null;
        if (!authedViaGateway) {
            String token = extractToken(request);
            if (StringUtils.hasText(token) && jwtTokenProvider.validateToken(token)) {
                try { claims = jwtTokenProvider.parseToken(token); }
                catch (Exception e) { log.warn("Could not parse JWT: {}", e.getMessage()); }
            }
        }

        if (authedViaGateway || claims != null) {
            try {
                final UUID userId;
                final UUID orgId;
                final String email;
                final boolean platformAdmin;
                String orgRole;

                if (authedViaGateway) {
                    userId        = UUID.fromString(cgUserId);
                    orgId         = UUID.fromString(cgOrgId);
                    email         = cgEmail;
                    platformAdmin = Boolean.parseBoolean(cgPlatformAdmin);
                    orgRole       = cgRole;
                } else {
                    userId = UUID.fromString(claims.getSubject());
                    orgId  = UUID.fromString(claims.get("orgId", String.class));
                    email  = claims.get("email", String.class);

                    Boolean platformAdminClaim = claims.get("platformAdmin", Boolean.class);
                    String orgRoleClaim        = claims.get("orgRole",       String.class);
                    String legacyRole          = claims.get("role",          String.class);

                    if (platformAdminClaim != null) {
                        platformAdmin = platformAdminClaim;
                        orgRole       = orgRoleClaim;
                    } else {
                        platformAdmin = "PLATFORM_ADMIN".equals(legacyRole);
                        orgRole       = platformAdmin ? null : legacyRole;
                    }
                }

                // Reject revoked sessions before any further processing
                if (!platformAdmin && tokenRevocationService.isRevoked(userId, orgId)) {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write(
                            "{\"status\":401,\"title\":\"Unauthorized\"," +
                            "\"detail\":\"Your access to this organisation has been revoked\"}");
                    return;
                }

                UUID effectiveOrgId = orgId;

                // X-Acting-As-Org: platform admin org override (ADR-0007)
                String actingAsHeader = request.getHeader("X-Acting-As-Org");
                if (StringUtils.hasText(actingAsHeader)) {
                    if (!platformAdmin) {
                        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                        response.getWriter().write("{\"status\":403,\"title\":\"Forbidden\",\"detail\":\"X-Acting-As-Org is restricted to PLATFORM_ADMIN\"}");
                        return;
                    }

                    // For write operations, X-Acting-As-Reason is mandatory.
                    String reasonHeader = request.getHeader("X-Acting-As-Reason");
                    if (WRITE_METHODS.contains(request.getMethod().toUpperCase())
                            && !StringUtils.hasText(reasonHeader)) {
                        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                        response.getWriter().write(
                                "{\"status\":400,\"title\":\"Missing reason\"," +
                                "\"detail\":\"X-Acting-As-Reason header is required for write operations\"}");
                        return;
                    }

                    UUID targetOrgId = UUID.fromString(actingAsHeader);
                    var targetOrg = organizationRepository.findById(targetOrgId).orElse(null);
                    if (targetOrg == null) {
                        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                        response.getWriter().write("{\"status\":404,\"title\":\"Not Found\",\"detail\":\"Target organisation not found\"}");
                        return;
                    }
                    effectiveOrgId = targetOrgId;
                    // When acting as another org, synthesize ADMIN role so normal guards pass
                    orgRole = "ADMIN";
                    TenantContext.setHomeOrgId(orgId);
                    MDC.put("actingAsOrgId", targetOrgId.toString());
                    MDC.put("homeOrgId", orgId.toString());

                    // Capture context for async audit write after chain completes.
                    auditActingUserId    = userId;
                    auditActingUserEmail = email;
                    auditTargetOrgId     = targetOrgId;
                    auditTargetOrgName   = targetOrg.getName();
                    auditReason          = reasonHeader;
                    needsAudit           = true;
                }

                CertGuardUserPrincipal principal =
                        new CertGuardUserPrincipal(userId, effectiveOrgId, email, platformAdmin, orgRole);

                TenantContext.setOrgId(effectiveOrgId);
                TenantContext.setUserId(userId);

                final UUID resolvedOrgId = effectiveOrgId;
                List<UUID> accessibleOrgIds = new ArrayList<>();
                accessibleOrgIds.add(resolvedOrgId);
                organizationRepository.findById(resolvedOrgId).ifPresent(org -> {
                    if (org.getOrgType() == OrgType.MSP) {
                        accessibleOrgIds.addAll(
                                organizationRepository.findActiveChildIds(resolvedOrgId));
                    }
                });
                TenantContext.setAccessibleOrgIds(accessibleOrgIds);

                MDC.put("userId", userId.toString());

                var auth = new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities());
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception e) {
                log.warn("Could not set authentication: {}", e.getMessage());
            }
        }

        try {
            chain.doFilter(request, response);
        } finally {
            if (needsAudit) {
                int status = response.getStatus();
                platformAdminAuditService.recordAsync(
                        auditActingUserId, auditActingUserEmail,
                        auditTargetOrgId, auditTargetOrgName,
                        request.getMethod(), request.getRequestURI(),
                        auditReason, status);
            }

            TenantContext.clear();
            MDC.remove("userId");
            MDC.remove("actingAsOrgId");
            MDC.remove("homeOrgId");
        }
    }

    private String extractToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }
}
