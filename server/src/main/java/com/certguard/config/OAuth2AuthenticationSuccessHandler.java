package com.certguard.config;

import com.certguard.entity.Organization;
import com.certguard.entity.Subscription;
import com.certguard.entity.User;
import com.certguard.enums.SubscriptionStatus;
import com.certguard.enums.UserRole;
import com.certguard.repository.OrganizationRepository;
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

        // Track whether this is a brand-new user so the UI can show the onboarding screen
        AtomicBoolean isNewUser = new AtomicBoolean(false);

        User user = userRepository.findByEmail(finalEmail).orElseGet(() -> {
            isNewUser.set(true);

            if (isPlatformAdmin) {
                // Check if platform admin org already exists (idempotent)
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

            // Regular MSP org user — auto-provision org + subscription
            String orgSlug = finalEmail.split("@")[0].toLowerCase()
                    .replaceAll("[^a-z0-9]", "-") + "-" + UUID.randomUUID().toString().substring(0, 8);
            Organization org = Organization.builder()
                    .name(finalEmail.split("@")[0] + "'s Org")
                    .slug(orgSlug)
                    .contactEmail(finalEmail)   // ← populate from Google
                    .build();
            orgRepository.save(org);
            subscriptionRepository.save(Subscription.builder()
                    .organization(org).maxCertificateQuota(10)
                    .status(SubscriptionStatus.TRIAL).build());
            log.info("Auto-provisioned new org '{}' for {}", org.getName(), finalEmail);
            return userRepository.save(User.builder()
                    .organization(org).email(finalEmail).name(finalName)
                    .role(UserRole.ADMIN).googleSub(finalSub).build());
        });

        // Keep role in sync with the allowlist
        UserRole expectedRole = isPlatformAdmin ? UserRole.PLATFORM_ADMIN : user.getRole();
        if (user.getRole() != expectedRole) {
            user.setRole(expectedRole);
            userRepository.save(user);
            log.info("Role updated to {} for {}", expectedRole, email);
        }

        String token = jwtTokenProvider.generateToken(user);
        // Pass newUser=true so the UI shows the onboarding modal on first login
        String redirect = baseUrl + "/?token=" + token + (isNewUser.get() ? "&newUser=true" : "");
        getRedirectStrategy().sendRedirect(request, response, redirect);
    }
}
