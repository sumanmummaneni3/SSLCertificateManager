package com.certguard.service;

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
import com.certguard.security.CertGuardUserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OAuth2UserService extends OidcUserService {

    private final UserRepository userRepository;
    private final OrganizationRepository orgRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final OrgMemberRepository orgMemberRepository;

    @Value("${app.platform-admin.emails:}")
    private List<String> platformAdminEmails;

    @Override
    @Transactional
    public OidcUser loadUser(OidcUserRequest request) throws OAuth2AuthenticationException {
        OidcUser oidcUser = super.loadUser(request);
        String email = oidcUser.getEmail();
        String sub   = oidcUser.getSubject();
        String name  = oidcUser.getFullName();

        boolean isPlatformAdmin = platformAdminEmails.contains(email);

        User user = userRepository.findByEmail(email).orElseGet(() -> {
            if (isPlatformAdmin) {
                Organization adminOrg = Organization.builder()
                        .name("__platform_admin__").slug("__platform_admin__").build();
                orgRepository.save(adminOrg);
                subscriptionRepository.save(Subscription.builder()
                        .organization(adminOrg).maxCertificateQuota(0)
                        .status(SubscriptionStatus.ACTIVE).build());
                log.info("Bootstrap: created PLATFORM_ADMIN user for {}", email);
                return userRepository.save(User.builder()
                        .organization(adminOrg).email(email).name(name)
                        .role(UserRole.PLATFORM_ADMIN).googleSub(sub).build());
            }

            Organization org = Organization.builder()
                    .name(email.split("@")[0] + "'s Org").slug(null).build();
            orgRepository.save(org);
            subscriptionRepository.save(Subscription.builder()
                    .organization(org).maxCertificateQuota(10)
                    .status(SubscriptionStatus.TRIAL).build());
            User newUser = userRepository.save(User.builder()
                    .organization(org).email(email).name(name)
                    .role(UserRole.ADMIN).googleSub(sub).build());
            orgMemberRepository.save(OrgMember.builder()
                    .organization(org).user(newUser)
                    .role(OrgMemberRole.ADMIN)
                    .inviteStatus(InviteStatus.ACCEPTED)
                    .build());
            return newUser;
        });

        UserRole expectedRole = isPlatformAdmin ? UserRole.PLATFORM_ADMIN : user.getRole();
        if (user.getRole() != expectedRole) {
            user.setRole(expectedRole);
            log.info("Role synced to {} for {}", expectedRole, email);
        }
        user.setGoogleSub(sub);
        if (name != null) user.setName(name);
        userRepository.save(user);

        // Resolve org-scoped role
        String orgRole = null;
        if (!isPlatformAdmin) {
            orgRole = orgMemberRepository
                    .findByOrganizationIdAndUserId(user.getOrganization().getId(), user.getId())
                    .map(m -> m.getRole().name())
                    .orElse("ADMIN");
        }

        return new CertGuardUserPrincipal(
                user.getId(), user.getOrganization().getId(), user.getEmail(),
                isPlatformAdmin, orgRole,
                oidcUser.getAttributes(), oidcUser.getIdToken(), oidcUser.getUserInfo());
    }
}
