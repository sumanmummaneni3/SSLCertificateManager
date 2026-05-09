package com.certguard.security;

import com.certguard.repository.OrganizationRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final OrganizationRepository organizationRepository;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {

        String token = extractToken(request);

        if (StringUtils.hasText(token) && jwtTokenProvider.validateToken(token)) {
            try {
                Claims claims = jwtTokenProvider.parseToken(token);
                UUID userId = UUID.fromString(claims.getSubject());
                UUID orgId  = UUID.fromString(claims.get("orgId", String.class));
                String email = claims.get("email", String.class);

                // Resolve role — prefer new claims, fall back to legacy `role` claim
                Boolean platformAdminClaim = claims.get("platformAdmin", Boolean.class);
                String orgRoleClaim        = claims.get("orgRole",        String.class);
                String legacyRole          = claims.get("role",           String.class);

                boolean platformAdmin;
                String orgRole;
                if (platformAdminClaim != null) {
                    platformAdmin = platformAdminClaim;
                    orgRole       = orgRoleClaim;
                } else {
                    // Legacy token — derive from old `role` claim
                    platformAdmin = "PLATFORM_ADMIN".equals(legacyRole);
                    orgRole       = platformAdmin ? null : legacyRole;
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
                    UUID targetOrgId = UUID.fromString(actingAsHeader);
                    if (!organizationRepository.existsById(targetOrgId)) {
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
                }

                CertGuardUserPrincipal principal =
                        new CertGuardUserPrincipal(userId, effectiveOrgId, email, platformAdmin, orgRole);

                TenantContext.setOrgId(effectiveOrgId);
                TenantContext.setUserId(userId);
                MDC.put("userId", userId.toString());

                var auth = new UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities());
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (Exception e) {
                log.warn("Could not set authentication from JWT: {}", e.getMessage());
            }
        }

        try {
            chain.doFilter(request, response);
        } finally {
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
