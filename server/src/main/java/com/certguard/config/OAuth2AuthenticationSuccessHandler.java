package com.certguard.config;

import com.certguard.entity.Organization;
import com.certguard.entity.OrgMember;
import com.certguard.entity.Subscription;
import com.certguard.entity.User;
import com.certguard.enums.InviteStatus;
import com.certguard.enums.OrgMemberRole;
import com.certguard.enums.SubscriptionStatus;
import com.certguard.enums.UserRole;
import com.certguard.repository.OrganizationRepository;
import com.certguard.repository.OrgMemberRepository;
import com.certguard.repository.SubscriptionRepository;
import com.certguard.repository.UserRepository;
import com.certguard.security.JwtTokenProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class OAuth2AuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;
    private final OrganizationRepository orgRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final OrgMemberRepository orgMemberRepository;

    @Value("${app.base-url:http://localhost:3000}")
    private String baseUrl;

    @Value("${app.platform-admin.emails:}")
    private List<String> platformAdminEmails;

    @Override
    @Transactional
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {

        Object principal = authentication.getPrincipal();

        String email, name, sub;
        if (principal instanceof OidcUser oidcUser) {
            email = oidcUser.getEmail();
            name  = oidcUser.getFullName();
            sub   = oidcUser.getSubject();
        } else if (principal instanceof OAuth2User oauth2User) {
            email = oauth2User.getAttribute("email");
            name  = oauth2User.getAttribute("name");
            sub   = oauth2User.getAttribute("sub") != null
                    ? oauth2User.getAttribute("sub")
                    : oauth2User.getAttribute("id");
            if (sub == null) sub = email;
        } else {
            log.error("Unexpected principal type: {}", principal.getClass().getName());
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unrecognised principal type");
            return;
        }

        if (email == null || email.isBlank()) {
            log.error("OAuth2 login failed — email attribute missing from principal");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Email not provided by Google");
            return;
        }

        boolean isPlatformAdmin = platformAdminEmails.contains(email);
        final String finalEmail = email;
        final String finalName  = name != null ? name : email;
        final String finalSub   = sub;

        AtomicBoolean isNewUser = new AtomicBoolean(false);

        User user = userRepository.findByEmail(finalEmail).orElseGet(() -> {
            isNewUser.set(true);

            if (isPlatformAdmin) {
                Organization adminOrg = orgRepository.findBySlug("__platform_admin__")
                        .orElseGet(() -> {
                            Organization o = Organization.builder()
                                    .name("__platform_admin__")
                                    .slug("__platform_admin__")
                                    .contactEmail(finalEmail)
                                    .build();
                            orgRepository.save(o);
                            subscriptionRepository.save(Subscription.builder()
                                    .organization(o).maxCertificateQuota(0)
                                    .status(SubscriptionStatus.ACTIVE).build());
                            return o;
                        });
                log.info("Bootstrap: created PLATFORM_ADMIN user for {}", finalEmail);
                return userRepository.save(User.builder()
                        .organization(adminOrg).email(finalEmail).name(finalName)
                        .role(UserRole.PLATFORM_ADMIN).googleSub(finalSub).build());
            }

            String orgSlug = finalEmail.split("@")[0].toLowerCase()
                    .replaceAll("[^a-z0-9]", "-") + "-" + UUID.randomUUID().toString().substring(0, 8);
            Organization org = Organization.builder()
                    .name(finalEmail.split("@")[0] + "'s Org")
                    .slug(orgSlug)
                    .contactEmail(finalEmail)
                    .build();
            orgRepository.save(org);
            subscriptionRepository.save(Subscription.builder()
                    .organization(org).maxCertificateQuota(10)
                    .status(SubscriptionStatus.TRIAL).build());
            log.info("Auto-provisioned new org '{}' for {}", org.getName(), finalEmail);
            User newUser = userRepository.save(User.builder()
                    .organization(org).email(finalEmail).name(finalName)
                    .role(UserRole.ADMIN).googleSub(finalSub).build());
            // Create OrgMember row so the JWT carries the org-scoped role
            orgMemberRepository.save(OrgMember.builder()
                    .organization(org).user(newUser)
                    .role(OrgMemberRole.ADMIN)
                    .inviteStatus(InviteStatus.ACCEPTED)
                    .build());
            return newUser;
        });

        // Keep platform-admin flag in sync with the allowlist
        UserRole expectedRole = isPlatformAdmin ? UserRole.PLATFORM_ADMIN : user.getRole();
        if (user.getRole() != expectedRole) {
            user.setRole(expectedRole);
            userRepository.save(user);
            log.info("Role updated to {} for {}", expectedRole, email);
        }

        // Resolve org-scoped role from OrgMember (null for platform admins)
        String orgRole = null;
        if (!isPlatformAdmin) {
            orgRole = orgMemberRepository
                    .findByOrganizationIdAndUserId(user.getOrganization().getId(), user.getId())
                    .map(m -> m.getRole().name())
                    .orElse("ADMIN");
        }

        String token = jwtTokenProvider.generateToken(
                user.getId(), user.getOrganization().getId(), user.getEmail(),
                isPlatformAdmin, orgRole);

        String redirect = baseUrl + "/?token=" + token + (isNewUser.get() ? "&newUser=true" : "");
        getRedirectStrategy().sendRedirect(request, response, redirect);
    }
}
